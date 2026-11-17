package com.personal.roleplay.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.ui.theme.Spacing

/**
 * 消息气泡。
 *
 * narration 走居中斜体，视觉上和用户台词、角色台词明显区分开——
 * 它是你手动塞进去的舞台说明，不是对话的一部分。
 *
 * 群聊里每句台词上方都带「色点 + 名字」，由调用方按消息的 speakerCharacterId 解析出
 * 对应角色传进来。这是「不串味」在界面上的最后一环：数据没混，界面也必须看得出来没混。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    /** 这条消息的发言角色。旁白与用户消息传 null */
    speaker: Character?,
    /** 已经过宏替换的正文。库里存的是原文，显示必须用替换后的 */
    displayContent: String,
    isStreaming: Boolean,
    highlighted: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val isNarration = message.role == MessageRole.NARRATION

    val speakerColor: Color = if (isNarration) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        speaker?.accentColorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    }

    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            highlighted -> MaterialTheme.colorScheme.tertiaryContainer
            isNarration -> MaterialTheme.colorScheme.surface
            isUser -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "气泡背景"
    )

    val contentColor = when {
        highlighted -> MaterialTheme.colorScheme.onTertiaryContainer
        isNarration -> MaterialTheme.colorScheme.onSurfaceVariant
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (!isUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = Spacing.xs, bottom = 2.dp)
                ) {
                    if (!isNarration) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(speakerColor)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                    }
                    Text(
                        text = if (isNarration) "旁白" else speaker?.name ?: "角色",
                        style = MaterialTheme.typography.labelSmall,
                        color = speakerColor
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(containerColor)
                    .then(
                        if (isNarration) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = shape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .combinedClickable(
                        onClick = onTap,
                        onLongClick = onLongPress
                    )
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                // 解析结果按内容缓存：流式输出时每个 token 内容都变，
                // 不缓存的话每次重组都要把整段文本重新扫一遍
                val body = remember(displayContent, isNarration) {
                    if (isNarration) {
                        AnnotatedString(displayContent)
                    } else {
                        renderActions(displayContent)
                    }
                }

                Column {
                    Text(
                        // 星号包裹的动作描写渲染成斜体，对话读起来更像剧本
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        textAlign = if (isNarration) TextAlign.Center else TextAlign.Start
                    )

                    if (isStreaming && displayContent.isEmpty()) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor
                        )
                    }

                    if (message.edited) {
                        Text(
                            text = "已编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                }
            }

            message.providerSnapshot?.let { snapshot ->
                Text(
                    text = "${snapshot.modelId} · ${snapshot.temperature}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.xs, top = 2.dp)
                )
            }
        }
    }
}

/**
 * 动作描写的匹配规则。
 *
 * 提到文件级常量而不是每次现编译：正则编译很贵，而这里是每条消息、每次重组都会走的路径。
 */
private val ACTION_PATTERN = Regex("\\*([^*]+)\\*")

/**
 * 把 *星号包裹的内容* 渲染成斜体。
 */
private fun renderActions(content: String): AnnotatedString = buildAnnotatedString {
    val pattern = ACTION_PATTERN
    var cursor = 0

    pattern.findAll(content).forEach { match ->
        append(content.substring(cursor, match.range.first))
        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
        append(match.groupValues[1])
        pop()
        cursor = match.range.last + 1
    }
    append(content.substring(cursor))
}
