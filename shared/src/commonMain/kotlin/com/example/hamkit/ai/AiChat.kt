package com.example.hamkit.ai

/**
 * 流式 HTTP 的跨平台抽象：iOS 用 `URLSession.AsyncBytes` 实现，Android 用 OkHttp。
 *
 * 只做一件事：POST 一个 JSON，把响应体当文本流不断回调上来。SSE 分帧、
 * 协议解析都在内核里，平台侧不碰任何协议细节。
 */
data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val timeoutSeconds: Int,
    /** 是否要求流式（false 时平台可以一次性读完整响应再回调一次 chunk）。 */
    val stream: Boolean = true,
)

/** 一次请求的取消句柄。 */
interface AiRequestHandle {
    fun cancel()
}

/**
 * 流回调。都是平台线程调用（iOS 上通常是后台队列），
 * **调用方需自行切回主线程**再更新 UI。
 */
interface AiStreamListener {
    fun onChunk(text: String)
    fun onCompleted()
    fun onFailed(error: AiError)
}

interface AiStreamTransport {
    fun start(request: AiHttpRequest, listener: AiStreamListener): AiRequestHandle
}

/** 工具宿主：规格与执行都由平台提供（iOS 侧实现卫星/定位数据 + 页面跳转）。 */
interface AiToolHost {
    fun specs(): List<AiToolSpec>

    /** 执行工具；实现方负责在主线程回调（可能要弹确认框）。 */
    fun execute(call: AiToolCall, callback: (AiToolResult) -> Unit)
}

/**
 * 对话状态机的回调。一次对话（可能包含多轮工具往返）只在最后回调
 * [onTurnCompleted] 或 [onFailed] 之一。
 */
interface AiChatListener {

    /** 助手正文增量（已拼接可直接追加显示）。 */
    fun onAssistantDelta(text: String)

    /** 思考过程增量（DeepSeek-R1 / Claude thinking / Gemini thought）。 */
    fun onReasoningDelta(text: String)

    /** 一次工具调用开始（argsJson 还在累计）。 */
    fun onToolStarted(callId: String, name: String, title: String)

    /** 一次工具调用结束。 */
    fun onToolFinished(
        callId: String,
        name: String,
        title: String,
        summary: String,
        isError: Boolean,
        cardJson: String,
    )

    /** token 用量（-1 表示该协议本次没给）。 */
    fun onUsage(inputTokens: Int, outputTokens: Int)

    /** 本轮对话结束：把完整消息列表交回，调用方替换本地状态即可。 */
    fun onTurnCompleted(messages: List<AiMessage>, stop: AiStopReason)

    fun onFailed(error: AiError)
}

/**
 * 对话引擎：把「请求 → 流式事件 → 工具往返 → 最终消息」这条链路收在内核里。
 *
 * 与 Android `AiAssistantViewModel` 的对话状态机等价（含：工具调用聚合、
 * 同一轮多个工具结果合并、工具往返次数上限、用尽后强制总结、取消）。
 *
 * 线程约定：**必须在同一线程（iOS 主线程）调用 [start] / [cancel]**，
 * 平台层把网络回调切回主线程后转发给 [AiStreamListener] 即可。
 */
