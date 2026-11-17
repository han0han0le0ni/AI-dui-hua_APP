package com.personal.roleplay.ui.worldbook

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.WorldBookEntry
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.EmptyState
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing

@Composable
fun WorldBookScreen(
    worldBookId: String,
    onBack: () -> Unit,
    viewModel: WorldBookViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()
    var showEditor by remember { mutableStateOf<WorldBookEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<WorldBookEntry?>(null) }

    LaunchedEffect(worldBookId) {
        viewModel.bind(worldBookId)
    }

    FeedbackEffect(uiState.feedback, snackbarHost, viewModel::consumeFeedback)

    Scaffold(
        topBar = {
            AppTopBar(
                title = uiState.book?.name ?: "世界书",
                subtitle = "命中的条目按优先级注入 prompt，超预算时先截断低优先级",
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showEditor = NEW_ENTRY }) {
                Icon(Icons.Default.Add, contentDescription = "新增条目")
            }
        }
    ) { padding ->
        if (uiState.entries.isEmpty()) {
            EmptyState(
                title = "这个世界还是空的",
                subtitle = "加几条设定：这个地方叫什么、有什么规矩、谁是敌谁是友。对话时按触发词自动注入。",
                modifier = Modifier.padding(padding),
                actionLabel = "新增条目",
                onAction = { showEditor = NEW_ENTRY }
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
            item { SectionHeader(text = "共 ${uiState.entries.size} 条") }

            items(uiState.entries, key = { it.id }) { entry ->
                EntryRow(
                    entry = entry,
                    isEditing = uiState.editingEntryId == entry.id,
                    onStartEdit = { viewModel.startEdit(entry.id) },
                    onCancelEdit = viewModel::cancelEdit,
                    onSubmitEdit = viewModel::updateEntry,
                    onToggleEnabled = { viewModel.toggleEnabled(entry) },
                    onRequestDelete = { pendingDelete = entry }
                )
            }
        }
    }

    showEditor?.let { entry ->
        EntryEditorDialog(
            entry = entry,
            onDismiss = { showEditor = null },
            onConfirm = { keys, content, priority, constant ->
                showEditor = null
                viewModel.addEntry(keys, content, priority, constant)
            }
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "删除这个条目？",
            message = entry.content,
            confirmLabel = "删除",
            dismissLabel = "取消",
            onConfirm = {
                viewModel.delete(entry.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** 新建条目的哨兵值：用空白 id 区分「新增」和「编辑已有」 */
private val NEW_ENTRY = WorldBookEntry(
    id = "",
    worldBookId = "",
    keys = emptyList(),
    content = "",
    priority = 50,
    enabled = true,
    constant = false,
    insertionOrder = 0
)

@Composable
private fun EntryRow(
    entry: WorldBookEntry,
    isEditing: Boolean,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (WorldBookEntry) -> Unit,
    onToggleEnabled: () -> Unit,
    onRequestDelete: () -> Unit
) {
    var keys by remember(entry.id, isEditing) { mutableStateOf(entry.keys.joinToString("、")) }
    var content by remember(entry.id, isEditing) { mutableStateOf(entry.content) }
    var priority by remember(entry.id, isEditing) { mutableStateOf(entry.priority.toString()) }

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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (entry.constant) {
                        AssistChip(onClick = {}, label = { Text("常驻") })
                    }
                    AssistChip(onClick = {}, label = { Text("优先级 ${entry.priority}") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (entry.enabled) "启用" else "停用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(checked = entry.enabled, onCheckedChange = { onToggleEnabled() })
                }
            }

            if (entry.keys.isNotEmpty() && !isEditing) {
                Text(
                    text = "触发词：${entry.keys.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (isEditing) {
                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("触发词（用 、或逗号分隔）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内容") },
                    minLines = 3
                )
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("优先级（数字越大越先注入）") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(
                        onClick = {
                            onSubmitEdit(
                                entry.copy(
                                    keys = keys.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                                    content = content.trim(),
                                    priority = priority.toIntOrNull() ?: entry.priority
                                )
                            )
                        },
                        enabled = content.isNotBlank()
                    ) {
                        Text("保存")
                    }
                    TextButton(onClick = onCancelEdit) {
                        Text("取消")
                    }
                }
            } else {
                Text(
                    text = entry.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onStartEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑条目")
                    }
                    IconButton(onClick = onRequestDelete) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "删除条目")
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryEditorDialog(
    entry: WorldBookEntry,
    onDismiss: () -> Unit,
    onConfirm: (List<String>, String, Int, Boolean) -> Unit
) {
    var keys by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("50") }
    var constant by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("触发词（用 、或逗号分隔）") },
                    supportingText = { Text("对话里出现这些词时，这条设定会被注入。") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内容") },
                    minLines = 4
                )
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("优先级") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = Spacing.minTouchTarget),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = constant, onCheckedChange = { constant = it })
                    Text("常驻（不看触发词，始终注入）")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        keys.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                        content.trim(),
                        priority.toIntOrNull() ?: 50,
                        constant
                    )
                },
                enabled = content.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
