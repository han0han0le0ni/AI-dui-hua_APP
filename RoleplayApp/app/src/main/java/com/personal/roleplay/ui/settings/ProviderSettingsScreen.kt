package com.personal.roleplay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.provider.ProviderTemplate
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.EmptyState
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
    onEditProvider: (String) -> Unit,
    onCreateProvider: (String) -> Unit,
    viewModel: ProviderSettingsViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()
    var showTemplateSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProviderConfig?>(null) }

    FeedbackEffect(uiState.feedback, snackbarHost, viewModel::consumeFeedback)

    Scaffold(
        topBar = {
            AppTopBar(
                title = "接入配置",
                subtitle = "按槽位分配模型，主对话用强的、抽取用便宜的",
                onBack = onBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showTemplateSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加接入配置")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (uiState.configs.isEmpty()) {
            EmptyState(
                title = "还没有任何接入配置",
                subtitle = "挑一个服务商模板，粘上 Key 就能聊。也可以接 Ollama、LM Studio 跑本地模型。",
                modifier = Modifier.padding(padding),
                actionLabel = "挑一个模板",
                onAction = { showTemplateSheet = true }
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Slot.entries.forEach { slot ->
                val inSlot = uiState.configs.filter { it.slot == slot }
                if (inSlot.isEmpty()) return@forEach

                item {
                    SectionHeader(
                        text = slot.displayName,
                        hint = slotHint(slot)
                    )
                }

                items(inSlot, key = { it.id }) { config ->
                    ProviderRow(
                        config = config,
                        onEdit = { onEditProvider(config.id) },
                        onSetDefault = { viewModel.setAsDefault(config.id) },
                        onRequestDelete = { pendingDelete = config }
                    )
                }
            }
        }
    }

    if (showTemplateSheet) {
        TemplateSheet(
            templates = uiState.templates,
            onDismiss = { showTemplateSheet = false },
            onPick = { template ->
                showTemplateSheet = false
                onCreateProvider(template.id)
            }
        )
    }

    pendingDelete?.let { config ->
        ConfirmDialog(
            title = "删除「${config.name}」？",
            message = "配置与它对应的 Key 会一起从本机删除。角色如果不指定偏好配置，会自动改用别的可用配置。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            onConfirm = {
                viewModel.delete(config.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

private fun slotHint(slot: Slot): String = when (slot) {
    Slot.MAIN_DIALOGUE -> "决定对话质量的上限，别在这里省"
    Slot.MEMORY_EXTRACTION -> "每 8 轮跑一次，用便宜的小模型就够"
    Slot.SUMMARY -> "压缩情景摘要，也是小模型的活"
    Slot.PERSONA_CHECK -> "可选，人设漂移自检"
    Slot.VOICE -> "语音合成，M5 再用"
}

@Composable
private fun ProviderRow(
    config: ProviderConfig,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onRequestDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(config.name, style = MaterialTheme.typography.titleSmall)
                    if (config.isDefault) {
                        AssistChip(
                            onClick = {},
                            label = { Text("默认") },
                            modifier = Modifier.padding(start = Spacing.xs)
                        )
                    }
                }
                Text(
                    text = "${config.protocol.displayName} · ${config.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    // 只显示尾号，Key 本身不出现在界面上
                    text = if (config.hasKey) "Key 尾号 ${config.keyTail}" else "未设置 Key",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (config.hasKey) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            IconButton(onClick = onSetDefault) {
                Icon(
                    imageVector = if (config.isDefault) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "设为该槽位默认",
                    tint = if (config.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑「${config.name}」")
            }
            IconButton(onClick = onRequestDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除「${config.name}」")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(
    templates: List<ProviderTemplate>,
    onDismiss: () -> Unit,
    onPick: (ProviderTemplate) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = Spacing.lg)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("挑一个服务商", style = MaterialTheme.typography.titleMedium)
            }
            templates.forEach { template ->
                TextButton(
                    onClick = { onPick(template) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = Spacing.primaryTouchTarget)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(template.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = template.defaultModel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
