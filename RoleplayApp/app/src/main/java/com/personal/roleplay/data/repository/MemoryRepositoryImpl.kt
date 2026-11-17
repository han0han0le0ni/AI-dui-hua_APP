package com.personal.roleplay.data.repository

import com.personal.roleplay.data.local.DeferredFtsWrites
import com.personal.roleplay.data.local.MemoryFtsIndex
import com.personal.roleplay.data.local.dao.MemoryDao
import com.personal.roleplay.data.local.dao.MemorySourceDao
import com.personal.roleplay.data.mapper.toDomain
import com.personal.roleplay.data.mapper.toEntity
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MemorySourceRef
import com.personal.roleplay.domain.model.MemoryStatus
import com.personal.roleplay.domain.model.MemoryWithSources
import com.personal.roleplay.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.coroutineContext

/**
 * 记忆仓储。
 *
 * FTS 索引与记忆表在这里同步维护：任何写记忆的入口都经过本类，
 * 不会出现「库里有、索引里没有」的漂移。索引写入失败不影响主流程。
 */
class MemoryRepositoryImpl(
    private val memoryDao: MemoryDao,
    private val sourceDao: MemorySourceDao,
    private val ftsIndex: MemoryFtsIndex
) : MemoryRepository {

    override fun observeBySession(sessionId: String): Flow<List<MemoryItem>> =
        memoryDao.observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun getBySession(sessionId: String): List<MemoryItem> =
        memoryDao.getBySession(sessionId).map { it.toDomain() }

    override suspend fun getByLayer(sessionId: String, layer: MemoryLayer): List<MemoryItem> =
        memoryDao.getByLayer(sessionId, layer.wire).map { it.toDomain() }

    override suspend fun getByIds(memoryIds: List<String>): List<MemoryItem> =
        if (memoryIds.isEmpty()) emptyList()
        else memoryDao.getByIds(memoryIds).map { it.toDomain() }

    override suspend fun getWithSources(sessionId: String): List<MemoryWithSources> {
        val memories = memoryDao.getBySession(sessionId).map { it.toDomain() }
        if (memories.isEmpty()) return emptyList()

        val grouped = sourceDao.getByMemories(memories.map { it.id })
            .map { it.toDomain() }
            .groupBy { it.memoryId }

        return memories.map { memory -> MemoryWithSources(memory, grouped[memory.id].orEmpty()) }
    }

    override suspend fun getSourcesOfMemory(memoryId: String): List<MemorySourceRef> =
        sourceDao.getByMemory(memoryId).map { it.toDomain() }

    override suspend fun getSourcesOfMessages(messageIds: List<String>): List<MemorySourceRef> =
        if (messageIds.isEmpty()) emptyList()
        else sourceDao.getByMessages(messageIds).map { it.toDomain() }

    override suspend fun save(memory: MemoryItem) {
        memoryDao.upsert(memory.toEntity())
        refreshIndex { ftsIndex.index(memory.id, memory.content) }
    }

    override suspend fun saveAll(memories: List<MemoryItem>) {
        if (memories.isEmpty()) return
        memoryDao.upsertAll(memories.map { it.toEntity() })
        refreshIndex { memories.forEach { ftsIndex.index(it.id, it.content) } }
    }

    override suspend fun addSources(sources: List<MemorySourceRef>) {
        if (sources.isEmpty()) return
        sourceDao.insertAll(sources.map { it.toEntity() })
    }

    override suspend fun removeSourceRefs(memoryId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        sourceDao.deleteRefs(memoryId, messageIds)
    }

    override suspend fun deleteMemories(memoryIds: List<String>) {
        if (memoryIds.isEmpty()) return
        sourceDao.deleteByMemories(memoryIds)
        memoryDao.deleteByIds(memoryIds)
        refreshIndex { memoryIds.forEach { ftsIndex.remove(it) } }
    }

    override suspend fun deleteBySession(sessionId: String) {
        val memories = memoryDao.getAllIncludingDeleted(sessionId)
        refreshIndex { memories.forEach { ftsIndex.remove(it.id) } }
        sourceDao.deleteByMemories(memories.map { it.id })
        memoryDao.deleteBySession(sessionId)
    }

    override suspend fun setStatus(memoryIds: List<String>, status: MemoryStatus) {
        if (memoryIds.isEmpty()) return
        memoryDao.setStatus(memoryIds, status.wire)
    }

    override suspend fun markRecalled(memoryIds: List<String>, timestamp: Long) {
        if (memoryIds.isEmpty()) return
        memoryDao.markRecalled(memoryIds, timestamp)
    }

    /**
     * 在事务内只排队、不在事务内直接写索引：
     * 事务持有唯一的 writer 连接，FTS 再开写连接会自锁。
     * 事务外的普通调用没有这个上下文，仍然立即刷新。
     */
    private suspend fun refreshIndex(block: suspend () -> Unit) {
        val deferred = coroutineContext[DeferredFtsWrites]
        if (deferred != null) deferred.operations += block else block()
    }
}
