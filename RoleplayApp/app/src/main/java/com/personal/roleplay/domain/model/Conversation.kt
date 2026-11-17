package com.personal.roleplay.domain.model

import kotlinx.serialization.Serializable

/**
 * L1 情景摘要按 seq 分段存储。
 *
 * 这是「截断后必须重算情景摘要」这条坑的解法：截断时直接丢弃断点之后的分段，
 * 不必重新调用模型，摘要里也绝不会残留已被删除的对话内容。
 */
@Serializable
data class SummarySegment(
    val fromSeq: Long,
    val toSeq: Long,
    val text: String
)

/**
 * 消息上快照生成它时用的模型与参数——混用不同模型时可追溯质量差异来源。
 */
@Serializable
data class ProviderSnapshot(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val temperature: Float
)

/**
 * 会话：一条线性消息序列。
 *
 * 单人线与群聊共用这一张表：[participantIds] 就是「演员表」，顺序即发言轮转顺序；
 * 单人线里它只有一个元素。一个角色可以有多个会话（「第二天」「重开一次」「if 线」）。
 */
data class ChatSession(
    val id: String,
    /**
     * 归属角色。单人线就是唯一的角色；群聊取演员表的第一个。
     * 角色列表与角色详情按它分组，所以群聊会挂在第一个成员名下。
     */
    val characterId: String,
    /**
     * 全部参与者，顺序即发言轮转顺序。
     *
     * 这是「群聊不串味」的根基：每一轮只挑其中一个角色交给模型扮演，
     * 其余角色只以「同场角色」的身份出现在提示词里，绝不进入扮演位。
     */
    val participantIds: List<String>,
    val title: String,
    /**
     * 「我扮演谁」引用的人设库 id。设了就在组装时现取正文——
     * 改了人设，所有引用它的线一起生效，不用逐条改。
     */
    val userPersonaId: String?,
    /**
     * 建线当时的人设正文快照。填了才注入「对方是谁」小节。
     * 同一角色下 A 线是弟子、B 线是掌门，TA 对你的认知不能混在一起，
     * 这也是 L2 必须绑在会话上的原因。
     *
     * 库里查不到 [userPersonaId]（人设被删）时退回它，这条线不会因此失去扮演身份。
     */
    val userPersona: String?,
    /** L1 摘要分段 */
    val summarySegments: List<SummarySegment>,
    val lastExtractedSeq: Long,
    val lastExtractedAt: Long,
    /** 距上次抽取累计的轮次，满 8 轮触发 */
    val roundsSinceExtract: Int,
    /**
     * 群聊一轮里让几个角色依次开口。
     *
     * 单人线忽略此值。设成 2 就是「你发一句，TA 们轮流回两句」；
     * 默认 2 而不是全员，是为了避免一个五人群被一句话引爆五连发。
     */
    val groupTurnSize: Int,
    val createdAt: Long,
    val lastActiveAt: Long
) {
    /** 演员表。老数据可能没写 participantIds，用归属角色兜底 */
    val cast: List<String> get() = participantIds.ifEmpty { listOf(characterId) }

    val isGroup: Boolean get() = cast.size > 1

    /** 本轮实际能让几个角色开口：设置值不能超过演员表人数 */
    val effectiveGroupTurnSize: Int
        get() = groupTurnSize.coerceIn(1, cast.size.coerceAtLeast(1))

    companion object {
        /** 新群聊默认一轮两个角色开口 */
        const val DEFAULT_GROUP_TURN_SIZE = 2
    }
}

/**
 * 消息。seq 在会话内单调递增，只用于排序与定位截断位置。
 */
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val seq: Long,
    val role: MessageRole,
    /**
     * 这条是谁说的。
     *
     * 群聊必须逐条记录，否则历史里分不出哪句是谁的台词，模型会把别人说过的话
     * 当成自己说过的——这正是串味的起点。单人线也照写，保持一致。
     * USER 与 NARRATION 为 null。
     */
    val speakerCharacterId: String?,
    val content: String,
    /** 被编辑过标记；不保留原版本 */
    val edited: Boolean,
    val providerSnapshot: ProviderSnapshot?,
    val promptTokens: Int,
    val completionTokens: Int,
    val createdAt: Long
) {
    val isFromCharacter: Boolean
        get() = role != MessageRole.USER
}
