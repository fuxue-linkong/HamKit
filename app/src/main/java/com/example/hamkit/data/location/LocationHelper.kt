package com.example.hamkit.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.util.UUID
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 纯 Android Framework [LocationManager] 定位封装。
 *
 * 不依赖 Google Play Services（GMS），避免无 GMS 设备触发"请启用谷歌服务"弹窗。
 * NETWORK_PROVIDER 优先（室内 Wi-Fi/基站毫秒级），GPS 兜底（室外高精度）。
 */
class LocationHelper(private val context: Context) {

    private val amapGeocodeService = AmapGeocodeService()

    companion object {
        private const val TAG = "LocationHelper"

        /** 最近一次定位请求的链路追踪 ID（线程安全，供调用方关联日志） */
        @Volatile
        private var lastTraceId: String? = null

        /** 最近一次定位请求的结构化性能指标（线程安全，供外层主动查询记录） */
        @Volatile
        private var lastMetric: LocationMetric? = null

        /** 最近一次已输出"定位结果"分类日志的 traceId（避免外层 catch 重复分类统计） */
        @Volatile
        private var lastClassifiedTraceId: String? = null

        /** 供调用方查询最近一次定位请求的 traceId，用于日志链路关联 */
        fun getLastTraceId(): String? = lastTraceId

        /**
         * 供调用方查询最近一次定位请求的结构化性能指标。
         * 在定位达到终态（成功/超时兜底/失败）后构建；无历史请求或请求尚未到终态时为 null。
         * 外层可在定位完成后主动读取并交给 [LocationMetricRecorder] 记录。
         */
        fun getLastMetric(): LocationMetric? = lastMetric

        /** 单次定位精度门槛：精度优于此值立即返回，否则继续等更优结果 */
        private const val ACCURACY_THRESHOLD_METERS = 20f
        /** lastKnown 缓存最大可接受精度：超过此值的缓存视为不可用 */
        private const val CACHE_MAX_ACCURACY_METERS = 50f
        /** lastKnown 缓存最大时效：超过 10 分钟的缓存视为过期，不可作为秒回结果 */
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
        /** 持续定位去重时间窗：此窗口内只保留精度最优的一次上报 */
        private const val DEDUP_WINDOW_MS = 1500L

        // ---------- 定位慢场景检测阈值 ----------
        /** 缓存秒回：缓存路径总耗时超过此值视为慢 */
        private const val SLOW_THRESHOLD_CACHE_MS = 50L
        /** 网络定位：NETWORK provider 最终返回超过此值视为慢 */
        private const val SLOW_THRESHOLD_NETWORK_MS = 500L
        /** GPS 定位：GPS provider 最终返回超过此值视为慢 */
        private const val SLOW_THRESHOLD_GPS_MS = 3000L
        /** 任何定位：总耗时超过此值一律视为慢 */
        private const val SLOW_THRESHOLD_TOTAL_MS = 5000L
        /** 缓存查询单独阈值：getLastKnownLocation 批量查询超过此值视为慢 */
        private const val SLOW_THRESHOLD_CACHE_QUERY_MS = 30L
        /** Provider 注册阈值：requestLocationUpdates 调用超过此值视为慢 */
        private const val SLOW_THRESHOLD_PROVIDER_REGISTER_MS = 50L
        /** Provider 首次响应阈值：GPS 冷启动判定参考值 */
        private const val SLOW_THRESHOLD_PROVIDER_RESPONSE_MS = 2000L

        /** 标记应用启动后是否曾获取过 GPS 结果（用于识别 GPS 冷启动） */
        @Volatile
        private var isGpsFirstResponse = false

        // ---------- 冷/热启动与场景转移追踪 ----------
        /** 上一次定位结果使用的 provider（用于识别定位场景转移，如室内→室外） */
        @Volatile
        private var lastLocationProvider: String? = null
        /** 应用启动后 GPS 返回结果的总次数（用于冷/热启动判定） */
        private var gpsResponseCount: Long = 0
        /** GPS 暖机阈值：连续成功回调次数达到此值视为 GPS 已暖机 */
        private const val GPS_WARM_UP_COUNT = 3L

        /** 将 provider 映射为场景标签 */
        private fun convertProviderToScenario(provider: String?): String {
            return when (provider) {
                LocationManager.NETWORK_PROVIDER -> "INDOOR(network)"
                LocationManager.GPS_PROVIDER -> "OUTDOOR(gps)"
                LocationManager.PASSIVE_PROVIDER -> "PASSIVE(passive)"
                else -> "UNKNOWN(${provider ?: "none"})"
            }
        }
    }

    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /**
     * 定位场景识别：将本次定位的 provider 映射为场景标签，并与上次对比。
     *
     * 返回值规则：
     * - provider 与上次不同："from=<上次场景> to=<本次场景>"（场景转移）
     * - provider 相同或首次定位："<本次场景>"（场景保持）
     * 调用后内部记录本次 provider，供下一次对比。
     */
    private fun identifyLocationScenario(location: Location): String {
        val lastProvider = lastLocationProvider
        lastLocationProvider = location.provider

        return if (lastProvider != null && lastProvider != location.provider) {
            "from=${convertProviderToScenario(lastProvider)} to=${convertProviderToScenario(location.provider)}"
        } else {
            convertProviderToScenario(location.provider)
        }
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getCurrentLocation(): Location {
        if (!hasPermission()) {
            // 分类统计：此处尚未进入定位链路，单独生成 traceId 以便日志关联
            val traceId = UUID.randomUUID().toString().substring(0, 8)
            lastTraceId = traceId
            Log.i(TAG, "[链路:$traceId] 定位结果 result=FAIL reason=NO_PERMISSION error=缺少定位权限")
            throw SecurityException("缺少定位权限")
        }

        return try {
            withTimeout(10_000) { requestLocationManagerLocation() }
        } catch (e: TimeoutCancellationException) {
            // 分类统计：10s 整体超时（内部 9s 兜底 Runnable 因线程调度未及时触发等场景）
            val traceId = lastTraceId
            if (traceId != null && traceId != lastClassifiedTraceId) {
                lastClassifiedTraceId = traceId
                Log.i(TAG, "[链路:$traceId] 定位结果 result=TIMEOUT reason=NO_RESPONSE_10S fallback=NONE totalMs=${10_000}")
            }
            throw Exception("定位超时，请检查手机是否开启 GPS/网络定位")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 分类统计：内部未覆盖的异常归为 UNKNOWN_ERROR（已分类的链路不重复统计）
            val traceId = lastTraceId
            if (traceId != null && traceId != lastClassifiedTraceId) {
                lastClassifiedTraceId = traceId
                Log.i(TAG, "[链路:$traceId] 定位结果 result=FAIL reason=UNKNOWN_ERROR error=${e.message ?: "未知异常"}")
            }
            throw Exception(
                "无法获取定位，请检查手机是否开启 GPS/网络定位，或检查是否禁止了本应用定位权限。详情: ${e.message ?: "失败"}"
            )
        }
    }

    /**
     * 持续位置监听（Flow 形式）。
     *
     * 基于 [LocationManager.requestLocationUpdates] 注册持续回调，
     * NETWORK + GPS 双 provider 并行监听，取每次变化的位置上报。
     * Flow 被取消时自动移除回调，避免泄漏。
     *
     * @param intervalMs 期望的位置上报间隔（毫秒）
     * @param minDistanceM 最小位移阈值（米），小于此距离的变化不上报
     */
    fun locationUpdates(
        intervalMs: Long = 5_000L,
        minDistanceM: Float = 5f
    ): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            close(SecurityException("缺少定位权限"))
            return@callbackFlow
        }

