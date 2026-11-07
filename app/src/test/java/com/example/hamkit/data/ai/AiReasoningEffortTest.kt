package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiReasoningEffortTest {

    @Test
    fun `off has no api value`() {
        assertNull(AiReasoningEffort.OFF.apiValue)
        assertEquals(0, AiReasoningEffort.OFF.budgetTokens)
    }

    @Test
    fun `levels map to budgets`() {
        assertEquals(1024, AiReasoningEffort.LOW.budgetTokens)
        assertEquals(2048, AiReasoningEffort.MEDIUM.budgetTokens)
        assertEquals(4096, AiReasoningEffort.HIGH.budgetTokens)
        assertEquals(-1, AiReasoningEffort.HIGH.geminiBudget)
    }

    @Test
    fun `labels`() {
        assertEquals("关闭", AiReasoningEffort.OFF.label)
        assertEquals("High", AiReasoningEffort.HIGH.label)
        assertEquals("medium", AiReasoningEffort.MEDIUM.apiValue)
    }
}
