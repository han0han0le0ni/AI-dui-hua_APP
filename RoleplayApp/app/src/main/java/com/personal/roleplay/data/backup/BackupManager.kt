package com.personal.roleplay.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.personal.roleplay.data.security.SecureKeyVault
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemorySourceRef
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.Protocol
import com.personal.roleplay.domain.model.ProviderCapabilities
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.ProviderParams
import com.personal.roleplay.domain.model.ReplyFormat
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.model.SummarySegment
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.domain.model.WorldBook
import com.personal.roleplay.domain.model.WorldBookEntry
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.MemoryRepository
import com.personal.roleplay.domain.repository.AppSettingsRepository
import com.personal.roleplay.domain.repository.ProviderRepository
import com.personal.roleplay.domain.repository.UserPersonaRepository
import com.personal.roleplay.domain.repository.WorldBookRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ImportMode { MERGE, OVERWRITE }

data class BackupSummary(
    val characterCount: Int,
    val sessionCount: Int,
    val messageCount: Int,
    val memoryCount: Int,
    val imageCount: Int
)

// ---------------- 备份用的扁平 DTO ----------------

@Serializable
private data class BackupCharacter(
    val id: String, val name: String, val avatarPath: String?, val tagline: String?,
    val persona: String, val personalityTags: List<String>, val tone: String?, val taboos: String?,
    val catchphrase: String?, val relationPosition: String?, val greeting: String,
    val exampleDialogues: String, val systemPromptTemplate: String?, val customSystemPrompt: Boolean,
    val worldBookId: String?, val accentColor: Long, val providerPreference: String?,
    val createdAt: Long, val updatedAt: Long, val archived: Boolean, val builtIn: Boolean
)

@Serializable
private data class BackupSession(
    val id: String, val characterId: String, val title: String, val userPersona: String?,
    val summarySegments: List<SummarySegment>, val lastExtractedSeq: Long, val lastExtractedAt: Long,
    val roundsSinceExtract: Int, val createdAt: Long, val lastActiveAt: Long,
    /** 演员表。带默认值是为了还能读入旧版本导出的备份 */
    val participantIds: List<String> = emptyList(),
    /** 引用的人设库 id。旧备份没有这一项，读回后靠 userPersona 正文快照兜底 */
    val userPersonaId: String? = null,
    /** 群聊一轮几个角色开口。旧备份没有这一项，按默认值补齐 */
    val groupTurnSize: Int = ChatSession.DEFAULT_GROUP_TURN_SIZE
)

/** 用户人设。跟着备份走，恢复后不必重新录一遍 */
@Serializable
private data class BackupUserPersona(
    val id: String, val name: String, val content: String,
    val isDefault: Boolean, val createdAt: Long, val updatedAt: Long
)

@Serializable
private data class BackupMessage(
    val id: String, val sessionId: String, val seq: Long, val role: String, val content: String,
    val edited: Boolean, val promptTokens: Int, val completionTokens: Int, val createdAt: Long,
    /** 发言人。旧备份没有这一项，读回后按会话归属角色兜底 */
    val speakerCharacterId: String? = null
)

@Serializable
private data class BackupMemory(
    val id: String, val characterId: String, val sessionId: String, val layer: String,
    val content: String, val confidence: Float, val pinned: Boolean, val origin: String,
    val status: String, val createdAt: Long, val updatedAt: Long, val lastRecalledAt: Long
)

@Serializable
private data class BackupMemorySource(
    val id: String, val memoryId: String, val messageId: String, val excerpt: String
)

@Serializable
private data class BackupWorldBook(
    val id: String, val name: String, val description: String?, val createdAt: Long
)

@Serializable
private data class BackupWorldBookEntry(
    val id: String, val worldBookId: String, val keys: List<String>, val content: String,
    val priority: Int, val enabled: Boolean, val constant: Boolean, val insertionOrder: Int
)

/** 备份里的接入配置：故意不含任何 Key 字段 */
@Serializable
private data class BackupProvider(
    val id: String, val name: String, val protocol: String, val baseUrl: String, val modelId: String,
    val params: ProviderParams, val capabilities: ProviderCapabilities, val slot: String, val isDefault: Boolean
)

@Serializable
private data class BackupPayload(
    val version: Int,
    val exportedAt: Long,
    val characters: List<BackupCharacter>,
    val worldBooks: List<BackupWorldBook>,
    val worldBookEntries: List<BackupWorldBookEntry>,
    val sessions: List<BackupSession>,
    val messages: List<BackupMessage>,
    val memories: List<BackupMemory>,
    val memorySources: List<BackupMemorySource>,
    val providers: List<BackupProvider>,
    val userPersonas: List<BackupUserPersona>,
    /** 全局回复格式。旧备份没有，读回后保持本机现值 */
    val replyFormat: ReplyFormat? = null
)

