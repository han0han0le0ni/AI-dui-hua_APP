package com.personal.roleplay.domain.context

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Random

/**
 * 酒馆宏替换。
 *
 * 只做导入的卡里真的会出现的那一小撮：身份、注释、日期时间、随机。
 * 替换发生在每次组装上下文（或渲染消息）时，**不写回数据库**——
 * 卡里的 `{{char}}` 必须跟着角色名走，导入时写死就再也改不回来了。
 */
class MacroResolver(
    private val charName: String,
    private val userName: String,
    /**
     * 确定性随机的种子。
     *
     * 同一轮重试必须得到同样的结果，否则「重新生成」每点一次，前缀就变一次，
     * 上游的前缀缓存全部作废。
     */
    private val seed: Long = 0L,
    nowMillis: Long = System.currentTimeMillis()
) {

    // 时间按 5 分钟取整：一分钟一变会让缓存每 60 秒失效一次，代价远大于收益
    private val now: LocalDateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(nowMillis / TIME_GRANULARITY_MS * TIME_GRANULARITY_MS),
        ZoneId.systemDefault()
    )

    fun resolve(text: String): String {
        if (text.isEmpty() || !text.contains("{{")) return text

        var result = COMMENT.replace(text, "")

        // {{random:a,b,c}}：随机源由「种子 + 这段文本自身」决定。
        // 同一段文本在任何一轮都给出同一个值，也不会因为调用顺序不同而漂移。
        val random = Random(seed xor text.hashCode().toLong())
        result = RANDOM.replace(result) { match ->
            val options = match.groupValues[1]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (options.isEmpty()) "" else options[random.nextInt(options.size)]
        }

        result = DATE_PATTERN.replace(result, DATE_FORMAT.format(now))
        result = TIME_PATTERN.replace(result, TIME_FORMAT.format(now))

        return result
            // 先替长的，避免 {{char}} 把 {{charname}} 截一半
            .replace("{{charname}}", charName, ignoreCase = true)
            .replace("{{username}}", userName, ignoreCase = true)
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("{{user}}", userName, ignoreCase = true)
            // {{original}} 是 V2 卡的翻译字段：没有译文时留空，不能漏成字面量
            .replace("{{original}}", "", ignoreCase = true)
    }

    private companion object {
        /** 5 分钟 */
        const val TIME_GRANULARITY_MS = 5 * 60 * 1000L

        val COMMENT = Regex("\\{\\{//.*?\\}\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val RANDOM = Regex("\\{\\{random:(.*?)\\}\\}", RegexOption.IGNORE_CASE)
        val DATE_PATTERN = Regex("\\{\\{date\\}\\}", RegexOption.IGNORE_CASE)
        val TIME_PATTERN = Regex("\\{\\{time\\}\\}", RegexOption.IGNORE_CASE)

        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
