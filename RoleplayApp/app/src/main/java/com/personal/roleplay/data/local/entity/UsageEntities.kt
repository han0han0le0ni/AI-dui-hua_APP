package com.personal.roleplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personal.roleplay.domain.model.PromptBlockRecord

/**
 * token 与费用记录。用于「用量」页按日/月、按模型、按角色、按槽位统计，防止 Key 被烧。
 */
@Entity(
    tableName = "usage_record",
    indices = [Index("created_at"), Index("session_id"), Index("character_id"), Index("slot")]
)
data class UsageRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "provider_id")
    val providerId: String,

    @ColumnInfo(name = "provider_name")
    val providerName: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    /** 取值见 Slot.wire */
    @ColumnInfo(name = "slot")
    val slot: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String?,

    @ColumnInfo(name = "character_id")
    val characterId: String?,

    @ColumnInfo(name = "prompt_tokens")
    val promptTokens: Int,

    @ColumnInfo(name = "completion_tokens")
    val completionTokens: Int,

    /** 按内置价目表估算，单位：元 */
    @ColumnInfo(name = "estimated_cost")
    val estimatedCost: Double,

    @ColumnInfo(name = "latency_ms")
    val latencyMs: Long,

    @ColumnInfo(name = "success")
    val success: Boolean,

    @ColumnInfo(name = "error_message")
    val errorMessage: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

/**
 * 调试面板用的一次请求快照：分区块的完整 prompt、各区块 token、模型返回原文、耗时、错误。
 */
@Entity(
    tableName = "request_log",
    indices = [Index("created_at"), Index("session_id")]
)
data class RequestLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String?,

    @ColumnInfo(name = "slot")
    val slot: String,

    @ColumnInfo(name = "provider_name")
    val providerName: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    /** 完整 prompt 文本，用于「复制 / 导出」 */
    @ColumnInfo(name = "full_prompt")
    val fullPrompt: String,

    /** PromptBlockRecord 序列化列表 */
    @ColumnInfo(name = "blocks_json")
    val blocksJson: List<PromptBlockRecord>,

    @ColumnInfo(name = "raw_response")
    val rawResponse: String?,

    @ColumnInfo(name = "error_message")
    val errorMessage: String?,

    @ColumnInfo(name = "prompt_tokens")
    val promptTokens: Int,

    @ColumnInfo(name = "completion_tokens")
    val completionTokens: Int,

    @ColumnInfo(name = "latency_ms")
    val latencyMs: Long,

    @ColumnInfo(name = "success")
    val success: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
