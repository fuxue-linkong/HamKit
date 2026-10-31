package com.example.hamkit.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨平台内核对外入口 [HamKitApi] 的用例。
 *
 * 该入口是 iOS 壳工程调用的唯一 surface，所以这里既验证数值正确性，
 * 也验证「非法输入不抛异常、返回 null / 布尔值」这一约定——
 * Kotlin/Native 的异常跨语言会直接终止进程，必须保证入口不抛。
 */
class HamKitApiTest {

    // 与 SatellitePropagatorDifferentialTest 相同的 ISS TLE（LEO，NORAD 25544）
    private val issTle = listOf(
        "ISS (ZARYA)",
        "1 25544U 98067A   26220.50489838  .00004539  00000+0  89319-4 0  9991",
        "2 25544  51.6323  41.1734 0007358  25.6275 334.5077 15.49385107579869"
    )

    @Test
    fun `zone - 北京返回 OM 网格与 24-44 分区`() {
        val zone = HamKitApi.zone(39.9042, 116.4074)
        assertEquals("OM89ev", zone.grid)
        assertEquals(24, zone.cqZone)
        assertEquals(44, zone.ituZone)
    }

    @Test
    fun `zone - 海上落点走兜底公式仍有分区`() {
        // 南太平洋 (0, -140) 不在任何包围盒内，应落到经度兜底
        val zone = HamKitApi.zone(0.0, -140.0)
        assertEquals(6, zone.grid.length)
        assertTrue("CQ 分区应在 1..40，实际 ${zone.cqZone}", zone.cqZone in 1..40)
        assertTrue("ITU 分区应在 1..90，实际 ${zone.ituZone}", zone.ituZone in 1..90)
    }

    @Test
    fun `zone - 非法经纬度抛 IllegalArgumentException`() {
        var thrown = false
        try {
            HamKitApi.zone(91.0, 0.0)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("@Throws 声明后 Kotlin 侧仍按异常处理", thrown)
    }

    @Test
    fun `satellitePosition - ISS 位置在合理范围`() {
        val now = System.currentTimeMillis()
        val pos = HamKitApi.satellitePosition(issTle, 39.9042, 116.4074, 50.0, now)
        assertNotNull(pos)
        pos ?: return
        assertEquals(now, pos.timeMs)
        assertTrue("仰角应在 -90..90，实际 ${pos.elevationDeg}", pos.elevationDeg in -90.0..90.0)
        assertTrue("方位角应在 0..360，实际 ${pos.azimuthDeg}", pos.azimuthDeg in 0.0..360.0)
        assertTrue("斜距应在 200..20000 km，实际 ${pos.rangeKm}", pos.rangeKm in 200.0..20000.0)
        assertTrue("星下点纬度应在 -90..90", pos.subLatitudeDeg in -90.0..90.0)
        assertTrue("星下点经度应在 -180..180", pos.subLongitudeDeg in -180.0..180.0)
    }

    @Test
    fun `nextPass - ISS 48 小时内至少有一次过境`() {
        val now = System.currentTimeMillis()
        val pass = HamKitApi.nextPass(issTle, 39.9042, 116.4074, 50.0, now, 48)
        assertNotNull("北京 48 小时内应能看到 ISS 过境", pass)
        pass ?: return
        // 在境时 AOS 会回溯到本次过境的真实开始时刻（上游修复：不再等于查询时刻），
        // 因此可能早于 now；不在境时下一次 AOS 必在未来。
        if (pass.isCurrentlyVisible) {
            assertTrue(
                "在境时 AOS 为真实过境开始，不应早于 now 超过 6 小时",
                pass.aosMs >= now - 6L * 60L * 60L * 1000L,
            )
            assertTrue("在境时 AOS 不应晚于查询时刻", pass.aosMs <= now)
        } else {
            assertTrue("不在境时 AOS 不应早于查询时刻", pass.aosMs >= now)
        }
        assertTrue("LOS 应晚于 AOS", pass.losMs > pass.aosMs)
        assertTrue("最大仰角应在 0..90，实际 ${pass.maxElevationDeg}", pass.maxElevationDeg in 0.0..90.0)
        assertTrue("过境时长应在 1 分钟到 1 小时之间，实际 ${pass.durationSeconds}s", pass.durationSeconds in 60L..3600L)
        assertTrue("最大仰角时刻应落在窗口内", pass.maxElevationMs in pass.aosMs..pass.losMs)
    }

    @Test
    fun `satellitePosition - 非法 TLE 返回 null 而不是抛异常`() {
        val bad = listOf("BAD", "not a tle", "still not a tle")
        assertNull(HamKitApi.satellitePosition(bad, 0.0, 0.0, 0.0, System.currentTimeMillis()))
        assertNull(HamKitApi.nextPass(bad, 0.0, 0.0, 0.0, System.currentTimeMillis(), 24))
        assertFalse(HamKitApi.isValidTle(bad))
        assertTrue(HamKitApi.isValidTle(issTle))
    }

    @Test
    fun `satelliteTrack - 等间隔采样且时间单调`() {
        val base = 1_790_000_000_000L // 固定时间，避免依赖当前时刻
        val track = HamKitApi.satelliteTrack(
            tle = issTle,
            latitude = 39.9042,
            longitude = 116.4074,
            altitudeM = 50.0,
            fromMs = base - 600_000L,
            toMs = base + 600_000L,
            stepSeconds = 60
        )

        assertEquals("±10 分钟、60 秒步长应得到 21 个点", 21, track.size)
        assertEquals(60_000L, track[1].timeMs - track[0].timeMs)
        assertTrue("时间必须严格递增", track.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs })
        track.forEach { point ->
            assertTrue("方位角越界: ${point.azimuthDeg}", point.azimuthDeg in 0.0..360.0)
            assertTrue("仰角越界: ${point.elevationDeg}", point.elevationDeg in -90.0..90.0)
        }
    }

