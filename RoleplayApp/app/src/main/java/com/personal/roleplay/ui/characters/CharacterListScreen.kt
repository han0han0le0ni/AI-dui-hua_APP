package com.personal.roleplay.ui.characters

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.data.card.CharacterCardCodec
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.ui.components.ConfirmDialog
import com.personal.roleplay.ui.components.EmptyState
import com.personal.roleplay.ui.components.FeedbackEffect
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing
import com.personal.roleplay.ui.util.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListScreen(
    onOpenCharacter: (String) -> Unit,
    onEditCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    viewModel: CharacterListViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()
    var pendingDelete by remember { mutableStateOf<Character?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 只读一次文件，读到的字节交给 ViewModel；不持有 Uri，避免权限过期后还能再次读取的假象
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readCardBytes(context, uri) } }
                .onSuccess(viewModel::importCard)
                .onFailure { viewModel.reportImportError(it.message ?: "无法读取文件") }
        }
    }

    FeedbackEffect(uiState.feedback, snackbarHost, viewModel::consumeFeedback)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.showArchived) "已归档" else "我的角色") },
                actions = {
                    TextButton(
                        onClick = {
                            importPicker.launch(
                                arrayOf(
                                    "application/json",
                                    "image/png",
                                    "image/*",
                                    "application/octet-stream",
                                    "text/plain"
                                )
                            )
                        }
                    ) {
                        Text("导入")
                    }
                    IconButton(onClick = viewModel::toggleArchivedView) {
                        Icon(
                            imageVector = if (uiState.showArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = if (uiState.showArchived) "查看未归档角色" else "查看已归档角色"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateCharacter) {
                Icon(Icons.Default.Add, contentDescription = "新建角色")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (uiState.cards.isEmpty() && !uiState.isLoading) {
            EmptyState(
                title = if (uiState.showArchived) "还没有归档的角色" else "还没有角色",
                subtitle = "内置的两个示例角色如果被删掉了，可以在设置里恢复；也可以自己建一个。",
                modifier = Modifier.padding(padding),
                actionLabel = "新建角色",
                onAction = onCreateCharacter
            )
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(uiState.cards, key = { it.character.id }) { card ->
                CharacterCard(
                    character = card.character,
                    sessionCount = card.sessionCount,
                    lastActiveAt = card.lastActiveAt,
                    onClick = { onOpenCharacter(card.character.id) },
                    onEdit = { onEditCharacter(card.character.id) },
                    onToggleArchive = {
                        viewModel.setArchived(card.character.id, !card.character.archived)
                    },
                    onRequestDelete = { pendingDelete = card.character }
                )
            }
        }
    }

    pendingDelete?.let { character ->
        ConfirmDialog(
            title = "删除「${character.name}」？",
            message = "会删除 TA 自己的所有线，以及这些线里 TA 记住的事。" +
                "TA 参与的群聊会保留下来，只把 TA 从成员里摘掉。这个操作无法撤销。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            onConfirm = {
                viewModel.deleteCharacter(character.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun CharacterCard(
    character: Character,
    sessionCount: Int,
    lastActiveAt: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onRequestDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(character.accentColorArgb).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                if (character.avatarPath != null) {
                    AsyncImage(
                        model = File(character.avatarPath),
                        contentDescription = "${character.name}的立绘",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 没有立绘时用首字 + 主题色兜底，卡片网格不至于一片空白
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(character.accentColorArgb)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = character.name.take(1),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                    }
                }

                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "「${character.name}」的更多操作"
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑角色卡") },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (character.archived) "取消归档" else "归档") },
                            onClick = {
                                menuExpanded = false
                                onToggleArchive()
                            },
                            leadingIcon = {
                                Icon(
                                    if (character.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            onClick = {
                                menuExpanded = false
                                onRequestDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                character.subtitle.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (sessionCount == 0) "还没开始" else "$sessionCount 条线",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = relativeTime(lastActiveAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 读取用户选中的卡。
 *
 * 边读边数条数，超过上限立即停：角色卡通常几百 KB，选错了文件（比如一张大图）
 * 不该先把整份读进内存再报错。
 */
private fun readCardBytes(context: Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取选中的文件")
    return input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > CharacterCardCodec.MAX_BYTES) {
                error("角色卡超过 16MB，请检查是不是选错了文件")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}
