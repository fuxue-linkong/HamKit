package com.example.hamkit.data.satellite

import com.example.hamkit.data.satellite.predict.SatellitePropagator
import com.example.hamkit.data.satellite.predict.TleElements
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [SatellitePredictor] 在境过境语义与模式聚合回归测试。
 *
 * 回归背景：旧实现在“当前在境”分支把 AOS 设为预测基准时刻，
 * 导致同一批在境卫星的 next_aos 全部相同且已过期。
 */
class SatellitePredictorInPassTest {

    @Test
    fun `in-pass satellite AOS is real pass start not prediction time`() = runBlocking {
        val tle = buildLeoTle(catnum = 25544, meanAnomaly = 325.0288)
        val now = findInPassMoment(tle)

        val results = SatellitePredictor().predictUpcomingPasses(
            sourcedTles = listOf(sourcedTleOf(tle)),
            latitude = 0.0,
            longitude = 0.0,
            hoursAhead = 12,
            now = now,
        )

        assertEquals("在境卫星应返回一条本次过境记录", 1, results.size)
        val sat = results.single()
        assertTrue("当前应在境", sat.isCurrentlyVisible)
        assertTrue("LOS 应在未来", sat.losTime.isAfter(now))
        assertTrue(
            "AOS 应回溯到本次过境开始，而不是等于预测时刻（实际 ${sat.aosTime} / now=$now）",
            sat.aosTime.isBefore(now),
        )
        assertTrue(
            "AOS 回溯不应超过 6 小时上限",
            sat.aosTime.isAfter(now.minus(java.time.Duration.ofHours(6))),
        )
    }

    @Test
    fun `two LEO satellites do not share the same AOS`() = runBlocking {
        val tle1 = buildLeoTle(catnum = 11111, meanAnomaly = 10.0)
        val tle2 = buildLeoTle(catnum = 22222, meanAnomaly = 200.0)
        val now = findBothBelowHorizon(tle1, tle2)
        Assume.assumeTrue("当前轨道相位下未找到两颗都不在境的时刻，跳过", now != null)

        val results = SatellitePredictor().predictUpcomingPasses(
            sourcedTles = listOf(sourcedTleOf(tle1), sourcedTleOf(tle2)),
            latitude = 0.0,
            longitude = 0.0,
            hoursAhead = 24,
            now = now!!,
        )

        assertEquals("两颗卫星都应返回下一次过境", 2, results.size)
        val aosByCatalog = results.associate { it.catalogNumber to it.aosTime }
        assertNotEquals(
            "不同卫星的 AOS 不应因共享预测时刻而相同",
            aosByCatalog[11111],
            aosByCatalog[22222],
        )
        results.forEach { sat ->
            assertTrue("不在境卫星的 AOS 应在未来", sat.aosTime.isAfter(now))
        }
    }

    @Test
    fun `modes merge active radios with catalog and ignore inactive radios`() = runBlocking {
        val tle = buildLeoTle(catnum = 25544, meanAnomaly = 325.0288)
        val radios = mapOf(
            25544 to listOf(
                RadioInfo(noradCatId = 25544, name = "ISS FM", mode = "FM", status = RadioInfo.STATUS_ACTIVE),
                RadioInfo(noradCatId = 25544, name = "ISS SSTV", mode = "SSTV", status = RadioInfo.STATUS_ACTIVE),
                RadioInfo(noradCatId = 25544, name = "inactive", mode = "USB", status = RadioInfo.STATUS_INACTIVE),
            ),
        )

        val results = SatellitePredictor().predictUpcomingPasses(
            sourcedTles = listOf(sourcedTleOf(tle)),
            latitude = 0.0,
            longitude = 0.0,
            hoursAhead = 24,
            radiosByCatalogNumber = radios,
        )

        assertTrue("24 小时内应有 ISS 过境", results.isNotEmpty())
        assertEquals(listOf("FM", "SSTV"), results.first().modes)
    }

    // ── 辅助 ──

    /** 构造一颗合成 LEO 卫星（ISS 轨道参数，历元 = 当前时刻）。 */
    private fun buildLeoTle(catnum: Int, meanAnomaly: Double): TleElements {
        val epochStr = LocalDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val (year, refEpoch) = TleParser.parseEpoch(epochStr)!!
        val line1 = TleParser.buildLine1(
            noradCatId = catnum,
            classification = "U",
            objectId = "1998-067A",
            year = year,
            refEpoch = refEpoch,
            meanMotionDot = 0.00010980,
            meanMotionDdot = 0.0,
            bstar = 0.00011606,
            ephemerisType = "0",
            elementSetNo = 999,
        )
        val line2 = TleParser.buildLine2(
            noradCatId = catnum,
            inclination = 51.6416,
            raan = 247.4627,
            eccentricity = 0.0006703,
            argPerigee = 130.5360,
            meanAnomaly = meanAnomaly,
            meanMotion = 15.50028952,
            revAtEpoch = 9995,
        )
        return TleElements.fromThreeLines(arrayOf("TEST-LEO-$catnum", line1, line2))
    }

    /** 在 ±12 小时内找仰角最高的采样点，作为“在境”时刻。 */
    private fun findInPassMoment(tle: TleElements): Instant {
        val propagator = SatellitePropagator(tle, 0.0, 0.0)
        val base = System.currentTimeMillis()
        val halfWindowMs = 12L * 60L * 60L * 1000L
        var bestMs = -1L
        var bestEl = Double.NEGATIVE_INFINITY
        var t = base - halfWindowMs
        while (t <= base + halfWindowMs) {
            val el = propagator.getPosition(t)?.let { Math.toDegrees(it.elevationRad) }
            if (el != null && el > bestEl) {
                bestEl = el
                bestMs = t
            }
            t += 2L * 60L * 1000L
        }
        check(bestEl > 0.0) { "±12 小时内找不到在境时刻" }
        return Instant.ofEpochMilli(bestMs)
    }

    /** 在 ±12 小时内找两颗卫星都明显低于地平线的时刻（确保都不在境）。 */
    private fun findBothBelowHorizon(tle1: TleElements, tle2: TleElements): Instant? {
        val p1 = SatellitePropagator(tle1, 0.0, 0.0)
        val p2 = SatellitePropagator(tle2, 0.0, 0.0)
        val base = System.currentTimeMillis()
        val halfWindowMs = 12L * 60L * 60L * 1000L
        var t = base - halfWindowMs
        while (t <= base + halfWindowMs) {
            val el1 = p1.getPosition(t)?.let { Math.toDegrees(it.elevationRad) }
            val el2 = p2.getPosition(t)?.let { Math.toDegrees(it.elevationRad) }
            if (el1 != null && el2 != null && el1 < -5.0 && el2 < -5.0) {
                return Instant.ofEpochMilli(t)
            }
            t += 60L * 1000L
        }
        return null
    }

    private fun sourcedTleOf(tle: TleElements): SourcedTLE =
        SourcedTLE(tle = tle, source = "TEST")
}
