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
 * 另外本服务把前台登记的结果回传给调用方：
 * - 成功进入前台 → [setStartReadyListener] 通知调用方，此时才可以取投影；
 * - 失败 → 先用 0 类型补登记一次（清掉系统的 5 秒超时判定），再停掉自己并通过
 *   [setStartFailureListener] 告知 UI 回退，**不会再崩溃进程**。
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
        // 只要进来就先把这一轮的「前台登记义务」清掉（见下方注释），确保无论后续成功失败
        // 都不会再触发系统的 ForegroundServiceDidNotStartInTimeException。
        createChannel()
        val notification = buildNotification()

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
            // ── 为什么失败后要「降级再登记一次」（关键修复）──
            //
            // startForegroundService() 会让系统起一个 5 秒计时器：只有**成功**调用过
            // startForeground 才算交差。厂商 ROM 拒绝 mediaProjection 类型时这里抛异常，
            // 若直接 stopSelf()，那个计时器不会被撤销，5 秒后主线程仍会抛
            // ForegroundServiceDidNotStartInTimeException 把进程杀掉 ——
            // 也就是「优雅失败」反而造出了另一条闪退路径。
            //
            // 解法：用 FOREGROUND_SERVICE_TYPE_NONE（0）再登记一次。0 类型不走投影 token
            // 校验，几乎总能成功，于是系统认为「义务已履行」、计时器被取消；随后立刻收回
            // 前台状态并停止服务，用户不会看到残留通知。
            val fallback = runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            }.exceptionOrNull()
            // 失败后立刻收起前台状态并停止自己，避免留下一个「半启动」的服务
            stopForegroundCompat()
            stopSelf()
            // 通过静态回调把失败原因交回 UI（onStartCommand 本身就在主线程，可直接回调）。
            // 降级登记都失败时，优先把降级错误也带上，方便定位真实原因。
            val reported = if (fallback != null) {
                IllegalStateException("${error.message}；降级登记亦失败：${fallback.message}", error)
            } else {
                error
            }
            startFailureListener?.invoke(reported)
            return START_NOT_STICKY
        }
        // ── 成功登记前台后回调（关键修复：消除竞态）──
        //
        // startForegroundService() 是**异步投递**：调用方拿到的只是「请求已发出」，
        // 并不意味着 onStartCommand 已执行、服务已进入前台。而 Android 14+ 要求
        // getMediaProjection() 时前台服务必须**已经**在前台，否则 SecurityException /
        // 返回 null。因此这里登记成功后主动通知调用方「可以取投影了」。
        startReadyListener?.invoke()
        return START_NOT_STICKY
    }

    /** 构建常驻通知（前台服务必须有通知，否则平台直接拒绝）。 */
    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.sstv_capture_notification_title))
        .setContentText(getString(R.string.sstv_capture_notification_text))
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

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
         * 前台服务**已成功登记前台状态**后的监听器。
         *
         * 调用方应在这之后才去 `getMediaProjection()` —— `startForegroundService()` 是
         * 异步的，`start()` 返回 true 只代表请求已发出，此时服务尚未真正进入前台。
         */
        @Volatile
        private var startReadyListener: (() -> Unit)? = null

        /** 挂载 / 传 null 摘除「前台已就绪」监听。 */
        fun setStartReadyListener(listener: (() -> Unit)?) {
            startReadyListener = listener
        }

        /**
         * 启动前台服务。
         *
         * @return `false` 表示前置条件不满足（缺 `FOREGROUND_SERVICE[_MEDIA_PROJECTION]`
         *   权限、Android 13+ 缺 `POST_NOTIFICATIONS`，或系统版本过低），**此时不会调用
         *   startService**，调用方应直接回退，不要继续去取投影。真正的启动失败（厂商 ROM
         *   拦截）会通过 [setStartFailureListener] 异步回调；成功进入前台后由
         *   [setStartReadyListener] 通知调用方，再取投影。
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
            // Android 13+ 缺通知权限时，部分 ROM 的前台服务会因「无法展示通知」而拒绝登记；
            // 提前拦下，避免走 startForegroundService() 后落到 5 秒超时那套崩溃路径。
            // （Android 13 以下通知无需运行时权限，默认视为通过）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
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
