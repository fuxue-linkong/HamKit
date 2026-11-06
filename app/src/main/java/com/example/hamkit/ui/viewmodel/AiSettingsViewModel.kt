package com.example.hamkit.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ai.AiClientFactory
import com.example.hamkit.data.ai.AiConfig
import com.example.hamkit.data.ai.AiConfigStore
import com.example.hamkit.data.ai.AiKeyStore
import com.example.hamkit.data.ai.AiModelEntry
import com.example.hamkit.data.ai.AiModelProfile
import com.example.hamkit.data.ai.AiProfileStore
import com.example.hamkit.data.ai.AiErrorKind
import com.example.hamkit.data.ai.AiModelList
import com.example.hamkit.data.ai.AiModelListException
import com.example.hamkit.data.ai.AiNetworkPolicy
import com.example.hamkit.data.ai.AiPreset
import com.example.hamkit.data.ai.AiProbeResult
import com.example.hamkit.data.ai.AiProtocol
import com.example.hamkit.data.ai.AiSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.withContext

/** AI 设置页状态。 */
data class AiSettingsUiState(
    val config: AiConfig = AiConfig(),
    val keyMasked: String? = null,
    val testing: Boolean = false,
    val probe: AiProbeResult? = null,
    val notice: String? = null,
    val error: String? = null,
    /** 「获取模型列表」返回的候选模型。 */
    val models: List<String> = emptyList(),
    val fetchingModels: Boolean = false,
    val modelsError: String? = null,
    /** 已配置的模型列表。 */
    val profiles: List<AiModelProfile> = emptyList(),
    /** 当前编辑/使用的模型 id。 */
    val activeProfileId: String = AiModelProfile.DEFAULT_ID,
    /** 正在编辑的模型条目（保存前只改内存）。 */
    val editingModels: List<AiModelEntry> = emptyList(),
    /** 正在编辑的模型条目 id。 */
    val editingModelId: String? = null,
    /** 保存中（按钮禁用/显示保存中）。 */
    val saving: Boolean = false,
) {
    val hasKey: Boolean get() = keyMasked != null
}

/**
 * AI 设置页状态机：修改只落在内存，点"保存"才持久化；
 * 连接测试直接使用当前内存配置 + 已保存的 Key。
 */
