package com.example.hamkit.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 协议客户端：把各家 SSE 映射成统一的 [AiEvent] 流。 */
interface AiProtocolClient {
    val protocol: AiProtocol

    /** 流式对话；取消协程时会取消底层 OkHttp Call。 */
    fun stream(config: AiConfig, request: AiRequest, apiKey: String): Flow<AiEvent>

    /** 设置页"测试连接"：发一个最小请求，返回延迟与错误分类。 */
    suspend fun probe(config: AiConfig, apiKey: String): AiProbeResult
}

object AiClientFactory {
    fun create(protocol: AiProtocol): AiProtocolClient = when (protocol) {
        AiProtocol.OPENAI_CHAT -> OpenAiChatClient
        AiProtocol.ANTHROPIC_MESSAGES -> AnthropicClient
        AiProtocol.GEMINI -> GeminiClient
    }
}

// ── 公共工具 ────────────────────────────────────────────────────────────────

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Android 的 `org.json` 对显式 `null` 值调用 `optString` 会返回字符串 `"null"`
 * （与缺失键不同）。协议里大量可选字段是显式 null，直接透传会把
 * `finish_reason` 误判成结束、把工具 id 变成 "null"。
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null
    else optString(key, "").takeIf { it.isNotBlank() && it != "null" }

/**
 * 把一个 SSE 帧的 data 解析成一个或多个 JSON 对象。
 *
 * - 标准情况：一帧一个对象；
 * - 兼容没有空行分隔的实现：多个 JSON 挤在同一个 data 里，按行拆开逐个解析；
 * - 兼容非 SSE 的整段 JSON（[SseReader] 也会把裸 JSON 行收进 data）。
 */
private fun parseJsonObjects(data: String): List<JSONObject> {
    runCatching { JSONObject(data) }.getOrNull()?.let { return listOf(it) }
    return data.lineSequence()
        .map { it.trim().removePrefix("data:").trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        .toList()
}

private fun JSONObject.asBody() = toString().toRequestBody(JSON_MEDIA_TYPE)

/** 流式请求的通用执行骨架：鉴权头由各实现自行加到 [Request.Builder] 上。 */
private fun streamRequest(
    builder: Request.Builder,
    parser: StreamParser,
): Flow<AiEvent> = flow {
    val call = AiHttp.streaming.newCall(builder.build())
    try {
        val response = call.execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(AiEvent.Failed(AiError.fromHttp(resp.code, resp.body?.string())))
                return@use
            }
            val body = resp.body ?: run {
                emit(AiEvent.Failed(AiError(AiErrorKind.PARSE, "empty response body")))
                return@use
            }
            SseReader(body.source().asLineSource()).read { frame ->
                parser.parse(frame).forEach { emit(it) }
            }
            parser.finish().forEach { emit(it) }
        }
    } catch (ce: CancellationException) {
        call.cancel()
        throw ce
    } catch (io: java.io.IOException) {
        // 用户主动取消时 OkHttp 会抛 IOException("Canceled")，不要当成网络错误展示
        if (call.isCanceled()) throw CancellationException("cancelled")
        emit(AiEvent.Failed(AiError.fromThrowable(io)))
    } catch (t: Throwable) {
        emit(AiEvent.Failed(AiError.fromThrowable(t)))
    }
}.flowOn(Dispatchers.IO)

private suspend fun probeRequest(
    build: () -> Request,
    block: (okhttp3.Response) -> AiProbeResult,
): AiProbeResult = withContext(Dispatchers.IO) {
    val started = System.currentTimeMillis()
    val call = try {
        AiHttp.blocking.newCall(build())
    } catch (t: Throwable) {
        return@withContext AiProbeResult(false, 0, AiError.fromThrowable(t))
    }
    try {
        call.execute().use { resp ->
            block(resp).copy(latencyMs = System.currentTimeMillis() - started)
        }
    } catch (t: Throwable) {
        AiProbeResult(false, System.currentTimeMillis() - started, AiError.fromThrowable(t))
    }
}

/** 三种协议的流式解析器统一接口（无状态调用，状态在实例字段里）。 */
internal interface StreamParser {
    fun parse(frame: SseFrame): List<AiEvent>
    fun finish(): List<AiEvent>
}

