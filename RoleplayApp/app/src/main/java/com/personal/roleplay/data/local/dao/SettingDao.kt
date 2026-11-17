package com.personal.roleplay.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.personal.roleplay.data.local.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {

    @Query("SELECT * FROM app_setting")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Query("SELECT * FROM app_setting")
    suspend fun getAll(): List<AppSettingEntity>

    @Query("SELECT * FROM app_setting WHERE setting_key = :key LIMIT 1")
    suspend fun get(key: String): AppSettingEntity?

    @Upsert
    suspend fun upsert(setting: AppSettingEntity)

    @Query("DELETE FROM app_setting")
    suspend fun clear()
}
