package com.personal.roleplay.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.repository.DiagnosticsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DebugUiState(
    val logs: List<com.personal.roleplay.domain.model.RequestLog> = emptyList(),
    val expandedLogId: String? = null,
    val expandedBlockKeys: Set<String> = emptySet()
)

/**
 * 请求调试页。
 *
 * 整个 App 里唯一能看见「实际发了什么」的地方：分区块折叠的完整 prompt、
 * 各区块 token 占用、模型返回原文、耗时、错误信息。可复制、可导出。
 *
 * M1 就要有它——没有它，人设和预算只能靠猜。
 */
class DebugViewModel(
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {

    private val expandedLogId = MutableStateFlow<String?>(null)
    private val expandedBlockKeys = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<DebugUiState> = combine(
        diagnosticsRepository.observeRecent(30),
        expandedLogId,
        expandedBlockKeys
    ) { logs, expandedId, blocks ->
        DebugUiState(
            logs = logs,
            // 默认展开最近一条，进来就能看到东西
            expandedLogId = expandedId ?: logs.firstOrNull()?.id,
            expandedBlockKeys = blocks
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugUiState())

    fun toggleLog(logId: String) {
        expandedLogId.value = if (expandedLogId.value == logId) null else logId
    }

    fun toggleBlock(key: String) {
        expandedBlockKeys.value = expandedBlockKeys.value.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
    }
}
