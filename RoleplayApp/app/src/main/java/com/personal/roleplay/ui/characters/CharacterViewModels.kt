package com.personal.roleplay.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.data.card.CharacterCardImporter
import com.personal.roleplay.data.seed.BuiltInCharacters
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.UserPersonaRepository
import com.personal.roleplay.domain.repository.WorldBookRepository
import com.personal.roleplay.domain.truncation.TruncationEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ==================== 角色列表 ====================

data class CharacterCard(
    val character: Character,
    val sessionCount: Int,
    val lastActiveAt: Long
)

data class CharacterListUiState(
    val cards: List<CharacterCard> = emptyList(),
    val showArchived: Boolean = false,
    val isLoading: Boolean = true,
    val feedback: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterListViewModel(
    private val characterRepository: CharacterRepository,
    private val conversationRepository: ConversationRepository,
    private val truncationEngine: TruncationEngine,
    private val characterCardImporter: CharacterCardImporter
) : ViewModel() {

    private val showArchived = MutableStateFlow(false)
    private val feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<CharacterListUiState> = combine(
        showArchived.flatMapLatest { archived ->
            if (archived) characterRepository.observeArchived() else characterRepository.observeActive()
        },
        conversationRepository.observeAllSessions(),
        feedback
    ) { characters, sessions, message ->
        // 群聊有自己的 Tab，不计入任何角色的「几条线」
        val grouped = sessions.filter { !it.isGroup }.groupBy { it.characterId }
        val cards = characters
            .map { character ->
                val owned = grouped[character.id].orEmpty()
                CharacterCard(
                    character = character,
                    sessionCount = owned.size,
                    lastActiveAt = owned.maxOfOrNull { it.lastActiveAt } ?: character.createdAt
                )
            }
            // 最近在聊的置顶
            .sortedByDescending { it.lastActiveAt }

        CharacterListUiState(
            cards = cards,
            showArchived = showArchived.value,
            isLoading = false,
            feedback = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CharacterListUiState())

    fun toggleArchivedView() {
        showArchived.update { !it }
    }

    fun setArchived(characterId: String, archived: Boolean) {
        viewModelScope.launch {
            characterRepository.setArchived(characterId, archived)
            feedback.value = if (archived) "已归档" else "已取消归档"
        }
    }

    /**
     * 删角色。
     *
     * 单人线跟着一起消失——那是 TA 专属的线。
     *
     * **群聊只把 TA 从演员表里摘掉**：群里的对话和其他成员的记忆是大家共有的，
     * 不能因为删掉一个角色就把整个群、连同别人说过的台词一起清掉。
     */
    fun deleteCharacter(characterId: String) {
        viewModelScope.launch {
            val sessions = conversationRepository.getAllSessions()

            sessions
                .filter { !it.isGroup && it.cast.contains(characterId) }
                .forEach { truncationEngine.deleteSession(it.id) }

            sessions
                .filter { it.isGroup && it.cast.contains(characterId) }
                .forEach { session ->
                    val remaining = session.cast.filter { it != characterId }
                    if (remaining.isEmpty()) {
                        truncationEngine.deleteSession(session.id)
                    } else {
                        conversationRepository.updateSession(
                            session.copy(
                                participantIds = remaining,
                                // 归属锚点被删就让位给下一个成员：主题色与兜底标题还要取它
                                characterId = if (session.characterId == characterId) {
                                    remaining.first()
                                } else {
                                    session.characterId
                                }
                            )
                        )
                    }
                }

            characterRepository.delete(characterId)
            feedback.value = "已删除角色。TA 参与的群聊保留了下来，只摘掉了 TA 本人"
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }

    /**
     * 导入 PNG/JSON 角色卡。
     *
     * 解码与落库都在 IO 线程完成，这里只负责把结果翻译成一句人话。
     */
    fun importCard(bytes: ByteArray) {
        viewModelScope.launch {
            runCatching { characterCardImporter.import(bytes) }
                .onSuccess { imported ->
                    feedback.value = buildString {
                        append("已导入「${imported.name}」")
                        if (imported.worldBookId != null) append("，世界书条目也一起导入了")
                    }
                }
                .onFailure { error ->
                    feedback.value = "导入失败：${error.message ?: "文件不是有效的角色卡"}"
                }
        }
    }

    /** 读文件阶段就失败时用，保持提示只有一处出口 */
    fun reportImportError(message: String) {
        feedback.value = "导入失败：$message"
    }
}

// ==================== 角色详情 ====================

data class SessionSummary(
    val session: ChatSession,
    val messageCount: Int
)

data class CharacterDetailUiState(
    val character: Character? = null,
    val sessions: List<SessionSummary> = emptyList(),
    /** 人设库，供开新线时挑选 */
    val personas: List<UserPersona> = emptyList(),
    /** 开新线时预选的人设。和仓储的 getDefault 同一套规则，界面不用自己猜 */
    val defaultPersonaId: String? = null,
    val isLoading: Boolean = true,
    val feedback: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterDetailViewModel(
    private val characterRepository: CharacterRepository,
    private val conversationRepository: ConversationRepository,
    private val truncationEngine: TruncationEngine,
    private val userPersonaRepository: UserPersonaRepository
) : ViewModel() {

    private val characterId = MutableStateFlow<String?>(null)
    private val feedback = MutableStateFlow<String?>(null)
    private val createdSessionId = MutableStateFlow<String?>(null)

    val newSessionId: StateFlow<String?> = createdSessionId.asStateFlow()

    val uiState: StateFlow<CharacterDetailUiState> = combine(
        characterId.filterNotNull().flatMapLatest { id -> characterRepository.observeById(id) },
        conversationRepository.observeAllSessions(),
        conversationRepository.observeMessageCounts(),
        userPersonaRepository.observeAll(),
        feedback
    ) { character, sessions, counts, personas, message ->
        val id = character?.id
        CharacterDetailUiState(
            character = character,
            sessions = sessions
                // 只列这条角色自己的线；群聊统一在「群聊」页里管理
                .filter { !it.isGroup && it.characterId == id }
                .map { session -> SessionSummary(session, counts[session.id] ?: 0) },
            personas = personas,
            // 与仓储 getDefault 同一套规则：显式设过用设的，否则退回第一条
            defaultPersonaId = personas.firstOrNull { it.isDefault }?.id
                ?: personas.firstOrNull()?.id,
            isLoading = false,
            feedback = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CharacterDetailUiState())

    fun bind(characterId: String) {
        this.characterId.value = characterId
    }

    /**
     * 新建一条线。
     *
     * 「我扮演谁」从人设库里挑，不再逐条手打；同一个角色可以有完全不同的几条线。
     * 传 null 表示这条线不做扮演，就是你自己。
     */
    fun createSession(userPersonaId: String?) {
        viewModelScope.launch {
            val id = characterId.value ?: return@launch
            val persona = userPersonaId?.let { userPersonaRepository.getById(it) }
            val existing = conversationRepository.getAllSessions().count { it.characterId == id }
            val session = conversationRepository.createSession(
                characterId = id,
                title = "第 ${existing + 1} 条线",
                userPersonaId = persona?.id,
                // 同时写一份正文快照：人设日后被删，这条线也还认得自己是谁
                userPersona = persona?.content,
                participantIds = listOf(id)
            )
            createdSessionId.value = session.id
        }
    }

    fun consumeNewSession() {
        createdSessionId.value = null
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val outcome = truncationEngine.deleteSession(sessionId)
            feedback.value = "已删除这条线，含 ${outcome.deletedMessageCount} 条消息、${outcome.deletedMemoryCount} 条记忆"
        }
    }

    fun setArchived(archived: Boolean) {
        viewModelScope.launch {
            val id = characterId.value ?: return@launch
            characterRepository.setArchived(id, archived)
            feedback.value = if (archived) "已归档" else "已取消归档"
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }
}

// ==================== 角色编辑 ====================

data class CharacterEditorUiState(
    val character: Character? = null,
    val isNew: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterEditorViewModel(
    private val characterRepository: CharacterRepository,
    private val worldBookRepository: WorldBookRepository
) : ViewModel() {

    private val draft = MutableStateFlow<Character?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val saved = MutableStateFlow(false)
    private val isNew = MutableStateFlow(false)

    val uiState: StateFlow<CharacterEditorUiState> = combine(
        draft, isNew, saved, error
    ) { character, new, savedFlag, errorMessage ->
        CharacterEditorUiState(
            character = character,
            isNew = new,
            saved = savedFlag,
            error = errorMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CharacterEditorUiState())

    fun bind(characterId: String?) {
        viewModelScope.launch {
            val existing = characterId?.let { characterRepository.getById(it) }
            if (existing != null) {
                draft.value = existing
                isNew.value = false
            } else {
                draft.value = BuiltInCharacters.newCharacter()
                isNew.value = true
            }
        }
    }

    /**
     * 角色还没挂世界书时现建一本，返回它的 id 交给界面跳过去编辑。
     */
    suspend fun ensureWorldBook(): String? {
        val current = draft.value ?: return null
        current.worldBookId?.let { return it }

        val book = worldBookRepository.createBook(name = "${current.name} 的世界", description = null)
        update { it.copy(worldBookId = book.id) }
        characterRepository.save(current.copy(worldBookId = book.id))
        return book.id
    }

    fun update(transform: (Character) -> Character) {
        draft.update { current -> current?.let(transform) }
        saved.value = false
    }

    fun save() {
        viewModelScope.launch {
            val current = draft.value ?: return@launch
            val name = current.name.trim()
            if (name.isBlank()) {
                error.value = "名字不能为空"
                return@launch
            }
            if (current.persona.isBlank() && current.exampleDialogues.isBlank()) {
                error.value = "人设正文与示例对话至少填一个，否则模型不知道该演谁"
                return@launch
            }

            characterRepository.save(
                current.copy(
                    name = name,
                    persona = current.persona.trim(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            saved.value = true
            error.value = null
        }
    }

    fun consumeError() {
        error.value = null
    }
}
