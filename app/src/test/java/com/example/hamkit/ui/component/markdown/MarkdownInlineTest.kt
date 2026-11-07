package com.example.hamkit.ui.component.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTest {

    @Test
    fun `splits inline chinese bullet list`() {
        val result = normalizeInlineBullets("定位分区：网格- 卫星：过境预测")
        assertTrue(result.contains("\n- 卫星："))
    }

    @Test
    fun `keeps number ranges`() {
        assertEquals("大约 2-3 分钟", normalizeInlineBullets("大约 2-3 分钟"))
    }

    @Test
    fun `keeps english hyphen`() {
        assertEquals("FM-卫星：测试", normalizeInlineBullets("FM-卫星：测试"))
    }

    @Test
    fun `keeps urls`() {
        val url = "https://a-b.com"
        assertEquals(url, normalizeInlineBullets(url))
    }

    @Test
    fun `keeps code blocks`() {
        val code = "```\n网格- 卫星：示例\n```"
        assertEquals(code, normalizeInlineBullets(code))
    }
}
