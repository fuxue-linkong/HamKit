package com.example.hamkit.ai

/**
 * 协议适配层：把三种协议的「请求体拼装 + 流式响应解析」收敛成同一组接口。
 *
 * 与 Android `data/ai/AiProtocolClients.kt` 的行为逐项对齐（字段名、增量语义、
 * 结束原因映射）。差异只在于这里是纯 Kotlin：没有 OkHttp、没有 Flow，
 * 网络由平台注入（见 [AiStreamTransport]）。
 */
internal interface AiStreamDecoder {
    fun onFrame(frame: AiSseFrame): List<AiEvent>

    /** 流结束时补齐一个 [AiEventKind.FINISHED]（协议没给 finish 标志时）。 */
    fun finish(): List<AiEvent>
}

internal object AiCodecs {

    fun headers(protocol: AiProtocol, apiKey: String): Map<String, String> {
        val key = apiKey.trim()
        return when (protocol) {
            AiProtocol.OPENAI_CHAT -> mapOf(
                "Authorization" to "Bearer $key",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            )

            AiProtocol.ANTHROPIC_MESSAGES -> mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            )

            AiProtocol.GEMINI -> mapOf(
                "x-goog-api-key" to key,
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            )
        }
    }

    fun decoder(protocol: AiProtocol): AiStreamDecoder = when (protocol) {
        AiProtocol.OPENAI_CHAT -> OpenAiDecoder()
        AiProtocol.ANTHROPIC_MESSAGES -> AnthropicDecoder()
        AiProtocol.GEMINI -> GeminiDecoder()
    }

    // ── 请求体 ──────────────────────────────────────────────────────────────

    fun chatBody(
        config: AiConfig,
        system: String,
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
    ): String {
        val cfg = config.normalized()
        return when (cfg.protocol) {
            AiProtocol.OPENAI_CHAT -> openAiBody(cfg, system, messages, tools)
            AiProtocol.ANTHROPIC_MESSAGES -> anthropicBody(cfg, system, messages, tools)
            AiProtocol.GEMINI -> geminiBody(cfg, system, messages, tools)
        }
    }

    fun probeBody(config: AiConfig, withTool: Boolean): String {
        val cfg = config.normalized()
        return when (cfg.protocol) {
            AiProtocol.OPENAI_CHAT -> {
                val body = Json.obj()
                body.put("model", cfg.model)
                body.put(
                    "messages",
                    Json.arr().add(
                        Json.obj().put("role", "user").put("content", "ping")
                    ),
                )
                body.put("max_tokens", 1)
                if (withTool) {
                    body.put(
                        "tools",
                        Json.arr().add(
                            Json.obj()
                                .put("type", "function")
                                .put(
                                    "function",
                                    Json.obj()
                                        .put("name", "noop")
                                        .put("description", "noop")
                                        .put(
                                            "parameters",
                                            Json.obj()
                                                .put("type", "object")
                                                .put("properties", Json.obj()),
                                        ),
                                )
                        ),
                    )
                    body.put("tool_choice", "auto")
                }
                body.stringify()
            }

            AiProtocol.ANTHROPIC_MESSAGES -> Json.obj()
                .put("model", cfg.model)
                .put("max_tokens", 1)
                .put(
                    "messages",
                    Json.arr().add(Json.obj().put("role", "user").put("content", "ping")),
                )
                .stringify()

            AiProtocol.GEMINI -> Json.obj()
                .put(
                    "contents",
                    Json.arr().add(
                        Json.obj()
                            .put("role", "user")
                            .put("parts", Json.arr().add(Json.obj().put("text", "ping"))),
                    ),
                )
                .put("generationConfig", Json.obj().put("maxOutputTokens", 1))
                .stringify()
        }
    }

    /** 连接测试的响应判定：成功即 ok，400/422 视为「支持探测失败 → 不支持工具」。 */
    fun decodeProbe(status: Int, body: String, withTool: Boolean): AiProbeResult = when {
        status in 200..299 -> AiProbeResult(
            ok = true,
            latencyMs = 0,
            toolsSupported = if (withTool) true else null,
        )

        withTool && (status == 400 || status == 422) -> AiProbeResult(
            ok = true,
            latencyMs = 0,
            toolsSupported = false,
        )

        else -> AiProbeResult(ok = false, latencyMs = 0, error = AiError.fromHttp(status, body))
    }

    // ── OpenAI Chat Completions ─────────────────────────────────────────────

    private fun openAiBody(
        cfg: AiConfig,
        system: String,
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
    ): String {
        val array = Json.arr()
        if (system.isNotBlank()) {
            array.add(Json.obj().put("role", "system").put("content", system))
        }
        for (message in messages) {
            when (message.role) {
                AiRole.USER -> {
                    if (message.images.isEmpty()) {
                        if (message.text.isNotBlank()) {
                            array.add(Json.obj().put("role", "user").put("content", message.text))
                        }
                    } else {
                        val parts = Json.arr()
                        if (message.text.isNotBlank()) {
                            parts.add(Json.obj().put("type", "text").put("text", message.text))
                        }
                        for (image in message.images) {
                            parts.add(
                                Json.obj()
                                    .put("type", "image_url")
                                    .put(
                                        "image_url",
                                        Json.obj().put(
                                            "url",
                                            "data:${image.mime};base64,${image.base64}",
                                        ),
                                    ),
                            )
                        }
                        array.add(Json.obj().put("role", "user").put("content", parts))
                    }
                }

                AiRole.ASSISTANT -> {
                    if (message.calls.isEmpty()) {
                        if (message.text.isNotBlank()) {
                            array.add(
                                Json.obj().put("role", "assistant").put("content", message.text)
                            )
                        }
                    } else {
                        val obj = Json.obj().put("role", "assistant")
                        if (message.text.isNotBlank()) {
                            obj.put("content", message.text)
                        } else {
                            obj.putNull("content")
                        }
                        obj.put(
                            "tool_calls",
                            Json.arr().also { calls ->
                                message.calls.forEach { call ->
                                    calls.add(
                                        Json.obj()
                                            .put("id", call.id.ifEmpty { "call_${call.name}" })
                                            .put("type", "function")
                                            .put(
                                                "function",
                                                Json.obj()
                                                    .put("name", call.name)
                                                    .put(
                                                        "arguments",
                                                        call.argsJson.ifBlank { "{}" },
                                                    ),
                                            ),
                                    )
                                }
                            },
                        )
                        array.add(obj)
                    }
                }

                AiRole.TOOL -> {
                    array.add(
                        Json.obj()
                            .put("role", "tool")
                            .put("tool_call_id", message.toolCallId)
                            .put("content", message.text),
                    )
                }

                AiRole.SYSTEM -> Unit
            }
        }

        val body = Json.obj()
        body.put("model", cfg.model)
        body.put("messages", array)
        body.put("temperature", cfg.temperature)
        body.put("max_tokens", cfg.maxOutputTokens)
        body.put("stream", true)
        body.put("stream_options", Json.obj().put("include_usage", true))
        if (tools.isNotEmpty()) {
            body.put(
                "tools",
                Json.arr().also { specs ->
                    tools.forEach { spec ->
                        specs.add(
                            Json.obj()
                                .put("type", "function")
                                .put(
                                    "function",
                                    Json.obj()
                                        .put("name", spec.name)
                                        .put("description", spec.description)
                                        .put("parameters", parseSchema(spec.parametersJson)),
                                ),
                        )
                    }
                },
            )
            body.put("tool_choice", "auto")
        }
        return body.stringify()
    }

    // ── Anthropic Messages ──────────────────────────────────────────────────

    private fun anthropicBody(
        cfg: AiConfig,
        system: String,
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
    ): String {
        val array = Json.arr()
        for (message in messages) {
            when (message.role) {
                AiRole.USER -> {
                    val blocks = Json.arr()
                    if (message.text.isNotBlank()) {
                        blocks.add(Json.obj().put("type", "text").put("text", message.text))
                    }
                    for (image in message.images) {
                        blocks.add(
                            Json.obj()
                                .put("type", "image")
                                .put(
                                    "source",
                                    Json.obj()
                                        .put("type", "base64")
                                        .put("media_type", image.mime)
                                        .put("data", image.base64),
                                ),
                        )
                    }
                    if (blocks.size > 0) {
                        array.add(Json.obj().put("role", "user").put("content", blocks))
                    }
                }

                AiRole.ASSISTANT -> {
                    val blocks = Json.arr()
                    if (message.text.isNotBlank()) {
                        blocks.add(Json.obj().put("type", "text").put("text", message.text))
                    }
                    message.calls.forEach { call ->
                        blocks.add(
                            Json.obj()
                                .put("type", "tool_use")
                                .put("id", call.id.ifEmpty { "toolu_${call.name}" })
                                .put("name", call.name)
                                .put("input", parseObject(call.argsJson)),
                        )
                    }
                    if (blocks.size > 0) {
                        array.add(Json.obj().put("role", "assistant").put("content", blocks))
                    }
                }

                AiRole.TOOL -> {
                    val block = Json.obj()
                        .put("type", "tool_result")
                        .put("tool_use_id", message.toolCallId)
                        .put("content", message.text)
                    if (message.isError) block.put("is_error", true)
                    // tool_result 必须回在 user 轮里；同一轮多个结果要合并成一个消息，
                    // 这里逐个追加 content block 到同一个 user 消息。
                    val last = array.items.lastOrNull()?.objOrNull()
                    if (last != null && last["role"]?.stringOrNull() == "user" &&
                        last["content"]?.arrOrNull()?.items?.firstOrNull()?.objOrNull()
                            ?.get("type")?.stringOrNull() == "tool_result"
                    ) {
                        last["content"]?.arrOrNull()?.add(block)
                    } else {
                        array.add(
                            Json.obj().put("role", "user").put("content", Json.arr().add(block)),
                        )
                    }
                }

                AiRole.SYSTEM -> Unit
            }
        }

        val body = Json.obj()
        body.put("model", cfg.model)
        body.put("max_tokens", cfg.maxOutputTokens)
        if (system.isNotBlank()) body.put("system", system)
        body.put("messages", array)
        body.put("temperature", cfg.temperature.coerceIn(0.0, 1.0))
        body.put("stream", true)
        if (tools.isNotEmpty()) {
            body.put(
                "tools",
                Json.arr().also { specs ->
                    tools.forEach { spec ->
                        specs.add(
                            Json.obj()
                                .put("name", spec.name)
                                .put("description", spec.description)
                                .put("input_schema", parseSchema(spec.parametersJson)),
                        )
                    }
                },
            )
        }
        return body.stringify()
    }

    // ── Gemini ──────────────────────────────────────────────────────────────

    private fun geminiBody(
        cfg: AiConfig,
        system: String,
        messages: List<AiMessage>,
        tools: List<AiToolSpec>,
    ): String {
        val contents = Json.arr()
        for (message in messages) {
            when (message.role) {
                AiRole.USER -> {
                    val parts = Json.arr()
                    if (message.text.isNotBlank()) parts.add(Json.obj().put("text", message.text))
                    for (image in message.images) {
                        parts.add(
                            Json.obj().put(
                                "inlineData",
                                Json.obj()
                                    .put("mimeType", image.mime)
                                    .put("data", image.base64),
                            ),
                        )
                    }
                    if (parts.size > 0) {
                        contents.add(Json.obj().put("role", "user").put("parts", parts))
                    }
                }

                AiRole.ASSISTANT -> {
                    val parts = Json.arr()
                    if (message.text.isNotBlank()) parts.add(Json.obj().put("text", message.text))
                    message.calls.forEach { call ->
                        parts.add(
                            Json.obj().put(
                                "functionCall",
                                Json.obj()
                                    .put("name", call.name)
                                    .put("args", parseObject(call.argsJson)),
                            ),
                        )
                    }
                    if (parts.size > 0) {
                        contents.add(Json.obj().put("role", "model").put("parts", parts))
                    }
                }

                AiRole.TOOL -> {
                    val part = Json.obj().put(
                        "functionResponse",
                        Json.obj()
                            .put("name", message.toolName.ifEmpty { "tool" })
                            .put("response", Json.obj().put("result", message.text)),
                    )
                    val last = contents.items.lastOrNull()?.objOrNull()
                    if (last != null && last["role"]?.stringOrNull() == "user" &&
                        last["parts"]?.arrOrNull()?.items?.firstOrNull()?.objOrNull()
                            ?.get("functionResponse") != null
                    ) {
                        last["parts"]?.arrOrNull()?.add(part)
                    } else {
                        contents.add(
                            Json.obj().put("role", "user").put("parts", Json.arr().add(part)),
                        )
                    }
                }

                AiRole.SYSTEM -> Unit
            }
        }

        val body = Json.obj()
        if (system.isNotBlank()) {
            body.put(
                "systemInstruction",
                Json.obj().put("parts", Json.arr().add(Json.obj().put("text", system))),
            )
        }
        body.put("contents", contents)
        body.put("generationConfig", Json.obj()
            .put("temperature", cfg.temperature)
            .put("maxOutputTokens", cfg.maxOutputTokens))
        if (tools.isNotEmpty()) {
            body.put(
                "tools",
                Json.arr().add(
                    Json.obj().put(
                        "functionDeclarations",
                        Json.arr().also { decls ->
                            tools.forEach { spec ->
                                decls.add(
                                    Json.obj()
                                        .put("name", spec.name)
                                        .put("description", spec.description)
                                        .put("parameters", parseSchema(spec.parametersJson)),
                                )
                            }
                        },
                    ),
                ),
            )
        }
        return body.stringify()
    }

    /** 工具参数 schema：解析失败时退化成空对象 schema，避免把整条请求弄坏。 */
    private fun parseSchema(json: String): Json =
        Json.parse(json) ?: Json.obj().put("type", "object").put("properties", Json.obj())

    private fun parseObject(json: String): Json =
        Json.parse(json.ifBlank { "{}" }) ?: Json.obj()
}

