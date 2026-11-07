package com.example.hamkit.data.ai.tool

import com.example.hamkit.data.ai.tool.tools.parseBand
import com.example.hamkit.data.ft8.Ft8Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BandParseTest {

    @Test
    fun `parses lowercase and uppercase`() {
        assertEquals(Ft8Band.BAND_2M, parseBand("2m"))
        assertEquals(Ft8Band.BAND_2M, parseBand("2M"))
        assertEquals(Ft8Band.BAND_2M, parseBand("2 m"))
        assertEquals(Ft8Band.BAND_40M, parseBand("40M"))
    }

    @Test
    fun `parses combined bands`() {
        assertEquals(Ft8Band.BAND_2M, parseBand("2m/70cm"))
        assertEquals(Ft8Band.BAND_2M, parseBand("70cm/2m"))
    }

    @Test
    fun `maps 70cm to 2m`() {
        assertEquals(Ft8Band.BAND_2M, parseBand("70cm"))
        assertEquals(Ft8Band.BAND_2M, parseBand("435MHz"))
    }

    @Test
    fun `null defaults to 40m and invalid is rejected`() {
        assertEquals(Ft8Band.BAND_40M, parseBand(null))
        assertNull(parseBand("xyz"))
    }
}
