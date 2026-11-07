package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DsmlTest {

    @Test
    fun `keeps plain text`() {
        assertEquals("你好，HamKit", stripDsml("你好，HamKit"))
    }

    @Test
    fun `removes full dsml block`() {
        val text = """
            开始
            <||DSML||calls>
            <||DSML||invoke name="get_satellite_passes">
            <||DSML||parameter name="norad_id" string="false">25544</||DSML||parameter>
            </||DSML||invoke>
            </||DSML||calls>
            结束
        """.trimIndent()
        val cleaned = stripDsml(text)
        assertFalse(cleaned.contains("DSML"))
        assertFalse(cleaned.contains("get_satellite_passes"))
        assertTrue(cleaned.startsWith("开始"))
        assertTrue(cleaned.endsWith("结束"))
    }

    @Test
    fun `removes leftover markers`() {
        val cleaned = stripDsml("前 <||DSML||invoke name=\"x\"> 后")
        assertFalse(cleaned.contains("DSML"))
    }
}
