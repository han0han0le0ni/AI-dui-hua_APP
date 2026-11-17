package com.personal.roleplay.domain.repository

import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.CharacterUsage
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MemorySourceRef
import com.personal.roleplay.domain.model.MemoryStatus
import com.personal.roleplay.domain.model.MemoryWithSources
import com.personal.roleplay.domain.model.ModelUsage
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.ReplyFormat
import com.personal.roleplay.domain.model.RequestLog
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.model.SlotUsage
import com.personal.roleplay.domain.model.UsageRecord
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.domain.model.UsageTotals
import com.personal.roleplay.domain.model.WorldBook
import com.personal.roleplay.domain.model.WorldBookEntry
import kotlinx.coroutines.flow.Flow

interface CharacterRepository {
    /** 含已归档的角色，供 id → 名字反查 */
    fun observeAll(): Flow<List<Character>>
    fun observeActive(): Flow<List<Character>>
    fun observeArchived(): Flow<List<Character>>
    fun observeById(characterId: String): Flow<Character?>
    suspend fun getById(characterId: String): Character?
    suspend fun getAll(): List<Character>
    suspend fun count(): Int
    suspend fun save(character: Character)
    suspend fun saveAll(characters: List<Character>)
    suspend fun setArchived(characterId: String, archived: Boolean)
    suspend fun delete(characterId: String)
}

interface ProviderRepository {
    fun observeAll(): Flow<List<ProviderConfig>>
    suspend fun getAll(): List<ProviderConfig>
    suspend fun getById(providerId: String): ProviderConfig?

    /**
     * 槽位路由（6.3）：角色偏好优先，其次该槽位的默认配置，最后退回同槽位任意一个。
     */
    suspend fun resolveForSlot(slot: Slot, characterPreference: String?): ProviderConfig?

    suspend fun save(config: ProviderConfig)
    suspend fun setAsDefault(providerId: String)
    suspend fun delete(providerId: String)
}

interface ConversationRepository {
    /** 全量会话，角色列表页按「最近在聊」排序要用 */
    fun observeAllSessions(): Flow<List<ChatSession>>

    /** 每个会话的消息条数，角色详情页一次取完 */
    fun observeMessageCounts(): Flow<Map<String, Int>>

    fun observeSessionsByCharacter(characterId: String): Flow<List<ChatSession>>
    fun observeSession(sessionId: String): Flow<ChatSession?>
    suspend fun getSession(sessionId: String): ChatSession?
    suspend fun getAllSessions(): List<ChatSession>

    /**
     * 新建会话。
     *
     * [participantIds] 是演员表，顺序即发言轮转顺序。单人线传一个元素即可；
     * 群聊传多个，[characterId] 作为归属角色（角色列表按它分组）。
     *
     * [userPersonaId] 指向人设库，注入时现取，改人设全体生效；
     * [userPersona] 是建线当时的正文快照，人设被删后靠它兜底。
     */
    suspend fun createSession(
        characterId: String,
        title: String,
        userPersonaId: String?,
        userPersona: String?,
        participantIds: List<String>,
        /** 群聊一轮让几个角色开口；单人线忽略 */
        groupTurnSize: Int = ChatSession.DEFAULT_GROUP_TURN_SIZE
    ): ChatSession
    suspend fun updateSession(session: ChatSession)
    suspend fun touchSession(sessionId: String, timestamp: Long)
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteSessionsOfCharacter(characterId: String)

    fun observeMessageCount(sessionId: String): Flow<Int>

    /** 一次性取条数。比 getMessages().size 省掉一整轮实体到领域对象的映射 */
    suspend fun countMessages(sessionId: String): Int

    /** 分页加载：只取最新的 limit 条，避免几千条消息一次性进内存 */
    suspend fun getLatestWindow(sessionId: String, limit: Int): List<ChatMessage>
    suspend fun getMessages(sessionId: String): List<ChatMessage>
    suspend fun getMessagesFrom(sessionId: String, fromSeq: Long): List<ChatMessage>
    suspend fun getMessage(messageId: String): ChatMessage?
    suspend fun getMessagesByIds(messageIds: List<String>): List<ChatMessage>