class AiChatEngine(
    private val transport: AiStreamTransport,
    private val listener: AiChatListener,
) {

    private var config: AiConfig = AiConfig.defaults()
    private var apiKey: String = ""
    private var toolHost: AiToolHost? = null

    private var messages: List<AiMessage> = emptyList()
    private var handle: AiRequestHandle? = null
    private var cancelled = false
    private var completed = false

    private var decoder: AiStreamDecoder? = null
    private var sse: AiSseParser? = null
    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val callMap = LinkedHashMap<Int, MutableCall>()
    private var stop: AiStopReason? = null
    private var streamFailed = false
    private var rounds = 0

    /**
     * 本轮对话命中的工具集（按用户意图裁剪）。
     *
     * 空集表示未命中任何分类 → 下发全部工具（保守回退）。只在 [start] 里算一次，
     * 后续工具往返沿用同一份，避免「同一句话前后两轮看到的工具不一样」。
     */
    private var route: Set<String> = emptySet()

    private class MutableCall(val index: Int, val id: String, val name: String) {
        val args = StringBuilder()
    }

    /** 当前消息列表（调用方可用来做「重新生成」等操作）。 */
    fun messages(): List<AiMessage> = messages

    /**
     * 开始一轮对话。
     *
     * @param messages 完整历史（含本轮用户消息）
     */
    fun start(
        config: AiConfig,
        apiKey: String,
        messages: List<AiMessage>,
        toolHost: AiToolHost?,
    ) {
        this.config = config.normalized()
        this.apiKey = apiKey
        this.messages = messages.toList()
        this.toolHost = toolHost
        this.cancelled = false
        this.completed = false
        this.rounds = 0
        // 用本轮最后一条用户消息做意图路由（历史里的旧问题不参与）
        this.route = AiToolRouter.route(
            messages.lastOrNull { it.role == AiRole.USER }?.text.orEmpty(),
        )
        sendRequest(allowTools = toolHost != null)
    }

    fun cancel() {
        cancelled = true
        handle?.cancel()
        handle = null
        if (!completed) {
            completed = true
            listener.onFailed(AiError.cancelled())
        }
    }

    // ── 请求 ────────────────────────────────────────────────────────────────

    private fun sendRequest(allowTools: Boolean) {
        if (cancelled) return
        val cfg = config
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            finishWithError(AiError.noConfig("请先在「AI 设置」里填写 Base URL 与模型"))
            return
        }
        if (apiKey.isBlank()) {
            finishWithError(AiError.noConfig("请先在「AI 设置」里填写 API Key"))
            return
        }

        textBuffer.setLength(0)
        reasoningBuffer.clear()
        callMap.clear()
        stop = null
        streamFailed = false

        val specs = if (allowTools) toolHost?.specs()?.filter {
            it.name !in cfg.disabledToolNames && (route.isEmpty() || it.name in route)
        }.orEmpty() else emptyList()

        val body = AiCodecs.chatBody(
            config = cfg,
            system = cfg.effectiveSystemPrompt,
            messages = messages,
            tools = specs,
        )
        val request = AiHttpRequest(
            url = cfg.endpoint(),
            headers = AiCodecs.headers(cfg.protocol, apiKey),
            body = body,
            timeoutSeconds = 120,
        )
        val streamDecoder = AiCodecs.decoder(cfg.protocol)
        decoder = streamDecoder
        sse = AiSseParser()

        handle = transport.start(request, object : AiStreamListener {
            override fun onChunk(text: String) {
                if (cancelled) return
                val parser = sse ?: return
                parser.feed(text).forEach { frame -> handleEvents(streamDecoder.onFrame(frame)) }
            }

            override fun onCompleted() {
                if (cancelled || completed) return
                handle = null
                val parser = sse
                parser?.flush()?.forEach { frame ->
                    handleEvents(streamDecoder.onFrame(frame))
                }
                handleEvents(streamDecoder.finish())
                onStreamEnded()
            }

            override fun onFailed(error: AiError) {
                if (cancelled || completed) return
                handle = null
                streamFailed = true
                finishWithError(error)
            }
        })
    }

    private fun handleEvents(events: List<AiEvent>) {
        for (event in events) {
            if (cancelled) return
            when (event.kind) {
                AiEventKind.TEXT_DELTA -> {
                    textBuffer.append(event.text)
                    listener.onAssistantDelta(event.text)
                }

                AiEventKind.REASONING_DELTA -> {
                    reasoningBuffer.append(event.text)
                    listener.onReasoningDelta(event.text)
                }

                AiEventKind.TOOL_CALL_STARTED -> {
                    val existing = callMap[event.index]
                    if (existing == null) {
                        val id = event.callId.ifEmpty { "call_${event.index}_${event.toolName}" }
                        callMap[event.index] = MutableCall(event.index, id, event.toolName)
                        listener.onToolStarted(id, event.toolName, titleOf(event.toolName))
                    } else if (event.toolName.isNotEmpty() && existing.name.isEmpty()) {
                        callMap[event.index] = MutableCall(existing.index, existing.id, event.toolName)
                    }
                }

                AiEventKind.TOOL_ARGS_DELTA -> {
                    callMap[event.index]?.args?.append(event.text)
                }

                AiEventKind.USAGE -> listener.onUsage(event.inputTokens, event.outputTokens)

                AiEventKind.FINISHED -> stop = event.stop

                AiEventKind.FAILED -> {
                    event.error?.let { streamFailed = true; finishWithError(it) }
                }
            }
        }
    }

    private fun onStreamEnded() {
        if (cancelled || completed || streamFailed) return

        val finalStop = stop ?: AiStopReason.END_TURN
        val calls = callMap.values.map {
            AiToolCall(
                id = it.id,
                name = it.name,
                argsJson = it.args.toString().ifBlank { "{}" },
            )
        }

        val assistant = AiMessage(
            role = AiRole.ASSISTANT,
            text = textBuffer.toString(),
            calls = calls,
        )
        messages = messages + assistant

        val host = toolHost
        if (finalStop == AiStopReason.TOOL_USE && calls.isNotEmpty() && host != null) {
            rounds += 1
            if (rounds > config.maxToolRounds) {
                // 轮次用尽：不再给工具，要求模型直接基于已有结果作答
                messages = messages + AiMessage(
                    role = AiRole.USER,
                    text = "（系统）工具调用次数已达上限，请直接根据已有结果给出最终答复，不要再调用工具。",
                )
                sendRequest(allowTools = false)
            } else {
                startTools(host, calls)
            }
            return
        }

        completed = true
        listener.onTurnCompleted(messages, finalStop)
    }

    /**
     * 执行本轮工具调用。
     *
     * 同一轮里如果**全部是只读工具**就并行发起（典型场景：一次问「ISS 过境 + 转发器 + 实时位置」），
     * 否则保持串行——副作用/导航类工具需要按顺序确认，不能并发弹窗。
     * 无论并行还是串行，结果都按模型给出的 `calls` 顺序回填消息列表，
     * 保证多轮请求里 tool_call 与 tool_result 的配对顺序稳定。
     */
    private fun startTools(host: AiToolHost, calls: List<AiToolCall>) {
        if (cancelled) return
        val specs = host.specs()
        val allReadOnly = calls.size > 1 && calls.all { call ->
            specs.firstOrNull { it.name == call.name }?.risk == AiToolRisk.READ_ONLY
        }
        if (!allReadOnly) {
            runTools(host, calls, 0)
            return
        }
        val results = arrayOfNulls<AiToolResult>(calls.size)
        var remaining = calls.size
        calls.forEachIndexed { index, call ->
            host.execute(call) { result ->
                results[index] = result
                remaining -= 1
                if (remaining == 0 && !cancelled) {
                    calls.forEachIndexed { i, done ->
                        appendToolResult(
                            done,
                            results[i] ?: AiToolResult(
                                modelText = "工具没有返回结果",
                                summary = "无结果",
                                isError = true,
                            ),
                        )
                    }
                    sendRequest(allowTools = true)
                }
            }
        }
    }

    private fun runTools(host: AiToolHost, calls: List<AiToolCall>, index: Int) {
        if (cancelled) return
        if (index >= calls.size) {
            sendRequest(allowTools = true)
            return
        }
        val call = calls[index]
        host.execute(call) { result ->
            appendToolResult(call, result)
            runTools(host, calls, index + 1)
        }
    }

    private fun appendToolResult(call: AiToolCall, result: AiToolResult) {
        messages = messages + AiMessage.toolResult(
            callId = call.id,
            name = call.name,
            content = result.errorTextForModel(),
            isError = result.isError,
        )
        listener.onToolFinished(
            callId = call.id,
            name = call.name,
            title = titleOf(call.name),
            summary = result.summary,
            isError = result.isError,
            cardJson = result.cardJson,
        )
    }

    private fun finishWithError(error: AiError) {
        if (completed) return
        completed = true
        handle?.cancel()
        handle = null
        listener.onFailed(error)
    }

    private fun titleOf(name: String): String =
        toolHost?.specs()?.firstOrNull { it.name == name }?.title ?: name
}

/**
 * 给模型看的工具结果文本。
 *
 * 失败时补一层 `[错误]` 前缀：模型对「工具报错」和「工具查到了空结果」是两种反应，
 * 不加前缀时容易把错误当成数据继续推理。超长结果统一截断，避免一次查询占满上下文。
 */
private fun AiToolResult.errorTextForModel(): String {
    val text = truncateToolResult(modelText)
    return if (isError) "[错误] $text" else text
}
