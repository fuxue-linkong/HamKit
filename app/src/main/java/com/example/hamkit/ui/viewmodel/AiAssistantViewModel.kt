package com.example.hamkit.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ai.AiBlock
import com.example.hamkit.data.ai.AiChatMessage
import com.example.hamkit.data.ai.AiClientFactory
import com.example.hamkit.data.ai.AiConfig
import com.example.hamkit.data.ai.AiConfigStore
import com.example.hamkit.data.ai.AiError
import com.example.hamkit.data.ai.AiErrorKind
import com.example.hamkit.data.ai.AiEvent
import com.example.hamkit.data.ai.AiKeyStore
import com.example.hamkit.data.ai.AiMessage
import com.example.hamkit.data.ai.AiNetworkPolicy
import com.example.hamkit.data.ai.AiRequest
import com.example.hamkit.data.ai.AiRole
import com.example.hamkit.data.ai.AiSession
import com.example.hamkit.data.ai.AiSessionStore
import com.example.hamkit.data.ai.AiUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 对话列表里的一条消息（UI 模型，与持久化模型分开）。 */
data class AiChatMessageUi(
    val id: String,
    val role: AiRole,
    val text: String,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long? = null,
)

/** 对话页状态。 */
data class AiChatUiState(
    val messages: List<AiChatMessageUi> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val config: AiConfig = AiConfig(),
    val hasKey: Boolean = false,
    val sessionId: String? = null,
    val initialized: Boolean = false,
    /** 顶层提示（未配置 Key、URL 非法、网络错误等），展示后可清空。 */
    val error: String? = null,
)

/**
 * AI 对话状态机。
 *
 * 生命周期：application 级（pager 切页不中断请求）。
 * 流式更新做了 48ms 节流，避免每个 token 触发一次整页重组。
 *
 * 当前阶段（P0）只做纯文本对话；工具调用的事件模型已就绪，P1 接入执行器。
 */
class AiAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = AiConfigStore(application)
    private val keyStore = AiKeyStore(application)
    private val sessionStore = AiSessionStore(application)

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var sessionCreatedAt = System.currentTimeMillis()

    init {
        viewModelScope.launch { initialize() }
    }

    private suspend fun initialize() {
        val config = withContext(Dispatchers.IO) { configStore.load() }
        val hasKey = withContext(Dispatchers.IO) { keyStore.hasKey() }
        _uiState.update { it.copy(config = config, hasKey = hasKey, initialized = true) }

        // 恢复最近一次会话（仅当用户开启了历史保存）
        if (!config.saveHistory) return
        val latest = sessionStore.summaries().maxByOrNull { it.updatedAt } ?: return
        val session = sessionStore.load(latest.id) ?: return
        if (session.messages.isEmpty()) return
        sessionCreatedAt = session.createdAt
        _uiState.update {
            it.copy(
                sessionId = session.id,
                messages = session.messages.map { message -> message.toUi() },
            )
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    /** 从设置页返回后刷新配置/Key 状态。 */
    fun reloadConfig() {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { configStore.load() }
            val hasKey = withContext(Dispatchers.IO) { keyStore.hasKey() }
            _uiState.update { it.copy(config = config, hasKey = hasKey) }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    fun newSession() {
        streamJob?.cancel()
        streamJob = null
        sessionCreatedAt = System.currentTimeMillis()
        _uiState.update {
            it.copy(messages = emptyList(), sessionId = null, error = null, isStreaming = false)
        }
    }

    fun send() {
        val state = _uiState.value
        if (state.isStreaming) return
        val text = state.input.trim()
        if (text.isEmpty()) return
        if (!state.hasKey) {
            _uiState.update { it.copy(error = "还没有配置 API Key，请先到「AI 设置」填写") }
            return
        }
        AiNetworkPolicy.validate(state.config.baseUrl, state.config.allowCleartext)?.let { reason ->
            _uiState.update { it.copy(error = reason) }
            return
        }

        val userMessage = AiChatMessageUi(
            id = UUID.randomUUID().toString(),
            role = AiRole.USER,
            text = text,
        )
        val assistantMessage = AiChatMessageUi(
            id = UUID.randomUUID().toString(),
            role = AiRole.ASSISTANT,
            text = "",
            isStreaming = true,
        )
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + assistantMessage,
                input = "",
                isStreaming = true,
                error = null,
            )
        }
        streamJob = viewModelScope.launch { stream(text) }
    }

    /** 停止生成：保留已生成文本。 */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    /** 重试最后一条：去掉末尾的 user + assistant，用原文重新发送。 */
    fun retry() {
        val state = _uiState.value
        if (state.isStreaming) return
        val lastUserIndex = state.messages.indexOfLast { it.role == AiRole.USER }
        if (lastUserIndex < 0) return
        val prompt = state.messages[lastUserIndex].text
        _uiState.update {
            it.copy(
                messages = state.messages.subList(0, lastUserIndex),
                input = prompt,
                error = null,
            )
        }
        send()
    }

    fun clearHistory() {
        viewModelScope.launch {
            sessionStore.clear()
            newSession()
        }
    }

    // ── 流式请求 ────────────────────────────────────────────────────────────

    private suspend fun stream(prompt: String) {
        val config = _uiState.value.config
        val apiKey = withContext(Dispatchers.IO) { keyStore.load() }
        if (apiKey.isNullOrBlank()) {
            finishStreaming("", null, "API Key 读取失败，请重新保存", 0L)
            return
        }

        val request = buildRequest()
        val startedAt = System.currentTimeMillis()
        val text = StringBuilder()
        var usage: AiUsage? = null
        var errorText: String? = null
        var lastFlush = 0L

        try {
            AiClientFactory.create(config.protocol).stream(config, request, apiKey).collect { event ->
                when (event) {
                    is AiEvent.TextDelta -> {
                        text.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= STREAM_FLUSH_INTERVAL_MS) {
                            lastFlush = now
                            updateStreamingText(text.toString())
                        }
                    }

                    is AiEvent.Usage -> usage = AiUsage(event.input, event.output)

                    is AiEvent.Failed -> errorText = humanize(event.error)

                    // P0 不执行工具；若模型仍然返回工具调用，按"能力不支持"提示
                    is AiEvent.ToolCallStarted -> errorText = "当前模型返回了工具调用，但工具能力尚未启用"

                    else -> Unit
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            errorText = humanize(AiError.fromThrowable(t))
        } finally {
            finishStreaming(
                text = text.toString(),
                usage = usage,
                error = errorText,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    private fun buildRequest(): AiRequest {
        val history = _uiState.value.messages
            .filter { it.role == AiRole.USER || it.role == AiRole.ASSISTANT }
            .filter { it.text.isNotBlank() && it.error == null && !it.isStreaming }
            .takeLast(MAX_CONTEXT_MESSAGES)
            .map { AiMessage(it.role, listOf(AiBlock.Text(it.text))) }
        return AiRequest(system = SYSTEM_PROMPT, messages = history)
    }

    private fun updateStreamingText(value: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.isStreaming) message.copy(text = value) else message
                },
            )
        }
    }

    private suspend fun finishStreaming(
        text: String,
        usage: AiUsage?,
        error: String?,
        elapsedMs: Long,
    ) {
        _uiState.update { state ->
            state.copy(
                isStreaming = false,
                messages = state.messages.map { message ->
                    if (!message.isStreaming) return@map message
                    message.copy(
                        text = text,
                        isStreaming = false,
                        error = error,
                        elapsedMs = elapsedMs,
                    )
                },
                error = if (text.isBlank() && error != null) error else null,
            )
        }
        // 取消时协程已处于 cancelled 状态，持久化必须用 NonCancellable
        withContext(NonCancellable) {
            persistSession(usage)
        }
        streamJob = null
    }

    private suspend fun persistSession(usage: AiUsage?) {
        val state = _uiState.value
        if (!state.config.saveHistory) return
        val messages = state.messages
            .filter { it.text.isNotBlank() || it.error != null }
            .map { it.toRecord(usage) }
        if (messages.isEmpty()) return

        val id = state.sessionId ?: UUID.randomUUID().toString().also { newId ->
            _uiState.update { it.copy(sessionId = newId) }
        }
        val title = messages.firstOrNull { it.role == AiRole.USER }?.text
            ?.replace('\n', ' ')
            ?.take(24)
            .orEmpty()
        val session = AiSession(
            id = id,
            title = title.ifBlank { "新对话" },
            createdAt = sessionCreatedAt,
            updatedAt = System.currentTimeMillis(),
            messages = messages,
        )
        withContext(Dispatchers.IO) {
            runCatching { sessionStore.save(session) }
        }
    }

    private fun humanize(error: AiError): String = when (error.kind) {
        AiErrorKind.AUTH -> "API Key 无效或没有权限（HTTP ${error.status ?: "?"}）"
        AiErrorKind.RATE_LIMIT -> "请求过于频繁，请稍后再试"
        AiErrorKind.INVALID_REQUEST -> "请求被服务商拒绝：${error.message}"
        AiErrorKind.SERVER -> "服务商暂时不可用：${error.message}"
        AiErrorKind.NETWORK -> "网络不可用：${error.message}"
        AiErrorKind.TIMEOUT -> "请求超时，请检查网络后重试"
        AiErrorKind.PARSE -> "响应解析失败：${error.message}"
        AiErrorKind.NO_CONFIG -> error.message
    }

    private fun AiChatMessageUi.toRecord(usage: AiUsage?): AiChatMessage = AiChatMessage(
        id = id,
        role = role,
        text = text,
        createdAt = System.currentTimeMillis(),
        usage = usage,
        elapsedMs = elapsedMs,
        error = error,
    )

    private fun AiChatMessage.toUi(): AiChatMessageUi = AiChatMessageUi(
        id = id,
        role = role,
        text = text,
        error = error,
        elapsedMs = elapsedMs,
    )

    private companion object {
        /** 节流间隔：每个 token 都更新会导致整页重组。 */
        const val STREAM_FLUSH_INTERVAL_MS = 48L
        const val MAX_CONTEXT_MESSAGES = 24

        /** P0 的系统提示：明确"不能调用工具"，避免模型编造工具结果。 */
        val SYSTEM_PROMPT = """
            你是 HamKit 内置的业余无线电助手。HamKit 是一个 Android 端的业余无线电工具箱，
            提供双区定位（CQ/ITU 分区与 Maidenhead 网格）、卫星过境预测、SSTV 收发、
            CW 摩尔斯、FT8、APRS、QSO 日志等功能。

            回答要求：
            1. 只回答业余无线电相关或与之明显相关的问题；不确定的知识直接说明不确定，不要编造。
            2. 不要编造卫星过境时间、频率、转发器状态等实时数据；这些数据需要 App 内的卫星模块查询。
            3. 当前版本还不能调用 App 内工具，因此不要声称"已为你查询/已打开页面"。
            4. 中文提问用中文回答，英文提问用英文回答；保持简洁，需要时用列表与 Markdown。
        """.trimIndent()
    }
}
