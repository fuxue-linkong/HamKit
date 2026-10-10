package com.example.hamkit.data.sstv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.hamkit.R

/**
 * 「播放捕获」用的前台服务。
 *
 * **为什么必须有它**：Android 14（API 34）起，
 * `MediaProjectionManager.getMediaProjection()` 要求**调用前**已经有一个
 * `foregroundServiceType="mediaProjection"` 的前台服务在运行，否则直接抛
 * `SecurityException`。本项目的 `targetSdk` 是 37，因此「先起服务、再取投影」是硬性顺序。
 *
 * 本服务**不持有**任何采集逻辑（采集由 [PlaybackCaptureAudioSource] 在
 * [SstvRecorder] 的协程里完成）—— 它只负责把进程提到前台并声明 mediaProjection 用途，
 * 这是平台对「录屏/录音捕获」的合规要求，也让用户能在通知栏看到并随时停止。
 *
 * 行为：`ACTION_STOP` 停止自身；被系统回收时返回 `START_NOT_STICKY`（不做无声重启，
 * 否则用户会看到「采集开着但没人用」的状态）。
 */
class SstvCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sstv_capture_notification_title))
            .setContentText(getString(R.string.sstv_capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // API 34+ 必须显式声明前台服务类型，且要与 manifest 里的一致
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sstv_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.sstv_capture_channel_desc) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "sstv_playback_capture"
        private const val NOTIFICATION_ID = 0x5517

        /** 停止服务的 action。 */
        const val ACTION_STOP = "com.example.hamkit.action.SSTV_CAPTURE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, SstvCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SstvCaptureService::class.java))
        }
    }
}
