package com.personal.roleplay.domain.model

/**
 * 记忆条目。session_id 必填：删会话即删全部记忆，没有例外。
 *
 * L0 人设不进这张表（直接从 character 读），L5 世界不进这张表（走 world_book_entry），
 * 所以这里只有 L1-L4，全部是会话级。
 */
data class MemoryItem(
    val id: String,
    val characterId: String,
    val sessionId: String,
    val layer: MemoryLayer,
    val content: String,
    /** 0f - 1f，来源越少越低 */
    val confidence: Float,
    /** 置顶：注入优先级更高、不参与淘汰；删会话时仍随之删除 */
    val pinned: Boolean,
    val origin: MemoryOrigin,
    val status: MemoryStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRecalledAt: Long
)

/**
 * 记忆溯源记录：一条记忆由哪几条消息的哪句话支撑。
 */
data class MemorySourceRef(
    val id: String,
    val memoryId: String,
    val messageId: String,
    /** 支撑这条记忆的原文片段，界面上直接展示 */
    val excerpt: String
)

/** 界面展示用：记忆 + 它的全部来源 */
data class MemoryWithSources(
    val memory: MemoryItem,
    val sources: List<MemorySourceRef>
)

/**
 * 记忆操作指令（8.2）。
 *
 * [sourceRefs] 是抽取请求里那批消息的下标（对模型呈现为 1 起编号），
 * 由 MemoryEngine 映射回真实 messageId。
 */
sealed interface MemoryOperation {
    data class Add(
        val layer: MemoryLayer,
        val content: String,
        val sourceRefs: List<Int>
    ) : MemoryOperation

    data class Update(
        val memoryId: String,
        val content: String,
        val sourceRefs: List<Int>
    ) : MemoryOperation

    data class Delete(
        val memoryId: String,
        val reason: String?
    ) : MemoryOperation

    data object Noop : MemoryOperation
}
