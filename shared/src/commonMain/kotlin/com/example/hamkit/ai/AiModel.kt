package com.example.hamkit.ai

/**
 * AI 助手的跨平台数据模型（Swift 可直接构造）。
 *
 * 设计取舍：协议差异（OpenAI 兼容 / Anthropic / Gemini）全部收敛在
 * [AiProtocolCodec] 里，对话状态机与 UI 只认这里的类型。
 *
 * **为什么事件是「一个 data class + 枚举」而不是 sealed class**：
 * Kotlin/Native 把 sealed 层级导出成一组类，Swift 侧要写 `if let e = event as? ...`
 * 逐类型判断；用一个带 kind 的扁平结构，Swift 端 switch 一下就能处理，也不会因为
 * 新增事件类型而编译失败。
 */

/** 三种 API 协议。 */
enum class AiProtocol(val label: String) {
    OPENAI_CHAT("Chat Completions"),
    ANTHROPIC_MESSAGES("Messages"),
    GEMINI("Gemini");

    companion object {
        fun fromName(name: String?): AiProtocol =
            entries.firstOrNull { it.name == name } ?: OPENAI_CHAT
    }
}

/**
 * 服务商预设：只用于填 Base URL 与常用模型建议，不改变协议行为。
 * 默认 DeepSeek：国内可直连、支持工具调用。
 */
enum class AiPreset(
    val label: String,
    val protocol: AiProtocol,
    val baseUrl: String,
    val defaultModel: String,
    val models: String,
) {
    DEEPSEEK(
        "DeepSeek", AiProtocol.OPENAI_CHAT,
        "https://api.deepseek.com/v1", "deepseek-chat", "deepseek-chat,deepseek-reasoner",
    ),
    OPENAI(
        "OpenAI", AiProtocol.OPENAI_CHAT,
        "https://api.openai.com/v1", "gpt-4.1", "gpt-4.1,gpt-4.1-mini,gpt-4o",
    ),
    ANTHROPIC(
        "Anthropic", AiProtocol.ANTHROPIC_MESSAGES,
        "https://api.anthropic.com", "claude-sonnet-4-5", "claude-sonnet-4-5,claude-haiku-4-5",
    ),
    GOOGLE(
        "Gemini", AiProtocol.GEMINI,
        "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
        "gemini-2.5-pro,gemini-2.5-flash",
    ),
    KIMI(
        "Kimi", AiProtocol.OPENAI_CHAT,
        "https://api.moonshot.cn/v1", "moonshot-v1-8k", "moonshot-v1-8k,kimi-k2",
    ),
    GLM(
        "GLM", AiProtocol.OPENAI_CHAT,
        "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", "glm-4.6,glm-4-flash",
    ),
    QWEN(
        "通义千问", AiProtocol.OPENAI_CHAT,
        "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen-plus,qwen-max",
    ),
    OPENROUTER(
        "OpenRouter", AiProtocol.OPENAI_CHAT,
        "https://openrouter.ai/api/v1", "", "",
    ),
    CUSTOM(
        "自定义", AiProtocol.OPENAI_CHAT, "", "", "",
    );

    /** 建议模型列表（逗号分隔，便于 Swift 直接 split）。 */
    fun modelList(): List<String> = models.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun fromName(name: String?): AiPreset =
            entries.firstOrNull { it.name == name } ?: DEEPSEEK
    }
}

/**
 * AI 配置。
 *
 * [systemPrompt] 为空表示使用 [AiPrompts.DEFAULT_SYSTEM_PROMPT]；
 * [disabledTools] 里的工具不会出现在请求里（模型完全看不到）。
 */
