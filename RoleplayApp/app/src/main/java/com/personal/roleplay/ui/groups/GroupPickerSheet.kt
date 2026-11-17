package com.personal.roleplay.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.ui.components.SectionHeader
import com.personal.roleplay.ui.theme.Spacing

/**
 * 群聊成员选择。
 *
 * 勾选顺序就是发言轮转顺序，所以用保序的列表而不是 Set。
 * 至少要两个角色——一个人不叫群聊。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerSheet(
    characters: List<Character>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val picked = remember { mutableStateListOf<String>() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = Spacing.lg)) {
            SectionHeader(
                text = "开个群聊",
                hint = "按勾选顺序轮流发言。每轮只让模型扮演其中一个人，" +
                    "其余角色只作为同场角色出现，所以角色之间不会串味。"
            )

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                characters.forEach { character ->
                    item(key = character.id) {
                        val checked = picked.contains(character.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) picked.remove(character.id) else picked.add(character.id)
                                }
                                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 整行都可点，Checkbox 只负责显示状态
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column {
                                Text(
                                    text = character.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                character.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                                    Text(
                                        text = tagline,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { onConfirm(picked.toList()) },
                enabled = picked.size >= 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Text(if (picked.size < 2) "至少选两个角色" else "开始群聊（已选 ${picked.size} 个）")
            }
        }
    }
}