class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = AiConfigStore(application)
    private val keyStore = AiKeyStore(application)
    private val profileStore = AiProfileStore(application)
    private val sessionStore = AiSessionStore(application)

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val legacy = withContext(Dispatchers.IO) { configStore.load() }
            val profile = withContext(Dispatchers.IO) {
                profileStore.ensureMigrated(legacy, keyStore.hasKey())
            }
            val masked = withContext(Dispatchers.IO) { keyStoreFor(profile.id).masked() }
            _uiState.update {
                it.copy(
                    config = profile.applyTo(legacy),
                    keyMasked = masked,
                    profiles = profileStore.list(),
                    activeProfileId = profile.id,
                    editingModels = profile.models,
                    editingModelId = profile.currentEntry?.id,
                )
            }
        }
    }

    private fun keyStoreFor(profileId: String): AiKeyStore =
        AiKeyStore(getApplication(), if (profileId == AiModelProfile.DEFAULT_ID) "" else profileId)

    /** 把当前表单写回当前 profile（切换/新建前调用，避免丢改动）。 */
    private fun persistCurrentProfile() {
        val state = _uiState.value
        val current = state.profiles.firstOrNull { it.id == state.activeProfileId } ?: return
        val models = state.editingModels.filter { it.modelId.isNotBlank() }
            .ifEmpty { current.models }
        val selected = state.editingModelId?.takeIf { id -> models.any { it.id == id } }
            ?: models.firstOrNull()?.id
        profileStore.upsert(
            current.copy(
                preset = state.config.preset,
                protocol = state.config.protocol,
                baseUrl = state.config.baseUrl,
                models = models,
                selectedModelId = selected,
                temperature = state.config.temperature,
                maxOutputTokens = state.config.maxOutputTokens,
            ),
        )
    }

    fun addModel() {
        val entry = AiModelEntry(
            id = "m_" + UUID.randomUUID().toString().take(8),
            modelId = "",
        )
        _uiState.update {
            it.copy(
                editingModels = it.editingModels + entry,
                editingModelId = entry.id,
                error = null,
            )
        }
    }

    fun updateModel(entryId: String, modelId: String, displayName: String) {
        _uiState.update {
            it.copy(
                editingModels = it.editingModels.map { entry ->
                    if (entry.id == entryId) {
                        entry.copy(modelId = modelId, displayName = displayName)
                    } else {
                        entry
                    }
                },
            )
        }
    }

    fun deleteModel(entryId: String) {
        val state = _uiState.value
        if (state.editingModels.size <= 1) {
            _uiState.update { it.copy(error = "至少保留一个模型") }
            return
        }
        val remaining = state.editingModels.filterNot { it.id == entryId }
        _uiState.update {
            it.copy(
                editingModels = remaining,
                editingModelId = if (state.editingModelId == entryId) {
                    remaining.first().id
                } else {
                    state.editingModelId
                },
                error = null,
            )
        }
    }

    fun selectModel(entryId: String) {
        _uiState.update { it.copy(editingModelId = entryId) }
    }

    /** 取消编辑：重新载入当前 profile 的已保存值。 */
    fun resetToSaved() {
        val profiles = profileStore.list()
        val active = profiles.firstOrNull { it.id == _uiState.value.activeProfileId }
            ?: profileStore.active(profiles)
        viewModelScope.launch {
            val legacy = withContext(Dispatchers.IO) { configStore.load() }
            val masked = withContext(Dispatchers.IO) { keyStoreFor(active.id).masked() }
            _uiState.update {
                it.copy(
                    config = active.applyTo(legacy),
                    keyMasked = masked,
                    profiles = profiles,
                    activeProfileId = active.id,
                    editingModels = active.models,
                    editingModelId = active.currentEntry?.id,
                    probe = null,
                    models = emptyList(),
                    error = null,
                )
            }
        }
    }

    /** 修改当前 profile 的显示名称。 */
    fun setProfileName(value: String) {
        val state = _uiState.value
        val current = state.profiles.firstOrNull { it.id == state.activeProfileId } ?: return
        val updated = current.copy(name = value)
        profileStore.upsert(updated)
        _uiState.update {
            it.copy(profiles = it.profiles.map { p -> if (p.id == updated.id) updated else p })
        }
    }

    fun selectProfile(id: String) {
        val state = _uiState.value
        if (id == state.activeProfileId) return
        persistCurrentProfile()
        val profiles = profileStore.list()
        val target = profiles.firstOrNull { it.id == id } ?: return
        profileStore.setActive(id)
        viewModelScope.launch {
            val masked = withContext(Dispatchers.IO) { keyStoreFor(id).masked() }
            _uiState.update {
                it.copy(
                    config = target.applyTo(it.config),
                    keyMasked = masked,
                    activeProfileId = id,
                    profiles = profiles,
                    editingModels = target.models,
                    editingModelId = target.currentEntry?.id,
                    probe = null,
                    models = emptyList(),
                    error = null,
                    notice = "已保存当前修改，并切换到「${target.name}」",
                )
            }
        }
    }

    fun addProfile() {
        persistCurrentProfile()
        val state = _uiState.value
        val id = "p_" + UUID.randomUUID().toString().take(8)
        val name = "模型 ${state.profiles.size + 1}"
        profileStore.upsert(AiModelProfile.fromConfig(id, name, state.config))
        profileStore.setActive(id)
        _uiState.update {
            it.copy(
                profiles = profileStore.list(),
                activeProfileId = id,
                keyMasked = null,
                editingModels = profileStore.active().models,
                editingModelId = profileStore.active().currentEntry?.id,
                notice = "已保存当前修改，新建「$name」，请填写 Key",
                probe = null,
                models = emptyList(),
                error = null,
            )
        }
    }

    fun deleteProfile(id: String) {
        if (!profileStore.delete(id)) {
            _uiState.update { it.copy(error = "至少保留一个模型") }
            return
        }
        viewModelScope.launch {
            val profiles = withContext(Dispatchers.IO) { profileStore.list() }
            val active = withContext(Dispatchers.IO) { profileStore.active(profiles) }
            val masked = withContext(Dispatchers.IO) { keyStoreFor(active.id).masked() }
            _uiState.update {
                it.copy(
                    profiles = profiles,
                    activeProfileId = active.id,
                    config = active.applyTo(it.config),
                    keyMasked = masked,
                    editingModels = active.models,
                    editingModelId = active.currentEntry?.id,
                    notice = "已删除模型",
                    error = null,
                )
            }
        }
    }

    fun selectPreset(preset: AiPreset) {
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(
                    preset = preset,
                    protocol = preset.protocol,
                    baseUrl = preset.baseUrl.ifBlank { state.config.baseUrl },
                    model = preset.defaultModel.ifBlank { state.config.model },
                ),
                probe = null,
                error = null,
            )
        }
    }

    fun selectProtocol(protocol: AiProtocol) {
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(protocol = protocol),
                probe = null,
                error = null,
            )
        }
    }

    fun setBaseUrl(value: String) {
        _uiState.update {
            it.copy(config = it.config.copy(baseUrl = value), probe = null, error = null)
        }
    }

    fun setModel(value: String) {
        _uiState.update { it.copy(config = it.config.copy(model = value), probe = null, error = null) }
    }

    fun setTemperature(value: Float) {
        _uiState.update { it.copy(config = it.config.copy(temperature = value)) }
    }

    fun setMaxOutputTokens(value: Int) {
        _uiState.update { it.copy(config = it.config.copy(maxOutputTokens = value)) }
    }

    fun setSaveHistory(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(saveHistory = enabled)) }
    }

    fun setConfirmSideEffects(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(confirmSideEffects = enabled)) }
    }

    fun toggleTool(name: String, enabled: Boolean) {
        _uiState.update { state ->
            val disabled = state.config.disabledTools.toMutableSet()
            if (enabled) disabled.remove(name) else disabled.add(name)
            state.copy(config = state.config.copy(disabledTools = disabled))
        }
    }

    fun setSystemPrompt(value: String) {
        _uiState.update { it.copy(config = it.config.copy(systemPrompt = value)) }
    }

    fun setAllowCleartext(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(allowCleartext = enabled), error = null) }
    }

    /** 保存配置；URL 不合法时返回 false 并通过 [AiSettingsUiState.error] 提示。 */
    fun save(onSaved: (Boolean) -> Unit = {}) {
        if (_uiState.value.saving) return
        val config = _uiState.value.config.normalized()
        val reason = AiNetworkPolicy.validate(config.baseUrl, config.allowCleartext)
        if (reason != null) {
            _uiState.update { it.copy(config = config, error = reason) }
            onSaved(false)
            return
        }
        if (config.baseUrl.isBlank()) {
            _uiState.update { it.copy(config = config, error = "Base URL 不能为空") }
            onSaved(false)
            return
        }
        val models = _uiState.value.editingModels.filter { it.modelId.isNotBlank() }
        if (models.isEmpty()) {
            _uiState.update { it.copy(config = config, error = "至少填写一个模型 ID") }
            onSaved(false)
            return
        }
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    configStore.save(config)
                    persistCurrentProfile()
                }
                val profiles = withContext(Dispatchers.IO) { profileStore.list() }
                val active = profileStore.active(profiles)
                _uiState.update {
                    it.copy(
                        config = config,
                        profiles = profiles,
                        editingModels = active.models,
                        editingModelId = active.currentEntry?.id,
                        saving = false,
                        notice = "设置已保存",
                        error = null,
                    )
                }
                onSaved(true)
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(saving = false, error = t.message ?: "保存失败")
                }
                onSaved(false)
            }
        }
    }

    fun saveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val masked = withContext(Dispatchers.IO) {
                keyStoreFor(_uiState.value.activeProfileId).save(trimmed)
                keyStoreFor(_uiState.value.activeProfileId).masked()
            }
            _uiState.update { it.copy(keyMasked = masked, notice = "API Key 已加密保存", error = null) }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { keyStoreFor(_uiState.value.activeProfileId).clear() }
            _uiState.update { it.copy(keyMasked = null, notice = "API Key 已清除", probe = null) }
        }
    }

    fun testConnection() {
        val state = _uiState.value
        if (state.testing) return
        val config = state.config.normalized()
        val reason = AiNetworkPolicy.validate(config.baseUrl, config.allowCleartext)
        if (reason != null) {
            _uiState.update { it.copy(error = reason) }
            return
        }
        if (!state.hasKey) {
            _uiState.update { it.copy(error = "请先保存 API Key") }
            return
        }
        _uiState.update { it.copy(testing = true, probe = null, error = null) }
        viewModelScope.launch {
            val apiKey = withContext(Dispatchers.IO) { keyStoreFor(_uiState.value.activeProfileId).load() }
            if (apiKey.isNullOrBlank()) {
                _uiState.update { it.copy(testing = false, error = "API Key 读取失败，请重新保存") }
                return@launch
            }
            val result = runCatching {
                AiClientFactory.create(config.protocol).probe(config, apiKey)
            }.getOrElse { throwable ->
                AiProbeResult(
                    ok = false,
                    latencyMs = 0,
                    error = com.example.hamkit.data.ai.AiError.fromThrowable(throwable),
                )
            }
            _uiState.update { it.copy(testing = false, probe = result) }
        }
    }

    /** 拉取服务商模型列表（只读；失败不影响当前配置）。 */
    fun fetchModels() {
        val state = _uiState.value
        if (state.fetchingModels) return
        val config = state.config.normalized()
        AiNetworkPolicy.validate(config.baseUrl, config.allowCleartext)?.let { reason ->
            _uiState.update { it.copy(error = reason) }
            return
        }
        if (!state.hasKey) {
            _uiState.update { it.copy(error = "请先保存 API Key") }
            return
        }
        _uiState.update { it.copy(fetchingModels = true, modelsError = null, error = null) }
        viewModelScope.launch {
            val apiKey = withContext(Dispatchers.IO) { keyStoreFor(_uiState.value.activeProfileId).load() }
            if (apiKey.isNullOrBlank()) {
                _uiState.update {
                    it.copy(fetchingModels = false, modelsError = "API Key 读取失败，请重新保存")
                }
                return@launch
            }
            AiModelList.fetch(config, apiKey)
                .onSuccess { models ->
                    _uiState.update { state ->
                        // 自动填充：先填已有的空条目，再追加新模型（保留已有显示名称与选中）
                        val working = state.editingModels.toMutableList()
                        val pending = models.toMutableList()
                        working.indices
                            .filter { working[it].modelId.isBlank() }
                            .forEach { index ->
                                val next = pending.firstOrNull() ?: return@forEach
                                working[index] = working[index].copy(modelId = next)
                                pending.remove(next)
                            }
                        val existingIds = working.map { it.modelId }.toSet()
                        pending.filter { it !in existingIds }.forEach { modelId ->
                            working += AiModelEntry(
                                id = "m_" + UUID.randomUUID().toString().take(8),
                                modelId = modelId,
                            )
                        }
                        val selected = state.editingModelId?.takeIf { id -> working.any { it.id == id } }
                            ?: working.firstOrNull()?.id
                        state.copy(
                            fetchingModels = false,
                            models = models,
                            modelsError = null,
                            editingModels = working,
                            editingModelId = selected,
                            notice = "已获取 ${models.size} 个模型",
                        )
                    }
                }
                .onFailure { throwable ->
                    val message = (throwable as? AiModelListException)?.error?.let { error ->
                        when (error.kind) {
                            AiErrorKind.AUTH -> "API Key 无效或没有权限"
                            AiErrorKind.RATE_LIMIT -> "请求过于频繁，请稍后再试"
                            AiErrorKind.NETWORK -> "网络不可用：${error.message}"
                            else -> error.message
                        }
                    } ?: throwable.message ?: "获取模型列表失败"
                    _uiState.update { it.copy(fetchingModels = false, modelsError = message) }
                }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sessionStore.clear() }
            _uiState.update { it.copy(notice = "对话历史已清除") }
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }
}