data class AiConfig(
    val protocol: AiProtocol = AiProtocol.OPENAI_CHAT,
    val preset: AiPreset = AiPreset.DEEPSEEK,
    val baseUrl: String = AiPreset.DEEPSEEK.baseUrl,
    val model: String = AiPreset.DEEPSEEK.defaultModel,
    val temperature: Double = 0.6,
    val maxOutputTokens: Int = 2048,
    val saveHistory: Boolean = true,
    val historyLimit: Int = 200,
    val allowCleartext: Boolean = false,
    val systemPrompt: String = "",
    /** 逗号分隔的禁用工具名。 */
    val disabledTools: String = "",
    /** 副作用工具（创建提醒等）执行前是否二次确认。 */
    val confirmSideEffects: Boolean = true,
    /** 单轮对话里最多允许的「工具往返」次数。 */
    val maxToolRounds: Int = 4,
) {

    fun normalized(): AiConfig = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        temperature = temperature.coerceIn(0.0, 2.0),
        maxOutputTokens = maxOutputTokens.coerceIn(64, 8192),
        maxToolRounds = maxToolRounds.coerceIn(1, 8),
    )

    val effectiveSystemPrompt: String
        get() = systemPrompt.trim().ifEmpty { AiPrompts.DEFAULT_SYSTEM_PROMPT }

    val disabledToolNames: Set<String>
        get() = disabledTools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 实际请求地址（按协议拼接，Base URL 允许带或不带版本段）。 */
    fun endpoint(): String {
        val cfg = normalized()
        val base = cfg.baseUrl
        return when (cfg.protocol) {
            AiProtocol.OPENAI_CHAT -> "$base/chat/completions"
            AiProtocol.ANTHROPIC_MESSAGES ->
                if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"

            AiProtocol.GEMINI -> {
                val root = if (base.endsWith("/v1beta") || base.endsWith("/v1")) base else "$base/v1beta"
                "$root/models/${cfg.model}:streamGenerateContent?alt=sse"
            }
        }
    }

    /** 连接测试用的非流式地址（Gemini 的流式地址带 `?alt=sse`，探测用普通地址）。 */
    fun probeEndpoint(): String {
        val cfg = normalized()
        val base = cfg.baseUrl
        return when (cfg.protocol) {
            AiProtocol.GEMINI -> {
                val root = if (base.endsWith("/v1beta") || base.endsWith("/v1")) base else "$base/v1beta"
                "$root/models/${cfg.model}:generateContent"
            }

            else -> cfg.endpoint()
        }
    }

    companion object {
        fun defaults(): AiConfig = AiConfig()
    }
}

// ── 对话内容 ────────────────────────────────────────────────────────────────

enum class AiRole { SYSTEM, USER, ASSISTANT, TOOL }

/** 一次工具调用（参数是完整 JSON 字符串，由流式增量拼接而成）。 */
data class AiToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/** 多模态图片（base64，不含 `data:` 前缀）。 */
data class AiImage(
    val base64: String,
    val mime: String = "image/jpeg",
)

/**
 * 一条对话消息。
 *
 * - USER：用 [text] + [images]；
 * - ASSISTANT：用 [text] + [calls]；
 * - TOOL：[toolCallId] + [text]（工具结果）+ [isError]。
 */
data class AiMessage(
    val role: AiRole,
    val text: String = "",
    val calls: List<AiToolCall> = emptyList(),
    val images: List<AiImage> = emptyList(),
    val toolCallId: String = "",
    val toolName: String = "",
    val isError: Boolean = false,
) {
    companion object {
        fun user(text: String): AiMessage = AiMessage(AiRole.USER, text = text)

        fun assistant(text: String): AiMessage = AiMessage(AiRole.ASSISTANT, text = text)

        fun toolResult(callId: String, name: String, content: String, isError: Boolean): AiMessage =
            AiMessage(
                role = AiRole.TOOL,
                text = content,
                toolCallId = callId,
                toolName = name,
                isError = isError,
            )
    }
}

// ── 工具 ────────────────────────────────────────────────────────────────────

/** 工具风险等级：READ_ONLY 直接执行；SIDE_EFFECT 默认要用户确认。 */
enum class AiToolRisk { READ_ONLY, NAVIGATION, SIDE_EFFECT }

/** 工具规格：名称/描述给模型看，[parametersJson] 是 JSON Schema。 */
data class AiToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val parametersJson: String,
    val risk: AiToolRisk = AiToolRisk.READ_ONLY,
    val longRunning: Boolean = false,
)

/** 工具执行结果：给模型的文本 + 给 UI 的行摘要。 */
data class AiToolResult(
    val modelText: String,
    val summary: String,
    val isError: Boolean = false,
    /** 可选卡片（JSON 字符串，由 UI 自行解释；空串表示没有卡片）。 */
    val cardJson: String = "",
)

/** 回传模型的工具结果最大字符数：超出截断，避免一次查询占满上下文。 */
const val MAX_TOOL_RESULT_CHARS: Int = 6000

