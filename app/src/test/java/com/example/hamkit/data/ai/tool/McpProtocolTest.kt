package com.example.hamkit.data.ai.tool

import com.example.hamkit.data.mcp.McpProtocol
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class McpProtocolTest {

    @Test
    fun `authorizes bearer token`() {
        assertTrue(McpProtocol.authorize("Bearer abc", "abc"))
        assertTrue(McpProtocol.authorize("bearer ABC", "abc"))
        assertFalse(McpProtocol.authorize("Bearer wrong", "abc"))
        assertFalse(McpProtocol.authorize(null, "abc"))
        assertFalse(McpProtocol.authorize("Bearer abc", ""))
    }

    @Test
    fun `only read only tools are exposed`() {
        val exposed = McpProtocol.readOnlySpecs(AiToolRegistry().specs()).map { it.name }.toSet()
        assertTrue("get_utc_time" in exposed)
        assertTrue("search_satellites" in exposed)
        assertFalse("add_qso_log" in exposed)
        assertFalse("add_pass_reminder" in exposed)
        assertFalse("open_screen" in exposed)
        assertFalse("start_sstv_receive" in exposed)
    }
}
