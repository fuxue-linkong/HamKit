package com.example.hamkit.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ai.AiAttachment
import com.example.hamkit.data.ai.AiAttachmentKind
import com.example.hamkit.data.ai.AiAttachmentParser
import com.example.hamkit.data.ai.AiAttachmentStatus
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
import com.example.hamkit.data.ai.AiPrompts
import com.example.hamkit.data.ai.AiRequest
import com.example.hamkit.data.ai.AiRole
import com.example.hamkit.data.ai.AiSession
import com.example.hamkit.data.ai.AiSessionSummary
import com.example.hamkit.data.ai.AiSessionStore
import com.example.hamkit.data.ai.AiStopReason
import com.example.hamkit.data.ai.AiUsage
import com.example.hamkit.data.ai.tool.AiAppFacade
import com.example.hamkit.data.ai.tool.AiCard
import com.example.hamkit.data.ai.tool.AiToolCall
import com.example.hamkit.data.ai.tool.AiToolContext
import com.example.hamkit.data.ai.tool.AiToolExecutor
import com.example.hamkit.data.ai.tool.AiToolRegistry
import com.example.hamkit.data.ai.tool.AiToolSpec
import com.example.hamkit.data.ai.tool.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/** 工具卡状态。 */
enum class AiToolUiState { RUNNING, SUCCESS, ERROR }

/** 一条工具调用的 UI 状态。 */
data class AiToolUi(
    val callId: String,
    val name: String,
    val title: String,
    val argsPreview: String,
    val state: AiToolUiState,
    val progress: Float? = null,
    val stage: String? = null,
    val summary: String? = null,
    val error: String? = null,
)

/** 对话列表里的一条消息（UI 模型，与持久化模型分开）。 */
data class AiChatMessageUi(
    val id: String,
    val role: AiRole,
    val text: String,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long? = null,
    val tools: List<AiToolUi> = emptyList(),
    val cards: List<AiCard> = emptyList(),
    val attachments: List<AiAttachment> = emptyList(),
    /** 历史会话列表（最近在前）。 */
    val sessions: List<AiSessionSummary> = emptyList(),
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
    val error: String? = null,
    /** 待发送附件（文本已读入 / 音频已由 SSTV 解码）。 */
    val attachments: List<AiAttachment> = emptyList(),
    /** 历史会话列表（最近在前）。 */
    val sessions: List<AiSessionSummary> = emptyList(),
)

/**
 * AI 对话状态机（P1：工具调用）。
 *
 * 一次发送最多 [MAX_TOOL_ROUNDS] 轮「模型 → 工具 → 模型」。协议差异（tool_calls /
 * tool_use / functionCall）已在协议层收敛成 [AiEvent]；这里只做参数聚合、执行与回传。
 */
class AiAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = AiConfigStore(application)
    private val keyStore = AiKeyStore(application)
    private val sessionStore = AiSessionStore(application)

    private val registry = AiToolRegistry()
    private val executor = AiToolExecutor(registry)

    /** 由 UI 层注入（页面销毁时置空），工具通过它访问 App 能力。 */
    private var facade: AiAppFacade? = null

    /** SSTV 解码器（音频附件解析用）；页面销毁时置空。 */
    private var sstv: SstvViewModel? = null

    private val _uiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var sessionCreatedAt = System.currentTimeMillis()

    /** 多轮工具调用的协议消息（与 UI 消息分开维护）。 */
    private val conversation = mutableListOf<AiMessage>()

    private val settingsPrefs = application.getSharedPreferences(
        AiConfigStore.PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadConfig()
    }

    init {
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
        viewModelScope.launch { initialize() }
    }

    override fun onCleared() {
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        super.onCleared()
    }

    // ── 对外接口 ────────────────────────────────────────────────────────────

    fun attachFacade(value: AiAppFacade) {
        facade = value
    }

    fun detachFacade() {
        facade = null
    }

    fun attachSstv(value: SstvViewModel) {
        sstv = value
    }

    fun detachSstv() {
        sstv = null
    }

    fun addAttachments(uris: List<Uri>) {
        uris.take(MAX_ATTACHMENTS).forEach { uri ->
            val meta = queryMeta(uri)
            val kind = AiAttachmentParser.kindOf(meta.name, meta.mime)
            val attachment = AiAttachment(
                id = UUID.randomUUID().toString(),
                uri = uri,
                name = meta.name,
                mime = meta.mime,
                sizeBytes = meta.size,
                kind = kind,
            )
            _uiState.update {
                it.copy(attachments = (it.attachments + attachment).take(MAX_ATTACHMENTS))
            }
            when (kind) {
                AiAttachmentKind.TEXT -> viewModelScope.launch { parseTextAttachment(attachment) }
                AiAttachmentKind.AUDIO -> viewModelScope.launch { parseAudioAttachment(attachment) }
                AiAttachmentKind.IMAGE -> updateAttachment(attachment.id) {
                    it.copy(status = AiAttachmentStatus.ERROR, error = "图片暂不支持，请上传文本或音频")
                }
                AiAttachmentKind.UNSUPPORTED -> updateAttachment(attachment.id) {
                    it.copy(status = AiAttachmentStatus.ERROR, error = "暂不支持该文件类型")
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.id == id })
        }
    }

    private data class AttachmentMeta(val name: String, val mime: String, val size: Long)

    private fun queryMeta(uri: Uri): AttachmentMeta {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "附件"
        var size = 0L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return AttachmentMeta(name, mime, size)
    }

    private suspend fun parseTextAttachment(attachment: AiAttachment) {
        val text = withContext(Dispatchers.IO) {
            AiAttachmentParser.readText(getApplication(), attachment.uri)
        }
        updateAttachment(attachment.id) {
            if (text.isNullOrBlank()) {
                it.copy(status = AiAttachmentStatus.ERROR, error = "无法读取文本内容（编码或权限问题）")
            } else {
                it.copy(status = AiAttachmentStatus.READY, text = text)
            }
        }
    }

    private suspend fun parseAudioAttachment(attachment: AiAttachment) {
        val decoder = sstv
        if (decoder == null) {
            updateAttachment(attachment.id) {
                it.copy(status = AiAttachmentStatus.ERROR, error = "SSTV 模块未就绪，请稍后重试")
            }
            return
        }
        val finished = withTimeoutOrNull(SSTV_DECODE_TIMEOUT_MS) {
            decoder.importAudio(attachment.uri)
            snapshotFlow { decoder.uiState.value.importing }.first { !it }
            true
        } ?: false
        val state = decoder.uiState.value
        updateAttachment(attachment.id) {
            when {
                !finished -> it.copy(status = AiAttachmentStatus.ERROR, error = "SSTV 解码超时")
                state.error != null -> it.copy(status = AiAttachmentStatus.ERROR, error = state.error)
                state.savedFileName == null -> it.copy(
                    status = AiAttachmentStatus.READY,
                    sstvMode = state.mode?.name,
                    error = "已解码但未保存（可能未识别到有效图像）",
                )
                else -> it.copy(
                    status = AiAttachmentStatus.READY,
                    sstvMode = state.mode?.name,
                    sstvFile = File(
                        getApplication<Application>().filesDir,
                        "sstv/${state.savedFileName}",
                    ).absolutePath,
                )
            }
        }
    }

    private fun updateAttachment(id: String, transform: (AiAttachment) -> AiAttachment) {
        _uiState.update { state ->
            state.copy(
                attachments = state.attachments.map {
                    if (it.id == id) transform(it) else it
                },
            )
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

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
        conversation.clear()
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
        val activeFacade = facade
        if (activeFacade == null) {
            _uiState.update { it.copy(error = "AI 页面尚未就绪，请稍后重试") }
            return
        }
        if (!state.hasKey) {
            _uiState.update { it.copy(error = "还没有配置 API Key，请先到「AI 设置」填写") }
            return
        }
        if (state.attachments.any { it.status == AiAttachmentStatus.PARSING }) {
            _uiState.update { it.copy(error = "附件还在解析中，请稍候再发送") }
            return
        }
        AiNetworkPolicy.validate(state.config.baseUrl, state.config.allowCleartext)?.let { reason ->
            _uiState.update { it.copy(error = reason) }
            return
        }

        // 历史会话作为上下文（只带文本；工具调用结果不跨会话保留）
        conversation.clear()
        conversation += state.messages
            .filter { (it.role == AiRole.USER || it.role == AiRole.ASSISTANT) && it.text.isNotBlank() }
            .takeLast(MAX_CONTEXT_MESSAGES)
            .map { AiMessage(it.role, listOf(AiBlock.Text(it.text))) }
        conversation += AiMessage.user(text + buildAttachmentContext(state.attachments))

        val userMessage = AiChatMessageUi(
            id = UUID.randomUUID().toString(),
            role = AiRole.USER,
            text = text,
            attachments = state.attachments,
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
                attachments = emptyList(),
                isStreaming = true,
                error = null,
            )
        }
        state.attachments
            .filter { it.kind == AiAttachmentKind.AUDIO && it.status == AiAttachmentStatus.READY }
            .forEach { audio -> appendCardToUi(AiCard.Sstv(audio.sstvMode, audio.sstvFile)) }
        streamJob = viewModelScope.launch { runConversation(activeFacade) }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

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

    fun refreshSessions() {
        viewModelScope.launch {
            val list = sessionStore.summaries()
            _uiState.update { it.copy(sessions = list) }
        }
    }

    fun loadSession(id: String) {
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch {
            val session = sessionStore.load(id) ?: return@launch
            conversation.clear()
            sessionCreatedAt = session.createdAt
            _uiState.update {
                it.copy(
                    sessionId = id,
                    messages = session.messages.map { message -> message.toUi() },
                    error = null,
                    isStreaming = false,
                    attachments = emptyList(),
                )
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionStore.delete(id)
            if (_uiState.value.sessionId == id) newSession()
            refreshSessions()
        }
    }

    /** 导出为 Markdown（明文）；调用方负责写入 SAF。 */
    suspend fun exportMarkdown(id: String): String? {
        val session = sessionStore.load(id) ?: return null
        return buildString {
            append("# ").append(session.title).append("\n\n")
            session.messages.forEach { message ->
                append(if (message.role == AiRole.USER) "## 用户\n\n" else "## AI\n\n")
                append(message.text).append("\n\n")
            }
            append("\n---\n\n由 HamKit AI 导出\n")
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            sessionStore.clear()
            newSession()
        }
    }

    // ── 初始化 / 持久化 ────────────────────────────────────────────────────

    private suspend fun initialize() {
        val config = withContext(Dispatchers.IO) { configStore.load() }
        val hasKey = withContext(Dispatchers.IO) { keyStore.hasKey() }
        _uiState.update { it.copy(config = config, hasKey = hasKey, initialized = true) }

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

    private suspend fun persistSession(usage: AiUsage?) {
        val state = _uiState.value
        if (!state.config.saveHistory) return
        val visible = state.messages.filter { it.text.isNotBlank() || it.error != null }
        if (visible.isEmpty()) return
        val lastAssistantIndex = visible.indexOfLast { it.role == AiRole.ASSISTANT }
        val messages = visible.mapIndexed { index, message ->
            message.toRecord(if (index == lastAssistantIndex) usage else null)
        }

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
        refreshSessions()
    }

    // ── 多轮工具调用 ────────────────────────────────────────────────────────

    private suspend fun runConversation(activeFacade: AiAppFacade) {
        val apiKey = withContext(Dispatchers.IO) { keyStore.load() }
        if (apiKey.isNullOrBlank()) {
            finishStreaming("", null, "API Key 读取失败，请重新保存", 0L)
            return
        }

        val startedAt = System.currentTimeMillis()
        val toolSpecs = registry.specs(_uiState.value.config.disabledTools)
        var usage: AiUsage? = null
        var errorText: String? = null
        var lastStop: AiStopReason? = null
        var textDeltaCount = 0
        val finalText = StringBuilder()

        var cancelled = false
        val toolCache = mutableMapOf<String, ToolOutcome>()
        try {
            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                round++
                val result = streamOnce(conversation.toList(), toolSpecs, apiKey)
                usage = result.usage ?: usage
                lastStop = result.stop ?: lastStop
                textDeltaCount += result.textDeltaCount
                if (result.error != null) {
                    errorText = result.error
                    break
                }
                if (result.text.isNotBlank()) finalText.append(result.text)
                if (result.toolCalls.isEmpty()) break

                conversation += AiMessage(
                    AiRole.ASSISTANT,
                    buildList {
                        if (result.text.isNotBlank()) add(AiBlock.Text(result.text))
                        result.toolCalls.forEach { add(AiBlock.ToolCall(it.id, it.name, it.argsJson)) }
                    },
                )
                registerToolsInUi(result.toolCalls)

                val toolResults = mutableListOf<AiBlock.ToolResult>()
                for (call in result.toolCalls) {
                    val cacheKey = "${call.name}|${call.argsJson}"
                    val cached = toolCache[cacheKey]
                    val outcome = if (cached != null) {
                        completeTool(
                            call.id,
                            registry.find(call.name)?.spec?.title ?: call.name,
                            cached,
                        )
                        cached
                    } else {
                        executeTool(activeFacade, call).also { toolCache[cacheKey] = it }
                    }
                    toolResults += AiBlock.ToolResult(
                        callId = call.id,
                        content = outcome.modelText,
                        isError = outcome.error != null,
                        name = call.name,
                    )
                    outcome.card?.let { card -> appendCardToUi(card) }
                }
                conversation += AiMessage(AiRole.TOOL, toolResults)
            }

            // 轮次用尽或模型只调工具没回答：强制一次「不再带工具」的总结请求
            if (finalText.isBlank() && errorText == null && conversation.isNotEmpty()) {
                val summary = streamOnce(conversation.toList(), emptyList(), apiKey)
                if (summary.error != null) {
                    errorText = summary.error
                } else {
                    finalText.append(summary.text)
                    usage = summary.usage ?: usage
                    textDeltaCount += summary.textDeltaCount
                }
            }
        } catch (cancel: CancellationException) {
            // 用户点“停止”：保留已显示的部分文本，不能把 streaming 状态卡死
            cancelled = true
            throw cancel
        } finally {
            if (cancelled) {
                finishCancelled()
            } else {
                val effectiveError = errorText ?: if (finalText.isBlank() && textDeltaCount == 0) {
                    "服务商返回了空内容（结束原因 ${lastStop?.name ?: "未知"}）。请检查模型名、API 协议与 Base URL 是否匹配。"
                } else {
                    null
                }
                finishStreaming(
                    text = finalText.toString(),
                    usage = usage,
                    error = effectiveError,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            }
        }
    }

    /** 取消收尾：不覆盖已显示的文本，只把 streaming 标记复位。 */
    private fun finishCancelled() {
        _uiState.update { state ->
            state.copy(
                isStreaming = false,
                messages = state.messages.map { message ->
                    if (!message.isStreaming) return@map message
                    message.copy(
                        isStreaming = false,
                        tools = message.tools.map { tool ->
                            if (tool.state == AiToolUiState.RUNNING) {
                                tool.copy(state = AiToolUiState.ERROR, error = "已中断")
                            } else {
                                tool
                            }
                        },
                    )
                },
            )
        }
        streamJob = null
    }

    private data class PendingCall(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    private data class StreamResult(
        val text: String,
        val toolCalls: List<AiToolCall>,
        val usage: AiUsage?,
        val stop: AiStopReason?,
        val error: String?,
        val textDeltaCount: Int,
    )

    /** 单轮流式请求：聚合文本与工具参数。 */
    private suspend fun streamOnce(
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
        apiKey: String,
    ): StreamResult {
        val config = _uiState.value.config
        val request = AiRequest(
            system = _uiState.value.config.systemPrompt.ifBlank { AiPrompts.DEFAULT_SYSTEM_PROMPT },
            messages = messages,
            tools = tools,
        )
        val text = StringBuilder()
        val calls = LinkedHashMap<Int, PendingCall>()
        var usage: AiUsage? = null
        var stop: AiStopReason? = null
        var error: String? = null
        var deltaCount = 0
        var lastFlush = 0L

        try {
            AiClientFactory.create(config.protocol).stream(config, request, apiKey).collect { event ->
                when (event) {
                    is AiEvent.TextDelta -> {
                        deltaCount++
                        text.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= STREAM_FLUSH_INTERVAL_MS) {
                            lastFlush = now
                            updateStreamingText(text.toString())
                        }
                    }

                    is AiEvent.ToolCallStarted -> {
                        val pending = calls.getOrPut(event.index) { PendingCall(event.index) }
                        if (event.id.isNotBlank()) pending.id = event.id
                        if (event.name.isNotBlank()) pending.name = event.name
                    }

                    is AiEvent.ToolArgsDelta -> {
                        calls.getOrPut(event.index) { PendingCall(event.index) }
                            .args.append(event.delta)
                    }

                    is AiEvent.Usage -> usage = AiUsage(event.input, event.output)
                    is AiEvent.Finished -> stop = event.stop
                    is AiEvent.Failed -> error = humanize(event.error)
                    is AiEvent.ReasoningDelta -> Unit
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            error = humanize(AiError.fromThrowable(t))
        }

        val toolCalls = calls.values
            .filter { it.name.isNotBlank() }
            .map {
                AiToolCall(
                    id = it.id.ifBlank { "call_${it.name}_${it.index}" },
                    name = it.name,
                    argsJson = it.args.toString().ifBlank { "{}" },
                )
            }
        return StreamResult(text.toString(), toolCalls, usage, stop, error, deltaCount)
    }

    private suspend fun executeTool(activeFacade: AiAppFacade, call: AiToolCall): ToolOutcome {
        val title = registry.find(call.name)?.spec?.title ?: call.name
        val context = AiToolContext(activeFacade) { fraction, stage ->
            updateToolProgress(call.id, fraction, stage)
        }
        val outcome = executor.execute(call, context)
        completeTool(call.id, title, outcome)
        return outcome
    }

    // ── UI 状态同步 ─────────────────────────────────────────────────────────

    private fun updateStreamingText(value: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.isStreaming) message.copy(text = value) else message
                },
            )
        }
    }

    private fun registerToolsInUi(calls: List<AiToolCall>) {
        val tools = calls.map { call ->
            AiToolUi(
                callId = call.id,
                name = call.name,
                title = registry.find(call.name)?.spec?.title ?: call.name,
                argsPreview = call.argsJson.take(80),
                state = AiToolUiState.RUNNING,
            )
        }
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.isStreaming) message.copy(tools = message.tools + tools) else message
                },
            )
        }
    }

    private fun updateToolProgress(callId: String, fraction: Float?, stage: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    message.copy(
                        tools = message.tools.map { tool ->
                            if (tool.callId == callId) tool.copy(progress = fraction, stage = stage)
                            else tool
                        },
                    )
                },
            )
        }
    }

    private fun completeTool(callId: String, title: String, outcome: ToolOutcome) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    message.copy(
                        tools = message.tools.map { tool ->
                            if (tool.callId != callId) return@map tool
                            tool.copy(
                                title = title,
                                state = if (outcome.error != null) AiToolUiState.ERROR else AiToolUiState.SUCCESS,
                                progress = if (outcome.error != null) tool.progress else 1f,
                                stage = null,
                                summary = outcome.summary,
                                error = outcome.error,
                            )
                        },
                    )
                },
            )
        }
    }

    private fun appendCardToUi(card: AiCard) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.isStreaming) message.copy(cards = message.cards + card) else message
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
                        tools = message.tools.map { tool ->
                            if (tool.state == AiToolUiState.RUNNING) {
                                tool.copy(state = AiToolUiState.ERROR, error = "已中断")
                            } else {
                                tool
                            }
                        },
                    )
                },
                error = if (text.isBlank() && error != null) error else null,
            )
        }
        withContext(NonCancellable) {
            persistSession(usage)
        }
        streamJob = null
    }

    /** 把 READY 附件拼进用户消息（文本内容 / 音频解码结果）。 */
    private fun buildAttachmentContext(attachments: List<AiAttachment>): String = buildString {
        attachments.filter { it.status == AiAttachmentStatus.ERROR }.forEach { attachment ->
            append("\n\n[附件：${attachment.name} 未能解析：${attachment.error ?: "未知原因"}]")
        }
        attachments.filter { it.status == AiAttachmentStatus.READY }.forEach { attachment ->
            when (attachment.kind) {
                AiAttachmentKind.TEXT -> attachment.text?.let { text ->
                    append("\n\n[附件：${attachment.name}]\n```\n")
                    append(text.take(MAX_ATTACHMENT_TEXT_CHARS))
                    append("\n```")
                }

                AiAttachmentKind.AUDIO -> {
                    append("\n\n[音频附件：${attachment.name}]")
                    if (attachment.status == AiAttachmentStatus.ERROR) {
                        append("解析失败：${attachment.error ?: "未知原因"}")
                    } else {
                        append("已由 App 的 SSTV 解码器离线解码")
                        attachment.sstvMode?.let { append("，模式 $it") }
                        attachment.sstvFile?.let { append("，图片已保存到图库（$it）") }
                    }
                }

                else -> Unit
            }
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
        const val STREAM_FLUSH_INTERVAL_MS = 48L
        const val MAX_CONTEXT_MESSAGES = 24
        const val MAX_TOOL_ROUNDS = 4

        const val MAX_ATTACHMENTS = 5
        const val MAX_ATTACHMENT_TEXT_CHARS = 8_000
        const val SSTV_DECODE_TIMEOUT_MS = 180_000L
    }
}
