package com.personal.roleplay.domain.provider

import com.personal.roleplay.domain.model.Protocol

/**
 * 接入模板（6.1 步骤 1）。
 *
 * 预填协议与地址，用户只需粘 Key、选模型、分配槽位。
 * 「自定义」模板不在列里，由界面单独提供空表单。
 *
 * Ollama / LM Studio 跑在本机或局域网，地址用 10.0.2.2（模拟器访问宿主机）。
 * 真机上要改成电脑的局域网 IP，这也正是 App 允许 usesCleartextTraffic 的原因。
 */
data class ProviderTemplate(
    val id: String,
    val displayName: String,
    val protocol: Protocol,
    val baseUrl: String,
    val defaultModel: String,
    val contextWindow: Int,
    /** Key 前缀提示，用于粘贴后自动识别 */
    val keyPrefixHint: String,
    /** 本地服务不需要 Key */
    val requiresKey: Boolean = true
)

object ProviderTemplates {

    val all: List<ProviderTemplate> = listOf(
        ProviderTemplate(
            id = "openai",
            displayName = "OpenAI",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            contextWindow = 128_000,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "anthropic",
            displayName = "Anthropic",
            protocol = Protocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-3-5-sonnet-latest",
            contextWindow = 200_000,
            keyPrefixHint = "sk-ant-"
        ),
        ProviderTemplate(
            id = "gemini",
            displayName = "Gemini",
            protocol = Protocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-2.0-flash",
            contextWindow = 1_048_576,
            keyPrefixHint = "AIza"
        ),
        ProviderTemplate(
            id = "deepseek",
            displayName = "DeepSeek",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            contextWindow = 65_536,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "moonshot",
            displayName = "Kimi（Moonshot）",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            contextWindow = 8_192,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "zhipu",
            displayName = "智谱 GLM",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            contextWindow = 128_000,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "qwen",
            displayName = "通义千问",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            contextWindow = 131_072,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "doubao",
            displayName = "豆包（火山方舟）",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-pro-32k",
            contextWindow = 32_768,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "hunyuan",
            displayName = "混元",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            defaultModel = "hunyuan-turbo",
            contextWindow = 32_768,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "siliconflow",
            displayName = "SiliconFlow",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            contextWindow = 32_768,
            keyPrefixHint = "sk-"
        ),
        ProviderTemplate(
            id = "openrouter",
            displayName = "OpenRouter",
            protocol = Protocol.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openai/gpt-4o-mini",
            contextWindow = 128_000,
            keyPrefixHint = "sk-or-"
        ),
        ProviderTemplate(
            id = "ollama",
            displayName = "Ollama（本地）",
            protocol = Protocol.OLLAMA,
            baseUrl = "http://10.0.2.2:11434/v1",
            defaultModel = "qwen2.5:7b",
            contextWindow = 32_768,
            keyPrefixHint = "",
            requiresKey = false
        ),
        ProviderTemplate(
            id = "lmstudio",
            displayName = "LM Studio（本地）",
            protocol = Protocol.OLLAMA,
            baseUrl = "http://10.0.2.2:1234/v1",
            defaultModel = "local-model",
            contextWindow = 32_768,
            keyPrefixHint = "",
            requiresKey = false
        )
    )

    fun findById(id: String): ProviderTemplate? = all.firstOrNull { it.id == id }
}

/**
 * 槽位分配（6.3）。
 *
 * Ollama / LM Studio 暴露的就是 OpenAI 兼容端点，
 * 因此与 OPENAI_COMPATIBLE 共用同一个适配器实现。
 */
object ProviderAdapterFactory {

    fun create(protocol: Protocol): ProviderAdapter = when (protocol) {
        Protocol.OPENAI_COMPATIBLE,
        Protocol.OLLAMA -> OpenAiCompatibleAdapter()

        Protocol.ANTHROPIC -> AnthropicAdapter()
        Protocol.GEMINI -> GeminiAdapter()
    }
}
