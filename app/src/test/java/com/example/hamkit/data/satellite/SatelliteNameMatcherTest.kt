package com.example.hamkit.data.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SatelliteNameMatcher] 分层匹配单元测试。
 *
 * 核心回归：`ISS` 必须优先命中 `ISS (ZARYA)` / `ISS (NAUKA)`，
 * 不能因为 `OUTPOST MISSION2` 的 `MISSION` 含子串 `ISS` 而静默取第一条。
 */
class SatelliteNameMatcherTest {

    private val candidates = listOf(
        SatelliteNameMatcher.Candidate(58334, "OUTPOST MISSION2"),
        SatelliteNameMatcher.Candidate(25544, "ISS (ZARYA)"),
        SatelliteNameMatcher.Candidate(49044, "ISS (NAUKA)"),
        SatelliteNameMatcher.Candidate(43017, "AO-91 (Fox-1B)"),
        SatelliteNameMatcher.Candidate(24278, "FO-29"),
    )

    @Test
    fun `ISS is ambiguous between ZARYA and NAUKA and never matches MISSION2`() {
        val resolution = SatelliteNameMatcher.resolve("ISS", candidates)
        assertTrue("ISS 应返回歧义而不是静默取第一条：$resolution", resolution is SatelliteNameMatcher.Resolution.Ambiguous)
        val matched = (resolution as SatelliteNameMatcher.Resolution.Ambiguous).candidates
        assertEquals(setOf(25544, 49044), matched.map { it.catalogNumber }.toSet())
        assertTrue(matched.none { it.catalogNumber == 58334 })
    }

    @Test
    fun `exact name resolves uniquely`() {
        val resolution = SatelliteNameMatcher.resolve("iss (zarya)", candidates)
        assertEquals(
            25544,
            (resolution as SatelliteNameMatcher.Resolution.Unique).candidate.catalogNumber,
        )
    }

    @Test
    fun `NORAD number resolves uniquely even when name looks similar`() {
        val resolution = SatelliteNameMatcher.resolve("25544", candidates)
        assertEquals(
            25544,
            (resolution as SatelliteNameMatcher.Resolution.Unique).candidate.catalogNumber,
        )
    }

    @Test
    fun `mission substring does not win over exact name`() {
        val resolution = SatelliteNameMatcher.resolve("OUTPOST MISSION2", candidates)
        assertEquals(
            58334,
            (resolution as SatelliteNameMatcher.Resolution.Unique).candidate.catalogNumber,
        )
    }

    @Test
    fun `catalog aliases with punctuation resolve`() {
        val ao91 = SatelliteNameMatcher.resolve("AO-91", candidates)
        assertEquals(
            43017,
            (ao91 as SatelliteNameMatcher.Resolution.Unique).candidate.catalogNumber,
        )
        val fox = SatelliteNameMatcher.resolve("fox-1b", candidates)
        assertEquals(
            43017,
            (fox as SatelliteNameMatcher.Resolution.Unique).candidate.catalogNumber,
        )
    }

    @Test
    fun `unknown name returns not found`() {
        assertTrue(
            SatelliteNameMatcher.resolve("完全不存在的卫星", candidates) is
                SatelliteNameMatcher.Resolution.NotFound,
        )
    }

    @Test
    fun `match tiers keep substring below exact and prefix`() {
        val issName = "ISS (ZARYA)"
        assertEquals(1, SatelliteNameMatcher.nameTier("ISS", issName))
        assertEquals(1, SatelliteNameMatcher.nameTier("ISS (ZARYA)", issName))
        assertEquals(4, SatelliteNameMatcher.nameTier("ISS", "OUTPOST MISSION2"))
        assertNull(SatelliteNameMatcher.nameTier("25544", issName))
    }

    @Test
    fun `ranking puts prefix matches before substring matches`() {
        val ranked = SatelliteNameMatcher.rank("ISS", candidates)
        assertEquals(listOf(25544, 49044, 58334), ranked.map { it.catalogNumber })
    }
}
