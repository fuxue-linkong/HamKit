package com.example.hamkit.data.satellite

import com.example.hamkit.data.satellite.predict.SatellitePropagator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln

/**
 * 是否为 GEO / 深空轨道（周期 > 1200 分钟）。
 * 周期 = 1440 / meanmo（meanmo 单位：转/天）。
 * 顶层函数便于单元测试与复用。
 */
internal fun isGeoPeriod(meanMotion: Double): Boolean =
    meanMotion > 0.0 && 1440.0 / meanMotion > 1200.0

/**
 * 地球半径（km），与 Look4Sat / PREDICT 对齐。
 */
private const val EARTH_RADIUS_KM = 6378.137

/**
 * 判断卫星是否可能从地面站纬度可见（几何可达性预检）。
 *
 * 基于 Look4Sat 的 [OrbitalObject.willBeSeen] 算法：
 * 计算卫星远地点并检查其轨道是否能够覆盖观测者纬度。
 * meanmo ≈ 0 视为已衰变 / 无效 TLE，直接返回 false。
 */
internal fun willBeSeenAt(meanMotion: Double, eccentricity: Double, inclinationDeg: Double, observerLatDeg: Double): Boolean {
    if (meanMotion < 1e-8) return false
    val sma = 331.25 * exp(ln(1440.0 / meanMotion) * (2.0 / 3.0))
    val apogee = sma * (1.0 + eccentricity) - EARTH_RADIUS_KM
    var lin = inclinationDeg
    if (lin >= 90.0) lin = 180.0 - lin
    return acos(EARTH_RADIUS_KM / (apogee + EARTH_RADIUS_KM)) + Math.toRadians(lin) > abs(Math.toRadians(observerLatDeg))
}

/**
 * 基于自研 SGP4/SDP4 引擎（[SatellitePropagator]）计算卫星过境信息。
 *
 * 性能优化要点：
 * 1. 不再调用库内 nextSatPass（内部默认搜到 7 天上限），
 *    改用 [SatellitePropagator.getPosition] 按步长采样仰角，搜索范围严格限制在 [hoursAhead] 内。
 * 2. GEO / 高轨卫星（周期 > 1200min）特判：当前仰角 > 0 视为恒定可见卫星
 *    （AOS=now，LOS=now+24h，isGeo=true），仰角 ≤ 0 直接跳过，
 *    避免对永不可见过境的卫星做无效扫描，同时不再漏掉可见的 GEO（如 QO-100）。
 * 3. 全量并发派发（不再分批串行 await），让 Default 线程池自行调度。
 * 4. Phase 4 新增：填充 [SatelliteInfo.isDaylightPass]（AOS 时刻地面站是否白天，
 *    由 [CelestialComputer.isDaylightAt] 计算，失败时回退 false）。
 */
class SatellitePredictor {