// ── 各协议的流式解析器 ──────────────────────────────────────────────────────

/**
 * 一个 SSE 帧里可能有多个 JSON（非标实现把多条挤在没有空行分隔的 data 里），
 * 也可能整段就是一个 JSON。这里统一拆成对象列表。
 */
internal fun parseJsonObjects(data: String): List<Json.Obj> {
    val trimmed = data.trim()
    if (trimmed.isEmpty() || trimmed == "[DONE]") return emptyList()
    Json.parse(trimmed)?.objOrNull()?.let { return listOf(it) }
    return trimmed.lineSequence()
        .map { it.trim().removePrefix("data:").trim() }
        .filter { it.startsWith("{") && it != "[DONE]" }
        .mapNotNull { Json.parse(it)?.objOrNull() }
        .toList()
}

internal class OpenAiDecoder : AiStreamDecoder {

    private var finished = false
    private var sawToolCall = false
    private var stop = AiStopReason.END_TURN

    override fun onFrame(frame: AiSseFrame): List<AiEvent> =
        parseJsonObjects(frame.data).flatMap { parseOne(it) }

    private fun parseOne(json: Json.Obj): List<AiEvent> {
        val events = ArrayList<AiEvent>()

        json["usage"]?.objOrNull()?.let { usage ->
            val input = usage["prompt_tokens"]?.intOrNull() ?: -1
            val output = usage["completion_tokens"]?.intOrNull() ?: -1
            if (input >= 0 || output >= 0) {
                events.add(AiEvent(AiEventKind.USAGE, inputTokens = input, outputTokens = output))
            }
        }

        val choice = json["choices"]?.arrOrNull()?.get(0)?.objOrNull() ?: return events
        val delta = choice["delta"]?.objOrNull()
        val message = choice["message"]?.objOrNull()

        (delta?.get("content")?.stringOrNull() ?: message?.get("content")?.stringOrNull())
            ?.takeIf { it.isNotEmpty() }
            ?.let { events.add(AiEvent(AiEventKind.TEXT_DELTA, text = it)) }

        // DeepSeek-R1 等把思考过程放在 reasoning_content，不进正文
        (delta?.get("reasoning_content")?.stringOrNull()
            ?: message?.get("reasoning_content")?.stringOrNull())
            ?.takeIf { it.isNotEmpty() }
            ?.let { events.add(AiEvent(AiEventKind.REASONING_DELTA, text = it)) }

        (delta?.get("tool_calls")?.arrOrNull() ?: message?.get("tool_calls")?.arrOrNull())
            ?.let { calls ->
                for (i in 0 until calls.size) {
                    val tc = calls[i]?.objOrNull() ?: continue
                    val index = tc["index"]?.intOrNull() ?: i
                    val id = tc["id"]?.stringOrNull().orEmpty()
                    val function = tc["function"]?.objOrNull()
                    val name = function?.get("name")?.stringOrNull().orEmpty()
                    if (id.isNotEmpty() || name.isNotEmpty()) {
                        sawToolCall = true
                        events.add(
                            AiEvent(
                                AiEventKind.TOOL_CALL_STARTED,
                                index = index,
                                callId = id,
                                toolName = name,
                            )
                        )
                    }
                    function?.get("arguments")?.stringOrNull()?.takeIf { it.isNotEmpty() }?.let {
                        events.add(AiEvent(AiEventKind.TOOL_ARGS_DELTA, text = it, index = index))
                    }
                }
            }

        choice["finish_reason"]?.stringOrNull()?.let { reason ->
            stop = when {
                reason == "tool_calls" || sawToolCall -> AiStopReason.TOOL_USE
                reason == "length" -> AiStopReason.MAX_TOKENS
                reason == "content_filter" -> AiStopReason.CONTENT_FILTER
                reason == "stop" -> AiStopReason.END_TURN
                else -> AiStopReason.UNKNOWN
            }
            finished = true
            events.add(AiEvent(AiEventKind.FINISHED, stop = stop))
        }
        return events
    }

