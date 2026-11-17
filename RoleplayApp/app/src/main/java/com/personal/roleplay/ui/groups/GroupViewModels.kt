package com.personal.roleplay.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.UserPersonaRepository
import com.personal.roleplay.domain.truncation.TruncationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 群聊列表里的一行 */
data class GroupRow(
    val session: ChatSession,
    /** 演员表里还查得到的成员。成员被删掉后这里会变短，但群聊本身仍在 */
    val members: List<Character>,
    val messageCount: Int
)

data class GroupListUiState(
    val rows: List<GroupRow> = emptyList(),
    /** 可用来建群的活跃角色 */
    val candidates: List<Character> = emptyList(),
    val isLoading: Boolean = true,
    val feedback: String? = null
)

/**
 * 群聊列表。
 *
 * 群聊独立于角色：它有自己的归属，不再挂在某个角色的详情页底下。
 * 归属角色被删掉时只从演员表里摘人（见 CharacterListViewModel.deleteCharacter），
 * 所以这里的行不会因为少了一个成员就整条消失。
 */
class GroupListViewModel(
    private val conversationRepository: ConversationRepository,
    private val characterRepository: CharacterRepository,
    private val userPersonaRepository: UserPersonaRepository,
    private val truncationEngine: TruncationEngine
) : ViewModel() {

    private val feedback = MutableStateFlow<String?>(null)
    private val createdSessionId = MutableStateFlow<String?>(null)

    /** 建好群聊后交给界面跳转 */
    val newSessionId: StateFlow<String?> = createdSessionId.asStateFlow()

    val uiState: StateFlow<GroupListUiState> = combine(
        conversationRepository.observeAllSessions(),
        conversationRepository.observeMessageCounts(),
        // 用全量角色（含已归档）解析成员名：归档不该让群聊里的名字变成空白
        characterRepository.observeAll(),
        feedback
    ) { sessions, counts, characters, message ->
        val byId = characters.associateBy { it.id }

        GroupListUiState(
            rows = sessions
                .filter { it.isGroup }
                .sortedByDescending { it.lastActiveAt }
                .map { session ->
                    GroupRow(
                        session = session,
                        members = session.cast.mapNotNull { byId[it] },
                        messageCount = counts[session.id] ?: 0
                    )
                },
            candidates = characters.filter { !it.archived },
            isLoading = false,
            feedback = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupListUiState())

    /**
     * 开一个群聊。
     *
     * 演员表顺序即发言轮转顺序；第一个成员同时作为会话的归属锚点
     * （主题色、兜底标题都取它），但群聊在界面上是独立入口，不再依赖它。
     */
    fun createGroup(memberIds: List<String>) {
        viewModelScope.launch {
            val members = memberIds.distinct()
            if (members.size < 2) {
                feedback.value = "群聊至少要选两个角色"
                return@launch
            }
            val names = members.mapNotNull { characterRepository.getById(it)?.name }
            if (names.size < 2) {
                feedback.value = "选中的角色已不存在，请重新选择"
                return@launch
            }

            // 默认人设直接套用，开群时不必再填一遍
            val persona = userPersonaRepository.getDefault()
            val session = conversationRepository.createSession(
                characterId = members.first(),
                title = names.joinToString("、"),
                userPersonaId = persona?.id,
                userPersona = persona?.content,
                participantIds = members,
                groupTurnSize = ChatSession.DEFAULT_GROUP_TURN_SIZE
            )
            createdSessionId.value = session.id
        }
    }

    fun consumeNewSession() {
        createdSessionId.value = null
    }

    fun deleteGroup(sessionId: String) {
        viewModelScope.launch {
            val outcome = truncationEngine.deleteSession(sessionId)
            feedback.value =
                "已删除这个群聊，含 ${outcome.deletedMessageCount} 条消息、${outcome.deletedMemoryCount} 条记忆"
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }
}
