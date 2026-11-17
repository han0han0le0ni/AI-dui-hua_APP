package com.personal.roleplay.domain.usage

import com.personal.roleplay.domain.model.PriceTable
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.model.UsageRecord
import com.personal.roleplay.domain.repository.UsageRepository
import com.personal.roleplay.util.Ids

/**
 * 用量计量。
 *
 * 服务商不返回 usage 时，调用方要先用估算值顶上再记一笔——
 * 「用量」页的意义是防止 Key 被烧，缺几条记录不如记个量级。
 */
class UsageTracker(
    private val usageRepository: UsageRepository
) {

    suspend fun record(
        config: ProviderConfig,
        slot: Slot,
        sessionId: String?,
        characterId: String?,
        promptTokens: Int,
        completionTokens: Int,
        latencyMs: Long,
        success: Boolean,
        errorMessage: String? = null
    ) {
        usageRepository.record(
            UsageRecord(
                id = Ids.new(),
                providerId = config.id,
                providerName = config.name,
                modelId = config.modelId,
                slot = slot,
                sessionId = sessionId,
                characterId = characterId,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                estimatedCost = PriceTable.estimateCost(config.modelId, promptTokens, completionTokens),
                latencyMs = latencyMs,
                success = success,
                errorMessage = errorMessage,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** 失败也要记一笔：排查「为什么一直在烧钱」时，失败记录往往比成功记录更有用 */
    suspend fun recordFailure(
        config: ProviderConfig,
        slot: Slot,
        sessionId: String?,
        characterId: String?,
        latencyMs: Long,
        errorMessage: String
    ) = record(
        config = config,
        slot = slot,
        sessionId = sessionId,
        characterId = characterId,
        promptTokens = 0,
        completionTokens = 0,
        latencyMs = latencyMs,
        success = false,
        errorMessage = errorMessage
    )
}
