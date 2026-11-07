package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiErrorTest {

    @Test
    fun `ssl wrong version maps to ssl kind`() {
        val error = AiError.fromThrowable(
            javax.net.ssl.SSLException("wrong version number"),
        )
        assertEquals(AiErrorKind.SSL, error.kind)
    }

    @Test
    fun `io maps to network`() {
        assertEquals(AiErrorKind.NETWORK, AiError.fromThrowable(java.io.IOException("x")).kind)
    }
}
