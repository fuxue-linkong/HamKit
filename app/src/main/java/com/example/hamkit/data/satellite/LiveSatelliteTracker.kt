package com.example.hamkit.data.satellite

import com.example.hamkit.data.satellite.predict.PropagatedPosition
import com.example.hamkit.data.satellite.predict.SatellitePropagator
import com.example.hamkit.data.satellite.predict.TleElements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 卫星实时追踪信息（单个时刻的快照）。
 *
 * 所有角度均已换算为度（自研引擎 [PropagatedPosition] 内部为弧度）；
 * 距离单位为 km，速度单位为 m/s，时间戳为 UTC 毫秒。
 *
 * @param name 卫星名称
 * @param catalogNumber NORAD 编号
 * @param azimuthDeg 方位角（度，0=正北，顺时针，范围 [0, 360)）
 * @param elevationDeg 仰角（度，负值表示在地平线以下）
 * @param rangeKm 斜距（km）
 * @param rangeRateMps 径向速度（m/s，>0 远离，<0 接近）
 * @param altitudeKm 轨道高度（km）
 * @param subpointLatDeg 星下点纬度（度）
 * @param subpointLonDeg 星下点经度（度，范围 [-180, 180]）
 * @param phaseDeg 轨道相位（度，0-360）
 * @param isEclipsed 是否处于地影（蚀）中。依赖 [CelestialComputer.isSunlit]，
 * 该计算不可用时（如桩实现未就绪）回退为 false（视为日照）。
 * @param sunAzimuthDeg 太阳方位角（度），[CelestialComputer.sunPosition] 不可用时为 null
 * @param sunElevationDeg 太阳仰角（度），同上
 * @param moonAzimuthDeg 月亮方位角（度），同上
 * @param moonElevationDeg 月亮仰角（度），同上
 * @param dopplerDownlinkHz 多普勒校正后下行频率（Hz），无基准频率或计算不可用时为 null
 * @param dopplerUplinkHz 多普勒校正后上行频率（Hz），同上
 * @param isAboveHorizon 是否在地平线以上
 * @param timestampMs 采样时刻（UTC 毫秒）
 */
data class SatelliteLiveInfo(
    val name: String,
    val catalogNumber: Int,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val rangeRateMps: Double,
    val altitudeKm: Double,
    val subpointLatDeg: Double,
    val subpointLonDeg: Double,
    val phaseDeg: Double,
    val isEclipsed: Boolean,
    val sunAzimuthDeg: Double?,
    val sunElevationDeg: Double?,
    val moonAzimuthDeg: Double?,
    val moonElevationDeg: Double?,
    val dopplerDownlinkHz: Double?,
    val dopplerUplinkHz: Double?,
    val isAboveHorizon: Boolean,
    val timestampMs: Long,
) {
    /** 是否在地平线以上（以仰角为准的冗余判断，供 UI 直接使用） */
    val isVisible: Boolean
        get() = elevationDeg > 0.0
}

/**
 * 卫星实时追踪器。
 *
 * 以 1 秒为周期调用自研引擎 [SatellitePropagator.getPosition] 计算卫星实时
 * 方位/仰角/距离/速度/星下点，并结合 [CelestialComputer]（日月位置、蚀检测）与
 * [DopplerCalculator]（多普勒频移）组装成 [SatelliteLiveInfo] 快照。
 *
 * 计算全部在 [Dispatchers.Default] 上执行，不阻塞主线程；调用方通过
 * [liveInfo] 订阅最新的快照。
 *
 * @param tle 卫星 TLE
 * @param latitudeDeg 地面站纬度（度）
 * @param longitudeDeg 地面站经度（度）
 * @param altitudeM 地面站海拔（米）
 * @param baseDownlinkHz 下行静止基准频率（Hz），用于多普勒计算，默认 145.800 MHz
 * @param baseUplinkHz 上行静止基准频率（Hz），用于多普勒计算，默认 435.000 MHz
 */
