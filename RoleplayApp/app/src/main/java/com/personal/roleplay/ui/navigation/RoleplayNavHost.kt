package com.personal.roleplay.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.personal.roleplay.ui.characters.CharacterDetailScreen
import com.personal.roleplay.ui.characters.CharacterEditorScreen
import com.personal.roleplay.ui.characters.CharacterListScreen
import com.personal.roleplay.ui.chat.ChatScreen
import com.personal.roleplay.ui.debug.RequestDebugScreen
import com.personal.roleplay.ui.groups.GroupListScreen
import com.personal.roleplay.ui.memory.MemoryPanelScreen
import com.personal.roleplay.ui.settings.BackupScreen
import com.personal.roleplay.ui.settings.PersonaEditorScreen
import com.personal.roleplay.ui.settings.PersonaSettingsScreen
import com.personal.roleplay.ui.settings.ProviderEditorScreen
import com.personal.roleplay.ui.settings.ReplyFormatScreen
import com.personal.roleplay.ui.settings.ProviderSettingsScreen
import com.personal.roleplay.ui.settings.SettingsScreen
import com.personal.roleplay.ui.settings.UsageScreen
import com.personal.roleplay.ui.worldbook.WorldBookScreen

/**
 * 导航结构（第十章）：底部只有「角色」和「设置」两个 Tab，
 * 其余都是全屏页面从右侧推入，返回键逐层退出。
 *
 * 刻意不做深层嵌套导航：个人工具，路径越短越好找。
 */
