package com.example.hamkit.data.ai

import com.example.hamkit.data.ai.tool.AiToolSpec
import org.json.JSONObject

/**
 * 三种 API 协议。
 *
 * 抽象层只认这三种；所有供应商差异（OpenAI 兼容、Claude、Gemini）都在各自
 * 的 [AiProtocolClient] 里收敛成统一的 [AiEvent] 流，UI 与对话状态机不感知协议。
 */
enum class AiProtocol(val label: String) {
    OPENAI_CHAT("Chat Completions"),
    ANTHROPIC_MESSAGES("Messages"),
    GEMINI("Gemini"),
}

/**
 * 服务商预设：只用于填充 Base URL 与常用模型建议，不改变协议行为。
 *
 * 默认 [DEEPSEEK]：国内可直连、支持工具调用（P1 起使用）。
 */
enum class AiPreset(
    val label: String,
    val protocol: AiProtocol,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String>,
) {
    DEEPSEEK(
        "DeepSeek", AiProtocol.OPENAI_CHAT,
        "https://api.deepseek.com/v1", "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner"),
    ),
    OPENAI(
        "OpenAI", AiProtocol.OPENAI_CHAT,
        "https://api.openai.com/v1", "gpt-4.1",
        listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o"),
    ),
    ANTHROPIC(
        "Anthropic", AiProtocol.ANTHROPIC_MESSAGES,
        "https://api.anthropic.com", "claude-sonnet-4-5",
        listOf("claude-sonnet-4-5", "claude-haiku-4-5"),
    ),
    GOOGLE(
        "Gemini", AiProtocol.GEMINI,
        "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
        listOf("gemini-2.5-pro", "gemini-2.5-flash"),
    ),
    KIMI(
        "Kimi", AiProtocol.OPENAI_CHAT,
        "https://api.moonshot.cn/v1", "moonshot-v1-8k",
        listOf("moonshot-v1-8k", "kimi-k2"),
    ),
    GLM(
        "GLM", AiProtocol.OPENAI_CHAT,
        "https://open.bigmodel.cn/api/paas/v4", "glm-4.6",
        listOf("glm-4.6", "glm-4-flash"),
    ),
    QWEN(
        "通义千问", AiProtocol.OPENAI_CHAT,
        "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
        listOf("qwen-plus", "qwen-max"),
    ),
    OPENROUTER(
        "OpenRouter", AiProtocol.OPENAI_CHAT,
        "https://openrouter.ai/api/v1", "",
        emptyList(),
    ),
    CUSTOM(
        "自定义", AiProtocol.OPENAI_CHAT, "", "", emptyList(),
    );

    companion object {
        fun fromName(name: String?): AiPreset =
            entries.firstOrNull { it.name == name } ?: DEEPSEEK
    }
}

/**
 * AI 配置（持久化在 `ai_settings` SharedPreferences，API Key 单独加密）。
 *
 * [allowCleartext] 默认 false：只允许 https；开启后也只允许私有地址 http
 * （判定见 [AiNetworkPolicy]），并不等于"全局放开"。
 */
data class AiConfig(
    val protocol: AiProtocol = AiProtocol.OPENAI_CHAT,
    val preset: AiPreset = AiPreset.DEEPSEEK,
    val baseUrl: String = AiPreset.DEEPSEEK.baseUrl,
    val model: String = AiPreset.DEEPSEEK.defaultModel,
    val temperature: Float = 0.6f,
    val maxOutputTokens: Int = 2048,
    val stream: Boolean = true,
    val autoRetry: Boolean = true,
    val saveHistory: Boolean = true,
    val historyLimit: Int = 200,
    val allowCleartext: Boolean = false,
    /** 自定义系统提示词；空串表示使用 [AiPrompts.DEFAULT_SYSTEM_PROMPT]。 */
    val systemPrompt: String = "",
    /** 被禁用的工具名（模型看不到，也不会调用）。 */
    val disabledTools: Set<String> = emptySet(),
    /** 副作用工具（创建提醒等）执行前是否二次确认。 */
    val confirmSideEffects: Boolean = true,
) {
    fun normalized(): AiConfig = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        temperature = temperature.coerceIn(0f, 2f),
        maxOutputTokens = maxOutputTokens.coerceIn(64, 8192),
    )

    /** 实际请求地址（按协议拼接，Base URL 允许带或不带版本段）。 */
    fun endpoint(): String {
        val cfg = normalized()
        val base = cfg.baseUrl
        return when (cfg.protocol) {
            AiProtocol.OPENAI_CHAT -> "$base/chat/completions"
            AiProtocol.ANTHROPIC_MESSAGES ->
                if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            AiProtocol.GEMINI -> {
                val root = when {
                    base.endsWith("/v1beta") || base.endsWith("/v1") -> base
                    else -> "$base/v1beta"
                }
                "$root/models/${cfg.model}:streamGenerateContent?alt=sse"
            }
        }
    }

    /** Gemini 的非流式地址（连接测试用）。 */
    fun probeEndpoint(): String {
        val cfg = normalized()
        val base = cfg.baseUrl
        val root = when {
            base.endsWith("/v1beta") || base.endsWith("/v1") -> base
            else -> "$base/v1beta"
        }
        return "$root/models/${cfg.model}:generateContent"
    }
}

