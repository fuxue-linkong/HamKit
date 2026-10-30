package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 三种协议的流式解析：验证统一事件模型的最关键差异——
 * 文本增量、工具参数增量/一次性、结束原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AiProtocolParserTest {

    @Test
    fun `openai text and tool call deltas`() {
        val parser = OpenAiStreamParser()
        val events = mutableListOf<AiEvent>()

        events += parser.parse(
            SseFrame(
                null,
                """{"choices":[{"delta":{"content":"ISS "},"finish_reason":null}]}""",
            )
        )
        events += parser.parse(
            SseFrame(
                null,
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_satellite_passes","arguments":"{\"sat"}}]},"finish_reason":null}]}""",
            )
        )
        events += parser.parse(
            SseFrame(
                null,
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ellite\":\"ISS\"}"}}]},"finish_reason":"tool_calls"}]}""",
            )
        )
        events += parser.finish()

        assertEquals(
            AiEvent.TextDelta("ISS "),
            events.filterIsInstance<AiEvent.TextDelta>().first(),
        )
        val started = events.filterIsInstance<AiEvent.ToolCallStarted>()
        assertEquals(1, started.size)
        assertEquals("call_1", started[0].id)
        assertEquals("get_satellite_passes", started[0].name)

        val args = events.filterIsInstance<AiEvent.ToolArgsDelta>().joinToString("") { it.delta }
        assertEquals("{\"satellite\":\"ISS\"}", args)

        assertEquals(1, events.filterIsInstance<AiEvent.Finished>().size)
        assertEquals(
            AiStopReason.TOOL_USE,
            events.filterIsInstance<AiEvent.Finished>().first().stop,
        )
    }

    @Test
    fun `openai usage and finish stop`() {
        val parser = OpenAiStreamParser()
        val usageEvents = parser.parse(
            SseFrame(
                null,
                """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":8}}""",
            )
        )
        val finishEvents = parser.parse(
            SseFrame(
                null,
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            )
        )
        assertTrue(usageEvents.any { it is AiEvent.Usage })
        assertEquals(AiStopReason.END_TURN, (finishEvents.last() as AiEvent.Finished).stop)
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun `anthropic stream events`() {
        val parser = AnthropicStreamParser()
        val events = mutableListOf<AiEvent>()

        events += parser.parse(
            SseFrame(
                "message_start",
                """{"type":"message_start","message":{"usage":{"input_tokens":88}}}""",
            )
        )
        events += parser.parse(
            SseFrame(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"我查一下。"}}""",
            )
        )
        events += parser.parse(
            SseFrame(
                "content_block_start",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_satellite_live"}}""",
            )
        )
        events += parser.parse(
            SseFrame(
                "content_block_delta",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"satellite\":\"ISS\"}"}}""",
            )
        )
        events += parser.parse(
            SseFrame(
                "message_delta",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}""",
            )
        )
        events += parser.parse(SseFrame("message_stop", """{"type":"message_stop"}"""))

        assertEquals("我查一下。", events.filterIsInstance<AiEvent.TextDelta>().first().text)
        assertEquals(
            "get_satellite_live",
            events.filterIsInstance<AiEvent.ToolCallStarted>().first().name,
        )
        assertEquals(1, events.filterIsInstance<AiEvent.Finished>().size)
        assertEquals(
            AiStopReason.TOOL_USE,
            events.filterIsInstance<AiEvent.Finished>().first().stop,
        )
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun `gemini text and function call`() {
        val parser = GeminiStreamParser()
        val events = mutableListOf<AiEvent>()

        events += parser.parse(
            SseFrame(
                null,
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2}}""",
            )
        )

        val toolParser = GeminiStreamParser()
        val toolEvents = toolParser.parse(
            SseFrame(
                null,
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_zone_info","args":{"lat":22.7}}}]},"finishReason":"STOP"}]}""",
            )
        ) + toolParser.finish()

        assertEquals("hello", events.filterIsInstance<AiEvent.TextDelta>().first().text)
        assertTrue(events.any { it is AiEvent.Usage })
        assertEquals(AiStopReason.END_TURN, events.filterIsInstance<AiEvent.Finished>().first().stop)

        assertEquals("get_zone_info", toolEvents.filterIsInstance<AiEvent.ToolCallStarted>().first().name)
        val args = toolEvents.filterIsInstance<AiEvent.ToolArgsDelta>().joinToString("") { it.delta }
        assertTrue(args.contains("22.7"))
        assertEquals(AiStopReason.TOOL_USE, toolEvents.filterIsInstance<AiEvent.Finished>().first().stop)
    }

    @Test
    fun `gemini finish reason is emitted once`() {
        val parser = GeminiStreamParser()
        val first = parser.parse(
            SseFrame(null, """{"candidates":[{"content":{"parts":[{"text":"a"}]},"finishReason":"STOP"}]}""")
        )
        val second = parser.parse(
            SseFrame(null, """{"candidates":[{"content":{"parts":[{"text":"b"}]},"finishReason":"STOP"}]}""")
        )
        assertEquals(1, first.filterIsInstance<AiEvent.Finished>().size)
        assertEquals(0, second.filterIsInstance<AiEvent.Finished>().size)
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun `openai error body parsing`() {
        val error = AiError.fromHttp(401, """{"error":{"message":"invalid api key","type":"auth_error"}}""")
        assertEquals(AiErrorKind.AUTH, error.kind)
        assertEquals("invalid api key", error.message)
        assertEquals(401, error.status)
    }
}
