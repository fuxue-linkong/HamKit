package com.example.hamkit.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.hamkit.data.wearable.WearableAlertDispatcher

/**
 * 接收 [AlarmManager] 触发的提醒广播。
 *
 * 由 [ReminderScheduler.schedule] 注册的精确闹钟触发，
 * 通过 Intent extra 携带提醒详情，构建并发送通知。
 *
 * 直接从 Intent extra 读取数据，避免 Receiver 内做 DB IO（10s 限制）。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_REMINDER) return

        val catalogNumber = intent.getIntExtra(EXTRA_CATALOG, -1)
        if (catalogNumber < 0) return

        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val aosTimeMillis = intent.getLongExtra(EXTRA_AOS, 0L)
        if (aosTimeMillis <= 0L) return

        val item = ReminderItem(
            catalogNumber = catalogNumber,
            name = name,
            aosTimeMillis = aosTimeMillis,
            losTimeMillis = intent.getLongExtra(EXTRA_LOS, 0L),
            maxElevation = intent.getDoubleExtra(EXTRA_MAX_EL, 0.0),
            aosAzimuth = intent.getIntExtra(EXTRA_AOS_AZ, 0),
            losAzimuth = intent.getIntExtra(EXTRA_LOS_AZ, 0),
            modes = intent.getStringArrayExtra(EXTRA_MODES)?.toList() ?: emptyList(),
            enabled = true
        )

        val store = ReminderStore(context)
        // 同一个 Receiver 服务两种闹钟：手机通知（AOS - leadMinutes）与
        // 手环提醒（AOS - 60s），用 extra 区分，避免再多注册一个 Receiver。
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: CHANNEL_PHONE

        // SharedPreferences 读取 + 通知构建/渠道创建属磁盘 IO，转后台线程避免主线程 ANR
        val pendingResult = goAsync()
        Thread {
            try {
                val settings = store.loadSettings()

                if (channel == CHANNEL_WEARABLE) {
                    // 开关可能在调度后被关掉，这里再确认一次
                    if (settings.wearableEnabled) {
                        WearableAlertDispatcher.get(context).dispatch(item)
                    }
                } else {
                    val helper = ReminderNotificationHelper(context)

                    // 渠道可能未创建（首次触发）或被用户修改，重新创建一次保证一致
                    helper.createChannel(settings)
                    helper.showPassReminder(item)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.example.hamkit.ACTION_TRIGGER_REMINDER"
        const val EXTRA_CATALOG = "catalog"
        const val EXTRA_NAME = "name"
        const val EXTRA_AOS = "aos"
        const val EXTRA_LOS = "los"
        const val EXTRA_MAX_EL = "max_el"
        const val EXTRA_AOS_AZ = "aos_az"
        const val EXTRA_LOS_AZ = "los_az"
        const val EXTRA_MODES = "modes"
        /** 闹钟用途：手机通知 / 手环提醒 */
        const val EXTRA_CHANNEL = "channel"

        const val CHANNEL_PHONE = "phone"
        const val CHANNEL_WEARABLE = "wearable"
    }
}
