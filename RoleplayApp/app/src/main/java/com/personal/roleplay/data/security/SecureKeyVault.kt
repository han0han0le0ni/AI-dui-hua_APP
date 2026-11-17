package com.personal.roleplay.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Key 存储：Android Keystore + EncryptedSharedPreferences（6.4）。
 *
 * Key 是本机唯一的凭据，因此：
 * - 只存这里，绝不进 Room，也绝不进备份文件（导入后需重新录入）；
 * - 对外只暴露尾号，不提供「列出全部 Key」这类接口；
 * - 初始化失败直接抛错，不退化成明文存储。
 */
class SecureKeyVault(context: Context) {

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** 写入或覆盖某个配置别名下的 Key */
    fun put(alias: String, apiKey: String) {
        preferences.edit().putString(alias, apiKey).apply()
        Log.i(TAG, "put: 已更新 Key，alias=$alias，尾号=${tailOf(alias)}")
    }

    /** 读取 Key；不存在返回空串而不是 null，减少调用点的空判断 */
    fun get(alias: String): String =
        preferences.getString(alias, "").orEmpty()

    fun remove(alias: String) {
        preferences.edit().remove(alias).apply()
        Log.i(TAG, "remove: 已删除 Key，alias=$alias")
    }

    /** 列表页展示用：只给尾号 */
    fun tailOf(alias: String): String {
        val value = get(alias)
        return if (value.length >= 4) value.takeLast(4) else "••••"
    }

    fun hasKey(alias: String): Boolean = get(alias).isNotBlank()

    companion object {
        private const val TAG = "SecureKeyVault"
        private const val PREFS_FILE_NAME = "roleplay_provider_keys"

        /** 新建配置时生成别名，避免与已有配置冲突 */
        fun newAlias(): String = "key_${java.util.UUID.randomUUID().toString().replace("-", "")}"
    }
}
