package com.example.hamkit.data.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.hamkit.data.SettingsStore
import com.example.hamkit.data.satellite.FavoriteSatellitesStore
import com.example.hamkit.data.satellite.SatelliteCacheStore
import com.example.hamkit.data.satellite.SatelliteDataSource
import com.example.hamkit.data.satellite.SatellitePredictor
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant

/**
 * 提醒刷新 Worker。
 *
 * 职责（完整后台预测链路）：
 * 1. 从 [SettingsStore] 读取用户最后已知位置（由 MainViewModel 在前台定位时持久化）
 * 2. 从 [SatelliteCacheStore] 读取 TLE 缓存；若缓存超过 24 小时则重新下载
 * 3. 用 [SatellitePredictor] 预测未来 48 小时的卫星过境
 * 4. 过滤出收藏卫星（[FavoriteSatellitesStore]），更新 [ReminderStore] 中的提醒项
 * 5. 用 [ReminderScheduler] 重新注册所有 AlarmManager 闹钟
 *
 * 此 Worker 可在应用进程不存在时独立运行，保证后台持续推送提醒通知。
 * 由两种方式触发：
 * - [ReminderScheduler.scheduleDailyRefresh] 注册的 24 小时周期任务
 * - [ReminderBootReceiver] 在设备重启后注册的一次性任务
 *
 * 周期：每 24 小时执行一次（WorkManager 最小周期 15 分钟，但每日足够）。
 */
class ReminderRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val settingsStore = SettingsStore(applicationContext)
            val reminderStore = ReminderStore(applicationContext)
            val favoriteStore = FavoriteSatellitesStore(applicationContext)
            val settings = reminderStore.loadSettings()

            // 提醒功能未启用时，仅取消现有闹钟，不做预测
            if (!settings.enabled) {
                val scheduler = ReminderScheduler(applicationContext)
                val existingItems = reminderStore.loadItems()
                existingItems.forEach { scheduler.cancel(it.catalogNumber) }
                return Result.success()
            }

            // 读取用户最后已知位置（前台定位时持久化到 SharedPreferences）
            if (!settingsStore.hasLastLocation()) {
                // 无位置信息，仅重新注册已有闹钟（兼容旧行为）
                val items = reminderStore.loadItems()
                val scheduler = ReminderScheduler(applicationContext)
                scheduler.scheduleAll(items, settings)
                return Result.success()
            }

            val latitude = settingsStore.lastLatitude
            val longitude = settingsStore.lastLongitude

            // 获取 TLE 数据：优先使用缓存，超过 24 小时则重新下载
            val cacheStore = SatelliteCacheStore(applicationContext)
            val cached = cacheStore.load()
            val tles = if (cached == null || isCacheExpired(cached.updatedAt)) {
                // 缓存过期或不存在，重新下载 TLE
                val dataSource = SatelliteDataSource()
                val fresh = dataSource.fetchAmateurTLEs(
                    enableAmateur = settingsStore.tleSourceAmateur,
                    enableSatnogs = settingsStore.tleSourceSatnogs,
                    enableActive = true
                )
                cacheStore.save(fresh, Instant.now())
                fresh
            } else {
                cached.tles
            }

            // 预测未来 48 小时的卫星过境
            val predictor = SatellitePredictor()
            val satellites = predictor.predictUpcomingPasses(
                sourcedTles = tles,
                latitude = latitude,
                longitude = longitude,
                hoursAhead = 48
            )

            // 过滤出收藏卫星的未来过境（AOS 在未来）。
            // 旧逻辑用 !isCurrentlyVisible 过滤，但预测器对在境卫星返回的是下次过境，
            // 旧过滤会把下次过境一起丢弃。改以 AOS 时间为准。
            val favorites = favoriteStore.load()
            val nowMillis = System.currentTimeMillis()
            val futureFavorites = satellites.filter {
                it.catalogNumber in favorites && it.aosTime.toEpochMilli() > nowMillis
            }

            if (futureFavorites.isNotEmpty()) {
                // 更新提醒项
                val updatedItems = reminderStore.loadItems().toMutableList()
                futureFavorites.forEach { sat ->
                    val item = ReminderItem(
                        catalogNumber = sat.catalogNumber,
                        name = sat.name,
                        aosTimeMillis = sat.aosTime.toEpochMilli(),
                        losTimeMillis = sat.losTime.toEpochMilli(),
                        maxElevation = sat.maxElevation,
                        aosAzimuth = sat.aosAzimuth,
                        losAzimuth = sat.losAzimuth,
                        modes = sat.modes,
                        enabled = true
                    )
                    val idx = updatedItems.indexOfFirst { it.catalogNumber == sat.catalogNumber }
                    if (idx >= 0) {
                        // 保留原 enabled 状态，仅更新过境信息
                        val merged = item.copy(enabled = updatedItems[idx].enabled)
                        updatedItems[idx] = merged
                    } else {
                        updatedItems.add(item)
                    }
                }
                reminderStore.saveItems(updatedItems)
            }

            // 重新注册所有闹钟
            val scheduler = ReminderScheduler(applicationContext)
            scheduler.scheduleAll(reminderStore.loadItems(), settings)

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // 权限类错误属永久失败，重试无意义，避免浪费后台执行配额
            Result.failure()
        } catch (e: Exception) {
            // 临时性错误（网络等）：有限次重试，超过阈值放弃等待下个周期
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    /**
     * 判断 TLE 缓存是否过期（超过 24 小时）。
     */
    private fun isCacheExpired(updatedAt: Instant): Boolean {
        return Duration.between(updatedAt, Instant.now()).toHours() >= 24
    }

    companion object {
        /** 单次调度内的最大重试次数，超过则等待下个 24 小时周期 */
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
