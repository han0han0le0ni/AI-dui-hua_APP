package com.personal.roleplay.domain.truncation

import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemorySourceRef
import com.personal.roleplay.domain.model.SummarySegment

/**
 * 截断后的撤销快照（7.2）。
 *
 * 只在内存里，不落库：它是一次性防手滑保护，不是一个回收站功能。
 * 离开对话页即视为确认删除，快照丢弃，数据真正清理。
 *
 * [memories] 存的是「受影响记忆的原值」，[sources] 存的是「这些记忆的全部溯源」。
 * 因为部分受影响的记忆不会删除，只是丢掉几个来源并重算了置信度，
 * 撤销时必须连同原始置信度和全部来源一起还原。
 */
data class UndoSnapshot(
    val sessionId: String,
    /** 断点 seq */
    val anchorSeq: Long,
    val messages: List<ChatMessage>,
    val memories: List<MemoryItem>,
    val sources: List<MemorySourceRef>,
    /** 截断前的完整摘要分段，用于整体还原 */
    val summarySegments: List<SummarySegment>,
    val sessionBefore: ChatSession,
    val createdAt: Long = System.currentTimeMillis()
) {
    val messageCount: Int get() = messages.size
    val memoryCount: Int get() = memories.size
}

/** 截断模式 */
enum class TruncateMode {
    /** 重新生成：删掉定位点自身及其后所有消息 */
    REGENERATE,

    /** 从这里继续：保留定位点，只删其后的 */
    CONTINUE_FROM
}

data class TruncateOutcome(
    val snapshot: UndoSnapshot?,
    val deletedMessageCount: Int,
    val deletedMemoryCount: Int
) {
    companion object {
        fun nothing() = TruncateOutcome(null, 0, 0)
    }
}

data class DeleteOutcome(
    val deletedMessageCount: Int,
    val deletedMemoryCount: Int
)

/**
 * 删除前的确认弹窗要展示「会同时抹去 TA 由此记住的 N 件事」（8.5）。
 * 只算不删。
 */
data class TruncationPreview(
    val messageCount: Int,
    /** 会随消息一起消失的记忆（来源全部失效） */
    val losingMemories: List<MemoryItem>,
    /** 只是少几个来源、不会消失的记忆 */
    val weakenedMemories: List<MemoryItem>
)
