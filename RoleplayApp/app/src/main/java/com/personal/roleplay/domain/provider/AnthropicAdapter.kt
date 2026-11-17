package com.personal.roleplay.domain.provider

import com.personal.roleplay.data.remote.HttpSupport
import com.personal.roleplay.data.remote.ProviderException
import com.personal.roleplay.data.remote.forEachSseData
import com.personal.roleplay.domain.model.ChatCompletion
import com.personal.roleplay.domain.model.ChatStreamEvent
import com.personal.roleplay.domain.model.ChatTurn
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
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic 原生协议适配器。
 *
 * 与 OpenAI 兼容协议的三个关键差异：
 * 1. system 是顶层字段，不能混进 messages；
 * 2. max_tokens 是必填项；
 * 3. messages 必须严格 user/assistant 交替，且首条必须是 user。
 */
class AnthropicAdapter : ProviderAdapter {

    override val protocol: Protocol = Protocol.ANTHROPIC

    private val json = HttpSupport.json

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        val config = request.config
        val httpRequest = HttpSupport.jsonRequest(
            url = messagesUrl(config.baseUrl),
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
            var inputTokens: Int? = null
            var outputTokens: Int? = null

            httpResponse.forEachSseData { payload ->
                val event = runCatching { json.decodeFromString<AnthropicEvent>(payload) }.getOrNull()
                    ?: return@forEachSseData

                when (event.type) {
                    "message_start" -> {
                        inputTokens = event.message?.usage?.inputTokens ?: inputTokens
                        outputTokens = event.message?.usage?.outputTokens ?: outputTokens
                    }
                    "content_block_delta" -> {
                        val text = event.delta?.text
                        if (!text.isNullOrEmpty()) {
                            builder.append(text)
                            emit(ChatStreamEvent.Delta(text))
                        }
                    }
                    "message_delta" -> {
                        outputTokens = event.usage?.outputTokens ?: outputTokens
                    }
                    "error" -> {
                        emit(ChatStreamEvent.Failed(ProviderError(event.error?.message ?: "Anthropic 返回错误")))
                        return@forEachSseData
                    }
                }
            }

            emit(ChatStreamEvent.Completed(builder.toString(), inputTokens, outputTokens))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun complete(request: ChatRequest): Result<ChatCompletion> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = request.config
                val httpRequest = HttpSupport.jsonRequest(
                    url = messagesUrl(config.baseUrl),
                    body = buildRequestBody(request, stream = false),
                    headers = authHeaders(config)
                )
                val startedAt = System.currentTimeMillis()

                HttpSupport.standardClient.newCall(httpRequest).execute().use { httpResponse ->
                    val raw = httpResponse.body.string()
                    if (!httpResponse.isSuccessful) {
                        throw ProviderException(HttpSupport.httpError(httpResponse, raw))
                    }
                    val parsed = json.decodeFromString<AnthropicMessageResponse>(raw)
                    val text = parsed.content
                        .filter { it.type == "text" }
                        .joinToString("") { it.text.orEmpty() }
                    ChatCompletion(
                        text = text,
                        promptTokens = parsed.usage?.inputTokens,
                        completionTokens = parsed.usage?.outputTokens,
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
                    json.decodeFromString<AnthropicModelList>(raw)
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
        ProbeResult(
            success = true,
            message = "连接成功，可用 ${models.size} 个模型",
            models = models,
            capabilities = ProviderCapabilities(
                streaming = true,
                // Anthropic 原生接口没有 response_format，JSON 模式靠提示词约束
                jsonMode = false,
                functionCalling = true,
                contextWindow = config.capabilities.contextWindow,
                probedAt = System.currentTimeMillis()
            )
        )
    }

    private fun authHeaders(config: ProviderConfig): Map<String, String> = mapOf(
        "x-api-key" to config.apiKey,
        "anthropic-version" to ANTHROPIC_VERSION
    )

    private fun messagesUrl(baseUrl: String): String =
        "${HttpSupport.normalizeBaseUrl(baseUrl)}/messages"

    private fun buildRequestBody(request: ChatRequest, stream: Boolean): String {
        val config = request.config
        val requestedMax = request.maxTokensOverride ?: config.params.maxTokens

        // Anthropic 开启思考时有两条硬性约束，踩中任意一条都是 400：
        //   1. temperature 必须是 1（且不能再传 top_p）
        //   2. max_tokens 必须大于思考预算
        // 与其让用户自己记住这两条，不如在这里直接收敛；界面只负责提示。
        val thinkingBudget = config.params.anthropicThinkingBudget()
        val maxTokens = if (thinkingBudget != null) {
            config.params.anthropicMaxTokens(requestedMax)
        } else {
            requestedMax
        }
        val temperature = if (thinkingBudget != null) {
            1.0f
        } else {
            request.temperatureOverride ?: config.params.temperature
        }

        return buildJsonObject {
            put("model", config.modelId)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            if (thinkingBudget == null) put("top_p", config.params.topP)
            put("stream", stream)
            if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
            if (thinkingBudget != null) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget)
                }
            }
            if (request.stopSequences.isNotEmpty()) {
                putJsonArray("stop_sequences") { request.stopSequences.forEach { add(it) } }
            }
            putJsonArray("messages") {
                mergeTurns(request.turns).forEach { turn ->
                    add(buildJsonObject {
                        put("role", if (turn.role == MessageRole.ASSISTANT) "assistant" else "user")
                        put("content", turn.content)
                    })
                }
            }
        }.toString()
    }

    /**
     * 合并相邻同角色消息，并保证首条是 user。
     *
     * narration 归到 user 侧：对用户是旁白，对模型就是一段用户提供的上下文。
     *
     * 这里用换行拼接**不会**丢掉「谁说的」：上游给每一行都带了
     * 「【说话人】：」标记，归属信息在行内，不依赖消息边界。
     * 这一点是有约束的——如果哪天上游改成只在消息级别标注说话人，
     * 这个合并就会把不同人的话糊成一段，必须同步改掉。
     */
    private fun mergeTurns(turns: List<ChatTurn>): List<ChatTurn> {
        val merged = mutableListOf<ChatTurn>()
        turns.forEach { turn ->
            val role = if (turn.role == MessageRole.ASSISTANT) MessageRole.ASSISTANT else MessageRole.USER
            val previous = merged.lastOrNull()
            if (previous != null && previous.role == role) {
                merged[merged.lastIndex] = previous.copy(content = "${previous.content}\n${turn.content}")
            } else {
                merged.add(ChatTurn(role, turn.content))
            }
        }
        // Anthropic 要求首条必须是 user。但 turns 为空时（例如只发 system 的抽取请求）
        // 不能硬塞占位，否则会凭空多出一条用户输入。
        if (merged.isNotEmpty() && merged.first().role != MessageRole.USER) {
            merged.add(0, ChatTurn(MessageRole.USER, CONTINUE_PLACEHOLDER))
        }
        return merged
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val CONTINUE_PLACEHOLDER = "（继续上文的对话）"
    }

    @Serializable
    private data class AnthropicEvent(
        val type: String? = null,
        val delta: Delta? = null,
        val message: Message? = null,
        val usage: Usage? = null,
        val error: ErrorBody? = null
    ) {
        @Serializable
        data class Delta(val type: String? = null, val text: String? = null)

        @Serializable
        data class Message(val usage: Usage? = null)

        /** Anthropic 的 usage 同样是 snake_case，显式标注线名 */
        @Serializable
        data class Usage(
            @SerialName("input_tokens") val inputTokens: Int? = null,
            @SerialName("output_tokens") val outputTokens: Int? = null
        )

        @Serializable
        data class ErrorBody(val message: String? = null)
    }

    @Serializable
    private data class AnthropicMessageResponse(
        val content: List<ContentPart> = emptyList(),
        val usage: Usage? = null
    ) {
        @Serializable
        data class ContentPart(val type: String? = null, val text: String? = null)

        @Serializable
        data class Usage(
            @SerialName("input_tokens") val inputTokens: Int? = null,
            @SerialName("output_tokens") val outputTokens: Int? = null
        )
    }

    @Serializable
    private data class AnthropicModelList(val data: List<Item> = emptyList()) {
        @Serializable
        data class Item(val id: String? = null)
    }
}
