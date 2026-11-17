package com.personal.roleplay.util

import java.util.UUID

/**
 * 全局 id 生成。用 UUID 而不是自增：
 * 备份导入时可按「默认合并（分配新 ID）」策略处理，不需要重排主键。
 */
object Ids {
    fun new(): String = UUID.randomUUID().toString()
}
