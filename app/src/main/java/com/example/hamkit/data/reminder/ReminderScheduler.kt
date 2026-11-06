package com.example.hamkit.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 提醒调度器：负责将每条 [ReminderItem] 注册到 [AlarmManager]。
 *
 * 调度策略：
 * - AlarmManager.setExactAndAllowWhileIdle 在 AOS 前 [ReminderSettings.leadMinutes] 分钟触发
 *   该 API 在 Doze 模式下仍会唤醒，保证精确提醒。
 * - WorkManager 周期任务（每日一次）负责重新预测过境并刷新闹钟，
 *   因为 TLE 数据每天漂移会导致过境时间变化。
 *
 * 注意：调度的时间是 AOS - leadMinutes。若该时间已过去则不调度。
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 调度单条提醒。若 settings.enabled=false 或 item.enabled=false 或触发时间已过去则跳过。
     *
     * @return true 表示已成功调度，false 表示跳过
     */
    fun schedule(item: ReminderItem, settings: ReminderSettings): Boolean {
        if (!settings.enabled || !item.enabled) {
            cancel(item.catalogNumber)
            return false
        }

        // 计算触发时间（AOS - leadMinutes），转换为毫秒。
        // leadMinutes 钳制在 [0, 1440]，防止 SharedPreferences 异常值导致乘法溢出
        val leadMinutesSafe = settings.leadMinutes.coerceIn(0, 1440)
        val triggerAtMillis = item.aosTimeMillis - leadMinutesSafe * 60L * 1000L
        val now = System.currentTimeMillis()
        if (triggerAtMillis <= now) {
            // 触发时间已过去，不调度
            cancel(item.catalogNumber)
            return false
        }

        // 仅白天提醒：检查触发时间的小时是否在 6-22 之间
        if (settings.repeatMode == RepeatMode.DAYTIME_ONLY) {
            val hour = Instant.ofEpochMilli(triggerAtMillis)
                .atZone(ZoneId.systemDefault())
                .hour
            if (hour < 6 || hour >= 22) {
                cancel(item.catalogNumber)
                return false
            }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.catalogNumber,
            createReceiverIntent(item, ReminderReceiver.CHANNEL_PHONE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        setAlarm(triggerAtMillis, pendingIntent)

        // 手环提醒单独一个闹钟：AOS 前 60 秒触发，与手机通知互不覆盖
        scheduleWearable(item, settings)
        return true
    }

    /**
     * 注册一个精确闹钟，并在无精确闹钟权限时回退到非精确闹钟。
     *
     * Android 12+ 要求 SCHEDULE_EXACT_ALARM 或 USE_EXACT_ALARM 权限；
     * Android 14+ 新装 App 默认不授予，需先检查 canScheduleExactAlarms。
     * 未授权时用 setAndAllowWhileIdle 兜底（误差可能达数分钟），
     * 避免直接抛 SecurityException 导致本次调度被静默丢弃。
     */
    private fun setAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        try {
            if (hasExactAlarmCapability()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            // 极少数 OEM 实现异常：回退到非精确闹钟保证至少能触发
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    /**
     * 调度手环提醒（AOS 前 [WEARABLE_LEAD_MS]）。
     *
     * 独立的 requestCode（`catalogNumber + WEARABLE_REQUEST_OFFSET`）保证与
     * 手机通知的闹钟互不覆盖：两者触发时间不同（提前量不同），必须并存。
     */
    private fun scheduleWearable(item: ReminderItem, settings: ReminderSettings) {
        if (!settings.wearableEnabled) {
            cancelWearable(item.catalogNumber)
            return
        }

        val triggerAtMillis = item.aosTimeMillis - WEARABLE_LEAD_MS
        if (triggerAtMillis <= System.currentTimeMillis()) {
            cancelWearable(item.catalogNumber)
            return
        }

        // 仅白天提醒的判定与手机通知保持一致，避免"夜里手环狂震"
        if (settings.repeatMode == RepeatMode.DAYTIME_ONLY) {
            val hour = Instant.ofEpochMilli(triggerAtMillis)
                .atZone(ZoneId.systemDefault())
                .hour
            if (hour < 6 || hour >= 22) {
                cancelWearable(item.catalogNumber)
                return
            }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.catalogNumber + WEARABLE_REQUEST_OFFSET,
            createReceiverIntent(item, ReminderReceiver.CHANNEL_WEARABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setAlarm(triggerAtMillis, pendingIntent)
    }

    /** 取消某颗卫星的手环提醒闹钟（不影响手机通知闹钟） */
    private fun cancelWearable(catalogNumber: Int) {
        val intent = createReceiverIntent(
            ReminderItem(
                catalogNumber = catalogNumber,
                name = "",
                aosTimeMillis = 0L,
                losTimeMillis = 0L,
                maxElevation = 0.0,
                aosAzimuth = 0,
                losAzimuth = 0,
                modes = emptyList()
            ),
            ReminderReceiver.CHANNEL_WEARABLE
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            catalogNumber + WEARABLE_REQUEST_OFFSET,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    /**
     * 批量调度多个提醒。会先清除所有旧闹钟再重新注册。
     */
    fun scheduleAll(items: List<ReminderItem>, settings: ReminderSettings) {
        // 注意：无法枚举已注册的 PendingIntent，所以仅取消 items 中未启用的项
        items.forEach { item ->
            if (!settings.enabled || !item.enabled) {
                cancel(item.catalogNumber)
            }
        }
        // 调度启用的提醒
        items.forEach { item ->
            if (item.enabled && settings.enabled) {
                schedule(item, settings)
            }
        }
        // 启动每日刷新 Worker
        scheduleDailyRefresh()
        // 取消已不在 items 中的提醒（如关闭某颗卫星的提醒开关）
        // 由调用方在 MainViewModel.setSatelliteReminderEnabled 中处理
    }

    /**
     * 查询当前是否具备精确闹钟调度能力（Android 12+）。
     * Android 11- 始终返回 true。
     * 供权限管理 UI 判断是否需要引导用户前往系统设置授权。
     */
    fun hasExactAlarmCapability(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * 取消指定卫星的全部提醒（手机通知 + 手环提醒）。
     */
    fun cancel(catalogNumber: Int) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            catalogNumber,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        cancelWearable(catalogNumber)
    }

    /**
     * 取消所有已注册的提醒。
     *
     * 由于 Android 不支持枚举已注册的 PendingIntent，调用方必须传入当前所有需清理的
     * catalogNumber 列表（通常为 ReminderStore.loadItems 的全量）。WorkManager 周期任务一并取消。
     */
    fun cancelAll(catalogNumbers: List<Int>) {
        catalogNumbers.forEach { cancel(it) }
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_DAILY_REFRESH)
    }

    /**
     * 启动或更新每日刷新的周期 Work。
     * 重复调用安全（ExistingPeriodicWorkPolicy.KEEP 保持已有任务，UPDATE 更新配置）。
     */
    fun scheduleDailyRefresh() {
        // 网络约束：Worker 需要下载 TLE 数据，仅在有网络时执行
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ReminderRefreshWorker>(
            REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
            // flex period 让 Work 在每日的最后 1 小时内执行，减少电量消耗
            FLEX_PERIOD_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_DAILY_REFRESH,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun createReceiverIntent(item: ReminderItem, channel: String): Intent {
        return Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            // 通过 extra 传递提醒详情，避免 Receiver 再次查 DB
            putExtra(ReminderReceiver.EXTRA_CHANNEL, channel)
            putExtra(ReminderReceiver.EXTRA_CATALOG, item.catalogNumber)
            putExtra(ReminderReceiver.EXTRA_NAME, item.name)
            putExtra(ReminderReceiver.EXTRA_AOS, item.aosTimeMillis)
            putExtra(ReminderReceiver.EXTRA_LOS, item.losTimeMillis)
            putExtra(ReminderReceiver.EXTRA_MAX_EL, item.maxElevation)
            putExtra(ReminderReceiver.EXTRA_AOS_AZ, item.aosAzimuth)
            putExtra(ReminderReceiver.EXTRA_LOS_AZ, item.losAzimuth)
            // modes 用 String 数组传递
            putExtra(ReminderReceiver.EXTRA_MODES, item.modes.toTypedArray())
        }
    }

    companion object {
        private const val WORK_DAILY_REFRESH = "reminder_daily_refresh"
        // WorkManager 最小周期为 15 分钟，但每日刷新足够（TLE 漂移每日约 1 分钟）
        private const val REPEAT_INTERVAL_HOURS = 24L
        private const val FLEX_PERIOD_HOURS = 1L

        /** 手环提醒的提前量：AOS 前 60 秒（手环上做"还有 1 分钟"的倒计时） */
        private const val WEARABLE_LEAD_MS = 60L * 1000L

        /**
         * 手环闹钟的 requestCode 偏移。
         * 必须避开手机通知（直接用 catalogNumber）与 SSTV 动作按钮（+100000），
         * 否则 PendingIntent 会被系统判定为同一个而互相覆盖。
         */
        private const val WEARABLE_REQUEST_OFFSET = 200_000
    }
}
