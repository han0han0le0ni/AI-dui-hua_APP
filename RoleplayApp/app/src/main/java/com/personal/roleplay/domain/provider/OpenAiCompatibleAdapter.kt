package com.personal.roleplay.domain.provider

import com.personal.roleplay.data.remote.HttpSupport
import com.personal.roleplay.data.remote.ProviderException
import com.personal.roleplay.data.remote.forEachSseData
import com.personal.roleplay.domain.model.ChatCompletion
import com.personal.roleplay.domain.model.ChatStreamEvent
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.ProbeResult
import com.personal.roleplay.domain.model.Protocol
import com.personal.roleplay.domain.model.ProviderCapabilities
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OpenAI 兼容协议适配器。
 *
 * 覆盖：OpenAI、DeepSeek、Kimi、智谱 GLM、通义千问（兼容模式）、豆包、混元、
 * SiliconFlow、OpenRouter、Ollama（/v1）、LM Studio、以及「自定义」。
 * 只要是对外暴露 /v1/chat/completions 的服务，都能直接接进来。
 */
class OpenAiCompatibleAdapter : ProviderAdapter {

    override val protocol: Protocol = Protocol.OPENAI_COMPATIBLE

    private val json = HttpSupport.json

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        val config = request.config
        val httpRequest = HttpSupport.jsonRequest(
            url = chatCompletionsUrl(config.baseUrl),
            body = buildRequestBody(request, stream = true),
            headers = authHeaders(config)
        )

