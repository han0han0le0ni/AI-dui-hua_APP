package com.personal.roleplay.ui.worldbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.model.WorldBook
import com.personal.roleplay.domain.model.WorldBookEntry
import com.personal.roleplay.domain.repository.WorldBookRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WorldBookUiState(
    val book: WorldBook? = null,
    val entries: List<WorldBookEntry> = emptyList(),
    val editingEntryId: String? = null,
    val feedback: String? = null
)

/**
 * 世界书（9.3）。
 *
 * 条目结构：触发词 + 内容 + 优先级 + 是否常驻 + 插入位置。
 * 对话时按用户输入与最近 2 条消息匹配触发词，命中的按优先级注入 prompt。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorldBookViewModel(
    private val worldBookRepository: WorldBookRepository
) : ViewModel() {

    private val worldBookId = MutableStateFlow<String?>(null)
    private val editingEntryId = MutableStateFlow<String?>(null)
    private val feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<WorldBookUiState> = worldBookId
        .filterNotNull()
        .flatMapLatest { id -> buildUiState(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorldBookUiState())

    private fun buildUiState(id: String): Flow<WorldBookUiState> =
        worldBookRepository.observeEntries(id).map { entries ->
            WorldBookUiState(
                book = worldBookRepository.getBook(id),
                entries = entries,
                editingEntryId = editingEntryId.value,
                feedback = feedback.value
            )
        }

    fun bind(worldBookId: String) {
        this.worldBookId.value = worldBookId
    }

    fun startEdit(entryId: String) {
        editingEntryId.value = entryId
    }

    fun cancelEdit() {
        editingEntryId.value = null
    }

    fun addEntry(keys: List<String>, content: String, priority: Int, constant: Boolean) {
        val id = worldBookId.value ?: return
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            feedback.value = "条目内容不能为空"
            return
        }

        viewModelScope.launch {
            val nextOrder = worldBookRepository.getEnabledEntries(id).size
            worldBookRepository.saveEntry(
                WorldBookEntry(
                    id = Ids.new(),
                    worldBookId = id,
                    keys = keys.map { it.trim() }.filter { it.isNotEmpty() },
                    content = trimmed,
                    priority = priority,
                    enabled = true,
                    constant = constant,
                    insertionOrder = nextOrder
                )
            )
            feedback.value = "已添加条目"
        }
    }

    fun updateEntry(entry: WorldBookEntry) {
        viewModelScope.launch {
            worldBookRepository.saveEntry(entry)
            editingEntryId.value = null
            feedback.value = "已保存"
        }
    }

    fun toggleEnabled(entry: WorldBookEntry) {
        viewModelScope.launch {
            worldBookRepository.saveEntry(entry.copy(enabled = !entry.enabled))
        }
    }

    fun delete(entryId: String) {
        viewModelScope.launch {
            worldBookRepository.deleteEntry(entryId)
            feedback.value = "已删除条目"
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }
}
