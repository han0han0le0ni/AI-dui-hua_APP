package com.personal.roleplay.data.local

import androidx.room.TypeConverter
import com.personal.roleplay.domain.model.PromptBlockRecord
import com.personal.roleplay.domain.model.ProviderSnapshot
import com.personal.roleplay.domain.model.SummarySegment
import kotlinx.serialization.json.Json

/**
 * Room 类型转换器。
 *
 * 所有 JSON 解析都带兜底：某条记录的数据损坏只影响它自己，
 * 不会让整个会话列表打不开。
 */
class Converters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---- List<String> ----

    @TypeConverter
    fun stringListToString(value: List<String>?): String =
        json.encodeToString(value ?: emptyList())

    @TypeConverter
    fun stringToStringList(value: String?): List<String> =
        value?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

    // ---- List<SummarySegment>（L1 分段摘要）----

    @TypeConverter
    fun summarySegmentsToString(value: List<SummarySegment>?): String =
        json.encodeToString(value ?: emptyList())

    @TypeConverter
    fun stringToSummarySegments(value: String?): List<SummarySegment> =
        value?.let { raw ->
            runCatching { json.decodeFromString<List<SummarySegment>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

    // ---- ProviderSnapshot ----

    @TypeConverter
    fun providerSnapshotToString(value: ProviderSnapshot?): String? =
        value?.let { json.encodeToString(it) }

    @TypeConverter
    fun stringToProviderSnapshot(value: String?): ProviderSnapshot? =
        value?.let { raw -> runCatching { json.decodeFromString<ProviderSnapshot>(raw) }.getOrNull() }

    // ---- List<PromptBlockRecord>（调试面板）----

    @TypeConverter
    fun promptBlocksToString(value: List<PromptBlockRecord>?): String =
        json.encodeToString(value ?: emptyList())

    @TypeConverter
    fun stringToPromptBlocks(value: String?): List<PromptBlockRecord> =
        value?.let { raw ->
            runCatching { json.decodeFromString<List<PromptBlockRecord>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
}
