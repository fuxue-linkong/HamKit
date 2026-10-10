package com.example.hamkit

import com.example.hamkit.data.aprs.AprsConfig
import com.example.hamkit.data.aprs.AprsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APRS 报文格式化单元测试。
 *
 * 合规说明（HK-BUG-003）：APRS-IS 官方要求软件作者负责发放 passcode，
 * 不得「按需提供」验证码。因此本应用**不实现 passcode 算法**，
 * 相关算法测试已随实现一并删除，改为：
 * - 金标准断言：只读登录串必须是官方约定的 `pass -1`；
 * - 回归守卫：`AprsPacket` 不得重新引入 passcode 计算方法。
 */
class AprsPacketTest {

    // ── 回归守卫：算法不得随发行包发布 ──

    @Test
    fun `AprsPacket exposes no passcode computation`() {
        val illegalNames = listOf("passcode", "computePasscode", "calculatePasscode", "passcodeFor")
        val leaked = AprsPacket::class.java.declaredMethods
            .map { it.name }
            .filter { name -> illegalNames.any { name.equals(it, ignoreCase = true) } }
        assertTrue(
            "APRS-IS 合规（HK-BUG-003）：不得在应用内提供 passcode 计算，发现: $leaked",
            leaked.isEmpty()
        )
    }

    // ── 金标准：只读 / 持有 passcode 两种登录串 ──

    @Test
    fun `formatLogin emits pass -1 for read-only session`() {
        val login = AprsPacket.formatLogin("N0CALL", null, AprsPacket.READ_ONLY_PASSCODE, "HamKit-3.0.1")
        assertEquals("user N0CALL pass -1 vers HamKit-3.0.1", login)
    }

    @Test
    fun `formatLogin emits supplied passcode with ssid`() {
        val login = AprsPacket.formatLogin("N0CALL", "7", "12345", "HamKit-3.0.1")
        assertEquals("user N0CALL-7 pass 12345 vers HamKit-3.0.1", login)
    }

    @Test
    fun `read only passcode constant matches APRS-IS convention`() {
        assertEquals("-1", AprsPacket.READ_ONLY_PASSCODE)
    }

    @Test
    fun `formatCallSsid returns correct format`() {
        assertEquals("N0CALL-7", AprsPacket.formatCallSsid("N0CALL", "7"))
        assertEquals("N0CALL", AprsPacket.formatCallSsid("N0CALL", ""))
        assertEquals("N0CALL", AprsPacket.formatCallSsid("N0CALL", null))
    }

    @Test
    fun `formatPosition produces correct uncompressed format`() {
        val position = AprsPacket.formatPosition(
            latitude = 40.7128,
            longitude = -74.0060,
            symbolTable = '/',
            symbolCode = '>',
            comment = "Test",
            compressed = false
        )
        assertTrue(position.startsWith("!"))
        assertTrue(position.contains("40"))
        assertTrue(position.contains("074"))
        assertTrue(position.contains(">"))
        assertTrue(position.contains("Test"))
    }

    @Test
    fun `formatMessage produces correct format`() {
        val message = AprsPacket.formatMessage("N0CALL", "W1AW", "Hello", "123")
        assertTrue(message.startsWith(":W1AW     :Hello"))
        assertTrue(message.contains("{123}"))
    }

    @Test
    fun `formatMessage without msgNumber produces correct format`() {
        val message = AprsPacket.formatMessage("N0CALL", "W1AW", "Hello")
        assertEquals(":W1AW     :Hello", message)
    }

    @Test
    fun `parseHostPort extracts host and port`() {
        val (host1, port1) = AprsPacket.parseHostPort("euro.aprs2.net:14580", 14580)
        assertEquals("euro.aprs2.net", host1)
        assertEquals(14580, port1)

        val (host2, port2) = AprsPacket.parseHostPort("rotate.aprs.net", 14580)
        assertEquals("rotate.aprs.net", host2)
        assertEquals(14580, port2)
    }

    @Test
    fun `formatCompressedPosition produces valid compressed string`() {
        val compressed = AprsPacket.formatPosition(
            latitude = 51.5074,
            longitude = -0.1278,
            symbolTable = '/',
            symbolCode = '>',
            comment = "London",
            compressed = true
        )
        assertTrue("Should start with !", compressed.startsWith("!"))
        assertTrue("Compressed should be at least 13 chars", compressed.length >= 13)
    }

    @Test
    fun `parseQrg extracts frequency from comment`() {
        assertEquals("146.520", AprsPacket.parseQrg("Freq 146.520 MHz"))
        assertEquals("446.000", AprsPacket.parseQrg("Output: 446.000"))
        assertEquals(null, AprsPacket.parseQrg("No frequency here"))
    }
}
