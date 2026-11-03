package com.example.hamkit.data.satellite.predict

import kotlin.math.*

/**
 * 轨道计算用时间与坐标数学工具（纯函数，无状态）。
 *
 * 算法来源（均为公共领域/宽松许可的公开公式）：
 * - Vallado, "Fundamentals of Astrodynamics and Applications"（儒略日、GMST、大地坐标转换）
 * - satellite.js（MIT License）的 ext.ts / transforms.ts / gstime.ts
 */
internal object OrbitMath {

    /** 一天的总毫秒数 */
    const val MS_PER_DAY = 86_400_000.0

    /** WGS-84 椭球长半轴（km） */
    private const val WGS84_A = 6378.137

    /** WGS-84 椭球短半轴（km） */
    private const val WGS84_B = 6356.7523142

    /**
     * 由年、月、日、时、分、秒（UTC）计算儒略日。
     */
    fun jday(year: Int, mon: Int, day: Int, hr: Int, minute: Int, sec: Double): Double {
        val yearD = year.toDouble()
        val monD = mon.toDouble()
        val dayD = day.toDouble()
        return 367.0 * yearD -
            floor(7.0 * (yearD + floor((monD + 9.0) / 12.0)) * 0.25) +
            floor(275.0 * monD / 9.0) +
            dayD +
            1721013.5 +
            ((sec / 60.0 + minute) / 60.0 + hr) / 24.0
    }

    /**
     * 把"年内日数"（epoch day of year，含小数）转换为月、日、时、分、秒。
     * 闰年按 [year] 每 4 年一次近似（2000 为闰年，公式与标准实现一致）。
     */
    fun days2mdhms(year: Int, days: Double): DoubleArray {
        val lmonth = intArrayOf(
            31, if (year % 4 == 0) 29 else 28, 31, 30, 31, 30,
            31, 31, 30, 31, 30, 31
        )
        val dayofyr = floor(days).toInt()
        var i = 0
        var inttemp = 0
        // 先判断下标再访问，避免潜在越界
        while (i < 11 && dayofyr > inttemp + lmonth[i]) {
            inttemp += lmonth[i]
            i++
        }
        val mon = i + 1
        val day = dayofyr - inttemp
        var temp = (days - dayofyr) * 24.0
        val hr = floor(temp).toInt()
        temp = (temp - hr) * 60.0
        val minute = floor(temp).toInt()
        // 秒保留小数亚秒精度（截断会丢失 TLE 历元日的小数部分）
        val sec = (temp - minute) * 60.0
        return doubleArrayOf(mon.toDouble(), day.toDouble(), hr.toDouble(), minute.toDouble(), sec)
    }

    /**
     * 格林尼治平恒星时（rad，[0, 2π)）。
     *
     * @param jdut1 儒略日（UT1）
     */
    fun gstime(jdut1: Double): Double {
        val tut1 = (jdut1 - 2451545.0) / 36525.0
        var temp =
            -6.2e-6 * tut1 * tut1 * tut1 +
                0.093104 * tut1 * tut1 +
                (876600.0 * 3600 + 8640184.812866) * tut1 +
                67310.54841 // 秒
        temp = (temp * PI / 180.0 / 240.0) % TWO_PI // 360/86400 = 1/240
        if (temp < 0.0) temp += TWO_PI
        return temp
    }

    /**
     * 由 UTC 毫秒时间戳直接计算儒略日（无历法换算，避免跨年误差）。
     */
    fun jdayOfMillis(epochMillis: Long): Double =
        epochMillis / MS_PER_DAY + 2440587.5

