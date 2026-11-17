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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini 适配器。
 *
 * 与 OpenAI 兼容协议的差异：
 * 1. system 走顶层 systemInstruction；
 * 2. 角色名是 model 而不是 assistant；
 * 3. 流式要显式带 alt=sse，返回才是 data: 形式，这样才能复用统一的 SSE 解析。
 *
 * base_url 由模板直接给出含版本号的完整前缀（…/v1beta），
 * 因此这里不做 /v1 补全，只去掉尾部斜杠。
 */
class GeminiAdapter : ProviderAdapter {

    override val protocol: Protocol = Protocol.GEMINI

    private val json = HttpSupport.json

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        val config = request.config
        val httpRequest = HttpSupport.jsonRequest(
            url = generateUrl(config, stream = true),
            body = buildRequestBody(request),
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
                val chunk = runCatching { json.decodeFromString<GeminiChunk>(payload) }.getOrNull()
                    ?: return@forEachSseData

                chunk.promptFeedback?.blockReason?.let { reason ->
                    emit(ChatStreamEvent.Failed(ProviderError("内容被安全策略拦截：$reason")))
                    return@forEachSseData
                }

                chunk.usageMetadata?.promptTokenCount?.let { promptTokens = it }
                chunk.usageMetadata?.candidatesTokenCount?.let { completionTokens = it }

                val text = chunk.candidates
                    .firstOrNull()
                    ?.content
                    ?.parts
                    ?.joinToString("") { it.text.orEmpty() }

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
                    url = generateUrl(config, stream = false),
                    body = buildRequestBody(request),
                    headers = authHeaders(config)
                )
                val startedAt = System.currentTimeMillis()

                HttpSupport.standardClient.newCall(httpRequest).execute().use { httpResponse ->
                    val raw = httpResponse.body.string()
                    if (!httpResponse.isSuccessful) {
                        throw ProviderException(HttpSupport.httpError(httpResponse, raw))
                    }
                    val parsed = json.decodeFromString<GeminiChunk>(raw)
                    val text = parsed.candidates
                        .firstOrNull()
                        ?.content
                        ?.parts
                        ?.joinToString("") { it.text.orEmpty() }
                        .orEmpty()
                    ChatCompletion(
                        text = text,
                        promptTokens = parsed.usageMetadata?.promptTokenCount,
                        completionTokens = parsed.usageMetadata?.candidatesTokenCount,
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
                    url = "${config.baseUrl.trim().trimEnd('/')}/models",
                    headers = authHeaders(config)
                )
                HttpSupport.standardClient.newCall(httpRequest).execute().use { httpResponse ->
                    val raw = httpResponse.body.string()
                    if (!httpResponse.isSuccessful) {
                        throw ProviderException(HttpSupport.httpError(httpResponse, raw))
                    }
                    json.decodeFromString<GeminiModelList>(raw)
                        .models
                        .mapNotNull { it.name?.removePrefix("models/") }
                        .filter { it.isNotBlank() }
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
                // Gemini 走 responseSchema，等价于 JSON 模式，但需要声明结构，暂按不支持处理
                jsonMode = false,
                functionCalling = true,
                contextWindow = config.capabilities.contextWindow,
                probedAt = System.currentTimeMillis()
            )
        )
    }

    private fun authHeaders(config: ProviderConfig): Map<String, String> =
        mapOf("x-goog-api-key" to config.apiKey)

    private fun generateUrl(config: ProviderConfig, stream: Boolean): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "$base/models/${config.modelId}:$action"
    }

    private fun buildRequestBody(request: ChatRequest): String {
        val config = request.config
        return buildJsonObject {
            if (request.systemPrompt.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", request.systemPrompt) })
                    }
                })
            }
            putJsonArray("contents") {
                mergeContents(request.turns).forEach { turn ->
                    add(buildJsonObject {
                        put("role", if (turn.role == MessageRole.ASSISTANT) "model" else "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", turn.content) })
                        }
                    })
                }
            }
            put("generationConfig", buildJsonObject {
                put("temperature", request.temperatureOverride ?: config.params.temperature)
                put("topP", config.params.topP)
                put("maxOutputTokens", request.maxTokensOverride ?: config.params.maxTokens)
                if (request.stopSequences.isNotEmpty()) {
                    putJsonArray("stopSequences") { request.stopSequences.forEach { add(it) } }
                }
                config.params.reasoning.thinkingBudget()?.let { budget ->
                    putJsonObject("thinkingConfig") {
                        put("thinkingBudget", budget)
                        // 不把思考过程混进正文：模型返回的 thought part 会污染角色台词
                        put("includeThoughts", false)
                    }
                }
            })
        }.toString()
    }

    /**
     * 合并相邻同角色内容，并保证首条是 user。
     *
     * Gemini 要求 contents 在 user / model 之间交替，首条必须是 user。
     * 群聊里「上一位角色」「这一位角色」的旧台词都挂在 model 侧，连排是常态，
     * 不合并就会被拒。
     *
     * 换行拼接**不会**丢掉「谁说的」：上游给每一行都带了「【说话人】：」标记，
     * 归属信息在行内，不依赖消息边界（与 AnthropicAdapter.mergeTurns 同一约定）。
     */
    private fun mergeContents(turns: List<ChatTurn>): List<ChatTurn> {
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
        // 首条必须是 user。turns 为空时（例如只发 system 的抽取请求）不硬塞占位，
        // 否则会凭空多出一条用户输入。
        if (merged.isNotEmpty() && merged.first().role != MessageRole.USER) {
            merged.add(0, ChatTurn(MessageRole.USER, CONTINUE_PLACEHOLDER))
        }
        return merged
    }

    private companion object {
        const val CONTINUE_PLACEHOLDER = "（继续上文的对话）"
    }

    @Serializable
    private data class GeminiChunk(
        val candidates: List<Candidate> = emptyList(),
        val usageMetadata: UsageMetadata? = null,
        val promptFeedback: PromptFeedback? = null
    ) {
        @Serializable
        data class Candidate(val content: Content? = null)

        @Serializable
        data class Content(val parts: List<Part> = emptyList())

        @Serializable
        data class Part(val text: String? = null)

        @Serializable
        data class UsageMetadata(
            val promptTokenCount: Int? = null,
            val candidatesTokenCount: Int? = null
        )

        @Serializable
        data class PromptFeedback(val blockReason: String? = null)
    }

    @Serializable
    private data class GeminiModelList(val models: List<Item> = emptyList()) {
        @Serializable
        data class Item(val name: String? = null)
    }
}
