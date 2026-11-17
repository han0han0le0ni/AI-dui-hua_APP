package com.personal.roleplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personal.roleplay.domain.model.ProviderCapabilities
import com.personal.roleplay.domain.model.ProviderParams

/**
 * 接入配置。Key 不落库，只存 [keyAlias]，密文放在 EncryptedSharedPreferences 里。
 *
 * 参数 [ProviderParams] 与能力 [ProviderCapabilities] 定义在领域层，
 * 本实体只存它们序列化后的 JSON。
 */
@Entity(
    tableName = "provider_config",
    indices = [Index("slot"), Index("is_default")]
)
data class ProviderConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 别名，如「主力-DeepSeek」 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 协议，取值见 Protocol.wire */
    @ColumnInfo(name = "protocol")
    val protocol: String,

    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    /** ProviderParams 序列化后的 JSON */
    @ColumnInfo(name = "params_json")
    val paramsJson: String,

    /** ProviderCapabilities 序列化后的 JSON */
    @ColumnInfo(name = "capabilities_json")
    val capabilitiesJson: String,

    /** 槽位，取值见 Slot.wire */
    @ColumnInfo(name = "slot")
    val slot: String,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,

    /** EncryptedSharedPreferences 中的键名 */
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