    override fun finish(): List<AiEvent> =
        if (finished) emptyList()
        else listOf(
            AiEvent(
                AiEventKind.FINISHED,
                stop = if (sawToolCall) AiStopReason.TOOL_USE else AiStopReason.END_TURN,
            )
        )
}

internal class AnthropicDecoder : AiStreamDecoder {

    private var finished = false
    private var sawToolCall = false
    private var stop = AiStopReason.END_TURN

    override fun onFrame(frame: AiSseFrame): List<AiEvent> =
        parseJsonObjects(frame.data).flatMap { parseOne(it, frame.event) }

    private fun parseOne(json: Json.Obj, event: String?): List<AiEvent> {
        val events = ArrayList<AiEvent>()
        val type = event ?: json["type"]?.stringOrNull().orEmpty()

        when (type) {
            "message_start" -> {
                val usage = json["message"]?.objOrNull()?.get("usage")?.objOrNull()
                val input = usage?.get("input_tokens")?.intOrNull() ?: -1
                if (input >= 0) {
                    events.add(AiEvent(AiEventKind.USAGE, inputTokens = input))
                }
            }

            "content_block_start" -> {
                val block = json["content_block"]?.objOrNull()
                if (block?.get("type")?.stringOrNull() == "tool_use") {
                    sawToolCall = true
                    events.add(
                        AiEvent(
                            AiEventKind.TOOL_CALL_STARTED,
                            index = json["index"]?.intOrNull() ?: 0,
                            callId = block["id"]?.stringOrNull().orEmpty(),
                            toolName = block["name"]?.stringOrNull().orEmpty(),
                        )
                    )
                }
            }

            "content_block_delta" -> {
                val delta = json["delta"]?.objOrNull() ?: return events
                when (delta["type"]?.stringOrNull()) {
                    "text_delta" -> delta["text"]?.stringOrNull()
                        ?.let { events.add(AiEvent(AiEventKind.TEXT_DELTA, text = it)) }

                    "thinking_delta" -> delta["thinking"]?.stringOrNull()
                        ?.let { events.add(AiEvent(AiEventKind.REASONING_DELTA, text = it)) }

                    "input_json_delta" -> delta["partial_json"]?.stringOrNull()?.let { partial ->
                        sawToolCall = true
                        events.add(
                            AiEvent(
                                AiEventKind.TOOL_ARGS_DELTA,
                                text = partial,
                                index = json["index"]?.intOrNull() ?: 0,
                            )
                        )
                    }
                }
            }

            "message_delta" -> {
                json["delta"]?.objOrNull()?.get("stop_reason")?.stringOrNull()?.let { reason ->
                    stop = when {
                        reason == "tool_use" || sawToolCall -> AiStopReason.TOOL_USE
                        reason == "max_tokens" -> AiStopReason.MAX_TOKENS
                        reason == "end_turn" || reason == "stop_sequence" -> AiStopReason.END_TURN
                        reason == "refusal" -> AiStopReason.CONTENT_FILTER
                        else -> AiStopReason.UNKNOWN
                    }
                }
                json["usage"]?.objOrNull()?.get("output_tokens")?.intOrNull()?.let { output ->
                    events.add(AiEvent(AiEventKind.USAGE, outputTokens = output))
                }
            }

            "message_stop" -> {
                finished = true
                events.add(AiEvent(AiEventKind.FINISHED, stop = stop))
            }

            // 非流式完整响应（type = message）：部分代理会忽略 stream=true
            "message" -> {
                json["content"]?.arrOrNull()?.let { blocks ->
                    for (i in 0 until blocks.size) {
                        val block = blocks[i]?.objOrNull() ?: continue
                        when (block["type"]?.stringOrNull()) {
                            "text" -> block["text"]?.stringOrNull()?.let {
                                events.add(AiEvent(AiEventKind.TEXT_DELTA, text = it))
                            }

                            "thinking" -> block["thinking"]?.stringOrNull()?.let {
                                events.add(AiEvent(AiEventKind.REASONING_DELTA, text = it))
                            }

                            "tool_use" -> {
                                sawToolCall = true
                                events.add(
                                    AiEvent(
                                        AiEventKind.TOOL_CALL_STARTED,
                                        index = i,
                                        callId = block["id"]?.stringOrNull().orEmpty(),
                                        toolName = block["name"]?.stringOrNull().orEmpty(),
                                    )
                                )
                                events.add(
                                    AiEvent(
                                        AiEventKind.TOOL_ARGS_DELTA,
                                        text = block["input"]?.stringify() ?: "{}",
                                        index = i,
                                    )
                                )
                            }
                        }
                    }
                }
                val reason = json["stop_reason"]?.stringOrNull().orEmpty()
                stop = when {
                    reason == "tool_use" || sawToolCall -> AiStopReason.TOOL_USE
                    reason == "max_tokens" -> AiStopReason.MAX_TOKENS
                    reason == "end_turn" || reason == "stop_sequence" -> AiStopReason.END_TURN
                    reason == "refusal" -> AiStopReason.CONTENT_FILTER
                    else -> AiStopReason.UNKNOWN
                }
                finished = true
                events.add(AiEvent(AiEventKind.FINISHED, stop = stop))
            }

            "error" -> {
                val message = json["error"]?.objOrNull()?.get("message")?.stringOrNull()
                    ?: "stream error"
                events.add(
                    AiEvent(
                        AiEventKind.FAILED,
                        error = AiError(AiErrorKind.INVALID_REQUEST, message),
                    )
                )
            }
        }
        return events
    }

