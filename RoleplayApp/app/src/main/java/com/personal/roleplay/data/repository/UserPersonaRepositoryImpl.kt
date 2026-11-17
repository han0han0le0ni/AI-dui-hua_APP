package com.personal.roleplay.data.repository

import com.personal.roleplay.data.local.dao.UserPersonaDao
import com.personal.roleplay.data.mapper.toDomain
import com.personal.roleplay.data.mapper.toEntity
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.domain.repository.UserPersonaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserPersonaRepositoryImpl(
    private val personaDao: UserPersonaDao
) : UserPersonaRepository {

    override fun observeAll(): Flow<List<UserPersona>> =
        personaDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<UserPersona> =
        personaDao.getAll().map { it.toDomain() }

    override suspend fun getById(personaId: String): UserPersona? =
        personaDao.getById(personaId)?.toDomain()

    /**
     * 没显式设过默认时退回第一条。
     *
     * 这样「角色和群聊直接读取」永远有结果：只要建过一个人设，
     * 新线就自动带上，不必先去设置里点一下「设为默认」。
     */
    override suspend fun getDefault(): UserPersona? =
        personaDao.getDefault()?.toDomain()
            ?: personaDao.getAll().firstOrNull()?.toDomain()

    override suspend fun save(persona: UserPersona) {
        // 第一条人设自动成为默认，省掉一次多余的操作
        val isFirst = personaDao.count() == 0
        val entity = persona.copy(isDefault = persona.isDefault || isFirst).toEntity()
        if (entity.isDefault) {
            personaDao.clearDefaultExcept(entity.id)
        }
        personaDao.upsert(entity)
    }

    override suspend fun setAsDefault(personaId: String) {
        val target = personaDao.getById(personaId) ?: return
        personaDao.clearDefaultExcept(personaId)
        personaDao.upsert(target.copy(isDefault = true))
    }

    override suspend fun delete(personaId: String) {
        val wasDefault = personaDao.getById(personaId)?.isDefault == true
        personaDao.delete(personaId)

        // 删的正好是默认人设，就把默认位让给剩下的第一个，
        // 否则「直接读取」会突然读不到东西
        if (wasDefault) {
            personaDao.getAll().firstOrNull()?.let { next ->
                personaDao.upsert(next.copy(isDefault = true))
            }
        }
    }

    override suspend fun count(): Int = personaDao.count()
}
