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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.ReplyFormatPreset
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.theme.Spacing

/**
 * 回复格式：角色怎么标注动作与台词。
 *
 * 全局生效，所有角色、所有群聊、所有线共用一份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyFormatScreen(
    onBack: () -> Unit,
    viewModel: ReplyFormatViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "回复格式",
                subtitle = "动作与台词怎么标，全局生效",
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            item {
                SectionHeader(
                    text = "预设",
                    hint = "改完立刻生效，下一条回复就按新格式来。已经聊过的内容不会被改写。"
                )
            }

            items(ReplyFormatPreset.entries.size) { index ->
                val preset = ReplyFormatPreset.entries[index]
                PresetRow(
                    preset = preset,
                    selected = uiState.format.preset == preset,
                    onClick = { viewModel.selectPreset(preset) }
                )
            }

            if (uiState.format.preset == ReplyFormatPreset.CUSTOM) {
                item {
                    SectionHeader(
                        text = "自定义规则",
                        hint = "这段原样追加到「回复要求」的最后，一行一条更清楚。"
                    )
                    OutlinedTextField(
                        value = uiState.format.customText,
                        onValueChange = viewModel::updateCustomText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md),
                        minLines = 5,
                        placeholder = {
                            Text("- 台词直接写\n- 动作用圆括号包裹")
                        }
                    )
                }
            }

            item {
                SectionHeader(
                    text = "注入预览",
                    hint = "下面这段就是每次对话会发出去的格式规则。"
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = uiState.preview,
                        modifier = Modifier.padding(Spacing.md),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: ReplyFormatPreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .sizeIn(minHeight = Spacing.minTouchTarget)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(preset.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 选「自定义」时把它自己的说明也亮出来，免得看不到实例
            if (selected && preset == ReplyFormatPreset.MAOXIANG) {
                Text(
                    text = "例：（把外套披在你肩上）\n——今天就先这样吧。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
