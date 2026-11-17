package com.personal.roleplay.domain.model

/**
 * 用户人设：我在这个世界里是谁。
 *
 * 独立成库而不是绑在某条会话上——同一个「仙门弟子」要跨多个角色、多个群聊复用，
 * 所以设置里统一维护，会话只引用它的 id。改一次全体生效，不必每开一条新线重打一遍。
 *
 * [name] 只用于界面辨认与挑选；[content] 才是真正注入提示词的正文。
 */
data class UserPersona(
    val id: String,
    /** 便于辨认的短名字，如「仙门弟子」 */
    val name: String,
    /** 人设正文，注入「对方是谁」小节 */
    val content: String,
    /** 新会话默认套用的人设；全局最多一个 */
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** 列表里的一行摘要：把换行压平，太长交给界面省略 */
    val preview: String
        get() = content.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}
