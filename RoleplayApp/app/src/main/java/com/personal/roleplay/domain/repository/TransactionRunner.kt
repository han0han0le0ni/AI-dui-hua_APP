package com.personal.roleplay.domain.repository

/**
 * 事务边界。
 *
 * 领域层不直接依赖 Room，但记忆抽取、截断、撤销这些动作必须「要么全成，要么全不成」。
 * 由数据层提供一个实现，领域层只声明需要事务包裹的代码块。
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