/**
 * 截断工具结果（只作用于给模型的文本，UI 摘要与卡片保持原样）。
 *
 * 与 Android `truncateToolResult` 同义：`max` 是字符数上限，超出时保留前缀并追加说明，
 * 让模型知道「还有更多」而不是误以为结果就这么点。
 */
fun truncateToolResult(text: String, max: Int = MAX_TOOL_RESULT_CHARS): String =
    if (text.length <= max) text else text.take(max) + "\n…（结果已截断）"

// ── 流式事件 ────────────────────────────────────────────────────────────────

enum class AiEventKind {
    TEXT_DELTA,
    REASONING_DELTA,
    TOOL_CALL_STARTED,
    TOOL_ARGS_DELTA,
    USAGE,
    FINISHED,
    FAILED,
}

enum class AiStopReason { END_TURN, TOOL_USE, MAX_TOKENS, CONTENT_FILTER, UNKNOWN }

/**
 * 统一的流式事件（扁平结构，见文件头说明）。
 *
 * 一次流结束一定会收到 [AiEventKind.FINISHED] 或 [AiEventKind.FAILED] 其中之一。
 */
data class AiEvent(
    val kind: AiEventKind,
    val text: String = "",
    val index: Int = 0,
    val callId: String = "",
    val toolName: String = "",
    val stop: AiStopReason = AiStopReason.UNKNOWN,
    val error: AiError? = null,
    /** -1 表示该协议本次没有返回此计数。 */
    val inputTokens: Int = -1,
    val outputTokens: Int = -1,
)

// ── 错误 ────────────────────────────────────────────────────────────────────

enum class AiErrorKind {
    AUTH, RATE_LIMIT, INVALID_REQUEST, SERVER, NETWORK, TIMEOUT, PARSE, NO_CONFIG, CANCELLED
}

data class AiError(
    val kind: AiErrorKind,
    val message: String,
    val retryable: Boolean = false,
    val status: Int = 0,
) {
    /** UI 一行摘要（Swift 侧也会用，避免两边各写一套文案）。 */
    val displayText: String
        get() = when (kind) {
            AiErrorKind.AUTH -> "认证失败：$message（请检查 API Key）"
            AiErrorKind.RATE_LIMIT -> "请求过于频繁：$message"
            AiErrorKind.INVALID_REQUEST -> "请求被拒绝：$message"
            AiErrorKind.SERVER -> "服务端错误：$message"
            AiErrorKind.NETWORK -> "网络不可用：$message"
            AiErrorKind.TIMEOUT -> "请求超时：$message"
            AiErrorKind.PARSE -> "响应解析失败：$message"
            AiErrorKind.NO_CONFIG -> "尚未配置：$message"
            AiErrorKind.CANCELLED -> "已取消"
        }

    companion object {
        fun fromHttp(status: Int, body: String?): AiError {
            val kind = when (status) {
                401, 403 -> AiErrorKind.AUTH
                429 -> AiErrorKind.RATE_LIMIT
                in 500..599 -> AiErrorKind.SERVER
                else -> AiErrorKind.INVALID_REQUEST
            }
            return AiError(
                kind = kind,
                message = parseMessage(body) ?: "HTTP $status",
                retryable = kind == AiErrorKind.RATE_LIMIT || kind == AiErrorKind.SERVER,
                status = status,
            )
        }

        /** 兼容 OpenAI / Anthropic / Gemini 的错误体；取不到就用原文前 200 字。 */
        private fun parseMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val text = runCatching {
                val root = Json.parse(body)?.objOrNull() ?: return@runCatching null
                val err = root["error"]?.objOrNull()
                err?.get("message")?.stringOrNull()
                    ?: root["message"]?.stringOrNull()
                    ?: err?.get("type")?.stringOrNull()
            }.getOrNull()
            return (text ?: body).take(200)
        }

        fun noConfig(detail: String): AiError = AiError(AiErrorKind.NO_CONFIG, detail)

        fun cancelled(): AiError = AiError(AiErrorKind.CANCELLED, "cancelled")

        fun network(detail: String): AiError =
            AiError(AiErrorKind.NETWORK, detail, retryable = true)

        fun parse(detail: String): AiError = AiError(AiErrorKind.PARSE, detail)
    }
}

/** 连接测试结果。 */
data class AiProbeResult(
    val ok: Boolean,
    val latencyMs: Long,
    val error: AiError? = null,
    /** null 表示未探测或无法判断。 */
    val toolsSupported: Boolean? = null,
)
