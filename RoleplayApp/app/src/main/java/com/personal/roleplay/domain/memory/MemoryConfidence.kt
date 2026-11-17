package com.personal.roleplay.domain.memory

import com.personal.roleplay.domain.model.MemoryOrigin

/**
 * 记忆置信度。
 *
 * 规则很简单：来源越多越可信；手动添加与置顶的不参与衰减。
 * 置信度有两个用途——注入时超出预算按它截断，以及多来源被删后重新评估。
 */
object MemoryConfidence {

    private const val BASE = 0.45f
    private const val PER_SOURCE = 0.18f
    private const val MAX_COUNTED_SOURCES = 3

    fun compute(sourceCount: Int, pinned: Boolean, origin: MemoryOrigin): Float {
        if (pinned || origin == MemoryOrigin.USER_DEFINED) return 1f
        return (BASE + PER_SOURCE * sourceCount.coerceAtMost(MAX_COUNTED_SOURCES)).coerceAtMost(1f)
    }
}
