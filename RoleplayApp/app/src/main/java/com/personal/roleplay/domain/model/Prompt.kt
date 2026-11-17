package com.personal.roleplay.domain.model

import kotlinx.serialization.Serializable

/**
 * 调试面板落库的区块快照。
 */
@Serializable
data class PromptBlockRecord(
    val key: String,
    val label: String,
    val content: String,
    val estimatedTokens: Int,
    val truncated: Boolean
)

/**
 * 拼装过程中的一个区块。
 *
 * [estimatedTokens] 用于「各区块 token 占用」展示，
 * 也是后续按真实占用调整预算数字的依据（见 11.2 表格尾部备注）。
 */
data class PromptBlock(
    val key: String,
    val label: String,
    val content: String
) {
    var estimatedTokens: Int = 0
        internal set

    var truncated: Boolean = false
        internal set

    /** 组装时回填 token 估算 */
    fun applyEstimated(value: Int) {
        estimatedTokens = value
    }

    /** 标记这个区块因为超预算被截短了，调试面板要能看出来 */
    fun markTruncated() {
        truncated = true
    }

    fun toRecord(): PromptBlockRecord =
        PromptBlockRecord(key, label, content, estimatedTokens, truncated)
}

/** 交给模型 API 的一轮对话 */
data class ChatTurn(
    val role: MessageRole,
    val content: String
)

/**
 * 群聊历史里的说话人标签词表。
 *
 * 写入端在 `ContextAssembler`（拼历史），读出端在 `ChatOrchestrator`（剥掉模型模仿出来的标签）、
 * `MemoryPrompts`（记忆抽取时标明每句是谁说的）。三处必须是同一份字面量，
 * 各写各的迟早会漂移，而漂移的表现恰好是最难查的那类故障：归属错乱，不报错、不留痕。
 */
object SpeakerLabels {
    /**
     * 当前扮演者自己。
     *
     * 专门用「我」而不是角色名：角色名会和「同场角色」区块里的别人混在一起，
     * 而「我」在整段上下文里只有一种读法。
     */
    const val SELF = "我"

    /**
     * 那个真人。
     *
     * 不用「对方」：群聊里三个人在场时，每个角色都有自己的「对方」，
     * 这个称呼根本指不明是哪个真人。「用户」是唯一的、不会和角色名混淆的实体。
     */
    const val USER = "用户"

    /** 场景描写，不是谁说的话 */
    const val NARRATION = "旁白"

    /** 演员表里的角色已被删除，但它的历史台词还在。标记要明确「不是用户」 */
    const val UNKNOWN_SPEAKER = "已离场角色"
}

/**
 * 组装好的上下文。
 *
 * [systemPrompt] 把静态区块（L0 人设、人设锚定、L5、L4、L2、L1、L3、约束）拼成一条 system 消息。
 * 静态部分放最前是为了命中上游 LLM 的前缀缓存，省钱且降低首字延迟（见 11.1）。
 *
 * [turns] 是「最近 N 轮原文」，按 token 从最新往回填，N 由剩余空间动态决定。
 */
data class AssembledContext(
    val systemPrompt: String,
    val blocks: List<PromptBlock>,
    val turns: List<ChatTurn>,
    val estimatedTokens: Int,
    /** 静态区块已吃掉窗口的 60% 以上，调用方应先压缩 L1 摘要 */
    val compressionNeeded: Boolean,
    /** 空间不足，最早的原文被丢弃了，界面上要提示 */
    val droppedOldTurns: Boolean
) {
    /**
     * 「回复要求」区块被超预算截短了。
     *
     * 单独拎出来是因为这个区块和别的不同：它装的是「谁是谁」的辨认规则，
     * 被裁掉不会报错、不会崩，只会让模型悄悄把别人的台词算到用户头上——
     * 属于最难查的一类故障，必须能在界面上被发现。
     */
    val constraintTruncated: Boolean
        get() = blocks.any { it.key == CONSTRAINT_BLOCK_KEY && it.truncated }

    companion object {
        /** 约束区块的 key，与 ContextAssembler 共用 */
        const val CONSTRAINT_BLOCK_KEY = "constraint"
    }
}
