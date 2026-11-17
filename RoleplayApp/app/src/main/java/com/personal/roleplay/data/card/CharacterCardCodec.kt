package com.personal.roleplay.data.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.Inflater

/**
 * 角色卡解码：PNG 卡与 JSON 卡都收敛到这里。
 *
 * 只做「读」：把 V1/V2/V3 三种形状归一化成 [CardData]，
 * 字段怎么落库由 [CharacterCardImporter] 决定。
 * 规则来自 character-card-spec-v2 / v3 与几个开源实现（Eta、Muse、NativeTavern）。
 */
object CharacterCardCodec {

    /** 上限 16 MiB：正常卡几百 KB，超过这个数基本是选错了文件 */
    const val MAX_BYTES = 16 * 1024 * 1024

    data class CardBook(
        val name: String?,
        val entries: List<CardBookEntry>
    )

    data class CardBookEntry(
        val keys: List<String>,
        val content: String,
        val comment: String?,
        val enabled: Boolean,
        val constant: Boolean,
        val order: Int,
        val priority: Int?
    )

    data class CardData(
        val name: String,
        val description: String,
        val personality: String,
        val scenario: String,
        val systemPrompt: String,
        val postHistory: String,
        val firstMessage: String,
        val exampleMessages: String,
        val creatorNotes: String,
        val tags: List<String>,
        val book: CardBook?,
        /** 解码时用的格式，调试与提示用 */
        val source: String
    )

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /**
     * @throws IllegalArgumentException 文件不是卡、字段缺失或结构损坏，message 可直接展示给用户
     */
    fun decode(bytes: ByteArray): CardData {
        require(bytes.isNotEmpty()) { "文件是空的" }
        require(bytes.size <= MAX_BYTES) { "角色卡超过 16MB，请检查是不是选错了文件" }

        return if (isPng(bytes)) {
            val json = readPngCardJson(bytes)
                ?: error("这张 PNG 里没有角色卡数据（缺少 chara / ccv3 文本块）")
            parseJson(json, source = "PNG")
        } else {
            // 有些编辑器导出的 JSON 带 BOM，直接丢给解析器会报「不是合法 JSON」
            val json = strictUtf8(bytes).trimStart('\uFEFF')
            parseJson(json, source = "JSON")
        }
    }

    // ---------------- PNG ----------------

    /** `ccv3` 优先于 `chara`：前者是新规范，两者同时存在时以新的为准 */
    private fun readPngCardJson(bytes: ByteArray): String? {
        var offset = PNG_SIGNATURE.size
        var legacy: String? = null
        var v3: String? = null

        while (offset + CHUNK_HEADER_SIZE <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + CHUNK_HEADER_SIZE + length + CRC_SIZE > bytes.size) break

            val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)
            val dataOffset = offset + CHUNK_HEADER_SIZE
            val data = bytes.copyOfRange(dataOffset, dataOffset + length)

            when (type) {
                "tEXt" -> readTextChunk(data)?.let { (key, value) ->
                    when (key) {
                        "ccv3" -> v3 = value
                        "chara" -> legacy = value
                    }
                }

                "zTXt" -> readCompressedTextChunk(data)?.let { (key, value) ->
                    when (key) {
                        "ccv3" -> v3 = value
                        "chara" -> legacy = value
                    }
                }

                "iTXt" -> readInternationalTextChunk(data)?.let { (key, value) ->
                    when (key) {
                        "ccv3" -> v3 = value
                        "chara" -> legacy = value
                    }
                }

                "IEND" -> break
            }

