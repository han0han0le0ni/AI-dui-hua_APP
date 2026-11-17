package com.personal.roleplay.data.card

import android.content.Context
import com.personal.roleplay.data.local.AvatarStore
import com.personal.roleplay.data.seed.BuiltInCharacters
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.WorldBookEntry
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.WorldBookRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把一张酒馆卡落成本地角色。
 *
 * 映射原则（与定稿 3.5 一致）：
 * - 人设相关的几段文本（description / personality / scenario / system_prompt /
 *   post_history_instructions）合并进 `persona`，**不走自定义模板**。
 *   自定义模板的渲染只替换 `{{区块key}}`，卡里的 system_prompt 没有这些占位符，
 *   直接塞进去会把 L1-L5 区块全部挤掉；合并进 persona 则仍然受预算截断保护。
 * - `first_mes` → 开场白，`mes_example` → 示例对话，`character_book` → 世界书。
 * - PNG 卡整张图直接存成头像（酒馆里看到的也是这张图）。
 * - `alternate_greetings`、`use_regex`、`secondary_keys` 暂不支持，直接丢弃。
 */
class CharacterCardImporter(
    private val context: Context,
    private val characterRepository: CharacterRepository,
    private val worldBookRepository: WorldBookRepository
) {

    /**
     * @throws IllegalArgumentException 卡本身有问题，message 可直接展示
     */
    suspend fun import(bytes: ByteArray): Character = withContext(Dispatchers.IO) {
        val card = CharacterCardCodec.decode(bytes)

        val avatarPath = if (CharacterCardCodec.isPng(bytes)) {
            AvatarStore.saveBytes(context, bytes)
        } else {
            null
        }

        val worldBookId = importBook(card)

        val now = System.currentTimeMillis()
        val character = BuiltInCharacters.newCharacter(now).copy(
            name = card.name,
            avatarPath = avatarPath,
            tagline = card.description.firstLineTagline(),
            persona = composePersona(card),
            personalityTags = card.tags.take(MAX_TAGS),
            greeting = card.firstMessage,
            exampleDialogues = card.exampleMessages,
            worldBookId = worldBookId,
            createdAt = now,
            updatedAt = now
        )

        characterRepository.save(character)
        character
    }

    private suspend fun importBook(card: CharacterCardCodec.CardData): String? {
        val book = card.book ?: return null

        val created = worldBookRepository.createBook(
            name = book.name ?: "${card.name} 的世界书",
            description = null
        )

        book.entries.forEach { entry ->
            worldBookRepository.saveEntry(
                WorldBookEntry(
                    id = Ids.new(),
                    worldBookId = created.id,
                    keys = entry.keys,
                    content = entry.content,
                    // 卡里的 order 越小越靠前，对应我们的「优先级越高」
                    priority = entry.priority ?: (DEFAULT_PRIORITY - entry.order).coerceIn(0, MAX_PRIORITY),
                    enabled = entry.enabled,
                    constant = entry.constant,
                    insertionOrder = entry.order
                )
            )
        }
        return created.id
    }

    private fun composePersona(card: CharacterCardCodec.CardData): String = buildString {
        append(card.description.trim())

        fun section(title: String, body: String) {
            if (body.isBlank()) return
            if (isNotEmpty()) append("\n\n")
            append("【$title】\n")
            append(body.trim())
        }

        section("性格", card.personality)
        section("场景", card.scenario)
        // 卡的作者指令：单独标出来，模型才知道这是「怎么演」而不是「我是谁」
        section("扮演指令", card.systemPrompt)
        section("补充要求", card.postHistory)
    }.trim()

    /** 卡片网格的副标题：拿描述第一行，去掉 markdown 记号，太长截断 */
    private fun String.firstLineTagline(): String? = this
        .lineSequence()
        .map { it.trim().trimStart('#', '*', '-', '>').trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(MAX_TAGLINE_LENGTH)
        ?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_TAGS = 8
        const val MAX_TAGLINE_LENGTH = 40
        const val DEFAULT_PRIORITY = 100
        const val MAX_PRIORITY = 1000
    }
}
