package com.personal.roleplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 全局设置的键值表。
 *
 * 单表存 JSON 值而不是给每个设置开一列：设置项会持续增加，
 * 每加一项都升一次数据库版本太笨重，这里只存字符串、由仓储负责编解码。
 */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "setting_key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String
)
