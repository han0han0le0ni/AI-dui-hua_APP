package com.personal.roleplay.domain.chat

import android.util.Log
import com.personal.roleplay.domain.context.AssemblyParams
import com.personal.roleplay.domain.context.ContextAssembler
import com.personal.roleplay.domain.context.ContextBudget
import com.personal.roleplay.domain.context.TokenEstimator
import com.personal.roleplay.domain.memory.MemoryEngine
import com.personal.roleplay.domain.memory.MemoryRetriever
import com.personal.roleplay.domain.model.AssembledContext
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.ChatStreamEvent
import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.ProviderError
import com.personal.roleplay.domain.model.ProviderSnapshot
import com.personal.roleplay.domain.model.RequestLog
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.model.SpeakerLabels
import com.personal.roleplay.domain.model.WorldBookEntry
import com.personal.roleplay.domain.provider.ChatRequest
import com.personal.roleplay.domain.provider.ProviderAdapterFactory
import com.personal.roleplay.domain.repository.AppSettingsRepository
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.DiagnosticsRepository
import com.personal.roleplay.domain.repository.MemoryRepository
import com.personal.roleplay.domain.repository.ProviderRepository
import com.personal.roleplay.domain.repository.UserPersonaRepository
import com.personal.roleplay.domain.repository.WorldBookRepository
import com.personal.roleplay.domain.usage.UsageTracker
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.flow.Flow

/**
 * 一次生成所需的一切都在这里备齐，UI 拿到它就可以直接开流。
 */
data class PreparedTurn(
    val session: ChatSession,
    /** 本轮要扮演的角色。群聊里由轮转决定，不是随机的 */
    val character: Character,
    /** 全部参与者，顺序即发言轮转顺序 */
    val participants: List<Character>,
    val config: ProviderConfig,
    val context: AssembledContext
)

/**
 * 一次生成的结果。失败时 [error] 有值，[assistantText] 为空。
 */
data class TurnOutcome(
    val assistantText: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val latencyMs: Long,
    val rawResponse: String?,
    val error: ProviderError?
) {
    val succeeded: Boolean get() = error == null && assistantText.isNotBlank()
}

/** 生成结束后的后续动作建议，由调用方在后台发起 */
data class TurnAftermath(
    val shouldExtract: Boolean,
    val shouldCompress: Boolean
)

/**
 * 对话编排（11.1）。
 *
 * 把「检索情节记忆 → 组装上下文 → 槽位路由 → 流式请求 → 落库计量 → 触发后续」串起来，
 * 让 ViewModel 只负责状态与渲染。
 */