        val response = runCatching {
            HttpSupport.streamingClient.newCall(httpRequest).execute()
        }.getOrElse { error ->
            emit(ChatStreamEvent.Failed(ProviderError("无法连接到 ${config.name}：${error.message}")))
            return@flow
        }

        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) {
                emit(ChatStreamEvent.Failed(HttpSupport.httpError(httpResponse, HttpSupport.readErrorBody(httpResponse))))
                return@flow
            }

            val builder = StringBuilder()
            var promptTokens: Int? = null
            var completionTokens: Int? = null

            httpResponse.forEachSseData { payload ->
                if (payload == DONE_SENTINEL) return@forEachSseData

                val chunk = runCatching { json.decodeFromString<OpenAiChunk>(payload) }.getOrNull()
                    ?: return@forEachSseData

                chunk.usage?.promptTokens?.let { promptTokens = it }
                chunk.usage?.completionTokens?.let { completionTokens = it }

                val choice = chunk.choices.firstOrNull() ?: return@forEachSseData
                val text = choice.delta?.content ?: choice.message?.content
                if (!text.isNullOrEmpty()) {
                    builder.append(text)
                    emit(ChatStreamEvent.Delta(text))
                }
            }

            emit(ChatStreamEvent.Completed(builder.toString(), promptTokens, completionTokens))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun complete(request: ChatRequest): Result<ChatCompletion> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = request.config
                val httpRequest = HttpSupport.jsonRequest(
                    url = chatCompletionsUrl(config.baseUrl),
                    body = buildRequestBody(request, stream = false),
                    headers = authHeaders(config)
                )
                val startedAt = System.currentTimeMillis()

                HttpSupport.standardClient.newCall(httpRequest).execute().use { httpResponse ->
                    val raw = httpResponse.body.string()
                    if (!httpResponse.isSuccessful) {
                        throw ProviderException(HttpSupport.httpError(httpResponse, raw))
                    }
                    val parsed = json.decodeFromString<OpenAiChunk>(raw)
                    val choice = parsed.choices.firstOrNull()
                    ChatCompletion(
                        text = choice?.message?.content.orEmpty(),
                        promptTokens = parsed.usage?.promptTokens,
                        completionTokens = parsed.usage?.completionTokens,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        rawResponse = raw
                    )
                }
            }
        }

    override suspend fun listModels(config: ProviderConfig): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val httpRequest = HttpSupport.getRequest(
                    url = "${HttpSupport.normalizeBaseUrl(config.baseUrl)}/models",
                    headers = authHeaders(config)
                )
                HttpSupport.standardClient.newCall(httpRequest).execute().use { httpResponse ->
                    val raw = httpResponse.body.string()
                    if (!httpResponse.isSuccessful) {
                        throw ProviderException(HttpSupport.httpError(httpResponse, raw))
                    }
                    json.decodeFromString<OpenAiModelList>(raw)
                        .data
                        .mapNotNull { it.id }
                        .sorted()
                }
            }
        }

    override suspend fun probe(config: ProviderConfig): ProbeResult = withContext(Dispatchers.IO) {
        val models = listModels(config).getOrElse { error ->
            return@withContext ProbeResult(success = false, message = error.message ?: "连接失败")
        }

        // 用一次 1-token 请求探测 JSON 模式是否真的被支持
        val jsonMode = detectJsonMode(config)

        ProbeResult(
            success = true,
            message = "连接成功，可用 ${models.size} 个模型${if (jsonMode) "，支持 JSON 模式" else "，不支持 JSON 模式"}",
            models = models,
            capabilities = ProviderCapabilities(
                streaming = true,
                jsonMode = jsonMode,
                functionCalling = false,
                contextWindow = config.capabilities.contextWindow,
                probedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun detectJsonMode(config: ProviderConfig): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("model", config.modelId)
                put("max_tokens", 1)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "hi")
                    })
                }
                put("response_format", buildJsonObject { put("type", "json_object") })
            }.toString()

            val httpRequest = HttpSupport.jsonRequest(
                url = chatCompletionsUrl(config.baseUrl),
                body = body,
                headers = authHeaders(config)
            )
            HttpSupport.standardClient.newCall(httpRequest).execute().use { httpResponse ->
                when {
                    httpResponse.isSuccessful -> true
                    // 明确报 response_format 不支持，说明这家服务商没有 JSON 模式
                    httpResponse.code == 400 && HttpSupport.readErrorBody(httpResponse)
                        .contains("response_format", ignoreCase = true) -> false
                    else -> false
                }
            }
        }.getOrDefault(false)
    }

    private fun authHeaders(config: ProviderConfig): Map<String, String> =
        mapOf("Authorization" to "Bearer ${config.apiKey}")

    internal fun chatCompletionsUrl(baseUrl: String): String {
        val normalized = HttpSupport.normalizeBaseUrl(baseUrl)
        return if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
    }

    private fun buildRequestBody(request: ChatRequest, stream: Boolean): String {
        val config = request.config
        return buildJsonObject {
            put("model", config.modelId)
            put("stream", stream)
            put("temperature", request.temperatureOverride ?: config.params.temperature)
            put("top_p", config.params.topP)
            put("max_tokens", request.maxTokensOverride ?: config.params.maxTokens)
            if (config.params.presencePenalty != 0f) put("presence_penalty", config.params.presencePenalty)
            if (config.params.frequencyPenalty != 0f) put("frequency_penalty", config.params.frequencyPenalty)
            // 思考强度。非推理模型收到这个字段可能直接报错，所以只在用户显式开启时才发送
            config.params.reasoning.openAiEffort()?.let { effort ->
                put("reasoning_effort", effort)
            }
            if (request.jsonMode && config.capabilities.jsonMode) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            if (request.stopSequences.isNotEmpty()) {
                putJsonArray("stop") { request.stopSequences.forEach { add(it) } }
            }
            putJsonArray("messages") {
                if (request.systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.systemPrompt)
                    })
                }
                request.turns.forEach { turn ->
                    add(buildJsonObject {
                        put("role", roleWire(turn.role))
                        put("content", turn.content)
                    })
                }
            }
        }.toString()
    }

    /**
     * narration 对用户是旁白，对模型就是一段用户侧文本；
     * 只有 user / assistant 两种角色能进 OpenAI 的 messages。
     */
    private fun roleWire(role: MessageRole): String =
        if (role == MessageRole.ASSISTANT) "assistant" else "user"

    private companion object {
        const val DONE_SENTINEL = "[DONE]"
    }

    @Serializable
    private data class OpenAiChunk(
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null
    ) {
        @Serializable
        data class Choice(
            val delta: Delta? = null,
            val message: Message? = null
        )

        @Serializable
        data class Delta(val content: String? = null)

        @Serializable
        data class Message(val content: String? = null)

        /**
         * OpenAI 的 usage 是 snake_case。共享 Json 没有配全局命名策略
         * （配了会破坏 Gemini 的 promptTokenCount），所以这里显式标注线名。
         */
        @Serializable
        data class Usage(
            @SerialName("prompt_tokens") val promptTokens: Int? = null,
            @SerialName("completion_tokens") val completionTokens: Int? = null
        )
    }

    @Serializable
    private data class OpenAiModelList(val data: List<Item> = emptyList()) {
        @Serializable
        data class Item(val id: String? = null)
    }
}
