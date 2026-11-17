package com.personal.roleplay.data.repository

import android.util.Log
import com.personal.roleplay.data.local.dao.ProviderDao
import com.personal.roleplay.data.mapper.toDomain
import com.personal.roleplay.data.mapper.toEntity
import com.personal.roleplay.data.security.SecureKeyVault
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 接入配置仓储。
 *
 * Key 只在这里与 [SecureKeyVault] 打交道：读配置时才注入明文，
 * 写库时只落 keyAlias，因此 Room 里、备份文件里都不会出现 Key。
 */
class ProviderRepositoryImpl(
    private val providerDao: ProviderDao,
    private val keyVault: SecureKeyVault
) : ProviderRepository {

    override fun observeAll(): Flow<List<ProviderConfig>> =
        providerDao.observeAll().map { list -> list.map { it.toDomain(keyVault.get(it.keyAlias)) } }

    override suspend fun getAll(): List<ProviderConfig> =
        providerDao.getAll().map { it.toDomain(keyVault.get(it.keyAlias)) }

    override suspend fun getById(providerId: String): ProviderConfig? =
        providerDao.getById(providerId)?.let { entity ->
            entity.toDomain(keyVault.get(entity.keyAlias))
        }

    /**
     * 槽位路由：角色偏好 → 该槽位默认 → 该槽位任意一个 → 主对话槽。
     *
     * 最后一级回退对应「M1 先复用主槽」：没单独配记忆抽取槽时，直接用主对话模型，
     * 功能不阻断，等想省钱了再拆。
     */
    override suspend fun resolveForSlot(slot: Slot, characterPreference: String?): ProviderConfig? {
        val all = getAll()
        if (all.isEmpty()) return null

        if (slot == Slot.MAIN_DIALOGUE && !characterPreference.isNullOrBlank()) {
            all.firstOrNull { it.id == characterPreference }?.let { return it }
        }

        return all.firstOrNull { it.slot == slot && it.isDefault }
            ?: all.firstOrNull { it.slot == slot }
            ?: all.firstOrNull { it.slot == Slot.MAIN_DIALOGUE }
    }

    override suspend fun save(config: ProviderConfig) {
        keyVault.put(config.keyAlias, config.apiKey)
        providerDao.upsert(config.toEntity())
        if (config.isDefault) {
            // 实体层存的是 wire 字符串，不能直接塞领域枚举
            providerDao.clearDefaultExcept(config.slot.wire, config.id)
        }
        Log.i(TAG, "save: 已保存配置 ${config.toSafeSummary()}，槽位=${config.slot.displayName}")
    }

    override suspend fun setAsDefault(providerId: String) {
        val entity = providerDao.getById(providerId) ?: return
        providerDao.clearDefaultExcept(entity.slot, providerId)
        providerDao.upsert(entity.copy(isDefault = true))
    }

    override suspend fun delete(providerId: String) {
        val entity = providerDao.getById(providerId) ?: return
        keyVault.remove(entity.keyAlias)
        providerDao.delete(providerId)
        Log.i(TAG, "delete: 已删除配置与对应 Key，id=$providerId")
    }

    private companion object {
        const val TAG = "ProviderRepository"
    }
}
