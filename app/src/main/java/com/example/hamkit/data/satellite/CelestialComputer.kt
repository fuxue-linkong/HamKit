package com.example.hamkit.data.satellite

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 天体在观察者天空中的位置（地平坐标系）。
 *
 * @param azimuthDeg 方位角（度，0=正北，顺时针）
 * @param elevationDeg 仰角（度，负值表示在地平线以下）
 * @param distanceKm 距离（km）
 */
data class CelestialBodyPosition(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val distanceKm: Double,
)

/**
 * 天体计算服务（Phase 4 实现）。
 *
 * 提供太阳/月亮位置、日照与蚀检测、日出日落计算，
 * 供雷达图（日月图标）、轨道地图（星下点）、过境列表（白天过境标记）使用。
 *
 * ## 算法依据（全部为标准公开天文公式，自实现，不依赖第三方库）
 * - 太阳：NOAA 简化太阳位置公式（Spencer/NOAA solar calculator 的思路）：
 *   由儒略日求黄经、黄赤交角、赤经/赤纬，再经格林尼治恒星时换算为时角，
 *   最后用球面三角得到地平坐标。精度约 0.01°，远优于 0.5° 需求。
 * - 月亮：Meeus 天文算法的简化近似（仅主项）：平黄经 + 6.289°sin(M)
 *   等有限项级数，精度约 0.3°~0.5°，对雷达图显示足够。
 * - 蚀检测：圆柱地影模型。太阳距离 ~1 AU，其光线可视为平行光；
 *   卫星在"远离太阳一侧"且到地影轴线的垂直距离 < 地球半径时为蚀中。
 * - 日出日落：标准 -0.833° 民用晨昏线定义（太阳几何仰角阈值，
 *   含标准大气折射与太阳半径改正），在正午前后二分求穿越时刻。
 *
 * 所有经度为东经正、西经负；纬度为北正南负；时间为 UTC。
 */
object CelestialComputer {

    // ── 常量 ──

    /** 地球平均半径（km），用于蚀检测圆柱模型 */
    const val EARTH_MEAN_RADIUS_KM = 6371.0

    /** 地球赤道半径（km），用于地心坐标转换 */
    const val EARTH_EQUATORIAL_RADIUS_KM = 6378.137

    /** 地球扁率倒数 */
    const val EARTH_INVERSE_FLATTENING = 298.257223563

    /** 天文单位（km），太阳平均地心距离 */
    const val ASTRONOMICAL_UNIT_KM = 149_597_870.7

    /** 月球平均地心距离（km） */
    const val MOON_MEAN_DISTANCE_KM = 384_400.0

    /**
     * 民用日出日落仰角阈值（度）。
     * -0.833° = 标准大气折射（≈0.567°）+ 太阳视半径（≈0.267°），
     * 与 timeanddate.com / USNO 的民用定义一致。
     */
    const val CIVIL_SUN_ELEVATION_THRESHOLD = -0.833

    // ── 公共 API ──

    /**
     * 太阳在地面站天空中的位置（顶心视位置，已含视差改正）。
     *
     * 算法：NOAA 简化太阳位置公式求太阳赤经/赤纬（黄道坐标经
     * 黄赤交角旋转到赤道坐标），再计算地面站的地心位置矢量，
     * 由"天体地心矢量 - 观测者地心矢量"得到顶心矢量，
     * 最后经时角公式转换为地平坐标（方位角 0=北、顺时针为正）。
     *
     * 精度：方位/仰角误差 < 0.1°（本方法实际 ~0.01°），满足雷达图/地图需求。
     *
     * @param latitudeDeg 地面站纬度（度，北正南负）
     * @param longitudeDeg 地面站经度（度，东正西负）
     * @param altitudeM 地面站海拔（米）
     * @param epochMillis 时刻（UTC 毫秒）
     */
    fun sunPosition(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        epochMillis: Long,
    ): CelestialBodyPosition {
        val jd = julianDay(epochMillis)
        val (raDeg, decDeg) = sunEquatorial(jd)
        val distKm = sunDistanceAu(jd) * ASTRONOMICAL_UNIT_KM
        return topocentricAzEl(latitudeDeg, longitudeDeg, altitudeM, jd, raDeg, decDeg, distKm)
    }

