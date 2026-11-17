package com.personal.roleplay.data.local

/**
 * FTS5 分词辅助。
 *
 * SQLite 的 unicode61 分词器会把一整串中文当成一个 token（"你怕打雷" = 1 个 token），
 * 直接拿它检索中文等于失效。解法是入库时把 CJK 字符两侧插空格，
 * 让每个汉字独立成 token；检索时对查询串做同样处理，再拼成 OR 查询。
 *
 * 这样不需要 trigram 分词器（SQLite 3.34+ 才有），在 Android 8 自带的
 * SQLite 3.18 上也能正常检索中文。
 */
object FtsSegmenter {

    private const val MAX_QUERY_TOKENS = 24

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF ||   // 日文假名
            code in 0x3400..0x4DBF ||      // CJK 扩展 A
            code in 0x4E00..0x9FFF ||      // CJK 基本区
            code in 0xF900..0xFAFF ||      // CJK 兼容表意文字
            code in 0xAC00..0xD7AF         // 韩文音节
    }

    /** 入库前处理：CJK 字符两侧补空格，其余字符保持原样 */
    fun segment(text: String): String {
        val builder = StringBuilder(text.length * 2)
        for (ch in text) {
            if (isCjk(ch)) {
                builder.append(' ').append(ch).append(' ')
            } else {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * 把检索词拼成 FTS5 的 MATCH 表达式。
     *
     * @param terms 检索词，通常是用户输入 + 最近若干条消息里抽出的关键词
     * @return null 表示没有可用 token，调用方应跳过 FTS 查询
     */
    fun buildMatchQuery(terms: List<String>): String? {
        val tokens = terms.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { term -> segment(term).split(' ', '\n', '\r', '\t').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_QUERY_TOKENS)
            .toList()

        if (tokens.isEmpty()) return null

        // FTS5 字符串字面量里，双引号用两个双引号转义
        return tokens.joinToString(" OR ") { token ->
            "\"${token.replace("\"", "\"\"")}\""
        }
    }
}
