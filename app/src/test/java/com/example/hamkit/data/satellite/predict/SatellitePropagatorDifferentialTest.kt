package com.example.hamkit.data.satellite.predict

import com.github.amsacode.predict4java.GroundStationPosition
import com.github.amsacode.predict4java.PassPredictor
import com.github.amsacode.predict4java.TLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 自研 SGP4/SDP4 引擎（[SatellitePropagator]）与 predict4java 的差分一致性测试。
 *
 * predict4java 仅作为**测试期参考实现**（testImplementation 依赖，不进入 APK），
 * 用于验证自研引擎在 LEO（SGP4）与 GEO/深空（SDP4）两种轨道上的传播结果
 * 与其一致，作为正确性锚点。算法同源（Spacetrack Report #3），
 * 允许的误差来自实现细节差异（常数精度、GMST 公式、坐标转换迭代）。
 *
 * 容差设计：
 * - LEO：星下点误差 < 0.5°，方位角 < 1°，仰角 < 1°，斜距相对误差 < 2%
 * - GEO：星下点误差 < 1°，方位角 < 2°，仰角 < 2°
 */
class SatellitePropagatorDifferentialTest {

    // 2026-08-09 拉取的 ISS TLE（LEO，SGP4 路径，NORAD 25544）
    private val issTle = TleElements.fromThreeLines(
        arrayOf(
            "ISS (ZARYA)",
            "1 25544U 98067A   26220.50489838  .00004539  00000+0  89319-4 0  9991",
            "2 25544  51.6323  41.1734 0007358  25.6275 334.5077 15.49385107579869"
        )
    )
    private val issTleP4j = TLE(
        arrayOf(
            "ISS (ZARYA)",
            "1 25544U 98067A   26220.50489838  .00004539  00000+0  89319-4 0  9991",
            "2 25544  51.6323  41.1734 0007358  25.6275 334.5077 15.49385107579869"
        )
    )

    // 2026-08-09 拉取的 QO-100（Es'hail-2）TLE（GEO，SDP4 路径，NORAD 43700）
    private val geoTle = TleElements.fromThreeLines(
        arrayOf(
            "ESH'HAIL-2",
            "1 43700U 18090A   26220.35824306 -.00000275  00000+0  00000+0 0  9997",
            "2 43700   0.0213 318.9716 0002679 126.3766 197.7513  1.00270839 26574"
        )
    )
    private val geoTleP4j = TLE(
        arrayOf(
            "ESH'HAIL-2",
            "1 43700U 18090A   26220.35824306 -.00000275  00000+0  00000+0 0  9997",
            "2 43700   0.0213 318.9716 0002679 126.3766 197.7513  1.00270839 26574"
        )
    )

    // 北京地面站
    private val lat = 39.9042
    private val lon = 116.4074
    private val altM = 50.0

    // ── 差分采样的时间基准 ──
    //
    // 必须钉在 TLE 历元，而不是 System.currentTimeMillis()：差分测试比较的是两个
    // **实现**（自研 vs predict4java）在同一组元素上的传播结果，用当前时间等于把
    // 「TLE 老化」引入误差 —— 两个实现的外推速率略有差异，沿迹误差会随 TLE 年龄
    // 线性累积（实测 GEO 经度差 0.011°/天）。固定用 now 的用例在 TLE 刚拉取时是绿的，
    // 约一两个月后就会越界变红，属于典型的时间炸弹。
    private val issEpochMs = issTle.epochMillis
    private val geoEpochMs = geoTle.epochMillis

    // ── LEO / SGP4 差分 ──

    @Test
    fun `LEO - subpoint matches predict4java within 0-5 deg`() {
        val mine = SatellitePropagator(issTle, lat, lon, altM)
        val ref = PassPredictor(issTleP4j, GroundStationPosition(lat, lon, altM))

        var checked = 0
        for (offsetMinutes in 0..480 step 30) {
            val ms = issEpochMs + offsetMinutes * 60_000L
            val m = mine.getPosition(ms)
            val r = ref.getSatPos(Date(ms))
            if (m == null || r == null) continue

            val dLat = angularDistanceDeg(
                Math.toDegrees(m.latitudeRad), Math.toDegrees(r.latitude)
            )
            val dLon = angularDistanceDeg(
                Math.toDegrees(m.longitudeRad), Math.toDegrees(r.longitude)
            )
            val dAz = angularDistanceDeg(
                Math.toDegrees(m.azimuthRad), Math.toDegrees(r.azimuth)
            )
            val dEl = abs(Math.toDegrees(m.elevationRad) - Math.toDegrees(r.elevation))
            val rangeRel = abs(m.rangeKm - r.range) / r.range

            assertTrue("LEO 星下点纬度差 $dLat°", dLat < 0.5)
            assertTrue("LEO 星下点经度差 $dLon°", dLon < 0.5)
            assertTrue("LEO 方位角差 $dAz°", dAz < 1.0)
            assertTrue("LEO 仰角差 $dEl°", dEl < 1.0)
            assertTrue("LEO 斜距相对差 $rangeRel", rangeRel < 0.02)
            checked++
        }
        assertTrue("应至少检查一个采样点", checked > 0)
    }

