package com.personal.roleplay.ui.memory

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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MemoryWithSources
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.EmptyState
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryPanelScreen(
    sessionId: String,
    onBack: () -> Unit,
    onJumpToMessage: (Long) -> Unit,
    viewModel: MemoryPanelViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()

    var layerFilter by remember { mutableStateOf<MemoryLayer?>(null) }
    var expandedMemoryId by remember { mutableStateOf<String?>(null) }
    var pendingForget by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        viewModel.bind(sessionId)
    }

    val focusSeq by viewModel.focusSeqEvent.collectAsStateWithLifecycle()
    LaunchedEffect(focusSeq) {
        focusSeq?.let { seq ->
            viewModel.consumeFocusSeq()
            onJumpToMessage(seq)
        }
    }

    FeedbackEffect(uiState.feedback, snackbarHost, viewModel::consumeFeedback)

    val visible = uiState.memories
        .filter { layerFilter == null || it.memory.layer == layerFilter }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "TA 记住的事",
                subtitle = "只属于这一条线，换一条线就是另一套记忆",
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::recompute) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "重新整理记忆")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "手动添加一条记忆")
            }
        }
    ) { padding ->
        if (uiState.memories.isEmpty()) {
            EmptyState(
                title = "TA 还没有记住任何事",
                subtitle = "聊满 8 轮会自动抽取一次。也可以现在手动加一条，或者让它对现有对话重新整理一遍。",
                modifier = Modifier.padding(padding),
                actionLabel = "重新整理",
                onAction = viewModel::recompute
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SectionHeader(
                    text = "共 ${uiState.memories.size} 条",
                    hint = "点开任意一条，能看到是哪些话让它被记住的。"
                )
            }

            item {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    FilterChip(
                        selected = layerFilter == null,
                        onClick = { layerFilter = null },
                        label = { Text("全部") }
                    )
                    MemoryLayer.entries.forEach { layer ->
                        FilterChip(
                            selected = layerFilter == layer,
                            onClick = { layerFilter = if (layerFilter == layer) null else layer },
                            label = { Text(layer.displayName) }
                        )
                    }
                }
            }

            if (uiState.isRecomputing) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (visible.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "这一层还没有记忆",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(visible, key = { it.memory.id }) { entry ->
                MemoryRow(
                    entry = entry,
                    expanded = expandedMemoryId == entry.memory.id,
                    isEditing = uiState.editingMemoryId == entry.memory.id,
                    onToggleExpand = {
                        expandedMemoryId = if (expandedMemoryId == entry.memory.id) null else entry.memory.id
                    },
                    onStartEdit = { viewModel.startEdit(entry.memory.id) },
                    onCancelEdit = viewModel::cancelEdit,
                    onSubmitEdit = { content -> viewModel.updateContent(entry.memory.id, content) },
                    onTogglePin = { viewModel.togglePin(entry.memory.id) },
                    onRequestForget = { pendingForget = entry.memory.id },
                    onJumpToMessageId = { messageId -> viewModel.requestJumpToMessage(messageId) }
                )
            }
        }
    }

    pendingForget?.let { memoryId ->
        ConfirmDialog(
            title = "让 TA 忘掉这件事？",
            message = "这条记忆和它的全部来源都会被删除。如果它是从对话里抽出来的，下次抽取时可能又会被记起来。",
            confirmLabel = "忘记",
            dismissLabel = "取消",
            onConfirm = {
                viewModel.forget(memoryId)
                pendingForget = null
            },
            onDismiss = { pendingForget = null }
        )
    }

    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { layer, content ->
                showAddDialog = false
                viewModel.addMemory(layer, content)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryRow(
    entry: MemoryWithSources,
    expanded: Boolean,
    isEditing: Boolean,
    onToggleExpand: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (String) -> Unit,
    onTogglePin: () -> Unit,
    onRequestForget: () -> Unit,
    /** 点某个来源的「去看」：传进来的是 messageId，由外层换成 seq 再跳 */
    onJumpToMessageId: (String) -> Unit
) {
    val memory = entry.memory
    var draft by remember(memory.id, isEditing) { mutableStateOf(memory.content) }

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
                    AssistChip(
                        onClick = {},
                        label = { Text(memory.layer.displayName) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                    if (memory.pinned) {
                        AssistChip(
                            onClick = {},
                            label = { Text("置顶") }
                        )
                    }
                    if (memory.status != com.personal.roleplay.domain.model.MemoryStatus.ACTIVE) {
                        AssistChip(
                            onClick = {},
                            label = { Text(memory.status.displayName) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        )
                    }
                }
                Text(
                    text = "置信度 ${(memory.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isEditing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(
                        onClick = { onSubmitEdit(draft) },
                        enabled = draft.isNotBlank()
                    ) {
                        Text("保存")
                    }
                    TextButton(onClick = onCancelEdit) {
                        Text("取消")
                    }
                }
            } else {
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // 溯源：这条记忆由哪几句话支撑
            TextButton(
                onClick = onToggleExpand,
                modifier = Modifier.sizeIn(minHeight = Spacing.minTouchTarget)
            ) {
                Text(if (expanded) "收起来源" else "来自 ${entry.sources.size} 条对话")
            }

            if (expanded) {
                if (entry.sources.isEmpty()) {
                    Text(
                        text = "这条是你手动添加的，没有来源对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    entry.sources.forEach { source ->
                        SourceRow(
                            excerpt = source.excerpt,
                            onJump = { onJumpToMessageId(source.messageId) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = if (memory.pinned) "取消置顶" else "置顶这条记忆",
                        tint = if (memory.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onStartEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑这条记忆")
                }
                IconButton(onClick = onRequestForget) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "让 TA 忘掉这件事")
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    excerpt: String,
    onJump: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .sizeIn(minHeight = Spacing.minTouchTarget)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "「$excerpt」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onJump) {
            Text("去看")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (MemoryLayer, String) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var layer by remember { mutableStateOf(MemoryLayer.L2_SEMANTIC) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加一条记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    MemoryLayer.entries.forEach { candidate ->
                        FilterChip(
                            selected = layer == candidate,
                            onClick = { layer = candidate },
                            label = { Text(candidate.displayName) }
                        )
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内容") },
                    minLines = 3
                )
                Text(
                    text = "手动添加的记忆没有来源，不会因删某条消息而消失；但删掉这条线时仍会随之删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(layer, content) },
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
