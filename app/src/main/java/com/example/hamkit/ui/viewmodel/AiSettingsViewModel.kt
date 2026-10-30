package com.example.hamkit.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ai.AiClientFactory
import com.example.hamkit.data.ai.AiConfig
import com.example.hamkit.data.ai.AiConfigStore
import com.example.hamkit.data.ai.AiKeyStore
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
    private val sessionStore = AiSessionStore(application)

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { configStore.load() }
            val masked = withContext(Dispatchers.IO) { keyStore.masked() }
            _uiState.update { it.copy(config = config, keyMasked = masked) }
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

    fun setAllowCleartext(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(allowCleartext = enabled), error = null) }
    }

    /** 保存配置；URL 不合法时返回 false 并通过 [AiSettingsUiState.error] 提示。 */
    fun save(onSaved: (Boolean) -> Unit = {}) {
        val config = _uiState.value.config.normalized()
        val reason = AiNetworkPolicy.validate(config.baseUrl, config.allowCleartext)
        if (reason != null) {
            _uiState.update { it.copy(config = config, error = reason) }
            onSaved(false)
            return
        }
        if (config.model.isBlank() || config.baseUrl.isBlank()) {
            _uiState.update { it.copy(config = config, error = "Base URL 与模型不能为空") }
            onSaved(false)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { configStore.save(config) }
            _uiState.update {
                it.copy(config = config, notice = "设置已保存", error = null)
            }
            onSaved(true)
        }
    }

    fun saveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val masked = withContext(Dispatchers.IO) {
                keyStore.save(trimmed)
                keyStore.masked()
            }
            _uiState.update { it.copy(keyMasked = masked, notice = "API Key 已加密保存", error = null) }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { keyStore.clear() }
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
            val apiKey = withContext(Dispatchers.IO) { keyStore.load() }
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
            val apiKey = withContext(Dispatchers.IO) { keyStore.load() }
            if (apiKey.isNullOrBlank()) {
                _uiState.update {
                    it.copy(fetchingModels = false, modelsError = "API Key 读取失败，请重新保存")
                }
                return@launch
            }
            AiModelList.fetch(config, apiKey)
                .onSuccess { models ->
                    _uiState.update {
                        it.copy(fetchingModels = false, models = models, modelsError = null)
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
