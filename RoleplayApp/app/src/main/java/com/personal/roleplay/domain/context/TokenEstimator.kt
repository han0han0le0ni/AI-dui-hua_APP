package com.personal.roleplay.domain.context

/**
 * token 估算。
 *
 * 本地跑不了各家真实分词器，用启发式近似：
 * - CJK 字符按 1 token/字（略偏保守，宁可少装一点原文也不要超窗口）；
 * - 其余按 4 字符 ≈ 1 token，逐段累加（每段至少算 1）。
 *
 * 这是估算值，不是对账凭据。真实 token 以服务商 usage 返回为准，
 * 调试面板里能看到估算与实际的偏差，按真实占用再回头调 11.2 的预算数字。
 */
object TokenEstimator {

    private const val LATIN_CHARS_PER_TOKEN = 4

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        var total = 0
        var latinRun = 0

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    if (latinRun > 0) {
                        total += latinRunTokens(latinRun)
                        latinRun = 0
                    }
                    total += 1
                }
                ch.isWhitespace() -> {
                    if (latinRun > 0) {
                        total += latinRunTokens(latinRun)
                        latinRun = 0
                    }
                }
                else -> latinRun++
            }
        }

        if (latinRun > 0) total += latinRunTokens(latinRun)
        return total
    }

    private fun latinRunTokens(length: Int): Int = (length + LATIN_CHARS_PER_TOKEN - 1) / LATIN_CHARS_PER_TOKEN

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0xAC00..0xD7AF
    }
}