// ── OpenAI Chat Completions ────────────────────────────────────────────────

internal class OpenAiStreamParser : StreamParser {
    private var finished = false
    private var stop: AiStopReason = AiStopReason.END_TURN
    private var sawToolCall = false

    override fun parse(frame: SseFrame): List<AiEvent> =
        parseJsonObjects(frame.data).flatMap { parseOne(it) }

    /**
     * 单个 JSON 对象。同时兼容两种形态：
     * - 流式 chunk：`choices[0].delta.content`
     * - 非流式完整响应：`choices[0].message.content`
     *   （部分网关/代理忽略 `stream: true`，直接返回整段 JSON）
     */
    private fun parseOne(json: JSONObject): List<AiEvent> {
        val events = mutableListOf<AiEvent>()

        json.optJSONObject("usage")?.let { usage ->
            val input = usage.optInt("prompt_tokens", -1).takeIf { it >= 0 }
            val output = usage.optInt("completion_tokens", -1).takeIf { it >= 0 }
            if (input != null || output != null) events += AiEvent.Usage(input, output)
        }

        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return events
        val delta = choice.optJSONObject("delta")
        val message = choice.optJSONObject("message")

        (delta?.optStringOrNull("content") ?: message?.optStringOrNull("content"))?.let {
            events += AiEvent.TextDelta(it)
        }
        // DeepSeek-R1 等把思考过程放在 reasoning_content，不进正文
        (delta?.optStringOrNull("reasoning_content") ?: message?.optStringOrNull("reasoning_content"))
            ?.let { events += AiEvent.ReasoningDelta(it) }

        (delta?.optJSONArray("tool_calls") ?: message?.optJSONArray("tool_calls"))?.let { calls ->
            for (i in 0 until calls.length()) {
                val tc = calls.optJSONObject(i) ?: continue
                val index = tc.optInt("index", i)
                val id = tc.optStringOrNull("id").orEmpty()
                val function = tc.optJSONObject("function")
                val name = function?.optStringOrNull("name").orEmpty()
                if (id.isNotBlank() || name.isNotBlank()) {
                    sawToolCall = true
                    events += AiEvent.ToolCallStarted(index, id, name)
                }
                function?.optStringOrNull("arguments")?.let {
                    events += AiEvent.ToolArgsDelta(index, it)
                }
            }
        }

        choice.optStringOrNull("finish_reason")?.let { reason ->
            stop = mapOpenAiStop(reason, sawToolCall)
            finished = true
            events += AiEvent.Finished(stop)
        }
        return events
    }

    override fun finish(): List<AiEvent> =
        if (finished) emptyList()
        else listOf(AiEvent.Finished(if (sawToolCall) AiStopReason.TOOL_USE else AiStopReason.END_TURN))
}

object OpenAiChatClient : AiProtocolClient {
    override val protocol = AiProtocol.OPENAI_CHAT

    override fun stream(config: AiConfig, request: AiRequest, apiKey: String): Flow<AiEvent> {
        val cfg = config.normalized()
        val builder = Request.Builder()
            .url(cfg.endpoint())
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "text/event-stream")
            .post(buildBody(cfg, request))
        return streamRequest(builder, OpenAiStreamParser())
    }

    private fun buildBody(cfg: AiConfig, request: AiRequest): okhttp3.RequestBody {
        val messages = JSONArray()
        if (request.system.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", request.system))
        }
        request.messages.forEach { message ->
            if (message.role == AiRole.SYSTEM) return@forEach
            val role = if (message.role == AiRole.ASSISTANT) "assistant" else "user"
            val content = message.text
            if (content.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        return JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            put("temperature", cfg.temperature.toDouble())
            put("max_tokens", cfg.maxOutputTokens)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
        }.asBody()
    }

    override suspend fun probe(config: AiConfig, apiKey: String): AiProbeResult {
        val cfg = config.normalized()
        return probeRequest(
            build = {
                Request.Builder()
                    .url(cfg.endpoint())
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .post(
                        JSONObject().apply {
                            put("model", cfg.model)
                            put(
                                "messages",
                                JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
                            )
                            put("max_tokens", 1)
                        }.asBody()
                    )
                    .build()
            },
        ) { resp ->
            if (resp.isSuccessful) {
                AiProbeResult(true, 0)
            } else {
                AiProbeResult(false, 0, AiError.fromHttp(resp.code, resp.body?.string()))
            }
        }
    }
}

