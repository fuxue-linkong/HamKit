package com.example.hamkit.data.ai

import org.json.JSONArray
import org.json.JSONObject

/** token 用量（供应商未返回时为 null，UI 显示"估算"或留空）。 */
data class AiUsage(val input: Int?, val output: Int?)

/** 会话列表用的摘要（索引里只存这些，避免列表页解密整个会话）。 */
data class AiSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/** 历史里保存的工具调用快照（只存展示所需字段）。 */
data class AiToolSnapshot(
    val callId: String,
    val name: String,
    val title: String,
    val state: String,
    val summary: String? = null,
)

/** 一条聊天消息（只存渲染所需的文本与元信息；P1 的卡片快照另加字段）。 */
data class AiChatMessage(
    val id: String,
    val role: AiRole,
    val text: String,
    val createdAt: Long,
    val reasoning: String? = null,
    val usage: AiUsage? = null,
    val elapsedMs: Long? = null,
    val error: String? = null,
    /** 该条消息关联的工具调用（历史里也要能展示）。 */
    val tools: List<AiToolSnapshot> = emptyList(),
)

/** 一个会话。 */
data class AiSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<AiChatMessage>,
)

/**
 * 会话 JSON 编解码（org.json，不引入新依赖）。
 *
 * 解码对缺失字段宽容：旧版本/损坏字段按默认值处理，单条消息解析失败则跳过，
 * 不让整份历史因为一条脏数据而不可读。
 */
object AiSessionCodec {

    fun encode(session: AiSession): String = JSONObject().apply {
        put("version", 1)
        put("id", session.id)
        put("title", session.title)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("messages", JSONArray().apply {
            session.messages.forEachIndexed { index, message ->
                put(JSONObject().apply {
                    put("id", message.id.ifBlank { "m${index}" })
                    put("role", message.role.name)
                    put("text", message.text)
                    put("createdAt", message.createdAt)
                    message.reasoning?.let { put("reasoning", it) }
                    message.usage?.let { usage ->
                        put("usage", JSONObject().apply {
                            usage.input?.let { put("input", it) }
                            usage.output?.let { put("output", it) }
                        })
                    }
                    message.elapsedMs?.let { put("elapsedMs", it) }
                    message.error?.let { put("error", it) }
                    if (message.tools.isNotEmpty()) {
                        put("tools", JSONArray().apply {
                            message.tools.forEach { tool ->
                                put(JSONObject().apply {
                                    put("callId", tool.callId)
                                    put("name", tool.name)
                                    put("title", tool.title)
                                    put("state", tool.state)
                                    tool.summary?.let { put("summary", it) }
                                })
                            }
                        })
                    }
                })
            }
        })
    }.toString()

    fun decode(json: String): AiSession? = runCatching {
        val root = JSONObject(json)
        val messages = mutableListOf<AiChatMessage>()
        val array = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val role = runCatching { AiRole.valueOf(item.optString("role", "USER")) }
                .getOrDefault(AiRole.USER)
            messages += AiChatMessage(
                id = item.optString("id").takeIf { it.isNotBlank() } ?: "m$i",
                role = role,
                text = item.optString("text", ""),
                createdAt = item.optLong("createdAt", 0L),
                reasoning = item.optString("reasoning").takeIf { it.isNotBlank() },
                usage = item.optJSONObject("usage")?.let { usage ->
                    AiUsage(
                        input = usage.optInt("input", -1).takeIf { it >= 0 },
                        output = usage.optInt("output", -1).takeIf { it >= 0 },
                    )
                },
                elapsedMs = item.optLong("elapsedMs", -1L).takeIf { it >= 0 },
                error = item.optString("error").takeIf { it.isNotBlank() },
                tools = item.optJSONArray("tools")?.let { tools ->
                    buildList {
                        for (t in 0 until tools.length()) {
                            val tool = tools.optJSONObject(t) ?: continue
                            add(
                                AiToolSnapshot(
                                    callId = tool.optString("callId"),
                                    name = tool.optString("name"),
                                    title = tool.optString("title"),
                                    state = tool.optString("state", "SUCCESS"),
                                    summary = tool.optString("summary").takeIf { it.isNotBlank() },
                                )
                            )
                        }
                    }
                }.orEmpty(),
            )
        }
        AiSession(
            id = root.optString("id"),
            title = root.optString("title"),
            createdAt = root.optLong("createdAt"),
            updatedAt = root.optLong("updatedAt"),
            messages = messages,
        ).takeIf { it.id.isNotBlank() }
    }.getOrNull()

    fun encodeSummaries(summaries: List<AiSessionSummary>): String = JSONArray().apply {
        summaries.forEach { summary ->
            put(JSONObject().apply {
                put("id", summary.id)
                put("title", summary.title)
                put("updatedAt", summary.updatedAt)
                put("messageCount", summary.messageCount)
            })
        }
    }.toString()

    fun decodeSummaries(json: String): List<AiSessionSummary> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    AiSessionSummary(
                        id = id,
                        title = item.optString("title", ""),
                        updatedAt = item.optLong("updatedAt", 0L),
                        messageCount = item.optInt("messageCount", 0),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
