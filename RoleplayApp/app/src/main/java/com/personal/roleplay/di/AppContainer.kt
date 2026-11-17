package com.personal.roleplay.di

import android.content.Context
import com.personal.roleplay.data.backup.BackupManager
import com.personal.roleplay.data.card.CharacterCardImporter
import com.personal.roleplay.data.local.MemoryFtsIndex
import com.personal.roleplay.data.local.RoleplayDatabase
import com.personal.roleplay.data.local.RoomTransactionRunner
import com.personal.roleplay.data.repository.AppSettingsRepositoryImpl
import com.personal.roleplay.data.repository.CharacterRepositoryImpl
import com.personal.roleplay.data.repository.ConversationRepositoryImpl
import com.personal.roleplay.data.repository.DiagnosticsRepositoryImpl
import com.personal.roleplay.data.repository.MemoryRepositoryImpl
import com.personal.roleplay.data.repository.ProviderRepositoryImpl
import com.personal.roleplay.data.repository.UsageRepositoryImpl
import com.personal.roleplay.data.repository.UserPersonaRepositoryImpl
import com.personal.roleplay.data.repository.WorldBookRepositoryImpl
import com.personal.roleplay.data.seed.BuiltInCharacters
import com.personal.roleplay.data.security.SecureKeyVault
import com.personal.roleplay.domain.chat.ChatOrchestrator
import com.personal.roleplay.domain.memory.CoarseHit
import com.personal.roleplay.domain.memory.MemoryEngine
import com.personal.roleplay.domain.memory.MemoryRetriever
import com.personal.roleplay.domain.repository.AppSettingsRepository
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.DiagnosticsRepository
import com.personal.roleplay.domain.repository.MemoryRepository
import com.personal.roleplay.domain.repository.ProviderRepository
import com.personal.roleplay.domain.repository.UsageRepository
import com.personal.roleplay.domain.repository.UserPersonaRepository
import com.personal.roleplay.domain.repository.WorldBookRepository
import com.personal.roleplay.domain.truncation.TruncationEngine
import com.personal.roleplay.domain.usage.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手写依赖容器。
 *
 * 个人项目不上 Hilt：依赖图一眼看得完，调试时不用去猜注入图，
 * 也少一层代码生成。想换 Koin 时替换这一个类即可。
 */
class AppContainer(private val context: Context) {

    /** 应用层作用域：用于启动时的种子数据这类「发后不管」的任务 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: RoleplayDatabase = RoleplayDatabase.create(context)
    private val keyVault: SecureKeyVault = SecureKeyVault(context)
    private val memoryFtsIndex: MemoryFtsIndex = MemoryFtsIndex(database)
    private val transactionRunner: RoomTransactionRunner = RoomTransactionRunner(database)

    val characterRepository: CharacterRepository = CharacterRepositoryImpl(database.characterDao())
    val worldBookRepository: WorldBookRepository = WorldBookRepositoryImpl(database.worldBookDao())
    val providerRepository: ProviderRepository = ProviderRepositoryImpl(database.providerDao(), keyVault)
    val conversationRepository: ConversationRepository =
        ConversationRepositoryImpl(database.sessionDao(), database.messageDao())
    val memoryRepository: MemoryRepository =
        MemoryRepositoryImpl(database.memoryDao(), database.memorySourceDao(), memoryFtsIndex)
    val usageRepository: UsageRepository = UsageRepositoryImpl(database.usageDao())
    val diagnosticsRepository: DiagnosticsRepository = DiagnosticsRepositoryImpl(database.requestLogDao())
    val userPersonaRepository: UserPersonaRepository =
        UserPersonaRepositoryImpl(database.userPersonaDao())
    val appSettingsRepository: AppSettingsRepository =
        AppSettingsRepositoryImpl(database.settingDao())

    /** 角色卡导入：PNG/JSON 解码 + 世界书落库都在这里收口 */
    val characterCardImporter: CharacterCardImporter = CharacterCardImporter(
        context = context,
        characterRepository = characterRepository,
        worldBookRepository = worldBookRepository
    )

    val usageTracker: UsageTracker = UsageTracker(usageRepository)

    val memoryEngine: MemoryEngine = MemoryEngine(
        memoryRepository = memoryRepository,
        conversationRepository = conversationRepository,
        characterRepository = characterRepository,
        resolveConfig = { slot -> providerRepository.resolveForSlot(slot, characterPreference = null) },
        usageTracker = usageTracker,
        transactionRunner = transactionRunner
    )

    val memoryRetriever: MemoryRetriever = MemoryRetriever(
        memoryRepository = memoryRepository,
        coarseSearch = { terms, limit ->
            memoryFtsIndex.search(terms, limit).map { CoarseHit(it.memoryId, it.score) }
        },
        resolveConfig = { slot -> providerRepository.resolveForSlot(slot, characterPreference = null) }
    )

    val chatOrchestrator: ChatOrchestrator = ChatOrchestrator(
        conversationRepository = conversationRepository,
        characterRepository = characterRepository,
        memoryRepository = memoryRepository,
        worldBookRepository = worldBookRepository,
        providerRepository = providerRepository,
        memoryRetriever = memoryRetriever,
        memoryEngine = memoryEngine,
        usageTracker = usageTracker,
        diagnosticsRepository = diagnosticsRepository,
        userPersonaRepository = userPersonaRepository,
        appSettingsRepository = appSettingsRepository
    )

    val truncationEngine: TruncationEngine = TruncationEngine(
        conversationRepository = conversationRepository,
        memoryRepository = memoryRepository,
        transactionRunner = transactionRunner
    )

    val backupManager: BackupManager = BackupManager(
        context = context,
        characterRepository = characterRepository,
        conversationRepository = conversationRepository,
        memoryRepository = memoryRepository,
        worldBookRepository = worldBookRepository,
        providerRepository = providerRepository,
        userPersonaRepository = userPersonaRepository,
        appSettingsRepository = appSettingsRepository,
        keyVault = keyVault
    )

    val keyVaultRef: SecureKeyVault get() = keyVault

    init {
        appScope.launch {
            runCatching { BuiltInCharacters.seedIfNeeded(characterRepository) }
        }
    }
}