    /**
     * 月亮在地面站天空中的位置（顶心视位置）。
     *
     * 算法：Meeus 简化月球理论——以平黄经 L'、平近点角 M、升交点角距 F
     * 计算黄经/黄纬（保留主项 6.289°sin(M) 等），黄纬经黄赤交角旋转为
     * 赤经/赤纬，距离用视差级数主项（~38.44 万 km 量级），
     * 其余转换与 [sunPosition] 相同。
     *
     * 精度：方位/仰角约 0.3°~0.5°，对雷达图日月图标显示足够。
     *
     * @param latitudeDeg 地面站纬度（度，北正南负）
     * @param longitudeDeg 地面站经度（度，东正西负）
     * @param altitudeM 地面站海拔（米）
     * @param epochMillis 时刻（UTC 毫秒）
     */
    fun moonPosition(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        epochMillis: Long,
    ): CelestialBodyPosition {
        val jd = julianDay(epochMillis)
        val (raDeg, decDeg, distKm) = moonEquatorial(jd)
        return topocentricAzEl(latitudeDeg, longitudeDeg, altitudeM, jd, raDeg, decDeg, distKm)
    }

    /**
     * 卫星是否被太阳照射（蚀检测）。
     *
     * 算法：圆柱地影模型（cylinder shadow model）。
     * 1. 由太阳赤经/赤纬计算日心方向的单位矢量 s（地心赤道惯性系近似）；
     * 2. 由卫星星下点经纬度 + 高度计算卫星的地心位置矢量 p；
     * 3. 地影轴线单位矢量 a = -s（指向远离太阳的一侧）；
     * 4. 若 p 沿 a 的投影 ≤ 0，说明卫星位于地球朝向太阳的一侧 → 被照亮；
     *    否则若 p 到轴线 a 的垂直距离 < 地球半径 → 卫星在地影圆柱内 → 蚀中；
     *    其余情况 → 被照亮。
     *
     * 太阳距离约 1 AU，平行光假设引入的误差 < 0.1°，对本项目过境日照
     * 标记的精度需求（分钟级）完全足够。
     *
     * @param satLatDeg 卫星纬度（度，星下点，北正南负）
     * @param satLonDeg 卫星经度（度，星下点，东正西负）
     * @param satAltKm 卫星高度（km）
     * @param epochMillis 时刻（UTC 毫秒）
     * @return true = 被太阳照亮；false = 在地影内（蚀中）
     */
    fun isSunlit(
        satLatDeg: Double,
        satLonDeg: Double,
        satAltKm: Double,
        epochMillis: Long,
    ): Boolean {
        val jd = julianDay(epochMillis)
        val gmstDeg = gmstDegrees(jd)

        // 日心方向单位矢量（赤道坐标系，黄经→赤经/赤纬→直角坐标）
        val (sunRaDeg, sunDecDeg) = sunEquatorial(jd)
        val sunRa = Math.toRadians(sunRaDeg)
        val sunDec = Math.toRadians(sunDecDeg)
        val sunDir = doubleArrayOf(
            cos(sunDec) * cos(sunRa),
            cos(sunDec) * sin(sunRa),
            sin(sunDec),
        )

        // 卫星地心位置矢量（由星下点 + 高度，经 ECEF→ECI 旋转得到）
        val satEci = geodeticToEci(satLatDeg, satLonDeg, satAltKm, gmstDeg)

        // 地影轴线：远离太阳方向
        val axisX = -sunDir[0]
        val axisY = -sunDir[1]
        val axisZ = -sunDir[2]

        // 卫星在轴线上的投影
        val proj = satEci[0] * axisX + satEci[1] * axisY + satEci[2] * axisZ
        if (proj <= 0.0) return true // 位于地球朝向太阳一侧，必然被照亮

        // 卫星到地影轴线的垂直距离
        val satMag2 =
            satEci[0] * satEci[0] + satEci[1] * satEci[1] + satEci[2] * satEci[2]
        val perp2 = satMag2 - proj * proj
        return perp2 >= EARTH_MEAN_RADIUS_KM * EARTH_MEAN_RADIUS_KM
    }