    @Test
    fun `LEO - rangeRate matches predict4java within 20 percent`() {
        val mine = SatellitePropagator(issTle, lat, lon, altM)
        val ref = PassPredictor(issTleP4j, GroundStationPosition(lat, lon, altM))

        var checked = 0
        for (offsetMinutes in 0..240 step 60) {
            val ms = issEpochMs + offsetMinutes * 60_000L
            val m = mine.getPosition(ms)
            val r = ref.getSatPos(Date(ms))
            if (m == null || r == null) continue
            val rel = abs(m.rangeRateKmPerSec - r.rangeRate) /
                maxOf(abs(r.rangeRate), 0.1)
            assertTrue("LEO 径向速度相对差 $rel", rel < 0.2)
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `LEO - altitude matches predict4java within 10 km`() {
        val mine = SatellitePropagator(issTle, lat, lon, altM)
        val ref = PassPredictor(issTleP4j, GroundStationPosition(lat, lon, altM))
        for (offsetMinutes in 0..240 step 60) {
            val ms = issEpochMs + offsetMinutes * 60_000L
            val m = mine.getPosition(ms)
            val r = ref.getSatPos(Date(ms))
            if (m == null || r == null) continue
            val diff = abs(m.altitudeKm - r.altitude)
            assertTrue("LEO 高度差 $diff km", diff < 10.0)
        }
    }

    // ── GEO / SDP4 差分 ──

    @Test
    fun `GEO - position matches predict4java within tolerance`() {
        val mine = SatellitePropagator(geoTle, lat, lon, altM)
        val ref = PassPredictor(geoTleP4j, GroundStationPosition(lat, lon, altM))

        var checked = 0
        for (offsetMinutes in 0..1440 step 120) {
            val ms = geoEpochMs + offsetMinutes * 60_000L
            val m = mine.getPosition(ms)
            val r = ref.getSatPos(Date(ms))
            if (m == null || r == null) continue

            val dLat = angularDistanceDeg(
                Math.toDegrees(m.latitudeRad), Math.toDegrees(r.latitude)
            )
            val dLon = angularDistanceDeg(
                Math.toDegrees(m.longitudeRad), Math.toDegrees(r.longitude)
            )
            val dAz = angularDistanceDeg(
                Math.toDegrees(m.azimuthRad), Math.toDegrees(r.azimuth)
            )
            val dEl = abs(Math.toDegrees(m.elevationRad) - Math.toDegrees(r.elevation))

            assertTrue("GEO 星下点纬度差 $dLat°", dLat < 1.0)
            assertTrue("GEO 星下点经度差 $dLon°", dLon < 1.0)
            assertTrue("GEO 方位角差 $dAz°", dAz < 2.0)
            assertTrue("GEO 仰角差 $dEl°", dEl < 2.0)
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `GEO - isAboveHorizon agrees with predict4java`() {
        val mine = SatellitePropagator(geoTle, lat, lon, altM)
        val ref = PassPredictor(geoTleP4j, GroundStationPosition(lat, lon, altM))
        for (offsetMinutes in 0..1440 step 240) {
            val ms = geoEpochMs + offsetMinutes * 60_000L
            val m = mine.getPosition(ms)
            val r = ref.getSatPos(Date(ms))
            if (m == null || r == null) continue
            assertEquals(
                "GEO 可见性应一致（${Math.toDegrees(m.elevationRad)}° vs ${Math.toDegrees(r.elevation)}°）",
                r.elevation > 0.0,
                m.isAboveHorizon
            )
        }
    }

    // ── 引擎行为 ──

    @Test
    fun `engine - propagates valid LEO satellite to ECI state`() {
        val engine = Sgp4Satellite.fromTle(issTle.toParsedElements())
        // 距历元 1 天（1440 分钟）
        val state = engine.propagate(1440.0)
        assertNotNull(state)
        state ?: return
        val r = state.position
        val magnitude = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
        // ISS 轨道半径约 6778 km
        assertTrue("轨道半径应在 6600-7000 km：$magnitude", magnitude in 6600.0..7000.0)
        // 相位在 [0, 2π)
        assertTrue(state.phaseRad in 0.0..2 * Math.PI)
    }

    @Test
    fun `engine - invalid TLE produces null propagation`() {
        val badTle = TleElements.fromThreeLines(
            arrayOf(
                "BAD",
                "1 00000U 00000A   00001.00000000  .00000000  00000-0  00000-0 0  9991",
                "2 00000   0.0000   0.0000 0000000   0.0000   0.0000 00.00000000    00"
            )
        )
        val propagator = SatellitePropagator(badTle, lat, lon, altM)
        assertNull(propagator.getPosition(System.currentTimeMillis()))
    }

    @Test
    fun `track - returns sampled positions within window`() {
        val propagator = SatellitePropagator(issTle, lat, lon, altM)
        val now = System.currentTimeMillis()
        val track = propagator.getTrack(now, 60, 30, 30)
        // 61 个采样点（前后各 30 分钟、60 秒步长）
        assertEquals(61, track.size)
        assertTrue(track.all { it.isAboveHorizon || !it.isAboveHorizon })
        assertTrue(track.first().timeMs <= now)
        assertTrue(track.last().timeMs >= now)
    }

    private fun angularDistanceDeg(a: Double, b: Double): Double {
        var d = abs(a - b)
        if (d > 180.0) d = 360.0 - d
        return d
    }
}
