package com.personal.roleplay.domain.model

/**
 * 单次调用的用量记录。
 *
 * [estimatedCost] 按内置价目表估算，单位元。服务商返回的 token 数缺失时按估算值兜底，
 * 所以这些数字是量级参考而不是对账凭据。
 */
data class UsageRecord(
    val id: String,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val slot: Slot,
    val sessionId: String?,
    val characterId: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCost: Double,
    val latencyMs: Long,
    val success: Boolean,
    val errorMessage: String?,
    val createdAt: Long
)

data class UsageTotals(
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCost: Double,
    val callCount: Int
)

data class ModelUsage(
    val modelId: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCost: Double,
    val callCount: Int
)

data class SlotUsage(
    val slot: Slot,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCost: Double,
    val callCount: Int
)

data class CharacterUsage(
    val characterId: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCost: Double,
    val callCount: Int
)

/**
 * 内置价目表（元 / 百万 token）。
 *
 * 只是常见模型的默认值，界面上允许手动改；匹配不到时按 [FALLBACK] 的单价算，
 * 至少能给出一个量级，不至于让「用量」页全是 0。
 */
object PriceTable {

    private const val FALLBACK_INPUT = 4.0
    private const val FALLBACK_OUTPUT = 16.0

    /** 输入价，元 / 百万 token */
    private val inputPrices = mapOf(
        "gpt-4o" to 17.5,
        "gpt-4o-mini" to 1.05,
        "claude-3-5-sonnet" to 21.0,
        "claude-3-5-haiku" to 5.6,
        "gemini-2.0-flash" to 0.7,
        "gemini-1.5-pro" to 9.0,
        "deepseek-chat" to 1.4,
        "deepseek-reasoner" to 2.8,
        "moonshot-v1-8k" to 0.0,
        "moonshot-v1-32k" to 1.7,
        "moonshot-v1-128k" to 4.3,
        "glm-4-flash" to 0.0,
        "glm-4-plus" to 3.6,
        "qwen-plus" to 2.8,
        "qwen-turbo" to 0.6,
        "qwen-max" to 8.4,
        "hunyuan-turbo" to 1.05
    )

    /** 输出价，元 / 百万 token */
    private val outputPrices = mapOf(
        "gpt-4o" to 70.0,
        "gpt-4o-mini" to 4.2,
        "claude-3-5-sonnet" to 105.0,
        "claude-3-5-haiku" to 28.0,
        "gemini-2.0-flash" to 2.8,
        "gemini-1.5-pro" to 36.0,
        "deepseek-chat" to 5.6,
        "deepseek-reasoner" to 11.2,
        "moonshot-v1-8k" to 0.0,
        "moonshot-v1-32k" to 1.7,
        "moonshot-v1-128k" to 4.3,
        "glm-4-flash" to 0.0,
        "glm-4-plus" to 3.6,
        "qwen-plus" to 8.4,
        "qwen-turbo" to 1.8,
        "qwen-max" to 25.2,
        "hunyuan-turbo" to 3.15
    )

    /**
     * 按前缀匹配：模型 id 常带版本号或厂商前缀（如 openai/gpt-4o-mini），
     * 因此从最长键开始找，命中就停。
     */
    fun estimateCost(modelId: String, promptTokens: Int, completionTokens: Int): Double {
        val key = inputPrices.keys
            .filter { modelId.contains(it, ignoreCase = true) }
            .maxByOrNull { it.length }

        val inputRate = key?.let { inputPrices[it] } ?: FALLBACK_INPUT
        val outputRate = key?.let { outputPrices[it] } ?: FALLBACK_OUTPUT

        return promptTokens / 1_000_000.0 * inputRate + completionTokens / 1_000_000.0 * outputRate
    }
}
