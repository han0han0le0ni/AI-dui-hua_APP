package com.personal.roleplay.data.repository

import com.personal.roleplay.data.local.dao.SettingDao
import com.personal.roleplay.data.local.entity.AppSettingEntity
import com.personal.roleplay.domain.model.ReplyFormat
import com.personal.roleplay.domain.repository.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class AppSettingsRepositoryImpl(
    private val settingDao: SettingDao
) : AppSettingsRepository {

    override fun observeReplyFormat(): Flow<ReplyFormat> =
        settingDao.observeAll().map { rows -> decode(rows) }

    override suspend fun getReplyFormat(): ReplyFormat =
        decodeReplyFormat(settingDao.get(KEY_REPLY_FORMAT)?.value)

    override suspend fun saveReplyFormat(format: ReplyFormat) {
        settingDao.upsert(AppSettingEntity(KEY_REPLY_FORMAT, json.encodeToString(format)))
    }

    private fun decode(rows: List<AppSettingEntity>): ReplyFormat =
        decodeReplyFormat(rows.firstOrNull { it.key == KEY_REPLY_FORMAT }?.value)

    /** 解析失败退回默认格式，不让一条坏数据把对话卡住 */
    private fun decodeReplyFormat(raw: String?): ReplyFormat =
        raw?.let { runCatching { json.decodeFromString<ReplyFormat>(it) }.getOrNull() }
            ?: ReplyFormat.DEFAULT

    private companion object {
        const val KEY_REPLY_FORMAT = "reply_format"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
