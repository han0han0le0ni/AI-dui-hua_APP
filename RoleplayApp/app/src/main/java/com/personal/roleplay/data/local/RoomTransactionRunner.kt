package com.personal.roleplay.data.local

import android.util.Log
import androidx.room.Transactor
import androidx.room.useWriterConnection
import com.personal.roleplay.domain.repository.TransactionRunner
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 事务内暂存的 FTS 刷新动作。
 *
 * FTS 更新走的是独立连接（useWriterConnection），在 Room 事务里再开写连接会等待
 * 同一个 writer 而卡死。所以事务期间先把索引刷新排进这个队列，提交成功后再执行；
 * 事务回滚时队列随上下文一起丢弃，索引自然保持原样。
 */
class DeferredFtsWrites(
    val operations: MutableList<suspend () -> Unit>
) : AbstractCoroutineContextElement(DeferredFtsWrites) {

    companion object Key : CoroutineContext.Key<DeferredFtsWrites>
}

/**
 * Room 事务实现。
 *
 * 事务范围只包数据库写入，不包含网络请求；块内的 FTS 刷新会被延后到提交之后。
 */
class RoomTransactionRunner(
    private val database: RoleplayDatabase
) : TransactionRunner {

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        val pending = mutableListOf<suspend () -> Unit>()

        // 不能用 Room 的 withTransaction 扩展：它内部 beginTransaction 依赖
        // SupportSQLiteOpenHelper，而配置了 SQLiteDriver 后 openHelper 不存在，会直接抛异常。
        // 驱动模式下要用 useWriterConnection + Transactor 开事务。
        val result = database.useWriterConnection { transactor ->
            transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                withContext(DeferredFtsWrites(pending)) { block() }
            }
        }

        // 提交成功后再刷新索引；单条失败只记日志，索引是可重建的加速结构
        pending.forEach { operation ->
            runCatching { operation() }
                .onFailure { error -> Log.w(TAG, "事务提交后刷新 FTS 失败", error) }
        }

        return result
    }

    private companion object {
        const val TAG = "RoomTransactionRunner"
    }
}
