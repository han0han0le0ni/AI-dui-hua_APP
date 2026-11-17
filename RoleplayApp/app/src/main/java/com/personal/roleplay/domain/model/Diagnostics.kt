package com.personal.roleplay.domain.model

/**
 * 一次请求的完整快照（「请求调试」页）。
 *
 * 这是整个 App 里唯一能看见「实际发了什么」的地方：
 * 分区块折叠的完整 prompt、各区块 token 占用、模型返回原文、耗时、错误信息。
 */
data class RequestLog(
    val id: String,
    val sessionId: String?,
    val slot: Slot,
    val providerName: String,
    val modelId: String,
    /** 完整 prompt，可复制、可导出 */
    val fullPrompt: String,
    val blocks: List<PromptBlockRecord>,
    val rawResponse: String?,
    val errorMessage: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
    val success: Boolean,
    val createdAt: Long
) {
    /** Key 绝不能出现在调试面板里，因此拼接时只暴露脱敏后的配置名 */
    val totalTokens: Int
        get() = promptTokens + completionTokens
}
