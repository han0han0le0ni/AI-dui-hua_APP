package com.personal.roleplay.domain.context

import com.personal.roleplay.domain.model.AssembledContext
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatTurn
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.PromptBlock
import com.personal.roleplay.domain.model.SummarySegment
import com.personal.roleplay.domain.model.SpeakerLabels
import com.personal.roleplay.domain.model.WorldBookEntry

data class AssemblyParams(
    /** 本轮要扮演的角色。群聊里每一轮只挑一个，这是防串味的第一道闸 */
    val character: Character,
    /**
     * 同场但不参与本轮扮演的角色。
     *
     * 只给姓名 + 一句简介，**不给完整人设**：给多了模型容易顺手把别人的台词也写了。
     * 单人线传空列表。
     */
    val bystanders: List<Character> = emptyList(),
    /** 角色 id → 名字。群聊时用来给历史记录标发言人；单人线可为空 */
    val speakerNames: Map<String, String> = emptyMap(),
    val userPersona: String?,
    /** 人设库里的名字，用来点明「对方扮演的是谁」。为空则只给正文 */
    val userPersonaName: String? = null,
    /** L1 情景摘要分段 */
    val summarySegments: List<SummarySegment>,
    /** L2：TA 对你的认知 */
    val semanticMemories: List<MemoryItem>,
    /** L3：精挑后的情节记忆 */
    val episodicMemories: List<MemoryItem>,
    /** L4：当前心情 */
    val emotionMemories: List<MemoryItem>,
    val worldBookEntries: List<WorldBookEntry>,
    /** 最近若干轮原文，按时间正序 */
    val recentMessages: List<ChatMessage>,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    /**
     * 标注格式规则（动作、外貌、心理怎么标），来自全局设置「回复格式」。
     * 与扮演规则分开传：前者是用户偏好可随时改，后者是防串味的硬要求。
     */
    val replyFormatText: String = "",
    /**
     * 宏替换的确定性随机种子。
     *
     * 由调用方按「会话 + 轮次」算出来：同一轮重试必须给出同样的结果，
     * 否则每次重试都会换一份前缀，上游缓存全部作废。
     */
    val macroSeed: Long = 0L
) {
    val isGroup: Boolean get() = bystanders.isNotEmpty()
}

/**
 * 上下文组装器（11.1 / 11.2）。
 *
 * 静态区块（人设、人设锚定、世界、情感、语义、摘要、情节、约束）拼成一条 system 消息，
 * 动态区块（最近 N 轮原文）作为真实的多轮 messages。
 *
 * 这样排是为了命中上游 LLM 的前缀缓存：静态部分每次都一样且排在最前，
 * 省钱同时降低首字延迟。
 */
