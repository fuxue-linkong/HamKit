package com.example.hamkit.data.sstv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
 * **启动时机（重要）**：虽然 AOSP 只要求在取投影**之前**有前台服务，但国产 ROM
 * （MIUI / HyperOS 等）会额外校验 `CAPTURE_VIDEO_OUTPUT` 或 `project_media`：
 * 进程尚未持有有效投影授权时启动 mediaProjection 前台服务会直接抛
 * `SecurityException`（"Starting FGS with type mediaProjection ... requires permissions"）。
 * 该异常发生在 `onStartCommand` 中且无法被调用方捕获，会**直接崩溃进程**。
 * 因此调用方必须**先拿到投影授权、再启动本服务**（见 `handleCaptureResult`）。
 *
 * 另外本服务把 `startForeground` 的结果回传给调用方：失败时**不再崩溃**，
 * 而是停掉自己并通过回调告知 UI 回退（见 [start] 的 `onFailure`）。
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
        // 关键：厂商 ROM 会在 setServiceForeground 里再校验一次权限/投影 token，
        // 失败时抛 SecurityException。这里必须兜住，否则整个进程被杀掉（用户看到的是闪退）。
        val error = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.exceptionOrNull()
        if (error != null) {
            // 失败后立刻收起前台状态并停止自己，避免留下一个「半启动」的服务
            stopForegroundCompat()
            stopSelf()
            // 通过静态回调把失败原因交回 UI（onStartCommand 本身就在主线程，可直接回调）
            startFailureListener?.invoke(error)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
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

        /**
         * 前台服务启动失败的监听器（服务进程内静态持有，由 ViewModel 在发起捕获时挂上、
         * 结束或取消时摘下）。回调发生在主线程（`onStartCommand` 本就在主线程）。
         */
        @Volatile
        private var startFailureListener: ((Throwable) -> Unit)? = null

        /** 挂载 / 传 null 摘除前台服务启动失败监听。 */
        fun setStartFailureListener(listener: ((Throwable) -> Unit)?) {
            startFailureListener = listener
        }

        /**
         * 启动前台服务。
         *
         * @return `false` 表示前置条件不满足（缺 `FOREGROUND_SERVICE_MEDIA_PROJECTION`
         *   权限，或系统版本过低），**此时不会调用 startService**，调用方应直接回退，
         *   不要继续去取投影。真正的启动失败（厂商 ROM 拦截）会通过
         *   [setStartFailureListener] 异步回调。
         */
        fun start(context: Context): Boolean {
            // FGS 权限被用户在系统设置里撤销时，startService 必然抛 SecurityException，
            // 提前拦下，避免把一个「注定失败」的启动请求发出去
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.FOREGROUND_SERVICE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val intent = Intent(context, SstvCaptureService::class.java)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SstvCaptureService::class.java)) }
        }
    }
}