@Composable
fun RoleplayNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showBottomBar = currentDestination?.route == Route.Characters.path ||
        currentDestination?.route == Route.Groups.path ||
        currentDestination?.route == Route.Settings.path

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == Route.Characters.path } == true,
                        onClick = {
                            navController.navigate(Route.Characters.path) {
                                popUpTo(Route.Characters.path) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Forum, contentDescription = "角色") },
                        label = { Text("角色") }
                    )
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == Route.Groups.path } == true,
                        onClick = {
                            navController.navigate(Route.Groups.path) {
                                popUpTo(Route.Characters.path) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.People, contentDescription = "群聊") },
                        label = { Text("群聊") }
                    )
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == Route.Settings.path } == true,
                        onClick = {
                            navController.navigate(Route.Settings.path) {
                                popUpTo(Route.Characters.path) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                        label = { Text("设置") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.Characters.path,
            modifier = Modifier.padding(padding)
        ) {
            composable(Route.Characters.path) {
                CharacterListScreen(
                    onOpenCharacter = { characterId ->
                        navController.navigate(Route.CharacterDetail.create(characterId))
                    },
                    onEditCharacter = { characterId ->
                        navController.navigate(Route.CharacterEditor.create(characterId))
                    },
                    onCreateCharacter = {
                        navController.navigate(Route.CharacterEditor.create(NEW_ID))
                    }
                )
            }

            composable(Route.Groups.path) {
                GroupListScreen(
                    onOpenSession = { sessionId ->
                        navController.navigate(Route.Chat.create(sessionId))
                    }
                )
            }

            composable(Route.Settings.path) {
                SettingsScreen(
                    onOpenPersonas = { navController.navigate(Route.Personas.path) },
                    onOpenReplyFormat = { navController.navigate(Route.ReplyFormat.path) },
                    onOpenProviders = { navController.navigate(Route.Providers.path) },
                    onOpenUsage = { navController.navigate(Route.Usage.path) },
                    onOpenBackup = { navController.navigate(Route.Backup.path) },
                    onOpenDebug = { navController.navigate(Route.Debug.path) }
                )
            }

            composable(
                route = Route.CharacterDetail.path,
                arguments = listOf(
                    navArgument(Route.CharacterDetail.ARG_CHARACTER_ID) { type = NavType.StringType }
                )
            ) { entry ->
                val characterId = entry.arguments?.getString(Route.CharacterDetail.ARG_CHARACTER_ID).orEmpty()
                CharacterDetailScreen(
                    characterId = characterId,
                    onBack = { navController.popBackStack() },
                    onOpenSession = { sessionId ->
                        navController.navigate(Route.Chat.create(sessionId))
                    },
                    onEditCharacter = {
                        navController.navigate(Route.CharacterEditor.create(characterId))
                    }
                )
            }

            composable(
                route = Route.CharacterEditor.path,
                arguments = listOf(
                    navArgument(Route.CharacterEditor.ARG_CHARACTER_ID) { type = NavType.StringType }
                )
            ) { entry ->
                val rawId = entry.arguments?.getString(Route.CharacterEditor.ARG_CHARACTER_ID).orEmpty()
                CharacterEditorScreen(
                    // NEW_ID 表示新建，其余按已有角色加载
                    characterId = rawId.takeIf { it != NEW_ID },
                    onBack = { navController.popBackStack() },
                    onOpenWorldBook = { worldBookId ->
                        navController.navigate(Route.WorldBook.create(worldBookId))
                    }
                )
            }

            composable(
                route = Route.Chat.path,
                arguments = listOf(
                    navArgument(Route.Chat.ARG_SESSION_ID) { type = NavType.StringType },
                    navArgument(Route.Chat.ARG_FOCUS_SEQ) {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) { entry ->
                val sessionId = entry.arguments?.getString(Route.Chat.ARG_SESSION_ID).orEmpty()
                val focusSeq = entry.arguments?.getLong(Route.Chat.ARG_FOCUS_SEQ) ?: -1L
                ChatScreen(
                    sessionId = sessionId,
                    focusSeq = focusSeq,
                    onBack = { navController.popBackStack() },
                    onOpenMemory = {
                        navController.navigate(Route.MemoryPanel.create(sessionId))
                    },
                    onOpenDebug = { navController.navigate(Route.Debug.path) }
                )
            }

            composable(
                route = Route.MemoryPanel.path,
                arguments = listOf(
                    navArgument(Route.MemoryPanel.ARG_SESSION_ID) { type = NavType.StringType }
                )
            ) { entry ->
                val sessionId = entry.arguments?.getString(Route.MemoryPanel.ARG_SESSION_ID).orEmpty()
                MemoryPanelScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onJumpToMessage = { seq ->
                        navController.navigate(Route.Chat.create(sessionId, seq)) {
                            popUpTo(Route.Chat.path) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Route.WorldBook.path,
                arguments = listOf(
                    navArgument(Route.WorldBook.ARG_WORLD_BOOK_ID) { type = NavType.StringType }
                )
            ) { entry ->
                val bookId = entry.arguments?.getString(Route.WorldBook.ARG_WORLD_BOOK_ID).orEmpty()
                WorldBookScreen(
                    worldBookId = bookId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Route.Personas.path) {
                PersonaSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onEditPersona = { personaId ->
                        navController.navigate(Route.PersonaEditor.create(personaId))
                    },
                    onCreatePersona = {
                        navController.navigate(Route.PersonaEditor.create(NEW_ID))
                    }
                )
            }

            composable(
                route = Route.PersonaEditor.path,
                arguments = listOf(
                    navArgument(Route.PersonaEditor.ARG_PERSONA_ID) { type = NavType.StringType }
                )
            ) { entry ->
                val rawId = entry.arguments?.getString(Route.PersonaEditor.ARG_PERSONA_ID).orEmpty()
                PersonaEditorScreen(
                    // NEW_ID 表示新建，其余按已有人设加载
                    personaId = rawId.takeIf { it != NEW_ID },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Route.Providers.path) {
                ProviderSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onEditProvider = { providerId ->
                        navController.navigate(Route.ProviderEditor.create(providerId))
                    },
                    onCreateProvider = { templateId ->
                        navController.navigate(Route.ProviderEditor.create(templateId))
                    }
                )
            }

            composable(
                route = Route.ProviderEditor.path,
                arguments = listOf(
                    navArgument(Route.ProviderEditor.ARG_PROVIDER_ID) { type = NavType.StringType }
                )
            ) { entry ->
                val providerId = entry.arguments?.getString(Route.ProviderEditor.ARG_PROVIDER_ID).orEmpty()
                ProviderEditorScreen(
                    providerId = providerId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Route.ReplyFormat.path) {
                ReplyFormatScreen(onBack = { navController.popBackStack() })
            }

            composable(Route.Usage.path) {
                UsageScreen(onBack = { navController.popBackStack() })
            }

            composable(Route.Backup.path) {
                BackupScreen(onBack = { navController.popBackStack() })
            }

            composable(Route.Debug.path) {
                RequestDebugScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/** 路由里表示「新建」的哨兵 id */
private const val NEW_ID = "new"