    /**
     * 地面站在给定时刻是否处于白天。
     *
     * 定义：太阳几何仰角 > [CIVIL_SUN_ELEVATION_THRESHOLD]（-0.833°）。
     *
     * @param latitudeDeg 地面站纬度（度）
     * @param longitudeDeg 地面站经度（度）
     * @param epochMillis 时刻（UTC 毫秒）
     */
    fun isDaylightAt(
        latitudeDeg: Double,
        longitudeDeg: Double,
        epochMillis: Long,
    ): Boolean {
        val el = sunPosition(latitudeDeg, longitudeDeg, 0.0, epochMillis).elevationDeg
        return el > CIVIL_SUN_ELEVATION_THRESHOLD
    }

    /**
     * 地面站当日日出/日落时刻（UTC）。
     *
     * "当日"取 [epochMillis] 所在 UTC 日历日。算法：
     * 1. 以当日 UTC 正午 12:00 为锚点（日出必然在正午之前、日落必然在正午之后，
     *    中低纬度成立；极昼/极夜时无穿越点）；
     * 2. 在 [正午-13h, 正午] 区间用二分法求仰角从下向上穿越 -0.833° 的时刻（日出），
     *    在 [正午, 正午+13h] 区间求从上向下穿越的时刻（日落）；
     * 3. 二分迭代 15 次，时间精度约 1.4 秒。
     *
     * 边界情况（极昼/极夜）：找不到穿越点时返回当日 UTC 00:00 / 次日 00:00
     * 作为占位，调用方应通过 [isDaylightAt] 复核后自行处理。
     *
     * @param latitudeDeg 地面站纬度（度）
     * @param longitudeDeg 地面站经度（度）
     * @param epochMillis 当日任意时刻（UTC 毫秒）
     * @return Pair(日出, 日落)，均为 UTC
     */
    fun sunriseSunset(
        latitudeDeg: Double,
        longitudeDeg: Double,
        epochMillis: Long,
    ): Pair<Instant, Instant> {
        val dayStartMs = (epochMillis / DAY_MS) * DAY_MS // 当日 UTC 00:00
        val noonMs = dayStartMs + 12L * 3600L * 1000L
        val halfWindowMs = 13L * 3600L * 1000L

        val riseMs = bisectCrossing(
            latitudeDeg, longitudeDeg, noonMs - halfWindowMs, noonMs, rising = true
        )
        val setMs = bisectCrossing(
            latitudeDeg, longitudeDeg, noonMs, noonMs + halfWindowMs, rising = false
        )

        val fallbackStart = Instant.ofEpochMilli(dayStartMs)
        val fallbackEnd = Instant.ofEpochMilli(dayStartMs + DAY_MS)
        val sunrise = riseMs?.let { Instant.ofEpochMilli(it) } ?: fallbackStart
        val sunset = setMs?.let { Instant.ofEpochMilli(it) } ?: fallbackEnd
        return sunrise to sunset
    }

    // ── 内部实现 ──

    private const val DAY_MS = 86_400_000L
    private const val TWO_PI = 2 * Math.PI

    /**
     * 由 UTC 毫秒时间戳计算儒略日（UT，忽略 ΔT 秒差，对本应用精度足够）。
     */
    private fun julianDay(epochMillis: Long): Double =
        epochMillis / 86_400_000.0 + 2440587.5

    /**
     * 格林尼治平均恒星时（度，0~360）。
     */
    private fun gmstDegrees(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        var gmst = 280.46061837 +
            360.98564736629 * (jd - 2451545.0) +
            (0.000387933 * t - t * t / 38710000.0) * t
        gmst %= 360.0
        return if (gmst < 0) gmst + 360.0 else gmst
    }

    /**
     * 太阳赤经/赤纬（度）。NOAA 简化公式：
     * n = JD - 2451545.0；平黄经 L、平近点角 g；
     * 太阳黄经 λ = L + 1.915°sin(g) + 0.020°sin(2g)；
     * 黄赤交角 ε ≈ 23.439° - 4e-7·n；由球面三角转赤道坐标。
     */
    private fun sunEquatorial(jd: Double): Pair<Double, Double> {
        val n = jd - 2451545.0
        val l = mod360(280.460 + 0.9856474 * n)
        val g = mod360(357.528 + 0.9856003 * n)
        val lambda = mod360(l + 1.915 * sin(Math.toRadians(g)) + 0.020 * sin(Math.toRadians(2 * g)))
        val eps = 23.439 - 0.0000004 * n
        val epsR = Math.toRadians(eps)
        val lambdaR = Math.toRadians(lambda)
        val ra = Math.toDegrees(atan2(cos(epsR) * sin(lambdaR), cos(lambdaR)))
        val dec = Math.toDegrees(asin(sin(epsR) * sin(lambdaR)))
        return normalizeRa(ra) to dec
    }

