package com.personal.roleplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personal.roleplay.domain.model.ProviderSnapshot
import com.personal.roleplay.domain.model.SummarySegment

/**
 * 会话：一条线性消息序列。一个角色可以有多个会话（「第二天」「重开一次」「if 线」）。
 *
 * 分段摘要 [SummarySegment] 与消息快照 [ProviderSnapshot] 定义在领域层，数据层只引用。
 */
@Entity(
    tableName = "sessions",
    indices = [Index("character_id"), Index("last_active_at")]
)
data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "character_id")
    val characterId: String,

    /**
     * 演员表，JSON 数组，顺序即发言轮转顺序。
     * 单人线只有一个元素；群聊按加入顺序排列。
     */
    @ColumnInfo(name = "participant_ids")
    val participantIds: List<String>,

    @ColumnInfo(name = "title")
    val title: String,

    /** 引用的人设库 id，组装时现取正文；为空表示这条线没有扮演身份 */
    @ColumnInfo(name = "user_persona_id")
    val userPersonaId: String?,

    /**
     * 「我扮演谁」的正文快照，兼作人设被删后的兜底。
     * 同一角色下 A 线是弟子、B 线是掌门，TA 对你的认知不能混在一起，
     * 这也是 L2 必须绑在会话上的原因。
     */
    @ColumnInfo(name = "user_persona")
    val userPersona: String?,

    /** L1 摘要分段，SummarySegment 序列化列表 */
    @ColumnInfo(name = "summary_segments")
    val summarySegments: List<SummarySegment>,

    /** 上次抽取时扫描到的最大 seq，避免重复扫描 */
    @ColumnInfo(name = "last_extracted_seq")
    val lastExtractedSeq: Long,

    @ColumnInfo(name = "last_extracted_at")
    val lastExtractedAt: Long,

    /** 距上次抽取累计的轮次，满 8 轮触发 */
    @ColumnInfo(name = "rounds_since_extract")
    val roundsSinceExtract: Int,

    /**
     * 群聊一轮里让几个角色开口，单人线忽略。
     * defaultValue 必须与迁移脚本里的 DEFAULT 一致，否则 Room 的 schema 校验会失败。
     */
    @ColumnInfo(name = "group_turn_size", defaultValue = "2")
    val groupTurnSize: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_active_at")
    val lastActiveAt: Long
)

/**
 * 消息。seq 在会话内单调递增，只用于排序与定位截断位置。
 *
 * 刻意不建外键级联：删除消息必须先算出受影响的记忆再一起删（见 8.5），
 * 交给数据库 ON DELETE CASCADE 会跳过这套溯源计算，因此全部在代码里显式处理。
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["session_id", "seq"], unique = true),
        Index("session_id"),
        Index("created_at")
    ]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "seq")
    val seq: Long,

    /** 取值见 MessageRole.wire */
    @ColumnInfo(name = "role")
    val role: String,

    /** 发言角色 id。群聊必须逐条记录，否则历史里分不出谁说了什么 */
    @ColumnInfo(name = "speaker_character_id")
    val speakerCharacterId: String?,

    @ColumnInfo(name = "content")
    val content: String,

    /** 被编辑过标记；不保留原版本 */
    @ColumnInfo(name = "edited")
    val edited: Boolean,

    /** ProviderSnapshot 序列化 JSON */
    @ColumnInfo(name = "provider_snapshot")
    val providerSnapshot: ProviderSnapshot?,

    @ColumnInfo(name = "prompt_tokens")
    val promptTokens: Int,

    @ColumnInfo(name = "completion_tokens")
    val completionTokens: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
