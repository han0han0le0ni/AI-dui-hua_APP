package com.personal.roleplay.domain.model

/**
 * 流式输出事件。
 */
sealed interface ChatStreamEvent {

    /** 增量文本，UI 打字机渲染 */
    data class Delta(val text: String) : ChatStreamEvent

    /** 生成完成，携带真实 token 计量（服务商返回时才有值） */
    data class Completed(
        val fullText: String,
        val promptTokens: Int?,
        val completionTokens: Int?
    ) : ChatStreamEvent

    /** 失败。抽取与压缩场景可静默跳过，主对话场景要展示给用户 */
    data class Failed(val error: ProviderError) : ChatStreamEvent
}

/**
 * 统一的接入错误。
 */
data class ProviderError(
    val message: String,
    val httpCode: Int? = null,
    /** 服务商返回的原始错误体，调试面板要看 */
    val rawBody: String? = null
) {
    fun describe(): String = buildString {
        append(message)
        if (httpCode != null) append("（HTTP $httpCode）")
    }
}

/** 一次请求的结果快照，用于计量与调试面板 */
data class ChatCompletion(
    val text: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val latencyMs: Long,
    val rawResponse: String?
)
