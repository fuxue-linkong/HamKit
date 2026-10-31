package com.example.hamkit.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对话状态机单测：文本轮、工具往返、工具轮次上限、取消。
 *
 * 传输层用假实现（[FakeTransport]）同步驱动，因此不依赖网络，也不依赖平台。
 */
class AiChatEngineTest {

    private class FakeTransport : AiStreamTransport {
        val requests = ArrayList<AiHttpRequest>()
        private var pending: AiStreamListener? = null
        var cancelled = false

        override fun start(request: AiHttpRequest, listener: AiStreamListener): AiRequestHandle {
            requests.add(request)
            pending = listener
            return object : AiRequestHandle {
                override fun cancel() {
                    cancelled = true
                }
            }
        }

        /** 把脚本化的分片喂给当前请求，然后结束。 */
        fun emit(vararg chunks: String) {
            val listener = pending ?: error("no pending request")
            chunks.forEach { listener.onChunk(it) }
            listener.onCompleted()
        }
    }

    private class RecordingListener : AiChatListener {
        val deltas = StringBuilder()
        var completed: List<AiMessage>? = null
        var stop: AiStopReason? = null
        var error: AiError? = null
        val toolSummaries = ArrayList<String>()

        override fun onAssistantDelta(text: String) {
            deltas.append(text)
        }

        override fun onReasoningDelta(text: String) = Unit

        override fun onToolStarted(callId: String, name: String, title: String) = Unit

        override fun onToolFinished(
            callId: String,
            name: String,
            title: String,
            summary: String,
            isError: Boolean,
            cardJson: String,
        ) {
            toolSummaries.add(summary)
        }

        override fun onUsage(inputTokens: Int, outputTokens: Int) = Unit

        override fun onTurnCompleted(messages: List<AiMessage>, stop: AiStopReason) {
            completed = messages
            this.stop = stop
        }

        override fun onFailed(error: AiError) {
            this.error = error
        }
    }

    private fun config() = AiConfig(baseUrl = "https://example.com/v1", model = "test-model")

    @Test
    fun `plain text turn completes with assistant message`() {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val engine = AiChatEngine(transport, listener)

        engine.start(config(), "key", listOf(AiMessage.user("你好")), toolHost = null)
        transport.emit(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}\n\n",
        )

        assertEquals("你好", listener.deltas.toString())
        assertEquals(2, listener.completed?.size)
        assertEquals("你好", listener.completed?.last()?.text)
        assertEquals(AiStopReason.END_TURN, listener.stop)
        // 请求头里应带上 Bearer key
        assertEquals("Bearer key", transport.requests.first().headers["Authorization"])
    }

    @Test
    fun `tool round trip feeds result back and finishes`() {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val engine = AiChatEngine(transport, listener)

        val host = object : AiToolHost {
            override fun specs(): List<AiToolSpec> = listOf(
                AiToolSpec("get_utc_time", "当前 UTC 时间", "返回 UTC", """{"type":"object"}"""),
            )

            override fun execute(call: AiToolCall, callback: (AiToolResult) -> Unit) {
                callback(AiToolResult(modelText = """{"utc":"2026-10-31T06:00:00Z"}""", summary = "UTC 06:00"))
            }
        }

        engine.start(config(), "key", listOf(AiMessage.user("现在几点")), host)

        // 第一轮：模型要调用工具
        transport.emit(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"," +
                "\"function\":{\"name\":\"get_utc_time\",\"arguments\":\"{}\"}}]}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        )
        assertEquals(1, listener.toolSummaries.size)
        assertEquals(2, transport.requests.size)

        // 第二轮：模型给最终回答，且请求体里包含 tool 结果消息
        transport.emit(
            "data: {\"choices\":[{\"delta\":{\"content\":\"现在 UTC 06:00\"},\"finish_reason\":\"stop\"}]}\n\n",
        )

        val body = transport.requests[1].body
        assertTrue(body.contains("\"role\":\"tool\""), "第二轮请求应带工具结果")
        assertTrue(body.contains("call_1"))
        assertTrue(listener.deltas.toString().contains("现在 UTC 06:00"))
        assertEquals(AiStopReason.END_TURN, listener.stop)
    }

    @Test
    fun `tool rounds are capped and last request drops tools`() {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val engine = AiChatEngine(transport, listener)

        val host = object : AiToolHost {
            override fun specs(): List<AiToolSpec> = listOf(
                AiToolSpec("noop", "noop", "noop", """{"type":"object"}"""),
            )

            override fun execute(call: AiToolCall, callback: (AiToolResult) -> Unit) {
                callback(AiToolResult(modelText = "{}", summary = "ok"))
            }
        }

        engine.start(config().copy(maxToolRounds = 2), "key", listOf(AiMessage.user("loop")), host)

        val toolCallFrame =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":" +
                "{\"name\":\"noop\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"

        // 触发 2 次工具往返，第 3 次请求应当不再带工具（强制总结）
        transport.emit(toolCallFrame)
        transport.emit(toolCallFrame)
        transport.emit(toolCallFrame)

        val lastBody = transport.requests.last().body
        assertTrue(!lastBody.contains("\"tools\""), "轮次用尽后不应再暴露工具")
        assertTrue(lastBody.contains("工具调用次数已达上限"))
    }

    @Test
    fun `cancel reports cancelled error`() {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val engine = AiChatEngine(transport, listener)

        engine.start(config(), "key", listOf(AiMessage.user("hi")), toolHost = null)
        engine.cancel()

        assertEquals(AiErrorKind.CANCELLED, listener.error?.kind)
        assertTrue(transport.cancelled)
    }

    @Test
    fun `missing api key fails fast without request`() {
        val transport = FakeTransport()
        val listener = RecordingListener()
        val engine = AiChatEngine(transport, listener)

        engine.start(config(), "", listOf(AiMessage.user("hi")), toolHost = null)

        assertEquals(AiErrorKind.NO_CONFIG, listener.error?.kind)
        assertTrue(transport.requests.isEmpty())
    }
}
