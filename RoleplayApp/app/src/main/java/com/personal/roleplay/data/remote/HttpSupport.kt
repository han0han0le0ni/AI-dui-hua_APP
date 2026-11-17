package com.personal.roleplay.data.remote

import com.personal.roleplay.domain.model.ProviderError
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 网络基础设施。
 *
 * 只用一个 OkHttpClient 家族：SSE 解析自己按行读（不引额外 SSE 库），
 * 三家协议都支持 alt=sse / data: 形式，因此解析逻辑可以共用一份。
 */
object HttpSupport {

    /** 错误体截断长度：服务商可能返回巨量 HTML，日志与调试面板都吃不消 */
    const val MAX_ERROR_BODY_CHARS = 2_000

    val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 解析用的 Json：字段缺失一律走默认值，服务商加字段不该让 App 崩 */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    /** 一次性请求：有读超时，避免探测时挂死 */
    val standardClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 流式请求：readTimeout 设为 0。
     * SSE 的首字可能几十秒后才到（长思考模型），有读超时会被掐断。
     */
    val streamingClient: OkHttpClient = standardClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /**
     * 规整 baseUrl：去尾部斜杠，并按需补 /v1。
     *
     * 各家文档给的 base_url 有的带 /v1、有的带 /api/v3、有的什么都不带，
     * 统一成「以 /v1 结尾」再拼具体路径，省掉大量边界判断。
     */
    fun normalizeBaseUrl(baseUrl: String, pathPrefix: String = "/v1"): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.endsWith(pathPrefix)) trimmed else trimmed + pathPrefix
    }

    fun httpError(response: Response, body: String): ProviderError =
        ProviderError(
            message = "请求失败：${response.code} ${response.message}".trim(),
            httpCode = response.code,
            rawBody = body.take(MAX_ERROR_BODY_CHARS)
        )

    /** 读失败响应体；流式响应已开始后不能重复读，失败场景都还没读过，安全 */
    fun readErrorBody(response: Response): String =
        runCatching { response.body.string() }.getOrDefault("")

    fun jsonRequest(url: String, body: String, headers: Map<String, String>): Request {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.post(okhttp3.RequestBody.create(jsonMediaType, body)).build()
    }

    fun getRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.get().build()
    }
}

/** 携带结构化错误的异常，供 Result 调用点还原 ProviderError */
class ProviderException(val error: ProviderError) : Exception(error.describe())
