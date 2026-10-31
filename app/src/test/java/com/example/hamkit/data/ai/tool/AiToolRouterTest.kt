package com.example.hamkit.data.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolRouterTest {

    @Test
    fun `routes satellite questions`() {
        val tools = AiToolRouter.route("ISS 什么时候过境？")
        assertTrue(tools.contains("get_satellite_passes"))
        assertTrue(tools.contains("search_satellites"))
    }

    @Test
    fun `routes ft8 and qso questions`() {
        assertTrue(AiToolRouter.route("FT8 最近收到什么").contains("get_ft8_activity"))
        assertTrue(AiToolRouter.route("我最近通联过哪些台").contains("search_qso_log"))
        assertTrue(AiToolRouter.route("附近 APRS 台站").contains("get_aprs_stations"))
    }

    @Test
    fun `routes zone question`() {
        assertTrue(AiToolRouter.route("我在哪个网格？").contains("get_zone_info"))
    }

    @Test
    fun `returns empty for unrelated text`() {
        assertEquals(emptySet<String>(), AiToolRouter.route("你好"))
        assertEquals(emptySet<String>(), AiToolRouter.route(""))
    }

    @Test
    fun `truncates long tool results`() {
        val short = truncateToolResult("ok")
        assertEquals("ok", short)

        val long = truncateToolResult("x".repeat(10_000))
        assertTrue(long.length < 10_000)
        assertTrue(long.endsWith("…（结果已截断）"))
    }
}
