package com.example.hamkit.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 三协议适配的关键行为单测：增量拼接、结束原因、请求体字段。
 *
 * 这里刻意用「真实响应片段」当输入（从各厂商文档的流式示例裁剪而来），
 * 因为协议适配最容易错的地方就是字段嵌套层级，而不是逻辑本身。
 */
class AiProtocolTest {

    private fun decodeAll(protocol: AiProtocol, vararg frames: String): List<AiEvent> {
        val decoder = AiCodecs.decoder(protocol)
        val parser = AiSseParser()
        val events = ArrayList<AiEvent>()
        frames.forEach { chunk ->
            parser.feed(chunk).forEach { events.addAll(decoder.onFrame(it)) }
        }
        parser.flush().forEach { events.addAll(decoder.onFrame(it)) }
        events.addAll(decoder.finish())
        return events
    }

    @Test
    fun `openai text deltas and finish`() {
        val events = decodeAll(
            AiProtocol.OPENAI_CHAT,
            "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n",
        )
        assertEquals("你好", events.filter { it.kind == AiEventKind.TEXT_DELTA }.joinToString("") { it.text })
        assertEquals(AiStopReason.END_TURN, events.last { it.kind == AiEventKind.FINISHED }.stop)
    }

    @Test
    fun `openai tool call args are streamed in fragments`() {
        val events = decodeAll(
            AiProtocol.OPENAI_CHAT,
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"," +
                "\"function\":{\"name\":\"search_satellites\",\"arguments\":\"{\\\"query\\\":\"}}]}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"function\":{\"arguments\":\"\\\"ISS\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )
        val started = events.first { it.kind == AiEventKind.TOOL_CALL_STARTED }
        assertEquals("call_1", started.callId)
        assertEquals("search_satellites", started.toolName)
        val args = events.filter { it.kind == AiEventKind.TOOL_ARGS_DELTA }.joinToString("") { it.text }
        assertEquals("""{"query":"ISS"}""", args)
        assertEquals(AiStopReason.TOOL_USE, events.last { it.kind == AiEventKind.FINISHED }.stop)
    }

    @Test
    fun `openai non-streaming message object still yields text`() {
        // 部分网关忽略 stream=true，直接回完整 JSON
        val events = decodeAll(
            AiProtocol.OPENAI_CHAT,
            "{\"choices\":[{\"message\":{\"content\":\"完整回答\"},\"finish_reason\":\"stop\"}]}\n",
        )
        assertEquals("完整回答", events.first { it.kind == AiEventKind.TEXT_DELTA }.text)
    }

    @Test
    fun `anthropic event sequence maps to unified events`() {
        val events = decodeAll(
            AiProtocol.ANTHROPIC_MESSAGES,
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12}}}\n\n",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_utc_time\"}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\n",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}," +
                "\"usage\":{\"output_tokens\":7}}\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        )
        assertEquals("toolu_1", events.first { it.kind == AiEventKind.TOOL_CALL_STARTED }.callId)
        assertEquals(AiStopReason.TOOL_USE, events.last { it.kind == AiEventKind.FINISHED }.stop)
        assertTrue(events.any { it.kind == AiEventKind.USAGE && it.inputTokens == 12 })
    }

    @Test
    fun `gemini function call carries complete args`() {
        val events = decodeAll(
            AiProtocol.GEMINI,
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" +
                "{\"functionCall\":{\"name\":\"get_satellite_passes\",\"args\":{\"satellite\":\"ISS\"}}}]}," +
                "\"finishReason\":\"STOP\"}]}\n\n",
        )
        assertEquals("get_satellite_passes", events.first { it.kind == AiEventKind.TOOL_CALL_STARTED }.toolName)
        val args = events.first { it.kind == AiEventKind.TOOL_ARGS_DELTA }.text
        assertEquals("""{"satellite":"ISS"}""", args)
        assertEquals(AiStopReason.TOOL_USE, events.last { it.kind == AiEventKind.FINISHED }.stop)
    }

    @Test
    fun `openai request body carries tools and system message`() {
        val spec = AiToolSpec(
            name = "get_utc_time",
            title = "当前 UTC 时间",
            description = "返回当前 UTC 时间",
            parametersJson = """{"type":"object","properties":{}}""",
        )
        val body = AiCodecs.chatBody(
            config = AiConfig(),
            system = "你是助手",
            messages = listOf(AiMessage.user("现在几点")),
            tools = listOf(spec),
        )
        val json = Json.parse(body)!!.objOrNull()!!
        assertEquals("deepseek-chat", json["model"]!!.stringOrNull())
        assertEquals(true, json["stream"]!!.boolOrNull())
        // system 消息 + 用户消息
        assertEquals(2, json["messages"]!!.arrOrNull()!!.size)
        val tool = json["tools"]!!.arrOrNull()!![0]!!.objOrNull()!!
        assertEquals("get_utc_time", tool["function"]!!.objOrNull()!!["name"]!!.stringOrNull())
    }

    @Test
    fun `anthropic body puts system at top level and clamps temperature`() {
        val body = AiCodecs.chatBody(
            config = AiConfig(protocol = AiProtocol.ANTHROPIC_MESSAGES, temperature = 1.8),
            system = "你是助手",
            messages = listOf(AiMessage.user("hi")),
            tools = emptyList(),
        )
        val json = Json.parse(body)!!.objOrNull()!!
        assertEquals("你是助手", json["system"]!!.stringOrNull())
        // Anthropic 的 temperature 上限是 1.0
        assertEquals(1.0, (json["temperature"] as Json.Num).value)
    }

    @Test
    fun `gemini body wraps messages in contents`() {
        val body = AiCodecs.chatBody(
            config = AiConfig(protocol = AiProtocol.GEMINI, baseUrl = "https://example.com"),
            system = "sys",
            messages = listOf(AiMessage.user("hi"), AiMessage.assistant("hello")),
            tools = emptyList(),
        )
        val json = Json.parse(body)!!.objOrNull()!!
        val contents = json["contents"]!!.arrOrNull()!!
        assertEquals(2, contents.size)
        assertEquals("model", contents[1]!!.objOrNull()!!["role"]!!.stringOrNull())
        assertEquals("sys", json["systemInstruction"]!!.objOrNull()!!["parts"]!!.arrOrNull()!!
            [0]!!.objOrNull()!!["text"]!!.stringOrNull())
    }

    @Test
    fun `endpoint building handles versioned and bare base urls`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiConfig().endpoint(),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AiConfig(protocol = AiProtocol.ANTHROPIC_MESSAGES, baseUrl = "https://api.anthropic.com")
                .endpoint(),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AiConfig(protocol = AiProtocol.ANTHROPIC_MESSAGES, baseUrl = "https://api.anthropic.com/v1")
                .endpoint(),
        )
        assertTrue(
            AiConfig(
                protocol = AiProtocol.GEMINI,
                baseUrl = "https://generativelanguage.googleapis.com",
                model = "gemini-2.5-flash",
            ).endpoint().endsWith("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse")
        )
    }
}
