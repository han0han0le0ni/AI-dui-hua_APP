package com.personal.roleplay.ui.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.PromptBlockRecord
import com.personal.roleplay.domain.model.RequestLog
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.EmptyState
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.formatCount
import com.personal.roleplay.ui.theme.Spacing
import com.personal.roleplay.ui.util.dateTime

/**
 * 请求调试页。
 *
 * M1 就要有它：没有这里，人设为什么跑偏、预算哪里超了，只能靠猜。
 * 分区块折叠的完整 prompt、各区块 token 占用、模型返回原文、耗时、错误信息，都能复制。
 */
@Composable
fun RequestDebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            AppTopBar(
                title = "请求调试",
                subtitle = "最近 30 次请求，Key 不会出现在这里",
                onBack = onBack
            )
        }
    ) { padding ->
        if (uiState.logs.isEmpty()) {
            EmptyState(
                title = "还没有任何请求记录",
                subtitle = "发一条消息，回来就能看到实际发出去的完整 prompt。",
                modifier = Modifier.padding(padding)
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
            item {
                SectionHeader(
                    text = "共 ${uiState.logs.size} 条",
                    hint = "点开任意一条看分区块 prompt；再点区块名展开内容。"
                )
            }

            items(uiState.logs, key = { it.id }) { log ->
                LogCard(
                    log = log,
                    expanded = uiState.expandedLogId == log.id,
                    expandedBlockKeys = uiState.expandedBlockKeys,
                    onToggleLog = { viewModel.toggleLog(log.id) },
                    onToggleBlock = viewModel::toggleBlock,
                    onCopy = { text -> clipboard.setText(AnnotatedString(text)) }
                )
            }
        }
    }
}

@Composable
private fun LogCard(
    log: RequestLog,
    expanded: Boolean,
    expandedBlockKeys: Set<String>,
    onToggleLog: () -> Unit,
    onToggleBlock: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleLog),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${log.slot.displayName} · ${log.modelId}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = buildString {
                            append(dateTime(log.createdAt))
                            append(" · ")
                            append(if (log.success) "${log.latencyMs} ms" else "失败")
                            append(" · 输入 ")
                            append(formatCount(log.promptTokens))
                            append(" / 输出 ")
                            append(formatCount(log.completionTokens))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开"
                )
            }

            if (!log.success) {
                AssistChip(
                    onClick = {},
                    label = { Text(log.errorMessage ?: "请求失败") },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                )
            }

            if (expanded) {
                Text(
                    text = log.providerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    IconButton(onClick = { onCopy(log.fullPrompt) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制完整 prompt")
                    }
                    Text(
                        text = "完整 prompt（${formatCount(log.promptTokens)} token）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }

                log.blocks.forEach { block ->
                    BlockRow(
                        block = block,
                        expanded = block.key in expandedBlockKeys,
                        onToggle = { onToggleBlock(block.key) },
                        onCopy = onCopy
                    )
                }

                log.rawResponse?.let { raw ->
                    SectionHeader(text = "模型返回原文", modifier = Modifier.padding(horizontal = 0.dp))
                    MonospaceBox(text = raw.take(4_000))
                }

                log.errorMessage?.let { error ->
                    SectionHeader(text = "错误信息", modifier = Modifier.padding(horizontal = 0.dp))
                    MonospaceBox(text = error)
                }
            }
        }
    }
}

@Composable
private fun BlockRow(
    block: PromptBlockRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(block.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = buildString {
                            append(formatCount(block.estimatedTokens))
                            append(" token")
                            if (block.truncated) append(" · 已截断")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (block.truncated) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(onClick = { onCopy(block.content) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制「${block.label}」")
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开"
                )
            }

            if (expanded) {
                MonospaceBox(text = block.content)
            }
        }
    }
}

@Composable
private fun MonospaceBox(text: String) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(Spacing.sm),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
}
