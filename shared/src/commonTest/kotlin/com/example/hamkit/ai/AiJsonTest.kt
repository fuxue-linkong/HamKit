package com.example.hamkit.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON 与 SSE 解析的内核单测。
 *
 * 这两块是 AI 链路的基础设施：解析错一个字符，表现就是「模型回了但界面空白」
 * 或「工具参数是半截 JSON」，在 iOS 上极难定位，所以先在 JVM 侧锁死行为。
 */
class AiJsonTest {

    @Test
    fun `parses nested objects and arrays`() {
        val json = Json.parse(
            """{"a":{"b":[1,2,{"c":"d"}]},"flag":true,"none":null}"""
        )!!.objOrNull()!!
        assertEquals("d", json["a"]!!.objOrNull()!!["b"]!!.arrOrNull()!![2]!!
            .objOrNull()!!["c"]!!.stringOrNull())
        assertEquals(true, json["flag"]!!.boolOrNull())
        assertNull(json["none"]!!.stringOrNull())
        assertNull(json["missing"]?.stringOrNull())
    }

    @Test
    fun `explicit null is not the string "null"`() {
        // Android 的 org.json 在这里会返回字符串 "null"，是三协议适配踩过的坑
        val json = Json.parse("""{"content":null}""")!!.objOrNull()!!
        assertNull(json["content"]!!.stringOrNull())
    }

    @Test
    fun `round trips strings with escapes and unicode`() {
        val original = "引用\"与\\反斜杠\n换行\t制表 与中文"
        val encoded = Json.obj().put("text", original).stringify()
        val decoded = Json.parse(encoded)!!.objOrNull()!!["text"]!!.stringOrNull()
        assertEquals(original, decoded)
    }

    @Test
    fun `integer doubles are written without decimal point`() {
        assertEquals("""{"n":3,"d":1.5}""", Json.obj().put("n", 3).put("d", 1.5).stringify())
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(Json.parse("{\"a\":}"))
        assertNull(Json.parse("not json"))
        assertNull(Json.parse("{\"a\":1} trailing"))
    }
}

class AiSseTest {

    @Test
    fun `splits frames on blank line`() {
        val parser = AiSseParser()
        val frames = parser.feed("data: {\"a\":1}\n\ndata: {\"a\":2}\n\n")
        assertEquals(2, frames.size)
        assertEquals("""{"a":1}""", frames[0].data)
    }

    @Test
    fun `keeps partial line across chunks`() {
        val parser = AiSseParser()
        // 把一个 JSON 从中间切开，模拟网络分片
        assertTrue(parser.feed("data: {\"cho").isEmpty())
        val frames = parser.feed("ices\":[]}\n\n")
        assertEquals(1, frames.size)
        assertEquals("""{"choices":[]}""", frames[0].data)
    }

    @Test
    fun `joins multi-line data and keeps event name`() {
        val parser = AiSseParser()
        val frames = parser.feed("event: message_delta\ndata: {\"a\":\ndata: 1}\n\n")
        assertEquals(1, frames.size)
        assertEquals("message_delta", frames[0].event)
        assertEquals("{\"a\":\n1}", frames[0].data)
    }

    @Test
    fun `handles CRLF and comments`() {
        val parser = AiSseParser()
        val frames = parser.feed(": ping\r\ndata: [DONE]\r\n\r\n")
        assertEquals(1, frames.size)
        assertEquals("[DONE]", frames[0].data)
    }

    @Test
    fun `bare json line becomes a frame`() {
        // 部分网关忽略 stream=true，直接回一条完整 JSON（没有 data: 前缀）
        val parser = AiSseParser()
        val frames = parser.feed("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}\n")
        assertEquals(1, frames.size)
    }

    @Test
    fun `flush emits trailing frame without newline`() {
        val parser = AiSseParser()
        assertTrue(parser.feed("data: {\"a\":1}").isEmpty())
        val frames = parser.flush()
        assertEquals(1, frames.size)
    }
}
