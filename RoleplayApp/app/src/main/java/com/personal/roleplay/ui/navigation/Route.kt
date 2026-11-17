package com.personal.roleplay.ui.navigation

/**
 * 路由。底部只有两个 Tab，其余都是全屏页面（第十章的导航结构）。
 */
sealed class Route(val path: String) {

    data object Characters : Route("characters")

    /** 群聊独立成 Tab：不再挂在角色详情页底下 */
    data object Groups : Route("groups")

    data object Settings : Route("settings")

    data object CharacterDetail : Route("character/{characterId}") {
        fun create(characterId: String) = "character/$characterId"
        const val ARG_CHARACTER_ID = "characterId"
    }

    data object CharacterEditor : Route("character_editor/{characterId}") {
        fun create(characterId: String) = "character_editor/$characterId"
        const val ARG_CHARACTER_ID = "characterId"
    }

    /**
     * focusSeq 是可参数：从记忆面板点「去看」溯源时带上，
     * 对话页据此滚到那条消息并短暂高亮。
     */
    data object Chat : Route("chat/{sessionId}?focusSeq={focusSeq}") {
        fun create(sessionId: String, focusSeq: Long? = null): String =
            "chat/$sessionId" + (focusSeq?.let { "?focusSeq=$it" } ?: "")

        const val ARG_SESSION_ID = "sessionId"
        const val ARG_FOCUS_SEQ = "focusSeq"
    }

    data object MemoryPanel : Route("memory/{sessionId}") {
        fun create(sessionId: String) = "memory/$sessionId"
        const val ARG_SESSION_ID = "sessionId"
    }

    data object WorldBook : Route("worldbook/{worldBookId}") {
        fun create(worldBookId: String) = "worldbook/$worldBookId"
        const val ARG_WORLD_BOOK_ID = "worldBookId"
    }

    /** 用户人设库：角色单线与群聊都从这里读取 */
    data object Personas : Route("personas")

    data object PersonaEditor : Route("persona_editor/{personaId}") {
        fun create(personaId: String) = "persona_editor/$personaId"
        const val ARG_PERSONA_ID = "personaId"
    }

    data object Providers : Route("providers")

    data object ProviderEditor : Route("provider_editor/{providerId}") {
        fun create(providerId: String) = "provider_editor/$providerId"
        const val ARG_PROVIDER_ID = "providerId"
    }

    /** 全局回复格式 */
    data object ReplyFormat : Route("reply_format")

    data object Usage : Route("usage")

    data object Backup : Route("backup")

    data object Debug : Route("debug")

    /** 底部导航栏只出现在这几个页面 */
    val isTab: Boolean
        get() = this is Characters || this is Groups || this is Settings
}
