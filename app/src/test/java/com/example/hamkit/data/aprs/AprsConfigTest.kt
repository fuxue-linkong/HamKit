package com.example.hamkit.data.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APRS 配置的只读模式 / 合规语义测试（HK-BUG-003、HK-REQ-003、HK-REQ-004）。
 *
 * 关键不变量：
 * 1. 未填写 passcode ⇒ [AprsConfig.isReadOnly] 为 true，登录使用官方约定的 `-1`；
 * 2. 填写 passcode ⇒ 原样使用用户持有的值，应用不做任何计算；
 * 3. 未确认持照声明 ⇒ [AprsConfig.licenseConfirmed] 默认 false，不得登录。
 */
class AprsConfigTest {

    @Test
    fun `blank passcode means read only session using pass -1`() {
        val config = AprsConfig(callsign = "N0CALL", passcode = "")
        assertTrue(config.isReadOnly)
        assertEquals("-1", config.effectivePasscode)
    }

    @Test
    fun `whitespace only passcode is treated as read only`() {
        val config = AprsConfig(callsign = "N0CALL", passcode = "   ")
        assertTrue(config.isReadOnly)
        assertEquals("-1", config.effectivePasscode)
    }

    @Test
    fun `user supplied passcode is used verbatim and not read only`() {
        val config = AprsConfig(callsign = "N0CALL", passcode = "  13023 ")
        assertFalse(config.isReadOnly)
        assertEquals("13023", config.effectivePasscode)
    }

    @Test
    fun `license confirmation defaults to false`() {
        assertFalse(AprsConfig(callsign = "N0CALL").licenseConfirmed)
    }

    @Test
    fun `full callsign joins ssid when present`() {
        assertEquals("N0CALL-7", AprsConfig(callsign = "N0CALL", ssid = "7").fullCallsign)
        assertEquals("N0CALL", AprsConfig(callsign = "N0CALL").fullCallsign)
    }
}
