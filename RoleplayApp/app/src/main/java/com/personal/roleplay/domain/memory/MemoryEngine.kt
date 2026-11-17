package com.personal.roleplay.domain.memory

import android.util.Log
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.ChatTurn
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemoryOperation
import com.personal.roleplay.domain.model.MemoryOrigin
import com.personal.roleplay.domain.model.MemorySourceRef
import com.personal.roleplay.domain.model.MemoryStatus
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.model.SummarySegment
import com.personal.roleplay.domain.provider.ChatRequest
import com.personal.roleplay.domain.provider.ProviderAdapterFactory
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.MemoryRepository
import com.personal.roleplay.domain.repository.TransactionRunner
import com.personal.roleplay.util.Ids

/**
 * 记忆引擎（第八章）。
 *
 * 三条铁律：
 * 1. 抽取走辅助槽（小模型），绝不阻塞对话；
 * 2. 抽取失败或格式错误一律静默跳过，绝不影响对话本身；
 * 3. 记忆必须可溯源——抽出来却没有有效来源的，直接丢弃。
 */
class MemoryEngine(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    /** 群聊里要用角色名给原文标发言人，也用它把记忆归属到说话人 */
    private val characterRepository: CharacterRepository,
    private val resolveConfig: suspend (Slot) -> ProviderConfig?,
    private val usageTracker: com.personal.roleplay.domain.usage.UsageTracker,
    private val transactionRunner: TransactionRunner
) {

    /** 触发条件：累计 8 轮，或距上次超过 10 分钟（15.2 默认值） */
    fun shouldExtract(session: ChatSession, now: Long = System.currentTimeMillis()): Boolean {
        if (session.roundsSinceExtract >= EXTRACT_ROUNDS) return true
        if (session.lastExtractedAt > 0L && now - session.lastExtractedAt >= EXTRACT_INTERVAL_MS) return true
        return false
    }

    /**
     * 一次抽取：最近 20 轮原文 + 该会话已有记忆 → 小模型 → 增删改指令。
     *
     * @return 实际落库的操作条数；失败返回 0，不做任何补偿动作。
     */
    suspend fun extract(sessionId: String): Result<Int> = runCatching {
        val session = conversationRepository.getSession(sessionId) ?: return@runCatching 0
        val config = resolveConfig(Slot.MEMORY_EXTRACTION) ?: run {
            Log.w(TAG, "未配置记忆抽取槽，跳过抽取")
            return@runCatching 0
        }

        val allMessages = conversationRepository.getMessages(sessionId)
        if (allMessages.isEmpty()) return@runCatching 0

        // 只扫最近 20 轮，不全量扫（省 token）
        val recent = allMessages.takeLast(RECENT_ROUNDS * 2)
        val existing = memoryRepository.getBySession(sessionId)

        // 群聊必须把发言人名字喂给抽取模型，否则它分不清哪条事实是谁的
        val speakerNames = session.cast
            .mapNotNull { characterRepository.getById(it) }
            .associate { it.id to it.name }

        val completion = callExtraction(config, recent, existing, speakerNames) ?: return@runCatching 0
        val operations = MemoryOperationParser.parse(completion)

        // 多条 ADD/UPDATE/DELETE 与抽取游标必须一起提交：中途失败不能留下半套记忆
        val applied = transactionRunner.inTransaction {
            val applied = applyOperations(session, recent, operations)

            val maxSeq = allMessages.maxOf { it.seq }
            conversationRepository.updateSession(
                session.copy(
                    lastExtractedSeq = maxSeq,
                    lastExtractedAt = System.currentTimeMillis(),
                    roundsSinceExtract = 0
                )
            )
            applied
        }

        Log.i(TAG, "extract: 解析出 ${operations.size} 条指令，落库 $applied 条")
        applied
    }.onFailure { error ->
        // 抽取是辅助动作，失败只能记日志，不能向上抛
        Log.w(TAG, "extract: 抽取失败，静默跳过，下次再抽", error)
    }

    /**
     * L1 滚动摘要压缩。
     *
     * 摘要按 seq 分段存储，截断时直接丢弃断点之后的分段；
     * 分段过多时把最早的两段再压一次，避免摘要自身无限膨胀。
     */
    suspend fun compressSummary(sessionId: String): Result<Boolean> = runCatching {
        val config = resolveConfig(Slot.SUMMARY) ?: return@runCatching false
        val session = conversationRepository.getSession(sessionId) ?: return@runCatching false
        val messages = conversationRepository.getMessages(sessionId)

        val summarizedTo = session.summarySegments.maxOfOrNull { it.toSeq } ?: 0L
        val pending = messages.filter { it.seq > summarizedTo }
        if (pending.size < MIN_PENDING_MESSAGES) return@runCatching false

        // 群聊的摘要同样要认人，否则「最近发生了什么」会写成一个人的事
        // 单人线也带上真名：摘要里写「角色」不如写「林深」清楚，
        // 和抽取环节的标注方式保持一致
        val speakerNames = session.cast
            .mapNotNull { characterRepository.getById(it) }
            .associate { it.id to it.name }
        val dialogue = pending.joinToString("\n") { message ->
            "${MemoryPrompts.speakerLabel(message, speakerNames)}：${message.content}"
        }

        val adapter = ProviderAdapterFactory.create(config.protocol)
        val prompt = MemoryPrompts.buildSummaryPrompt(
            previousSummary = session.summarySegments.lastOrNull()?.text,
            newDialogue = dialogue,
            multiSpeaker = session.isGroup
        )

        val completion = adapter.complete(
            ChatRequest(
                config = config,
                systemPrompt = prompt,
                turns = listOf(ChatTurn(MessageRole.USER, "请压缩上面这段对话。")),
                maxTokensOverride = 700,
                temperatureOverride = 0.3f
            )
        ).onSuccess { result ->
            usageTracker.record(
                config = config,
                slot = Slot.SUMMARY,
                sessionId = sessionId,
                characterId = null,
                promptTokens = result.promptTokens ?: 0,
                completionTokens = result.completionTokens ?: 0,
                latencyMs = result.latencyMs,
                success = true
            )
        }.getOrElse { error ->
            Log.w(TAG, "compressSummary: 压缩失败，保持原摘要不变", error)
            return@runCatching false
        }

        var segments = session.summarySegments + SummarySegment(
            fromSeq = pending.first().seq,
            toSeq = pending.last().seq,
            text = completion.text.trim()
        )

        if (segments.size > MAX_SEGMENTS) {
            segments = mergeOldestSegments(segments, adapter, config)
        }

        conversationRepository.updateSession(session.copy(summarySegments = segments))
        Log.i(TAG, "compressSummary: 摘要分段数 ${session.summarySegments.size} -> ${segments.size}")
        true
    }.onFailure { error ->
        Log.w(TAG, "compressSummary: 压缩过程异常", error)
    }

    /**
     * 手动「重新整理 TA 的记忆」（8.6）：清掉抽取来的记忆后对现存对话重新抽取。
     *
     * 手动添加与置顶的记忆保留——它们不是从对话里抽出来的，重抽也抽不回来。
     */
    suspend fun recomputeAll(sessionId: String): Result<Int> = runCatching {
        val session = conversationRepository.getSession(sessionId) ?: return@runCatching 0
        val config = resolveConfig(Slot.MEMORY_EXTRACTION) ?: return@runCatching 0
        val allMessages = conversationRepository.getMessages(sessionId)
        if (allMessages.isEmpty()) return@runCatching 0

        val preserved = memoryRepository.getWithSources(sessionId)
            .filter { it.memory.pinned || it.memory.origin == MemoryOrigin.USER_DEFINED }

        transactionRunner.inTransaction {
            memoryRepository.deleteBySession(sessionId)
            memoryRepository.saveAll(preserved.map { it.memory })
            memoryRepository.addSources(preserved.flatMap { it.sources })
        }

        val speakerNames = session.cast
            .mapNotNull { characterRepository.getById(it) }
            .associate { it.id to it.name }

        var total = 0

        allMessages.chunked(RECENT_ROUNDS * 2).forEach { chunk ->
            val completion = callExtraction(
                config = config,
                messages = chunk,
                existing = memoryRepository.getBySession(sessionId),
                speakerNames = speakerNames
            ) ?: return@forEach
            total += transactionRunner.inTransaction {
                applyOperations(session, chunk, MemoryOperationParser.parse(completion))
            }
        }

        conversationRepository.updateSession(
            session.copy(
                lastExtractedSeq = allMessages.maxOf { it.seq },
                lastExtractedAt = System.currentTimeMillis(),
                roundsSinceExtract = 0
            )
        )

        Log.i(TAG, "recomputeAll: 重建完成，共 $total 条记忆")
        total
    }.onFailure { error ->
        Log.w(TAG, "recomputeAll: 重算失败", error)
    }

    private suspend fun callExtraction(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        existing: List<MemoryItem>,
        speakerNames: Map<String, String>
    ): String? {
        val adapter = ProviderAdapterFactory.create(config.protocol)
        val prompt = MemoryPrompts.buildExtractionPrompt(messages, existing, speakerNames)

        return adapter.complete(
            ChatRequest(
                config = config,
                systemPrompt = prompt,
                turns = listOf(ChatTurn(MessageRole.USER, "请对账并输出操作指令。")),
                jsonMode = true,
                maxTokensOverride = 1_500,
                temperatureOverride = 0.2f
            )
        ).onSuccess { completion ->
            usageTracker.record(
                config = config,
                slot = Slot.MEMORY_EXTRACTION,
                sessionId = messages.firstOrNull()?.sessionId,
                characterId = null,
                promptTokens = completion.promptTokens ?: 0,
                completionTokens = completion.completionTokens ?: 0,
                latencyMs = completion.latencyMs,
                success = true
            )
        }.onFailure { error ->
            Log.w(TAG, "callExtraction: 请求失败（${config.toSafeSummary()}）", error)
            usageTracker.recordFailure(
                config = config,
                slot = Slot.MEMORY_EXTRACTION,
                sessionId = messages.firstOrNull()?.sessionId,
                characterId = null,
                latencyMs = 0L,
                errorMessage = error.message ?: "未知错误"
            )
        }.getOrNull()?.text
    }

    /**
     * 把指令落到库里，同时维护 memory_source。
     *
     * sourceRefs 是提示词里展示的消息编号（即 seq），这里映射回 messageId。
     */
    private suspend fun applyOperations(
        session: ChatSession,
        messages: List<ChatMessage>,
        operations: List<MemoryOperation>
    ): Int {
        val seqToMessage = messages.associateBy { it.seq }
        var count = 0

        operations.forEach { operation ->
            when (operation) {
                is MemoryOperation.Add -> {
                    if (applyAdd(session, operation, seqToMessage)) count++
                }

                is MemoryOperation.Update -> {
                    if (applyUpdate(session, operation, seqToMessage)) count++
                }

                is MemoryOperation.Delete -> {
                    val target = memoryRepository.getByIds(listOf(operation.memoryId)).firstOrNull()
                    if (target == null || target.sessionId != session.id) {
                        Log.w(TAG, "DELETE 目标不存在或不属于本会话，跳过：${operation.memoryId}")
                        return@forEach
                    }
                    memoryRepository.deleteMemories(listOf(target.id))
                    count++
                }

                MemoryOperation.Noop -> Unit
            }
        }

        return count
    }

    private suspend fun applyAdd(
        session: ChatSession,
        operation: MemoryOperation.Add,
        seqToMessage: Map<Long, ChatMessage>
    ): Boolean {
        val content = operation.content.trim()
        if (content.isBlank()) return false

        val sourceMessages = operation.sourceRefs.mapNotNull { seq -> seqToMessage[seq.toLong()] }
        val sources = sourceMessages
            .map { message -> MemorySourceRef(Ids.new(), "", message.id, excerptOf(message.content)) }

        // 无法溯源的记忆不入库：删消息、截段时都没法判断它该不该一起走
        if (sources.isEmpty()) {
            Log.w(TAG, "ADD 没有有效来源，丢弃：$content")
            return false
        }

        // 归属到最早那条来源的说话人。群聊里这是刚需：三个人各自的记忆必须分开，
        // 混在一起就等于让 A 记住 B 的私事，是串味最严重的一种。
        // 用户消息与旁白没有发言人，退回会话的归属角色。
        val ownerId = sourceMessages.firstNotNullOfOrNull { it.speakerCharacterId }
            ?: session.characterId

        val now = System.currentTimeMillis()
        val memoryId = Ids.new()
        memoryRepository.save(
            MemoryItem(
                id = memoryId,
                characterId = ownerId,
                sessionId = session.id,
                layer = operation.layer,
                content = content,
                confidence = MemoryConfidence.compute(sources.size, pinned = false, origin = MemoryOrigin.EXTRACTED),
                pinned = false,
                origin = MemoryOrigin.EXTRACTED,
                status = MemoryStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
                lastRecalledAt = 0L
            )
        )
        memoryRepository.addSources(sources.map { it.copy(memoryId = memoryId) })
        return true
    }

    private suspend fun applyUpdate(
        session: ChatSession,
        operation: MemoryOperation.Update,
        seqToMessage: Map<Long, ChatMessage>
    ): Boolean {
        val target = memoryRepository.getByIds(listOf(operation.memoryId)).firstOrNull()
        if (target == null || target.sessionId != session.id) {
            Log.w(TAG, "UPDATE 目标不存在或不属于本会话，跳过：${operation.memoryId}")
            return false
        }

        val existingSources = memoryRepository.getSourcesOfMemory(target.id)
        val knownMessageIds = existingSources.map { it.messageId }.toSet()

        val newSources = operation.sourceRefs
            .mapNotNull { seq -> seqToMessage[seq.toLong()] }
            .filter { it.id !in knownMessageIds }
            .map { message -> MemorySourceRef(Ids.new(), target.id, message.id, excerptOf(message.content)) }

        if (newSources.isNotEmpty()) {
            memoryRepository.addSources(newSources)
        }

        val totalSources = existingSources.size + newSources.size
        memoryRepository.save(
            target.copy(
                content = operation.content.trim(),
                status = MemoryStatus.ACTIVE,
                updatedAt = System.currentTimeMillis(),
                confidence = MemoryConfidence.compute(totalSources, target.pinned, target.origin)
            )
        )
        return true
    }

    private suspend fun mergeOldestSegments(
        segments: List<SummarySegment>,
        adapter: com.personal.roleplay.domain.provider.ProviderAdapter,
        config: ProviderConfig
    ): List<SummarySegment> {
        val oldest = segments.first()
        val second = segments[1]

        val merged = adapter.complete(
            ChatRequest(
                config = config,
                systemPrompt = MemoryPrompts.buildMergePrompt(oldest.text, second.text),
                turns = listOf(ChatTurn(MessageRole.USER, "请合并。")),
                maxTokensOverride = 500,
                temperatureOverride = 0.3f
            )
        ).getOrNull()?.text?.trim()

        return if (merged.isNullOrBlank()) {
            segments.drop(1)
        } else {
            listOf(SummarySegment(oldest.fromSeq, second.toSeq, merged)) + segments.drop(2)
        }
    }

    private fun excerptOf(content: String): String {
        val clean = content.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= EXCERPT_MAX_LENGTH) clean else clean.take(EXCERPT_MAX_LENGTH) + "…"
    }

    private companion object {
        const val TAG = "MemoryEngine"

        /** 距上次抽取累计多少轮触发 */
        const val EXTRACT_ROUNDS = 8
        const val EXTRACT_INTERVAL_MS = 10 * 60 * 1_000L

        /** 每次抽取扫描多少轮原文 */
        const val RECENT_ROUNDS = 20

        /** 少于这个数量的新消息不值得压缩 */
        const val MIN_PENDING_MESSAGES = 10

        /** 摘要分段上限，超出则把最早两段再压一次 */
        const val MAX_SEGMENTS = 6

        const val EXCERPT_MAX_LENGTH = 60
    }
}
