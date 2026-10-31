package com.example.hamkit.data.satellite

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 太阳地平仰角的最小实现（NOAA 简化公式）。
 *
 * 为什么单独放内核：AI 助手的 `daylight` 标签要判断「过境时地面站是不是白天」，
 * Android 有 `CelestialComputer`（依赖 java.time），iOS 侧没有等效实现。
 * 这里只保留「太阳赤经/赤纬 + 时角 → 仰角」这一条链路（约 0.01° 精度，
 * 远超 -0.833° 民用晨昏线判定的需要），双端共用同一份算法，避免白天/夜间
 * 判断在两端不一致。
 */
object SunPosition {

    /** 民用晨昏线：太阳几何仰角阈值（含大气折射与太阳半径改正）。 */
    const val CIVIL_DAWN_ELEVATION_DEG: Double = -0.833

    /** 儒略日（Unix 纪元 1970-01-01T00:00:00Z = JD 2440587.5）。 */
    private fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5

    private fun deg2rad(deg: Double): Double = deg * PI / 180.0

    private fun rad2deg(rad: Double): Double = rad * 180.0 / PI

    /** 角度归一到 [0, 360)。 */
    private fun norm360(deg: Double): Double {
        var value = deg % 360.0
        if (value < 0) value += 360.0
        return value
    }

    /**
     * 太阳在给定地面站的地平仰角（度，负值表示在地平线以下）。
     *
     * @param latitudeDeg 地面站纬度（度，北正）
     * @param longitudeDeg 地面站经度（度，东正）
     * @param epochMillis UTC 毫秒
     */
    fun elevationDeg(latitudeDeg: Double, longitudeDeg: Double, epochMillis: Long): Double {
        val jd = julianDay(epochMillis)
        val d = jd - 2_451_545.0 // 距 J2000 的天数

        // 平近点角与平黄经
        val meanAnomaly = norm360(357.5291 + 0.98560028 * d)
        val meanLongitude = norm360(280.46646 + 0.98564736 * d)
        val mRad = deg2rad(meanAnomaly)

        // 黄经（中心差前三项足够）
        val eclipticLongitude = norm360(
            meanLongitude +
                1.914602 * sin(mRad) +
                0.019993 * sin(2 * mRad) +
                0.000289 * sin(3 * mRad),
        )
        val lambda = deg2rad(eclipticLongitude)
        val obliquity = deg2rad(23.439291 - 0.0000004 * d)

        // 赤纬与赤经
        val sinDec = sin(obliquity) * sin(lambda)
        val declination = asin(sinDec)
        val rightAscension = atan2(cos(obliquity) * sin(lambda), cos(lambda))

        // 格林尼治恒星时（度）→ 时角
        val gmst = norm360(280.46061837 + 360.98564736629 * d)
        val hourAngle = deg2rad(
            norm360(gmst + longitudeDeg - rad2deg(rightAscension)),
        )

        val lat = deg2rad(latitudeDeg)
        val sinElevation = sin(lat) * sin(declination) +
            cos(lat) * cos(declination) * cos(hourAngle)
        return rad2deg(asin(sinElevation.coerceIn(-1.0, 1.0)))
    }

    /** 地面站在给定时刻是否白天（太阳仰角 > -0.833°）。 */
    fun isDaylightAt(latitudeDeg: Double, longitudeDeg: Double, epochMillis: Long): Boolean =
        elevationDeg(latitudeDeg, longitudeDeg, epochMillis) > CIVIL_DAWN_ELEVATION_DEG
}
