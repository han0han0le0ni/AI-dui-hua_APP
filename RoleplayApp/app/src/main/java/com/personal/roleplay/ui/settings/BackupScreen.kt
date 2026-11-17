package com.personal.roleplay.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.personal.roleplay.LocalAppContainer
import com.personal.roleplay.data.backup.ImportMode
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = rememberSnackbarHost()

    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.MERGE) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val summary = container.backupManager.export(uri).getOrNull()
            if (summary != null) {
                snackbarHost.showSnackbar(
                    "已导出：${summary.characterCount} 个角色、${summary.sessionCount} 个会话、" +
                        "${summary.messageCount} 条消息、${summary.memoryCount} 条记忆"
                )
            } else {
                Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        pendingImport = uri
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "备份与恢复",
                subtitle = "数据只在这一台手机上，卸载就没了",
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item { SectionHeader(text = "导出") }

            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = "导出成一个 zip：角色卡、世界书、会话、消息、记忆、立绘图片。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "备份文件里不含 API Key——导入后需要重新录入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = { exportLauncher.launch("roleplay-backup.zip") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Text("  导出到本地")
                        }
                    }
                }
            }

            item { SectionHeader(text = "导入") }

            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = "合并：保留现有数据，把备份里的内容追加进来。\n" +
                                "覆盖：先清空现有全部数据，再导入备份。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(
                            onClick = {
                                importMode = ImportMode.MERGE
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Text("  选择备份文件（合并导入）")
                        }
                        OutlinedButton(
                            onClick = {
                                importMode = ImportMode.OVERWRITE
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Text("  选择备份文件（覆盖导入）")
                        }
                    }
                }
            }

            item {
                Text(
                    text = "注意：备份不含 Key，导入后要在「接入配置」里重新粘贴。",
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = if (importMode == ImportMode.OVERWRITE) "覆盖导入？" else "合并导入？",
            message = if (importMode == ImportMode.OVERWRITE) {
                "现有全部角色、会话、消息、记忆都会被清空，然后导入备份内容。这个操作无法撤销。"
            } else {
                "备份里的角色、会话、消息、记忆会追加到现有数据上。"
            },
            confirmLabel = "开始导入",
            dismissLabel = "取消",
            onConfirm = {
                pendingImport = null
                scope.launch {
                    val summary = container.backupManager.import(uri, importMode).getOrNull()
                    if (summary != null) {
                        snackbarHost.showSnackbar(
                            "已导入：${summary.characterCount} 个角色、${summary.sessionCount} 个会话、" +
                                "${summary.messageCount} 条消息、${summary.memoryCount} 条记忆"
                        )
                    } else {
                        Toast.makeText(context, "导入失败，请确认选择的是本 App 导出的 zip", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { pendingImport = null }
        )
    }
}
