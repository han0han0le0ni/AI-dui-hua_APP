package com.personal.roleplay.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.chat.ChatOrchestrator
import com.personal.roleplay.domain.chat.PreparedTurn
import com.personal.roleplay.domain.chat.TurnAftermath
import com.personal.roleplay.domain.chat.TurnOutcome
import com.personal.roleplay.domain.memory.MemoryEngine
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.ChatStreamEvent
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.UserPersonaRepository
import com.personal.roleplay.domain.truncation.TruncateMode
import com.personal.roleplay.domain.truncation.TruncationEngine
import com.personal.roleplay.domain.truncation.UndoSnapshot
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 顶部提示条：截断后几秒内可撤销 */
data class UndoBanner(
    val messageCount: Int,
    val memoryCount: Int
)

/** 截断会连带删记忆，动手前先让用户看清楚要失去什么（8.5） */
data class PendingAction(
    val kind: ActionKind,
    val messageId: String,
    val anchorSeq: Long,
    /** 编辑消息时的新内容 */
    val newContent: String?,
    val messageCount: Int,
    val losingMemories: List<MemoryItem>,
    val weakenedMemoryCount: Int
)

enum class ActionKind {
    REGENERATE,
    CONTINUE_FROM,
    EDIT,
    DELETE_MESSAGE
}

data class ChatUiState(
    /** 会话的归属角色，决定整页主题色 */
    val character: Character? = null,
    /** 演员表。单人线只有一个元素，群聊按发言轮转顺序 */
    val participants: List<Character> = emptyList(),
    /** 本轮该谁开口。群聊时顶栏提示「下一句：xxx」 */
    val speaker: Character? = null,
    val session: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    /** 正在流式输出的那条消息 id，用于打字机光标 */
    val streamingMessageId: String? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val undoBanner: UndoBanner? = null,
    val hasMoreMessages: Boolean = false,
    val pendingAction: PendingAction? = null,
    /** 从记忆面板溯源跳转过来的目标 seq，短暂高亮后自动清除 */
    val highlightSeq: Long? = null,
    /** 人设库快照，供本条线随时切换扮演身份 */
    val personas: List<UserPersona> = emptyList(),
    /** 本条线引用的人设 id。为空表示不设扮演身份，你就是你自己 */
    val activePersonaId: String? = null
) {
    /**
     * 以会话的演员表为准。
     *
     * 不能用 participants.size：那是「查得到的成员」，某个成员被删掉后它会先缩水，
     * 而编排器仍按 cast 判定群聊，两边不一致会导致标题、轮转提示各说各话。
     */
    val isGroup: Boolean get() = session?.isGroup ?: (participants.size > 1)

    /** 当前扮演身份的名字。从人设库现算，改名字后面板跟着变 */
    val activePersonaName: String?
        get() = activePersonaId?.let { id -> personas.firstOrNull { it.id == id }?.name }

    /**
     * 发言人 id → 角色。消息气泡按它取名字与配色，群聊才分得清谁是谁。
     *
     * 预先算好而不是写成 getter：列表里每个气泡都会读它，
     * getter 就等于「每个可见气泡 × 每次重组」都重建一张映射表。
     */
    val speakersById: Map<String, Character> = participants.associateBy { it.id }
}

