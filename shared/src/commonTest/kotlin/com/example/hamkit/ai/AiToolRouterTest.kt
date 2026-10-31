package com.example.hamkit.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 意图路由单测：命中、词边界、未命中回退。 */
class AiToolRouterTest {

    @Test
    fun `satellite question routes to satellite tools`() {
        val route = AiToolRouter.route("ISS 什么时候过境？仰角多少")
        assertTrue("search_satellites" in route)
        assertTrue("get_satellite_passes" in route)
        assertTrue("get_zone_info" !in route)
    }

    @Test
    fun `aprs and ft8 and qso route separately`() {
        assertTrue("get_aprs_stations" in AiToolRouter.route("APRS 上收到哪些台站"))
        assertTrue("get_ft8_activity" in AiToolRouter.route("刚才 FT8 解出什么了"))
        assertTrue("search_qso_log" in AiToolRouter.route("我最近和 JA 台通联过几次"))
    }

    @Test
    fun `short ascii keywords respect word boundary`() {
        // mississippi 里含 iss，但不是一个词，不能路由到卫星工具
        val route = AiToolRouter.route("mississippi river")
        assertTrue("search_satellites" !in route, "不应因子串命中 ISS：$route")
    }

    @Test
    fun `time question routes only to utc tool`() {
        assertEquals(setOf("get_utc_time"), AiToolRouter.route("现在几点"))
    }

    @Test
    fun `unknown question falls back to empty set`() {
        assertTrue(AiToolRouter.route("你好").isEmpty())
        assertTrue(AiToolRouter.route("   ").isEmpty())
    }

    @Test
    fun `page navigation also keeps domain tools`() {
        val route = AiToolRouter.route("打开 APRS 页面")
        assertTrue("open_screen" in route)
        assertTrue("get_aprs_stations" in route)
    }
}
