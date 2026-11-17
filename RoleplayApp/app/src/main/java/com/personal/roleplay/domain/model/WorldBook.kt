package com.personal.roleplay.domain.model

/**
 * 世界书（L5）：世界观、地名、组织、人物关系。
 */
data class WorldBook(
    val id: String,
    val name: String,
    val description: String?,
    val createdAt: Long
)

/**
 * 世界书条目。对话时按触发词匹配，命中的按优先级注入 prompt（9.3）。
 */
data class WorldBookEntry(
    val id: String,
    val worldBookId: String,
    /** 触发词 */
    val keys: List<String>,
    val content: String,
    /** 数值越大越优先注入，超预算时先截断低优先级条目 */
    val priority: Int,
    val enabled: Boolean,
    /** 常驻条目：不依赖关键词命中，始终注入 */
    val constant: Boolean,
    /** 插入顺序，同优先级内按此排序 */
    val insertionOrder: Int
)
