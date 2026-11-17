package com.personal.roleplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色卡。L0 人设全部落在这张表里，不进 memory 表。
 */
@Entity(
    tableName = "characters",
    indices = [Index("archived"), Index("created_at")]
)
data class CharacterEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** 立绘文件绝对路径，位于 app 私有目录 */
    @ColumnInfo(name = "avatar_path")
    val avatarPath: String?,

    /** 一句话人设，用于卡片网格副标题 */
    @ColumnInfo(name = "tagline")
    val tagline: String?,

    /** 人设正文：身份、语气、价值观、禁忌、口头禅 */
    @ColumnInfo(name = "persona")
    val persona: String,

    /** 性格标签，如 ["外冷内热", "刀子嘴"] */
    @ColumnInfo(name = "personality_tags")
    val personalityTags: List<String>,

    @ColumnInfo(name = "tone")
    val tone: String?,

    @ColumnInfo(name = "taboos")
    val taboos: String?,

    @ColumnInfo(name = "catchphrase")
    val catchphrase: String?,

    /** 关系定位，如 "同门师兄" */
    @ColumnInfo(name = "relation_position")
    val relationPosition: String?,

    /** 开场白：角色主动说的第一句，绝不让你面对空白输入框 */
    @ColumnInfo(name = "greeting")
    val greeting: String,

    /** 示例对话原文，人设锚定用 */
    @ColumnInfo(name = "example_dialogues")
    val exampleDialogues: String,

    /** 自定义系统提示词模板；为空时用内置默认模板 */
    @ColumnInfo(name = "system_prompt_template")
    val systemPromptTemplate: String?,

    /** 完全自定义 system prompt 开关 */
    @ColumnInfo(name = "custom_system_prompt")
    val customSystemPrompt: Boolean,

    @ColumnInfo(name = "world_book_id")
    val worldBookId: String?,

    /** 主题色（ARGB 打包成 Long），进入对话页全局切换 */
    @ColumnInfo(name = "accent_color")
    val accentColor: Long,

    /** 该角色偏好的接入配置 id；为空则用默认配置 */
    @ColumnInfo(name = "provider_preference")
    val providerPreference: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "archived")
    val archived: Boolean,

    /** 内置示例角色，可归档可删除 */
    @ColumnInfo(name = "built_in")
    val builtIn: Boolean
)

/**
 * 世界书（L5）。
 */
@Entity(
    tableName = "world_book",
    indices = [Index("created_at")]
)
data class WorldBookEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

/**
 * 世界书条目：触发词 + 内容 + 优先级 + 是否常驻 + 插入位置。
 */
@Entity(
    tableName = "world_book_entry",
    indices = [Index("world_book_id"), Index("world_book_id", "priority")]
)
data class WorldBookEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "world_book_id")
    val worldBookId: String,

    /** 触发词列表 */
    @ColumnInfo(name = "keys")
    val keys: List<String>,

    @ColumnInfo(name = "content")
    val content: String,

    /** 数值越大越优先注入，超预算时先截断低优先级条目 */
    @ColumnInfo(name = "priority")
    val priority: Int,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean,

    /** 常驻条目：不依赖关键词命中，始终注入 */
    @ColumnInfo(name = "constant")
    val constant: Boolean,

    /** 插入顺序，同优先级内按此排序 */
    @ColumnInfo(name = "insertion_order")
    val insertionOrder: Int
)
