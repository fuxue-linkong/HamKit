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

        val now = System.currentTimeMillis()

        // 两条链路各自独立排期，互相不能拖累。
        // 历史 bug：手环闹钟写在手机通知的"触发时间已过"早退之后，
        // 一旦 AOS 落在 leadMinutes 之内（例如 3 分钟后就过境），
        // 手环闹钟根本没注册，用户看到的就是"到点什么都不弹"。
        val phoneScheduled = schedulePhone(item, settings, now)
        val wearableScheduled = scheduleWearable(item, settings, now)
        return phoneScheduled || wearableScheduled
    }

    /**
     * 算实际该在什么时候响。
     *
     * - 目标时刻在未来 → 就用它
     * - 目标时刻已过但**卫星还没过境** → 立刻补发（延后 [IMMEDIATE_DELAY_MS]，
     *   给 AlarmManager 留一点余量），而不是直接丢弃
     * - 卫星也已经过境 → null，调用方取消该闹钟
     */
    private fun resolveAlarmTime(triggerAtMillis: Long, aosMillis: Long, now: Long): Long? {
        if (triggerAtMillis > now) return triggerAtMillis
        if (now < aosMillis) return now + IMMEDIATE_DELAY_MS
        return null
    }

    /** 仅白天提醒：按目标触发时刻所在小时判断（对补发场景同样成立） */
    private fun isWithinDaytimeWindow(settings: ReminderSettings, triggerAtMillis: Long): Boolean {
        if (settings.repeatMode != RepeatMode.DAYTIME_ONLY) return true
        val hour = Instant.ofEpochMilli(triggerAtMillis)
            .atZone(ZoneId.systemDefault())
            .hour
        return hour in 6..21
    }

    /** 手机通知闹钟（AOS - leadMinutes） */
    private fun schedulePhone(item: ReminderItem, settings: ReminderSettings, now: Long): Boolean {
        // leadMinutes 钳制在 [0, 1440]，防止 SharedPreferences 异常值导致乘法溢出
        val leadMinutesSafe = settings.leadMinutes.coerceIn(0, 1440)
        val triggerAtMillis = item.aosTimeMillis - leadMinutesSafe * 60L * 1000L

        // 仅白天提醒：检查触发时间的小时是否在 6-22 之间
        if (!isWithinDaytimeWindow(settings, triggerAtMillis)) {
            cancelPhone(item.catalogNumber)
            return false
        }

        val alarmAt = resolveAlarmTime(triggerAtMillis, item.aosTimeMillis, now)
        if (alarmAt == null) {
            cancelPhone(item.catalogNumber)
            return false
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.catalogNumber,
            createReceiverIntent(item, ReminderReceiver.CHANNEL_PHONE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setAlarm(alarmAt, pendingIntent)
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
    private fun scheduleWearable(item: ReminderItem, settings: ReminderSettings, now: Long): Boolean {
        if (!settings.wearableEnabled) {
            cancelWearable(item.catalogNumber)
            return false
        }

        val triggerAtMillis = item.aosTimeMillis - WEARABLE_LEAD_MS

        // 手环用自己的触发时刻（AOS-60s）判断白天/夜间，不沿用手机通知的提前量
        if (!isWithinDaytimeWindow(settings, triggerAtMillis)) {
            cancelWearable(item.catalogNumber)
            return false
        }

        // 与手机通知同款补发逻辑：只有在 AOS 已过时才丢弃
        val alarmAt = resolveAlarmTime(triggerAtMillis, item.aosTimeMillis, now)
        if (alarmAt == null) {
            cancelWearable(item.catalogNumber)
            return false
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.catalogNumber + WEARABLE_REQUEST_OFFSET,
            createReceiverIntent(item, ReminderReceiver.CHANNEL_WEARABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setAlarm(alarmAt, pendingIntent)
        return true
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
        cancelPhone(catalogNumber)
        cancelWearable(catalogNumber)
    }

    /** 只取消手机通知闹钟 */
    private fun cancelPhone(catalogNumber: Int) {
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
         * 补发延迟：目标时刻已过但卫星还没过境时，立刻补一条。
         * 留 1 秒余量，避免 setExact* 传入"过去时间"被系统立即触发成一团。
         */
        private const val IMMEDIATE_DELAY_MS = 1_000L

        /**
         * 手环闹钟的 requestCode 偏移。
         * 必须避开手机通知（直接用 catalogNumber）与 SSTV 动作按钮（+100000），
         * 否则 PendingIntent 会被系统判定为同一个而互相覆盖。
         */
        private const val WEARABLE_REQUEST_OFFSET = 200_000
    }
}