/**
 * 本地备份（11.4）。
 *
 * 导出成 zip：角色、世界书、会话、消息、记忆、溯源、接入配置、立绘图片。
 * **不含 Key**——备份文件默认不含 Key，导入后需重新录入（6.4）。
 */
class BackupManager(
    private val context: Context,
    private val characterRepository: CharacterRepository,
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val worldBookRepository: WorldBookRepository,
    private val providerRepository: ProviderRepository,
    private val userPersonaRepository: UserPersonaRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val keyVault: SecureKeyVault
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun export(destination: Uri): Result<BackupSummary> = runCatching {
        withContext(Dispatchers.IO) {
            val characters = characterRepository.getAll()
            val sessions = conversationRepository.getAllSessions()
            val messages = sessions.flatMap { conversationRepository.getMessages(it.id) }
            val memories = sessions.flatMap { memoryRepository.getBySession(it.id) }
            val sources = memories.flatMap { memoryRepository.getSourcesOfMemory(it.id) }
            val providers = providerRepository.getAll()
            val books = worldBookRepository.getAllBooks()
            val bookEntries = books.flatMap { worldBookRepository.getAllEntries(it.id) }
            val personas = userPersonaRepository.getAll()

            val payload = BackupPayload(
                version = BACKUP_VERSION,
                exportedAt = System.currentTimeMillis(),
                characters = characters.map { it.toBackup() },
                worldBooks = books.map { it.toBackup() },
                worldBookEntries = bookEntries.map { it.toBackup() },
                sessions = sessions.map { it.toBackup() },
                messages = messages.map { it.toBackup() },
                memories = memories.map { it.toBackup() },
                memorySources = sources.map { it.toBackup() },
                // Key 不进备份文件
                providers = providers.map { it.toBackup() },
                userPersonas = personas.map { it.toBackup() },
                replyFormat = appSettingsRepository.getReplyFormat()
            )

            val backupDir = File(context.cacheDir, "backups").apply { mkdirs() }
            val zipFile = File(backupDir, "roleplay-backup-${System.currentTimeMillis()}.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                zip.putNextEntry(ZipEntry("characters.json"))
                zip.write(json.encodeToString(payload.characters).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("sessions.json"))
                zip.write(json.encodeToString(payload.sessions).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("messages.json"))
                zip.write(json.encodeToString(payload.messages).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("memories.json"))
                zip.write(json.encodeToString(payload.memories).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("memory_sources.json"))
                zip.write(json.encodeToString(payload.memorySources).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("world_books.json"))
                zip.write(json.encodeToString(payload.worldBooks).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("world_book_entries.json"))
                zip.write(json.encodeToString(payload.worldBookEntries).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("providers.json"))
                zip.write(json.encodeToString(payload.providers).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("user_personas.json"))
                zip.write(json.encodeToString(payload.userPersonas).toByteArray())
                zip.closeEntry()

                // 没有回复格式时干脆不写这个条目，读回时按「备份里没有」处理
                payload.replyFormat?.let { format ->
                    zip.putNextEntry(ZipEntry("reply_format.json"))
                    zip.write(json.encodeToString(format).toByteArray())
                    zip.closeEntry()
                }

                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(
                    json.encodeToString(
                        mapOf(
                            "version" to BACKUP_VERSION.toString(),
                            "exportedAt" to payload.exportedAt.toString()
                        )
                    ).toByteArray()
                )
                zip.closeEntry()

                var imageCount = 0
                characters.mapNotNull { it.avatarPath }.forEach { path ->
                    val source = File(path)
                    if (source.exists()) {
                        runCatching {
                            zip.putNextEntry(ZipEntry("images/${source.name}"))
                            FileInputStream(source).use { it.copyTo(zip) }
                            zip.closeEntry()
                            imageCount++
                        }
                    }
                }

                context.contentResolver.openOutputStream(destination)?.use { output ->
                    FileInputStream(zipFile).use { it.copyTo(output) }
                } ?: error("无法写入目标文件")

                zipFile.delete()

                BackupSummary(
                    characterCount = characters.size,
                    sessionCount = sessions.size,
                    messageCount = messages.size,
                    memoryCount = memories.size,
                    imageCount = imageCount
                )
            }
        }
    }.onFailure { error ->
        Log.e(TAG, "export: 导出失败", error)
    }

    suspend fun import(source: Uri, mode: ImportMode): Result<BackupSummary> = runCatching {
        withContext(Dispatchers.IO) {
            val stagingDir = File(context.cacheDir, "backups").apply { mkdirs() }
            val staged = File(stagingDir, "import-${System.currentTimeMillis()}.zip")

            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(staged).use { input.copyTo(it) }
            } ?: error("无法读取备份文件")

            val contents = mutableMapOf<String, String>()
            ZipInputStream(FileInputStream(staged)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json")) {
                        // 必须手动循环读取：ZipInputStream 上的 readBytes/readText
                        // 会一直读到整个流结束，把后面的条目一并吞掉
                        val buffer = ByteArrayOutputStream()
                        copyCurrentEntryTo(zip, buffer)
                        contents[entry.name] = buffer.toString(Charsets.UTF_8.name())
                    } else if (!entry.isDirectory && entry.name.startsWith("images/")) {
                        val target = File(avatarDir(), entry.name.removePrefix("images/"))
                        FileOutputStream(target).use { out -> copyCurrentEntryTo(zip, out) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            staged.delete()

            val characters = decodeList<BackupCharacter>(contents["characters.json"])
            val sessions = decodeList<BackupSession>(contents["sessions.json"])
            val messages = decodeList<BackupMessage>(contents["messages.json"])
            val memories = decodeList<BackupMemory>(contents["memories.json"])
            val sources = decodeList<BackupMemorySource>(contents["memory_sources.json"])
            val providers = decodeList<BackupProvider>(contents["providers.json"])
            val books = decodeList<BackupWorldBook>(contents["world_books.json"])
            val bookEntries = decodeList<BackupWorldBookEntry>(contents["world_book_entries.json"])
            val personas = decodeList<BackupUserPersona>(contents["user_personas.json"])
            val replyFormat = contents["reply_format.json"]?.let { raw ->
                runCatching { json.decodeFromString<ReplyFormat>(raw) }.getOrNull()
            }

            if (mode == ImportMode.OVERWRITE) {
                wipeAll()
            }

            val now = System.currentTimeMillis()

            characterRepository.saveAll(characters.map { it.toDomain() })
            books.forEach { worldBookRepository.saveBook(it.toDomain()) }
            bookEntries.forEach { worldBookRepository.saveEntry(it.toDomain()) }
            // 人设先于会话写回：会话里的 userPersonaId 要能指到它们
            personas.forEach { userPersonaRepository.save(it.toDomain()) }
            // 旧备份没有这项，保持本机现值而不是抹掉
            replyFormat?.let { appSettingsRepository.saveReplyFormat(it) }
            providerRepository.let { repo ->
                providers.forEach { backup ->
                    repo.save(backup.toDomain(now))
                }
            }
            sessions.forEach { backup ->
                conversationRepository.updateSession(backup.toDomain())
            }
            conversationRepository.let { repo ->
                messages.forEach { repo.insertMessage(it.toDomain()) }
            }
            memoryRepository.saveAll(memories.map { it.toDomain() })
            memoryRepository.addSources(sources.map { it.toDomain() })

            BackupSummary(
                characterCount = characters.size,
                sessionCount = sessions.size,
                messageCount = messages.size,
                memoryCount = memories.size,
                imageCount = avatarDir().listFiles()?.size ?: 0
            )
        }
    }.onFailure { error ->
        Log.e(TAG, "import: 导入失败", error)
    }

    private fun avatarDir(): File = File(context.filesDir, "avatars").apply { mkdirs() }

    /**
     * 只把「当前条目」的内容拷出去。
     *
     * ZipInputStream 没有按条目截流的语义，InputStream.copyTo/readBytes 都会一路读到
     * 整个 zip 结束。这里手动循环，读到当前条目 EOF（返回 -1）就停。
     */
    private fun copyCurrentEntryTo(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
    }

    private inline fun <reified T> decodeList(raw: String?): List<T> =
        raw?.let { runCatching { json.decodeFromString<List<T>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

    private suspend fun wipeAll() {
        conversationRepository.getAllSessions().forEach { session ->
            memoryRepository.deleteBySession(session.id)
            conversationRepository.deleteMessagesOfSession(session.id)
            conversationRepository.deleteSession(session.id)
        }
        characterRepository.getAll().forEach { characterRepository.delete(it.id) }
        worldBookRepository.getAllBooks().forEach { worldBookRepository.deleteBook(it.id) }
        userPersonaRepository.getAll().forEach { userPersonaRepository.delete(it.id) }
    }

    private companion object {
        const val TAG = "BackupManager"
        /** 2 起包含用户人设与会话的人设引用 */
        const val BACKUP_VERSION = 2
    }
}

// ---------------- 领域模型 ⇄ 备份 DTO ----------------

private fun Character.toBackup() = BackupCharacter(
    id, name, avatarPath, tagline, persona, personalityTags, tone, taboos, catchphrase,
    relationPosition, greeting, exampleDialogues, systemPromptTemplate, customSystemPrompt,
    worldBookId, accentColorArgb, providerPreference, createdAt, updatedAt, archived, builtIn
)

private fun BackupCharacter.toDomain() = Character(
    id, name, avatarPath, tagline, persona, personalityTags, tone, taboos, catchphrase,
    relationPosition, greeting, exampleDialogues, systemPromptTemplate, customSystemPrompt,
    worldBookId, accentColor, providerPreference, createdAt, updatedAt, archived, builtIn
)

private fun WorldBook.toBackup() = BackupWorldBook(id, name, description, createdAt)

private fun BackupWorldBook.toDomain() = WorldBook(id, name, description, createdAt)

private fun UserPersona.toBackup() = BackupUserPersona(
    id, name, content, isDefault, createdAt, updatedAt
)

private fun BackupUserPersona.toDomain() = UserPersona(
    id, name, content, isDefault, createdAt, updatedAt
)

private fun WorldBookEntry.toBackup() = BackupWorldBookEntry(
    id, worldBookId, keys, content, priority, enabled, constant, insertionOrder
)

private fun BackupWorldBookEntry.toDomain() = WorldBookEntry(
    id, worldBookId, keys, content, priority, enabled, constant, insertionOrder
)

private fun ChatSession.toBackup() = BackupSession(
    id = id,
    characterId = characterId,
    title = title,
    userPersonaId = userPersonaId,
    userPersona = userPersona,
    summarySegments = summarySegments,
    lastExtractedSeq = lastExtractedSeq,
    lastExtractedAt = lastExtractedAt,
    roundsSinceExtract = roundsSinceExtract,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
    participantIds = cast,
    groupTurnSize = groupTurnSize
)

private fun BackupSession.toDomain() = ChatSession(
    id = id,
    characterId = characterId,
    // 旧备份没有演员表，按归属角色兜底成单人线
    participantIds = participantIds.ifEmpty { listOf(characterId) },
    title = title,
    // 旧备份没有这一项，读回后靠 userPersona 正文快照兜底
    userPersonaId = userPersonaId,
    userPersona = userPersona,
    summarySegments = summarySegments,
    lastExtractedSeq = lastExtractedSeq,
    lastExtractedAt = lastExtractedAt,
    roundsSinceExtract = roundsSinceExtract,
    groupTurnSize = groupTurnSize,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt
)

private fun ChatMessage.toBackup() = BackupMessage(
    id = id,
    sessionId = sessionId,
    seq = seq,
    role = role.wire,
    content = content,
    edited = edited,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    createdAt = createdAt,
    speakerCharacterId = speakerCharacterId
)

private fun BackupMessage.toDomain() = ChatMessage(
    id = id,
    sessionId = sessionId,
    seq = seq,
    role = MessageRole.fromWire(role),
    speakerCharacterId = speakerCharacterId,
    content = content,
    edited = edited,
    providerSnapshot = null,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    createdAt = createdAt
)

private fun MemoryItem.toBackup() = BackupMemory(
    id, characterId, sessionId, layer.wire, content, confidence, pinned, origin.wire, status.wire,
    createdAt, updatedAt, lastRecalledAt
)

private fun BackupMemory.toDomain() = MemoryItem(
    id, characterId, sessionId, com.personal.roleplay.domain.model.MemoryLayer.fromWire(layer),
    content, confidence, pinned, com.personal.roleplay.domain.model.MemoryOrigin.fromWire(origin),
    com.personal.roleplay.domain.model.MemoryStatus.fromWire(status),
    createdAt, updatedAt, lastRecalledAt
)

private fun MemorySourceRef.toBackup() = BackupMemorySource(id, memoryId, messageId, excerpt)

private fun BackupMemorySource.toDomain() = MemorySourceRef(id, memoryId, messageId, excerpt)

private fun ProviderConfig.toBackup() = BackupProvider(
    id, name, protocol.wire, baseUrl, modelId, params, capabilities, slot.wire, isDefault
)

/** 导入的接入配置没有 Key，重新分配一个空别名，界面上会提示「需重新录入 Key」 */
private fun BackupProvider.toDomain(now: Long) = ProviderConfig(
    id = id,
    name = name,
    protocol = Protocol.fromWire(protocol),
    baseUrl = baseUrl,
    modelId = modelId,
    params = params,
    capabilities = capabilities,
    slot = Slot.fromWire(slot),
    isDefault = isDefault,
    keyAlias = SecureKeyVault.newAlias(),
    createdAt = now,
    updatedAt = now,
    apiKey = ""
)

/** 立绘目录对外暴露，供导入后重写 avatarPath */
fun avatarDirectory(context: Context): File = File(context.filesDir, "avatars").apply { mkdirs() }
