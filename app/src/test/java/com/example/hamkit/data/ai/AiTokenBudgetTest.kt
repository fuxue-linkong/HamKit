package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiTokenBudgetTest {

    @Test
    fun `default output tokens is 8192`() {
        assertEquals(8192, AiConfig().maxOutputTokens)
    }

    @Test
    fun `off keeps configured value`() {
        assertEquals(4096, requestMaxTokens(AiConfig(maxOutputTokens = 4096), AiReasoningEffort.OFF))
    }

    @Test
    fun `high lifts to budget floor`() {
        assertEquals(
            5120,
            requestMaxTokens(AiConfig(maxOutputTokens = 4096), AiReasoningEffort.HIGH),
        )
    }

    @Test
    fun `normalized clamps to 65536`() {
        assertEquals(65536, AiConfig(maxOutputTokens = 999_999).normalized().maxOutputTokens)
    }
}
