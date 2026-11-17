package com.personal.roleplay.domain.context

/**
 * 上下文预算（11.2 的混合策略）。
 *
 * 各层设固定上限，剩下的全部给「最近 N 轮原文」，N 由剩余空间动态决定。
 * 换 128K 模型时原文能装几百轮，换 8K 模型时自动收缩，两端都不浪费也不超限。
 *
 * 这些是初始值，做完调试面板后按真实占用再调。
 */
data class ContextBudget(
    /** L0 人设 + 示例对话，通常 300-800，硬上限防止人设写太长 */
    val personaMax: Int = 2_000,
    /** 「对方是谁」，未填则为 0 */
    val userPersonaMax: Int = 300,
    /** L5 世界书命中条目，按优先级截断 */
    val worldBookMax: Int = 800,
    /** L4 情感状态，简短状态描述 */
    val emotionMax: Int = 200,
    /** L2 关于你的认知，超出时按置信度截断 */
    val semanticMax: Int = 500,
    /** L1 情景摘要，滚动增长 */
    val summaryMax: Int = 1_200,
    /** L3 情节记忆（精挑后），通常 5 条以内 */
    val episodicMax: Int = 600,
    /**
     * 输出格式与约束，常驻。
     *
     * 这个数字要装下：扮演规则 + 谁是谁的辨认说明 + 用户可配的标注格式。
     * 之前给 200 是只按旧的短文案算的，加上标注格式后会超，而超了是从尾部静默裁剪——
     * 剪掉的恰好是辨认说明，直接导致归属错乱。宁可多留余量。
     */
    val constraintMax: Int = 480,
    /** 群聊：同场角色的一句话简介（只做辨识，不给完整人设） */
    val castMax: Int = 300,
    /**
     * 群聊：说话人标签对照表 + 防串味规则。
     *
     * 这份说明比单人线的约束更长，而且**必须整段活着**：标签一旦被截掉一半，
     * 模型就分不清「【我】」「【用户】」「别的名字」，归属错误随即出现。
     */
    val groupRuleMax: Int = 320,
    /**
     * 压缩触发阈值：静态区块占用超过模型窗口的这个比例时，先压缩 L1 摘要。
     */
    val compressionThreshold: Float = 0.6f
) {
    companion object {
        val DEFAULT = ContextBudget()

        /** 小窗口模型（< 16K）用收紧版，见 6.2 的降级策略 */
        val COMPACT = ContextBudget(
            personaMax = 1_200,
            userPersonaMax = 200,
            worldBookMax = 400,
            emotionMax = 120,
            semanticMax = 300,
            summaryMax = 600,
            episodicMax = 300,
            // 同样要留够：标签对照表一旦被截断就是归属错乱，不能靠省这点预算换空间
            constraintMax = 400,
            castMax = 180,
            groupRuleMax = 300
        )

        fun forContextWindow(contextWindow: Int): ContextBudget =
            if (contextWindow < 16_000) COMPACT else DEFAULT
    }
}
