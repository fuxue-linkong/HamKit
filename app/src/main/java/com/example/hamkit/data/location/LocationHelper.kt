package com.example.hamkit.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
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
        /** 单次定位精度门槛：精度优于此值立即返回，否则继续等更优结果 */
        private const val ACCURACY_THRESHOLD_METERS = 20f
        /** lastKnown 缓存最大可接受精度：超过此值的缓存视为不可用 */
        private const val CACHE_MAX_ACCURACY_METERS = 50f
        /** lastKnown 缓存最大时效：超过 10 分钟的缓存视为过期，不可作为秒回结果 */
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
        /** 持续定位去重时间窗：此窗口内只保留精度最优的一次上报 */
        private const val DEDUP_WINDOW_MS = 1500L
    }

    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
            throw SecurityException("缺少定位权限")
        }

        return try {
            withTimeout(10_000) { requestLocationManagerLocation() }
        } catch (e: TimeoutCancellationException) {
            throw Exception("定位超时，请检查手机是否开启 GPS/网络定位")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
            // 暂存精度未达标的结果：超时前有更优结果则替换，超时则用此兜底
            var fallback: Location? = null
            val handler = android.os.Handler(Looper.getMainLooper())
            // listener 在下面定义，这里用 lateinit 让 timeoutRunnable 可引用
            lateinit var listener: LocationListener
            val timeoutRunnable = Runnable {
                if (resumed.compareAndSet(false, true)) {
                    removeListener(listener)
                    val result = fallback
                    if (result != null) {
                        continuation.resume(result)
                    } else {
                        continuation.resumeWithException(Exception("定位超时未获取到结果"))
                    }
                }
            }

            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (resumed.get()) return
                    // 精度达标或来自 GPS：立即返回（GPS accuracy 计算保守，即使超门槛也可信）
                    if (location.accuracy <= ACCURACY_THRESHOLD_METERS ||
                        location.provider == LocationManager.GPS_PROVIDER) {
                        if (resumed.compareAndSet(false, true)) {
                            removeListener(this)
                            handler.removeCallbacks(timeoutRunnable)
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
                        continuation.resumeWithException(Exception("系统未开启任何定位源"))
                    }
                    return@suspendCancellableCoroutine
                }

                // lastKnown 筛选：精度达标且时间未过期（10 分钟内）才可用，避免拿到数小时前的陈旧位置
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

                if (bestCached != null && bestCached.accuracy <= ACCURACY_THRESHOLD_METERS) {
                    // 缓存精度达标，秒回
                    if (resumed.compareAndSet(false, true)) {
                        continuation.resume(bestCached)
                    }
                    return@suspendCancellableCoroutine
                }

                // 缓存精度不足或无缓存：启动真实定位 + 超时兜底
                if (bestCached != null) {
                    fallback = bestCached
                }
                // 超时后用 fallback 兜底（外层 withTimeout 10s，这里 9s 提前兜底）
                handler.postDelayed(timeoutRunnable, 9_000L)

                var registeredProvider = false
                for (provider in providers) {
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            500L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                        registeredProvider = true
                    } catch (e: SecurityException) {
                        // 忽略单个 provider 的权限异常，继续下一个
                    } catch (e: Exception) {
                        // 忽略单个 provider 异常
                    }
                }
                if (!registeredProvider && resumed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutRunnable)
                    removeListener(listener)
                    continuation.resumeWithException(Exception("无法注册定位监听器"))
                }
            } catch (e: SecurityException) {
                if (resumed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutRunnable)
                    removeListener(listener)
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
