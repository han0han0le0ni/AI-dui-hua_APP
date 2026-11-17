package com.personal.roleplay.domain.provider

import com.personal.roleplay.domain.model.ChatTurn
import com.personal.roleplay.domain.model.ProviderConfig

/**
 * 一次模型调用的请求。
 *
 * 三个 override 是为了槽位差异：记忆抽取要低温度 + JSON 模式，
 * 摘要压缩要小 maxTokens，主对话用配置里的原值。
 */
data class ChatRequest(
    val config: ProviderConfig,
    val systemPrompt: String,
    val turns: List<ChatTurn>,
    /** JSON 模式；服务商不支持时由适配器自动降级为普通文本 + 更强后处理 */
    val jsonMode: Boolean = false,
    val maxTokensOverride: Int? = null,
    val temperatureOverride: Float? = null,
    val stopSequences: List<String> = emptyList()
)

/**
 * 接入适配层统一接口：协议适配 · 能力注册 · 槽位路由 · 计量。
 */
interface ProviderAdapter {

    val protocol: com.personal.roleplay.domain.model.Protocol

    /** 流式对话。失败通过 ChatStreamEvent.Failed 下发，不抛异常 */
    fun streamChat(request: ChatRequest): kotlinx.coroutines.flow.Flow<com.personal.roleplay.domain.model.ChatStreamEvent>

    /** 一次性对话，用于记忆抽取、摘要压缩这类不需要打字机的场景 */
    suspend fun complete(request: ChatRequest): Result<com.personal.roleplay.domain.model.ChatCompletion>

    /** 拉取可用模型列表，同时用于校验 Key 与地址 */
    suspend fun listModels(config: ProviderConfig): Result<List<String>>

    /** 能力探测：鉴权 → 拉模型 → 探 JSON 模式，结果驱动 6.2 的降级策略 */
    suspend fun probe(config: ProviderConfig): com.personal.roleplay.domain.model.ProbeResult
}