    /**
     * 最近一条角色台词的发言人 id。群聊据此推出下一轮该谁开口。
     *
     * 刻意不把轮转位置存进会话：从消息反推，截断/重生成/改消息后轮转会自然回退，
     * 不会出现「消息删了但轮转指针还停在后面」的错位。
     */
    suspend fun getLastSpeakerId(sessionId: String): String?
    suspend fun nextSeq(sessionId: String): Long
    suspend fun insertMessage(message: ChatMessage)
    suspend fun insertMessages(messages: List<ChatMessage>)
    suspend fun updateMessage(message: ChatMessage)
    suspend fun deleteMessages(messageIds: List<String>)
    suspend fun deleteFromSeq(sessionId: String, fromSeq: Long)
    suspend fun deleteMessagesOfSession(sessionId: String)
}

interface MemoryRepository {
    fun observeBySession(sessionId: String): Flow<List<MemoryItem>>
    suspend fun getBySession(sessionId: String): List<MemoryItem>
    suspend fun getByLayer(sessionId: String, layer: MemoryLayer): List<MemoryItem>
    suspend fun getByIds(memoryIds: List<String>): List<MemoryItem>
    suspend fun getWithSources(sessionId: String): List<MemoryWithSources>
    suspend fun getSourcesOfMemory(memoryId: String): List<MemorySourceRef>

    /** 级联删除的核心查询：哪些溯源记录引用了这批消息 */
    suspend fun getSourcesOfMessages(messageIds: List<String>): List<MemorySourceRef>

    suspend fun save(memory: MemoryItem)
    suspend fun saveAll(memories: List<MemoryItem>)
    suspend fun addSources(sources: List<MemorySourceRef>)
    suspend fun removeSourceRefs(memoryId: String, messageIds: List<String>)
    suspend fun deleteMemories(memoryIds: List<String>)
    suspend fun deleteBySession(sessionId: String)
    suspend fun setStatus(memoryIds: List<String>, status: MemoryStatus)
    suspend fun markRecalled(memoryIds: List<String>, timestamp: Long)
}

interface WorldBookRepository {
    fun observeBooks(): Flow<List<WorldBook>>
    fun observeEntries(worldBookId: String): Flow<List<WorldBookEntry>>
    suspend fun getBook(worldBookId: String): WorldBook?
    suspend fun getAllBooks(): List<WorldBook>
    suspend fun getAllEntries(worldBookId: String): List<WorldBookEntry>
    suspend fun getEnabledEntries(worldBookId: String): List<WorldBookEntry>
    suspend fun createBook(name: String, description: String?): WorldBook
    /** 备份恢复用：按给定 id 原样写回 */
    suspend fun saveBook(book: WorldBook)
    suspend fun saveEntry(entry: WorldBookEntry)
    suspend fun deleteEntry(entryId: String)
    suspend fun deleteBook(worldBookId: String)
}

/**
 * 用户人设库。
 *
 * 全局共享：设置里维护，角色单线与群聊都从它读取，不再逐条手打。
 */
interface UserPersonaRepository {
    fun observeAll(): Flow<List<UserPersona>>
    suspend fun getAll(): List<UserPersona>
    suspend fun getById(personaId: String): UserPersona?

    /** 新会话默认套用的人设。没显式设默认时退回第一条，保证「直接读取」总有结果 */
    suspend fun getDefault(): UserPersona?

    /** 保存。[UserPersona.isDefault] 为真时顺带把其他人的默认标记清掉 */
    suspend fun save(persona: UserPersona)
    suspend fun setAsDefault(personaId: String)
    suspend fun delete(personaId: String)
    suspend fun count(): Int
}

/**
 * 全局设置。
 *
 * 目前只有回复格式一项，但用键值表存：以后加设置项不必再升数据库版本。
 */
interface AppSettingsRepository {
    fun observeReplyFormat(): Flow<ReplyFormat>
    suspend fun getReplyFormat(): ReplyFormat
    suspend fun saveReplyFormat(format: ReplyFormat)
}

interface UsageRepository {
    suspend fun record(record: UsageRecord)
    fun observeTotalsSince(from: Long): Flow<UsageTotals>
    fun observeByModel(from: Long): Flow<List<ModelUsage>>
    fun observeBySlot(from: Long): Flow<List<SlotUsage>>
    fun observeByCharacter(from: Long): Flow<List<CharacterUsage>>
    fun observeRecent(from: Long, limit: Int): Flow<List<UsageRecord>>
    suspend fun deleteBefore(before: Long)
}

interface DiagnosticsRepository {
    suspend fun save(log: RequestLog)
    fun observeRecent(limit: Int): Flow<List<RequestLog>>
    fun observeBySession(sessionId: String, limit: Int): Flow<List<RequestLog>>
    suspend fun getById(logId: String): RequestLog?
    suspend fun deleteBefore(before: Long)
}
