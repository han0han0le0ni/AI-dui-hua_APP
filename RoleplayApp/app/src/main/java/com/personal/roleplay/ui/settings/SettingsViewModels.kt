package com.personal.roleplay.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.roleplay.domain.model.CharacterUsage
import com.personal.roleplay.domain.model.ModelUsage
import com.personal.roleplay.domain.model.Protocol
import com.personal.roleplay.domain.model.ProbeResult
import com.personal.roleplay.domain.model.ProviderCapabilities
import com.personal.roleplay.domain.model.ProviderConfig
import com.personal.roleplay.domain.provider.ProviderTemplate
import com.personal.roleplay.domain.model.Slot
import com.personal.roleplay.domain.model.SlotUsage
import com.personal.roleplay.domain.model.UsageRecord
import com.personal.roleplay.domain.model.UsageTotals
import com.personal.roleplay.domain.provider.ProviderAdapterFactory
import com.personal.roleplay.domain.provider.ProviderTemplates
import com.personal.roleplay.domain.repository.CharacterRepository
import com.personal.roleplay.domain.repository.ProviderRepository
import com.personal.roleplay.domain.repository.UsageRepository
import com.personal.roleplay.util.Ids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ==================== 接入配置列表 ====================

data class ProviderSettingsUiState(
    val configs: List<ProviderConfig> = emptyList(),
    val templates: List<ProviderTemplate> = ProviderTemplates.all,
    val feedback: String? = null
)

