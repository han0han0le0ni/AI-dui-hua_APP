package com.personal.roleplay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.StatRow
import com.personal.roleplay.ui.components.formatCost
import com.personal.roleplay.ui.components.formatCount
import com.personal.roleplay.ui.theme.Spacing
import com.personal.roleplay.ui.util.dateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onBack: () -> Unit,
    viewModel: UsageViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "用量",
                subtitle = "费用按内置价目表估算，只看量级，不作对账凭据",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    UsageRange.entries.forEach { range ->
                        FilterChip(
                            selected = uiState.range == range,
                            onClick = { viewModel.selectRange(range) },
                            label = { Text(range.label) }
                        )
                    }
                }
            }

            item {
                val totals = uiState.totals
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = formatCost(totals?.estimatedCost ?: 0.0),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        StatRow(
                            label = "调用次数",
                            value = "${totals?.callCount ?: 0} 次",
                            modifier = Modifier.padding(horizontal = 0.dp)
                        )
                        StatRow(
                            label = "输入 token",
                            value = formatCount(totals?.promptTokens ?: 0),
                            modifier = Modifier.padding(horizontal = 0.dp)
                        )
                        StatRow(
                            label = "输出 token",
                            value = formatCount(totals?.completionTokens ?: 0),
                            modifier = Modifier.padding(horizontal = 0.dp)
                        )
                    }
                }
            }

            if (uiState.byModel.isNotEmpty()) {
                item { SectionHeader(text = "按模型") }
                items(uiState.byModel, key = { it.modelId }) { usage ->
                    UsageRow(
                        title = usage.modelId,
                        detail = "${usage.callCount} 次 · 输入 ${formatCount(usage.promptTokens)} / 输出 ${formatCount(usage.completionTokens)}",
                        cost = usage.estimatedCost
                    )
                }
            }

            if (uiState.bySlot.isNotEmpty()) {
                item { SectionHeader(text = "按槽位", hint = "看这里能判断省钱策略生效了没有") }
                items(uiState.bySlot, key = { it.slot.wire }) { usage ->
                    UsageRow(
                        title = usage.slot.displayName,
                        detail = "${usage.callCount} 次 · 输入 ${formatCount(usage.promptTokens)} / 输出 ${formatCount(usage.completionTokens)}",
                        cost = usage.estimatedCost
                    )
                }
            }

            if (uiState.byCharacter.isNotEmpty()) {
                item { SectionHeader(text = "按角色") }
                items(uiState.byCharacter, key = { it.characterId.orEmpty() }) { usage ->
                    UsageRow(
                        title = usage.characterId?.let { uiState.characterNames[it] } ?: "（会话已删除）",
                        detail = "${usage.callCount} 次 · 输入 ${formatCount(usage.promptTokens)} / 输出 ${formatCount(usage.completionTokens)}",
                        cost = usage.estimatedCost
                    )
                }
            }

            if (uiState.recent.isNotEmpty()) {
                item { SectionHeader(text = "最近调用") }
                items(uiState.recent.take(30), key = { it.id }) { record ->
                    UsageRow(
                        title = "${record.modelId} · ${record.slot.displayName}",
                        detail = buildString {
                            append(dateTime(record.createdAt))
                            append(" · ")
                            append(if (record.success) "${record.latencyMs} ms" else "失败")
                            record.errorMessage?.let { append(" · $it") }
                        },
                        cost = record.estimatedCost
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageRow(
    title: String,
    detail: String,
    cost: Double
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(formatCost(cost), style = MaterialTheme.typography.titleSmall)
        }
    }
}