        // 去重状态：时间窗内暂存最优结果，窗口结束 emit 精度最高的那个
        var pendingBest: Location? = null
        var pendingEmitTime = 0L
        val handler = android.os.Handler(Looper.getMainLooper())
        val emitRunnable = Runnable {
            pendingBest?.let { best ->
                pendingBest = null
                trySend(best)
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val now = android.os.SystemClock.elapsedRealtime()
                val current = pendingBest
                if (current == null) {
                    // 窗口起点：暂存并安排窗口结束 emit
                    pendingBest = location
                    pendingEmitTime = now + DEDUP_WINDOW_MS
                    handler.postDelayed(emitRunnable, DEDUP_WINDOW_MS)
                } else {
                    // 窗口内：保留精度更优的
                    if (location.accuracy < current.accuracy) {
                        pendingBest = location
                    }
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        // NETWORK + GPS 并行监听：室内 NETWORK 先出结果，室外 GPS 高精度
        val providers = listOfNotNull(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).filter { provider ->
            try {
                locationManager.isProviderEnabled(provider)
            } catch (e: Exception) {
                false
            }
        }

        if (providers.isEmpty()) {
            close(Exception("系统未开启任何定位源"))
            return@callbackFlow
        }

        var registeredProvider = false
        try {
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        intervalMs,
                        minDistanceM,
                        listener,
                        Looper.getMainLooper()
                    )
                    registeredProvider = true
                } catch (e: SecurityException) {
                    // 忽略单个 provider 的权限异常，继续下一个
                }
            }
            if (!registeredProvider) {
                close(Exception("无法注册定位监听器"))
                return@callbackFlow
            }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            handler.removeCallbacks(emitRunnable)
            try {
                locationManager.removeUpdates(listener)
            } catch (_: Exception) {
                // 忽略移除回调时的异常
            }
        }
    }

    private suspend fun requestLocationManagerLocation(): Location =
        suspendCancellableCoroutine { continuation ->
            // 协程体与 listener 回调可能在不同线程执行，用 AtomicBoolean 防二次 resume
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            // ---------- 链路追踪 ID ----------
            val traceId = UUID.randomUUID().toString().substring(0, 8)
            lastTraceId = traceId
            fun log(level: Int, event: String, detail: String = "") {
                val msg = "[链路:$traceId] $event" + if (detail.isEmpty()) "" else " $detail"
                if (level == Log.WARN) Log.w(TAG, msg) else Log.i(TAG, msg)
            }
            log(Log.INFO, "定位发起")
            // ---------- 性能日志状态 ----------
            val requestStartMs = System.currentTimeMillis()  // 请求总起点
            var cacheCheckCostMs = 0L                        // 缓存查询耗时
            val providerRegisterStartMs = mutableMapOf<String, Long>()  // 各 provider 注册起点
            val providerWaitStartMs = mutableMapOf<String, Long>()      // 注册完成 -> 等待回调起点
            val providerFirstResultMs = mutableMapOf<String, Long?>()   // 各 provider 首次回调耗时
            var finalProvider: String = "无"
            var finalAccuracy: Float = -1f
            var logged = java.util.concurrent.atomic.AtomicBoolean(false)

            /** 输出完整耗时分解日志（仅记录一次） */
            fun logBreakdown() {
                if (!logged.compareAndSet(false, true)) return
                val totalMs = System.currentTimeMillis() - requestStartMs
                fun fmt(name: String): String {
                    val v = providerFirstResultMs[name]
                    return if (v == null) "无响应" else "${v}ms"
                }
                val accStr = if (finalAccuracy >= 0) {
                    String.format(java.util.Locale.US, "%.1f", finalAccuracy) + "m"
                } else "未知"
                Log.i(
                    TAG,
                    "[链路:$traceId] [定位耗时分解] 缓存检查:${cacheCheckCostMs}ms | " +
                        "NETWORK等待:${fmt("network")} | " +
                        "PASSIVE等待:${fmt("passive")} | " +
                        "GPS等待:${fmt("gps")} | " +
                        "精度:${accStr} | 提供者:${finalProvider} | 总耗时:${totalMs}ms"
                )
            }

            /** provider 字符串归一化为指标里的小写 key（network/passive/gps/...） */
            fun metricProviderKey(rawProvider: String?): String = when (rawProvider) {
                LocationManager.NETWORK_PROVIDER -> "network"
                LocationManager.PASSIVE_PROVIDER -> "passive"
                LocationManager.GPS_PROVIDER -> "gps"
                else -> rawProvider ?: "unknown"
            }

            /** 供各终态路径调用：构建结构化指标并写入 lastMetric（与 logBreakdown 同点触发） */
            fun buildMetric(
                result: String,
                resultReason: String,
                provider: String,
                accuracy: Float,
                slow: Boolean,
                slowReason: String?
            ) {
                val nowMs = System.currentTimeMillis()
                val firstResponseMs = mutableMapOf<String, Long>()
                providerFirstResultMs.forEach { (key, value) ->
                    if (value != null) firstResponseMs[key] = value
                }
                lastMetric = LocationMetric(
                    traceId = traceId,
                    result = result,
                    resultReason = resultReason,
                    durationMs = (nowMs - requestStartMs).coerceAtLeast(0L),
                    provider = provider,
                    accuracy = accuracy,
                    cacheCheckMs = cacheCheckCostMs,
                    providerRegisterMs = providerRegisterStartMs.toRegisterCostMap(nowMs),
                    providerFirstResponseMs = firstResponseMs,
                    isSlow = slow,
                    slowReason = slowReason,
                    timestamp = requestStartMs,
                    deviceInfo = deviceInfoString()
                )
            }
            // ----------------------------------

            /** 输出细粒度分类结果日志（与耗时分解日志互补，便于快速筛选高发问题） */
            fun logResult(result: String, reason: String, extra: String = "") {
                val suffix = if (extra.isEmpty()) "" else " $extra"
                Log.i(TAG, "[链路:$traceId] 定位结果 result=$result reason=$reason$suffix")
            }

            /** 输出分类结果日志并标记该链路已完成分类（防止外层 catch 重复统计） */
            fun logResultClassified(result: String, reason: String, extra: String = "") {
                lastClassifiedTraceId = traceId
                logResult(result, reason, extra)
            }

            /** 精度格式化：无精度信息时输出 unknown */
            fun formatAccuracy(location: Location): String =
                if (location.hasAccuracy()) {
                    String.format(java.util.Locale.US, "%.0f", location.accuracy) + "m"
                } else "unknown"

            // 暂存精度未达标的结果：超时前有更优结果则替换，超时则用此兜底
            var fallback: Location? = null
            /** 当前兜底是否来自请求开始时的 lastKnown 缓存（用于超时分类 fallback=LAST_CACHED） */
            var fallbackFromCache = false
            val handler = android.os.Handler(Looper.getMainLooper())
            // listener 在下面定义，这里用 lateinit 让 timeoutRunnable 可引用
            lateinit var listener: LocationListener
            val timeoutRunnable = Runnable {
                if (resumed.compareAndSet(false, true)) {
                    removeListener(listener)
                    val result = fallback
                    // ---------- 慢场景检测：超时兜底路径 ----------
                    val totalMs = System.currentTimeMillis() - requestStartMs
                    if (totalMs > SLOW_THRESHOLD_TOTAL_MS) {
                        Log.w(
                            TAG,
                            "[链路:$traceId] 定位慢 耗时:${totalMs}ms 阈值:${SLOW_THRESHOLD_TOTAL_MS}ms " +
                                "provider=timeout 原因:TOTAL_SLOW_TIMEOUT"
                        )
                    }
                    // ----------------------------------
                    // 兜底结果的提供者与精度也纳入日志
                    if (result != null) {
                        finalProvider = (result.provider ?: "unknown") + "(兜底)"
                        finalAccuracy = if (result.hasAccuracy()) result.accuracy else -1f
                    }
                    // 结构化指标：超时兜底终态（超时路径总耗时必超总阈值，一律标记慢）
                    buildMetric(
                        result = "TIMEOUT",
                        resultReason = "NO_RESPONSE_10S",
                        provider = "timeout",
                        accuracy = finalAccuracy,
                        slow = true,
                        slowReason = "TOTAL_SLOW_TIMEOUT"
                    )
                    // 分类统计：fallback 为请求前缓存（LAST_CACHED）或超时前最优回调结果（BEST_SUBSTANDARD）
                    val fallbackStr = when {
                        result == null -> "NONE"
                        fallbackFromCache -> "LAST_CACHED"
                        else -> "BEST_SUBSTANDARD"
                    }
                    logResultClassified(
                        "TIMEOUT", "NO_RESPONSE_10S",
                        "fallback=$fallbackStr totalMs=${System.currentTimeMillis() - requestStartMs}"
                    )
                    logBreakdown()  // 超时兜底路径：同样输出耗时分解
                    if (result != null) {
                        continuation.resume(result)
                    } else {
                        continuation.resumeWithException(Exception("定位超时未获取到结果"))
                    }
                }
            }

            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // ---------- 性能日志：每次回调记录 provider / 精度 / 相对请求起点耗时 ----------
                    val callbackCostMs = System.currentTimeMillis() - requestStartMs
                    val providerKey = when (location.provider) {
                        LocationManager.NETWORK_PROVIDER -> "network"
                        LocationManager.PASSIVE_PROVIDER -> "passive"
                        LocationManager.GPS_PROVIDER -> "gps"
                        else -> location.provider ?: "unknown"
                    }
                    // 记录该 provider 首次回调耗时（保留首值，后续覆盖无意义）
                    if (!providerFirstResultMs.containsKey(providerKey)) {
                        providerFirstResultMs[providerKey] = callbackCostMs
                    }
                    Log.d(
                        TAG,
                        "[链路:$traceId] [定位回调] provider:${location.provider} 精度:${location.accuracy}m " +
                            "相对请求开始耗时:${callbackCostMs}ms"
                    )

                    // ---------- 冷/热启动判定：GPS 返回结果时更新计数 ----------
                    if (location.provider == LocationManager.GPS_PROVIDER) {
                        gpsResponseCount++

                        val isGpsWarmedUp = gpsResponseCount >= GPS_WARM_UP_COUNT
                        if (gpsResponseCount == 1L) {
                            Log.i(TAG, "[链路:$traceId] GPS初启动 isFirstGpsRequest=true")
                        } else if (isGpsWarmedUp) {
                            Log.d(TAG, "[链路:$traceId] GPS热启动 isFirstGpsRequest=false gpsCallCount=$gpsResponseCount")
                        }
                    }
                    // ----------------------------------------------

                    if (resumed.get()) return
                    // 精度达标或来自 GPS：立即返回（GPS accuracy 计算保守，即使超门槛也可信）
                    if (location.accuracy <= ACCURACY_THRESHOLD_METERS ||
                        location.provider == LocationManager.GPS_PROVIDER) {
                        if (resumed.compareAndSet(false, true)) {
                            // ---------- 慢场景检测：provider 返回路径 ----------
                            val totalMs = System.currentTimeMillis() - requestStartMs
                            val threshold = when {
                                location.provider == LocationManager.GPS_PROVIDER -> SLOW_THRESHOLD_GPS_MS
                                location.provider == LocationManager.NETWORK_PROVIDER -> SLOW_THRESHOLD_NETWORK_MS
                                location.provider == LocationManager.PASSIVE_PROVIDER -> SLOW_THRESHOLD_GPS_MS // 同GPS
                                else -> SLOW_THRESHOLD_TOTAL_MS
                            }
                            // 慢原因在慢日志与结构化指标间共用（未慢时为 null）
                            val slowReason = if (totalMs > threshold) {
                                when {
                                    location.provider == LocationManager.GPS_PROVIDER && !isGpsFirstResponse -> {
                                        isGpsFirstResponse = true
                                        "GPS_COLD_START"
                                    }
                                    location.provider == LocationManager.GPS_PROVIDER -> "GPS_RESPONSE_SLOW"
                                    location.provider == LocationManager.NETWORK_PROVIDER -> "NETWORK_PROVIDER_SLOW"
                                    location.provider == LocationManager.PASSIVE_PROVIDER -> "PASSIVE_RESPONSE_SLOW"
                                    else -> "TOTAL_SLOW"
                                }
                            } else null
                            if (slowReason != null) {
                                val providerKey = when (location.provider) {
                                    LocationManager.NETWORK_PROVIDER -> "network"
                                    LocationManager.PASSIVE_PROVIDER -> "passive"
                                    LocationManager.GPS_PROVIDER -> "gps"
                                    else -> location.provider ?: "unknown"
                                }
                                val firstResponseMs = providerFirstResultMs[providerKey] ?: totalMs
                                if (slowReason == "GPS_COLD_START") {
                                    // 冷启动引起的定位慢：单独标记，便于分析人员快速归因
                                    Log.w(
                                        TAG,
                                        "[链路:$traceId] 定位慢 耗时:${totalMs}ms 阈值:${threshold}ms " +
                                            "provider=$providerKey 原因:$slowReason 首次响应:${firstResponseMs}ms " +
                                            "[冷启动影响]"
                                    )
                                } else {
                                    Log.w(
                                        TAG,
                                        "[链路:$traceId] 定位慢 耗时:${totalMs}ms 阈值:${threshold}ms " +
                                            "provider=$providerKey 原因:$slowReason 首次响应:${firstResponseMs}ms"
                                    )
                                }
                            }
                            // GPS 首次成功返回后清除冷启动标记（无论快慢）
                            if (location.provider == LocationManager.GPS_PROVIDER) {
                                isGpsFirstResponse = true
                            }
                            // ----------------------------------
                            // 分类统计：按 provider 细分成功原因（GPS 直接受理，其余需精度达标）
                            val successReason = when (location.provider) {
                                LocationManager.GPS_PROVIDER -> "GPS"
                                LocationManager.NETWORK_PROVIDER -> "NETWORK"
                                LocationManager.PASSIVE_PROVIDER -> "PASSIVE"
                                else -> "OTHER"
                            }
                            removeListener(this)
                            handler.removeCallbacks(timeoutRunnable)
                            finalProvider = location.provider ?: "unknown"
                            finalAccuracy = if (location.hasAccuracy()) location.accuracy else -1f
                            // 结构化指标：provider 成功返回终态
                            buildMetric(
                                result = "SUCCESS",
                                resultReason = successReason,
                                provider = metricProviderKey(location.provider),
                                accuracy = finalAccuracy,
                                slow = slowReason != null,
                                slowReason = slowReason
                            )
                            logResultClassified(
                                "SUCCESS", successReason,
                                "provider=${location.provider ?: "unknown"} accuracy=" +
                                    formatAccuracy(location) +
                                    " totalMs=${System.currentTimeMillis() - requestStartMs}"
                            )
                            logBreakdown()  // 最终返回前：输出完整耗时分解
                            // ---------- 场景转移检测：在返回定位结果前 ----------
                            val scenario = identifyLocationScenario(location)
                            if (scenario.contains("from=")) {
                                Log.i(TAG, "[链路:$traceId] 场景转移 $scenario")
                            } else {
                                Log.d(TAG, "[链路:$traceId] 场景保持 $scenario")
                            }
                            // --------------------------------------------------
                            continuation.resume(location)
                        }
                    } else {
                        // 精度未达标：暂存为兜底，继续等更优结果
                        val cur = fallback
                        if (cur == null || location.accuracy < cur.accuracy) {
                            fallback = location
                        }
                    }
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                removeListener(listener)
            }

            try {
                // NETWORK 优先（室内 Wi-Fi/基站毫秒级返回），PASSIVE 次之，GPS 最后（冷启动慢）
                val providers = listOfNotNull(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                    LocationManager.GPS_PROVIDER
                ).filter { provider ->
                    try {
                        locationManager.isProviderEnabled(provider)
                    } catch (e: Exception) {
                        false
                    }
                }

                if (providers.isEmpty()) {
                    if (resumed.compareAndSet(false, true)) {
                        logResultClassified("FAIL", "LOCATION_DISABLED", "error=系统未开启任何定位源")
                        // 结构化指标：定位功能被系统关闭终态
                        buildMetric(
                            result = "FAIL",
                            resultReason = "LOCATION_DISABLED",
                            provider = "unknown",
                            accuracy = -1f,
                            slow = false,
                            slowReason = null
                        )
                        logBreakdown()
                        continuation.resumeWithException(Exception("系统未开启任何定位源"))
                    }
                    return@suspendCancellableCoroutine
                }

                // lastKnown 筛选：精度达标且时间未过期（10 分钟内）才可用，避免拿到数小时前的陈旧位置
                // ---------- 性能日志：缓存查询耗时 ----------
                val cacheCheckStartMs = System.currentTimeMillis()
                val now = System.currentTimeMillis()
                val bestCached = providers
                    .mapNotNull { p ->
                        try {
                            locationManager.getLastKnownLocation(p)
                        } catch (e: SecurityException) {
                            null
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .filter {
                        it.hasAccuracy() && it.accuracy <= CACHE_MAX_ACCURACY_METERS &&
                            now - it.time <= CACHE_MAX_AGE_MS
                    }
                    .maxByOrNull { if (it.accuracy > 0) it.time / it.accuracy.toLong() else it.time }
                cacheCheckCostMs = System.currentTimeMillis() - cacheCheckStartMs
                log(
                    Log.INFO, "缓存查询",
                    ":${cacheCheckCostMs}ms bestCached=${bestCached != null}" +
                        if (bestCached != null && bestCached.hasAccuracy()) {
                            " accuracy=${String.format(java.util.Locale.US, "%.0f", bestCached.accuracy)}m"
                        } else ""
                )

                if (bestCached != null && bestCached.accuracy <= ACCURACY_THRESHOLD_METERS) {
                    // 缓存精度达标，秒回
                    if (resumed.compareAndSet(false, true)) {
                        // ---------- 慢场景检测：缓存路径 ----------
                        val totalMs = System.currentTimeMillis() - requestStartMs
                        val cacheSlowReason = if (totalMs > SLOW_THRESHOLD_CACHE_MS) {
                            if (cacheCheckCostMs > SLOW_THRESHOLD_CACHE_QUERY_MS) {
                                "CACHE_QUERY_SLOW"
                            } else {
                                "TOTAL_SLOW"
                            }
                        } else null
                        if (cacheSlowReason != null) {
                            Log.w(
                                TAG,
                                "[链路:$traceId] 定位慢 耗时:${totalMs}ms 阈值:${SLOW_THRESHOLD_CACHE_MS}ms " +
                                    "provider=cache 原因:$cacheSlowReason 缓存查询:${cacheCheckCostMs}ms"
                            )
                        }
                        // ----------------------------------
                        finalProvider = bestCached.provider ?: "cache"
                        finalAccuracy = if (bestCached.hasAccuracy()) bestCached.accuracy else -1f
                        // ---------- 场景转移检测：缓存秒回路径也要识别 ----------
                        val cachedScenario = identifyLocationScenario(bestCached)
                        if (cachedScenario.contains("from=")) {
                            Log.i(TAG, "[链路:$traceId] 场景转移 $cachedScenario")
                        }
                        // --------------------------------------------------
                        // 结构化指标：缓存秒回终态
                        buildMetric(
                            result = "SUCCESS",
                            resultReason = "CACHE",
                            provider = "cache",
                            accuracy = finalAccuracy,
                            slow = cacheSlowReason != null,
                            slowReason = cacheSlowReason
                        )
                        logResultClassified(
                            "SUCCESS", "CACHE",
                            "provider=${bestCached.provider ?: "cache"} accuracy=" +
                                formatAccuracy(bestCached) +
                                " totalMs=${System.currentTimeMillis() - requestStartMs}"
                        )
                        logBreakdown()  // 缓存秒回路径：同样输出耗时分解
                        continuation.resume(bestCached)
                    }
                    return@suspendCancellableCoroutine
                }

                // 缓存精度不足或无缓存：启动真实定位 + 超时兜底
                if (bestCached != null) {
                    fallback = bestCached
                    fallbackFromCache = true
                }
                // 超时后用 fallback 兜底（外层 withTimeout 10s，这里 9s 提前兜底）
                handler.postDelayed(timeoutRunnable, 9_000L)

                var registeredProvider = false
                for (provider in providers) {
                    val providerKey = when (provider) {
                        LocationManager.NETWORK_PROVIDER -> "network"
                        LocationManager.PASSIVE_PROVIDER -> "passive"
                        LocationManager.GPS_PROVIDER -> "gps"
                        else -> provider
                    }
                    // ---------- 性能日志：注册前后记录时间戳 ----------
                    providerRegisterStartMs[providerKey] = System.currentTimeMillis()
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            500L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                        registeredProvider = true
                        // 注册成功 -> 开始等待回调的起点
                        providerWaitStartMs[providerKey] = System.currentTimeMillis()
                        val registerCostMs = System.currentTimeMillis() - providerRegisterStartMs[providerKey]!!
                        // ---------- 慢场景检测：Provider 注册耗时 ----------
                        if (registerCostMs > SLOW_THRESHOLD_PROVIDER_REGISTER_MS) {
                            Log.w(
                                TAG,
                                "[链路:$traceId] 定位慢 原因:PROVIDER_REGISTER_SLOW " +
                                    "provider:$provider 注册耗时:${registerCostMs}ms"
                            )
                        }
                        // ------------------------------------------------
                        Log.d(
                            TAG,
                            "[链路:$traceId] [provider:${providerKey}] 注册耗时:" +
                                "${registerCostMs}ms"
                        )
                    } catch (e: SecurityException) {
                        // 忽略单个 provider 的权限异常，继续下一个
                        log(Log.WARN, "[provider:${providerKey}] 注册失败", "原因:权限异常")
                    } catch (e: Exception) {
                        // 忽略单个 provider 异常
                        log(Log.WARN, "[provider:${providerKey}] 注册失败", "原因:${e.message}")
                    }
                }
                if (!registeredProvider && resumed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutRunnable)
                    removeListener(listener)
                    logResultClassified("FAIL", "NO_PROVIDER_ENABLED", "error=无法注册定位监听器")
                    // 结构化指标：全部 provider 注册失败终态
                    buildMetric(
                        result = "FAIL",
                        resultReason = "NO_PROVIDER_ENABLED",
                        provider = "unknown",
                        accuracy = -1f,
                        slow = false,
                        slowReason = null
                    )
                    logBreakdown()
                    continuation.resumeWithException(Exception("无法注册定位监听器"))
                }
            } catch (e: SecurityException) {
                if (resumed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutRunnable)
                    removeListener(listener)
                    logResultClassified("FAIL", "SECURITY_EXCEPTION", "error=${e.message ?: "权限异常"}")
                    // 结构化指标：注册阶段权限异常终态
                    buildMetric(
                        result = "FAIL",
                        resultReason = "SECURITY_EXCEPTION",
                        provider = "unknown",
                        accuracy = -1f,
                        slow = false,
                        slowReason = null
                    )
                    logBreakdown()
                    continuation.resumeWithException(e)
                }
            }
        }

    private fun removeListener(listener: LocationListener) {
        try {
            locationManager.removeUpdates(listener)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * 根据经纬度反向地理编码获取地址信息。
     * 在后台线程执行，返回格式化地址字符串；失败或不可用时返回空字符串。
     */
    suspend fun getAddress(latitude: Double, longitude: Double): String {
        return try {
            withTimeout(5_000) {
                amapGeocodeService.reverse(latitude, longitude)?.formatted.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 仅返回市级地址（city 优先，缺失时回退到 province）。
     * 失败或不可用时返回空字符串。
     */
    suspend fun getCityAddress(latitude: Double, longitude: Double): String {
        return try {
            withTimeout(5_000) {
                amapGeocodeService.reverse(latitude, longitude)?.city.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }
}
