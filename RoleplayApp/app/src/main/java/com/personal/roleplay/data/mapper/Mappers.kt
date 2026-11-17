package com.personal.roleplay.data.mapper

import com.personal.roleplay.data.local.dao.CharacterUsageRow
import com.personal.roleplay.data.local.dao.ModelUsageRow
import com.personal.roleplay.data.local.dao.SlotUsageRow
import com.personal.roleplay.data.local.dao.UsageTotals
import com.personal.roleplay.data.local.entity.CharacterEntity
import com.personal.roleplay.data.local.entity.MemoryEntity
import com.personal.roleplay.data.local.entity.MemorySourceEntity
import com.personal.roleplay.data.local.entity.MessageEntity
import com.personal.roleplay.data.local.entity.ProviderConfigEntity
import com.personal.roleplay.data.local.entity.RequestLogEntity
import com.personal.roleplay.data.local.entity.SessionEntity
import com.personal.roleplay.data.local.entity.UsageRecordEntity
import com.personal.roleplay.data.local.entity.UserPersonaEntity
import com.personal.roleplay.data.local.entity.WorldBookEntity
import com.personal.roleplay.data.local.entity.WorldBookEntryEntity
import com.personal.roleplay.domain.model.Character
import com.personal.roleplay.domain.model.CharacterUsage
import com.personal.roleplay.domain.model.ChatMessage
import com.personal.roleplay.domain.model.ChatSession
import com.personal.roleplay.domain.model.MemoryItem
import com.personal.roleplay.domain.model.MemoryLayer
import com.personal.roleplay.domain.model.MemoryOrigin
import com.personal.roleplay.domain.model.MemorySourceRef
import com.personal.roleplay.domain.model.MemoryStatus
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.domain.model.ModelUsage
import com.personal.roleplay.domain.model.Protocol
import com.personal.roleplay.domain.model.ProviderCapabilities
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.model.ProviderParams
import com.personal.roleplay.domain.model.PromptBlock
import com.personal.roleplay.domain.model.PromptBlockRecord
import com.personal.roleplay.domain.model.RequestLog
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.model.SlotUsage
import com.personal.roleplay.domain.model.UsageRecord
import com.personal.roleplay.domain.model.UsageTotals as DomainUsageTotals
import com.personal.roleplay.domain.model.UserPersona
import com.personal.roleplay.domain.model.WorldBook
import com.personal.roleplay.domain.model.WorldBookEntry
import com.personal.roleplay.util.Ids
import kotlinx.serialization.json.Json

/** 与实体字段 paramsJson / capabilitiesJson 区分开，避免同名遮蔽 */
private val mapperJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ---------------- Character ----------------

fun CharacterEntity.toDomain(): Character = Character(
    id = id,
    name = name,
    avatarPath = avatarPath,
    tagline = tagline,
    persona = persona,
    personalityTags = personalityTags,
    tone = tone,
    taboos = taboos,
    catchphrase = catchphrase,
    relationPosition = relationPosition,
    greeting = greeting,
    exampleDialogues = exampleDialogues,
    systemPromptTemplate = systemPromptTemplate,
    customSystemPrompt = customSystemPrompt,
    worldBookId = worldBookId,
    accentColorArgb = accentColor,
    providerPreference = providerPreference,
    createdAt = createdAt,
    updatedAt = updatedAt,
    archived = archived,
    builtIn = builtIn
)

fun Character.toEntity(): CharacterEntity = CharacterEntity(
    id = id,
    name = name,
    avatarPath = avatarPath,
    tagline = tagline,
    persona = persona,
    personalityTags = personalityTags,
    tone = tone,
    taboos = taboos,
    catchphrase = catchphrase,
    relationPosition = relationPosition,
    greeting = greeting,
    exampleDialogues = exampleDialogues,
    systemPromptTemplate = systemPromptTemplate,
    customSystemPrompt = customSystemPrompt,
    worldBookId = worldBookId,
    accentColor = accentColorArgb,
    providerPreference = providerPreference,
    createdAt = createdAt,
    updatedAt = updatedAt,
    archived = archived,
    builtIn = builtIn
)

// ---------------- UserPersona ----------------

