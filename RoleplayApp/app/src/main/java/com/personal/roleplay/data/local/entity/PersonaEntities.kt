package com.personal.roleplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户人设。全局共享，不绑会话——会话只存它的 id，
 * 这样改一次人设，所有引用它的线一起生效。
 */
@Entity(
    tableName = "user_persona",
    indices = [Index("is_default"), Index("created_at")]
)
data class UserPersonaEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 便于辨认的短名字 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 人设正文，注入「对方是谁」小节 */
    @ColumnInfo(name = "content")
    val content: String,

    /** 新会话默认套用；全局最多一个，由 DAO 的 clearDefaultExcept 保证 */
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
