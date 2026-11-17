package com.personal.roleplay.data.local

import android.util.Log
import androidx.room.PooledConnection
import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.personal.roleplay.data.local.MemoryFtsIndex.Companion.CREATE_TABLE_SQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 记忆全文索引（8.3 的「粗筛」环节）。
 *
 * 索引与 memory 表同步维护，读写都在这里收口，避免两处逻辑不一致。
 * 索引是加速结构而非数据源：查询失败一律返回空列表，由上层退化为内存打分。
 *
 * 这里全部走 Room 的连接 API（useWriterConnection/useReaderConnection），
 * 而不是 database.openHelper：配置了 SQLiteDriver 之后 openHelper 会直接抛异常。
 */
class MemoryFtsIndex(private val database: RoleplayDatabase) {

    data class Hit(
        val memoryId: String,
        /** bm25 得分，越小越相关 */
        val score: Double
    )

    @Volatile
    private var ready = false

    @Volatile
    private var available = true

    /** 串行化第一次建表，避免并发回填写进重复行 */
    private val readyLock = Mutex()

    /**
     * 懒建表 + 首次回填。
     *
     * 建表从 Room 的 onCreate 回调搬到了这里，原因是老版本在系统 SQLite 上建表失败后
     * 索引永久缺失；现在即使升级前没建成，第一次用到时也会补建并按 memory 表回填，
     * 不需要用户清库重来。
     */
    private suspend fun ensureReady(): Boolean {
        if (ready) return true
        if (!available) return false

        return readyLock.withLock {
            if (ready) return@withLock true
            if (!available) return@withLock false

            val ok = runCatching {
                database.useWriterConnection { transactor ->
                    transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        execSql(CREATE_TABLE_SQL)
                        if (count() == 0L) backfill()
                    }
                }
                true
            }.onFailure { error ->
                Log.w(TAG, "memory_fts 不可用，记忆粗筛退化为内存打分", error)
            }.getOrDefault(false)

            ready = ok
            available = ok
            ok
        }
    }

    /** 写入或刷新一条记忆的索引 */
    suspend fun index(memoryId: String, content: String) = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext
        runCatching {
            database.useWriterConnection { transactor ->
                transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    deleteByRef(memoryId)
                    insert(memoryId, content)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "写入 memory_fts 失败：memoryId=$memoryId", error)
        }
    }

    /** 批量重建索引（导入备份或手动重算记忆后调用） */
    suspend fun reindexAll(entries: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext
        runCatching {
            database.useWriterConnection { transactor ->
                transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    deleteAll()
                    entries.forEach { (memoryId, content) -> insert(memoryId, content) }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "重建 memory_fts 失败", error)
        }
    }

    suspend fun remove(memoryId: String) = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext
        runCatching {
            database.useWriterConnection { connection -> connection.deleteByRef(memoryId) }
        }.onFailure { error ->
            Log.w(TAG, "删除 memory_fts 记录失败：memoryId=$memoryId", error)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext
        runCatching {
            database.useWriterConnection { connection -> connection.deleteAll() }
        }.onFailure { error ->
            Log.w(TAG, "清空 memory_fts 失败", error)
        }
    }

    /** 粗筛：返回按相关度升序的候选记忆 id */
    suspend fun search(terms: List<String>, limit: Int): List<Hit> = withContext(Dispatchers.IO) {
        if (!ensureReady()) return@withContext emptyList()

        val matchQuery = FtsSegmenter.buildMatchQuery(terms) ?: return@withContext emptyList()

        runCatching {
            database.useReaderConnection { connection ->
                connection.usePrepared(SELECT_SQL) { statement ->
                    statement.bindText(1, matchQuery)
                    statement.bindLong(2, limit.toLong())
                    buildList {
                        while (statement.step()) {
                            add(Hit(statement.getText(0), statement.getDouble(1)))
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "FTS5 检索失败，退化为内存打分", error)
        }.getOrDefault(emptyList())
    }

    private suspend fun PooledConnection.execSql(sql: String) {
        usePrepared(sql) { statement -> statement.step() }
    }

    private suspend fun PooledConnection.insert(memoryId: String, content: String) {
        usePrepared(INSERT_SQL) { statement ->
            statement.bindText(1, FtsSegmenter.segment(content))
            statement.bindText(2, memoryId)
            statement.step()
        }
    }

    private suspend fun PooledConnection.deleteByRef(memoryId: String) {
        usePrepared(DELETE_BY_REF_SQL) { statement ->
            statement.bindText(1, memoryId)
            statement.step()
        }
    }

    private suspend fun PooledConnection.deleteAll() {
        execSql("DELETE FROM memory_fts")
    }

    private suspend fun PooledConnection.count(): Long =
        usePrepared("SELECT COUNT(*) FROM memory_fts") { statement ->
            if (statement.step()) statement.getLong(0) else 0L
        }

    /** 索引为空时按 memory 表回填，覆盖「升级前建表失败」的老设备 */
    private suspend fun PooledConnection.backfill() {
        val rows = mutableListOf<Pair<String, String>>()
        usePrepared("SELECT id, content FROM memory") { statement ->
            while (statement.step()) {
                rows += statement.getText(0) to statement.getText(1)
            }
        }
        rows.forEach { (memoryId, content) -> insert(memoryId, content) }
        Log.i(TAG, "memory_fts 首次回填 ${rows.size} 条记忆")
    }

    companion object {
        private const val TAG = "MemoryFtsIndex"

        /** memory_ref 声明为 UNINDEXED：只做回表用的外键，不参与分词 */
        const val CREATE_TABLE_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(body, memory_ref UNINDEXED)"

        private const val INSERT_SQL =
            "INSERT INTO memory_fts(body, memory_ref) VALUES (?, ?)"

        private const val DELETE_BY_REF_SQL =
            "DELETE FROM memory_fts WHERE memory_ref = ?"

        private const val SELECT_SQL =
            "SELECT memory_ref, bm25(memory_fts) AS rank FROM memory_fts " +
                "WHERE memory_fts MATCH ? ORDER BY rank LIMIT ?"
    }
}
