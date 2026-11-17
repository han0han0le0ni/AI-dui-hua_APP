package com.personal.roleplay.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.memory.MemoryEngine
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MemoryOrigin
import com.personal.roleplay.domain.model.MemoryStatus
import com.personal.roleplay.domain.model.MemoryWithSources
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.MemoryRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryPanelUiState(
    val memories: List<MemoryWithSources> = emptyList(),
    val characterName: String = "",
    val isRecomputing: Boolean = false,
    val feedback: String? = null,
    /** 正在编辑内容的记忆 id */
    val editingMemoryId: String? = null
)

/**
 * 记忆面板（8.4）。
 *
 * 这里最有价值的一处是溯源跳转：每条记忆显示「来自 N 条对话」，点进去能看到是哪几句话
 * 让它被记住的。AI 的记忆第一次变得可审计。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryPanelViewModel(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val memoryEngine: MemoryEngine
) : ViewModel() {

    private val sessionId = MutableStateFlow<String?>(null)
    private val feedback = MutableStateFlow<String?>(null)
    private val isRecomputing = MutableStateFlow(false)
    private val editingMemoryId = MutableStateFlow<String?>(null)
    private val focusSeq = MutableStateFlow<Long?>(null)

    /** 溯源跳转事件：记忆里只存 messageId，跳转要的是 seq */
    val focusSeqEvent: StateFlow<Long?> = focusSeq.asStateFlow()

    val uiState: StateFlow<MemoryPanelUiState> = sessionId
        .filterNotNull()
        .flatMapLatest { id ->
            memoryRepository.observeBySession(id).mapLatest { memories ->
                MemoryPanelUiState(
                    memories = memories.map { memory ->
                        MemoryWithSources(memory, memoryRepository.getSourcesOfMemory(memory.id))
                    },
                    isRecomputing = isRecomputing.value,
                    feedback = feedback.value,
                    editingMemoryId = editingMemoryId.value
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryPanelUiState())

    fun bind(sessionId: String) {
        this.sessionId.value = sessionId
    }

    fun startEdit(memoryId: String) {
        editingMemoryId.value = memoryId
    }

    fun cancelEdit() {
        editingMemoryId.value = null
    }

    fun updateContent(memoryId: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            val target = memoryRepository.getByIds(listOf(memoryId)).firstOrNull() ?: return@launch
            memoryRepository.save(
                target.copy(
                    content = trimmed,
                    status = MemoryStatus.ACTIVE,
                    updatedAt = System.currentTimeMillis()
                )
            )
            editingMemoryId.value = null
            feedback.value = "已更新这条记忆"
        }
    }

    /**
     * 手动添加记忆。
     *
     * 它没有来源消息，所以不因删某条消息而消失；但删会话时仍随之删除（硬约束 3）。
     */
    fun addMemory(layer: MemoryLayer, content: String) {
        val trimmed = content.trim()
        val id = sessionId.value
        if (trimmed.isBlank() || id == null) return

        viewModelScope.launch {
            val session = conversationRepository.getSession(id) ?: return@launch
            val now = System.currentTimeMillis()
            memoryRepository.save(
                MemoryItem(
                    id = Ids.new(),
                    characterId = session.characterId,
                    sessionId = id,
                    layer = layer,
                    content = trimmed,
                    confidence = 1f,
                    pinned = false,
                    origin = MemoryOrigin.USER_DEFINED,
                    status = MemoryStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                    lastRecalledAt = now
                )
            )
            feedback.value = "已手动添加一条记忆"
        }
    }

    fun togglePin(memoryId: String) {
        viewModelScope.launch {
            val target = memoryRepository.getByIds(listOf(memoryId)).firstOrNull() ?: return@launch
            memoryRepository.save(target.copy(pinned = !target.pinned))
            feedback.value = if (target.pinned) "已取消置顶" else "已置顶，注入优先级最高"
        }
    }

    fun forget(memoryId: String) {
        viewModelScope.launch {
            memoryRepository.deleteMemories(listOf(memoryId))
            feedback.value = "已遗忘这条记忆"
        }
    }

    /** 重新整理（8.6）：花自己的 token 对现存对话重新抽取 */
    fun recompute() {
        val id = sessionId.value ?: return
        viewModelScope.launch {
            isRecomputing.value = true
            val count = runCatching { memoryEngine.recomputeAll(id) }.getOrDefault(Result.success(0)).getOrDefault(0)
            isRecomputing.value = false
            feedback.value = "已重新整理，TA 现在记住 $count 件事"
        }
    }

    /** 点「去看」时把 messageId 换成会话里的 seq，交给对话页定位 */
    fun requestJumpToMessage(messageId: String) {
        viewModelScope.launch {
            focusSeq.value = conversationRepository.getMessage(messageId)?.seq
        }
    }

    fun consumeFocusSeq() {
        focusSeq.value = null
    }

    fun consumeFeedback() {
        feedback.value = null
    }
}
