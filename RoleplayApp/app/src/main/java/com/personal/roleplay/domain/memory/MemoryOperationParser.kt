package com.personal.roleplay.domain.memory

import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MemoryOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 记忆操作指令解析（8.2）。
 *
 * 两条路径：
 * 1. JSON 数组——服务商支持 JSON 模式时的正常路径；
 * 2. 竖线分隔的文本——不支持 JSON 模式时的降级路径（见 6.2）。
 *
 * 解析不出任何有效指令时返回 [MemoryOperation.Noop]，
 * 调用方按「格式错误」处理：静默跳过，下次再抽。
 */
object MemoryOperationParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(raw: String): List<MemoryOperation> {
        if (raw.isBlank()) return listOf(MemoryOperation.Noop)

        val arrayText = extractJsonArray(raw)
        if (arrayText != null) {
            val parsed = runCatching { json.decodeFromString<List<OpDto>>(arrayText) }.getOrNull()
            if (parsed != null) {
                val operations = parsed.mapNotNull { it.toOperation() }
                if (operations.isNotEmpty()) return operations
            }
        }

        return parseText(raw)
    }

    /** 模型常在 JSON 前后加说明文字，这里把数组部分抠出来 */
    private fun extractJsonArray(raw: String): String? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start == -1 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    /**
     * 降级解析，格式：
     * ADD|L2|内容|12,15
     * UPDATE|记忆id|内容|14
     * DELETE|记忆id|原因
     * NOOP
     */
    private fun parseText(raw: String): List<MemoryOperation> {
        val operations = mutableListOf<MemoryOperation>()

        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val op = line.substringBefore('|').trim().uppercase()
                if (!op.startsWith("ADD") &&
                    !op.startsWith("UPDATE") &&
                    !op.startsWith("DELETE") &&
                    !op.startsWith("NOOP")
                ) return@forEach

                val parts = line.split('|').map { it.trim() }
                when {
                    op.startsWith("ADD") && parts.size >= 3 -> {
                        operations += MemoryOperation.Add(
                            layer = MemoryLayer.fromWire(parts[1]),
                            content = parts[2],
                            sourceRefs = parseRefs(parts.getOrNull(3))
                        )
                    }
                    op.startsWith("UPDATE") && parts.size >= 3 -> {
                        operations += MemoryOperation.Update(
                            memoryId = parts[1],
                            content = parts[2],
                            sourceRefs = parseRefs(parts.getOrNull(3))
                        )
                    }
                    op.startsWith("DELETE") && parts.size >= 2 -> {
                        operations += MemoryOperation.Delete(
                            memoryId = parts[1],
                            reason = parts.getOrNull(2)
                        )
                    }
                    op.startsWith("NOOP") -> operations += MemoryOperation.Noop
                }
            }

        return operations.ifEmpty { listOf(MemoryOperation.Noop) }
    }

    private fun parseRefs(raw: String?): List<Int> =
        raw?.split(',', '，', ' ')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()

    @Serializable
    private data class OpDto(
        val op: String? = null,
        val layer: String? = null,
        val id: String? = null,
        val content: String? = null,
        val source_refs: List<Int>? = null,
        val reason: String? = null
    ) {
        fun toOperation(): MemoryOperation? {
            return when (op?.trim()?.uppercase()) {
                "ADD" -> {
                    val text = content?.trim().orEmpty()
                    if (text.isBlank()) return null
                    MemoryOperation.Add(
                        layer = MemoryLayer.fromWire(layer),
                        content = text,
                        sourceRefs = source_refs.orEmpty()
                    )
                }
                "UPDATE" -> {
                    val targetId = id?.trim().orEmpty()
                    val text = content?.trim().orEmpty()
                    if (targetId.isBlank() || text.isBlank()) return null
                    MemoryOperation.Update(
                        memoryId = targetId,
                        content = text,
                        sourceRefs = source_refs.orEmpty()
                    )
                }
                "DELETE" -> {
                    val targetId = id?.trim().orEmpty()
                    if (targetId.isBlank()) return null
                    MemoryOperation.Delete(memoryId = targetId, reason = reason?.trim())
                }
                else -> MemoryOperation.Noop
            }
        }
    }
}
