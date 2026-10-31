package com.example.hamkit.data.satellite.predict

import java.time.Instant

/**
 * 卫星位置传播门面：把自研 SGP4/SDP4 引擎（[Sgp4Satellite]）与坐标转换
 * （[OrbitMath]）组合为易用的"给定时刻 → 地平坐标/星下点"接口。
 *
 * 与 predict4java 的 `PassPredictor.getSatPos` 语义对齐：
 * - 方位角/仰角/星下点经纬度均为**弧度**
 * - 斜距单位为 km，径向速度单位为 km/s，高度单位为 km
 *
 * 实例内部持有 SGP4 传播状态，需单线程使用；预测器为每颗卫星创建独立实例，
 * 不跨卫星/线程复用同一实例。
 *
 * @param tle 卫星 TLE 元素集
 * @param latitudeDeg 地面站纬度（度，北正南负）
 * @param longitudeDeg 地面站经度（度，东正西负）
 * @param altitudeM 地面站海拔（米）
 */
class SatellitePropagator(
    val tle: TleElements,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double = 0.0,
) {

    private val engine: Sgp4Satellite? = try {
        Sgp4Satellite.fromTle(tle.toParsedElements())
    } catch (_: IllegalArgumentException) {
        null
    }

    private val observerLatRad = Math.toRadians(latitudeDeg)
    private val observerLonRad = Math.toRadians(longitudeDeg)
    private val observerAltKm = altitudeM / 1000.0

    /** 卫星名称（与 predict4java TLE.name 语义一致） */
    val name: String
        get() = tle.name

    /** NORAD 目录编号 */
    val catalogNumber: Int
        get() = tle.catalogNumber

    /**
     * 计算指定时刻的卫星地平坐标与星下点。
     *
     * @param epochMillis 时刻（UTC 毫秒）
     * @return 位置结果；TLE 无效或轨道计算失败（越界/再入）时为 null
     */
    fun getPosition(epochMillis: Long): PropagatedPosition? {
        val engine = engine ?: return null
        val tsince = (OrbitMath.jdayOfMillis(epochMillis) - tle.jdsatepoch) * 1440.0
        val state = engine.propagate(tsince) ?: return null
        return toPosition(state, epochMillis)
    }

    /**
     * 计算一段时间窗口内的等间隔轨迹采样（地面轨迹）。
     *
     * @param epochMillis 参考时刻（UTC 毫秒）
     * @param incrementSeconds 采样步长（秒）
     * @param minutesBefore 参考时刻之前回溯的分钟数
     * @param minutesAfter 参考时刻之后前瞻的分钟数
     * @return 按时间升序的位置采样列表；单次采样失败时跳过该点
     */
    fun getTrack(
        epochMillis: Long,
        incrementSeconds: Int,
        minutesBefore: Int,
        minutesAfter: Int,
    ): List<PropagatedPosition> {
        val engine = engine ?: return emptyList()
        if (incrementSeconds <= 0) return emptyList()
        val startMs = epochMillis - minutesBefore * 60_000L
        val endMs = epochMillis + minutesAfter * 60_000L
        val stepMs = incrementSeconds * 1000L
        val result = ArrayList<PropagatedPosition>()
        var t = startMs
        while (t <= endMs) {
            val tsince = (OrbitMath.jdayOfMillis(t) - tle.jdsatepoch) * 1440.0
            val state = engine.propagate(tsince)
            if (state != null) {
                result.add(toPosition(state, t))
            }
            t += stepMs
        }
        return result
    }

    /**
     * 从 ECI 状态计算地平坐标与星下点。
     *
     * 与 predict4java 的 calculateObs/calculateLatLonAlt 语义对齐：
     * 1. 地面站 ECF → ECI（按 GMST 旋转）；
     * 2. 顶心坐标：range = sat - obs，投影到本地水平面得到方位角/仰角/斜距；
     * 3. 径向速度：rangeRate = dot(range, sat_vel - obs_vel) / |range|，
     *    其中 obs_vel = ω × obs（地球自转速度，ω = 7.292115e-5 rad/s）；
     * 4. 星下点：ECI → 大地坐标（迭代求解）。
     */
    private fun toPosition(state: EciState, epochMillis: Long): PropagatedPosition {
        val jd = OrbitMath.jdayOfMillis(epochMillis)
        val gmst = OrbitMath.gstime(jd)

        // 地面站 ECF → ECI
        val obsEcf = OrbitMath.geodeticToEcf(observerLonRad, observerLatRad, observerAltKm)
        val obsEci = rotateEcfToEci(obsEcf, gmst)
        val satEci = state.position

        // 顶心坐标（range = sat − obs）
        val rx = satEci[0] - obsEci[0]
        val ry = satEci[1] - obsEci[1]
        val rz = satEci[2] - obsEci[2]
        val range = Math.sqrt(rx * rx + ry * ry + rz * rz)

        val sinLat = Math.sin(observerLatRad)
        val cosLat = Math.cos(observerLatRad)
        val theta = OrbitMath.mod2pi(gmst + observerLonRad)
        val sinTheta = Math.sin(theta)
        val cosTheta = Math.cos(theta)

        val topS = sinLat * cosTheta * rx + sinLat * sinTheta * ry - cosLat * rz
        val topE = -sinTheta * rx + cosTheta * ry
        val topZ = cosLat * cosTheta * rx + cosLat * sinTheta * ry + sinLat * rz

        var azimuth = Math.atan2(-topE, topS) + Math.PI
        if (azimuth < 0.0) azimuth += Sgp4Constants.TWO_PI
        val elevation = Math.asin((topZ / range).coerceIn(-1.0, 1.0))

        // 径向速度：相对速度 = 卫星速度 − 地球自转速度（ω × obs）
        val obsVelX = -MFACTOR * obsEci[1]
        val obsVelY = MFACTOR * obsEci[0]
        val relVx = state.velocity[0] - obsVelX
        val relVy = state.velocity[1] - obsVelY
        val relVz = state.velocity[2]
        val rangeRate = (rx * relVx + ry * relVy + rz * relVz) / range

        // 星下点（ECI → 大地坐标）
        val geodetic = OrbitMath.eciToGeodetic(satEci, gmst)
        val subLat = geodetic[1]
        val subLon = geodetic[0]
        val subAlt = geodetic[2]

        return PropagatedPosition(
            timeMs = epochMillis,
            azimuthRad = azimuth,
            elevationRad = elevation,
            rangeKm = range,
            rangeRateKmPerSec = rangeRate,
            latitudeRad = subLat,
            longitudeRad = subLon,
            altitudeKm = subAlt,
            phaseRad = state.phaseRad,
            isAboveHorizon = elevation > 0.0,
        )
    }

    /**
     * ECF → ECI 旋转（绕 Z 轴旋转 +gmst）。
     */
    private fun rotateEcfToEci(v: DoubleArray, gmst: Double): DoubleArray {
        val c = Math.cos(gmst)
        val s = Math.sin(gmst)
        return doubleArrayOf(c * v[0] - s * v[1], s * v[0] + c * v[1], v[2])
    }

    companion object {
        /** 地球自转角速度（rad/s） */
        private const val MFACTOR = 7.292115E-5
    }
}

/**
 * 卫星在某一时刻的位置结果（与 predict4java `SatPos` 字段语义对齐）。
 *
 * 角度单位为弧度，距离单位为 km，速度单位为 km/s。
 */
data class PropagatedPosition(
    /** 采样时刻（UTC 毫秒） */
    val timeMs: Long,
    /** 方位角（rad，0=正北，顺时针） */
    val azimuthRad: Double,
    /** 仰角（rad，负值在地平线以下） */
    val elevationRad: Double,
    /** 斜距（km） */
    val rangeKm: Double,
    /** 径向速度（km/s，>0 远离，<0 接近） */
    val rangeRateKmPerSec: Double,
    /** 星下点纬度（rad） */
    val latitudeRad: Double,
    /** 星下点经度（rad） */
    val longitudeRad: Double,
    /** 轨道高度（km） */
    val altitudeKm: Double,
    /** 轨道相位（rad，[0, 2π)） */
    val phaseRad: Double,
    /** 是否在地平线以上（仰角 > 0） */
    val isAboveHorizon: Boolean,
) {
    /** 采样时刻（UTC） */
    val time: Instant
        get() = Instant.ofEpochMilli(timeMs)
}
