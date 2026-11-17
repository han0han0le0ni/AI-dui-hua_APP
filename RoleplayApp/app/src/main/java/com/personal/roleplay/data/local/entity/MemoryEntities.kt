package com.personal.roleplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆。session_id 必填：删会话即删全部记忆，没有例外。
 *
 * L0 人设不进这张表（直接从 character 读），L5 世界不进这张表（走 world_book_entry），
 * 所以这里只有 L1-L4，全部是会话级。
 */
@Entity(
    tableName = "memory",
    indices = [
        Index("session_id"),
        Index("character_id"),
        Index(value = ["session_id", "layer"]),
        Index("status")
    ]
)
data class MemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "character_id")
    val characterId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** 取值见 MemoryLayer.wire：L1 / L2 / L3 / L4 */
    @ColumnInfo(name = "layer")
    val layer: String,

    @ColumnInfo(name = "content")
    val content: String,

    /** 0f - 1f，来源越少越低 */
    @ColumnInfo(name = "confidence")
    val confidence: Float,

    /** 置顶：注入优先级更高、不参与淘汰；删会话时仍随之删除 */
    @ColumnInfo(name = "pinned")
    val pinned: Boolean,

    /** 取值见 MemoryOrigin.wire */
    @ColumnInfo(name = "origin")
    val origin: String,

    /** 取值见 MemoryStatus.wire */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "last_recalled_at")
    val lastRecalledAt: Long
)

/**
 * 记忆溯源。一条记忆可对应多条来源消息，一条消息也可支撑多条记忆。
 */
@Entity(
    tableName = "memory_source",
    indices = [Index("memory_id"), Index("message_id")]
)
data class MemorySourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "memory_id")
    val memoryId: String,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    /** 支撑这条记忆的那句原文片段，界面上直接展示 */
    @ColumnInfo(name = "excerpt")
    val excerpt: String
)
