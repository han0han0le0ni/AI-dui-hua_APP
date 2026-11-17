package com.personal.roleplay.domain.truncation

import android.util.Log
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.memory.MemoryConfidence
import com.personal.roleplay.domain.model.MemorySourceRef
import com.personal.roleplay.domain.model.MemoryStatus
import com.personal.roleplay.domain.model.SummarySegment
import com.personal.roleplay.domain.repository.ConversationRepository
import com.personal.roleplay.domain.repository.MemoryRepository
import com.personal.roleplay.domain.repository.TransactionRunner

/**
 * 截断引擎（7.1 / 7.3 / 11.3）。
 *
 * 对话是一条线，回溯就是把线剪断，从断点重新长。
 * 剪下来的那一段直接删除，不留任何副本；由它支撑的记忆随之删除。
 *
 * 两个容易踩的坑在这里被显式处理掉了：
 * 1. L1 摘要按 seq 分段，截断时丢弃断点之后的分段，避免角色「记得」一段不存在的过去；
 * 2. 记忆按溯源关系级联：来源全失效才删，部分失效则移除引用并重算置信度。
 */
class TruncationEngine(
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val transactionRunner: TransactionRunner
) {

    /**
     * 只算不删，供确认弹窗展示将要失去的记忆。
     */
    suspend fun preview(sessionId: String, anchorSeq: Long, mode: TruncateMode): TruncationPreview {
        val messages = conversationRepository.getMessagesFrom(sessionId, cutSeq(anchorSeq, mode))
        val plan = computeCascade(messages)
        return TruncationPreview(
            messageCount = messages.size,
            losingMemories = plan.fullyDeletedMemories,
            weakenedMemories = plan.partiallyAffectedMemories
        )
    }

    /**
     * 删除指定消息的预览。与截断同一套溯源算法，区别只是范围由 seq 换成显式 id 列表。
     */
    suspend fun previewDelete(sessionId: String, messageIds: List<String>): TruncationPreview {
        val messages = conversationRepository.getMessagesByIds(messageIds).filter { it.sessionId == sessionId }
        val plan = computeCascade(messages)
        return TruncationPreview(
            messageCount = messages.size,
            losingMemories = plan.fullyDeletedMemories,
            weakenedMemories = plan.partiallyAffectedMemories
        )
    }

    /**
     * 截断并生成撤销快照。
     */
    suspend fun truncate(
        sessionId: String,
        anchorSeq: Long,
        mode: TruncateMode
    ): TruncateOutcome {
        val session = conversationRepository.getSession(sessionId) ?: return TruncateOutcome.nothing()
        val cut = cutSeq(anchorSeq, mode)
        val messages = conversationRepository.getMessagesFrom(sessionId, cut)

        if (messages.isEmpty()) {
            Log.w(TAG, "truncate: 断点之后没有消息，无需截断，sessionId=$sessionId, cutSeq=$cut")
            return TruncateOutcome.nothing()
        }

        val plan = computeCascade(messages)

        val snapshot = UndoSnapshot(
            sessionId = sessionId,
            anchorSeq = anchorSeq,
            messages = messages,
            memories = plan.affectedMemories,
            sources = plan.allAffectedSources,
            summarySegments = session.summarySegments,
            sessionBefore = session
        )

        // L1 摘要：丢弃断点之后的分段，角色不会「记得」一段已被删除的过去
        val keptSegments = session.summarySegments.filter { it.toSeq < cut }

        // 级联删记忆、删消息、改摘要必须同生同死，避免删了一半留下失源的记忆
        transactionRunner.inTransaction {
            applyCascade(plan)
            conversationRepository.deleteMessages(messages.map { it.id })
            conversationRepository.updateSession(session.copy(summarySegments = keptSegments))
        }

        Log.i(
            TAG,
            "truncate: 已删除 ${messages.size} 条消息、${plan.fullyDeletedMemories.size} 条记忆，" +
                "摘要分段 ${session.summarySegments.size} -> ${keptSegments.size}"
        )

        return TruncateOutcome(
            snapshot = snapshot,
            deletedMessageCount = messages.size,
            deletedMemoryCount = plan.fullyDeletedMemories.size
        )
    }

    /**
     * 撤销：消息、记忆、溯源、摘要分段整体还原，如同没发生过。
     */
    suspend fun undo(snapshot: UndoSnapshot) {
        transactionRunner.inTransaction {
            conversationRepository.insertMessages(snapshot.messages)
            memoryRepository.saveAll(snapshot.memories)
            memoryRepository.addSources(snapshot.sources)
            conversationRepository.updateSession(snapshot.sessionBefore)
        }
        Log.i(TAG, "undo: 已还原 ${snapshot.messageCount} 条消息、${snapshot.memoryCount} 条记忆")
    }

    /**
     * 删除单条或若干条消息：走同一套溯源计算，只是范围不同（8.5）。
     */
    suspend fun deleteMessages(sessionId: String, messageIds: List<String>): DeleteOutcome {
        if (messageIds.isEmpty()) return DeleteOutcome(0, 0)

        val messages = conversationRepository.getMessagesByIds(messageIds).filter { it.sessionId == sessionId }
        if (messages.isEmpty()) return DeleteOutcome(0, 0)

        val plan = computeCascade(messages)
        transactionRunner.inTransaction {
            applyCascade(plan)
            conversationRepository.deleteMessages(messages.map { it.id })
        }

        Log.i(TAG, "deleteMessages: 已删除 ${messages.size} 条消息、${plan.fullyDeletedMemories.size} 条记忆")
        return DeleteOutcome(messages.size, plan.fullyDeletedMemories.size)
    }

    /**
     * 编辑消息：相关记忆标记 stale，等下次抽取时对账（8.5）。
     *
     * 编辑角色消息等于「你主动定义事实」，是修正人设跑偏的利器，
     * 所以不直接删记忆，而是让它待对账。
     */
    suspend fun markMemoriesStale(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val sources = memoryRepository.getSourcesOfMessages(messageIds)
        val memoryIds = sources.map { it.memoryId }.distinct()
        if (memoryIds.isEmpty()) return
        memoryRepository.setStatus(memoryIds, MemoryStatus.STALE)
        Log.i(TAG, "markMemoriesStale: ${memoryIds.size} 条记忆待对账")
    }

    /**
     * 删除会话：消息、记忆、溯源全部删除，无例外（硬约束 3）。
     */
    suspend fun deleteSession(sessionId: String): DeleteOutcome {
        val messageCount = conversationRepository.getMessages(sessionId).size
        val memoryCount = memoryRepository.getBySession(sessionId).size

        transactionRunner.inTransaction {
            memoryRepository.deleteBySession(sessionId)
            conversationRepository.deleteMessagesOfSession(sessionId)
            conversationRepository.deleteSession(sessionId)
        }

        Log.i(TAG, "deleteSession: 已删除会话，含 $messageCount 条消息、$memoryCount 条记忆，sessionId=$sessionId")
        return DeleteOutcome(messageCount, memoryCount)
    }

    private fun cutSeq(anchorSeq: Long, mode: TruncateMode): Long =
        if (mode == TruncateMode.CONTINUE_FROM) anchorSeq + 1L else anchorSeq

    /**
     * 把一批被删消息翻译成记忆侧的处置方案。
     */
    private suspend fun computeCascade(deletedMessages: List<ChatMessage>): CascadePlan {
        if (deletedMessages.isEmpty()) return CascadePlan.empty()

        val deletedIds = deletedMessages.map { it.id }.toSet()
        val touchedSources = memoryRepository.getSourcesOfMessages(deletedIds.toList())
        if (touchedSources.isEmpty()) return CascadePlan.empty()

        val affectedMemoryIds = touchedSources.map { it.memoryId }.distinct()
        val affectedMemories = memoryRepository.getByIds(affectedMemoryIds)

        val fullyDeleted = mutableListOf<MemoryItem>()
        val partiallyAffected = mutableListOf<MemoryItem>()
        val allAffectedSources = mutableListOf<MemorySourceRef>()

        affectedMemories.forEach { memory ->
            val allSources = memoryRepository.getSourcesOfMemory(memory.id)
            allAffectedSources += allSources

            val remaining = allSources.filter { it.messageId !in deletedIds }
            if (remaining.isEmpty()) {
                fullyDeleted += memory
            } else {
                partiallyAffected += memory
            }
        }

        return CascadePlan(
            deletedMessageIds = deletedIds,
            fullyDeletedMemories = fullyDeleted,
            partiallyAffectedMemories = partiallyAffected,
            affectedMemories = affectedMemories,
            allAffectedSources = allAffectedSources
        )
    }

    private suspend fun applyCascade(plan: CascadePlan) {
        if (plan.isEmpty()) return

        // 来源全失效的记忆：连记忆带溯源一起删
        if (plan.fullyDeletedMemories.isNotEmpty()) {
            memoryRepository.deleteMemories(plan.fullyDeletedMemories.map { it.id })
        }

        // 部分失效：移除这批引用，按剩余来源数重算置信度
        plan.partiallyAffectedMemories.forEach { memory ->
            memoryRepository.removeSourceRefs(memory.id, plan.deletedMessageIds.toList())
            val remaining = memoryRepository.getSourcesOfMemory(memory.id)
            memoryRepository.save(memory.copy(confidence = confidenceFor(remaining.size, memory)))
        }
    }

    /** 置信度复用记忆引擎的同一套规则，避免两处常量漂移 */
    private fun confidenceFor(sourceCount: Int, memory: MemoryItem): Float =
        MemoryConfidence.compute(sourceCount, memory.pinned, memory.origin)

    private data class CascadePlan(
        val deletedMessageIds: Set<String>,
        val fullyDeletedMemories: List<MemoryItem>,
        val partiallyAffectedMemories: List<MemoryItem>,
        val affectedMemories: List<MemoryItem>,
        val allAffectedSources: List<MemorySourceRef>
    ) {
        fun isEmpty(): Boolean = affectedMemories.isEmpty()

        companion object {
            fun empty() = CascadePlan(
                deletedMessageIds = emptySet(),
                fullyDeletedMemories = emptyList(),
                partiallyAffectedMemories = emptyList(),
                affectedMemories = emptyList(),
                allAffectedSources = emptyList()
            )
        }
    }

    private companion object {
        const val TAG = "TruncationEngine"
    }
}
