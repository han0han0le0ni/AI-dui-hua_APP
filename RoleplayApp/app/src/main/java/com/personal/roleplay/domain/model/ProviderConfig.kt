package com.personal.roleplay.domain.model

import kotlinx.serialization.Serializable

/**
 * 思考/推理强度。
 *
 * 三家协议的字段形状完全不同：OpenAI 是 `reasoning_effort` 字符串，
 * Anthropic 是 `thinking.budget_tokens` 数字，Gemini 是 `thinkingConfig.thinkingBudget`。
 * 这里用同一个档位表示，各自映射，避免界面上出现三套旋钮。
 *
 * [OFF] 表示**不发送任何推理字段**，交给服务商默认行为——这跟「发送 low」是两回事，
 * 非推理模型收到这些字段可能直接报错。
 */
@Serializable
enum class ReasoningLevel(val displayName: String) {
    OFF("关闭"),
    LOW("低"),
    MEDIUM("中"),
    HIGH("高");

    /** OpenAI 的 reasoning_effort */
    fun openAiEffort(): String? = when (this) {
        OFF -> null
        LOW -> "low"
        MEDIUM -> "medium"
        HIGH -> "high"
    }

    /** Anthropic / Gemini 的思考预算 token 数 */
    fun thinkingBudget(): Int? = when (this) {
        OFF -> null
        LOW -> 1_024
        MEDIUM -> 4_096
        HIGH -> 12_000
    }
}

/**
 * 采样与推理参数。
 *
 * 这个类整体以 JSON 存在 provider_config.params_json 里，读取时字段缺失一律走默认值，
 * 所以**增删字段不需要数据库迁移**，老配置会自动补上默认值。
 */
@Serializable
data class ProviderParams(
    val temperature: Float = 0.9f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 1024,
    /** 存在惩罚，仅 OpenAI 兼容协议支持 */
    val presencePenalty: Float = 0f,
    /** 频率惩罚，仅 OpenAI 兼容协议支持 */
    val frequencyPenalty: Float = 0f,
    /** 思考强度，默认关闭 */
    val reasoning: ReasoningLevel = ReasoningLevel.OFF
) {
    /** Anthropic 的思考预算；关闭思考时为 null */
    fun anthropicThinkingBudget(): Int? = reasoning.thinkingBudget()

    /**
     * 开启思考时把上限抬到预算之上。
     *
     * Anthropic 硬性要求 max_tokens 必须大于思考预算，否则 400。
     * 用户设的 1024 是「正文想多长」，不该被思考预算挤掉，所以这里只抬高不压低。
     * max_tokens 是上限而非预留，抬高不会多花钱。
     */
    fun anthropicMaxTokens(maxTokens: Int): Int {
        val budget = anthropicThinkingBudget() ?: return maxTokens
        return maxOf(maxTokens, budget + MIN_REPLY_TOKENS)
    }

    /**
     * 必须是**公开** companion。
     *
     * kotlinx.serialization 生成的 `serializer()` 挂在 companion 上；companion 一旦声明为
     * private，其他文件里 `decodeFromString<ProviderParams>()` 就会在运行时抛
     * IllegalAccessError（尤其经 runCatching 这类 inline 函数展开后），而且往往被
     * runCatching 吞掉，表现为「参数莫名全变默认值」。
     */
    companion object {
        private const val MIN_REPLY_TOKENS = 1_024
    }
}

/**
 * 能力探测结果，驱动 6.2 的降级策略。
 */
@Serializable
data class ProviderCapabilities(
    val streaming: Boolean = true,
    val jsonMode: Boolean = false,
    val functionCalling: Boolean = false,
    /** 上下文窗口 token 数，压缩触发阈值按它算 */
    val contextWindow: Int = 32_768,
    /** 最近一次探测成功时间；0 表示从未探测 */
    val probedAt: Long = 0L
)

/**
 * 接入配置（领域层）。
 *
 * [apiKey] 只在发起请求前由 SecureKeyVault 注入，不落库、不进导出文件、不进日志。
 * 需要展示或落盘时一律走 [toSafeSummary] / [keyTail]。
 */
data class ProviderConfig(
    val id: String,
    /** 别名，如「主力-DeepSeek」 */
    val name: String,
    val protocol: Protocol,
    val baseUrl: String,
    val modelId: String,
    val params: ProviderParams,
    val capabilities: ProviderCapabilities,
    val slot: Slot,
    val isDefault: Boolean,
    /** EncryptedSharedPreferences 中的键名 */
    val keyAlias: String,
    val createdAt: Long,
    val updatedAt: Long,
    val apiKey: String = ""
) {
    /** Key 尾号，列表页只显示别名与尾号 */
    val keyTail: String
        get() = if (apiKey.length >= 4) apiKey.takeLast(4) else "••••"

    val hasKey: Boolean
        get() = apiKey.isNotBlank()

    fun toSafeSummary(): String = "$name · ${protocol.displayName} · $modelId"

    /** 复制时脱敏，避免 Key 出现在调试面板或日志里 */
    fun redacted(): String = toSafeSummary()
}

/** 探测结果：鉴权 → 拉模型列表 → 能力探测 */
data class ProbeResult(
    val success: Boolean,
    val message: String,
    val models: List<String> = emptyList(),
    val capabilities: ProviderCapabilities? = null
)
