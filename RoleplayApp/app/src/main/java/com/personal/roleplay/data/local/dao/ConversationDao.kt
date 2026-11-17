package com.personal.roleplay.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.personal.roleplay.data.local.entity.MessageEntity
import com.personal.roleplay.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY last_active_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE character_id = :characterId ORDER BY last_active_at DESC")
    fun observeByCharacter(characterId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun observeById(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions")
    suspend fun getAll(): List<SessionEntity>

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM sessions WHERE character_id = :characterId")
    suspend fun deleteByCharacter(characterId: String)
}

/** 每个会话的消息条数，角色详情页一次取完，避免逐会话发查询 */
data class SessionMessageCount(
    @androidx.room.ColumnInfo(name = "session_id") val sessionId: String,
    @androidx.room.ColumnInfo(name = "message_count") val messageCount: Int
)

@Dao
interface MessageDao {

    @Query("SELECT session_id, COUNT(*) AS message_count FROM messages GROUP BY session_id")
    fun observeMessageCounts(): Flow<List<SessionMessageCount>>

    /**
     * 只取最新的 [limit] 条。长会话必须分页加载，
     * 一次性 Flow 全表在几千条消息时会明显卡顿。
     */
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY seq DESC LIMIT :limit")
    suspend fun getLatestWindow(sessionId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY seq ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId AND seq >= :fromSeq ORDER BY seq ASC")
    suspend fun getFromSeq(sessionId: String, fromSeq: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id IN (:messageIds)")
    suspend fun getByIds(messageIds: List<String>): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE session_id = :sessionId")
    suspend fun maxSeq(sessionId: String): Long

    /**
     * 最近一条角色台词的发言人。群聊用它推出下一轮该谁说话。
     * role 取值见 MessageRole.wire，这里写死 'assistant' 与迁移脚本保持一致。
     */
    @Query(
        "SELECT speaker_character_id FROM messages " +
            "WHERE session_id = :sessionId AND role = 'assistant' AND speaker_character_id IS NOT NULL " +
            "ORDER BY seq DESC LIMIT 1"
    )
    suspend fun getLastSpeakerId(sessionId: String): String?

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    fun observeCount(sessionId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun count(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    /**
     * 截断：删除定位点之后（可选包含定位点本身）的全部消息。
     */
    @Query("DELETE FROM messages WHERE session_id = :sessionId AND seq >= :fromSeq")
    suspend fun deleteFromSeq(sessionId: String, fromSeq: Long)

    @Query("DELETE FROM messages WHERE id IN (:messageIds)")
    suspend fun deleteByIds(messageIds: List<String>)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /**
     * 撤销截断：整体还原，seq 由调用方保证连续。
     */
    @Transaction
    suspend fun restoreAll(messages: List<MessageEntity>) = insertAll(messages)
}
