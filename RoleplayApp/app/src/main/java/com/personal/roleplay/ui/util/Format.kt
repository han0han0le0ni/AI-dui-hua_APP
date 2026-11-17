package com.personal.roleplay.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 相对时间：列表页不需要精确时间戳，只要知道「多久之前聊过」 */
fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return "还没聊过"

    val diff = now - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 60 * 60_000L -> "${diff / 60_000L} 分钟前"
        diff < 24 * 60 * 60_000L -> "${diff / (60 * 60_000L)} 小时前"
        diff < 7 * 24 * 60 * 60_000L -> "${diff / (24 * 60 * 60_000L)} 天前"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(timestamp))
    }
}

fun dateTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

fun dayKey(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))