    override fun finish(): List<AiEvent> =
        if (finished) emptyList()
        else listOf(
            AiEvent(
                AiEventKind.FINISHED,
                stop = if (sawToolCall) AiStopReason.TOOL_USE else AiStopReason.END_TURN,
            )
        )
}

internal class GeminiDecoder : AiStreamDecoder {

    private var finished = false
    private var sawToolCall = false
    private var stop = AiStopReason.END_TURN

    override fun onFrame(frame: AiSseFrame): List<AiEvent> =
        parseJsonObjects(frame.data).flatMap { parseOne(it) }

    private fun parseOne(json: Json.Obj): List<AiEvent> {
        val events = ArrayList<AiEvent>()

        json["usageMetadata"]?.objOrNull()?.let { usage ->
            val input = usage["promptTokenCount"]?.intOrNull() ?: -1
            val output = usage["candidatesTokenCount"]?.intOrNull() ?: -1
            if (input >= 0 || output >= 0) {
                events.add(AiEvent(AiEventKind.USAGE, inputTokens = input, outputTokens = output))
            }
        }

        val candidate = json["candidates"]?.arrOrNull()?.get(0)?.objOrNull() ?: return events
        candidate["content"]?.objOrNull()?.get("parts")?.arrOrNull()?.let { parts ->
            for (i in 0 until parts.size) {
                val part = parts[i]?.objOrNull() ?: continue
                part["functionCall"]?.objOrNull()?.let { call ->
                    sawToolCall = true
                    events.add(
                        AiEvent(
                            AiEventKind.TOOL_CALL_STARTED,
                            index = 0,
                            toolName = call["name"]?.stringOrNull().orEmpty(),
                        )
                    )
                    // Gemini 一次性给完整 args（对象），转成 JSON 字符串统一处理
                    events.add(
                        AiEvent(
                            AiEventKind.TOOL_ARGS_DELTA,
                            text = call["args"]?.stringify() ?: "{}",
                            index = 0,
                        )
                    )
                }
                part["text"]?.stringOrNull()?.let { text ->
                    val thought = part["thought"]?.boolOrNull() == true
                    events.add(
                        AiEvent(
                            if (thought) AiEventKind.REASONING_DELTA else AiEventKind.TEXT_DELTA,
                            text = text,
                        )
                    )
                }
            }
        }

        candidate["finishReason"]?.stringOrNull()?.let { reason ->
            if (!finished) {
                stop = when {
                    sawToolCall -> AiStopReason.TOOL_USE
                    reason == "MAX_TOKENS" -> AiStopReason.MAX_TOKENS
                    reason == "SAFETY" || reason == "RECITATION" || reason == "BLOCKLIST" ->
                        AiStopReason.CONTENT_FILTER
                    reason == "STOP" || reason == "FINISH_REASON_UNSPECIFIED" ->
                        AiStopReason.END_TURN
                    else -> AiStopReason.UNKNOWN
                }
                finished = true
                events.add(AiEvent(AiEventKind.FINISHED, stop = stop))
            }
        }
        return events
    }

    override fun finish(): List<AiEvent> =
        if (finished) emptyList()
        else listOf(
            AiEvent(
                AiEventKind.FINISHED,
                stop = if (sawToolCall) AiStopReason.TOOL_USE else AiStopReason.END_TURN,
            )
        )
}
