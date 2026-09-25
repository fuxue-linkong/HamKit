package com.example.hamkit.ui

import com.example.hamkit.data.satellite.SatelliteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [SatelliteFilter] 与 [List.applyFilter] 单元测试。
 */
class SatelliteFilterTest {

    private fun createSatellite(
        catalogNumber: Int,
        modes: List<String> = listOf("FM"),
        isCurrentlyVisible: Boolean = false,
        status: String = ""
    ) = SatelliteInfo(
        catalogNumber = catalogNumber,
        name = "Sat-$catalogNumber",
        modes = modes,
        isCurrentlyVisible = isCurrentlyVisible,
        status = status,
        aosTime = Instant.now(),
        losTime = Instant.now().plusSeconds(600),
        maxElevation = 45.0,
        aosAzimuth = 180,
        losAzimuth = 270
    )

    private val testSats = listOf(
        createSatellite(1, modes = listOf("FM"), isCurrentlyVisible = true, status = "active"),
        createSatellite(2, modes = listOf("CW"), isCurrentlyVisible = false, status = "active"),
        createSatellite(3, modes = listOf("FM", "SSTV"), isCurrentlyVisible = false, status = ""),
        createSatellite(4, modes = listOf("DSTAR"), isCurrentlyVisible = true, status = ""),
    )

    @Test
    fun `no filter returns all satellites`() {
        val result = testSats.applyFilter(SatelliteFilter())
        assertEquals(4, result.size)
    }

    @Test
    fun `filter by FM mode`() {
        val filter = SatelliteFilter(modes = setOf("FM"))
        val result = testSats.applyFilter(filter)
        assertEquals(2, result.size)
        assertTrue(result.all { "FM" in it.modes })
    }

    @Test
    fun `filter by CW mode`() {
        val filter = SatelliteFilter(modes = setOf("CW"))
        val result = testSats.applyFilter(filter)
        assertEquals(1, result.size)
        assertEquals(2, result[0].catalogNumber)
    }

    @Test
    fun `filter by multiple modes returns union`() {
        val filter = SatelliteFilter(modes = setOf("CW", "DSTAR"))
        val result = testSats.applyFilter(filter)
        assertEquals(2, result.size)
        assertTrue(result.any { it.catalogNumber == 2 })
        assertTrue(result.any { it.catalogNumber == 4 })
    }

    @Test
    fun `filter onlyUpcoming excludes currently visible`() {
        val filter = SatelliteFilter(onlyUpcoming = true)
        val result = testSats.applyFilter(filter)
        assertFalse(result.any { it.isCurrentlyVisible })
        assertEquals(2, result.size)
    }

    @Test
    fun `filter onlyInPass shows only currently visible`() {
        val filter = SatelliteFilter(onlyInPass = true)
        val result = testSats.applyFilter(filter)
        assertTrue(result.all { it.isCurrentlyVisible })
        assertEquals(2, result.size)
    }

    @Test
    fun `filter onlyAmsat shows only satellites with status`() {
        val filter = SatelliteFilter(onlyAmsat = true)
        val result = testSats.applyFilter(filter)
        assertEquals(2, result.size)
        assertTrue(result.all { it.status.isNotBlank() })
    }

    @Test
    fun `filter by category shows only satellites in that category`() {
        val membership = mapOf(1 to setOf("c1"), 3 to setOf("c2"))
        val filter = SatelliteFilter(categoryIds = setOf("c1"))
        val result = testSats.applyFilter(filter, membership)
        assertEquals(1, result.size)
        assertEquals(1, result[0].catalogNumber)
    }

    @Test
    fun `filter by multiple categories uses OR semantics`() {
        val membership = mapOf(1 to setOf("c1"), 3 to setOf("c2"), 4 to setOf("c3"))
        val filter = SatelliteFilter(categoryIds = setOf("c1", "c2"))
        val result = testSats.applyFilter(filter, membership)
        assertEquals(2, result.size)
        assertTrue(result.any { it.catalogNumber == 1 })
        assertTrue(result.any { it.catalogNumber == 3 })
    }

    @Test
    fun `category filter matches satellite belonging to several categories`() {
        val membership = mapOf(2 to setOf("c1", "c9"))
        val filter = SatelliteFilter(categoryIds = setOf("c9"))
        val result = testSats.applyFilter(filter, membership)
        assertEquals(1, result.size)
        assertEquals(2, result[0].catalogNumber)
    }

    @Test
    fun `empty category filter does not filter by category`() {
        val result = testSats.applyFilter(SatelliteFilter(), emptyMap())
        assertEquals(4, result.size)
    }

    @Test
    fun `combined filters apply AND logic`() {
        val filter = SatelliteFilter(
            modes = setOf("FM"),
            onlyInPass = true
        )
        val result = testSats.applyFilter(filter)
        // Only sat 1 is FM and currently visible
        assertEquals(1, result.size)
        assertEquals(1, result[0].catalogNumber)
    }

    @Test
    fun `empty mode set does not filter by mode`() {
        val filter = SatelliteFilter(modes = emptySet(), onlyInPass = true)
        val result = testSats.applyFilter(filter)
        assertEquals(2, result.size)
    }

    @Test
    fun `isActive is false when no conditions set`() {
        assertFalse(SatelliteFilter().isActive)
    }

    @Test
    fun `isActive is true when any condition set`() {
        assertTrue(SatelliteFilter(modes = setOf("FM")).isActive)
        assertTrue(SatelliteFilter(onlyUpcoming = true).isActive)
        assertTrue(SatelliteFilter(onlyInPass = true).isActive)
        assertTrue(SatelliteFilter(onlyAmsat = true).isActive)
        assertTrue(SatelliteFilter(categoryIds = setOf("c1")).isActive)
    }

    @Test
    fun `no matching filter returns empty list`() {
        val filter = SatelliteFilter(modes = setOf("NONEXISTENT"))
        val result = testSats.applyFilter(filter)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filter on empty list returns empty`() {
        val filter = SatelliteFilter(modes = setOf("FM"))
        val result = emptyList<SatelliteInfo>().applyFilter(filter)
        assertTrue(result.isEmpty())
    }
}
