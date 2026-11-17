package com.personal.roleplay.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.personal.roleplay.data.local.entity.UserPersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPersonaDao {

    /** 按创建顺序，列表里位置稳定，不会因为改一次就跳到最前 */
    @Query("SELECT * FROM user_persona ORDER BY created_at ASC")
    fun observeAll(): Flow<List<UserPersonaEntity>>

    @Query("SELECT * FROM user_persona ORDER BY created_at ASC")
    suspend fun getAll(): List<UserPersonaEntity>

    @Query("SELECT * FROM user_persona WHERE id = :personaId")
    suspend fun getById(personaId: String): UserPersonaEntity?

    @Query("SELECT * FROM user_persona WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): UserPersonaEntity?

    @Upsert
    suspend fun upsert(persona: UserPersonaEntity)

    /** 全局只能有一个默认，设新的前先把旧的清掉 */
    @Query("UPDATE user_persona SET is_default = 0 WHERE id != :keepId")
    suspend fun clearDefaultExcept(keepId: String)

    @Query("DELETE FROM user_persona WHERE id = :personaId")
    suspend fun delete(personaId: String)

    @Query("SELECT COUNT(*) FROM user_persona")
    suspend fun count(): Int
}
