package com.personal.roleplay.ui.characters

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.personal.roleplay.LocalAppContainer
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.data.local.AvatarStore
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File

/** 主题色预设：够用就好，不做取色器 */
private val ACCENT_PRESETS = listOf(
    0xFF6B5B95L, 0xFF4A6B7CL, 0xFF8A4A6BL, 0xFF5C7A4AL,
    0xFF9A6B3FL, 0xFF3F5F8AL, 0xFF8C4A4AL, 0xFF4A7D6BL
)

private fun parseTags(raw: String): List<String> =
    raw.split('、', ',', '，', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterEditorScreen(
    characterId: String?,
    onBack: () -> Unit,
    onOpenWorldBook: (String) -> Unit,
    viewModel: CharacterEditorViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current

    val providerConfigs by container.providerRepository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var avatarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(characterId) {
        viewModel.bind(characterId)
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

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val path = AvatarStore.import(context, uri)
            if (path != null) {
                viewModel.update { it.copy(avatarPath = path) }
            } else {
                avatarMessage = "这张图没能导入，换一张试试"
            }
        }
    }

    val character = uiState.character

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (uiState.isNew) "新建角色" else "编辑角色卡",
                subtitle = character?.name,
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (character == null) {
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
            // ---- 立绘 ----
            item {
                SectionHeader(
                    text = "立绘",
                    hint = "存在 app 私有目录，不会往外传。可选，没有立绘时用首字代替。"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(Color(character.accentColorArgb))
                            .clickable { avatarPicker.launch("image/*") },
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
                            Text(
                                text = character.name.take(1),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White
                            )
                        }
                    }
                    Column {
                        TextButton(onClick = { avatarPicker.launch("image/*") }) {
                            Text(if (character.avatarPath == null) "选择图片" else "换一张")
                        }
                        if (character.avatarPath != null) {
                            TextButton(onClick = { viewModel.update { it.copy(avatarPath = null) } }) {
                                Text("移除立绘")
                            }
                        }
                    }
                }
                avatarMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ---- 基本信息 ----
            item { SectionHeader(text = "基本信息") }

            item {
                EditorField(
                    label = "名字",
                    value = character.name,
                    onValueChange = { value -> viewModel.update { it.copy(name = value) } }
                )
            }

            // ---- 人设 ----
            item {
                SectionHeader(
                    text = "人设",
                    hint = "身份、语气、价值观、禁忌、口头禅都写在这里。这是 L0，全量常驻 prompt。"
                )
            }

            item {
                OutlinedTextField(
                    value = character.persona,
                    onValueChange = { value -> viewModel.update { it.copy(persona = value) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    label = { Text("人设正文") },
                    minLines = 8
                )
            }

            item {
                EditorField(
                    label = "一句话人设（卡片副标题）",
                    value = character.tagline.orEmpty(),
                    onValueChange = { value -> viewModel.update { it.copy(tagline = value) } }
                )
            }

            item {
                EditorField(
                    label = "性格标签（用 、或逗号分隔）",
                    value = character.personalityTags.joinToString("、"),
                    onValueChange = { value -> viewModel.update { it.copy(personalityTags = parseTags(value)) } }
                )
            }

            item {
                EditorField(
                    label = "语气",
                    value = character.tone.orEmpty(),
                    onValueChange = { value -> viewModel.update { it.copy(tone = value) } }
                )
            }

            item {
                EditorField(
                    label = "口头禅",
                    value = character.catchphrase.orEmpty(),
                    onValueChange = { value -> viewModel.update { it.copy(catchphrase = value) } }
                )
            }

            item {
                EditorField(
                    label = "禁忌",
                    value = character.taboos.orEmpty(),
                    onValueChange = { value -> viewModel.update { it.copy(taboos = value) } }
                )
            }

            item {
                EditorField(
                    label = "关系定位",
                    value = character.relationPosition.orEmpty(),
                    onValueChange = { value -> viewModel.update { it.copy(relationPosition = value) } }
                )
            }

            // ---- 开场白 ----
            item {
                SectionHeader(
                    text = "开场白",
                    hint = "进入会话时角色主动说的第一句，绝不让你面对空白输入框。"
                )
            }

            item {
                OutlinedTextField(
                    value = character.greeting,
                    onValueChange = { value -> viewModel.update { it.copy(greeting = value) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    label = { Text("开场白") },
                    minLines = 3
                )
            }

            // ---- 示例对话 ----
            item {
                SectionHeader(
                    text = "示例对话",
                    hint = "人设锚定用，对把握语气效果最直接。"
                )
            }

            item {
                OutlinedTextField(
                    value = character.exampleDialogues,
                    onValueChange = { value -> viewModel.update { it.copy(exampleDialogues = value) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    label = { Text("示例对话") },
                    minLines = 6
                )
            }

            // ---- 主题色 ----
            item { SectionHeader(text = "主题色", hint = "进入对话页时全局切换配色。") }

            item {
                FlowRow(
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    ACCENT_PRESETS.forEach { argb ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .then(
                                    if (argb == character.accentColorArgb) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { viewModel.update { it.copy(accentColorArgb = argb) } }
                        )
                    }
                }
            }

            // ---- 偏好接入配置 ----
            item { SectionHeader(text = "偏好接入配置", hint = "留空则使用主对话槽的默认配置。") }

            item {
                ProviderPreferenceDropdown(
                    options = providerConfigs.map { it.id to it.name },
                    selectedId = character.providerPreference,
                    onSelect = { value -> viewModel.update { it.copy(providerPreference = value) } }
                )
            }

            // ---- 世界书 ----
            item {
                SectionHeader(
                    text = "世界书",
                    hint = "维护世界观、地名、组织、人物关系。玩长线剧情时这个是刚需。"
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                        .clickable {
                            scope.launch {
                                val bookId = viewModel.ensureWorldBook()
                                if (bookId != null) onOpenWorldBook(bookId)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Icon(Icons.Default.Book, contentDescription = null)
                        Text(if (character.worldBookId == null) "创建并编辑世界书" else "编辑世界书")
                    }
                }
            }

            // ---- 自定义 system prompt ----
            item {
                SectionHeader(
                    text = "完全自定义系统提示词",
                    hint = "打开后，上面的分层拼装不再生效，改由你写的模板决定。"
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
                    Text("使用自定义模板", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = character.customSystemPrompt,
                        onCheckedChange = { checked ->
                            viewModel.update { it.copy(customSystemPrompt = checked) }
                        }
                    )
                }
            }

            if (character.customSystemPrompt) {
                item {
                    OutlinedTextField(
                        value = character.systemPromptTemplate.orEmpty(),
                        onValueChange = { value -> viewModel.update { it.copy(systemPromptTemplate = value) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md),
                        label = { Text("系统提示词模板") },
                        supportingText = {
                            Text("可用占位符：{{persona}} {{user_persona}} {{world}} {{emotion}} {{semantic}} {{summary}} {{episodic}} {{constraint}}")
                        },
                        minLines = 8
                    )
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
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        label = { Text(label) },
        singleLine = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPreferenceDropdown(
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second ?: "跟随默认配置"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            label = { Text("偏好接入配置") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("跟随默认配置") },
                onClick = {
                    expanded = false
                    onSelect(null)
                }
            )
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    }
                )
            }
        }
    }
}