internal fun mapOpenAiStop(reason: String, sawToolCall: Boolean): AiStopReason = when {
    reason == "tool_calls" || sawToolCall -> AiStopReason.TOOL_USE
    reason == "length" -> AiStopReason.MAX_TOKENS
    reason == "content_filter" -> AiStopReason.CONTENT_FILTER
    reason == "stop" -> AiStopReason.END_TURN
    else -> AiStopReason.UNKNOWN
}

// ── Anthropic Messages ─────────────────────────────────────────────────────

internal class AnthropicStreamParser : StreamParser {
    private var finished = false
    private var stop: AiStopReason = AiStopReason.END_TURN
    private var sawToolCall = false

    override fun parse(frame: SseFrame): List<AiEvent> =
        parseJsonObjects(frame.data).flatMap { parseOne(it, frame.event) }

    private fun parseOne(json: JSONObject, event: String?): List<AiEvent> {
        val events = mutableListOf<AiEvent>()
        val type = event ?: json.optStringOrNull("type").orEmpty()

        when (type) {
            "message_start" -> {
                val usage = json.optJSONObject("message")?.optJSONObject("usage")
                val input = usage?.optInt("input_tokens", -1)?.takeIf { it >= 0 }
                if (input != null) events += AiEvent.Usage(input, null)
            }

            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                if (block?.optString("type") == "tool_use") {
                    sawToolCall = true
                    events += AiEvent.ToolCallStarted(
                        index = json.optInt("index", 0),
                        id = block.optString("id", ""),
                        name = block.optString("name", ""),
                    )
                }
            }

            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return events
                when (delta.optString("type")) {
                    "text_delta" -> {
                        delta.optStringOrNull("text")?.let { events += AiEvent.TextDelta(it) }
                    }

                    "thinking_delta" -> {
                        delta.optStringOrNull("thinking")?.let { events += AiEvent.ReasoningDelta(it) }
                    }

                    "input_json_delta" -> {
                        delta.optStringOrNull("partial_json")?.let { partial ->
                            sawToolCall = true
                            events += AiEvent.ToolArgsDelta(json.optInt("index", 0), partial)
                        }
                    }
                }
            }

            "message_delta" -> {
                json.optJSONObject("delta")?.optStringOrNull("stop_reason")
                    ?.let { stop = mapAnthropicStop(it, sawToolCall) }
                val output = json.optJSONObject("usage")?.optInt("output_tokens", -1)?.takeIf { it >= 0 }
                if (output != null) events += AiEvent.Usage(null, output)
            }

            "message_stop" -> {
                finished = true
                events += AiEvent.Finished(stop)
            }

            // 非流式完整响应（type = message）：部分代理会忽略 stream=true
            "message" -> {
                val blocks = json.optJSONArray("content")
                if (blocks != null) {
                    for (i in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(i) ?: continue
                        when (block.optString("type")) {
                            "text" -> block.optStringOrNull("text")
                                ?.let { events += AiEvent.TextDelta(it) }

                            "thinking" -> block.optStringOrNull("thinking")
                                ?.let { events += AiEvent.ReasoningDelta(it) }

                            "tool_use" -> {
                                sawToolCall = true
                                events += AiEvent.ToolCallStarted(
                                    index = i,
                                    id = block.optString("id", ""),
                                    name = block.optString("name", ""),
                                )
                                events += AiEvent.ToolArgsDelta(
                                    i,
                                    block.optJSONObject("input")?.toString() ?: "{}",
                                )
                            }
                        }
                    }
                }
                stop = mapAnthropicStop(json.optString("stop_reason", ""), sawToolCall)
                finished = true
                events += AiEvent.Finished(stop)
            }

