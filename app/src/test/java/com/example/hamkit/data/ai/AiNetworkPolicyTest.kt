package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内网 http 白名单：既要拦住公网明文，也不能误伤 https 与私网。 */
class AiNetworkPolicyTest {

    @Test
    fun `https is always allowed`() {
        assertNull(AiNetworkPolicy.validate("https://api.deepseek.com/v1", allowCleartext = false))
        assertNull(AiNetworkPolicy.validate("https://192.168.1.5:8443/v1", allowCleartext = true))
    }

    @Test
    fun `private network http needs the switch`() {
        val reason = AiNetworkPolicy.validate("http://192.168.1.5:8080/v1", allowCleartext = false)
        assertTrue(reason != null)
        assertNull(AiNetworkPolicy.validate("http://192.168.1.5:8080/v1", allowCleartext = true))
    }

    @Test
    fun `public http is rejected even with the switch`() {
        assertTrue(AiNetworkPolicy.validate("http://api.deepseek.com/v1", allowCleartext = true) != null)
        assertTrue(AiNetworkPolicy.validate("http://8.8.8.8/v1", allowCleartext = true) != null)
    }

    @Test
    fun `private host ranges`() {
        assertTrue(AiNetworkPolicy.isPrivateHost("10.0.0.1"))
        assertTrue(AiNetworkPolicy.isPrivateHost("172.16.0.1"))
        assertTrue(AiNetworkPolicy.isPrivateHost("172.31.255.254"))
        assertTrue(AiNetworkPolicy.isPrivateHost("192.168.31.7"))
        assertTrue(AiNetworkPolicy.isPrivateHost("localhost"))
        assertTrue(AiNetworkPolicy.isPrivateHost("127.0.0.1"))
        assertTrue(AiNetworkPolicy.isPrivateHost("ai.lan"))
        assertTrue(AiNetworkPolicy.isPrivateHost("gateway.local"))

        assertFalse(AiNetworkPolicy.isPrivateHost("172.15.0.1"))
        assertFalse(AiNetworkPolicy.isPrivateHost("172.32.0.1"))
        assertFalse(AiNetworkPolicy.isPrivateHost("11.0.0.1"))
        assertFalse(AiNetworkPolicy.isPrivateHost("192.169.0.1"))
        assertFalse(AiNetworkPolicy.isPrivateHost("example.com"))
    }

    @Test
    fun `malformed url is rejected`() {
        assertTrue(AiNetworkPolicy.validate("not a url", allowCleartext = true) != null)
    }

    @Test
    fun `endpoint joining per protocol`() {
        val openAi = AiConfig(
            protocol = AiProtocol.OPENAI_CHAT,
            baseUrl = "https://api.deepseek.com/v1/",
            model = "deepseek-chat",
        )
        assertEquals("https://api.deepseek.com/v1/chat/completions", openAi.endpoint())

        val anthropic = AiConfig(
            protocol = AiProtocol.ANTHROPIC_MESSAGES,
            baseUrl = "https://api.anthropic.com",
            model = "claude-sonnet-4-5",
        )
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.endpoint())

        val anthropicWithVersion = anthropic.copy(baseUrl = "https://proxy.lan/v1")
        assertEquals("https://proxy.lan/v1/messages", anthropicWithVersion.endpoint())

        val gemini = AiConfig(
            protocol = AiProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            model = "gemini-2.5-flash",
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            gemini.endpoint(),
        )
        assertTrue(gemini.probeEndpoint().endsWith(":generateContent"))
    }

    @Test
    fun `config normalization clamps values`() {
        val config = AiConfig(
            baseUrl = "  https://api.deepseek.com/v1///  ",
            model = " deepseek-chat ",
            temperature = 9f,
            maxOutputTokens = 99999,
        ).normalized()

        assertEquals("https://api.deepseek.com/v1", config.baseUrl)
        assertEquals("deepseek-chat", config.model)
        assertEquals(2f, config.temperature, 0.001f)
        assertEquals(8192, config.maxOutputTokens)
    }
}