class ContextAssembler(
    private val budget: ContextBudget = ContextBudget.DEFAULT
) {

    fun assemble(params: AssemblyParams): AssembledContext {
        // 宏要先替换再算总预算：`{{char}}` 换成真名后长度会变，
        // 按占位符算出来的数字会让实际请求比预算更胖。
        // 单个区块的截断仍按替换前估算——宏通常是把占位符换成更短的名字，
        // 真超了由末尾的安全余量兜住。
        val macros = MacroResolver(
            charName = params.character.name,
            userName = params.userPersonaName?.takeIf { it.isNotBlank() } ?: SpeakerLabels.USER,
            seed = params.macroSeed
        )

        val blocks = buildStaticBlocks(params).map { resolveMacros(it, macros) }
        val staticTokens = blocks.sumOf { it.estimatedTokens }

        val available = params.contextWindow - params.maxOutputTokens - staticTokens - SAFETY_MARGIN
        val (turns, droppedOldTurns) = fillRecentTurns(
            messages = params.recentMessages,
            availableTokens = available.coerceAtLeast(0),
            params = params
        ).let { (filled, dropped) ->
            filled.map { it.copy(content = macros.resolve(it.content)) } to dropped
        }

        val turnTokens = turns.sumOf { TokenEstimator.estimate(it.content) + TURN_OVERHEAD }

        return AssembledContext(
            systemPrompt = macros.resolve(renderSystemPrompt(params.character, blocks)),
            blocks = blocks,
            turns = turns,
            estimatedTokens = staticTokens + turnTokens,
            compressionNeeded = staticTokens > params.contextWindow * budget.compressionThreshold,
            droppedOldTurns = droppedOldTurns
        )
    }

    /** 区块替换宏后重新估算 token；截断标记要带过去，调试面板靠它提示 */
    private fun resolveMacros(block: PromptBlock, macros: MacroResolver): PromptBlock {
        val resolved = macros.resolve(block.content)
        if (resolved == block.content) return block

        return PromptBlock(block.key, block.label, resolved).also {
            it.applyEstimated(TokenEstimator.estimate(resolved))
            if (block.truncated) it.markTruncated()
        }
    }

    private fun buildStaticBlocks(params: AssemblyParams): List<PromptBlock> {
        val blocks = mutableListOf<PromptBlock>()

        // L0 人设 + 示例对话：全量常驻，硬上限防止人设写太长
        blocks += fitText(
            key = KEY_PERSONA,
            label = LABEL_PERSONA,
            text = buildPersonaBlock(params.character, params.isGroup),
            maxTokens = budget.personaMax
        )

        // 群聊防串味第一道：同场角色只给一句辨识用简介，并明说不许代写
        if (params.bystanders.isNotEmpty()) {
            blocks += fitItems(
                key = KEY_CAST,
                label = LABEL_CAST,
                header = "## 同场角色（不是你，不要替他们说话）\n" +
                    "他们也在场，台词由他们自己负责，你一个字都不要替他们写：",
                items = params.bystanders.map { describeForCast(it) },
                maxTokens = budget.castMax
            )
        }

        // 对方是谁：填了才注入。
        // 措辞必须点明「这是别人」，否则模型会把这段当成自己的设定——
        // 系统提示里的「你」指的是模型正在演的角色，用「你在扮演谁」会被读反。
        params.userPersona
            ?.takeIf { it.isNotBlank() }
            ?.let { persona ->
                val who = params.userPersonaName?.takeIf { it.isNotBlank() }
                blocks += fitText(
                    key = KEY_USER_PERSONA,
                    label = LABEL_USER_PERSONA,
                    text = buildString {
                        appendLine("## 对方是谁（就是历史记录里的【用户】）")
                        if (who != null) appendLine("对方扮演的身份：$who")
                        appendLine("下面这段描述的是对方，**不是你**。不要照着它来说话，也不要把它当成你自己的经历：")
                        append(persona.trim())
                    },
                    maxTokens = budget.userPersonaMax
                )
            }

        // L5 世界书：按优先级逐条加，超预算截断低优先级
        if (params.worldBookEntries.isNotEmpty()) {
            blocks += fitItems(
                key = KEY_WORLD,
                label = LABEL_WORLD,
                header = "## 世界设定（本轮命中）",
                items = params.worldBookEntries.map { entry ->
                    val trigger = entry.keys.firstOrNull().orEmpty()
                    if (trigger.isBlank()) entry.content else "[$trigger] ${entry.content}"
                },
                maxTokens = budget.worldBookMax
            )
        }

        // L4 情感。注意是「你的」情绪：这是角色自己的状态，不是对方的
        if (params.emotionMemories.isNotEmpty()) {
            blocks += fitItems(
                key = KEY_EMOTION,
                label = LABEL_EMOTION,
                header = "## 你现在的情绪（你自己的状态）",
                items = params.emotionMemories.map { it.content },
                maxTokens = budget.emotionMax
            )
        }

        // L2 语义：你记得的关于对方的事，超出时按置信度截断（调用方已排序）
        if (params.semanticMemories.isNotEmpty()) {
            blocks += fitItems(
                key = KEY_SEMANTIC,
                label = LABEL_SEMANTIC,
                header = "## 你记得的关于用户的事（用户的情况，不是你的）",
                items = params.semanticMemories.map { it.content },
                maxTokens = budget.semanticMax
            )
        }

        // L1 情景摘要
        val summaryText = params.summarySegments
            .joinToString("\n") { it.text }
            .takeIf { it.isNotBlank() }
        if (summaryText != null) {
            blocks += fitText(
                key = KEY_SUMMARY,
                label = LABEL_SUMMARY,
                text = "## 最近发生了什么（你与用户的共同经历）\n$summaryText",
                maxTokens = budget.summaryMax
            )
        }

        // L3 情节：精挑后的少量条目
        if (params.episodicMemories.isNotEmpty()) {
            blocks += fitItems(
                key = KEY_EPISODIC,
                label = LABEL_EPISODIC,
                header = "## 你与用户之间的往事（值得想起来的具体场景）",
                items = params.episodicMemories.map { it.content },
                maxTokens = budget.episodicMax
            )
        }

        // 输出格式与约束：常驻
        blocks += fitText(
            key = KEY_CONSTRAINT,
            label = LABEL_CONSTRAINT,
            text = buildConstraintText(params),
            maxTokens = if (params.isGroup) budget.constraintMax + budget.groupRuleMax else budget.constraintMax
        )

        return blocks
    }

    /**
     * 「输出格式与约束」区块。
     *
     * 顺序是有意安排的：**防串味的规则必须排在最前**。
     * 这个区块超预算时由 fitText 从**尾部**裁剪，如果把「谁是谁」的说明写在后面，
     * 一超预算就先被砍掉，模型随即把别人的台词算到用户头上——这正是最难查的那类故障。
     * 用户可配的标注格式放最后：它被截断只是格式不理想，不会让归属出错。
     */
    private fun buildConstraintText(params: AssemblyParams): String = buildString {
        appendLine("## 回复要求")

        if (params.isGroup) {
            val name = params.character.name

            appendLine("- 本轮你只扮演「$name」一个人，只写 $name 说的话。")
            appendLine("- 每一行都带说话人标记，先认清标记再读内容：")
            appendLine("  - 「【$SELF_LABEL】：」= **你自己（$name）**，你以前说过的话；")
            appendLine("  - 「【$USER_LABEL】：」= 那个真人说的话；")
            appendLine("  - 「（某角色 说：…）」= 别的角色说的话；")
            appendLine("  - 「【$NARRATION_LABEL】：」= 场景描写，不是谁说的话。")
            appendLine("- 这些都是**正在进行的对话**，不是背景资料。接着最近的那句往下演：真人刚说了什么，或者上一位角色刚说了什么，你都要接得下去。")
            appendLine("- 但说话人不能接错：「（某角色 说：…）」里的内容**既不是真人说的，也不是你说过的**。不要把它算到真人头上、不要当成自己的经历，也不要替那个角色再讲一遍。")
            appendLine("- 直接写台词本体，**回复里一个标记都不要写**（不写「【$SELF_LABEL】：」、不写自己的名字、不写「（… 说：）」）。")
            appendLine("- 绝对不要替其他角色写台词、不要写他们的名字。")
        } else {
            appendLine("- 用第一人称扮演这个角色，不要跳出来说明自己是 AI 或模型。")
            appendLine("- 只写角色这一轮的话，不要替对方写台词、不要替对方做决定。")
        }

        // 只在真的注入了对方人设时才提醒，否则会指到一个不存在的区块
        if (!params.userPersona.isNullOrBlank()) {
            appendLine("- 「你是谁」那节是你自己；「对方是谁」那节说的是【用户】，两边的设定绝不能混用。")
        }

        appendLine("- 严格遵守人设里的语气、口头禅与禁忌。")
        appendLine("- 不要复述上一轮已经说过的内容，不要写总结式旁白。")
        appendLine("- 长度贴近真实对话，默认两三句，情绪激动时才展开。")

        params.replyFormatText.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { append(it) }
    }.trimEnd()

    /**
     * 同场角色的一行简介。
     *
     * 刻意带上「与对方的关系」：它决定这个角色会怎么接话，
     * 比完整人设更省 token，也更不容易让模型越位。
     */
    private fun describeForCast(character: Character): String = buildString {
        append(character.name)
        character.tagline?.takeIf { it.isNotBlank() }?.let { append("：${it}") }
        character.relationPosition
            ?.takeIf { it.isNotBlank() }
            ?.let { append("（与用户的关系：${it}）") }
    }

    private fun buildPersonaBlock(character: Character, isGroup: Boolean): String = buildString {
        // 点出名字并声明「这是你自己的设定」：后面还有一节专门描述对方，
        // 不点名的话模型会把两边搅在一起
        appendLine("## 你是谁")
        appendLine(
            if (isGroup) {
                "你叫「${character.name}」，本轮只演这一个人。下面是你自己的设定，不是别人的："
            } else {
                "你叫「${character.name}」。下面是你自己的设定："
            }
        )
        appendLine(character.persona.trim())
        if (character.personalityTags.isNotEmpty()) {
            appendLine("性格标签：${character.personalityTags.joinToString("、")}")
        }
        character.tone?.takeIf { it.isNotBlank() }?.let { appendLine("语气：$it") }
        character.catchphrase?.takeIf { it.isNotBlank() }?.let { appendLine("口头禅：$it") }
        character.taboos?.takeIf { it.isNotBlank() }?.let { appendLine("禁忌：$it") }
        character.relationPosition?.takeIf { it.isNotBlank() }?.let { appendLine("与用户的关系：$it") }

        character.exampleDialogues.takeIf { it.isNotBlank() }?.let { examples ->
            appendLine()
            appendLine("示例对话（用于把握说话方式，不要照抄）")
            append(examples.trim())
        }
    }.trimEnd()

    /**
     * 完全自定义模板：把各区块按 key 填进占位符。
     * 关掉开关时用上面的默认拼装顺序。
     */
    private fun renderSystemPrompt(character: Character, blocks: List<PromptBlock>): String {
        val template = character.systemPromptTemplate
            ?.takeIf { character.customSystemPrompt && it.isNotBlank() }
            ?: return blocks.joinToString("\n\n") { it.content }

        var rendered = template
        blocks.forEach { block ->
            rendered = rendered.replace("{{${block.key}}}", block.content)
        }
        return rendered
    }

    /** 文本类区块：超预算就按 token 反向裁剪 */
    private fun fitText(key: String, label: String, text: String, maxTokens: Int): PromptBlock {
        val total = TokenEstimator.estimate(text)
        val block = PromptBlock(key, label, text)
        if (total <= maxTokens) {
            block.applyEstimated(total)
            return block
        }

        var keep = (text.length * maxTokens / total).coerceAtMost(text.length)
        while (keep > 0 && TokenEstimator.estimate(text.substring(0, keep)) > maxTokens) {
            keep -= (keep / 20).coerceAtLeast(1)
        }

        val truncated = PromptBlock(key, label, text.substring(0, keep) + TRUNCATION_MARK)
        truncated.applyEstimated(TokenEstimator.estimate(truncated.content))
        truncated.markTruncated()
        return truncated
    }

    /**
     * 列表类区块：逐条累加，装不下就丢掉后面的。
     *
     * 按条截断而不是按字符截断——半句话进 prompt 比少一条记忆更伤。
     */
    private fun fitItems(
        key: String,
        label: String,
        header: String,
        items: List<String>,
        maxTokens: Int
    ): PromptBlock {
        val kept = mutableListOf<String>()
        var used = TokenEstimator.estimate(header)

        for (item in items) {
            val line = "- $item"
            val cost = TokenEstimator.estimate(line)
            if (used + cost > maxTokens) break
            kept += line
            used += cost
        }

        val truncated = kept.size < items.size
        val content = if (kept.isEmpty()) header else "$header\n${kept.joinToString("\n")}"

        val block = PromptBlock(key, label, content)
        block.applyEstimated(TokenEstimator.estimate(content))
        if (truncated) block.markTruncated()
        return block
    }

    /**
     * 从最新往回填「最近 N 轮原文」。
     *
     * 至少保留最新一条：空间再小也要让模型看到刚发生了什么，
     * 否则会生成一段莫名其妙的回复。
     */
    private fun fillRecentTurns(
        messages: List<ChatMessage>,
        availableTokens: Int,
        params: AssemblyParams
    ): Pair<List<ChatTurn>, Boolean> {
        if (messages.isEmpty()) return emptyList<ChatTurn>() to false

        val selected = mutableListOf<ChatMessage>()
        var used = 0
        var dropped = false

        for (message in messages.asReversed()) {
            val cost = TokenEstimator.estimate(message.content) + TURN_OVERHEAD
            if (selected.isNotEmpty() && used + cost > availableTokens) {
                dropped = true
                break
            }
            selected += message
            used += cost
        }

        return selected.asReversed().map { toTurn(it, params) } to dropped
    }

    /**
     * 把一条历史消息还原成模型能看懂的一轮。
     *
     * 单人线照搬角色即可。群聊的**每一行都带说话人标记**，不存在「裸行」：
     *
     * | 谁说的 | role | 标记 |
     * |---|---|---|
     * | 当前扮演者自己 | assistant | `【我】：` |
     * | 那个真人 | user | `【用户】：` |
     * | 场景描写 | user | `【旁白】：` |
     * | 别的角色 | user | `（名字 说：…）` |
     *
     * 别的角色的台词必须在 messages 里、按原顺序排着——**不能挪进 system 当「前情」**。
     * 试过：搬进 system 后归属确实不会错，但模型会把它当背景资料，后开口的角色
     * 接不上前面角色的话（「无法感知前几个角色的聊天」）。台词只有留在对话流里，
     * 模型才会把它当成正在发生的事来回应。
     *
     * 那为什么不用 `【名字】：` 而用 `（名字 说：…）`：`user` 槽位里既有真人又有别的角色，
     * 光靠方括号标记，弱模型会把上一位角色的话当成真人刚说的（实测过）。
     * 写成第三人称转述后，归属信息落在**句子里**——弱模型可以忽略一个标记符号，
     * 但没法忽略一句自然语言。
     *
     * 自己那行也带上 `【我】：`：模型不必再靠「这一行没有标记」反推「所以是我说的」，
     * 这种反推正是弱模型最容易失手的地方。
     */
    private fun toTurn(message: ChatMessage, params: AssemblyParams): ChatTurn {
        if (!params.isGroup) return ChatTurn(message.role, message.content)

        // 自己以前说过的话
        val isMyOwnLine = message.role == MessageRole.ASSISTANT &&
            message.speakerCharacterId == params.character.id
        if (isMyOwnLine) return ChatTurn(MessageRole.ASSISTANT, "【$SELF_LABEL】：${message.content}")

        // 旁白是场景描写、不属于任何人：留在 user 侧，模型才会把它当「眼前正在发生的事」来回应
        if (message.role == MessageRole.NARRATION) {
            return ChatTurn(MessageRole.USER, "【$NARRATION_LABEL】：${message.content}")
        }

        // 真人发言
        if (message.role == MessageRole.USER) {
            return ChatTurn(MessageRole.USER, "【$USER_LABEL】：${message.content}")
        }

        // 别的角色的台词：第三人称转述
        val label = message.speakerCharacterId
            ?.let { params.speakerNames[it] }
            ?: UNKNOWN_SPEAKER_LABEL
        return ChatTurn(MessageRole.USER, "（$label 说：${message.content}）")
    }

    private companion object {
        const val SAFETY_MARGIN = 200
        const val TURN_OVERHEAD = 8
        const val TRUNCATION_MARK = " …（已截断）"

        /**
         * 群聊历史里每句话的说话人标签。
         *
         * 词表在 [SpeakerLabels]：剥标签的 `ChatOrchestrator` 与做记忆抽取的 `MemoryPrompts`
         * 都要认同一份字面量，这里只是引入进来方便用。
         */
        const val USER_LABEL = SpeakerLabels.USER
        const val SELF_LABEL = SpeakerLabels.SELF
        const val NARRATION_LABEL = SpeakerLabels.NARRATION
        const val UNKNOWN_SPEAKER_LABEL = SpeakerLabels.UNKNOWN_SPEAKER

        const val KEY_PERSONA = "persona"
        const val KEY_CAST = "cast"
        const val KEY_USER_PERSONA = "user_persona"
        const val KEY_WORLD = "world"
        const val KEY_EMOTION = "emotion"
        const val KEY_SEMANTIC = "semantic"
        const val KEY_SUMMARY = "summary"
        const val KEY_EPISODIC = "episodic"
        /** 与 AssembledContext 共用同一个 key，避免两边字面量漂移 */
        const val KEY_CONSTRAINT = AssembledContext.CONSTRAINT_BLOCK_KEY

        const val LABEL_PERSONA = "L0 人设与示例对话"
        const val LABEL_CAST = "同场角色（群聊）"
        const val LABEL_USER_PERSONA = "对方是谁（用户人设）"
        const val LABEL_WORLD = "L5 世界书命中"
        const val LABEL_EMOTION = "L4 TA 现在的心情"
        const val LABEL_SEMANTIC = "L2 TA 知道的你"
        const val LABEL_SUMMARY = "L1 情景摘要"
        const val LABEL_EPISODIC = "L3 情节记忆（精挑）"
        const val LABEL_CONSTRAINT = "输出格式与约束"
    }
}