            "error" -> {
                val message = json.optJSONObject("error")?.optStringOrNull("message")
                    ?: "stream error"
                events += AiEvent.Failed(AiError(AiErrorKind.INVALID_REQUEST, message))
            }
        }
        return events
    }

    override fun finish(): List<AiEvent> =
        if (finished) emptyList()
        else listOf(AiEvent.Finished(if (sawToolCall) AiStopReason.TOOL_USE else AiStopReason.END_TURN))
}

internal fun mapAnthropicStop(reason: String, sawToolCall: Boolean): AiStopReason = when {
    reason == "tool_use" || sawToolCall -> AiStopReason.TOOL_USE
    reason == "max_tokens" -> AiStopReason.MAX_TOKENS
    reason == "end_turn" || reason == "stop_sequence" -> AiStopReason.END_TURN
    reason == "refusal" -> AiStopReason.CONTENT_FILTER
    else -> AiStopReason.UNKNOWN
}

object AnthropicClient : AiProtocolClient {
    override val protocol = AiProtocol.ANTHROPIC_MESSAGES

    override fun stream(config: AiConfig, request: AiRequest, apiKey: String): Flow<AiEvent> {
        val cfg = config.normalized()
        val builder = Request.Builder()
            .url(cfg.endpoint())
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .post(buildBody(cfg, request))
        return streamRequest(builder, AnthropicStreamParser())
    }

    private fun buildBody(cfg: AiConfig, request: AiRequest): okhttp3.RequestBody {
        // Anthropic 要求 user/assistant 交替：相邻同角色合并，避免 400
        val merged = mutableListOf<Pair<String, StringBuilder>>()
        request.messages.forEach { message ->
            if (message.role == AiRole.SYSTEM) return@forEach
            val role = if (message.role == AiRole.ASSISTANT) "assistant" else "user"
            val text = message.text
            if (text.isBlank()) return@forEach
            if (merged.lastOrNull()?.first == role) {
                merged.last().second.append("\n\n").append(text)
            } else {
                merged += role to StringBuilder(text)
            }
        }
        val messages = JSONArray().apply {
            merged.forEach { (role, text) ->
                put(JSONObject().put("role", role).put("content", text.toString()))
            }
        }
        return JSONObject().apply {
            put("model", cfg.model)
            put("max_tokens", cfg.maxOutputTokens)
            if (request.system.isNotBlank()) put("system", request.system)
            put("messages", messages)
            // Claude 的 temperature 取值 0..1
            put("temperature", cfg.temperature.coerceIn(0f, 1f).toDouble())
            put("stream", true)
        }.asBody()
    }

    override suspend fun probe(config: AiConfig, apiKey: String): AiProbeResult {
        val cfg = config.normalized()
        return probeRequest(
            build = {
                Request.Builder()
                    .url(cfg.endpoint())
                    .header("x-api-key", apiKey.trim())
                    .header("anthropic-version", "2023-06-01")
                    .post(
                        JSONObject().apply {
                            put("model", cfg.model)
                            put("max_tokens", 1)
                            put(
                                "messages",
                                JSONArray().put(
                                    JSONObject().put("role", "user").put("content", "ping"),
                                ),
                            )
                        }.asBody()
                    )
                    .build()
            },
        ) { resp ->
            if (resp.isSuccessful) {
                AiProbeResult(true, 0)
            } else {
                AiProbeResult(false, 0, AiError.fromHttp(resp.code, resp.body?.string()))
            }
        }
    }
}

// ── Google Gemini ──────────────────────────────────────────────────────────

internal class GeminiStreamParser : StreamParser {
    private var finished = false
    private var stop: AiStopReason = AiStopReason.END_TURN
    private var sawToolCall = false

    override fun parse(frame: SseFrame): List<AiEvent> =
        parseJsonObjects(frame.data).flatMap { parseOne(it) }

