package com.personal.roleplay.domain.model

/**
 * 领域枚举。
 *
 * 每个枚举都带 [wire] 值：实体层只存字符串，领域层负责解析，
 * 这样 Room 不需要为每个枚举配 TypeConverter，也避免枚举改名时数据库迁移出问题。
 */

/** 接入协议 */
enum class Protocol(val wire: String, val displayName: String) {
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI 兼容"),
    ANTHROPIC("anthropic", "Anthropic"),
    GEMINI("gemini", "Gemini"),
    OLLAMA("ollama", "Ollama / 本地");

    /**
     * 是否支持存在/频率惩罚。
     * Anthropic 与 Gemini 的接口里根本没有这两个字段，界面不该给它们摆出无效旋钮。
     */
    val supportsPenalties: Boolean
        get() = this == OPENAI_COMPATIBLE || this == OLLAMA

    /** 是否走 OpenAI 的 reasoning_effort 那套思考字段 */
    val usesEffortStyleReasoning: Boolean
        get() = this == OPENAI_COMPATIBLE || this == OLLAMA

    companion object {
        fun fromWire(value: String?): Protocol =
            entries.firstOrNull { it.wire == value } ?: OPENAI_COMPATIBLE
    }
}

/**
 * 槽位。省钱的关键：主对话用强模型，抽取与压缩用小模型。
 */
enum class Slot(val wire: String, val displayName: String) {
    MAIN_DIALOGUE("main", "主对话"),
    MEMORY_EXTRACTION("memory", "记忆抽取"),
    SUMMARY("summary", "摘要压缩"),
    PERSONA_CHECK("persona", "人设校验"),
    VOICE("voice", "语音");

    companion object {
        fun fromWire(value: String?): Slot =
            entries.firstOrNull { it.wire == value } ?: MAIN_DIALOGUE
    }
}

/**
 * 记忆分层。L0 人设在角色卡里，L5 世界在世界书里，因此本表只有 L1-L4。
 *
 * 这里刻意不再放「注入时的标题」：标题的措辞必须站在角色视角（见 ContextAssembler），
 * 写在本枚举里容易被拿去用，而旧措辞正是用户与角色信息互相污染的来源。
 */
enum class MemoryLayer(val wire: String, val displayName: String) {
    L1_CONTEXT("L1", "情景"),
    L2_SEMANTIC("L2", "语义"),
    L3_EPISODIC("L3", "情节"),
    L4_EMOTION("L4", "情感");

    companion object {
        fun fromWire(value: String?): MemoryLayer =
            entries.firstOrNull { it.wire == value } ?: L3_EPISODIC
    }
}

/** 消息角色 */
enum class MessageRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    NARRATION("narration");

    companion object {
        fun fromWire(value: String?): MessageRole =
            entries.firstOrNull { it.wire == value } ?: USER
    }
}

/** 记忆来源：抽取所得 / 用户手动定义 */
enum class MemoryOrigin(val wire: String, val displayName: String) {
    EXTRACTED("extracted", "对话抽取"),
    USER_DEFINED("user_defined", "手动添加");

    companion object {
        fun fromWire(value: String?): MemoryOrigin =
            entries.firstOrNull { it.wire == value } ?: EXTRACTED
    }
}

/** 记忆状态：正常 / 来源被改动待对账 / 已删除 */
enum class MemoryStatus(val wire: String, val displayName: String) {
    ACTIVE("active", "生效"),
    STALE("stale", "待对账"),
    DELETED("deleted", "已失效");

    companion object {
        fun fromWire(value: String?): MemoryStatus =
            entries.firstOrNull { it.wire == value } ?: ACTIVE
    }
}
