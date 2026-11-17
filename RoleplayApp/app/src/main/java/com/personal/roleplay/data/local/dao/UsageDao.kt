package com.personal.roleplay.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.personal.roleplay.data.local.entity.RequestLogEntity
import com.personal.roleplay.data.local.entity.UsageRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 汇总行。所有聚合列都用 COALESCE，避免空表时 SUM 返回 NULL 导致映射失败。
 */
data class UsageTotals(
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int,
    @ColumnInfo(name = "estimated_cost") val estimatedCost: Double,
    @ColumnInfo(name = "call_count") val callCount: Int
)

/**
 * 按模型聚合的用量。
 */
data class ModelUsageRow(
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int,
    @ColumnInfo(name = "estimated_cost") val estimatedCost: Double,
    @ColumnInfo(name = "call_count") val callCount: Int
)

data class SlotUsageRow(
    @ColumnInfo(name = "slot") val slot: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int,
    @ColumnInfo(name = "estimated_cost") val estimatedCost: Double,
    @ColumnInfo(name = "call_count") val callCount: Int
)

data class CharacterUsageRow(
    @ColumnInfo(name = "character_id") val characterId: String?,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int,
    @ColumnInfo(name = "estimated_cost") val estimatedCost: Double,
    @ColumnInfo(name = "call_count") val callCount: Int
)

@Dao
interface UsageDao {

    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Query(
        """
        SELECT COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(estimated_cost), 0.0) AS estimated_cost,
               COUNT(*) AS call_count
        FROM usage_record WHERE created_at >= :from
        """
    )
    fun observeTotalsSince(from: Long): Flow<UsageTotals>

    @Query(
        """
        SELECT model_id,
               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(estimated_cost), 0.0) AS estimated_cost,
               COUNT(*) AS call_count
        FROM usage_record WHERE created_at >= :from
        GROUP BY model_id ORDER BY estimated_cost DESC
        """
    )
    fun observeByModel(from: Long): Flow<List<ModelUsageRow>>

    @Query(
        """
        SELECT slot,
               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(estimated_cost), 0.0) AS estimated_cost,
               COUNT(*) AS call_count
        FROM usage_record WHERE created_at >= :from
        GROUP BY slot ORDER BY estimated_cost DESC
        """
    )
    fun observeBySlot(from: Long): Flow<List<SlotUsageRow>>

    @Query(
        """
        SELECT character_id,
               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(estimated_cost), 0.0) AS estimated_cost,
               COUNT(*) AS call_count
        FROM usage_record WHERE created_at >= :from
        GROUP BY character_id ORDER BY estimated_cost DESC
        """
    )
    fun observeByCharacter(from: Long): Flow<List<CharacterUsageRow>>

    @Query("SELECT * FROM usage_record WHERE created_at >= :from ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(from: Long, limit: Int): Flow<List<UsageRecordEntity>>

    @Query("DELETE FROM usage_record WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT COUNT(*) FROM usage_record")
    suspend fun count(): Int
}

@Dao
interface RequestLogDao {

    @Upsert
    suspend fun upsert(log: RequestLogEntity)

    @Query("SELECT * FROM request_log ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RequestLogEntity>>

    @Query("SELECT * FROM request_log WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    fun observeBySession(sessionId: String, limit: Int): Flow<List<RequestLogEntity>>

    @Query("SELECT * FROM request_log WHERE id = :logId")
    suspend fun getById(logId: String): RequestLogEntity?

    @Query("DELETE FROM request_log WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)
}
