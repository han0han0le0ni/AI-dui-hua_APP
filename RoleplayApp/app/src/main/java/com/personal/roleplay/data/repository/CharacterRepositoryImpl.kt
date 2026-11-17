package com.personal.roleplay.data.repository

import com.personal.roleplay.data.local.dao.CharacterDao
import com.personal.roleplay.data.local.dao.WorldBookDao
import com.personal.roleplay.data.mapper.toDomain
import com.personal.roleplay.data.mapper.toEntity
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.WorldBook
import com.personal.roleplay.domain.model.WorldBookEntry
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.WorldBookRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CharacterRepositoryImpl(
    private val characterDao: CharacterDao
) : CharacterRepository {

    override fun observeAll(): Flow<List<Character>> =
        characterDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeActive(): Flow<List<Character>> =
        characterDao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observeArchived(): Flow<List<Character>> =
        characterDao.observeArchived().map { list -> list.map { it.toDomain() } }

    override fun observeById(characterId: String): Flow<Character?> =
        characterDao.observeById(characterId).map { it?.toDomain() }

    override suspend fun getById(characterId: String): Character? =
        characterDao.getById(characterId)?.toDomain()

    override suspend fun getAll(): List<Character> =
        characterDao.getAll().map { it.toDomain() }

    override suspend fun count(): Int = characterDao.count()

    override suspend fun save(character: Character) {
        characterDao.upsert(character.toEntity())
    }

    override suspend fun saveAll(characters: List<Character>) {
        characterDao.upsertAll(characters.map { it.toEntity() })
    }

    override suspend fun setArchived(characterId: String, archived: Boolean) {
        characterDao.setArchived(characterId, archived)
    }

    override suspend fun delete(characterId: String) {
        characterDao.delete(characterId)
    }
}

class WorldBookRepositoryImpl(
    private val worldBookDao: WorldBookDao
) : WorldBookRepository {

    override fun observeBooks(): Flow<List<WorldBook>> =
        worldBookDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEntries(worldBookId: String): Flow<List<WorldBookEntry>> =
        worldBookDao.observeEntries(worldBookId).map { list -> list.map { it.toDomain() } }

    override suspend fun getBook(worldBookId: String): WorldBook? =
        worldBookDao.getById(worldBookId)?.toDomain()

    override suspend fun getAllBooks(): List<WorldBook> =
        worldBookDao.getAll().map { it.toDomain() }

    override suspend fun getAllEntries(worldBookId: String): List<WorldBookEntry> =
        worldBookDao.getAllEntries(worldBookId).map { it.toDomain() }

    override suspend fun getEnabledEntries(worldBookId: String): List<WorldBookEntry> =
        worldBookDao.getEnabledEntries(worldBookId).map { it.toDomain() }

    override suspend fun createBook(name: String, description: String?): WorldBook {
        val book = WorldBook(
            id = Ids.new(),
            name = name,
            description = description,
            createdAt = System.currentTimeMillis()
        )
        worldBookDao.upsert(book.toEntity())
        return book
    }

    override suspend fun saveBook(book: WorldBook) {
        worldBookDao.upsert(book.toEntity())
    }

    override suspend fun saveEntry(entry: WorldBookEntry) {
        worldBookDao.upsertEntry(entry.toEntity())
    }

    override suspend fun deleteEntry(entryId: String) {
        worldBookDao.deleteEntry(entryId)
    }

    override suspend fun deleteBook(worldBookId: String) {
        worldBookDao.deleteEntriesOf(worldBookId)
        worldBookDao.delete(worldBookId)
    }
}