    /**
     * 大地坐标 → 地心地固系（ECF）直角坐标（WGS-84 椭球）。
     *
     * @param longitudeRad 经度（rad）
     * @param latitudeRad 纬度（rad）
     * @param heightKm 高度（km）
     * @return [x, y, z]（km）
     */
    fun geodeticToEcf(longitudeRad: Double, latitudeRad: Double, heightKm: Double): DoubleArray {
        val f = (WGS84_A - WGS84_B) / WGS84_A
        val e2 = 2.0 * f - f * f
        val normal = WGS84_A / sqrt(1.0 - e2 * sin(latitudeRad) * sin(latitudeRad))
        val x = (normal + heightKm) * cos(latitudeRad) * cos(longitudeRad)
        val y = (normal + heightKm) * cos(latitudeRad) * sin(longitudeRad)
        val z = (normal * (1.0 - e2) + heightKm) * sin(latitudeRad)
        return doubleArrayOf(x, y, z)
    }

    /**
     * ECI 位置 → 大地坐标（经度/纬度 rad、高度 km），迭代求解大地纬度。
     *
     * @param eci [x, y, z]（km，ECI 惯性系）
     * @param gmst 格林尼治恒星时（rad）
     * @return DoubleArray[3] = [经度, 纬度, 高度]（经度/纬度 rad，高度 km）
     */
    fun eciToGeodetic(eci: DoubleArray, gmst: Double): DoubleArray {
        val a = WGS84_A
        val b = WGS84_B
        val f = (a - b) / a
        val e2 = 2.0 * f - f * f
        val r = sqrt(eci[0] * eci[0] + eci[1] * eci[1])
        var longitude = atan2(eci[1], eci[0]) - gmst
        while (longitude < -PI) longitude += TWO_PI
        while (longitude > PI) longitude -= TWO_PI

        var latitude = atan2(eci[2], r)
        var c = 0.0
        var k = 0
        while (k++ < 20) {
            c = 1.0 / sqrt(1.0 - e2 * sin(latitude) * sin(latitude))
            latitude = atan2(eci[2] + a * c * e2 * sin(latitude), r)
        }
        val height = r / cos(latitude) - a * c
        return doubleArrayOf(longitude, latitude, height)
    }

    /**
     * 由地面站大地坐标与卫星 ECF 位置计算地平坐标（方位角/仰角/斜距）。
     *
     * @param observerLonRad 观察者经度（rad）
     * @param observerLatRad 观察者纬度（rad）
     * @param observerAltKm 观察者高度（km）
     * @param satelliteEcf 卫星 ECF 位置 [x, y, z]（km）
     * @return DoubleArray[3] = [方位角, 仰角, 斜距]（方位角/仰角 rad，斜距 km）。
     *         方位角 0=正南、逆时针为正（Kelso 约定），调用方如需 0=正北可再转换。
     */
    fun ecfToLookAngles(
        observerLonRad: Double,
        observerLatRad: Double,
        observerAltKm: Double,
        satelliteEcf: DoubleArray,
    ): DoubleArray {
        val obsEcf = geodeticToEcf(observerLonRad, observerLatRad, observerAltKm)
        val rx = satelliteEcf[0] - obsEcf[0]
        val ry = satelliteEcf[1] - obsEcf[1]
        val rz = satelliteEcf[2] - obsEcf[2]
        val sinLat = sin(observerLatRad)
        val cosLat = cos(observerLatRad)
        val sinLon = sin(observerLonRad)
        val cosLon = cos(observerLonRad)

        val topS = sinLat * cosLon * rx + sinLat * sinLon * ry - cosLat * rz
        val topE = -sinLon * rx + cosLon * ry
        val topZ = cosLat * cosLon * rx + cosLat * sinLon * ry + sinLat * rz

        val rangeSat = sqrt(topS * topS + topE * topE + topZ * topZ)
        val el = asin(topZ / rangeSat)
        val az = atan2(-topE, topS) + PI
        return doubleArrayOf(az, el, rangeSat)
    }

    /**
     * 归一化角度到 [0, 2π)。
     */
    fun mod2pi(value: Double): Double {
        var v = value % TWO_PI
        if (v < 0.0) v += TWO_PI
        return v
    }

    private const val TWO_PI = 2.0 * PI
}
