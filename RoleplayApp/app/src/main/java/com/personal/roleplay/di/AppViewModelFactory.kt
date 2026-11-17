package com.personal.roleplay.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.personal.roleplay.ui.characters.CharacterDetailViewModel
import com.personal.roleplay.ui.characters.CharacterEditorViewModel
import com.personal.roleplay.ui.characters.CharacterListViewModel
import com.personal.roleplay.ui.chat.ChatViewModel
import com.personal.roleplay.ui.debug.DebugViewModel
import com.personal.roleplay.ui.groups.GroupListViewModel
import com.personal.roleplay.ui.memory.MemoryPanelViewModel
import com.personal.roleplay.ui.settings.PersonaEditorViewModel
import com.personal.roleplay.ui.settings.PersonaListViewModel
import com.personal.roleplay.ui.settings.ProviderEditorViewModel
import com.personal.roleplay.ui.settings.ProviderSettingsViewModel
import com.personal.roleplay.ui.settings.ReplyFormatViewModel
import com.personal.roleplay.ui.settings.UsageViewModel
import com.personal.roleplay.ui.worldbook.WorldBookViewModel

/**
 * ViewModel 工厂。
 *
 * 手写 DI 的配套件：所有 ViewModel 的构造参数都从 AppContainer 里取，
 * 新增一个 ViewModel 时在这里加一行即可。
 */
class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(CharacterListViewModel::class.java) ->
            CharacterListViewModel(
                characterRepository = container.characterRepository,
                conversationRepository = container.conversationRepository,
                truncationEngine = container.truncationEngine,
                characterCardImporter = container.characterCardImporter
            ) as T

        modelClass.isAssignableFrom(GroupListViewModel::class.java) ->
            GroupListViewModel(
                conversationRepository = container.conversationRepository,
                characterRepository = container.characterRepository,
                userPersonaRepository = container.userPersonaRepository,
                truncationEngine = container.truncationEngine
            ) as T

        modelClass.isAssignableFrom(CharacterDetailViewModel::class.java) ->
            CharacterDetailViewModel(
                characterRepository = container.characterRepository,
                conversationRepository = container.conversationRepository,
                truncationEngine = container.truncationEngine,
                userPersonaRepository = container.userPersonaRepository
            ) as T

        modelClass.isAssignableFrom(CharacterEditorViewModel::class.java) ->
            CharacterEditorViewModel(
                characterRepository = container.characterRepository,
                worldBookRepository = container.worldBookRepository
            ) as T

        modelClass.isAssignableFrom(ChatViewModel::class.java) ->
            ChatViewModel(
                conversationRepository = container.conversationRepository,
                characterRepository = container.characterRepository,
                chatOrchestrator = container.chatOrchestrator,
                truncationEngine = container.truncationEngine,
                memoryEngine = container.memoryEngine,
                userPersonaRepository = container.userPersonaRepository
            ) as T

        modelClass.isAssignableFrom(MemoryPanelViewModel::class.java) ->
            MemoryPanelViewModel(
                memoryRepository = container.memoryRepository,
                conversationRepository = container.conversationRepository,
                memoryEngine = container.memoryEngine
            ) as T

        modelClass.isAssignableFrom(ReplyFormatViewModel::class.java) ->
            ReplyFormatViewModel(
                appSettingsRepository = container.appSettingsRepository
            ) as T

        modelClass.isAssignableFrom(PersonaListViewModel::class.java) ->
            PersonaListViewModel(
                userPersonaRepository = container.userPersonaRepository
            ) as T

        modelClass.isAssignableFrom(PersonaEditorViewModel::class.java) ->
            PersonaEditorViewModel(
                userPersonaRepository = container.userPersonaRepository
            ) as T

        modelClass.isAssignableFrom(ProviderSettingsViewModel::class.java) ->
            ProviderSettingsViewModel(providerRepository = container.providerRepository) as T

        modelClass.isAssignableFrom(ProviderEditorViewModel::class.java) ->
            ProviderEditorViewModel(providerRepository = container.providerRepository) as T

        modelClass.isAssignableFrom(UsageViewModel::class.java) ->
            UsageViewModel(
                usageRepository = container.usageRepository,
                characterRepository = container.characterRepository
            ) as T

        modelClass.isAssignableFrom(DebugViewModel::class.java) ->
            DebugViewModel(diagnosticsRepository = container.diagnosticsRepository) as T

        modelClass.isAssignableFrom(WorldBookViewModel::class.java) ->
            WorldBookViewModel(worldBookRepository = container.worldBookRepository) as T

        else -> throw IllegalArgumentException("未登记的 ViewModel：${modelClass.name}")
    }
}
