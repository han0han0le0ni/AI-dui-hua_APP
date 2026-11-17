package com.personal.roleplay.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.EmptyState
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing
import com.personal.roleplay.ui.util.relativeTime

/**
 * 群聊列表。
 *
 * 群聊有自己的入口，不再混在角色下面——角色详情页现在只列单人线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(
    onOpenSession: (String) -> Unit,
    viewModel: GroupListViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val newSessionId by viewModel.newSessionId.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()
    var showPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GroupRow?>(null) }

    FeedbackEffect(uiState.feedback, snackbarHost, viewModel::consumeFeedback)

    // 群聊建好后立刻进对话页
    LaunchedEffect(newSessionId) {
        newSessionId?.let { sessionId ->
            onOpenSession(sessionId)
            viewModel.consumeNewSession()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("群聊") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showPicker = true }) {
                Icon(Icons.Default.Add, contentDescription = "开个群聊")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (uiState.rows.isEmpty() && !uiState.isLoading) {
            EmptyState(
                title = "还没有群聊",
                subtitle = "选两个以上角色开一个。你发一句，他们按顺序接话；" +
                    "一轮几个人开口可以随时调。",
                modifier = Modifier.padding(padding),
                actionLabel = "开个群聊",
                onAction = { showPicker = true }
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                top = Spacing.sm,
                bottom = Spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(uiState.rows, key = { it.session.id }) { row ->
                GroupCard(
                    row = row,
                    onClick = { onOpenSession(row.session.id) },
                    onRequestDelete = { pendingDelete = row }
                )
            }
        }
    }

    if (showPicker) {
        GroupPickerSheet(
            characters = uiState.candidates,
            onDismiss = { showPicker = false },
            onConfirm = { memberIds ->
                showPicker = false
                viewModel.createGroup(memberIds)
            }
        )
    }

    pendingDelete?.let { row ->
        ConfirmDialog(
            title = "删除「${row.session.title}」？",
            message = "这个群聊的 ${row.messageCount} 条消息，以及 TA 们由此记住的事都会被删除。" +
                "角色本人不受影响。这个操作无法撤销。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            onConfirm = {
                viewModel.deleteGroup(row.session.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun GroupCard(
    row: GroupRow,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .clickable(onClick = onClick)
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
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                MemberDots(members = row.members)
                Text(
                    text = row.session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildGroupMeta(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRequestDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除「${row.session.title}」")
            }
        }
    }
}

/**
 * 成员色点。
 *
 * 用首字 + 角色主题色而不是立绘：群聊列表要一眼扫出「哪几个人」，
 * 圆形色块比缩略图更好认，也省掉图片加载。
 */
@Composable
private fun MemberDots(members: List<Character>) {
    if (members.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        members.take(MAX_DOTS).forEach { member ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(member.accentColorArgb)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = member.name.take(1),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        }
        if (members.size > MAX_DOTS) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+${members.size - MAX_DOTS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 成员数按演员表算，不是按查得到的成员算。
 *
 * 某位角色被删掉后，TA 的历史台词还在群里，成员数不该跟着变小——
 * 用查得到的数量会让人以为群聊被改过。
 */
private fun buildGroupMeta(row: GroupRow): String {
    val castSize = row.session.cast.size
    val perRound = row.session.effectiveGroupTurnSize
    return "$castSize 位成员 · 每轮 $perRound 人开口 · ${row.messageCount} 条消息 · " +
        relativeTime(row.session.lastActiveAt)
}

private const val MAX_DOTS = 5