fun UserPersonaEntity.toDomain(): UserPersona = UserPersona(
    id = id,
    name = name,
    content = content,
    isDefault = isDefault,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun UserPersona.toEntity(): UserPersonaEntity = UserPersonaEntity(
    id = id,
    name = name,
    content = content,
    isDefault = isDefault,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// ---------------- Session / Message ----------------

fun SessionEntity.toDomain(): ChatSession = ChatSession(
    id = id,
    characterId = characterId,
    participantIds = participantIds,
    title = title,
    userPersonaId = userPersonaId,
    userPersona = userPersona,
    summarySegments = summarySegments,
    lastExtractedSeq = lastExtractedSeq,
    lastExtractedAt = lastExtractedAt,
    roundsSinceExtract = roundsSinceExtract,
    groupTurnSize = groupTurnSize,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt
)

fun ChatSession.toEntity(): SessionEntity = SessionEntity(
    id = id,
    characterId = characterId,
    // 单人线也把演员表写全，避免读回时再靠兜底推断
    participantIds = cast,
    title = title,
    userPersonaId = userPersonaId,
    userPersona = userPersona,
    summarySegments = summarySegments,
    lastExtractedSeq = lastExtractedSeq,
    lastExtractedAt = lastExtractedAt,
    roundsSinceExtract = roundsSinceExtract,
    groupTurnSize = groupTurnSize,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt
)

fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    sessionId = sessionId,
    seq = seq,
    role = MessageRole.fromWire(role),
    speakerCharacterId = speakerCharacterId,
    content = content,
    edited = edited,
    providerSnapshot = providerSnapshot,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    createdAt = createdAt
)

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    seq = seq,
    role = role.wire,
    speakerCharacterId = speakerCharacterId,
    content = content,
    edited = edited,
    providerSnapshot = providerSnapshot,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    createdAt = createdAt
)

// ---------------- Memory ----------------

fun MemoryEntity.toDomain(): MemoryItem = MemoryItem(
    id = id,
    characterId = characterId,
    sessionId = sessionId,
    layer = MemoryLayer.fromWire(layer),
    content = content,
    confidence = confidence,
    pinned = pinned,
    origin = MemoryOrigin.fromWire(origin),
    status = MemoryStatus.fromWire(status),
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRecalledAt = lastRecalledAt
)

fun MemoryItem.toEntity(): MemoryEntity = MemoryEntity(
    id = id,
    characterId = characterId,
    sessionId = sessionId,
    layer = layer.wire,
    content = content,
    confidence = confidence,
    pinned = pinned,
    origin = origin.wire,
    status = status.wire,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRecalledAt = lastRecalledAt
)

fun MemorySourceEntity.toDomain(): MemorySourceRef = MemorySourceRef(
    id = id,
    memoryId = memoryId,
    messageId = messageId,
    excerpt = excerpt
)

fun MemorySourceRef.toEntity(): MemorySourceEntity = MemorySourceEntity(
    id = id.ifBlank { Ids.new() },
    memoryId = memoryId,
    messageId = messageId,
    excerpt = excerpt
)

// ---------------- Provider ----------------

fun ProviderConfigEntity.toDomain(apiKey: String): ProviderConfig = ProviderConfig(
    id = id,
    name = name,
    protocol = Protocol.fromWire(protocol),
    baseUrl = baseUrl,
    modelId = modelId,
    params = decodeOrDefault("params", paramsJson) { mapperJson.decodeFromString<ProviderParams>(it) }
        ?: ProviderParams(),
    capabilities = decodeOrDefault("capabilities", capabilitiesJson) {
        mapperJson.decodeFromString<ProviderCapabilities>(it)
    } ?: ProviderCapabilities(),
    slot = Slot.fromWire(slot),
    isDefault = isDefault,
    keyAlias = keyAlias,
    createdAt = createdAt,
    updatedAt = updatedAt,
    apiKey = apiKey
)

/**
 * 解析 JSON 字段，失败时记日志而不是悄悄用默认值。
 *
 * 静默回退会让「参数没生效」变成一个完全无迹可寻的 bug——参数在界面上保存了、
 * 库里也写对了，运行时却莫名其妙全变回默认值，排查时连个线索都没有。
 */
