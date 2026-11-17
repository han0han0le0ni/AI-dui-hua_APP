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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.EmptyState
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSettingsScreen(
    onBack: () -> Unit,
    onEditPersona: (String) -> Unit,
    onCreatePersona: () -> Unit,
    viewModel: PersonaListViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()
    var pendingDelete by remember { mutableStateOf<UserPersona?>(null) }

    FeedbackEffect(uiState.feedback, snackbarHost, viewModel::consumeFeedback)

    Scaffold(
        topBar = {
            AppTopBar(
                title = "用户人设",
                subtitle = "「我扮演谁」，角色单线与群聊共用",
                onBack = onBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreatePersona) {
                Icon(Icons.Default.Add, contentDescription = "新增人设")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (uiState.isEmpty) {
            EmptyState(
                title = "还没有人设",
                subtitle = "建几个常用的身份，比如「仙门弟子」「隔壁班同学」。"
                    + "之后开新线或在群聊里直接选，不用每次重打一遍。",
                modifier = Modifier.padding(padding),
                actionLabel = "建一个",
                onAction = onCreatePersona
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
                    text = "全部人设",
                    hint = "标了「默认」的那个，开新线和群聊时会自动套用。改动会同步到所有引用它的线。"
                )
            }

            items(uiState.personas, key = { it.id }) { persona ->
                PersonaRow(
                    persona = persona,
                    onEdit = { onEditPersona(persona.id) },
                    onSetDefault = { viewModel.setAsDefault(persona.id) },
                    onRequestDelete = { pendingDelete = persona }
                )
            }
        }
    }

    pendingDelete?.let { persona ->
        ConfirmDialog(
            title = "删除「${persona.name}」？",
            message = "删掉后不能再选它。已经用过它的话本不受影响——" +
                "对话会退回建线时写下的那份人设快照，扮演身份不会丢。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            onConfirm = {
                viewModel.delete(persona.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun PersonaRow(
    persona: UserPersona,
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(persona.name, style = MaterialTheme.typography.titleSmall)
                    if (persona.isDefault) {
                        AssistChip(
                            onClick = {},
                            label = { Text("默认") },
                            modifier = Modifier.padding(start = Spacing.xs)
                        )
                    }
                }
                Text(
                    text = persona.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onSetDefault) {
                Icon(
                    imageVector = if (persona.isDefault) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "把「${persona.name}」设为新会话默认",
                    tint = if (persona.isDefault) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑「${persona.name}」")
            }
            IconButton(onClick = onRequestDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除「${persona.name}」")
            }
        }
    }
}