    /**
     * 计算指定地面站位置未来一段时间内的卫星过境信息。
     * 过境预测为 CPU 密集型计算，默认在 Default 调度器上并行执行。
     *
     * @param sourcedTles 带来源标记的卫星 TLE 列表
     * @param latitude 地面站纬度（度）
     * @param longitude 地面站经度（度）
     * @param altitude 地面站海拔（米）
     * @param hoursAhead 预测未来小时数
     * @param now 预测基准时刻，默认当前时间；测试可注入固定时刻
     * @param radiosByCatalogNumber 转发器库（NORAD → 转发器），用于聚合工作模式；
     *   为空时仅使用硬编码目录模式
     */
    suspend fun predictUpcomingPasses(
        sourcedTles: List<SourcedTLE>,
        latitude: Double,
        longitude: Double,
        altitude: Double = 0.0,
        hoursAhead: Int = 48,
        now: Instant = Instant.now(),
        radiosByCatalogNumber: Map<Int, List<RadioInfo>> = emptyMap(),
    ): List<SatelliteInfo> = withContext(Dispatchers.Default) {
        if (sourcedTles.isEmpty()) return@withContext emptyList()

        val nowMs = now.toEpochMilli()
        val searchEndMs = nowMs + hoursAhead * 60L * 60L * 1000L

        // 全量并发：每颗卫星的 SGP4/SDP4 计算独立，一次性派发让 Default 线程池自行调度。
        // 相比分批 awaitAll，避免批间串行等待，在多核设备上并行度更高。
        val passes = coroutineScope {
            sourcedTles
                .map { sourcedTle ->
                    async(Dispatchers.Default) {
                        predictSinglePass(
                            sourcedTle = sourcedTle,
                            latitude = latitude,
                            longitude = longitude,
                            altitude = altitude,
                            nowMs = nowMs,
                            searchEndMs = searchEndMs,
                            radiosByCatalogNumber = radiosByCatalogNumber,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

        // 当前在境内的优先显示，其次按 AOS 时间排序
        passes
            .sortedWith(
                compareByDescending<SatelliteInfo> { it.isCurrentlyVisible }
                    .thenBy { it.aosTime }
            )
    }

    private companion object {
        /**
         * 仰角采样步长（分钟）。用于粗扫描过境窗口。
         * LEO 卫星过境持续 10-20 分钟，5 分钟步长足以捕捉所有过境的 AOS/LOS 大致位置，
         * 随后用二分法精化到秒级。步长过大可能漏掉极短过境，过小则增加采样次数。
         */
        private const val SAMPLE_STEP_MINUTES = 5L

        /**
         * GEO / 高轨卫星周期阈值（分钟）。
         * 周期 > 此值的卫星（近 GEO，~1436 分钟）要么对地面站恒定可见、要么永不可见，
         * 不做逐分钟扫描，改用 [GEO 特判]（见 [predictSinglePass]）。
         */
        private const val GEO_PERIOD_THRESHOLD_MINUTES = 1200.0

        /**
         * GEO / 深空恒定可见卫星的"可视窗口"（毫秒）：AOS=now，LOS=now+24h。
         * 对同步轨道卫星这是合理的展示约定（其星下点长期固定，24h 内始终可见）。
         */
        private const val GEO_VISIBLE_WINDOW_MS = 24L * 60L * 60L * 1000L

        /**
         * 二分法精化 AOS/LOS 的时间精度（毫秒）。10 秒精度足以满足 UI 显示与提醒调度。
         */
        private const val BISECTION_PRECISION_MS = 10_000L

        /**
         * 当 SGP4 传播器因数值不稳定返回恒定仰角时，扫描循环可能扫到 searchEndMs
         * 仍未找到 AOS/LOS，造成"在境 48 小时"等荒谬结果。以该卫星轨道周期的 1.5 倍
         * 作为单次过境时长的合理上限（参照 Look4Sat 基于轨道周期的区间划分）。
         */
        private fun maxPassDurationMs(meanMotion: Double): Long {
            if (meanMotion <= 0.0) return 3L * 60L * 60L * 1000L
            val periodMin = 1440.0 / meanMotion
            return ((periodMin * 1.5) * 60L * 1000L).toLong()
        }

        /**
         * 在境卫星回溯真实 AOS 的上限（6 小时）。
         *
         * 非 GEO 的常见在境过境远短于 6 小时；限制上限避免高轨异常 TLE 触发
         * 过长的回溯扫描。超过上限仍找不到 AOS 时回退为预测基准时刻。
         */
        private const val MAX_IN_PASS_LOOKBACK_MS = 6L * 60L * 60L * 1000L
    }

    private fun predictSinglePass(
        sourcedTle: SourcedTLE,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        nowMs: Long,
        searchEndMs: Long,
        radiosByCatalogNumber: Map<Int, List<RadioInfo>>,
    ): SatelliteInfo? {
        return try {
            val tle = sourcedTle.tle
            // 工作模式 = 活跃转发器模式 ∪ 硬编码目录模式。仅靠硬编码目录会让
            // 目录外卫星（如只有 SatNOGS 转发器的 OUTPOST MISSION2）模式为空。
            val modes = SatelliteCatalog.effectiveModes(
                catalogNumber = tle.catnum,
                radios = radiosByCatalogNumber[tle.catnum].orEmpty(),
            )

            val meanMotion = tle.meanmo

            // 几何可达性预检（参照 Look4Sat willBeSeen）：
            // 远地点 + 轨道倾角无法覆盖观测者纬度 → 本站永不可见，直接跳过。
            // 此检查也过滤 meanmo ≈ 0 的已衰变 / 无效 TLE。
            if (!isGeoPeriod(meanMotion) &&
                !willBeSeenAt(meanMotion, tle.eccentricity, tle.inclinationDeg, latitude)
            ) return null

            val propagator = SatellitePropagator(tle, latitude, longitude, altitude)
            val nowMs2 = nowMs

            val currentPos = propagator.getPosition(nowMs2)
            val isCurrentlyVisible = currentPos != null && currentPos.elevationRad > 0

            if (isGeoPeriod(meanMotion)) {
                if (currentPos == null || currentPos.elevationRad <= 0) return null
                val nowInstant = Instant.ofEpochMilli(nowMs2)
                val elevationDeg = Math.toDegrees(currentPos.elevationRad)
                val azimuthDeg = Math.toDegrees(currentPos.azimuthRad).toInt()
                val isDaylight = try {
                    CelestialComputer.isDaylightAt(latitude, longitude, nowMs2)
                } catch (_: Exception) { false }
                return SatelliteInfo(
                    name = tle.name.trim().ifEmpty { tle.catnum.toString() },
                    catalogNumber = tle.catnum,
                    modes = modes,
                    aosTime = nowInstant,
                    losTime = Instant.ofEpochMilli(nowMs2 + GEO_VISIBLE_WINDOW_MS),
                    maxElevation = elevationDeg,
                    aosAzimuth = azimuthDeg,
                    losAzimuth = azimuthDeg,
                    isCurrentlyVisible = true,
                    source = sourcedTle.source,
                    status = sourcedTle.status,
                    isGeo = true,
                    isDaylightPass = isDaylight
                )
            }

            // 动态过境时长校验：基于卫星轨道周期，而非固定 3 小时一刀切。
            // SGP4 传播器在 TLE 过期 / 近再入时可能产生假性恒定可见的仰角，
            // 导致 AOS-LOS 跨整个搜索窗口。以 1.5 × 轨道周期为上限滤除。
            val maxDurationMs = maxPassDurationMs(meanMotion)
            // 在境时向前回溯真实 AOS，避免所有在境卫星共享同一个预测时刻。
            val pass = findNextPass(
                propagator = propagator,
                fromMs = nowMs2,
                toMs = searchEndMs,
                lookbackMs = maxDurationMs.coerceAtMost(MAX_IN_PASS_LOOKBACK_MS),
            ) ?: return null
            val aosMs = pass.aosMs
            val losMs = pass.losMs
            if (aosMs >= searchEndMs) return null

            if (losMs - aosMs > maxDurationMs) return null

            // 白天过境标记：取 AOS 时刻地面站太阳仰角 > -0.833°。
            // 相比 TCA（最大仰角）时刻，AOS 更贴近用户"过境开始时间"的直觉；
            // 计算失败时回退 false，不中断预测。
            val isDaylightPass = try {
                CelestialComputer.isDaylightAt(
                    latitude,
                    longitude,
                    aosMs
                )
            } catch (_: Exception) {
                false
            }

            SatelliteInfo(
                name = tle.name.trim().ifEmpty { tle.catnum.toString() },
                catalogNumber = tle.catnum,
                modes = modes,
                aosTime = Instant.ofEpochMilli(aosMs),
                losTime = Instant.ofEpochMilli(losMs),
                maxElevation = pass.maxElevation,
                aosAzimuth = pass.aosAzimuth,
                losAzimuth = pass.losAzimuth,
                isCurrentlyVisible = isCurrentlyVisible,
                source = sourcedTle.source,
                status = sourcedTle.status,
                isDaylightPass = isDaylightPass
            )
        } catch (_: IllegalArgumentException) {
            // TLE 或地面站参数异常，跳过
            null
        } catch (e: CancellationException) {
            // 协程取消：必须向上传播，不能静默吞掉
            throw e
        } catch (_: Exception) {
            // 轨道计算内部异常（数值溢出等），跳过单颗卫星，避免整体崩溃
            null
        }
    }

    /**
     * 按固定步长采样仰角，寻找 [fromMs, toMs] 内的下次过境（仰角从 ≤0 穿越到 >0）。
     * 命中后用二分法精化 AOS/LOS 到秒级，并取过境窗口内的最大仰角。
     *
     * 当前已在境内时，向前回溯本次过境的真实 AOS，再向后找 LOS：
     * 早期实现直接把 AOS 设为 [fromMs]，导致同一批在境卫星的 AOS 全部等于
     * 预测基准时刻（表现为“所有卫星 next_aos 相同且已过期”）。
     *
     * @param lookbackMs 在境时向前回溯 AOS 的最大时长；回溯失败时回退到 [fromMs]
     */
    private fun findNextPass(
        propagator: SatellitePropagator,
        fromMs: Long,
        toMs: Long,
        lookbackMs: Long,
    ): PassResult? {
        val stepMs = SAMPLE_STEP_MINUTES * 60L * 1000L
        val prevEl = elevationAt(propagator, fromMs)

        // 当前已在境内：回溯真实 AOS，记录本次剩余 LOS。
        if (prevEl > 0) {
            val realAosMs = findAosBefore(propagator, fromMs, lookbackMs)
            val effectiveAosMs = realAosMs ?: fromMs
            val losResult = findLosAfter(propagator, fromMs, toMs, stepMs)
                ?: return null // 整个窗口内都在境内（异常情况），跳过
            val losMs = losResult.first
            if (losMs - effectiveAosMs > lookbackMs) return null
            // 当前在境时 TCA 可能已经过去，必须在完整 [AOS, LOS] 窗口内重新找峰值。
            val maxElInfo = scanMaxElevation(propagator, effectiveAosMs, losMs)
            return PassResult(
                aosMs = effectiveAosMs,
                losMs = losMs,
                maxElevation = maxElInfo.first,
                aosAzimuth = azimuthAt(propagator, effectiveAosMs),
                losAzimuth = azimuthAt(propagator, losMs),
            )
        }

        // 正常搜索：仰角从 ≤0 穿越到 >0 = AOS
        var t = fromMs + stepMs
        var previousEl = prevEl
        while (t <= toMs) {
            val el = elevationAt(propagator, t)
            if (previousEl <= 0 && el > 0) {
                // 命中 AOS：精化 AOS，然后向前找 LOS
                val aosMs = refineZeroCrossing(propagator, t - stepMs, t, crossingUp = true)
                val losResult = findLosAfter(propagator, aosMs, toMs, stepMs)
                if (losResult == null) return null // 窗口结束前未出境
                val (losMs, maxEl, maxElMs) = losResult
                val maxElValue = if (maxElMs == aosMs) {
                    maxEl
                } else {
                    maxElevationAzimuthAt(propagator, maxElMs, aosMs, losMs).first
                }
                return PassResult(
                    aosMs = aosMs,
                    losMs = losMs,
                    maxElevation = maxElValue,
                    aosAzimuth = azimuthAt(propagator, aosMs),
                    losAzimuth = azimuthAt(propagator, losMs)
                )
            }
            previousEl = el
            t += stepMs
        }
        return null
    }

    /**
     * 从 [fromMs]（该时刻仰角 > 0）向前回溯，找到本次过境的真实 AOS。
     *
     * 以 [SAMPLE_STEP_MINUTES] 步长回溯，找到仰角 ≤0 的采样点后用二分法精化。
     * 超过 [maxLookbackMs] 仍未找到（长时间在境或异常 TLE）时返回 null，
     * 由调用方回退为 [fromMs]。
     */
    private fun findAosBefore(
        propagator: SatellitePropagator,
        fromMs: Long,
        maxLookbackMs: Long,
    ): Long? {
        val stepMs = SAMPLE_STEP_MINUTES * 60L * 1000L
        var hiMs = fromMs
        var t = fromMs - stepMs
        var elapsed = stepMs
        while (elapsed <= maxLookbackMs) {
            val el = elevationAt(propagator, t)
            if (el <= 0) {
                // t 处 ≤0、hiMs 处 >0：二分精化上升过零点
                return refineZeroCrossing(propagator, t, hiMs, crossingUp = true)
            }
            hiMs = t
            t -= stepMs
            elapsed += stepMs
        }
        return null
    }

    /**
     * 在完整过境窗口 [aosMs, losMs] 内以 1 分钟步长扫描最大仰角及其方位角。
     *
     * 与 [maxElevationAzimuthAt] 不同，这里不假设峰值在某个采样点附近，
     * 适用于“当前在境、TCA 可能已过去”的场景。
     */
    private fun scanMaxElevation(
        propagator: SatellitePropagator,
        aosMs: Long,
        losMs: Long,
    ): Pair<Double, Int> {
        val stepMs = 60L * 1000L
        var bestEl = Double.NEGATIVE_INFINITY
        var bestMs = aosMs
        var t = aosMs
        while (t <= losMs) {
            val el = elevationAt(propagator, t)
            if (el > bestEl) {
                bestEl = el
                bestMs = t
            }
            t += stepMs
        }
        val pos = propagator.getPosition(bestMs)
        return bestEl to (pos?.let { Math.toDegrees(it.azimuthRad).toInt() } ?: 0)
    }

    /**
     * 在 [aosMs] 之后寻找 LOS（仰角从 >0 穿越到 ≤0），同时记录窗口内最大仰角。
     */
    private fun findLosAfter(
        propagator: SatellitePropagator,
        aosMs: Long,
        toMs: Long,
        stepMs: Long
    ): Triple<Long, Double, Long>? {
        var t = aosMs + stepMs
        var prevEl = elevationAt(propagator, t - stepMs)
        var maxEl = prevEl
        var maxElMs = aosMs
        while (t <= toMs) {
            val el = elevationAt(propagator, t)
            if (el > maxEl) {
                maxEl = el
                maxElMs = t
            }
            if (prevEl > 0 && el <= 0) {
                val losMs = refineZeroCrossing(propagator, t - stepMs, t, crossingUp = false)
                return Triple(losMs, maxEl, maxElMs)
            }
            prevEl = el
            t += stepMs
        }
        return null
    }

    /**
     * 二分法精化仰角过零点（AOS 为上升过零，LOS 为下降过零）到 [BISECTION_PRECISION_MS] 精度。
     */
    private fun refineZeroCrossing(
        propagator: SatellitePropagator,
        loMs: Long,
        hiMs: Long,
        crossingUp: Boolean
    ): Long {
        var lo = loMs
        var hi = hiMs
        while (hi - lo > BISECTION_PRECISION_MS) {
            val mid = (lo + hi) / 2
            val el = elevationAt(propagator, mid)
            if (crossingUp) {
                // AOS: lo 处 ≤0，hi 处 >0，mid >0 时收缩 hi
                if (el > 0) hi = mid else lo = mid
            } else {
                // LOS: lo 处 >0，hi 处 ≤0，mid ≤0 时收缩 hi
                if (el <= 0) hi = mid else lo = mid
            }
        }
        return if (crossingUp) hi else lo
    }

    /**
     * 在 [aosMs, losMs] 窗口内以更细步长搜索最大仰角及其方位角。
     * 粗扫描已给出大致 maxElMs，这里在其邻域精化。
     */
    private fun maxElevationAzimuthAt(
        propagator: SatellitePropagator,
        maxElMs: Long,
        aosMs: Long,
        losMs: Long
    ): Pair<Double, Int> {
        // 修复 P3-2：粗扫描步长 5 分钟，峰值可能落在命中点两侧各接近一个完整步长
        // （非对称过境），原 ±5 分钟窗口边界情况会漏掉真实峰值。扩为 ±2 个步长
        // （±10 分钟），保证窗口覆盖命中点相邻的完整采样间隔，再以 1 分钟细步长扫描。
        val fineStepMs = 60L * 1000L
        val searchStart = maxOf(maxElMs - 2L * SAMPLE_STEP_MINUTES * 60L * 1000L, aosMs)
        val searchEnd = minOf(maxElMs + 2L * SAMPLE_STEP_MINUTES * 60L * 1000L, losMs)
        var bestEl = elevationAt(propagator, maxElMs)
        var bestMs = maxElMs
        var t = searchStart
        while (t <= searchEnd) {
            val el = elevationAt(propagator, t)
            if (el > bestEl) {
                bestEl = el
                bestMs = t
            }
            t += fineStepMs
        }
        val pos = propagator.getPosition(bestMs)
        return bestEl to (pos?.let { Math.toDegrees(it.azimuthRad).toInt() } ?: 0)
    }

    private fun elevationAt(propagator: SatellitePropagator, timeMs: Long): Double {
        val pos = propagator.getPosition(timeMs) ?: return -1.0
        return Math.toDegrees(pos.elevationRad)
    }

    private fun azimuthAt(propagator: SatellitePropagator, timeMs: Long): Int {
        val pos = propagator.getPosition(timeMs) ?: return 0
        return Math.toDegrees(pos.azimuthRad).toInt()
    }

    private data class PassResult(
        val aosMs: Long,
        val losMs: Long,
        val maxElevation: Double,
        val aosAzimuth: Int,
        val losAzimuth: Int
    )
}
