package com.example.hamkit.data.ai.tool

import com.example.hamkit.data.ai.tool.tools.SatelliteTag
import com.example.hamkit.data.ai.tool.tools.TagParseResult
import com.example.hamkit.data.ai.tool.tools.filterSatellites
import com.example.hamkit.data.ai.tool.tools.parseSatelliteTags
import com.example.hamkit.data.satellite.SatelliteInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SatelliteTagsTest {

    private val now: Instant = Instant.parse("2026-10-31T10:00:00Z")

    private fun sat(
        name: String,
        cat: Int,
        modes: List<String> = emptyList(),
        visible: Boolean = false,
        aos: Instant = now.plusSeconds(600),
        geo: Boolean = false,
        daylight: Boolean = false,
        status: String = "",
    ) = SatelliteInfo(
        name = name,
        catalogNumber = cat,
        modes = modes,
        aosTime = aos,
        losTime = aos.plusSeconds(600),
        maxElevation = 30.0,
        aosAzimuth = 100,
        losAzimuth = 200,
        isCurrentlyVisible = visible,
        status = status,
        isGeo = geo,
        isDaylightPass = daylight,
    )

    @Test
    fun `parses tag array`() {
        val result = parseSatelliteTags(JSONObject("""{"tags":["amateur","visible"]}"""))
        assertTrue(result is TagParseResult.Success)
        assertEquals(
            setOf(SatelliteTag.AMATEUR, SatelliteTag.VISIBLE),
            (result as TagParseResult.Success).tags,
        )
    }

    @Test
    fun `parses comma separated tags`() {
        val result = parseSatelliteTags(JSONObject("""{"tags":"amateur, fm"}"""))
        assertTrue(result is TagParseResult.Success)
        assertEquals(
            setOf(SatelliteTag.AMATEUR, SatelliteTag.FM),
            (result as TagParseResult.Success).tags,
        )
    }

    @Test
    fun `keeps legacy mode and only_above`() {
        val result = parseSatelliteTags(JSONObject("""{"mode":"FM","only_above":true}"""))
        assertTrue(result is TagParseResult.Success)
        assertEquals(
            setOf(SatelliteTag.FM, SatelliteTag.VISIBLE),
            (result as TagParseResult.Success).tags,
        )
    }

    @Test
    fun `unknown tag fails with hint`() {
        val result = parseSatelliteTags(JSONObject("""{"tags":["foo"]}"""))
        assertTrue(result is TagParseResult.Failure)
        assertTrue((result as TagParseResult.Failure).message.contains("foo"))
    }

    @Test
    fun `ssb tag matches usb and lsb`() {
        val sat = sat("AO-7", 7530, modes = listOf("USB", "LSB"))
        assertTrue(SatelliteTag.SSB.matches(sat, now, emptyList()))
        assertFalse(SatelliteTag.FM.matches(sat, now, emptyList()))
    }

    @Test
    fun `amateur tag uses catalog`() {
        assertTrue(SatelliteTag.AMATEUR.matches(sat("ISS", 25544), now, emptyList()))
        assertFalse(SatelliteTag.AMATEUR.matches(sat("NOAA 19", 33591), now, emptyList()))
    }

    @Test
    fun `filters with and semantics`() {
        val am = sat("ISS", 25544, modes = listOf("FM"), visible = true)
        val amNotVisible = sat("AO-91", 43017, modes = listOf("FM"), visible = false)
        val other = sat("NOAA 19", 33591, modes = listOf("APT"), visible = true)

        val filtered = filterSatellites(
            all = listOf(am, amNotVisible, other),
            query = null,
            tags = setOf(SatelliteTag.AMATEUR, SatelliteTag.VISIBLE),
            now = now,
        ) { emptyList() }

        assertEquals(listOf("ISS"), filtered.map { it.name })
    }

    @Test
    fun `sorts amateur first when no query`() {
        val other = sat("NOAA 19", 33591, visible = true)
        val am = sat("ISS", 25544, visible = true)
        val filtered = filterSatellites(
            all = listOf(other, am),
            query = null,
            tags = emptySet(),
            now = now,
        ) { emptyList() }
        assertEquals("ISS", filtered.first().name)
    }

    @Test
    fun `query matches by name and norad`() {
        val iss = sat("ISS (ZARYA)", 25544)
        val other = sat("NOAA 19", 33591)
        assertEquals(
            listOf("ISS (ZARYA)"),
            filterSatellites(listOf(iss, other), "ISS", emptySet(), now) { emptyList() }
                .map { it.name },
        )
        assertEquals(
            listOf("ISS (ZARYA)"),
            filterSatellites(listOf(iss, other), "25544", emptySet(), now) { emptyList() }
                .map { it.name },
        )
    }

    @Test
    fun `upcoming tag excludes past passes`() {
        val past = sat("ISS", 25544, aos = now.minusSeconds(600))
        val future = sat("AO-91", 43017, aos = now.plusSeconds(600))
        val filtered = filterSatellites(
            listOf(past, future),
            null,
            setOf(SatelliteTag.UPCOMING),
            now,
        ) { emptyList() }
        assertEquals(listOf("AO-91"), filtered.map { it.name })
    }
}
