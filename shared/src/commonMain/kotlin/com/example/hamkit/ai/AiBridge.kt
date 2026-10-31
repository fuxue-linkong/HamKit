package com.example.hamkit.ai

/**
 * Swift 桥接层。
 *
 * 为什么需要它：Kotlin/Native 导出到 Swift 之后，
 * 1) 带默认值的数据类只能全参数构造（默认值不生效）；
 * 2) 嵌套 `List<AiMessage>` 的持久化、转录 JSON 都得各写一遍序列化。
 *
 * 所以 Swift 侧不直接 new 这些类型，而是走这里：配置与会话都表示为 JSON 字符串，
 * 由内核负责编解码（有单测保证），Swift 只持有字符串。
 */
object AiBridge {

    // ── 配置 ────────────────────────────────────────────────────────────────

    fun defaultConfig(): AiConfig = AiConfig.defaults()

    /** 服务商预设名列表（逗号分隔，顺序即 UI 顺序）。 */
    fun presetNames(): String = AiPreset.entries.joinToString(",") { it.name }

    fun presetLabel(name: String): String = AiPreset.fromName(name).label

    fun presetProtocol(name: String): String = AiPreset.fromName(name).protocol.name

    fun presetBaseUrl(name: String): String = AiPreset.fromName(name).baseUrl

    fun presetDefaultModel(name: String): String = AiPreset.fromName(name).defaultModel

    /** 预设建议模型（逗号分隔）。 */
    fun presetModels(name: String): String = AiPreset.fromName(name).models

    /** 协议名列表（逗号分隔）：Chat Completions / Messages / Gemini。 */
    fun protocolNames(): String = AiProtocol.entries.joinToString(",") { it.name }

    fun protocolLabel(name: String): String = AiProtocol.fromName(name).label

    fun configToJson(config: AiConfig): String {
        val cfg = config.normalized()
        return Json.obj()
            .put("protocol", cfg.protocol.name)
            .put("preset", cfg.preset.name)
            .put("baseUrl", cfg.baseUrl)
            .put("model", cfg.model)
            .put("temperature", cfg.temperature)
            .put("maxOutputTokens", cfg.maxOutputTokens)
            .put("saveHistory", cfg.saveHistory)
            .put("historyLimit", cfg.historyLimit)
            .put("allowCleartext", cfg.allowCleartext)
            .put("systemPrompt", cfg.systemPrompt)
            .put("disabledTools", cfg.disabledTools)
            .put("confirmSideEffects", cfg.confirmSideEffects)
            .put("maxToolRounds", cfg.maxToolRounds)
            .stringify()
    }

    /**
     * 用「逐字段」的方式生成配置 JSON。
     *
     * Swift 侧没有 Kotlin 的 `copy()`，改一个字段要重建整个对象；与其在 Swift 里
     * 重新拼一遍 JSON（schema 会各自漂移），不如把这一步也交给内核。
     */
    @Suppress("LongParameterList")
    fun configJson(
        protocolName: String,
        presetName: String,
        baseUrl: String,
        model: String,
        temperature: Double,
        maxOutputTokens: Int,
        saveHistory: Boolean,
        historyLimit: Int,
        allowCleartext: Boolean,
        systemPrompt: String,
        disabledTools: String,
        confirmSideEffects: Boolean,
        maxToolRounds: Int,
    ): String = configToJson(
        AiConfig(
            protocol = AiProtocol.fromName(protocolName),
            preset = AiPreset.fromName(presetName),
            baseUrl = baseUrl,
            model = model,
            temperature = temperature,
            maxOutputTokens = maxOutputTokens,
            saveHistory = saveHistory,
            historyLimit = historyLimit,
            allowCleartext = allowCleartext,
            systemPrompt = systemPrompt,
            disabledTools = disabledTools,
            confirmSideEffects = confirmSideEffects,
            maxToolRounds = maxToolRounds,
        )
    )

    fun configFromJson(json: String): AiConfig {        val root = Json.parse(json)?.objOrNull() ?: return AiConfig.defaults()
        val defaults = AiConfig.defaults()
        return AiConfig(
            protocol = AiProtocol.fromName(root["protocol"]?.stringOrNull()),
            preset = AiPreset.fromName(root["preset"]?.stringOrNull()),
            baseUrl = root["baseUrl"]?.stringOrNull() ?: defaults.baseUrl,
            model = root["model"]?.stringOrNull() ?: defaults.model,
            temperature = (root["temperature"] as? Json.Num)?.value ?: defaults.temperature,
            maxOutputTokens = root["maxOutputTokens"]?.intOrNull() ?: defaults.maxOutputTokens,
            saveHistory = root["saveHistory"]?.boolOrNull() ?: defaults.saveHistory,
            historyLimit = root["historyLimit"]?.intOrNull() ?: defaults.historyLimit,
            allowCleartext = root["allowCleartext"]?.boolOrNull() ?: defaults.allowCleartext,
            systemPrompt = root["systemPrompt"]?.stringOrNull() ?: "",
            disabledTools = root["disabledTools"]?.stringOrNull() ?: "",
            confirmSideEffects = root["confirmSideEffects"]?.boolOrNull()
                ?: defaults.confirmSideEffects,
            maxToolRounds = root["maxToolRounds"]?.intOrNull() ?: defaults.maxToolRounds,
        ).normalized()
    }

    // ── 会话（历史持久化 + Markdown 导出） ─────────────────────────────────

