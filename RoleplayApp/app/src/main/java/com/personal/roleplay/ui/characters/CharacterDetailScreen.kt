package com.personal.roleplay.ui.characters

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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing
import com.personal.roleplay.ui.util.relativeTime
import java.io.File

@Composable
fun CharacterDetailScreen(
    characterId: String,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onEditCharacter: () -> Unit,
    viewModel: CharacterDetailViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var selectedPersonaId by remember { mutableStateOf<String?>(null) }
    var defaultApplied by remember { mutableStateOf(false) }

    LaunchedEffect(characterId) {
        viewModel.bind(characterId)
    }

    // 人设库是异步读出来的，到齐后再把默认人设预选上；手动选过就不再覆盖
    LaunchedEffect(uiState.personas, uiState.defaultPersonaId, defaultApplied) {
        if (!defaultApplied && uiState.personas.isNotEmpty()) {
            selectedPersonaId = uiState.defaultPersonaId
            defaultApplied = true
        }
    }

    val newSessionId by viewModel.newSessionId.collectAsStateWithLifecycle()
    LaunchedEffect(newSessionId) {
        newSessionId?.let {
            viewModel.consumeNewSession()
            onOpenSession(it)
        }
    }

    FeedbackEffect(uiState.feedback, snackbarHost, viewModel::consumeFeedback)

    val character = uiState.character

    Scaffold(
        topBar = {
            AppTopBar(
                title = character?.name ?: "角色",
                subtitle = character?.let { originLabel(it) },
                onBack = onBack,
                actions = {
                    IconButton(onClick = onEditCharacter) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑角色卡")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (character == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("角色不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                CharacterHeader(
                    name = character.name,
                    tagline = character.subtitle,
                    persona = character.persona,
                    tags = character.personalityTags,
                    avatarPath = character.avatarPath,
                    accentArgb = character.accentColorArgb
                )
            }

            item {
                SectionHeader(
                    text = "开一条新线",
                    hint = "同一个角色可以有好几条互不干扰的线：第二天、重开一次、if 线。"
                )
            }

            item {
                NewSessionCard(
                    personas = uiState.personas,
                    selectedPersonaId = selectedPersonaId,
                    onSelectPersona = { selectedPersonaId = it },
                    onCreate = { viewModel.createSession(selectedPersonaId) }
                )
            }

            item {
                SectionHeader(
                    text = "已有的线",
                    hint = "每条线独立开场、独立推进，TA 的记忆互不干扰。"
                )
            }

            if (uiState.sessions.isEmpty()) {
                item {
                    Text(
                        text = "还没有任何一条线，上面开一条试试。",
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.sessions, key = { it.session.id }) { summary ->
                    SessionRow(
                        summary = summary,
                        // 优先显示人设名字；人设被删了就退回建线时留下的正文快照
                        personaLabel = summary.session.userPersonaId
                            ?.let { personaId -> uiState.personas.firstOrNull { it.id == personaId }?.name }
                            ?: summary.session.userPersona?.takeIf { it.isNotBlank() },
                        onClick = { onOpenSession(summary.session.id) },
                        onRequestDelete = { pendingDelete = summary.session }
                    )
                }
            }
        }
    }

    pendingDelete?.let { session ->
        ConfirmDialog(
            title = "删除「${session.title}」？",
            message = "这条线的全部消息，以及 TA 在这条线里记住的所有事情都会一起删除，无法撤销。",
            confirmLabel = "删除这条线",
            dismissLabel = "取消",
            onConfirm = {
                viewModel.deleteSession(session.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

private fun originLabel(character: com.personal.roleplay.domain.model.Character): String =
    if (character.builtIn) "内置示例角色" else "自建角色"

@Composable
private fun CharacterHeader(
    name: String,
    tagline: String,
    persona: String,
    tags: List<String>,
    avatarPath: String?,
    accentArgb: Long
) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                if (avatarPath != null) {
                    AsyncImage(
                        model = File(avatarPath),
                        contentDescription = "${name}的立绘",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(accentArgb)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(1),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    if (tagline.isNotBlank()) {
                        Text(
                            text = tagline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    tags.take(4).forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) })
                    }
                }
            }

            if (persona.isNotBlank()) {
                Text(
                    text = if (expanded || persona.length <= 160) persona else persona.take(160) + "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (persona.length > 160) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开完整人设")
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSessionCard(
    personas: List<UserPersona>,
    selectedPersonaId: String?,
    onSelectPersona: (String?) -> Unit,
    onCreate: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text("我扮演谁", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "从人设库里挑一个，不用每次重打。选中的身份会以「对方是谁」告诉角色，TA 对你的认知围绕它展开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 「不用人设」永远在第一位：这条线就是你自己，不套任何身份
            PersonaOption(
                label = "不用人设",
                subtitle = "你就是你自己",
                selected = selectedPersonaId == null,
                onClick = { onSelectPersona(null) }
            )

            personas.forEach { persona ->
                PersonaOption(
                    label = persona.name,
                    subtitle = persona.preview,
                    selected = selectedPersonaId == persona.id,
                    onClick = { onSelectPersona(persona.id) }
                )
            }

            if (personas.isEmpty()) {
                Text(
                    text = "还没建过人设。到「设置 → 用户人设」里存几套，这里就能直接选；"
                        + "同一个人设可以跨角色、跨群聊复用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  开这条线")
            }
        }
    }
}

/** 人设单选项：名字一行，正文摘要一行 */
@Composable
private fun PersonaOption(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .sizeIn(minHeight = Spacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    summary: SessionSummary,
    personaLabel: String?,
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(summary.session.title, style = MaterialTheme.typography.titleMedium)
                personaLabel?.let { persona ->
                    Text(
                        text = "你扮演：${persona}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${summary.messageCount} 条消息 · ${relativeTime(summary.session.lastActiveAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRequestDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除「${summary.session.title}」")
            }
        }
    }
}