class LiveSatelliteTracker(
    tle: TleElements,
    latitudeDeg: Double,
    longitudeDeg: Double,
    altitudeM: Double = 0.0,
    private val baseDownlinkHz: Double = DEFAULT_DOWNLINK_HZ,
    private val baseUplinkHz: Double = DEFAULT_UPLINK_HZ,
) {

    private val satelliteName = tle.name.trim().ifEmpty { tle.catnum.toString() }
    private val catalogNumber = tle.catnum
    private val stationLatDeg = latitudeDeg
    private val stationLonDeg = longitudeDeg
    private val stationAltM = altitudeM
    private val propagator: SatellitePropagator? = try {
        SatellitePropagator(tle, latitudeDeg, longitudeDeg, altitudeM)
    } catch (e: CancellationException) {
        throw e
    } catch (_: IllegalArgumentException) {
        null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private val _liveInfo = MutableStateFlow<SatelliteLiveInfo?>(null)
    /** 最新卫星实时信息；尚未产生首个快照时为 null */
    val liveInfo: StateFlow<SatelliteLiveInfo?> = _liveInfo.asStateFlow()

    private val _trackHistory = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    /** 卫星轨迹历史（方位角/仰角度数对），按时间升序排列 */
    val trackHistory: StateFlow<List<Pair<Double, Double>>> = _trackHistory.asStateFlow()

    private val historyLock = Any()
    private val historyBuffer = mutableListOf<Pair<Double, Double>>()

    /**
     * 启动 1 秒周期的实时追踪循环。重复调用安全（已运行时不重启）。
     */
    fun start() {
        if (tickJob?.isActive == true) return
        synchronized(historyLock) {
            historyBuffer.clear()
            _trackHistory.value = emptyList()
        }
        tickJob = scope.launch {
            while (isActive) {
                val info = computeOnce()
                _liveInfo.value = info
                if (info != null && info.elevationDeg > -5.0) {
                    synchronized(historyLock) {
                        historyBuffer.add(info.azimuthDeg to info.elevationDeg)
                        if (historyBuffer.size > MAX_TRACK_HISTORY) {
                            historyBuffer.removeAt(0)
                        }
                        _trackHistory.value = historyBuffer.toList()
                    }
                } else {
                    // 修复 P3-1：buffer 只收 elevation > -5.0 的点，last 点永远 > -5°，
                    // 原基于 last 点的清空条件永假。改为首次跌破 -5° 时立即清空，
                    // 避免过时轨迹数据残留。
                    synchronized(historyLock) {
                        if (historyBuffer.isNotEmpty()) {
                            historyBuffer.clear()
                            _trackHistory.value = emptyList()
                        }
                    }
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * 停止追踪循环并清空当前快照。可安全重复调用。
     */
    fun stop() {
        tickJob?.cancel()
        tickJob = null
        _liveInfo.value = null
        synchronized(historyLock) {
            historyBuffer.clear()
            _trackHistory.value = emptyList()
        }
    }

    /**
     * 计算指定时刻的卫星实时信息（同步、可测试）。
     *
     * TLE 无效或计算失败时返回 null（单次失败不会终止追踪循环）。
     *
     * @param epochMillis 时刻（UTC 毫秒），默认当前时间
     */
    fun computeOnce(epochMillis: Long = System.currentTimeMillis()): SatelliteLiveInfo? {
        val propagator = propagator ?: return null
        return try {
            val pos = propagator.getPosition(epochMillis) ?: return null
            toLiveInfo(pos, epochMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            // 轨道计算内部异常（数值溢出等），跳过本帧
            null
        }
    }

    private fun toLiveInfo(pos: PropagatedPosition, epochMillis: Long): SatelliteLiveInfo {
        val azimuthDeg = normalizeAzimuthDeg(Math.toDegrees(pos.azimuthRad))
        val elevationDeg = Math.toDegrees(pos.elevationRad)
        val subpointLat = Math.toDegrees(pos.latitudeRad)
        val subpointLon = normalizeLongitude180(Math.toDegrees(pos.longitudeRad))
        // rangeRate 单位 km/s → m/s
        val rangeRateMps = pos.rangeRateKmPerSec * 1000.0

        // 日月位置使用地面站坐标（观察者视角），失败时静默降级为 null
        val sun = runCatchingCancellable {
            CelestialComputer.sunPosition(stationLatDeg, stationLonDeg, stationAltM, epochMillis)
        }
        val moon = runCatchingCancellable {
            CelestialComputer.moonPosition(stationLatDeg, stationLonDeg, stationAltM, epochMillis)
        }
        // 蚀检测：isSunlit 为 true 表示被太阳照射；失败时回退为日照（false）
        val eclipsed = runCatchingCancellable {
            !CelestialComputer.isSunlit(subpointLat, subpointLon, pos.altitudeKm, epochMillis)
        }.getOrDefault(false)

        // 多普勒：失败时静默降级为 null
        val doppler = runCatchingCancellable {
            DopplerCalculator.dopplerFrequencies(baseDownlinkHz, baseUplinkHz, rangeRateMps)
        }.getOrNull()

        return SatelliteLiveInfo(
            name = satelliteName,
            catalogNumber = catalogNumber,
            azimuthDeg = azimuthDeg,
            elevationDeg = elevationDeg,
            rangeKm = pos.rangeKm,
            rangeRateMps = rangeRateMps,
            altitudeKm = pos.altitudeKm,
            subpointLatDeg = subpointLat,
            subpointLonDeg = subpointLon,
            phaseDeg = normalizeAzimuthDeg(Math.toDegrees(pos.phaseRad)),
            isEclipsed = eclipsed,
            sunAzimuthDeg = sun.getOrNull()?.azimuthDeg,
            sunElevationDeg = sun.getOrNull()?.elevationDeg,
            moonAzimuthDeg = moon.getOrNull()?.azimuthDeg,
            moonElevationDeg = moon.getOrNull()?.elevationDeg,
            dopplerDownlinkHz = doppler?.first,
            dopplerUplinkHz = doppler?.second,
            isAboveHorizon = pos.isAboveHorizon || elevationDeg > 0.0,
            timestampMs = epochMillis,
        )
    }

    companion object {
        /** 追踪周期（毫秒） */
        const val TICK_INTERVAL_MS = 1_000L

        /** 轨迹历史最大采样数（约 2.5 分钟 @ 1 Hz） */
        private const val MAX_TRACK_HISTORY = 150

        /** 默认下行基准频率（Hz）：业余 VHF 频段典型值，供多普勒计算 */
        const val DEFAULT_DOWNLINK_HZ = 145_800_000.0

        /** 默认上行基准频率（Hz）：业余 UHF 频段典型值，供多普勒计算 */
        const val DEFAULT_UPLINK_HZ = 435_000_000.0
    }
}

/**
 * 将方位角归一化到 [0, 360)。
 */
fun normalizeAzimuthDeg(azimuthDeg: Double): Double {
    var value = azimuthDeg % 360.0
    if (value < 0.0) value += 360.0
    return value
}

/**
 * 将经度归一化到 [-180, 180]。
 */
fun normalizeLongitude180(longitudeDeg: Double): Double {
    var value = longitudeDeg % 360.0
    if (value > 180.0) value -= 360.0
    if (value < -180.0) value += 360.0
    return value
}

/**
 * 将跨越 ±180° 经线的轨迹分割为多个连续线段。
 *
 * 轨迹点的经度应先经 [normalizeLongitude180] 归一化。相邻两点经度差绝对值超过
 * 180° 时视为跨越国际日期变更线：按真实行进方向（经度差为正走西侧 -180，
 * 为负走东侧 +180）在边界处线性插值出终止点，结束当前线段并另起新段。
 *
 * @param points 有序的 (纬度, 经度) 点列表
 * @return 分割后的线段列表；每段内相邻点经度差不超过 180°
 */
fun splitTrackAtAntimeridian(points: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(points)

    val segments = mutableListOf<MutableList<Pair<Double, Double>>>()
    var current = mutableListOf(points[0])
    for (i in 1 until points.size) {
        val (prevLat, prevLon) = current.last()
        val (lat, lon) = points[i]
        val diff = lon - prevLon
        if (abs(diff) > 180.0) {
            // 跨线：diff > 0 表示实际向西绕行（跨越 -180），diff < 0 表示向东绕行（跨越 +180）
            val unwrappedLon = prevLon + diff + if (diff > 0.0) -360.0 else 360.0
            val edgeLon = if (diff > 0.0) -180.0 else 180.0
            val denominator = unwrappedLon - prevLon
            val edgeLat = if (denominator == 0.0) {
                prevLat
            } else {
                prevLat + (lat - prevLat) * (edgeLon - prevLon) / denominator
            }
            current.add(edgeLat to edgeLon)
            segments.add(current)
            current = mutableListOf(lat to lon)
        } else {
            current.add(lat to lon)
        }
    }
    segments.add(current)
    return segments
}

/**
 * 生成以星下点为圆心的覆盖圆（足迹）边界点。
 *
 * 使用标准大圆终点公式（与 predict4java 的 [SatPos.getRangeCircle] 概念一致，
 * 为独立实现），点序为方位角 0°→360° 逆时针绕行。经度归一化到 [-180, 180]。
 *
 * @param subpointLatDeg 星下点纬度（度）
 * @param subpointLonDeg 星下点经度（度）
 * @param radiusKm 覆盖圆半径（km），可用 [SatPos.getRangeCircleRadiusKm] 获取
 * @param pointCount 圆周采样点数
 * @return 有序的 (纬度, 经度) 点列表
 */
fun buildCoverageCircle(
    subpointLatDeg: Double,
    subpointLonDeg: Double,
    radiusKm: Double,
    pointCount: Int = 180,
): List<Pair<Double, Double>> {
    if (pointCount < 3) return emptyList()
    val lat = Math.toRadians(subpointLatDeg)
    val lon = Math.toRadians(subpointLonDeg)
    val beta = (radiusKm / EARTH_RADIUS_KM).coerceIn(-PI, PI)

    return (0 until pointCount).map { i ->
        val azimuth = 2.0 * PI * i / pointCount
        val sinBeta = sin(beta)
        val cosBeta = cos(beta)
        val lat2 = asin(sin(lat) * cosBeta + cos(lat) * sinBeta * cos(azimuth)).coerceIn(-PI / 2, PI / 2)
        val num = cosBeta - sin(lat) * sin(lat2)
        val den = cos(lat) * cos(lat2)
        val lon2 = if (den == 0.0) {
            lon
        } else {
            lon + atan2(sin(azimuth) * sinBeta * cos(lat), num)
        }
        val lonNorm = normalizeLongitude180(Math.toDegrees(normalizeRadians(lon2)))
        Math.toDegrees(lat2) to lonNorm
    }
}

/**
 * 计算卫星覆盖圆（足迹）半径（km）。
 *
 * 与 predict4java [SatPos.getRangeCircleRadiusKm] 的公式一致：
 * 半径 = 0.5 * 地球直径 * acos(R / (R + 轨道高度))。
 *
 * @param altitudeKm 卫星轨道高度（km）
 */
fun coverageRadiusKm(altitudeKm: Double): Double {
    val earthRadius = 6378.137
    val ratio = (earthRadius / (earthRadius + altitudeKm)).coerceIn(-1.0, 1.0)
    return 0.5 * 12756.33 * acos(ratio)
}

/**
 * 由观察者位置与目标的地平坐标（方位/仰角）反推目标在地球上的星下点。
 *
 * 用于把 [CelestialComputer] 给出的太阳/月亮方位角与仰角映射为地图上的
 * 星下点标记。仰角为负（地平线以下）时返回地球另一侧的投影点，公式依然成立。
 *
 * @param observerLatDeg 观察者纬度（度）
 * @param observerLonDeg 观察者经度（度）
 * @param azimuthDeg 目标方位角（度，0=正北，顺时针）
 * @param elevationDeg 目标仰角（度）
 * @return (星下点纬度, 星下点经度)，经度归一化到 [-180, 180]
 */
fun subpointFromAzElevation(
    observerLatDeg: Double,
    observerLonDeg: Double,
    azimuthDeg: Double,
    elevationDeg: Double,
): Pair<Double, Double> {
    val phi = Math.toRadians(observerLatDeg)
    val lambda = Math.toRadians(observerLonDeg)
    val azi = Math.toRadians(normalizeAzimuthDeg(azimuthDeg))
    // 天顶角：仰角 0° 对应天顶角 90°，仰角 -90° 对应 180°
    val zenith = Math.toRadians(90.0 - elevationDeg)

    val sinZenith = sin(zenith)
    val cosZenith = cos(zenith)
    val sinPhi = sin(phi)
    val cosPhi = cos(phi)

    val targetLat = asin(sinPhi * cosZenith + cosPhi * sinZenith * cos(azi)).coerceIn(-PI / 2, PI / 2)
    val deltaLon = atan2(sin(azi) * sinZenith * cosPhi, cosZenith - sinPhi * sin(targetLat))
    val targetLon = lambda + deltaLon

    return Math.toDegrees(targetLat) to normalizeLongitude180(Math.toDegrees(normalizeRadians(targetLon)))
}

private fun normalizeRadians(radians: Double): Double {
    var value = radians % (2.0 * PI)
    if (value > PI) value -= 2.0 * PI
    if (value < -PI) value += 2.0 * PI
    return value
}

/** 地球平均半径（km），用于覆盖圆计算 */
private const val EARTH_RADIUS_KM = 6378.137

/**
 * 协程安全的 [runCatching]：捕获异常时重新抛出 [CancellationException]，
 * 避免破坏 Kotlin 协程的结构化并发语义。
 */
private inline fun <R> runCatchingCancellable(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