    fun messagesToJson(messages: List<AiMessage>): String {
        val array = Json.arr()
        messages.forEach { message ->
            val obj = Json.obj().put("role", message.role.name)
            when (message.role) {
                AiRole.USER, AiRole.ASSISTANT -> {
                    if (message.text.isNotEmpty()) obj.put("text", message.text)
                    if (message.calls.isNotEmpty()) {
                        obj.put(
                            "calls",
                            Json.arr().also { calls ->
                                message.calls.forEach { call ->
                                    calls.add(
                                        Json.obj()
                                            .put("id", call.id)
                                            .put("name", call.name)
                                            .put("args", call.argsJson),
                                    )
                                }
                            },
                        )
                    }
                    if (message.images.isNotEmpty()) {
                        obj.put(
                            "images",
                            Json.arr().also { images ->
                                message.images.forEach { image ->
                                    images.add(
                                        Json.obj()
                                            .put("mime", image.mime)
                                            .put("data", image.base64),
                                    )
                                }
                            },
                        )
                    }
                }

                AiRole.TOOL -> {
                    obj.put("text", message.text)
                    obj.put("toolCallId", message.toolCallId)
                    obj.put("toolName", message.toolName)
                    if (message.isError) obj.put("isError", true)
                }

                AiRole.SYSTEM -> obj.put("text", message.text)
            }
            array.add(obj)
        }
        return array.stringify()
    }

    fun messagesFromJson(json: String): List<AiMessage> {
        val array = Json.parse(json)?.arrOrNull() ?: return emptyList()
        return array.items.mapNotNull { item ->
            val obj = item.objOrNull() ?: return@mapNotNull null
            val role = when (obj["role"]?.stringOrNull()) {
                "USER" -> AiRole.USER
                "ASSISTANT" -> AiRole.ASSISTANT
                "SYSTEM" -> AiRole.SYSTEM
                "TOOL" -> AiRole.TOOL
                else -> return@mapNotNull null
            }
            AiMessage(
                role = role,
                text = obj["text"]?.stringOrNull() ?: "",
                calls = obj["calls"]?.arrOrNull()?.items.orEmpty().mapNotNull { call ->
                    val c = call.objOrNull() ?: return@mapNotNull null
                    AiToolCall(
                        id = c["id"]?.stringOrNull() ?: "",
                        name = c["name"]?.stringOrNull() ?: "",
                        argsJson = c["args"]?.stringOrNull() ?: "{}",
                    )
                },
                images = obj["images"]?.arrOrNull()?.items.orEmpty().mapNotNull { image ->
                    val i = image.objOrNull() ?: return@mapNotNull null
                    AiImage(
                        base64 = i["data"]?.stringOrNull() ?: "",
                        mime = i["mime"]?.stringOrNull() ?: "image/jpeg",
                    )
                },
                toolCallId = obj["toolCallId"]?.stringOrNull() ?: "",
                toolName = obj["toolName"]?.stringOrNull() ?: "",
                isError = obj["isError"]?.boolOrNull() ?: false,
            )
        }
    }

    /** 会话标题：第一条用户消息的前 24 个字。 */
    fun conversationTitle(messages: List<AiMessage>): String {
        val text = messages.firstOrNull { it.role == AiRole.USER }?.text?.trim().orEmpty()
        if (text.isEmpty()) return "新对话"
        val single = text.replace('\n', ' ')
        return if (single.length <= 24) single else single.take(24) + "…"
    }

    /** 导出为 Markdown（iOS 侧分享用）。 */
    fun messagesToMarkdown(messages: List<AiMessage>, title: String): String {
        val sb = StringBuilder("# $title\n\n")
        messages.forEach { message ->
            when (message.role) {
                AiRole.USER -> {
                    sb.append("**我**：").append(message.text.replace("\n", "\n> ")).append("\n\n")
                    if (message.images.isNotEmpty()) {
                        sb.append("（附 ${message.images.size} 张图片）\n\n")
                    }
                }

                AiRole.ASSISTANT -> {
                    if (message.text.isNotBlank()) {
                        sb.append("**助手**：\n\n").append(message.text).append("\n\n")
                    }
                    if (message.calls.isNotEmpty()) {
                        sb.append("> 调用工具：")
                        sb.append(message.calls.joinToString("、") { it.name })
                        sb.append("\n\n")
                    }
                }

                AiRole.TOOL -> sb.append(
                    "> 工具 `" + message.toolName + "` 结果：" +
                        message.text.take(200) + "\n\n",
                )

                AiRole.SYSTEM -> Unit
            }
        }
        return sb.toString()
    }

    /** 工具列表（名称、标题、风险），供设置页做开关。 */
    fun toolCatalogJson(toolbox: AiToolHost): String {
        val array = Json.arr()
        toolbox.specs().forEach { spec ->
            array.add(
                Json.obj()
                    .put("name", spec.name)
                    .put("title", spec.title)
                    .put("risk", spec.risk.name),
            )
        }
        return array.stringify()
    }

    // ── 错误构造（Swift 侧不要直接碰 companion / sealed 层级） ─────────────

    fun userMessage(text: String): AiMessage = AiMessage.user(text)

    fun assistantMessage(text: String): AiMessage = AiMessage.assistant(text)

    fun httpError(status: Int, body: String): AiError = AiError.fromHttp(status, body)

    fun networkError(detail: String): AiError = AiError.network(detail)

    fun cancelledError(): AiError = AiError.cancelled()

    fun errorText(error: AiError): String = error.displayText
}