    /**
     * 太阳地心距离（AU）。Kepler 椭圆轨道的级数近似，误差 < 1e-4 AU。
     */
    private fun sunDistanceAu(jd: Double): Double {
        val g = mod360(357.528 + 0.9856003 * (jd - 2451545.0))
        val gR = Math.toRadians(g)
        return 1.00014 - 0.01671 * cos(gR) - 0.00014 * cos(2 * gR)
    }

    /**
     * 月球赤经/赤纬（度）与地心距离（km）。
     * Meeus 简化月球理论：
     * d = JD - 2451545.0（历元 J2000 起的天数）；
     * 平黄经 L'、平近点角 M、距角 D、升交点角距 F；
     * 黄经主项：L' + 6.289°sin(M) + 1.274°sin(2D−M) + 0.658°sin(2D)；
     * 黄纬主项：5.128°sin(F) + 0.280°sin(M+F) + 0.277°sin(M−F)；
     * 距离主项：385001 − 20905°cos(M) − 3699°cos(2D−M)（km）。
     */
    private fun moonEquatorial(jd: Double): Triple<Double, Double, Double> {
        // 修复 P2-2：Meeus J2000.0 月球公式的系数以 d = JD - 2451545.0 为基准，
        // 此前误用 2451543.5 导致 2 天相位偏差（月球运动 ~1.1°/小时，偏差可达 19.8°+）。
        // 太阳公式使用自己的 n（sunEquatorial 内部已正确），此处为月球专用变量。
        val d = jd - 2451545.0
        val lp = mod360(218.316 + 13.176396 * d) // 平黄经
        val m = mod360(134.963 + 13.064993 * d)  // 平近点角
        val dAng = mod360(297.850 + 12.190749 * d) // 距角
        val f = mod360(93.272 + 13.229350 * d)  // 升交点角距

        val mR = Math.toRadians(m)
        val dR = Math.toRadians(dAng)
        val fR = Math.toRadians(f)

        val eclLon = mod360(lp + 6.289 * sin(mR) + 1.274 * sin(2 * dR - mR) + 0.658 * sin(2 * dR))
        val eclLat = 5.128 * sin(fR) + 0.280 * sin(mR + fR) + 0.277 * sin(mR - fR)
        val dist = 385001.0 - 20905.0 * cos(mR) - 3699.0 * cos(2 * dR - mR)

        val eps = Math.toRadians(23.4393 - 0.00000036 * d)
        val lonR = Math.toRadians(eclLon)
        val latR = Math.toRadians(eclLat)
        val ra = Math.toDegrees(
            atan2(sin(lonR) * cos(eps) - Math.tan(latR) * sin(eps), cos(lonR))
        )
        val dec = Math.toDegrees(
            asin(sin(latR) * cos(eps) + cos(latR) * sin(eps) * sin(lonR))
        )
        return Triple(normalizeRa(ra), dec, dist)
    }

