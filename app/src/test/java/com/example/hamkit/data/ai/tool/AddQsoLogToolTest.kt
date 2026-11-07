package com.example.hamkit.data.ai.tool

import com.example.hamkit.data.ai.tool.tools.normalizeCallsign
import com.example.hamkit.data.ai.tool.tools.parseBand
import com.example.hamkit.data.ai.tool.tools.parseQsoTime
import com.example.hamkit.data.ft8.Ft8Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddQsoLogToolTest {

    @Test
    fun `normalizes callsign`() {
        assertEquals("XX9XX", normalizeCallsign(" xx9xx "))
        assertEquals("BG1ABC/P", normalizeCallsign("bg1abc/p"))
    }

    @Test
    fun `rejects invalid callsign`() {
        assertNull(normalizeCallsign("!!"))
        assertNull(normalizeCallsign("A"))
    }

    @Test
    fun `parses band`() {
        assertEquals(Ft8Band.BAND_40M, parseBand("40m"))
        assertEquals(Ft8Band.BAND_20M, parseBand("BAND_20M"))
        assertEquals(Ft8Band.BAND_2M, parseBand("2m"))
        assertNull(parseBand("xyz"))
    }

    @Test
    fun `qso time falls back to now`() {
        assertTrue(parseQsoTime(null) > 0)
        assertTrue(parseQsoTime(0L) > 0)
        assertEquals(1234L, parseQsoTime(1234L))
    }
}