class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val characterRepository: CharacterRepository,
    private val chatOrchestrator: ChatOrchestrator,
    private val truncationEngine: TruncationEngine,
    private val memoryEngine: MemoryEngine,
    private val userPersonaRepository: UserPersonaRepository
) : ViewModel() {

    private val mutableState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = mutableState.asStateFlow()

    init {
        // 人设库跟着变（改名、增删），选择器不会显示过期内容
        viewModelScope.launch {
            userPersonaRepository.observeAll().collect { list ->
                mutableState.update { it.copy(personas = list) }
            }
        }
    }

    private var sessionId: String? = null
    private var generationJob: Job? = null

    /** 正在流式写入的那条占位消息；中途停止时据此清理空壳 */
    private var currentDraftId: String? = null

    /** 占位消息在 messages 里的下标缓存，避免流式时每个 token 都全表查找 */
    private var draftIndexHint = -1

    /** 「回复要求」被截断只提醒一次，避免每轮都弹同一条 */
    private var warnedConstraintTruncation = false

    /** 撤销栈只在内存里，不落库：离开对话页即视为确认删除 */
    private var undoSnapshot: UndoSnapshot? = null

    private var pageLimit = PAGE_SIZE

    /**
     * @param focusSeq 从记忆面板溯源跳转过来的消息 seq；传 -1 表示不跳转。
     */
    fun bind(sessionId: String, focusSeq: Long = -1L) {
        if (this.sessionId == sessionId) {
            if (focusSeq >= 0L) focusOn(focusSeq)
            return
        }
        this.sessionId = sessionId
        pageLimit = PAGE_SIZE
        warnedConstraintTruncation = false
        if (focusSeq >= 0L) focusOn(focusSeq)

        viewModelScope.launch {
            val session = conversationRepository.getSession(sessionId) ?: return@launch
            val participants = session.cast.mapNotNull { characterRepository.getById(it) }
            val host = participants.firstOrNull { it.id == session.characterId }
                ?: participants.firstOrNull()
                ?: return@launch

            // 开场白只在单人线自动插入：群聊里谁先开口没有共识，交给你第一句话决定
            if (!session.isGroup &&
                conversationRepository.getMessages(sessionId).isEmpty() &&
                host.greeting.isNotBlank()
            ) {
                conversationRepository.insertMessage(
                    ChatMessage(
                        id = Ids.new(),
                        sessionId = sessionId,
                        seq = conversationRepository.nextSeq(sessionId),
                        role = MessageRole.ASSISTANT,
                        speakerCharacterId = host.id,
                        content = host.greeting.trim(),
                        edited = false,
                        providerSnapshot = null,
                        promptTokens = 0,
                        completionTokens = 0,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            mutableState.update {
                it.copy(
                    character = host,
                    participants = participants,
                    session = conversationRepository.getSession(sessionId),
                    activePersonaId = session.userPersonaId
                )
            }
            reloadMessages()
        }
    }

    /**
     * 切换本条线扮演谁。
     *
     * 同时写 id 与正文快照：id 让改人设全体生效，快照保证人设被删后这条线还认得自己。
     * 传 null 表示取消扮演身份。
     */
    fun setPersona(personaId: String?) {
        viewModelScope.launch {
            val id = sessionId ?: return@launch
            val session = conversationRepository.getSession(id) ?: return@launch
            val persona = personaId?.let { userPersonaRepository.getById(it) }

            conversationRepository.updateSession(
                session.copy(
                    userPersonaId = persona?.id,
                    userPersona = persona?.content
                )
            )

            mutableState.update {
                it.copy(
                    session = conversationRepository.getSession(id),
                    activePersonaId = persona?.id,
                    infoMessage = if (persona != null) {
                        "这条线现在由你扮演「${persona.name}」，下一轮生效"
                    } else {
                        "已取消扮演身份，下一轮起你就是你自己"
                    }
                )
            }
        }
    }

    private suspend fun reloadMessages() {
        val id = sessionId ?: return
        // 只取条数，不要为了拿总数把整表读出来再映射一遍领域对象
        val total = conversationRepository.countMessages(id)
        val window = conversationRepository.getLatestWindow(id, pageLimit)
        // 每轮由轮转决定谁开口。统一在这里刷新，界面上的「下一句」才不会被甩在后面
        val nextSpeaker = chatOrchestrator.nextSpeaker(id)

        mutableState.update { state ->
            state.copy(
                messages = window,
                hasMoreMessages = total > window.size,
                speaker = nextSpeaker,
                session = state.session
            )
        }
    }

    fun loadMoreMessages() {
        viewModelScope.launch {
            pageLimit += PAGE_SIZE
            reloadMessages()
        }
    }

    // ==================== 发送 ====================

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            val id = sessionId ?: return@launch
            val message = chatOrchestrator.insertUserMessage(id, trimmed).getOrElse { error ->
                mutableState.update { it.copy(errorMessage = error.message) }
                return@launch
            }
            mutableState.update { it.copy(messages = it.messages + message, errorMessage = null) }
            runRound()
        }
    }

    /**
     * 调整群聊一轮让几个角色开口。
     *
     * 只影响往后每一轮，已经聊完的内容不动。
     */
    fun setGroupTurnSize(size: Int) {
        viewModelScope.launch {
            val id = sessionId ?: return@launch
            val session = conversationRepository.getSession(id) ?: return@launch
            val clamped = size.coerceIn(1, session.cast.size.coerceAtLeast(1))
            conversationRepository.updateSession(session.copy(groupTurnSize = clamped))
            mutableState.update {
                it.copy(
                    session = conversationRepository.getSession(id),
                    infoMessage = if (clamped == 1) {
                        "群聊改成每轮一个人开口"
                    } else {
                        "群聊改成每轮 $clamped 个人依次开口"
                    }
                )
            }
        }
    }

    /** 手动插入旁白：作为 narration 消息入库，参与记忆抽取 */
    fun insertNarration(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            val id = sessionId ?: return@launch
            val message = chatOrchestrator.insertNarration(id, trimmed).getOrElse { error ->
                mutableState.update { it.copy(errorMessage = error.message) }
                return@launch
            }
            mutableState.update { it.copy(messages = it.messages + message, errorMessage = null) }
        }
    }

    // ==================== 回溯操作 ====================

    fun requestRegenerate(messageId: String) {
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            preparePending(
                kind = ActionKind.REGENERATE,
                messageId = messageId,
                anchorSeq = message.seq,
                mode = TruncateMode.REGENERATE
            )
        }
    }

    fun requestContinueFrom(messageId: String) {
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            preparePending(
                kind = ActionKind.CONTINUE_FROM,
                messageId = messageId,
                anchorSeq = message.seq,
                mode = TruncateMode.CONTINUE_FROM
            )
        }
    }

    fun requestDelete(messageId: String) {
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            val id = sessionId ?: return@launch
            // 单条删除的溯源影响与截断同一套算法，这里只算不删
            val sources = truncationEngine.previewDelete(id, listOf(messageId))
            mutableState.update {
                it.copy(
                    pendingAction = PendingAction(
                        kind = ActionKind.DELETE_MESSAGE,
                        messageId = messageId,
                        anchorSeq = message.seq,
                        newContent = null,
                        messageCount = 1,
                        losingMemories = sources.losingMemories,
                        weakenedMemoryCount = sources.weakenedMemories.size
                    )
                )
            }
        }
    }

    fun requestEdit(messageId: String, newContent: String) {
        val trimmed = newContent.trim()
        if (trimmed.isBlank()) {
            mutableState.update { it.copy(errorMessage = "编辑后的内容不能为空") }
            return
        }
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            preparePending(
                kind = ActionKind.EDIT,
                messageId = messageId,
                anchorSeq = message.seq,
                mode = TruncateMode.CONTINUE_FROM,
                newContent = trimmed
            )
        }
    }

    private suspend fun preparePending(
        kind: ActionKind,
        messageId: String,
        anchorSeq: Long,
        mode: TruncateMode,
        newContent: String? = null
    ) {
        val id = sessionId ?: return
        val preview = truncationEngine.preview(id, anchorSeq, mode)
        mutableState.update {
            it.copy(
                pendingAction = PendingAction(
                    kind = kind,
                    messageId = messageId,
                    anchorSeq = anchorSeq,
                    newContent = newContent,
                    messageCount = preview.messageCount,
                    losingMemories = preview.losingMemories,
                    weakenedMemoryCount = preview.weakenedMemories.size
                )
            )
        }
    }

    fun dismissPending() {
        mutableState.update { it.copy(pendingAction = null) }
    }

    fun confirmPending() {
        val action = mutableState.value.pendingAction ?: return
        mutableState.update { it.copy(pendingAction = null) }

        viewModelScope.launch {
            when (action.kind) {
                ActionKind.DELETE_MESSAGE -> {
                    val outcome = truncationEngine.deleteMessages(
                        sessionId = sessionId ?: return@launch,
                        messageIds = listOf(action.messageId)
                    )
                    undoSnapshot = null
                    reloadMessages()
                    mutableState.update {
                        it.copy(infoMessage = "已删除 ${outcome.deletedMessageCount} 条消息、${outcome.deletedMemoryCount} 条记忆")
                    }
                }

                ActionKind.CONTINUE_FROM -> {
                    applyTruncation(action.anchorSeq, TruncateMode.CONTINUE_FROM)
                    // 「从这里继续」只回到那个时间点，等你重新开口
                    mutableState.update { it.copy(infoMessage = "已回到这里，等你重新开口") }
                }

                ActionKind.EDIT -> {
                    val newContent = action.newContent ?: return@launch
                    val message = conversationRepository.getMessage(action.messageId) ?: return@launch
                    conversationRepository.updateMessage(message.copy(content = newContent, edited = true))
                    // 编辑等于你主动定义事实，相关记忆标记待对账
                    truncationEngine.markMemoriesStale(listOf(action.messageId))
                    applyTruncation(action.anchorSeq, TruncateMode.CONTINUE_FROM)
                    generateSingle()
                }

                ActionKind.REGENERATE -> {
                    applyTruncation(action.anchorSeq, TruncateMode.REGENERATE)
                    generateSingle()
                }
            }
        }
    }

    private suspend fun applyTruncation(anchorSeq: Long, mode: TruncateMode) {
        val id = sessionId ?: return
        val outcome = truncationEngine.truncate(id, anchorSeq, mode)

        // 快照驻留内存：离开页面即丢弃，数据真正清理
        undoSnapshot = outcome.snapshot
        if (outcome.snapshot != null) {
            mutableState.update {
                it.copy(
                    undoBanner = UndoBanner(outcome.deletedMessageCount, outcome.deletedMemoryCount),
                    session = conversationRepository.getSession(id)
                )
            }
        }
        reloadMessages()
    }

    fun undo() {
        val snapshot = undoSnapshot ?: return
        viewModelScope.launch {
            truncationEngine.undo(snapshot)
            undoSnapshot = null
            mutableState.update { it.copy(undoBanner = null, infoMessage = "已撤销，对话与记忆都还原了") }
            reloadMessages()
        }
    }

    fun dismissUndo() {
        undoSnapshot = null
        mutableState.update { it.copy(undoBanner = null) }
    }

    // ==================== 生成 ====================

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        mutableState.update { it.copy(isGenerating = false, streamingMessageId = null) }

        // 停下时占位消息若还是空的，留着只会在气泡里显示一段空白
        val draftId = currentDraftId
        currentDraftId = null
        if (draftId != null) {
            viewModelScope.launch {
                val draft = conversationRepository.getMessage(draftId)
                if (draft != null && draft.content.isBlank()) {
                    conversationRepository.deleteMessages(listOf(draftId))
                    reloadMessages()
                }
            }
        }
    }

    /**
     * 用户发言后开一轮。
     *
     * 单人线恒回一条；群聊按会话设置让多个角色**依次**开口。
     * 依然是每轮只把**一个**身份交给模型——轮转位置从消息里的发言人反推，
     * 所以连发既不会串味，也不必额外存轮转指针。
     */
    private fun runRound() {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            val id = sessionId ?: return@launch
            val session = conversationRepository.getSession(id) ?: return@launch
            val turns = if (session.isGroup) session.effectiveGroupTurnSize else 1

            mutableState.update { it.copy(isGenerating = true, errorMessage = null) }

            var left = turns
            while (left > 0 && isActive) {
                if (!generateOnce()) break
                left--
                // 留一点停顿，连发的消息在界面上才看得出是「轮流」而不是一条长回复
                if (left > 0) delay(GROUP_TURN_GAP_MS)
            }

            mutableState.update { it.copy(isGenerating = false, streamingMessageId = null) }
        }
    }

    /** 只回一条：重新生成、编辑后重发都走这里，绝不连锁出第二个发言人 */
    private fun generateSingle() {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            mutableState.update { it.copy(isGenerating = true, errorMessage = null) }
            generateOnce()
            mutableState.update { it.copy(isGenerating = false, streamingMessageId = null) }
        }
    }

    /**
     * 生成一轮。
     *
     * @return 这一轮是否产出了内容。失败或空回复返回 false，调用方据此停止连锁，
     *         不会因为某位角色报错就把整轮剩下的发言位也吞掉。
     */
    private suspend fun generateOnce(): Boolean {
        val id = sessionId ?: return false

        val prepared = chatOrchestrator.prepare(id).getOrElse { error ->
            mutableState.update { it.copy(errorMessage = error.message ?: "无法发起对话") }
            return false
        }

        // 「回复要求」被截断是静默故障：不报错、不崩，只会让模型分不清谁在说话。
        // 所以这里主动提示一次，别让人靠猜。
        val truncationWarning = if (prepared.context.constraintTruncated && !warnedConstraintTruncation) {
            warnedConstraintTruncation = true
            "「回复要求」超出预算被截短了，角色可能分不清谁在说话。「请求调试」里能看到实际发出去的提示词"
        } else {
            null
        }

        mutableState.update { state ->
            state.copy(
                // 主题色跟着会话的归属角色走，不随发言人跳变
                character = state.character ?: prepared.character,
                participants = prepared.participants,
                speaker = prepared.character,
                session = prepared.session,
                infoMessage = when {
                    prepared.context.droppedOldTurns ->
                        "上下文空间不足，较早的一些对话这次没有发给模型"

                    truncationWarning != null -> truncationWarning

                    else -> state.infoMessage
                }
            )
        }

        // 先把空消息落库拿到 seq，之后边收 token 边按节流更新它。
        // 发言人此刻就定下来，群聊的正确归属依赖它。
        val draftId = Ids.new()
        currentDraftId = draftId
        val draft = ChatMessage(
            id = draftId,
            sessionId = id,
            seq = conversationRepository.nextSeq(id),
            role = MessageRole.ASSISTANT,
            speakerCharacterId = prepared.character.id,
            content = "",
            edited = false,
            providerSnapshot = null,
            promptTokens = 0,
            completionTokens = 0,
            createdAt = System.currentTimeMillis()
        )
        conversationRepository.insertMessage(draft)

        mutableState.update { state ->
            state.copy(messages = state.messages + draft, streamingMessageId = draftId)
        }
        // 记下占位消息的位置，供 updateDraft 走快路径
        draftIndexHint = mutableState.value.messages.lastIndex

        val startedAt = System.currentTimeMillis()
        val accumulated = StringBuilder()
        var lastFlushAt = 0L
        var lastFlushLength = 0
        var lastUiFlushAt = 0L
        // 上游偶尔会在 Completed 之后再多吐一帧，收尾只允许做一次
        var settled = false
        var produced = false

        chatOrchestrator.stream(prepared).collect { event ->
            if (settled) return@collect

            when (event) {
                is ChatStreamEvent.Delta -> {
                    accumulated.append(event.text)

                    // 两路节流，窗口不同：
                    //  界面按 ~20fps 刷——上游每秒可能吐上百个 token，
                    //  不节流就是整屏以同样频率重组，这是打字时最明显的卡顿来源；
                    //  数据库按更长窗口刷，避免每 token 一次写库。
                    // 收尾时 completeTurn 会把最终全文写回，末尾几十毫秒不会丢字。
                    val now = System.currentTimeMillis()
                    if (now - lastUiFlushAt >= UI_FLUSH_INTERVAL_MS) {
                        updateDraft(draftId, accumulated.toString())
                        lastUiFlushAt = now
                    }
                    if (now - lastFlushAt >= FLUSH_INTERVAL_MS ||
                        accumulated.length - lastFlushLength >= FLUSH_CHARS
                    ) {
                        flushDraft(draftId, accumulated.toString())
                        lastFlushAt = now
                        lastFlushLength = accumulated.length
                    }
                }

                is ChatStreamEvent.Completed -> {
                    settled = true
                    produced = event.fullText.isNotBlank()
                    val outcome = TurnOutcome(
                        assistantText = event.fullText,
                        promptTokens = event.promptTokens,
                        completionTokens = event.completionTokens,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        rawResponse = event.fullText,
                        error = null
                    )
                    finish(prepared, outcome, draftId)
                }

                is ChatStreamEvent.Failed -> {
                    settled = true
                    produced = accumulated.isNotEmpty()
                    val outcome = TurnOutcome(
                        assistantText = accumulated.toString(),
                        promptTokens = null,
                        completionTokens = null,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        rawResponse = null,
                        error = event.error
                    )
                    if (accumulated.isEmpty()) {
                        // 一个字都没吐出来，占位消息留着毫无意义
                        conversationRepository.deleteMessages(listOf(draftId))
                    }
                    finish(prepared, outcome, draftId.takeIf { accumulated.isNotEmpty() })
                }
            }
        }

        currentDraftId = null
        return produced
    }

    private suspend fun finish(
        prepared: PreparedTurn,
        outcome: TurnOutcome,
        draftId: String?
    ) {
        // 只收打字光标；isGenerating 由整轮的调用方统一管，群聊连发时不能中途变 false
        mutableState.update { it.copy(streamingMessageId = null) }

        if (outcome.error != null) {
            mutableState.update { it.copy(errorMessage = outcome.error.describe()) }
        }

        val aftermath = runCatching {
            chatOrchestrator.completeTurn(prepared, outcome, draftId)
        }.onFailure { error ->
            Log.e(TAG, "finish: 收尾失败", error)
        }.getOrDefault(TurnAftermath(shouldExtract = false, shouldCompress = false))

        reloadMessages()

        // 后续动作全部后台跑，绝不阻塞下一次发言
        if (aftermath.shouldCompress) {
            viewModelScope.launch { runCatching { memoryEngine.compressSummary(prepared.session.id) } }
        }
        if (aftermath.shouldExtract) {
            viewModelScope.launch { runCatching { memoryEngine.extract(prepared.session.id) } }
        }
    }

    /**
     * 把流式收到的文本写进占位消息。
     *
     * 占位消息永远排在列表末尾，所以先拿上次命中的下标试一次。
     * 这里每个 token 都会走一遍，长对话里全表线性查找是不必要的开销。
     */
    private fun updateDraft(draftId: String, text: String) {
        mutableState.update { state ->
            val messages = state.messages
            val index = if (messages.getOrNull(draftIndexHint)?.id == draftId) {
                draftIndexHint
            } else {
                messages.indexOfFirst { it.id == draftId }.also { draftIndexHint = it }
            }
            if (index < 0) return@update state

            val updated = messages.toMutableList()
            updated[index] = updated[index].copy(content = text)
            state.copy(messages = updated)
        }
    }

    private suspend fun flushDraft(draftId: String, text: String) {
        val current = conversationRepository.getMessage(draftId) ?: return
        conversationRepository.updateMessage(current.copy(content = text))
    }

    fun consumeError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    fun consumeInfo() {
        mutableState.update { it.copy(infoMessage = null) }
    }

    /** 溯源跳转：高亮那条消息，几秒后自动淡出 */
    private fun focusOn(seq: Long) {
        mutableState.update { it.copy(highlightSeq = seq) }
        viewModelScope.launch {
            delay(HIGHLIGHT_DURATION_MS)
            mutableState.update { state ->
                if (state.highlightSeq == seq) state.copy(highlightSeq = null) else state
            }
        }
    }

    /** 手动重新整理记忆（8.6） */
    fun recomputeMemories() {
        viewModelScope.launch {
            val id = sessionId ?: return@launch
            val count = memoryEngine.recomputeAll(id).getOrDefault(0)
            mutableState.update { it.copy(infoMessage = "已重新整理，TA 现在记住 $count 件事") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 撤销快照本就是内存态，这里只是显式表达「离开页面 = 确认删除」
        undoSnapshot = null
        generationJob?.cancel()
    }

    private companion object {
        const val TAG = "ChatViewModel"

        /** 流式写库节流：时间窗口或字符数，先到先触发 */
        const val FLUSH_INTERVAL_MS = 400L
        const val FLUSH_CHARS = 120

        /** 流式界面刷新节流窗口，约 20fps：比这更密也看不出差别，只是在白烧 CPU */
        const val UI_FLUSH_INTERVAL_MS = 50L

        /** 分页窗口大小 */
        const val PAGE_SIZE = 40

        /** 溯源跳转高亮持续时间 */
        const val HIGHLIGHT_DURATION_MS = 3_000L

        /** 群聊连发时两位发言人之间的停顿 */
        const val GROUP_TURN_GAP_MS = 400L
    }
}
