package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 加密与 JSON 编解码。
 *
 * 不触碰 AndroidKeyStore：用普通 JCE 密钥注入底层实现，保证单测在纯 JVM 下可跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AiCryptoTest {

    private val key = AiCrypto.generateTestKey()
    private val aad = "session-a".toByteArray()

    @Test
    fun `round trip with aad`() {
        val plain = "你好 HamKit AI".toByteArray()
        val blob = AiCrypto.encryptWithKey(key, plain, aad)
        assertTrue(blob.size > plain.size)
        assertTrue(blob[0] == 1.toByte())

        val decrypted = AiCrypto.decryptWithKey(key, blob, aad)
        assertEquals("你好 HamKit AI", decrypted?.toString(Charsets.UTF_8))
    }

    @Test
    fun `tampered ciphertext returns null`() {
        val blob = AiCrypto.encryptWithKey(key, "secret".toByteArray(), aad)
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertNull(AiCrypto.decryptWithKey(key, blob, aad))
    }

    @Test
    fun `wrong aad returns null`() {
        val blob = AiCrypto.encryptWithKey(key, "secret".toByteArray(), aad)
        assertNull(AiCrypto.decryptWithKey(key, blob, "session-b".toByteArray()))
    }

    @Test
    fun `short or unknown blob returns null`() {
        assertNull(AiCrypto.decryptWithKey(key, ByteArray(0)))
        assertNull(AiCrypto.decryptWithKey(key, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `session codec round trip`() {
        val session = AiSession(
            id = "s1",
            title = "ISS 过境",
            createdAt = 1000L,
            updatedAt = 2000L,
            messages = listOf(
                AiChatMessage(
                    id = "m1",
                    role = AiRole.USER,
                    text = "ISS 什么时候过境？",
                    createdAt = 1100L,
                ),
                AiChatMessage(
                    id = "m2",
                    role = AiRole.ASSISTANT,
                    text = "需要先获取卫星数据。",
                    createdAt = 1200L,
                    usage = AiUsage(120, 8),
                    elapsedMs = 1200L,
                ),
            ),
        )

        val decoded = AiSessionCodec.decode(AiSessionCodec.encode(session))
        assertEquals(session, decoded)
    }

    @Test
    fun `corrupted session json returns null`() {
        assertNull(AiSessionCodec.decode("not json"))
        assertNull(AiSessionCodec.decode("{}"))
    }

    @Test
    fun `summaries codec`() {
        val summaries = listOf(
            AiSessionSummary("s1", "标题一", 100L, 2),
            AiSessionSummary("s2", "标题二", 200L, 4),
        )
        val decoded = AiSessionCodec.decodeSummaries(AiSessionCodec.encodeSummaries(summaries))
        assertEquals(summaries, decoded)
        assertTrue(AiSessionCodec.decodeSummaries("bad json").isEmpty())
    }
}
