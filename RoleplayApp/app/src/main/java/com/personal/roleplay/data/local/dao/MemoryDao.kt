package com.personal.roleplay.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.personal.roleplay.data.local.entity.MemoryEntity
import com.personal.roleplay.data.local.entity.MemorySourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query(
        """
        SELECT * FROM memory
        WHERE session_id = :sessionId AND status != 'deleted'
        ORDER BY pinned DESC, confidence DESC, updated_at DESC
        """
    )
    fun observeBySession(sessionId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE session_id = :sessionId AND status != 'deleted'")
    suspend fun getBySession(sessionId: String): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memory
        WHERE session_id = :sessionId AND layer = :layer AND status != 'deleted'
        ORDER BY pinned DESC, confidence DESC, updated_at DESC
        """
    )
    suspend fun getByLayer(sessionId: String, layer: String): List<MemoryEntity>

    @Query("SELECT * FROM memory WHERE id IN (:memoryIds)")
    suspend fun getByIds(memoryIds: List<String>): List<MemoryEntity>

    @Query("SELECT * FROM memory WHERE session_id = :sessionId")
    suspend fun getAllIncludingDeleted(sessionId: String): List<MemoryEntity>

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    @Upsert
    suspend fun upsertAll(memories: List<MemoryEntity>)

    @Query("UPDATE memory SET status = :status WHERE id IN (:memoryIds)")
    suspend fun setStatus(memoryIds: List<String>, status: String)

    @Query("UPDATE memory SET last_recalled_at = :timestamp WHERE id IN (:memoryIds)")
    suspend fun markRecalled(memoryIds: List<String>, timestamp: Long)

    @Query("DELETE FROM memory WHERE id IN (:memoryIds)")
    suspend fun deleteByIds(memoryIds: List<String>)

    @Query("DELETE FROM memory WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

@Dao
interface MemorySourceDao {

    @Query("SELECT * FROM memory_source WHERE memory_id = :memoryId")
    suspend fun getByMemory(memoryId: String): List<MemorySourceEntity>

    @Query("SELECT * FROM memory_source WHERE memory_id IN (:memoryIds)")
    suspend fun getByMemories(memoryIds: List<String>): List<MemorySourceEntity>

    /**
     * 级联删除的核心查询：给定一批被删消息，找出所有引用了它们的溯源记录。
     */
    @Query("SELECT * FROM memory_source WHERE message_id IN (:messageIds)")
    suspend fun getByMessages(messageIds: List<String>): List<MemorySourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sources: List<MemorySourceEntity>)

    @Query("DELETE FROM memory_source WHERE memory_id = :memoryId AND message_id IN (:messageIds)")
    suspend fun deleteRefs(memoryId: String, messageIds: List<String>)

    @Query("DELETE FROM memory_source WHERE memory_id IN (:memoryIds)")
    suspend fun deleteByMemories(memoryIds: List<String>)

    @Query("DELETE FROM memory_source WHERE message_id IN (:messageIds)")
    suspend fun deleteByMessages(messageIds: List<String>)

    @Query("DELETE FROM memory_source WHERE memory_id IN (:memoryIds)")
    suspend fun deleteAllOf(memoryIds: List<String>)
}
