package com.personal.roleplay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.roleplay.LocalViewModelFactory
import com.personal.roleplay.ui.components.AppTopBar
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.components.rememberSnackbarHost
import com.personal.roleplay.ui.theme.Spacing

/** 正文占位符：给个骨架，免得面对空白框不知道写什么 */
private val CONTENT_PLACEHOLDER = """
我是谁：
和对方的关系：
我的性格：
我在意/怕的事：
""".trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaEditorScreen(
    personaId: String?,
    onBack: () -> Unit,
    viewModel: PersonaEditorViewModel = viewModel(factory = LocalViewModelFactory.current)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = rememberSnackbarHost()

    LaunchedEffect(personaId) {
        viewModel.bind(personaId)
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

    val persona = uiState.persona

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (uiState.isNew) "新建人设" else "编辑人设",
                subtitle = persona?.name?.takeIf { it.isNotBlank() },
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        if (persona == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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
            item {
                SectionHeader(
                    text = "名字",
                    hint = "只用于在列表和对话页里认出是哪一个人设，不会发给模型。"
                )
                OutlinedTextField(
                    value = persona.name,
                    onValueChange = { next -> viewModel.update { it.copy(name = next) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    singleLine = true,
                    label = { Text("例如：仙门弟子") }
                )
            }

            item {
                SectionHeader(
                    text = "人设正文",
                    hint = "这段会作为「对方是谁」注入提示词，告诉角色正在和什么样的人说话。" +
                        "写身份、关系、性格、在意的事，越具体越稳。"
                )
                OutlinedTextField(
                    value = persona.content,
                    onValueChange = { next -> viewModel.update { it.copy(content = next) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    minLines = 8,
                    placeholder = { Text(CONTENT_PLACEHOLDER) }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("设为新会话默认", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "开新线、建群聊时自动带上它，省掉每次挑选",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = persona.isDefault,
                        onCheckedChange = { checked ->
                            viewModel.update { it.copy(isDefault = checked) }
                        }
                    )
                }
            }

            item {
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                ) {
                    Text("保存")
                }
            }
        }
    }
}