            offset = dataOffset + length + CRC_SIZE
        }

        return (v3 ?: legacy)?.let(::decodeCardPayload)
    }

    /** tEXt：keyword \0 text（Latin-1，但内容是 base64/JSON 的 ASCII） */
    private fun readTextChunk(data: ByteArray): Pair<String, String>? {
        val separator = data.indexOf(0)
        if (separator <= 0) return null
        val key = String(data, 0, separator, StandardCharsets.ISO_8859_1)
        val value = String(data, separator + 1, data.size - separator - 1, StandardCharsets.ISO_8859_1)
        return key to value
    }

    /** zTXt：keyword \0 method(1) zlib(compressed text) */
    private fun readCompressedTextChunk(data: ByteArray): Pair<String, String>? {
        val separator = data.indexOf(0)
        if (separator <= 0 || separator + 2 > data.size) return null
        val key = String(data, 0, separator, StandardCharsets.ISO_8859_1)
        val compressed = data.copyOfRange(separator + 2, data.size)
        val text = inflate(compressed) ?: return null
        return key to String(text, StandardCharsets.UTF_8)
    }

    /** iTXt：keyword \0 flag(1) method(1) lang \0 translated \0 text */
    private fun readInternationalTextChunk(data: ByteArray): Pair<String, String>? {
        var cursor = data.indexOf(0)
        if (cursor <= 0) return null
        val key = String(data, 0, cursor, StandardCharsets.ISO_8859_1)
        cursor++
        if (cursor + 2 > data.size) return null

        val compressed = data[cursor].toInt() == 1
        // data[cursor + 1] 是 compression method，目前规范只定义了 0（zlib）
        cursor += 2

        val languageEnd = data.indexOf(0, cursor)
        if (languageEnd < 0) return null
        cursor = languageEnd + 1

        val translatedEnd = data.indexOf(0, cursor)
        if (translatedEnd < 0) return null
        cursor = translatedEnd + 1

        val textBytes = data.copyOfRange(cursor, data.size)
        val text = if (compressed) inflate(textBytes) else textBytes
        if (text == null) return null
        return key to String(text, StandardCharsets.UTF_8)
    }

    /** 文本块里可能是 base64（SillyTavern 的标准做法），也可能直接是 JSON */
    private fun decodeCardPayload(payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) return trimmed
        return runCatching {
            String(Base64.getMimeDecoder().decode(trimmed), StandardCharsets.UTF_8)
        }.getOrElse { error("角色卡文本块不是合法的 base64") }
    }

    private fun inflate(compressed: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }.getOrNull()

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun ByteArray.indexOf(value: Int, from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == value) return i
        return -1
    }

    // ---------------- JSON ----------------

    private fun parseJson(json: String, source: String): CardData {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }
            .getOrNull() ?: error("不是合法的角色卡 JSON")

        // V2/V3：{ spec, spec_version, data: {...} }；V1：字段直接铺在根上
        val data = (root["data"] as? JsonObject) ?: root

        val name = data.string("name")
        require(name.isNotBlank()) { "角色卡缺少 name 字段" }

        return CardData(
            name = name,
            description = data.string("description"),
            personality = data.string("personality"),
            scenario = data.string("scenario"),
            systemPrompt = data.string("system_prompt"),
            postHistory = data.string("post_history_instructions"),
            firstMessage = data.string("first_mes"),
            exampleMessages = data.string("mes_example"),
            // V1 的字段名是 creatorcomment，V2 起改成 creator_notes
            creatorNotes = data.string("creator_notes").ifBlank { data.string("creatorcomment") },
            tags = data.stringList("tags"),
            book = parseBook(data["character_book"] as? JsonObject),
            source = source
        )
    }

    private fun parseBook(book: JsonObject?): CardBook? {
        if (book == null) return null
        val entries = (book["entries"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapIndexedNotNull { index, entry ->
                val content = entry.string("content")
                if (content.isBlank()) return@mapIndexedNotNull null
                val keys = entry.stringList("keys").ifEmpty { entry.stringList("key") }
                CardBookEntry(
                    keys = keys,
                    content = content,
                    comment = entry.string("comment").ifBlank { entry.string("name") }.ifBlank { null },
                    enabled = entry.boolean("enabled", default = true),
                    constant = entry.boolean("constant", default = false),
                    order = entry.int("insertion_order") ?: index,
                    priority = entry.int("priority")
                )
            }

        return if (entries.isEmpty()) null else CardBook(
            name = book.string("name").ifBlank { null },
            entries = entries
        )
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }

    /** 严格 UTF-8：坏字节要报错，不能悄悄换成 U+FFFD 后继续跑 */
    private fun strictUtf8(bytes: ByteArray): String = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse { error("文件不是 UTF-8 编码的 JSON") }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** length(4) + type(4) */
    private const val CHUNK_HEADER_SIZE = 8
    private const val CRC_SIZE = 4
}
