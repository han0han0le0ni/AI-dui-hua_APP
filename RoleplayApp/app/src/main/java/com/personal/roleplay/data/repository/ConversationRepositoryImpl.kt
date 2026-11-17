package com.personal.roleplay.data.repository

import com.personal.roleplay.data.local.dao.MessageDao
import com.personal.roleplay.data.local.dao.SessionDao
import com.personal.roleplay.data.mapper.toDomain
import com.personal.roleplay.data.mapper.toEntity
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepositoryImpl(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) : ConversationRepository {

    override fun observeAllSessions(): Flow<List<ChatSession>> =
        sessionDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeMessageCounts(): Flow<Map<String, Int>> =
        messageDao.observeMessageCounts().map { rows ->
            rows.associate { it.sessionId to it.messageCount }
        }

    override fun observeSessionsByCharacter(characterId: String): Flow<List<ChatSession>> =
        sessionDao.observeByCharacter(characterId).map { list -> list.map { it.toDomain() } }

    override fun observeSession(sessionId: String): Flow<ChatSession?> =
        sessionDao.observeById(sessionId).map { it?.toDomain() }

    override suspend fun getSession(sessionId: String): ChatSession? =
        sessionDao.getById(sessionId)?.toDomain()

    override suspend fun getAllSessions(): List<ChatSession> =
        sessionDao.getAll().map { it.toDomain() }

    override suspend fun createSession(
        characterId: String,
        title: String,
        userPersonaId: String?,
        userPersona: String?,
        participantIds: List<String>,
        groupTurnSize: Int
    ): ChatSession {
        val now = System.currentTimeMillis()
        // 演员表去重且保序，避免同一个角色在群里出现两次导致轮转错乱
        val cast = participantIds.filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf(characterId) }
        val session = ChatSession(
            id = Ids.new(),
            characterId = characterId,
            participantIds = cast,
            title = title,
            userPersonaId = userPersonaId,
            userPersona = userPersona,
            summarySegments = emptyList(),
            lastExtractedSeq = 0L,
            lastExtractedAt = 0L,
            roundsSinceExtract = 0,
            groupTurnSize = groupTurnSize,
            createdAt = now,
            lastActiveAt = now
        )
        sessionDao.upsert(session.toEntity())
        return session
    }

    override suspend fun updateSession(session: ChatSession) {
        sessionDao.upsert(session.toEntity())
    }

    override suspend fun touchSession(sessionId: String, timestamp: Long) {
        val entity = sessionDao.getById(sessionId) ?: return
        sessionDao.upsert(entity.copy(lastActiveAt = timestamp))
    }

    override suspend fun deleteSession(sessionId: String) {
        messageDao.deleteBySession(sessionId)
        sessionDao.delete(sessionId)
    }

    override suspend fun deleteSessionsOfCharacter(characterId: String) {
        sessionDao.getAll()
            .filter { it.characterId == characterId }
            .forEach { messageDao.deleteBySession(it.id) }
        sessionDao.deleteByCharacter(characterId)
    }

    override fun observeMessageCount(sessionId: String): Flow<Int> =
        messageDao.observeCount(sessionId)

    override suspend fun countMessages(sessionId: String): Int = messageDao.count(sessionId)

    /** 窗口取的是倒序结果，翻回正序再交给上层 */
    override suspend fun getLatestWindow(sessionId: String, limit: Int): List<ChatMessage> =
        messageDao.getLatestWindow(sessionId, limit)
            .map { it.toDomain() }
            .asReversed()

    override suspend fun getMessages(sessionId: String): List<ChatMessage> =
        messageDao.getBySession(sessionId).map { it.toDomain() }

    override suspend fun getMessagesFrom(sessionId: String, fromSeq: Long): List<ChatMessage> =
        messageDao.getFromSeq(sessionId, fromSeq).map { it.toDomain() }

    override suspend fun getMessage(messageId: String): ChatMessage? =
        messageDao.getById(messageId)?.toDomain()

    override suspend fun getMessagesByIds(messageIds: List<String>): List<ChatMessage> =
        if (messageIds.isEmpty()) emptyList()
        else messageDao.getByIds(messageIds).map { it.toDomain() }

    override suspend fun getLastSpeakerId(sessionId: String): String? =
        messageDao.getLastSpeakerId(sessionId)

    override suspend fun nextSeq(sessionId: String): Long = messageDao.maxSeq(sessionId) + 1L

    override suspend fun insertMessage(message: ChatMessage) {
        messageDao.insert(message.toEntity())
    }

    override suspend fun insertMessages(messages: List<ChatMessage>) {
        messageDao.insertAll(messages.map { it.toEntity() })
    }

    override suspend fun updateMessage(message: ChatMessage) {
        messageDao.update(message.toEntity())
    }

    override suspend fun deleteMessages(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        messageDao.deleteByIds(messageIds)
    }

    override suspend fun deleteFromSeq(sessionId: String, fromSeq: Long) {
        messageDao.deleteFromSeq(sessionId, fromSeq)
    }

    override suspend fun deleteMessagesOfSession(sessionId: String) {
        messageDao.deleteBySession(sessionId)
    }
}