    @Test
    fun `satelliteTrack - 非法参数返回空列表`() {
        val now = System.currentTimeMillis()
        assertTrue(HamKitApi.satelliteTrack(issTle, 0.0, 0.0, 0.0, now, now - 1, 60).isEmpty())
        assertTrue(HamKitApi.satelliteTrack(issTle, 0.0, 0.0, 0.0, now - 1000, now + 1000, 0).isEmpty())
        assertTrue(
            "超过 2000 个采样点应拒绝，避免拖垮 UI",
            HamKitApi.satelliteTrack(issTle, 0.0, 0.0, 0.0, 0L, 10_000_000_000L, 1).isEmpty()
        )
        assertTrue(HamKitApi.satelliteTrack(listOf("bad"), 0.0, 0.0, 0.0, now, now + 1000, 60).isEmpty())
    }

    @Test
    fun `nextPasses - 返回按时间升序的多次过境`() {
        val now = System.currentTimeMillis()
        val passes = HamKitApi.nextPasses(issTle, 39.9042, 116.4074, 50.0, now, 48, 3)

        assertTrue("48 小时内北京应至少有 2 次 ISS 过境，实际 ${passes.size}", passes.size >= 2)
        assertTrue("最多返回 3 次", passes.size <= 3)
        passes.zipWithNext().forEach { (previous, next) ->
            assertTrue("下一次 AOS 必须晚于上一次 LOS", next.aosMs > previous.losMs)
        }
        passes.forEach { pass ->
            assertTrue("过境时长异常：${pass.durationSeconds}s", pass.durationSeconds in 60L..3600L)
            assertTrue("最大仰角异常：${pass.maxElevationDeg}", pass.maxElevationDeg in 0.0..90.0)
        }
    }

    @Test
    fun `nextPasses - 与 nextPass 的第一次结果一致`() {
        val now = System.currentTimeMillis()
        val first = HamKitApi.nextPass(issTle, 39.9042, 116.4074, 50.0, now, 48)
        val list = HamKitApi.nextPasses(issTle, 39.9042, 116.4074, 50.0, now, 48, 3)
        if (first != null) {
            assertEquals(first.aosMs, list.first().aosMs)
            assertEquals(first.losMs, list.first().losMs)
        }
    }
}
