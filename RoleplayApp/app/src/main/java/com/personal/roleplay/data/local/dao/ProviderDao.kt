package com.personal.roleplay.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.personal.roleplay.data.local.entity.ProviderConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {

    @Query("SELECT * FROM provider_config ORDER BY created_at ASC")
    fun observeAll(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_config ORDER BY created_at ASC")
    suspend fun getAll(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_config WHERE slot = :slot")
    suspend fun getBySlot(slot: String): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_config WHERE id = :providerId")
    suspend fun getById(providerId: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_config WHERE id = :providerId")
    fun observeById(providerId: String): Flow<ProviderConfigEntity?>

    @Query("SELECT * FROM provider_config WHERE id = :providerId")
    fun observeListById(providerId: String): Flow<List<ProviderConfigEntity>>

    @Upsert
    suspend fun upsert(config: ProviderConfigEntity)

    /** 同一槽位内只能有一个默认配置 */
    @Query("UPDATE provider_config SET is_default = 0 WHERE slot = :slot AND id != :keepId")
    suspend fun clearDefaultExcept(slot: String, keepId: String)

    @Query("DELETE FROM provider_config WHERE id = :providerId")
    suspend fun delete(providerId: String)
}