    private fun parseOne(json: JSONObject): List<AiEvent> {
        val events = mutableListOf<AiEvent>()

        json.optJSONObject("usageMetadata")?.let { usage ->
            val input = usage.optInt("promptTokenCount", -1).takeIf { it >= 0 }
            val output = usage.optInt("candidatesTokenCount", -1).takeIf { it >= 0 }
            if (input != null || output != null) events += AiEvent.Usage(input, output)
        }

        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return events
        candidate.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("functionCall")?.let { call ->
                    sawToolCall = true
                    events += AiEvent.ToolCallStarted(0, "", call.optStringOrNull("name").orEmpty())
                    // Gemini 一次性给完整 args（对象），这里转成 JSON 字符串统一处理
                    events += AiEvent.ToolArgsDelta(
                        0,
                        call.optJSONObject("args")?.toString() ?: "{}",
                    )
                }
                part.optStringOrNull("text")?.let { text ->
                    if (part.optBoolean("thought", false)) {
                        events += AiEvent.ReasoningDelta(text)
                    } else {
                        events += AiEvent.TextDelta(text)
                    }
                }
            }
        }

        candidate.optStringOrNull("finishReason")?.let { reason ->
            if (!finished) {
                stop = mapGeminiStop(reason, sawToolCall)
                finished = true
                events += AiEvent.Finished(stop)
            }
        }
        return events
    }

    override fun finish(): List<AiEvent> =
        if (finished) emptyList()
        else listOf(AiEvent.Finished(if (sawToolCall) AiStopReason.TOOL_USE else AiStopReason.END_TURN))
}

internal fun mapGeminiStop(reason: String, sawToolCall: Boolean): AiStopReason = when {
    sawToolCall -> AiStopReason.TOOL_USE
    reason == "MAX_TOKENS" -> AiStopReason.MAX_TOKENS
    reason == "SAFETY" || reason == "RECITATION" || reason == "BLOCKLIST" ->
        AiStopReason.CONTENT_FILTER
    reason == "STOP" || reason == "FINISH_REASON_UNSPECIFIED" -> AiStopReason.END_TURN
    else -> AiStopReason.UNKNOWN
}

object GeminiClient : AiProtocolClient {
    override val protocol = AiProtocol.GEMINI

    override fun stream(config: AiConfig, request: AiRequest, apiKey: String): Flow<AiEvent> {
        val cfg = config.normalized()
        val builder = Request.Builder()
            .url(cfg.endpoint())
            .header("x-goog-api-key", apiKey.trim())
            .header("Accept", "text/event-stream")
            .post(buildBody(cfg, request))
        return streamRequest(builder, GeminiStreamParser())
    }

    private fun buildBody(cfg: AiConfig, request: AiRequest): okhttp3.RequestBody {
        val contents = JSONArray()
        var lastRole: String? = null
        request.messages.forEach { message ->
            if (message.role == AiRole.SYSTEM) return@forEach
            val role = if (message.role == AiRole.ASSISTANT) "model" else "user"
            val text = message.text
            if (text.isBlank()) return@forEach
            if (role == lastRole) {
                // 连续同角色合并为一个 content，避免模型把同段话拆成多次发言
                val last = contents.optJSONObject(contents.length() - 1)
                val parts = last?.optJSONArray("parts")
                parts?.put(JSONObject().put("text", text))
            } else {
                contents.put(
                    JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    }
                )
                lastRole = role
            }
        }
        return JSONObject().apply {
            if (request.system.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", request.system)),
                    ),
                )
            }
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", cfg.temperature.toDouble())
                    put("maxOutputTokens", cfg.maxOutputTokens)
                },
            )
        }.asBody()
    }

    override suspend fun probe(config: AiConfig, apiKey: String): AiProbeResult {
        val cfg = config.normalized()
        return probeRequest(
            build = {
                Request.Builder()
                    .url(cfg.probeEndpoint())
                    .header("x-goog-api-key", apiKey.trim())
                    .post(
                        JSONObject().apply {
                            put(
                                "contents",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("role", "user")
                                        put(
                                            "parts",
                                            JSONArray().put(JSONObject().put("text", "ping")),
                                        )
                                    }
                                ),
                            )
                            put(
                                "generationConfig",
                                JSONObject().put("maxOutputTokens", 1),
                            )
                        }.asBody()
                    )
                    .build()
            },
        ) { resp ->
            if (resp.isSuccessful) {
                AiProbeResult(true, 0)
            } else {
                AiProbeResult(false, 0, AiError.fromHttp(resp.code, resp.body?.string()))
            }
        }
    }
}
