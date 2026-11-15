package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiVisionTest {

    @Test
    fun `vision models are detected`() {
        assertEquals(true, modelVisionSupport("Qwen3-VL-2B-Thinking-MNN"))
        assertEquals(true, modelVisionSupport("gpt-4o-mini"))
        assertEquals(true, modelVisionSupport("claude-3-5-sonnet"))
        assertEquals(true, modelVisionSupport("gemini-2.5-pro"))
    }

    @Test
    fun `deepseek is text only`() {
        assertEquals(false, modelVisionSupport("deepseek-flash"))
        assertEquals(false, modelVisionSupport("deepseek-v4-pro"))
    }

    @Test
    fun `unknown models are not blocked`() {
        assertNull(modelVisionSupport("my-local-model"))
        assertNull(modelVisionSupport(""))
    }
}
