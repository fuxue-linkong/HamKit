package com.example.hamkit.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 更新链纯逻辑测试（不依赖 Robolectric/Android 框架）。
 */
class UpdateSecurityTest {

    @Test
    fun `only official APK CDN over https is allowed`() {
        assertTrue(isAllowedUpdateUrl("https://apk.hamkit.click/release/HamKit_1.0.0_1-release.apk"))
        assertFalse(isAllowedUpdateUrl("http://apk.hamkit.click/release/app.apk"))
        assertFalse(isAllowedUpdateUrl("https://hamkit.click/release/app.apk"))
        assertFalse(isAllowedUpdateUrl("https://apk.hamkit.click.evil.com/app.apk"))
        assertFalse(isAllowedUpdateUrl("https://apk.hamkit.click@evil.com/app.apk"))
    }

    @Test
    fun `sha256 normalization accepts colon separated hex and rejects malformed values`() {
        val raw = "BA7816BF8F01CFEA414140DE5DAE2223" +
            "B00361A396177A9CB410FF61F20015AD"
        val colonSeparated = raw.chunked(2).joinToString(":").lowercase()
        assertEquals(raw.lowercase(), ApkVerifier.normalizeSha256(colonSeparated))
        assertNull(ApkVerifier.normalizeSha256("not-a-sha"))
        assertNull(ApkVerifier.normalizeSha256("abc123"))
    }

    @Test
    fun `sha256 matches known digest`() {
        val file = File.createTempFile("hamkit-update", ".bin")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223" +
                    "b00361a396177a9cb410ff61f20015ad",
                ApkVerifier.sha256(file),
            )
        } finally {
            file.delete()
        }
    }
}
