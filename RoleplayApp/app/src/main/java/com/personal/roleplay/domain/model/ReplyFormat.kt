package com.personal.roleplay.domain.model

import kotlinx.serialization.Serializable

/**
 * 回复格式预设：角色该怎么标注动作与台词。
 *
 * 这是全局设置（见设置 → 回复格式），所有会话共用一份。
 * 默认取「猫箱风格」：动作用圆括号，台词直接写、不加标记。
 */
enum class ReplyFormatPreset(
    val id: String,
    val displayName: String,
    val description: String
) {
    MAOXIANG("maoxiang", "猫箱风格", "动作、外貌、心理用（），台词直接写"),
    ASTERISK("asterisk", "星号风格", "动作用 *星号* 包裹，台词直接写"),
    PLAIN("plain", "只要台词", "不写动作与心理描写"),
    CUSTOM("custom", "自定义", "这段话原样注入提示词，规则自己定");

    companion object {
        /** 认不出来的 id 一律退回默认，旧数据或手改过的备份都不会打不开 */
        fun fromId(id: String?): ReplyFormatPreset =
            entries.firstOrNull { it.id == id } ?: MAOXIANG
    }
}

/**
 * 回复格式。
 *
 * 落库时存的是 [presetId] 字符串而不是枚举名——将来重命名枚举常量，
 * 旧数据也不会失效。真正注入提示词的那几行由 [resolvedRules] 现算，
 * 预设文案改了也不必迁移数据。
 */
@Serializable
data class ReplyFormat(
    val presetId: String = ReplyFormatPreset.MAOXIANG.id,
    /** 仅 [ReplyFormatPreset.CUSTOM] 时生效 */
    val customText: String = ""
) {
    /** 认不出来的 id 退回默认 */
    val preset: ReplyFormatPreset get() = ReplyFormatPreset.fromId(presetId)

    fun withPreset(next: ReplyFormatPreset): ReplyFormat = copy(presetId = next.id)

    /**
     * 注入「回复要求」末尾的格式规则。
     *
     * 自定义留空时退回猫箱风格：宁可给一套能用的默认，也不要往提示词里塞一段空白。
     */
    fun resolvedRules(): String = when (preset) {
        ReplyFormatPreset.MAOXIANG -> MAOXIANG_RULES
        ReplyFormatPreset.ASTERISK -> ASTERISK_RULES
        ReplyFormatPreset.PLAIN -> PLAIN_RULES
        ReplyFormatPreset.CUSTOM -> customText.trim().ifBlank { MAOXIANG_RULES }
    }

    companion object {
        val DEFAULT = ReplyFormat()

        const val MAOXIANG_RULES = """- 台词直接写，不加任何标记。
- 动作、神态、外貌、心理一律用圆括号包裹，例如（把外套披在你肩上）。
- 括号里只写动作与描写，不要把台词塞进括号，台词也不要加括号。"""

        const val ASTERISK_RULES = """- 动作、表情、心理描写用 *星号* 包裹，例如 *把外套披在你肩上*。
- 台词直接写，不加任何标记。"""

        const val PLAIN_RULES = """- 只写台词，不写动作、神态与心理描写。"""
    }
}