// ── 统一消息模型 ────────────────────────────────────────────────────────────

enum class AiRole { SYSTEM, USER, ASSISTANT, TOOL }

sealed interface AiBlock {
    data class Text(val text: String) : AiBlock

    data class ToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
        /** Gemini 新模型的 thoughtSignature，必须原样回传（P1 使用）。 */
        val signature: String? = null,
    ) : AiBlock

    data class ToolResult(
        val callId: String,
        val content: String,
        val isError: Boolean = false,
        /** Gemini 的 functionResponse 需要工具名；其它协议用 callId 即可。 */
        val name: String = "",
    ) : AiBlock

    data class Reasoning(val text: String) : AiBlock

    /** 多模态图片（base64，不含 data: 前缀）。 */
    data class Image(val base64: String, val mime: String = "image/jpeg") : AiBlock
}

data class AiMessage(val role: AiRole, val blocks: List<AiBlock>) {
    val text: String
        get() = blocks.filterIsInstance<AiBlock.Text>().joinToString("") { it.text }

    companion object {
        fun user(text: String) = AiMessage(AiRole.USER, listOf(AiBlock.Text(text)))
        fun assistant(text: String) = AiMessage(AiRole.ASSISTANT, listOf(AiBlock.Text(text)))
    }
}

/** 一次对话请求（P1 会加入 tools 字段，事件模型已预留工具事件）。 */
data class AiRequest(
    val system: String,
    val messages: List<AiMessage>,
    /** 启用的工具；为空时不向模型暴露工具能力。 */
    val tools: List<AiToolSpec> = emptyList(),
)

enum class AiStopReason { END_TURN, TOOL_USE, MAX_TOKENS, CONTENT_FILTER, UNKNOWN }

/**
 * 统一流式事件。协议适配器负责把各家 SSE 映射到这里。
 */
sealed interface AiEvent {
    data class TextDelta(val text: String) : AiEvent
    data class ReasoningDelta(val text: String) : AiEvent
    data class ToolCallStarted(val index: Int, val id: String, val name: String) : AiEvent
    data class ToolArgsDelta(val index: Int, val delta: String) : AiEvent
    data class Usage(val input: Int?, val output: Int?) : AiEvent
    data class Finished(val stop: AiStopReason) : AiEvent
    data class Failed(val error: AiError) : AiEvent
}

// ── 错误 ────────────────────────────────────────────────────────────────────

enum class AiErrorKind {
    AUTH, RATE_LIMIT, INVALID_REQUEST, SERVER, NETWORK, TIMEOUT, PARSE, NO_CONFIG,
}

data class AiError(
    val kind: AiErrorKind,
    val message: String,
    val retryable: Boolean = false,
    val status: Int? = null,
) {
    companion object {
        /** 从 HTTP 错误响应构造；三种协议的 error 结构不同，这里做通用降级解析。 */
        fun fromHttp(status: Int, body: String?): AiError {
            val detail = parseMessage(body)
            val kind = when (status) {
                401, 403 -> AiErrorKind.AUTH
                429 -> AiErrorKind.RATE_LIMIT
                in 500..599 -> AiErrorKind.SERVER
                else -> AiErrorKind.INVALID_REQUEST
            }
            return AiError(
                kind = kind,
                message = detail ?: "HTTP $status",
                retryable = kind == AiErrorKind.RATE_LIMIT || kind == AiErrorKind.SERVER,
                status = status,
            )
        }

        /** 兼容 OpenAI / Anthropic / Gemini 的错误体，取不到就用原文前 200 字。 */
        private fun parseMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val text = runCatching {
                val root = JSONObject(body)
                val err = root.optJSONObject("error")
                fun clean(value: String?): String? =
                    value?.takeIf { it.isNotBlank() && it != "null" }
                when {
                    clean(err?.optString("message")) != null -> clean(err?.optString("message"))
                    clean(root.optString("message")) != null -> clean(root.optString("message"))
                    clean(err?.optString("type")) != null -> clean(err?.optString("type"))
                    else -> null
                }
            }.getOrNull()
            return (text ?: body).take(200)
        }

        fun fromThrowable(t: Throwable): AiError = when (t) {
            is java.net.SocketTimeoutException ->
                AiError(AiErrorKind.TIMEOUT, t.message ?: "timeout", retryable = true)
            is java.io.IOException ->
                AiError(AiErrorKind.NETWORK, t.message ?: "network error", retryable = true)
            else -> AiError(AiErrorKind.PARSE, t.message ?: t::class.java.simpleName)
        }
    }
}

/** 连接测试结果。 */
data class AiProbeResult(
    val ok: Boolean,
    val latencyMs: Long,
    val error: AiError? = null,
    /** 该模型/网关是否接受 tools（null 表示未探测或无法判断）。 */
    val toolsSupported: Boolean? = null,
)