class ChatOrchestrator(
    private val conversationRepository: ConversationRepository,
    private val characterRepository: CharacterRepository,
    private val memoryRepository: MemoryRepository,
    private val worldBookRepository: WorldBookRepository,
    private val providerRepository: ProviderRepository,
    private val memoryRetriever: MemoryRetriever,
    private val memoryEngine: MemoryEngine,
    private val usageTracker: UsageTracker,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val userPersonaRepository: UserPersonaRepository,
    private val appSettingsRepository: AppSettingsRepository
) {

    /**
     * 检索记忆并组装上下文。
     */
    suspend fun prepare(sessionId: String): Result<PreparedTurn> = runCatching {
        val session = conversationRepository.getSession(sessionId)
            ?: throw IllegalStateException("会话不存在")

        // 演员表：单人线只有一个元素，群聊按加入顺序
        val participants = loadParticipants(session)
        if (participants.isEmpty()) throw IllegalStateException("角色不存在")

        val speaker = pickSpeaker(session, participants)
        val config = providerRepository.resolveForSlot(Slot.MAIN_DIALOGUE, speaker.providerPreference)
            ?: throw IllegalStateException("还没有可用的接入配置，请先到「设置 → 接入配置」里添加一个")

        val recentMessages = conversationRepository.getLatestWindow(sessionId, RECENT_WINDOW)

        // L3 情节记忆：FTS5 粗筛 + 小模型精挑
        val episodic = memoryRetriever.retrieveEpisodic(sessionId, buildSearchTerms(recentMessages))

        // L2 / L4 直接按层取，由组装器按预算截断
        val semantic = memoryRepository.getByLayer(sessionId, MemoryLayer.L2_SEMANTIC)
        val emotion = memoryRepository.getByLayer(sessionId, MemoryLayer.L4_EMOTION)

        // L5 世界书：群聊把所有成员的世界书并起来，同一条目只注入一次
        val worldEntries = participants
            .mapNotNull { it.worldBookId }
            .distinct()
            .flatMap { worldBookRepository.getEnabledEntries(it) }
            .distinctBy { it.id }
            .let { entries -> matchWorldBook(entries, recentMessages) }

        val budget = ContextBudget.forContextWindow(config.capabilities.contextWindow)
        val context = ContextAssembler(budget).assemble(
            AssemblyParams(
                character = speaker,
                // 同场角色只做辨识，不进扮演位
                bystanders = participants.filter { it.id != speaker.id },
                speakerNames = participants.associate { it.id to it.name },
                userPersona = resolveUserPersona(session)?.text,
                userPersonaName = resolveUserPersona(session)?.name,
                summarySegments = session.summarySegments,
                semanticMemories = semantic,
                episodicMemories = episodic,
                emotionMemories = emotion,
                worldBookEntries = worldEntries,
                recentMessages = recentMessages,
                contextWindow = config.capabilities.contextWindow,
                maxOutputTokens = config.params.maxTokens,
                replyFormatText = appSettingsRepository.getReplyFormat().resolvedRules(),
                // 种子跟着「会话 + 当前轮」走：同一条消息重试时拿到同一份随机结果，
                // 前缀不会因为重试而变，缓存才有意义
                macroSeed = session.id.hashCode().toLong() * 1_000_003L +
                    (recentMessages.lastOrNull()?.seq ?: 0L)
            )
        )

        PreparedTurn(session, speaker, participants, config, context)
    }

    /**
     * 下一轮该谁开口。
     *
     * 界面用它显示「下一句：xxx」。单独暴露是有必要的：轮转位置由消息反推，
     * 不看一眼消息谁都不知道下一位是谁，界面不能自己猜。
     */
    suspend fun nextSpeaker(sessionId: String): Character? {
        val session = conversationRepository.getSession(sessionId) ?: return null
        val participants = loadParticipants(session)
        if (participants.isEmpty()) return null
        return pickSpeaker(session, participants)
    }

    private suspend fun loadParticipants(session: ChatSession): List<Character> =
        session.cast.mapNotNull { characterRepository.getById(it) }

    /**
     * 本轮「对方扮演谁」的内容与名字。
     *
     * 优先按 id 去人设库现取：在人设库里改了正文，所有引用它的线一起生效，
     * 不必逐条会话重设——这正是把人设抽成全局库的意义。
     *
     * 库里查不到（人设被删）时退回建线时写下的快照，
     * 这条线不会因为删掉一个人设就突然失去扮演身份。快照没有名字，只返回正文。
     */
    private suspend fun resolveUserPersona(session: ChatSession): PersonaText? {
        val fromLibrary = session.userPersonaId?.let { userPersonaRepository.getById(it) }
        if (fromLibrary != null && fromLibrary.content.isNotBlank()) {
            return PersonaText(text = fromLibrary.content, name = fromLibrary.name)
        }
        return session.userPersona
            ?.takeIf { it.isNotBlank() }
            ?.let { PersonaText(text = it, name = null) }
    }

    /** 人设正文 + 可选的辨认名 */
    private data class PersonaText(val text: String, val name: String?)

    /**
     * 选出本轮发言的角色。
     *
     * 单人线恒为那唯一的角色；群聊即「轮流发言」——每轮只把**一个**身份交给模型扮演，
     * 这是从结构上杜绝串味的做法：模型根本没有机会同时演两个人。
     */
    private suspend fun pickSpeaker(
        session: ChatSession,
        participants: List<Character>
    ): Character {
        if (participants.size <= 1) return participants.first()

        val cast = session.cast
        val lastIndex = conversationRepository.getLastSpeakerId(session.id)
            ?.let { cast.indexOf(it) }
            ?: -1
        val nextId = cast[(lastIndex + 1) % cast.size]
        return participants.firstOrNull { it.id == nextId } ?: participants.first()
    }

    /**
     * 流式生成。只负责发请求与吐增量，不碰数据库。
     */
    fun stream(prepared: PreparedTurn): Flow<ChatStreamEvent> {
        val adapter = ProviderAdapterFactory.create(prepared.config.protocol)
        return adapter.streamChat(
            ChatRequest(
                config = prepared.config,
                systemPrompt = prepared.context.systemPrompt,
                turns = prepared.context.turns
            )
        )
    }

    /**
     * 收尾：落库角色回复、计量、写调试日志、推进会话状态。
     *
     * @return 后续动作建议，调用方应在后台协程里据此触发记忆抽取与摘要压缩。
     */
    suspend fun completeTurn(
        prepared: PreparedTurn,
        outcome: TurnOutcome,
        /**
         * 流式阶段预先插入的占位消息 id。
         *
         * 流式时先把空消息落库拿到 seq，之后边收 token 边节流更新它；
         * 收尾时改更新这条而不是再插一条，否则会多出一条重复回复。
         */
        draftMessageId: String? = null
    ): TurnAftermath {
        val sessionId = prepared.session.id
        val now = System.currentTimeMillis()

        val promptTokens = outcome.promptTokens ?: prepared.context.estimatedTokens
        val assistantText = stripOwnSpeakerLabel(outcome.assistantText, prepared.character.name)
        val completionTokens = outcome.completionTokens ?: TokenEstimator.estimate(assistantText)

        val snapshot = ProviderSnapshot(
            providerId = prepared.config.id,
            providerName = prepared.config.name,
            modelId = prepared.config.modelId,
            temperature = prepared.config.params.temperature
        )

        if (outcome.succeeded) {
            val existing = draftMessageId?.let { conversationRepository.getMessage(it) }
            if (existing != null) {
                conversationRepository.updateMessage(
                    existing.copy(
                        content = assistantText,
                        // 占位消息是流式前插的，这里补上发言人，群聊才分得清是谁说的
                        speakerCharacterId = prepared.character.id,
                        providerSnapshot = snapshot,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens
                    )
                )
            } else {
                conversationRepository.insertMessage(
                    ChatMessage(
                        id = Ids.new(),
                        sessionId = sessionId,
                        seq = conversationRepository.nextSeq(sessionId),
                        role = MessageRole.ASSISTANT,
                        speakerCharacterId = prepared.character.id,
                        content = assistantText,
                        edited = false,
                        providerSnapshot = snapshot,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        createdAt = now
                    )
                )
            }
        }

        if (outcome.error == null) {
            usageTracker.record(
                config = prepared.config,
                slot = Slot.MAIN_DIALOGUE,
                sessionId = sessionId,
                characterId = prepared.character.id,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                latencyMs = outcome.latencyMs,
                success = true
            )
        } else {
            usageTracker.recordFailure(
                config = prepared.config,
                slot = Slot.MAIN_DIALOGUE,
                sessionId = sessionId,
                characterId = prepared.character.id,
                latencyMs = outcome.latencyMs,
                errorMessage = outcome.error.describe()
            )
        }

        saveDiagnostics(prepared, outcome, promptTokens, completionTokens)

        val roundsSinceExtract = if (outcome.succeeded) prepared.session.roundsSinceExtract + 1 else prepared.session.roundsSinceExtract
        conversationRepository.touchSession(sessionId, now)
        conversationRepository.getSession(sessionId)?.let { current ->
            conversationRepository.updateSession(current.copy(roundsSinceExtract = roundsSinceExtract))
        }

        val updatedSession = conversationRepository.getSession(sessionId) ?: prepared.session

        return TurnAftermath(
            shouldExtract = outcome.succeeded && memoryEngine.shouldExtract(updatedSession, now),
            shouldCompress = prepared.context.compressionNeeded
        )
    }

    /**
     * 剥掉模型模仿出来的说话人包装。
     *
     * 群聊历史里，别的角色的台词统一写成「（某角色 说：…）」，偶尔模型会照这个格式
     * 把自己的名字也包在回复外面。名字在界面上由气泡自己显示，所以这里剥掉，
     * 免得出现「名字：名字：台词」这种重复。
     *
     * 只处理「确实是自己名字」的两种包装，其余内容原样返回——宁可漏剥也不误伤正文。
     */
    private fun stripOwnSpeakerLabel(text: String, characterName: String): String {
        val name = characterName.trim()
        if (name.isEmpty() || text.isBlank()) return text

        val body = text.trimStart()

        // 「【我】：台词」「【林深】：台词」——历史里这两种标签都指自己
        for (label in listOfNotNull(SpeakerLabels.SELF, name.takeIf { it.isNotEmpty() })) {
            val head = "【$label】"
            if (!body.startsWith(head)) continue
            val rest = body.removePrefix(head).trimStart()
            val afterColon = when (rest.firstOrNull()) {
                '：', ':' -> rest.drop(1).trimStart()
                // 剥完什么都不剩，说明原文本来就只有标签，照原样留着更诚实
                else -> null
            }
            if (afterColon.isNullOrEmpty()) continue
            return afterColon
        }

        // 形如「（林深 说：台词）」的整体包装：只有整条都被包住时才拆，避免拆掉正文里的括号
        for (label in listOf(SpeakerLabels.SELF, name)) {
            val wrapped = "（$label 说："
            if (body.startsWith(wrapped) && body.endsWith("）")) {
                val inner = body.removePrefix(wrapped).removeSuffix("）").trim()
                if (inner.isNotEmpty()) return inner
            }
        }

        return text
    }

    /** 手动插入旁白：作为 narration 消息入库，参与记忆抽取 */
    suspend fun insertNarration(sessionId: String, text: String): Result<ChatMessage> = runCatching {
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { "旁白内容不能为空" }

        val message = ChatMessage(
            id = Ids.new(),
            sessionId = sessionId,
            seq = conversationRepository.nextSeq(sessionId),
            role = MessageRole.NARRATION,
            speakerCharacterId = null,
            content = trimmed,
            edited = false,
            providerSnapshot = null,
            promptTokens = 0,
            completionTokens = 0,
            createdAt = System.currentTimeMillis()
        )
        conversationRepository.insertMessage(message)
        conversationRepository.touchSession(sessionId, message.createdAt)
        message
    }

    suspend fun insertUserMessage(sessionId: String, text: String): Result<ChatMessage> = runCatching {
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { "消息内容不能为空" }

        val message = ChatMessage(
            id = Ids.new(),
            sessionId = sessionId,
            seq = conversationRepository.nextSeq(sessionId),
            role = MessageRole.USER,
            speakerCharacterId = null,
            content = trimmed,
            edited = false,
            providerSnapshot = null,
            promptTokens = 0,
            completionTokens = 0,
            createdAt = System.currentTimeMillis()
        )
        conversationRepository.insertMessage(message)
        conversationRepository.touchSession(sessionId, message.createdAt)
        message
    }

    /**
     * 调试面板里展示的「实际发出去的内容」。
     *
     * 前半是拼好的 system prompt（静态区块），后半是真实的多轮消息——
     * 后者才看得出群聊的说话人归属是否正常，因为归属信息就写在每行的「【名字】：」里。
     */
    private fun buildFullPrompt(prepared: PreparedTurn): String = buildString {
        append(prepared.context.systemPrompt)

        if (prepared.context.turns.isNotEmpty()) {
            appendLine()
            appendLine()
            append("========== 消息历史（共 ${prepared.context.turns.size} 条）==========")
            prepared.context.turns.forEach { turn ->
                appendLine()
                appendLine()
                append("[${turn.role.wire}] ${turn.content}")
            }
        }
    }

    private suspend fun saveDiagnostics(
        prepared: PreparedTurn,
        outcome: TurnOutcome,
        promptTokens: Int,
        completionTokens: Int
    ) {
        runCatching {
            diagnosticsRepository.save(
                RequestLog(
                    id = Ids.new(),
                    sessionId = prepared.session.id,
                    slot = Slot.MAIN_DIALOGUE,
                    // 只记脱敏后的配置名，Key 绝不能进调试面板
                    providerName = prepared.config.toSafeSummary(),
                    modelId = prepared.config.modelId,
                    // 字段本意是「完整 prompt」，所以连消息历史一起记。
                    // 群聊最容易出错的地方正是历史里「谁说的」，
                    // 只记 system prompt 的话，归属错了从界面上根本看不出来。
                    fullPrompt = buildFullPrompt(prepared),
                    blocks = prepared.context.blocks.map { it.toRecord() },
                    rawResponse = outcome.rawResponse ?: outcome.assistantText.takeIf { it.isNotBlank() },
                    errorMessage = outcome.error?.describe(),
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    latencyMs = outcome.latencyMs,
                    success = outcome.error == null,
                    createdAt = System.currentTimeMillis()
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "saveDiagnostics: 调试日志写入失败，不影响对话", error)
        }
    }

    /**
     * 世界书匹配（9.3）：匹配用户输入与最近 2 条消息，按分词包含匹配。
     * 常驻条目不依赖关键词，始终注入。
     */
    private fun matchWorldBook(
        entries: List<WorldBookEntry>,
        recentMessages: List<ChatMessage>
    ): List<WorldBookEntry> {
        if (entries.isEmpty()) return emptyList()

        val scope = recentMessages.takeLast(WORLD_MATCH_WINDOW)
            .joinToString("\n") { it.content }
            .lowercase()

        return entries.filter { entry ->
            entry.constant || entry.keys.any { key ->
                key.isNotBlank() && scope.contains(key.trim().lowercase())
            }
        }
    }

    private fun buildSearchTerms(messages: List<ChatMessage>): List<String> =
        messages.takeLast(SEARCH_TERM_WINDOW).map { it.content }

    private companion object {
        const val TAG = "ChatOrchestrator"

        /** 候选原文窗口：先取一批，由组装器按剩余预算决定最终装多少轮 */
        const val RECENT_WINDOW = 80

        const val WORLD_MATCH_WINDOW = 2
        const val SEARCH_TERM_WINDOW = 4
    }
}
