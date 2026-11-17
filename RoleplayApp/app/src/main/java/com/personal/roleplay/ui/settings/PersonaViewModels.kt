package com.personal.roleplay.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.domain.repository.UserPersonaRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ==================== 人设列表 ====================

data class PersonaListUiState(
    val personas: List<UserPersona> = emptyList(),
    val feedback: String? = null
) {
    val isEmpty: Boolean get() = personas.isEmpty()
}

class PersonaListViewModel(
    private val userPersonaRepository: UserPersonaRepository
) : ViewModel() {

    private val feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PersonaListUiState> = combine(
        userPersonaRepository.observeAll(),
        feedback
    ) { personas, message ->
        PersonaListUiState(personas = personas, feedback = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonaListUiState())

    /** 新会话默认套用哪个，全局只有一个 */
    fun setAsDefault(personaId: String) {
        viewModelScope.launch {
            userPersonaRepository.setAsDefault(personaId)
            feedback.value = "已设为新会话的默认人设"
        }
    }

    fun delete(personaId: String) {
        viewModelScope.launch {
            userPersonaRepository.delete(personaId)
            feedback.value = "已删除。用过它的话本会退回建线时的人设快照，对话不受影响"
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }
}

// ==================== 人设编辑 ====================

data class PersonaEditorUiState(
    val persona: UserPersona? = null,
    val isNew: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null
)

class PersonaEditorViewModel(
    private val userPersonaRepository: UserPersonaRepository
) : ViewModel() {

    private val draft = MutableStateFlow<UserPersona?>(null)
    private val isNew = MutableStateFlow(false)
    private val saved = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PersonaEditorUiState> = combine(
        draft, isNew, saved, error
    ) { persona, new, savedFlag, errorMessage ->
        PersonaEditorUiState(
            persona = persona,
            isNew = new,
            saved = savedFlag,
            error = errorMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonaEditorUiState())

    /** @param personaId 为 null 表示新建 */
    fun bind(personaId: String?) {
        viewModelScope.launch {
            val existing = personaId?.let { userPersonaRepository.getById(it) }
            draft.value = existing ?: newPersona()
            isNew.value = existing == null
        }
    }

    fun update(transform: (UserPersona) -> UserPersona) {
        draft.update { current -> current?.let(transform) }
        saved.value = false
    }

    fun save() {
        val current = draft.value ?: return
        val name = current.name.trim()
        val content = current.content.trim()

        when {
            name.isBlank() -> error.value = "起个短名字，好在对话页里一眼认出是谁"
            content.isBlank() -> error.value = "人设正文不能为空，否则注入不进提示词"
            else -> viewModelScope.launch {
                userPersonaRepository.save(
                    current.copy(
                        name = name,
                        content = content,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                saved.value = true
                error.value = null
            }
        }
    }

    fun consumeError() {
        error.value = null
    }

    private fun newPersona(): UserPersona {
        val now = System.currentTimeMillis()
        return UserPersona(
            id = Ids.new(),
            name = "",
            content = "",
            // 第一条会被仓储自动设成默认，这里传 false 即可
            isDefault = false,
            createdAt = now,
            updatedAt = now
        )
    }
}
