package com.personal.roleplay.data.repository

import com.personal.roleplay.data.local.dao.RequestLogDao
import com.personal.roleplay.data.local.dao.UsageDao
import com.personal.roleplay.data.mapper.toDomain
import com.personal.roleplay.data.mapper.toEntity
import com.personal.roleplay.domain.model.CharacterUsage
import com.personal.roleplay.domain.model.ModelUsage
import com.personal.roleplay.domain.model.RequestLog
import com.personal.roleplay.domain.model.SlotUsage
import com.personal.roleplay.domain.model.UsageRecord
import com.personal.roleplay.domain.model.UsageTotals
import com.personal.roleplay.domain.repository.DiagnosticsRepository
import com.personal.roleplay.domain.repository.UsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UsageRepositoryImpl(
    private val usageDao: UsageDao
) : UsageRepository {

    override suspend fun record(record: UsageRecord) {
        usageDao.insert(record.toEntity())
    }

    override fun observeTotalsSince(from: Long): Flow<UsageTotals> =
        usageDao.observeTotalsSince(from).map { it.toDomain() }

    override fun observeByModel(from: Long): Flow<List<ModelUsage>> =
        usageDao.observeByModel(from).map { list -> list.map { it.toDomain() } }

    override fun observeBySlot(from: Long): Flow<List<SlotUsage>> =
        usageDao.observeBySlot(from).map { list -> list.map { it.toDomain() } }

    override fun observeByCharacter(from: Long): Flow<List<CharacterUsage>> =
        usageDao.observeByCharacter(from).map { list -> list.map { it.toDomain() } }

    override fun observeRecent(from: Long, limit: Int): Flow<List<UsageRecord>> =
        usageDao.observeRecent(from, limit).map { list -> list.map { it.toDomain() } }

    override suspend fun deleteBefore(before: Long) {
        usageDao.deleteBefore(before)
    }
}

class DiagnosticsRepositoryImpl(
    private val requestLogDao: RequestLogDao
) : DiagnosticsRepository {

    override suspend fun save(log: RequestLog) {
        requestLogDao.upsert(log.toEntity())
    }

    override fun observeRecent(limit: Int): Flow<List<RequestLog>> =
        requestLogDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override fun observeBySession(sessionId: String, limit: Int): Flow<List<RequestLog>> =
        requestLogDao.observeBySession(sessionId, limit).map { list -> list.map { it.toDomain() } }

    override suspend fun getById(logId: String): RequestLog? =
        requestLogDao.getById(logId)?.toDomain()

    override suspend fun deleteBefore(before: Long) {
        requestLogDao.deleteBefore(before)
    }
}
