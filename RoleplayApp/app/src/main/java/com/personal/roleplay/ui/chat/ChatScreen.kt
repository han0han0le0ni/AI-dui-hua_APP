package com.personal.roleplay.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.domain.context.MacroResolver
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.SpeakerLabels
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.RoleplayAppTheme
import com.personal.roleplay.ui.theme.Spacing

@Composable
fun ChatScreen(
    sessionId: String,
    focusSeq: Long,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenDebug: () -> Unit,
    viewModel: ChatViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 进入某个角色的对话时，全局配色切成 TA 的主题色
    RoleplayAppTheme(accentArgb = uiState.character?.accentColorArgb) {
        ChatContent(
            uiState = uiState,
            viewModel = viewModel,
            sessionId = sessionId,
            focusSeq = focusSeq,
            onBack = onBack,
            onOpenMemory = onOpenMemory,
            onOpenDebug = onOpenDebug
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    sessionId: String,
    focusSeq: Long,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenDebug: () -> Unit
) {
    val snackbarHost = rememberSnackbarHost()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var sheetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var narrationMode by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showPersonaPicker by remember { mutableStateOf(false) }
    var showGroupSettings by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, focusSeq) {
        viewModel.bind(sessionId, focusSeq)
    }

    FeedbackEffect(uiState.errorMessage, snackbarHost, viewModel::consumeError)
    FeedbackEffect(uiState.infoMessage, snackbarHost, viewModel::consumeInfo)

    // 新消息进来，或者正在流式输出，都把视口压到最新一条
    var lastCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(uiState.messages.size) {
        val current = uiState.messages.size
        if (current > lastCount && current > 0) {
            listState.scrollToItem(current - 1)
        }
        lastCount = current
    }
    // 流式输出时跟着尾巴走。加两道闸：正在手动滑动时不要抢，已经在末尾时不必再滚
    val tailLength = uiState.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(tailLength, uiState.isGenerating) {
        if (!uiState.isGenerating || uiState.messages.isEmpty()) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect

        val last = uiState.messages.lastIndex
        val info = listState.layoutInfo
        val atBottom = info.visibleItemsInfo.lastOrNull()?.index == last
        if (!atBottom) listState.scrollToItem(last)
    }

    // 溯源跳转：滚到那条消息上
    LaunchedEffect(uiState.highlightSeq) {
        val target = uiState.highlightSeq ?: return@LaunchedEffect
        val index = uiState.messages.indexOfFirst { it.seq == target }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = buildTitle(uiState),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildSubtitle(uiState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = Spacing.minTouchTarget, minHeight = Spacing.minTouchTarget)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 群聊才有「一轮几个人开口」这个开关，单人线不必出现
                    if (uiState.isGroup) {
                        IconButton(onClick = { showGroupSettings = true }) {
                            Icon(Icons.Default.People, contentDescription = "群聊设置")
                        }
                    }
                    IconButton(onClick = { showPersonaPicker = true }) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            // 已设扮演身份时用主题色点出来，扫一眼就知道现在是谁
                            tint = if (uiState.activePersonaId != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.ui.graphics.Color.Unspecified
                            },
                            contentDescription = uiState.activePersonaName
                                ?.let { "我扮演：$it" }
                                ?: "我扮演谁"
                        )
                    }
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Default.Memory, contentDescription = "TA 记住的事")
                    }
                    IconButton(onClick = onOpenDebug) {
                        Icon(Icons.Default.Terminal, contentDescription = "请求调试")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                uiState.undoBanner?.let { banner ->
                    UndoBar(
                        messageCount = banner.messageCount,
                        memoryCount = banner.memoryCount,
                        onUndo = viewModel::undo,
                        onDismiss = viewModel::dismissUndo
                    )
                }

                InputBar(
                    text = input,
                    onTextChange = { input = it },
                    narrationMode = narrationMode,
                    onToggleNarration = { narrationMode = !narrationMode },
                    isGenerating = uiState.isGenerating,
                    onSend = {
                        if (narrationMode) {
                            viewModel.insertNarration(input)
                        } else {
                            viewModel.send(input)
                        }
                        input = ""
                        narrationMode = false
                    },
                    onStop = viewModel::stopGeneration
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "说点什么开始吧",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.md)
                ) {
                    if (uiState.hasMoreMessages) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                FilledTonalButton(onClick = viewModel::loadMoreMessages) {
                                    Text("加载更早的对话")
                                }
                            }
                        }
                    }

                    items(uiState.messages, key = { it.id }) { message ->
                        // 按这条消息自己记录的发言人取名与配色；老数据没有发言人时回落到会话角色
                        val speaker = uiState.speakersById[message.speakerCharacterId]
                            ?: uiState.character
                        // 导入的卡里开场白可能带 {{char}} / {{user}}：库里存的仍是原文，
                        // 显示时现替换——角色改名后这段也跟着变，不会被写死成旧名字
                        val displayContent = remember(
                            message.content,
                            speaker?.name,
                            uiState.activePersonaName
                        ) {
                            resolveDisplayContent(
                                message = message,
                                speakerName = speaker?.name,
                                personaName = uiState.activePersonaName
                            )
                        }
                        MessageBubble(
                            message = message,
                            speaker = speaker,
                            displayContent = displayContent,
                            isStreaming = message.id == uiState.streamingMessageId,
                            highlighted = uiState.highlightSeq == message.seq,
                            onTap = { /* 气泡本身不需要点击动作，长按才是操作入口 */ },
                            onLongPress = { sheetMessage = message }
                        )
                    }
                }
            }
        }
    }

    // ---- 群聊：一轮几个人回话 ----
    if (showGroupSettings) {
        GroupSettingsDialog(
            memberCount = uiState.session?.cast?.size ?: uiState.participants.size,
            current = uiState.session?.groupTurnSize ?: 1,
            onDismiss = { showGroupSettings = false },
            onPick = { count ->
                showGroupSettings = false
                viewModel.setGroupTurnSize(count)
            }
        )
    }

    // ---- 切换本条线扮演谁 ----
    if (showPersonaPicker) {
        PersonaPickerDialog(
            personas = uiState.personas,
            activePersonaId = uiState.activePersonaId,
            onDismiss = { showPersonaPicker = false },
            onPick = { personaId ->
                showPersonaPicker = false
                viewModel.setPersona(personaId)
            }
        )
    }

    // ---- 长按消息后的操作面板 ----
    sheetMessage?.let { message ->
        MessageActionSheet(
            isFromCharacter = message.isFromCharacter,
            onDismiss = { sheetMessage = null },
            onCopy = {
                clipboard.setText(
                    AnnotatedString(
                        resolveDisplayContent(
                            message = message,
                            speakerName = uiState.speakersById[message.speakerCharacterId]?.name
                                ?: uiState.character?.name,
                            personaName = uiState.activePersonaName
                        )
                    )
                )
                sheetMessage = null
            },
            onContinueFrom = {
                sheetMessage = null
                viewModel.requestContinueFrom(message.id)
            },
            onEdit = {
                sheetMessage = null
                editingMessage = message
            },
            onRegenerate = {
                sheetMessage = null
                viewModel.requestRegenerate(message.id)
            },
            onDelete = {
                sheetMessage = null
                viewModel.requestDelete(message.id)
            }
        )
    }

    // ---- 编辑消息 ----
    editingMessage?.let { message ->
        EditMessageDialog(
            initialContent = message.content,
            onDismiss = { editingMessage = null },
            onConfirm = { content ->
                editingMessage = null
                viewModel.requestEdit(message.id, content)
            }
        )
    }

    // ---- 破坏性操作确认：会连带删记忆，必须先说清楚 ----
    uiState.pendingAction?.let { action ->
        ConfirmDialog(
            title = when (action.kind) {
                ActionKind.REGENERATE -> "重新生成这段话？"
                ActionKind.CONTINUE_FROM -> "从这里继续？"
                ActionKind.EDIT -> "编辑这条并重新生成？"
                ActionKind.DELETE_MESSAGE -> "删除这条消息？"
            },
            message = buildPendingMessage(action),
            confirmLabel = "继续",
            dismissLabel = "取消",
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissPending
        )
    }
}

