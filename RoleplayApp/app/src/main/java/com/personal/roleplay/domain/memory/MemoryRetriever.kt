package com.personal.roleplay.domain.memory

import android.util.Log
import com.personal.roleplay.domain.model.ChatTurn
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.provider.ChatRequest
import com.personal.roleplay.domain.provider.ProviderAdapterFactory
import com.personal.roleplay.domain.repository.MemoryRepository

/**
 * 粗筛命中的一条记忆。[score] 越小越相关（bm25 的语义）。
 */
data class CoarseHit(
    val memoryId: String,
    val score: Double
)

/**
 * 情节记忆检索（8.3）：不引 embedding，两级筛选。
 *
 * 1. 粗筛：FTS5 关键词检索取 Top 20（索引不可用时退化为内存打分）
 * 2. 精挑：把这批候选连同当前对话交给记忆抽取槽的小模型，让它只输出真正相关的编号
 * 3. 注入：只有被挑中的几条进主对话 prompt
 *
 * 未配置辅助槽时退化为纯粗筛 Top-K，功能不阻断。
 */
class MemoryRetriever(
    private val memoryRepository: MemoryRepository,
    /** 由数据层注入的 FTS5 检索实现；返回空列表时走内存打分兜底 */
    private val coarseSearch: suspend (terms: List<String>, limit: Int) -> List<CoarseHit>,
    private val resolveConfig: suspend (Slot) -> ProviderConfig?
) {

    suspend fun retrieveEpisodic(
        sessionId: String,
        terms: List<String>,
        topK: Int = DEFAULT_TOP_K
    ): List<MemoryItem> {
        val candidates = memoryRepository.getByLayer(sessionId, MemoryLayer.L3_EPISODIC)
        if (candidates.isEmpty()) return emptyList()

        // 候选本身就少于 topK，没什么好挑的
        if (candidates.size <= topK) {
            memoryRepository.markRecalled(candidates.map { it.id }, System.currentTimeMillis())
            return candidates
        }

        val coarse = coarseRank(candidates, terms, COARSE_LIMIT)
        val selected = rerank(coarse, terms, topK)

        memoryRepository.markRecalled(selected.map { it.id }, System.currentTimeMillis())
        return selected
    }

    private suspend fun coarseRank(
        candidates: List<MemoryItem>,
        terms: List<String>,
        limit: Int
    ): List<MemoryItem> {
        val byId = candidates.associateBy { it.id }

        val hits = runCatching { coarseSearch(terms, limit) }
            .onFailure { error -> Log.w(TAG, "粗筛失败，退化为内存打分", error) }
            .getOrDefault(emptyList())
            .mapNotNull { byId[it.memoryId] }

        if (hits.isNotEmpty()) return hits

        // 兜底：内存字面打分。个人数据量几千条，这一步足够快
        return candidates
            .map { memory -> memory to scoreByOverlap(memory.content, terms) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (memory, _) -> memory }
            .ifEmpty { candidates.take(limit) }
    }

    /**
     * 精挑。失败、超时、返回格式不对，都退回粗筛的头部——绝不因为检索失败阻断对话。
     */
    private suspend fun rerank(
        coarse: List<MemoryItem>,
        terms: List<String>,
        topK: Int
    ): List<MemoryItem> {
        if (coarse.isEmpty()) return emptyList()

        val config = resolveConfig(Slot.MEMORY_EXTRACTION) ?: run {
            Log.w(TAG, "未配置辅助槽，情节记忆退化为纯粗筛 Top-K")
            return coarse.take(topK)
        }

        return runCatching {
            val adapter = ProviderAdapterFactory.create(config.protocol)
            val context = terms.joinToString("\n").take(MAX_CONTEXT_CHARS)
            val prompt = MemoryPrompts.buildRerankPrompt(context, coarse)

            val completion = adapter.complete(
                ChatRequest(
                    config = config,
                    systemPrompt = prompt,
                    turns = listOf(ChatTurn(MessageRole.USER, "请输出相关记忆的编号。")),
                    maxTokensOverride = 120,
                    temperatureOverride = 0.1f
                )
            ).getOrNull() ?: return coarse.take(topK)

            parseIndices(completion.text, coarse.size)
                .mapNotNull { index -> coarse.getOrNull(index) }
                .take(topK)
                .ifEmpty { coarse.take(topK) }
        }.onFailure { error ->
            Log.w(TAG, "精挑失败，退回粗筛头部", error)
        }.getOrDefault(coarse.take(topK))
    }

    /** 从模型返回里抠出编号（1 起），非法或越界的一律丢掉 */
    private fun parseIndices(raw: String, candidateCount: Int): List<Int> =
        raw.split(',', '，', ' ', '\n', '\t', '、')
            .mapNotNull { token -> token.trim().trim('.', '。').toIntOrNull() }
            .filter { it in 1..candidateCount }
            .distinct()

    /**
     * 字面重叠打分：中英文都按子串命中计数，够用且零依赖。
     */
    private fun scoreByOverlap(content: String, terms: List<String>): Int {
        val lower = content.lowercase()
        return terms.sumOf { term ->
            val normalized = term.trim().lowercase()
            when {
                normalized.isBlank() -> 0
                normalized.length <= 2 -> if (lower.contains(normalized)) 1 else 0
                else -> normalized.chunked(2).count { lower.contains(it) }
            }
        }
    }

    private companion object {
        const val TAG = "MemoryRetriever"
        const val COARSE_LIMIT = 20
        const val DEFAULT_TOP_K = 5
        const val MAX_CONTEXT_CHARS = 800
    }
}
