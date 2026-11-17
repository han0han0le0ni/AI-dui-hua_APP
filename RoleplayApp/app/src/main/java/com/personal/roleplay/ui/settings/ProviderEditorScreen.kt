package com.personal.roleplay.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.Protocol
import com.personal.roleplay.domain.model.ProviderParams
import com.personal.roleplay.domain.model.ReasoningLevel
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorScreen(
    providerId: String?,
    onBack: () -> Unit,
    viewModel: ProviderEditorViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()

    LaunchedEffect(providerId) {
        viewModel.bind(providerId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) snackbarHost.showSnackbar("已保存")
    }

    val config = uiState.config
    var showParameterSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (uiState.isNew) "添加接入配置" else "编辑接入配置",
                subtitle = config?.name,
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (config == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item { SectionHeader(text = "换服务商", hint = "选一个模板会把协议和地址一起预填好。") }

            item {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    com.personal.roleplay.domain.provider.ProviderTemplates.all.forEach { template ->
                        FilterChip(
                            selected = config.protocol == template.protocol && config.baseUrl == template.baseUrl,
                            onClick = { viewModel.applyTemplate(template) },
                            label = { Text(template.displayName) }
                        )
                    }
                }
            }

            item { SectionHeader(text = "基本信息") }

            item {
                OutlinedTextField(
                    value = config.name,
                    onValueChange = { value -> viewModel.update { it.copy(name = value) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                    label = { Text("别名") },
                    singleLine = true
                )
            }

            item {
                EnumDropdown(
                    label = "协议",
                    options = Protocol.entries.map { it to it.displayName },
                    selected = config.protocol,
                    onSelect = { value -> viewModel.update { it.copy(protocol = value) } }
                )
            }

            item {
                OutlinedTextField(
                    value = config.baseUrl,
                    onValueChange = { value -> viewModel.update { it.copy(baseUrl = value) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                    label = { Text("接口地址") },
                    supportingText = { Text("不填 /v1 也没关系，请求时会自动补全。") },
                    singleLine = true
                )
            }

            item {
                if (uiState.models.isEmpty()) {
                    OutlinedTextField(
                        value = config.modelId,
                        onValueChange = { value -> viewModel.update { it.copy(modelId = value) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                        label = { Text("模型标识") },
                        supportingText = { Text("点下面的「测试连接」可以拉取可用模型列表。") },
                        singleLine = true
                    )
                } else {
                    ModelDropdown(
                        models = uiState.models,
                        selected = config.modelId,
                        onSelect = { model -> viewModel.update { it.copy(modelId = model) } }
                    )
                }
            }

            item {
                EnumDropdown(
                    label = "槽位",
                    options = Slot.entries.map { it to it.displayName },
                    selected = config.slot,
                    onSelect = { value -> viewModel.update { it.copy(slot = value) } }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("设为该槽位默认", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "没给角色指定偏好配置时，就用这一个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.isDefault,
                        onCheckedChange = { checked -> viewModel.update { it.copy(isDefault = checked) } }
                    )
                }
            }

            item {
                SectionHeader(
                    text = "API Key",
                    hint = "存在系统密钥库里，不进数据库、不进备份文件、不进日志。界面上只回显尾号。"
                )
            }

            item {
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { value -> viewModel.update { it.copy(apiKey = value) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                    label = { Text(if (config.hasKey) "Key（已保存，留空表示不改）" else "Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }

            item {
                SectionHeader(
                    text = "采样与推理参数",
                    hint = "温度、Top P、输出上限、惩罚项，以及思考强度。点进去单独调。"
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                        .clickable { showParameterSheet = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("模型参数", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = paramsSummary(config.params),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            item { SectionHeader(text = "连接测试") }

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
                        Button(
                            onClick = viewModel::probe,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isProbing
                        ) {
                            Icon(Icons.Default.CloudDone, contentDescription = null)
                            Text("  测试连接并拉取模型列表")
                        }

                        if (uiState.isProbing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        uiState.probeMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.probeSucceeded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }

                        if (config.capabilities.probedAt > 0L) {
                            Text(
                                text = buildString {
                                    append("上下文窗口 ${config.capabilities.contextWindow} token")
                                    if (config.capabilities.jsonMode) append(" · 支持 JSON 模式")
                                    if (config.capabilities.streaming) append(" · 支持流式")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.md)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("  保存")
                }
            }
        }
    }

    if (showParameterSheet && config != null) {
        ParameterSheet(
            params = config.params,
            protocol = config.protocol,
            onUpdate = { transform ->
                viewModel.update { it.copy(params = transform(it.params)) }
            },
            onDismiss = { showParameterSheet = false }
        )
    }
}

/** 不点开也能看到关键数字，省得忘了自己调过什么 */
private fun paramsSummary(params: ProviderParams): String {
    val base = "温度 %.2f · Top P %.2f · 上限 %d".format(
        params.temperature,
        params.topP,
        params.maxTokens
    )
    return if (params.reasoning == ReasoningLevel.OFF) {
        base
    } else {
        "$base · 思考${params.reasoning.displayName}"
    }
}

/**
 * 参数调节面板。
 *
 * 只展示该协议真正支持的字段：Anthropic 与 Gemini 没有惩罚项，硬摆出来只会误导。
 * 思考强度按协议映射成各自的字段形状，用户只需要选一个档位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParameterSheet(
    params: ProviderParams,
    protocol: Protocol,
    onUpdate: ((ProviderParams) -> ProviderParams) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.lg)
        ) {
            SectionHeader(
                text = "模型参数",
                hint = "改完立刻生效，不用重新测试连接。"
            )

            ParameterSlider(
                label = "温度",
                hint = "越高越发散，越低越稳。角色扮演一般 0.7～1.0。",
                value = params.temperature,
                range = 0f..2f,
                onValueChange = { next -> onUpdate { it.copy(temperature = next) } }
            )

            ParameterSlider(
                label = "Top P",
                hint = "只在概率最高的前 P 比例里取样。一般和温度二选一调，别同时动。",
                value = params.topP,
                range = 0f..1f,
                onValueChange = { next -> onUpdate { it.copy(topP = next) } }
            )

            OutlinedTextField(
                value = params.maxTokens.toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.takeIf { it > 0 }?.let { parsed ->
                        onUpdate { it.copy(maxTokens = parsed) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                label = { Text("单次最大输出 token") },
                supportingText = { Text("只限制这一次回复的长度，不是上下文窗口。") },
                singleLine = true
            )

            if (protocol.supportsPenalties) {
                ParameterSlider(
                    label = "存在惩罚",
                    hint = "正值更愿意引入新话题，负值更容易反复提同一件事。",
                    value = params.presencePenalty,
                    range = -2f..2f,
                    onValueChange = { next -> onUpdate { it.copy(presencePenalty = next) } }
                )

                ParameterSlider(
                    label = "频率惩罚",
                    hint = "正值抑制重复用词。调太高角色会说话不自然。",
                    value = params.frequencyPenalty,
                    range = -2f..2f,
                    onValueChange = { next -> onUpdate { it.copy(frequencyPenalty = next) } }
                )
            } else {
                Text(
                    text = "${protocol.displayName} 的接口里没有惩罚项，所以不做展示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text("思考强度", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = reasoningHint(protocol, params.reasoning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ReasoningLevel.entries.forEach { level ->
                        FilterChip(
                            selected = params.reasoning == level,
                            onClick = { onUpdate { it.copy(reasoning = level) } },
                            label = { Text(level.displayName) }
                        )
                    }
                }
            }
        }
    }
}

private fun reasoningHint(protocol: Protocol, level: ReasoningLevel): String = when {
    level == ReasoningLevel.OFF ->
        "关闭时不发送任何推理字段，用服务商自己的默认行为。"

    protocol == Protocol.ANTHROPIC ->
        "Anthropic 强制要求：开启思考后温度必须是 1，输出上限也会自动抬到预算之上。"

    protocol == Protocol.GEMINI ->
        "按档位换算成 thinkingConfig.thinkingBudget 发送。"

    else ->
        "以 reasoning_effort 发送。只有推理模型认这个字段，普通模型收到可能报错。"
}

@Composable
private fun ParameterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    hint: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodyMedium)
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            label = { Text("模型标识") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        expanded = false
                        onSelect(model)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second.orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}
