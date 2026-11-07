package com.example.hamkit.data.ai.tool

import com.example.hamkit.data.ai.tool.tools.matchesQsoFilter
import com.example.hamkit.data.ft8.Ft8Band
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QsoFilterTest {

    private fun record(band: String = "2m", mode: String = "FM") = QsoSummary(
        callsign = "BA7ONV",
        grid = null,
        band = band,
        mode = mode,
        qsoTime = 0L,
        isComplete = true,
    )

    @Test
    fun `empty filters pass`() {
        assertTrue(matchesQsoFilter(record(), null, null))
    }

    @Test
    fun `band and mode match`() {
        assertTrue(matchesQsoFilter(record(), Ft8Band.BAND_2M, "FM"))
        assertFalse(matchesQsoFilter(record(), Ft8Band.BAND_2M, "FT8"))
        assertFalse(matchesQsoFilter(record(band = "70cm"), Ft8Band.BAND_2M, null))
    }
}
