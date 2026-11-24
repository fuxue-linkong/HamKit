package com.example.hamkit.data.aprs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * APRS 前台 Service：连接期间提供后台保活 + 状态通知。
 *
 * 参照 aprsdroid AprsService 的前台 Service 模式（简化版）：
 * - 连接逻辑仍由 [com.example.hamkit.ui.viewmodel.AprsViewModel] 持有（降低重构风险）
 * - Service 负责常驻通知，避免应用被系统回收导致 APRS-IS 连接断开
 * - 连接时 startForegroundService，断开时 stopService
 */
class AprsService : Service() {

    companion object {
        private const val CHANNEL_ID = "aprs_service"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, AprsService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AprsService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("APRS 服务运行中"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "APRS 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "APRS 连接状态通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("APRS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Android 16 QPR2+ Live Updates：把 APRS 连接状态升级为
        // 锁屏顶部卡片 / 通知栏置顶 / 状态栏 chip（资格详见官方文档）。
        // 前提：manifest 已声明 POST_PROMOTED_NOTIFICATIONS 权限；
        // 需要 androidx.core 1.17.0+ 的 NotificationCompat API，
        // 旧版本库上编译期不可见，运行时也无需调用（extras 方案兼容性差，不用）。
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }
}
