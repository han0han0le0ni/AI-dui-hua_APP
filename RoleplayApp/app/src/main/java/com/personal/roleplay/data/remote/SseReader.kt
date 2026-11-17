package com.personal.roleplay.data.remote

import okhttp3.Response
import okio.BufferedSource

/**
 * SSE 按行读取。
 *
 * 声明为 inline 是为了让调用方在回调里直接 emit 到 Flow——
 * 非 inline 的 lambda 不能调用 suspend 函数。
 *
 * readUtf8Line 会一直阻塞到有一整行可读，天然适合流式输出；
 * 源码耗尽时返回 null，循环结束。
 */
inline fun Response.forEachSseData(onData: (String) -> Unit) {
    val source: BufferedSource = body.source()
    while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) continue
        onData(payload)
    }
}
