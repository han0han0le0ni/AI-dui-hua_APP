package com.personal.roleplay.domain.memory

import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.SpeakerLabels

/**
 * 记忆引擎用到的三组提示词。
 *
 * 设计要点（8.2）：抽取时必须把「TA 已经记住了什么」一起发给模型。
 * 只有看到已有记忆，模型才能判断「我不吃辣」是对「我喜欢吃辣」的覆盖（UPDATE），
 * 而不是两条并存的矛盾事实。这类判断交给代码做很容易出错。
 */
object MemoryPrompts {

    fun buildExtractionPrompt(
        messages: List<ChatMessage>,
        existingMemories: List<MemoryItem>,
        /** 角色 id → 名字。多角色会话靠它标出每句话是谁说的 */
        speakerNames: Map<String, String> = emptyMap()
    ): String = buildString {
        appendLine(EXTRACTION_ROLE)
        appendLine()
        // 先把「谁是谁」钉死，再谈分层。歧义从这里开始，后面的规则都救不回来
        appendLine("## 术语（务必分清，这是最常见的出错点）")
        appendLine("- 每条原文前的中括号是编号，中括号后的【】里是说话人。")
        appendLine("- 【$USER_LABEL】= 具体那个真人，即正在和角色对话的那个人。")
        appendLine("- 【旁白】= 场景描写，不是任何人的台词。")
        appendLine("- 出现具体角色名（如【林深】）= 那个角色本人说的，**不是用户**。")
        appendLine()
        appendLine("## 分层规则（注意每层写的是**谁**的事）")
        appendLine("- L1 情景：最近发生了什么（滚动摘要，由压缩环节维护，这里一般不用）")
        appendLine("- L2 语义：角色对**$USER_LABEL**的稳定认知——用户自己的身份、喜好、习惯、痛点。")
        appendLine("  角色**自己**的身世、性格、口头禅不属于 L2，那本来就在人设里，不要记。")
        appendLine("  别的**角色**说过的话也不属于 L2，那是那个角色的事。")
        appendLine("- L3 情节：角色与**$USER_LABEL 之间**发生过的具体事件——第一次吵架、那场雨、那个承诺。")
        appendLine("- L4 情感：**角色自己**当前的心情、关系温度、未解心结。不是用户的心情。")
        appendLine()
        appendLine("## 判定要求")
        appendLine("- 新事实与已有记忆冲突时，用 UPDATE 覆盖，不要新增一条并列的事实。")
        appendLine("- 已有记忆被明确推翻时，用 DELETE。")
        appendLine("- 寒暄、客套、无信息量的重复内容不入库。")
        appendLine("- 绝大多数情况下都应该输出 NOOP，宁缺毋滥。")
        appendLine("- source_refs 填消息编号，必须真实出现在下面的原文里。")
        appendLine("- 先判断一条事实是**谁**说的、关于**谁**的，写进 content 时必须带上主语，")
        appendLine("  绝不能把两个主体的事混成一条。")
        if (speakerNames.size > 1) {
            appendLine("- 这是多人场景，可能有多个角色在场：写清是哪一位角色，不要把不同角色写成同一个人。")
        }
        appendLine()
        appendLine("## 已记住的事（编号 | 层 | 内容）")
        if (existingMemories.isEmpty()) {
            appendLine("（暂无）")
        } else {
            existingMemories.forEachIndexed { index, memory ->
                appendLine("${index + 1}. ${memory.id} | ${memory.layer.wire} | ${memory.content}")
            }
        }
        appendLine()
        appendLine("## 对话原文（方括号内为编号，【】内为说话人）")
        messages.forEach { message ->
            appendLine("[${message.seq}]【${speakerLabel(message, speakerNames)}】${message.content}")
        }
        appendLine()
        appendLine("## 输出格式")
        appendLine("只输出一个 JSON 数组，不要任何解释文字：")
        appendLine("""[{"op":"ADD","layer":"L2","content":"用户在准备考研，很焦虑","source_refs":[12]}]""")
        appendLine("""[{"op":"UPDATE","id":"<上面的记忆 id>","content":"用户不吃辣","source_refs":[15]}]""")
        appendLine("""[{"op":"DELETE","id":"<上面的记忆 id>","reason":"已被推翻"}]""")
        appendLine("""[{"op":"NOOP"}]""")
    }.trimEnd()

