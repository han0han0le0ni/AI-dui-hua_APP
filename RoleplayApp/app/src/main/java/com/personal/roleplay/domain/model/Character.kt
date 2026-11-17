package com.personal.roleplay.domain.model

/**
 * 角色卡。L0 人设全部在这里，不进 memory 表。
 *
 * [accentColorArgb] 用 ARGB 打包的 Long 而不是 Compose 的 Color：
 * 领域层不依赖 UI，颜色在界面层再转换。
 */
data class Character(
    val id: String,
    val name: String,
    /** 立绘文件绝对路径，位于 app 私有目录 */
    val avatarPath: String?,
    /** 一句话人设，用于卡片网格副标题 */
    val tagline: String?,
    /** 人设正文：身份、语气、价值观、禁忌、口头禅 */
    val persona: String,
    val personalityTags: List<String>,
    val tone: String?,
    val taboos: String?,
    val catchphrase: String?,
    /** 关系定位，如「同门师兄」 */
    val relationPosition: String?,
    /** 开场白：角色主动说的第一句，绝不让你面对空白输入框 */
    val greeting: String,
    /** 示例对话原文，人设锚定用 */
    val exampleDialogues: String,
    /** 自定义系统提示词模板 */
    val systemPromptTemplate: String?,
    /** 完全自定义 system prompt 开关 */
    val customSystemPrompt: Boolean,
    val worldBookId: String?,
    val accentColorArgb: Long,
    /** 该角色偏好的接入配置 id；为空则用默认配置 */
    val providerPreference: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
    /** 内置示例角色，可归档可删除 */
    val builtIn: Boolean
) {
    /** 卡片副标题：优先一句话人设，否则用性格标签拼 */
    val subtitle: String
        get() = tagline?.takeIf { it.isNotBlank() }
            ?: personalityTags.take(3).joinToString(" · ")
}
