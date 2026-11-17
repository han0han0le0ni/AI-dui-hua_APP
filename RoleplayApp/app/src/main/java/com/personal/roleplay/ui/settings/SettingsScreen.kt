package com.personal.roleplay.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.roleplay.LocalAppContainer
import com.personal.roleplay.data.seed.BuiltInCharacters
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenPersonas: () -> Unit,
    onOpenReplyFormat: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenDebug: () -> Unit
) {
    val snackbarHost = rememberSnackbarHost()
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl)
        ) {
            item { SectionHeader(text = "模型接入") }

            item {
                SettingsRow(
                    icon = { Icon(Icons.Default.Key, contentDescription = null) },
                    title = "接入配置",
                    subtitle = "添加服务商、分配槽位、探测能力",
                    onClick = onOpenProviders
                )
            }

            item {
                SettingsRow(
                    icon = { Icon(Icons.Default.Savings, contentDescription = null) },
                    title = "用量",
                    subtitle = "按模型、按槽位、按角色统计，防止 Key 被烧",
                    onClick = onOpenUsage
                )
            }

            item { SectionHeader(text = "对话") }

            item {
                SettingsRow(
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    title = "用户人设",
                    subtitle = "「我扮演谁」。存好几套，角色与群聊直接读取，不用每次重打",
                    onClick = onOpenPersonas
                )
            }

            item {
                SettingsRow(
                    icon = { Icon(Icons.Default.FormatQuote, contentDescription = null) },
                    title = "回复格式",
                    subtitle = "动作台词怎么标。默认猫箱风格：动作用括号，台词不括",
                    onClick = onOpenReplyFormat
                )
            }

            item { SectionHeader(text = "数据与调试") }

            item {
                SettingsRow(
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    title = "备份与恢复",
                    subtitle = "导出成 zip 存到本地；备份文件不含 Key",
                    onClick = onOpenBackup
                )
            }

            item {
                SettingsRow(
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    title = "请求调试",
                    subtitle = "看实际发出去的 prompt、各区块 token、返回原文",
                    onClick = onOpenDebug
                )
            }

            item {
                SettingsRow(
                    icon = { Icon(Icons.Default.Restore, contentDescription = null) },
                    title = "恢复内置示例角色",
                    subtitle = "把删掉的林深、苏叶找回来（已有的不会被覆盖）",
                    onClick = {
                        scope.launch {
                            val restored = BuiltInCharacters.restore(container.characterRepository)
                            snackbarHost.showSnackbar(
                                if (restored > 0) "已恢复 $restored 个内置角色" else "内置角色都还在"
                            )
                        }
                    }
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "所有数据都在这台手机上。角色、会话、记忆存在本地数据库，Key 存在系统密钥库，立绘存在私有目录。卸载 App 会全部清除，所以记得定期备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md)
                .sizeIn(minHeight = Spacing.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