class ProviderSettingsViewModel(
    private val providerRepository: ProviderRepository
) : ViewModel() {

    private val feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProviderSettingsUiState> = combine(
        providerRepository.observeAll(),
        feedback
    ) { configs, message ->
        ProviderSettingsUiState(configs = configs, feedback = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProviderSettingsUiState())

    fun setAsDefault(providerId: String) {
        viewModelScope.launch {
            providerRepository.setAsDefault(providerId)
            feedback.value = "已设为该槽位的默认配置"
        }
    }

    fun delete(providerId: String) {
        viewModelScope.launch {
            providerRepository.delete(providerId)
            feedback.value = "已删除配置与对应的 Key"
        }
    }

    fun consumeFeedback() {
        feedback.value = null
    }
}

// ==================== 接入配置编辑 ====================

data class ProviderEditorUiState(
    val config: ProviderConfig? = null,
    val isNew: Boolean = false,
    val models: List<String> = emptyList(),
    val isProbing: Boolean = false,
    val probeMessage: String? = null,
    val probeSucceeded: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null
)

/** 表单主体：改这些字段不该触发探测，和下面的进度状态分开合并 */
private data class ProviderEditorForm(
    val config: ProviderConfig?,
    val isNew: Boolean,
    val models: List<String>
)

/** 探测与保存的进度、结果 */
private data class ProviderEditorStatus(
    val isProbing: Boolean,
    val probeMessage: String?,
    val probeSucceeded: Boolean,
    val saved: Boolean,
    val error: String?
)

class ProviderEditorViewModel(
    private val providerRepository: ProviderRepository
) : ViewModel() {

    private val draft = MutableStateFlow<ProviderConfig?>(null)
    private val isNew = MutableStateFlow(false)
    private val models = MutableStateFlow<List<String>>(emptyList())
    private val isProbing = MutableStateFlow(false)
    private val probeMessage = MutableStateFlow<String?>(null)
    private val probeSucceeded = MutableStateFlow(false)
    private val saved = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProviderEditorUiState> = combine(
        combine(draft, isNew, models) { config, new, modelList ->
            ProviderEditorForm(config, new, modelList)
        },
        combine(isProbing, probeMessage, probeSucceeded, saved, error) {
                probing, probe, probeOk, savedFlag, errorMessage ->
            ProviderEditorStatus(probing, probe, probeOk, savedFlag, errorMessage)
        }
    ) { form, status ->
        ProviderEditorUiState(
            config = form.config,
            isNew = form.isNew,
            models = form.models,
            isProbing = status.isProbing,
            probeMessage = status.probeMessage,
            probeSucceeded = status.probeSucceeded,
            saved = status.saved,
            error = status.error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProviderEditorUiState())

    /**
     * @param providerId 既可能是已有配置的 id，也可能是模板 id（新建时走路由传模板）。
     *                   两者都不是时退回第一个模板。
     */
    fun bind(providerId: String?) {
        viewModelScope.launch {
            val existing = providerId?.let { providerRepository.getById(it) }
            val initial = existing
                ?: providerId?.let { ProviderTemplates.findById(it) }?.toDraftConfig()
                ?: ProviderTemplates.all.first().toDraftConfig()

            draft.value = initial
            isNew.value = existing == null
        }
    }

    fun update(transform: (ProviderConfig) -> ProviderConfig) {
        draft.update { current -> current?.let(transform) }
        saved.value = false
        probeSucceeded.value = false
        probeMessage.value = null
    }

    /** 选模板：预填协议与地址，用户只需粘 Key、选模型 */
    fun applyTemplate(template: ProviderTemplate) {
        update { current ->
            template.toDraftConfig().copy(
                id = current.id,
                name = current.name.takeIf { it.isNotBlank() } ?: template.displayName,
                keyAlias = current.keyAlias,
                apiKey = current.apiKey,
                slot = current.slot,
                isDefault = current.isDefault,
                createdAt = current.createdAt
            )
        }
    }

    /** 测试连接：鉴权 → 拉模型列表 → 能力探测 */
    fun probe() {
        val current = draft.value ?: return
        // 本地服务（Ollama / LM Studio）不需要 Key
        if (current.apiKey.isBlank() && current.protocol != Protocol.OLLAMA) {
            error.value = "请先填写 API Key"
            return
        }

        viewModelScope.launch {
            isProbing.value = true
            probeMessage.value = null
            error.value = null

            val result: ProbeResult = runCatching {
                ProviderAdapterFactory.create(current.protocol).probe(current)
            }.getOrDefault(ProbeResult(success = false, message = "连接失败：请检查地址与 Key"))

            isProbing.value = false
            probeSucceeded.value = result.success
            probeMessage.value = result.message

            if (result.success) {
                models.value = result.models
                val capabilities = result.capabilities
                    ?: ProviderCapabilities(probedAt = System.currentTimeMillis())
                update { config -> config.copy(capabilities = capabilities) }
            }
        }
    }

    fun save() {
        val current = draft.value ?: return
        val name = current.name.trim()

        when {
            name.isBlank() -> error.value = "请给这个配置起个别名"
            current.baseUrl.isBlank() -> error.value = "请填写接口地址"
            current.modelId.isBlank() -> error.value = "请选择或填写模型标识"
            else -> viewModelScope.launch {
                providerRepository.save(
                    current.copy(
                        name = name,
                        baseUrl = current.baseUrl.trim().trimEnd('/'),
                        modelId = current.modelId.trim(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                saved.value = true
                error.value = null
            }
        }
    }

    fun consumeError() {
        error.value = null
    }
}

private fun ProviderTemplate.toDraftConfig(): ProviderConfig = ProviderConfig(
    id = Ids.new(),
    name = displayName,
    protocol = protocol,
    baseUrl = baseUrl,
    modelId = defaultModel,
    params = com.personal.roleplay.domain.model.ProviderParams(),
    capabilities = ProviderCapabilities(contextWindow = contextWindow),
    slot = Slot.MAIN_DIALOGUE,
    isDefault = false,
    keyAlias = com.personal.roleplay.data.security.SecureKeyVault.newAlias(),
    createdAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
    apiKey = ""
)

// ==================== 用量 ====================

enum class UsageRange(val label: String, val days: Long) {
    TODAY("今天", 1L),
    WEEK("近 7 天", 7L),
    MONTH("近 30 天", 30L),
    ALL("全部", 3650L)
}

data class UsageUiState(
    val range: UsageRange = UsageRange.MONTH,
    val totals: UsageTotals? = null,
    val byModel: List<ModelUsage> = emptyList(),
    val bySlot: List<SlotUsage> = emptyList(),
    val byCharacter: List<CharacterUsage> = emptyList(),
    val characterNames: Map<String, String> = emptyMap(),
    val recent: List<UsageRecord> = emptyList()
)

/** 用量页的横向聚合结果，只为了让 combine 保持在具名重载内 */
private data class UsageAggregates(
    val totals: UsageTotals,
    val byModel: List<ModelUsage>,
    val bySlot: List<SlotUsage>,
    val byCharacter: List<CharacterUsage>,
    val recent: List<UsageRecord>
)

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModel(
    private val usageRepository: UsageRepository,
    private val characterRepository: CharacterRepository
) : ViewModel() {

    private val range = MutableStateFlow(UsageRange.MONTH)

    /**
     * 角色 id → 名字。用持续流而不是一次性查询：
     * 用量页里的角色名会跟着角色改名/删除一起更新。
     */
    private val namesFlow: Flow<Map<String, String>> = characterRepository.observeAll()
        .map { characters -> characters.associate { it.id to it.name } }

    val uiState: StateFlow<UsageUiState> = range.flatMapLatest { selected ->
        val from = System.currentTimeMillis() - selected.days * 24 * 60 * 60 * 1000L
        // combine 的具名重载最多只到 5 个流；6 个会退化成 Array<Any?> 版本，
        // 参数全变 Any? 无法推断类型。所以先横向合成一组，再和名字映射合并。
        combine(
            combine(
                usageRepository.observeTotalsSince(from),
                usageRepository.observeByModel(from),
                usageRepository.observeBySlot(from),
                usageRepository.observeByCharacter(from),
                usageRepository.observeRecent(from, 50)
            ) { totals, byModel, bySlot, byCharacter, recent ->
                UsageAggregates(totals, byModel, bySlot, byCharacter, recent)
            },
            namesFlow
        ) { aggregates, names ->
            UsageUiState(
                range = selected,
                totals = aggregates.totals,
                byModel = aggregates.byModel,
                bySlot = aggregates.bySlot,
                byCharacter = aggregates.byCharacter,
                characterNames = names,
                recent = aggregates.recent
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUiState())

    fun selectRange(next: UsageRange) {
        range.value = next
    }
}
