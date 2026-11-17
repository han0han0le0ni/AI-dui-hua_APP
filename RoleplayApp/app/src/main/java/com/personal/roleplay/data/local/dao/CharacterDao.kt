package com.personal.roleplay.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.personal.roleplay.data.local.entity.CharacterEntity
import com.personal.roleplay.data.local.entity.WorldBookEntity
import com.personal.roleplay.data.local.entity.WorldBookEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters WHERE archived = 0 ORDER BY created_at DESC")
    fun observeActive(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE archived = 1 ORDER BY created_at DESC")
    fun observeArchived(): Flow<List<CharacterEntity>>

    /** 含已归档，用量页按 id 反查名字要用 */
    @Query("SELECT * FROM characters")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :characterId")
    fun observeById(characterId: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE id = :characterId")
    suspend fun getById(characterId: String): CharacterEntity?

    @Query("SELECT * FROM characters")
    suspend fun getAll(): List<CharacterEntity>

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(character: CharacterEntity)

    @Upsert
    suspend fun upsertAll(characters: List<CharacterEntity>)

    @Query("UPDATE characters SET archived = :archived WHERE id = :characterId")
    suspend fun setArchived(characterId: String, archived: Boolean)

    @Query("DELETE FROM characters WHERE id = :characterId")
    suspend fun delete(characterId: String)
}

@Dao
interface WorldBookDao {

    @Query("SELECT * FROM world_book ORDER BY created_at DESC")
    fun observeAll(): Flow<List<WorldBookEntity>>

    @Query("SELECT * FROM world_book")
    suspend fun getAll(): List<WorldBookEntity>

    @Query("SELECT * FROM world_book WHERE id = :worldBookId")
    suspend fun getById(worldBookId: String): WorldBookEntity?

    @Upsert
    suspend fun upsert(worldBook: WorldBookEntity)

    @Query("DELETE FROM world_book WHERE id = :worldBookId")
    suspend fun delete(worldBookId: String)

    @Query("SELECT * FROM world_book_entry WHERE world_book_id = :worldBookId ORDER BY priority DESC, insertion_order ASC")
    fun observeEntries(worldBookId: String): Flow<List<WorldBookEntryEntity>>

    @Query("SELECT * FROM world_book_entry WHERE world_book_id = :worldBookId AND enabled = 1 ORDER BY priority DESC, insertion_order ASC")
    suspend fun getEnabledEntries(worldBookId: String): List<WorldBookEntryEntity>

    @Query("SELECT * FROM world_book_entry WHERE id = :entryId")
    suspend fun getEntry(entryId: String): WorldBookEntryEntity?

    /** 备份要连停用的条目一起导出 */
    @Query("SELECT * FROM world_book_entry WHERE world_book_id = :worldBookId")
    suspend fun getAllEntries(worldBookId: String): List<WorldBookEntryEntity>

    @Upsert
    suspend fun upsertEntry(entry: WorldBookEntryEntity)

    @Query("DELETE FROM world_book_entry WHERE id = :entryId")
    suspend fun deleteEntry(entryId: String)

    @Query("DELETE FROM world_book_entry WHERE world_book_id = :worldBookId")
    suspend fun deleteEntriesOf(worldBookId: String)
}