/**
 * 角色消息的显示文本。
 *
 * 库里存的是原文（导入卡的开场白可能带 `{{char}}` / `{{user}}`），
 * 显示与复制时现替换：角色改名后旧开场白跟着变，不会被写死成旧名字。
 * 用户自己打的字不替换——他输入的字面量就是他想要的字面量。
 */
private fun resolveDisplayContent(
    message: ChatMessage,
    speakerName: String?,
    personaName: String?
): String {
    if (!message.isFromCharacter) return message.content
    return MacroResolver(
        charName = speakerName.orEmpty(),
        userName = personaName?.takeIf { it.isNotBlank() } ?: SpeakerLabels.USER
    ).resolve(message.content)
}

/**
 * 切换「我扮演谁」。
 *
 * 列出人设库里的全部人设，外加「不用人设」。选定后写回本条线：
 * 改人设正文会立刻反映到下一轮，但已经聊过的内容不受影响。
 */
@Composable
private fun PersonaPickerDialog(
    personas: List<UserPersona>,
    activePersonaId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我扮演谁") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (personas.isEmpty()) {
                    Text(
                        text = "还没建过人设。到「设置 → 用户人设」里存几套，"
                            + "之后每条线、每个群聊都能直接选，不用重打。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PersonaPickRow(
                    label = "不用人设",
                    subtitle = "你就是你自己",
                    selected = activePersonaId == null,
                    onClick = { onPick(null) }
                )
                personas.forEach { persona ->
                    PersonaPickRow(
                        label = persona.name,
                        subtitle = persona.preview,
                        selected = activePersonaId == persona.id,
                        onClick = { onPick(persona.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 群聊设置：一轮让几个角色开口。
 *
 * 上限就是成员数——同一轮里不该出现同一个人说两次。
 */
@Composable
private fun GroupSettingsDialog(
    memberCount: Int,
    current: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("群聊设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = "你发一句之后，本轮让几个角色依次回话。" +
                        "人越多越热闹，也越费 token。只影响往后每一轮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                (1..memberCount).forEach { count ->
                    PersonaPickRow(
                        label = when (count) {
                            1 -> "1 个（还是一人一轮）"
                            memberCount -> "$count 个（全员都开口）"
                            else -> "$count 个"
                        },
                        subtitle = null,
                        selected = current == count,
                        onClick = { onPick(count) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PersonaPickRow(
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

private fun buildSubtitle(state: ChatUiState): String {
    val nextSpeaker = state.speaker?.name
    return when {
        // 群聊最要紧的信息就是「下一句谁开口」，让它盖过会话标题
        state.isGenerating && nextSpeaker != null -> "${nextSpeaker} 正在回复…"
        state.isGenerating -> "正在回复…"
        state.isGroup && nextSpeaker != null -> "下一句：${nextSpeaker} · ${state.session?.title.orEmpty()}"
        else -> state.session?.title.orEmpty()
    }
}

/** 群聊用「群聊 · 成员」，单人线直接用角色名 */
private fun buildTitle(state: ChatUiState): String = when {
    state.isGroup -> "群聊 · " + state.participants.joinToString("、") { it.name }
    else -> state.character?.name ?: "对话"
}

private fun buildPendingMessage(action: PendingAction): String = buildString {
    when (action.kind) {
        ActionKind.REGENERATE -> appendLine("这句话和它之后的所有对话都会被删除，然后重新生成。")
        ActionKind.CONTINUE_FROM -> appendLine("这句话之后的所有对话都会被删除，你会回到这个时间点重新往下聊。")
        ActionKind.EDIT -> appendLine("这句话会被你的新内容替换，它之后的所有对话会被删除，然后重新生成。")
        ActionKind.DELETE_MESSAGE -> appendLine("这条消息会被删除。")
    }
    appendLine()
    append("共影响 ${action.messageCount} 条消息。")
    if (action.losingMemories.isNotEmpty()) {
        appendLine()
        appendLine()
        append("同时会抹去 TA 由此记住的 ${action.losingMemories.size} 件事：")
        action.losingMemories.take(5).forEach { memory ->
            appendLine()
            append("· ${memory.content}")
        }
    } else if (action.weakenedMemoryCount > 0) {
        appendLine()
        appendLine()
        append("另有 ${action.weakenedMemoryCount} 条记忆会少掉几个来源，不会消失。")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    isFromCharacter: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onContinueFrom: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = Spacing.lg)) {
            SheetRow(icon = { Icon(Icons.Default.ContentCopy, null) }, text = "复制", onClick = onCopy)

            // 「从这里继续」对角色消息同样有效：回到这一步，换一种方式往下聊
            SheetRow(
                icon = { Icon(Icons.Default.Refresh, null) },
                text = if (isFromCharacter) "换一种回复（重新生成）" else "从这里重新说",
                onClick = onRegenerate
            )

            SheetRow(
                icon = { Icon(Icons.Default.Undo, null) },
                text = "回到这里（删除其后对话）",
                onClick = onContinueFrom
            )

            // 编辑角色消息 = 你主动定义事实，是修正人设跑偏最直接的手段
            SheetRow(
                icon = { Icon(Icons.Default.Edit, null) },
                text = if (isFromCharacter) "改成我希望 TA 说的那句" else "修改我说的这句话",
                onClick = onEdit
            )

            SheetRow(
                icon = { Icon(Icons.Default.DeleteOutline, null) },
                text = "删除这条",
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = Spacing.primaryTouchTarget)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Text(
                text = "  $text",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = Spacing.md)
            )
        }
    }
}

@Composable
private fun UndoBar(
    messageCount: Int,
    memoryCount: Int,
    onUndo: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "已删除 $messageCount 条消息${if (memoryCount > 0) "、$memoryCount 条记忆" else ""}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUndo) {
                Icon(Icons.Default.Undo, contentDescription = "撤销删除")
                Text("  撤销")
            }
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    }
}

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    narrationMode: Boolean,
    onToggleNarration: () -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(
                onClick = onToggleNarration,
                modifier = Modifier.sizeIn(minWidth = Spacing.minTouchTarget, minHeight = Spacing.minTouchTarget)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoStories,
                    contentDescription = if (narrationMode) "切换回对话输入" else "插入一段旁白",
                    tint = if (narrationMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.xs),
                placeholder = {
                    Text(if (narrationMode) "写一段旁白…" else "对 TA 说…")
                },
                maxLines = 5
            )

            if (isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.sizeIn(minWidth = Spacing.minTouchTarget, minHeight = Spacing.minTouchTarget)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止生成")
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.sizeIn(minWidth = Spacing.minTouchTarget, minHeight = Spacing.minTouchTarget)
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = if (narrationMode) "插入旁白" else "发送"
                    )
                }
            }
        }
    }
}

@Composable
private fun EditMessageDialog(
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var content by remember(initialContent) { mutableStateOf(initialContent) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑这条消息") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(content) },
                enabled = content.isNotBlank()
            ) {
                Text("改好，重新生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
