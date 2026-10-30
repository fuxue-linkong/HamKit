package com.example.hamkit.data.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 解析是三种协议的共同底座：这里覆盖帧切分、多行 data、注释、[DONE] 与
 * 流结束补帧。测试直接喂字符串，不依赖网络栈。
 */
class SseReaderTest {

    private fun reader(vararg lines: String): SseReader {
        val queue = ArrayDeque(lines.toList())
        return SseReader { queue.removeFirstOrNull() }
    }

    @Test
    fun `parses event and data`() = runTest {
        val frames = mutableListOf<SseFrame>()
        reader(
            "event: content_block_delta",
            "data: {\"type\":\"text_delta\",\"text\":\"ISS\"}",
            "",
            "data: [DONE]",
            "",
        ).read { frames += it }

        assertEquals(1, frames.size)
        assertEquals("content_block_delta", frames[0].event)
        assertEquals("{\"type\":\"text_delta\",\"text\":\"ISS\"}", frames[0].data)
    }

    @Test
    fun `joins multi line data`() = runTest {
        val frames = mutableListOf<SseFrame>()
        reader(
            "data: line1",
            "data: line2",
            "",
        ).read { frames += it }

        assertEquals(1, frames.size)
        assertEquals("line1\nline2", frames[0].data)
    }

    @Test
    fun `ignores comments and unknown fields`() = runTest {
        val frames = mutableListOf<SseFrame>()
        reader(
            ": heartbeat",
            "id: 42",
            "data: hello",
            "",
        ).read { frames += it }

        assertEquals(1, frames.size)
        assertEquals("hello", frames[0].data)
    }

    @Test
    fun `flushes trailing frame without blank line`() = runTest {
        val frames = mutableListOf<SseFrame>()
        reader(
            "data: last",
        ).read { frames += it }

        assertEquals(1, frames.size)
        assertEquals("last", frames[0].data)
    }

    @Test
    fun `stops at done`() = runTest {
        val frames = mutableListOf<SseFrame>()
        reader(
            "data: before",
            "",
            "data: [DONE]",
            "",
            "data: after",
            "",
        ).read { frames += it }

        assertEquals(1, frames.size)
        assertEquals("before", frames[0].data)
    }

    @Test
    fun `blank data lines are not emitted`() = runTest {
        val frames = mutableListOf<SseFrame>()
        reader(
            "",
            "",
        ).read { frames += it }

        assertTrue(frames.isEmpty())
    }

    @Test
    fun `multiple frames parse independently`() = runTest {
        val frames = mutableListOf<SseFrame>()
        reader(
            "data: a",
            "",
            "data: b",
            "",
            "event: message_stop",
            "data: c",
            "",
        ).read { frames += it }

        assertEquals(3, frames.size)
        assertEquals(listOf("a", "b", "c"), frames.map { it.data })
        assertNull(frames[0].event)
        assertEquals("message_stop", frames[2].event)
    }
}
