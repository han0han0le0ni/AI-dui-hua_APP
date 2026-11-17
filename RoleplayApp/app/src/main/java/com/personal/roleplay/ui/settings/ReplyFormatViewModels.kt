package com.personal.roleplay.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.model.ReplyFormat
import com.personal.roleplay.domain.model.ReplyFormatPreset
import com.personal.roleplay.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReplyFormatUiState(
    val format: ReplyFormat = ReplyFormat.DEFAULT,
    /** 真正会注入提示词的那几行，页面直接展示，省得靠想象 */
    val preview: String = ReplyFormat.DEFAULT.resolvedRules()
)

/**
 * 回复格式设置。
 *
 * 改一下就存一次，不设「保存」按钮：这是全局偏好，
 * 页面上有实时预览，存没存一眼就看得出，多一步确认反而累。
 */
class ReplyFormatViewModel(
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    /** 用户在本页改动过的值；没改过时用库里的 */
    private val draft = MutableStateFlow<ReplyFormat?>(null)

    val uiState: StateFlow<ReplyFormatUiState> = combine(
        appSettingsRepository.observeReplyFormat(),
        draft
    ) { stored, local ->
        val current = local ?: stored
        ReplyFormatUiState(format = current, preview = current.resolvedRules())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReplyFormatUiState())

    fun selectPreset(preset: ReplyFormatPreset) {
        update { it.withPreset(preset) }
    }

    fun updateCustomText(text: String) {
        update { it.copy(customText = text) }
    }

    private fun update(transform: (ReplyFormat) -> ReplyFormat) {
        viewModelScope.launch {
            val current = draft.value ?: appSettingsRepository.getReplyFormat()
            val next = transform(current)
            draft.value = next
            appSettingsRepository.saveReplyFormat(next)
        }
    }
}
