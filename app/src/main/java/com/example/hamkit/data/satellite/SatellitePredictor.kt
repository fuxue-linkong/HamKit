package com.example.hamkit.data.satellite

import com.example.hamkit.data.satellite.pass.PassFinder
import com.example.hamkit.data.satellite.predict.SatellitePropagator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 是否为 GEO / 深空轨道（周期 > 1200 分钟）。
 * 周期 = 1440 / meanmo（meanmo 单位：转/天）。
 *
 * 判定逻辑已下沉到跨平台内核 [PassFinder]，这里保留一个薄封装，
 * 既有的调用点与单元测试无需改动。
 */
internal fun isGeoPeriod(meanMotion: Double): Boolean = PassFinder.isGeoPeriod(meanMotion)

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
         * GEO / 深空恒定可见卫星的"可视窗口"（毫秒）：AOS=now，LOS=now+24h。
         * 对同步轨道卫星这是合理的展示约定（其星下点长期固定，24h 内始终可见）。
         */
        private const val GEO_VISIBLE_WINDOW_MS = 24L * 60L * 60L * 1000L

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
                activeRadioModes = radiosByCatalogNumber[tle.catnum].orEmpty()
                    .filter { it.isActive }
                    .map { it.mode },
            )

            val meanMotion = tle.meanmo

            // 几何可达性预检（参照 Look4Sat willBeSeen）：
            // 远地点 + 轨道倾角无法覆盖观测者纬度 → 本站永不可见，直接跳过。
            // 此检查也过滤 meanmo ≈ 0 的已衰变 / 无效 TLE。
            if (!isGeoPeriod(meanMotion) &&
                !PassFinder.willBeSeenAt(meanMotion, tle.eccentricity, tle.inclinationDeg, latitude)
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
            val maxDurationMs = PassFinder.maxPassDurationMs(meanMotion)
            // 在境时向前回溯真实 AOS，避免所有在境卫星共享同一个预测时刻。
            val pass = PassFinder.nextPass(
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
                maxElevation = pass.maxElevationDeg,
                aosAzimuth = pass.aosAzimuthDeg,
                losAzimuth = pass.losAzimuthDeg,
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
}
