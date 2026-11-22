package com.example.hamkit.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.hamkit.R
import com.example.hamkit.data.sstv.SstvLaunchIntent
import com.example.hamkit.data.sstv.SstvSatelliteLink
import com.example.hamkit.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 通知渠道与通知构建器。
 *
 * 渠道创建一次后系统持久化保存；用户可在系统设置中再次修改。
 * 通知样式：
 * - 标题：卫星名 + "即将过境"
 * - 正文：AOS 时间、最大仰角、方位角、工作模式、LOS 时间
 */
class ReminderNotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * 创建通知渠道。重复调用安全（系统会忽略重复创建）。
     *
     * 渠道重要性依据 [ReminderSettings]：
     * - 声音开启 -> IMPORTANCE_HIGH（带声音 + 横幅）
     * - 仅振动 -> IMPORTANCE_DEFAULT
     * - 都关闭 -> IMPORTANCE_LOW（静默）
     */
    fun createChannel(settings: ReminderSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val importance = when {
            settings.soundEnabled -> NotificationManager.IMPORTANCE_HIGH
            settings.vibrationEnabled -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_LOW
        }

        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
            description = CHANNEL_DESC
            enableVibration(settings.vibrationEnabled)
            if (settings.soundEnabled) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, attrs)
            } else {
                setSound(null, null)
            }
            enableLights(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun recreateChannel(settings: ReminderSettings) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(CHANNEL_ID)
        }
        createChannel(settings)
    }

    /**
     * 发送过境提醒通知。通知 ID 使用卫星 NORAD 编号，避免不同卫星通知互相覆盖。
     *
     * **SSTV 卫星的直达接收态（二期 T8）**：若该卫星支持 SSTV，通知的正文点击与「接收 SSTV」
     * 动作都会带上 [SstvLaunchIntent] 的 extra，点一下即进入解码器并开始接收，
     * 免去「展开应用 → 找卫星 → 进详情页 → 点接收」的四步。预置模式由
     * [SstvSatelliteLink.presetModeNameFor] 决定（ISS 为 PD-120，其余交给 VIS）。
     */
    fun showPassReminder(item: ReminderItem) {
        val isSstv = SstvSatelliteLink.isSstvSatellite(item.modes, item.catalogNumber)
        val sstvPresetMode = if (isSstv) {
            SstvSatelliteLink.presetModeNameFor(item.catalogNumber)
        } else {
            null
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (isSstv) SstvLaunchIntent.putLaunchRequest(intent, sstvPresetMode)
        val pendingIntent = PendingIntent.getActivity(
            context,
            item.catalogNumber,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val utcFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("UTC"))

        val aosLocal = formatter.format(Instant.ofEpochMilli(item.aosTimeMillis))
        val losLocal = formatter.format(Instant.ofEpochMilli(item.losTimeMillis))
        val aosUtc = utcFormatter.format(Instant.ofEpochMilli(item.aosTimeMillis))

        val modeText = if (item.modes.isEmpty()) {
            context.getString(R.string.mode_unknown)
        } else {
            item.modes.joinToString("/")
        }

        val contentText = context.getString(
            R.string.reminder_notification_content,
            aosLocal,
            item.maxElevation.toInt(),
            item.aosAzimuth,
            modeText
        )

        val subText = context.getString(
            R.string.reminder_notification_sub,
            losLocal,
            aosUtc
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(context.getString(R.string.reminder_notification_title, item.name))
            .setContentText(contentText)
            .setSubText(subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\n$subText"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                // SSTV 卫星：额外给一个显式动作按钮。部分 ROM 会把通知正文点击折叠成「打开应用」，
                // 动作按钮则是稳定的直达入口。
                if (isSstv) {
                    val actionIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    SstvLaunchIntent.putLaunchRequest(actionIntent, sstvPresetMode)
                    // requestCode 必须与正文 PendingIntent 不同，否则系统会复用同一个
                    val actionPendingIntent = PendingIntent.getActivity(
                        context,
                        item.catalogNumber + SSTV_ACTION_REQUEST_OFFSET,
                        actionIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(0, context.getString(R.string.sstv_reminder_action_receive), actionPendingIntent)
                }
            }
            .build()

        try {
            notificationManager.notify(item.catalogNumber, notification)
        } catch (_: SecurityException) {
            // 部分设备通知权限被禁用，忽略避免崩溃
        }
    }

    /**
     * 发送"自动添加提醒成功"的轻量提示通知。
     * 通知 ID 固定为 AUTO_ADD_ID，多次添加只显示最新一条。
     */
    fun showAutoAddedReminder(item: ReminderItem) {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
        val aosTime = formatter.format(Instant.ofEpochMilli(item.aosTimeMillis))

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle(context.getString(R.string.reminder_auto_added_title))
            .setContentText(context.getString(R.string.reminder_auto_added_text, item.name, aosTime))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            notificationManager.notify(AUTO_ADD_ID, notification)
        } catch (_: SecurityException) {
            // 通知权限被禁用，忽略
        }
    }

    /**
     * 取消指定卫星的通知。
     */
    fun cancel(catalogNumber: Int) {
        notificationManager.cancel(catalogNumber)
    }

    /**
     * 取消所有提醒通知（不取消闹钟调度，调度由 [ReminderScheduler] 负责）。
     *
     * 逐个按 catalogNumber 取消，避免 [NotificationManager.cancelAll] 误清
     * 其他功能（如 APRS、下载进度）的通知。
     */
    fun cancelAll(catalogNumbers: List<Int>) {
        catalogNumbers.forEach { notificationManager.cancel(it) }
        notificationManager.cancel(AUTO_ADD_ID)
    }

    companion object {
        const val CHANNEL_ID = "satellite_pass_reminder"
        const val AUTO_ADD_ID = 9001

        /**
         * SSTV 动作按钮的 PendingIntent requestCode 偏移。
         *
         * 必须与正文的 requestCode（即 NORAD 编号本身）区分开：`PendingIntent` 的复用判定
         * 只看 requestCode + `Intent.filterEquals`，而 extras 不参与比较，两者若同号会被
         * 系统判为同一个 PendingIntent。偏移取 10 万，NORAD 编号远小于该量级，不会撞号。
         */
        private const val SSTV_ACTION_REQUEST_OFFSET = 100_000

        private const val CHANNEL_NAME = "卫星过境提醒"
        private const val CHANNEL_DESC = "在已开启过境提醒的卫星过境前推送通知"
    }
}
