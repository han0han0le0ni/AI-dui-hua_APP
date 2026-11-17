package com.personal.roleplay.data.local

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 立绘存储：图片只落在 app 私有目录，不申请外存写权限（6.4 的数据边界）。
 */
object AvatarStore {

    private const val TAG = "AvatarStore"
    private const val MAX_BYTES = 20L * 1024 * 1024

    fun directory(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    /**
     * 把用户选中的图片拷进私有目录。
     *
     * @return 新文件的绝对路径；失败返回 null，由界面提示。
     */
    suspend fun import(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(directory(context), "avatar_${UUID.randomUUID()}.img")
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(target).use { output ->
                    val copied = input.copyTo(output)
                    if (copied > MAX_BYTES) {
                        target.delete()
                        error("图片过大（超过 20MB），请换一张")
                    }
                }
            } ?: error("无法读取选中的图片")
            target.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "import: 导入立绘失败", error)
        }.getOrNull()
    }

    /**
     * 已经拿到字节的图片（如 PNG 角色卡整张图）直接落盘。
     */
    suspend fun saveBytes(context: Context, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.isNotEmpty()) { "图片是空的" }
            if (bytes.size > MAX_BYTES) error("图片过大（超过 20MB），请换一张")
            val target = File(directory(context), "avatar_${UUID.randomUUID()}.img")
            FileOutputStream(target).use { it.write(bytes) }
            target.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "saveBytes: 保存立绘失败", error)
        }.getOrNull()
    }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        path?.let { runCatching { File(it).delete() } }
    }
}