    /**
     * 由天体地心赤经/赤纬与距离、观测者经纬高，计算顶心地平坐标。
     *
     * 步骤：观测者地心矢量（椭球模型 ECEF→ECI 旋转）→ 顶心矢量
     * = 天体地心矢量 − 观测者地心矢量 → 顶心赤经/赤纬 →
     * 时角 h = 本地恒星时 − 顶心赤经 → 球面三角得仰角与方位角。
     * 顶心计算天然包含视差（高度/距离修正），无需近似公式。
     */
    private fun topocentricAzEl(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        jd: Double,
        raDeg: Double,
        decDeg: Double,
        distKm: Double,
    ): CelestialBodyPosition {
        val gmstDeg = gmstDegrees(jd)
        val obsEci = geodeticToEci(latitudeDeg, longitudeDeg, altitudeM / 1000.0, gmstDeg)

        val raR = Math.toRadians(raDeg)
        val decR = Math.toRadians(decDeg)
        val bodyEci = doubleArrayOf(
            distKm * cos(decR) * cos(raR),
            distKm * cos(decR) * sin(raR),
            distKm * sin(decR),
        )
        val topo = doubleArrayOf(
            bodyEci[0] - obsEci[0],
            bodyEci[1] - obsEci[1],
            bodyEci[2] - obsEci[2],
        )
        val topoDist = sqrt(topo[0] * topo[0] + topo[1] * topo[1] + topo[2] * topo[2])
        val topoRaR = atan2(topo[1], topo[0])
        val topoDecR = asin((topo[2] / topoDist).coerceIn(-1.0, 1.0))

        val latR = Math.toRadians(latitudeDeg)
        val hourAngleR = Math.toRadians(gmstDeg + longitudeDeg) - topoRaR

        val sinAlt = sin(latR) * sin(topoDecR) + cos(latR) * cos(topoDecR) * cos(hourAngleR)
        val elevationDeg = Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))

        var azimuthDeg = Math.toDegrees(
            atan2(
                sin(hourAngleR),
                cos(hourAngleR) * sin(latR) - Math.tan(topoDecR) * cos(latR),
            )
        ) + 180.0
        azimuthDeg %= 360.0
        if (azimuthDeg < 0) azimuthDeg += 360.0

        return CelestialBodyPosition(
            azimuthDeg = azimuthDeg,
            elevationDeg = elevationDeg,
            distanceKm = topoDist,
        )
    }

    /**
     * 由大地坐标（纬度、经度、高度 km）计算地心位置矢量（ECI 惯性系近似，
     * 由 ECEF 经 GMST 旋转得到）。椭球模型（WGS-84 参数）。
     */
    private fun geodeticToEci(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeKm: Double,
        gmstDeg: Double,
    ): DoubleArray {
        val latR = Math.toRadians(latitudeDeg)
        val lonR = Math.toRadians(longitudeDeg)
        val e2 = (2.0 - 1.0 / EARTH_INVERSE_FLATTENING) / EARTH_INVERSE_FLATTENING
        val sinLat = sin(latR)
        val cosLat = cos(latR)
        val n = EARTH_EQUATORIAL_RADIUS_KM / sqrt(1.0 - e2 * sinLat * sinLat)

        val xEcef = (n + altitudeKm) * cosLat * cos(lonR)
        val yEcef = (n + altitudeKm) * cosLat * sin(lonR)
        val zEcef = (n * (1.0 - e2) + altitudeKm) * sinLat

        val gmstR = Math.toRadians(gmstDeg)
        return doubleArrayOf(
            xEcef * cos(gmstR) - yEcef * sin(gmstR),
            xEcef * sin(gmstR) + yEcef * cos(gmstR),
            zEcef,
        )
    }

    /**
     * 在 [loMs, hiMs] 区间二分求仰角穿越 [CIVIL_SUN_ELEVATION_THRESHOLD]
     * 的时刻。[rising]=true 时求上升穿越（日出），false 时求下降穿越（日落）。
     * 无穿越点（极昼/极夜）时返回 null。
     */
    private fun bisectCrossing(
        latitudeDeg: Double,
        longitudeDeg: Double,
        loMs: Long,
        hiMs: Long,
        rising: Boolean,
    ): Long? {
        val threshold = CIVIL_SUN_ELEVATION_THRESHOLD
        val fLo = sunElevationAt(latitudeDeg, longitudeDeg, loMs) - threshold
        val fHi = sunElevationAt(latitudeDeg, longitudeDeg, hiMs) - threshold
        val cross = if (rising) fLo <= 0 && fHi > 0 else fLo > 0 && fHi <= 0
        if (!cross) return null

        var lo = loMs
        var hi = hiMs
        repeat(15) {
            val mid = lo + (hi - lo) / 2
            val fMid = sunElevationAt(latitudeDeg, longitudeDeg, mid) - threshold
            val midIsDaySide = if (rising) fMid > 0 else fMid <= 0
            if (midIsDaySide) hi = mid else lo = mid
        }
        return lo
    }

    private fun sunElevationAt(latitudeDeg: Double, longitudeDeg: Double, ms: Long): Double =
        sunPosition(latitudeDeg, longitudeDeg, 0.0, ms).elevationDeg

    private fun mod360(deg: Double): Double {
        var v = deg % 360.0
        if (v < 0) v += 360.0
        return v
    }

    private fun normalizeRa(raDeg: Double): Double = mod360(raDeg)

    /** 便捷方法：把 UTC 时刻转成"当日 00:00 的 LocalDate"，供调试/测试用。 */
    internal fun utcDateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
}