    /**
     * 查询重写提示词（8.3 的「精挑」环节）。
     *
     * 交给记忆抽取槽的小模型：给一批候选编号，让它只挑真正相关的。
     * 请求只处理一个短列表，token 消耗很小，比把候选全塞进主 prompt 便宜得多。
     */
    fun buildRerankPrompt(context: String, candidates: List<MemoryItem>): String = buildString {
        appendLine("你是检索重排器。判断下面的候选记忆里，哪些与「当前对话」真正相关。")
        appendLine()
        appendLine("## 当前对话")
        appendLine(context)
        appendLine()
        appendLine("## 候选记忆")
        candidates.forEachIndexed { index, memory ->
            appendLine("${index + 1}. ${memory.content}")
        }
        appendLine()
        appendLine("## 输出要求")
        appendLine("- 只输出最相关的记忆编号，用逗号分隔，最多 $MAX_RERANK_OUTPUT 个。")
        appendLine("- 一个都不相关就输出：0")
        appendLine("- 不要输出任何解释、标点以外的文字。")
    }.trimEnd()

    /** L1 滚动摘要压缩（11.2 的压缩触发） */
    fun buildSummaryPrompt(
        previousSummary: String?,
        newDialogue: String,
        /** 多人场景：摘要里必须写清是谁做的，否则下一个人会被当成同一个主体 */
        multiSpeaker: Boolean = false
    ): String = buildString {
        appendLine("你是对话摘要压缩器。把下面的对话压缩成一段紧凑的情景摘要。")
        appendLine()
        appendLine("## 要求")
        appendLine("- 用第三人称客观叙述，控制在 200 字以内。")
        if (multiSpeaker) {
            appendLine("- 这是多人场景：【】里是说话人。每条都写清是谁做的、对谁做的，不要混成一个人。")
        }
        appendLine("- 保留：发生了什么关键事件、双方的关系变化、未解决的悬念。")
        appendLine("- 丢弃：寒暄、重复表达、已经过去且不影响的细节。")
        appendLine("- 不要写评价，不要写「总之」这类总结腔。")
        if (!previousSummary.isNullOrBlank()) {
            appendLine()
            appendLine("## 上一段摘要（接续它往下写）")
            appendLine(previousSummary)
        }
        appendLine()
        appendLine("## 待压缩的对话")
        appendLine(newDialogue)
    }.trimEnd()

    /** 把最早的两段摘要再压成一段，避免摘要自身无限膨胀 */
    fun buildMergePrompt(first: String, second: String): String = buildString {
        appendLine("把下面两段摘要合并成一段，控制在 200 字以内，保留关键事件与未解决的悬念。")
        appendLine()
        appendLine("第一段：")
        appendLine(first)
        appendLine()
        appendLine("第二段：")
        appendLine(second)
    }.trimEnd()

    /**
     * 用户在这套提示词里的统一称谓。
     *
     * 直接引用 [SpeakerLabels]，与拼历史的 `ContextAssembler` 共用同一份字面量：
     * 抽取提示词里标错说话人，抽出来的记忆会长期把别人的事记成用户的。
     */
    const val USER_LABEL = SpeakerLabels.USER

    fun roleLabel(role: MessageRole): String = when (role) {
        MessageRole.USER -> USER_LABEL
        MessageRole.ASSISTANT -> "角色"
        MessageRole.NARRATION -> "旁白"
    }

    /**
     * 一条消息的说话人标签。
     *
     * 角色消息优先用真实名字——多人场景不写名字，抽取模型就分不清是谁说的；
     * 用户与旁白没有发言人，回落到固定称谓。
     */
    fun speakerLabel(message: ChatMessage, speakerNames: Map<String, String>): String =
        message.speakerCharacterId?.let { id -> speakerNames[id] } ?: roleLabel(message.role)

    const val MAX_RERANK_OUTPUT = 5

    // 用 val 而非 const val：.trimIndent() 是函数调用，不是编译期常量
    private val EXTRACTION_ROLE = """
        你是记忆整理器。下面是一段角色扮演对话的原文，以及这个角色目前已经记住的事。

        请对账后只输出操作指令，不要输出解释，不要复述原文。

        可选操作：
        - ADD：新事实。字段 op、layer（L1/L2/L3/L4）、content、source_refs
        - UPDATE：修正已有记忆。字段 op、id、content、source_refs
        - DELETE：已被推翻或不再成立。字段 op、id、reason
        - NOOP：无变化
    """.trimIndent()
}