private inline fun <T> decodeOrDefault(
    field: String,
    raw: String,
    decode: (String) -> T
): T? = runCatching { decode(raw) }.onFailure { error ->
    android.util.Log.w("Mappers", "解析 $field 失败，退回默认值。原文=$raw", error)
}.getOrNull()

fun ProviderConfig.toEntity(): ProviderConfigEntity = ProviderConfigEntity(
    id = id,
    name = name,
    protocol = protocol.wire,
    baseUrl = baseUrl,
    modelId = modelId,
    paramsJson = mapperJson.encodeToString(params),
    capabilitiesJson = mapperJson.encodeToString(capabilities),
    slot = slot.wire,
    isDefault = isDefault,
    keyAlias = keyAlias,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// ---------------- WorldBook ----------------

fun WorldBookEntity.toDomain(): WorldBook = WorldBook(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt
)

fun WorldBook.toEntity(): WorldBookEntity = WorldBookEntity(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt
)

fun WorldBookEntryEntity.toDomain(): WorldBookEntry = WorldBookEntry(
    id = id,
    worldBookId = worldBookId,
    keys = keys,
    content = content,
    priority = priority,
    enabled = enabled,
    constant = constant,
    insertionOrder = insertionOrder
)

fun WorldBookEntry.toEntity(): WorldBookEntryEntity = WorldBookEntryEntity(
    id = id,
    worldBookId = worldBookId,
    keys = keys,
    content = content,
    priority = priority,
    enabled = enabled,
    constant = constant,
    insertionOrder = insertionOrder
)

// ---------------- Usage ----------------

fun UsageRecordEntity.toDomain(): UsageRecord = UsageRecord(
    id = id,
    providerId = providerId,
    providerName = providerName,
    modelId = modelId,
    slot = Slot.fromWire(slot),
    sessionId = sessionId,
    characterId = characterId,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    estimatedCost = estimatedCost,
    latencyMs = latencyMs,
    success = success,
    errorMessage = errorMessage,
    createdAt = createdAt
)

fun UsageRecord.toEntity(): UsageRecordEntity = UsageRecordEntity(
    id = id,
    providerId = providerId,
    providerName = providerName,
    modelId = modelId,
    slot = slot.wire,
    sessionId = sessionId,
    characterId = characterId,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    estimatedCost = estimatedCost,
    latencyMs = latencyMs,
    success = success,
    errorMessage = errorMessage,
    createdAt = createdAt
)

fun UsageTotals.toDomain(): DomainUsageTotals = DomainUsageTotals(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    estimatedCost = estimatedCost,
    callCount = callCount
)

fun ModelUsageRow.toDomain(): ModelUsage = ModelUsage(
    modelId = modelId,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    estimatedCost = estimatedCost,
    callCount = callCount
)

fun SlotUsageRow.toDomain(): SlotUsage = SlotUsage(
    slot = Slot.fromWire(slot),
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    estimatedCost = estimatedCost,
    callCount = callCount
)

fun CharacterUsageRow.toDomain(): CharacterUsage = CharacterUsage(
    characterId = characterId,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    estimatedCost = estimatedCost,
    callCount = callCount
)

// ---------------- RequestLog ----------------

fun RequestLogEntity.toDomain(): RequestLog = RequestLog(
    id = id,
    sessionId = sessionId,
    slot = Slot.fromWire(slot),
    providerName = providerName,
    modelId = modelId,
    fullPrompt = fullPrompt,
    blocks = blocksJson,
    rawResponse = rawResponse,
    errorMessage = errorMessage,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    latencyMs = latencyMs,
    success = success,
    createdAt = createdAt
)

fun RequestLog.toEntity(): RequestLogEntity = RequestLogEntity(
    id = id,
    sessionId = sessionId,
    slot = slot.wire,
    providerName = providerName,
    modelId = modelId,
    fullPrompt = fullPrompt,
    blocksJson = blocks,
    rawResponse = rawResponse,
    errorMessage = errorMessage,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    latencyMs = latencyMs,
    success = success,
    createdAt = createdAt
)

/** PromptBlockRecord 由领域层定义，无需转换，只需收集 */
fun List<PromptBlock>.toRecords(): List<PromptBlockRecord> = map { it.toRecord() }
