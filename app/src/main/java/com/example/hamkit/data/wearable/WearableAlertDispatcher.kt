package com.example.hamkit.data.wearable

import android.content.Context
import android.util.Log
import com.example.hamkit.data.reminder.ReminderItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 把「卫星即将过境」推送到手环。
 *
 * 职责边界：
 * - 组装协议、去重、过期丢弃、失败降级（快应用不可达 → 手环系统通知）
 * - 处理手环上行消息：`hello` 补发最近一条未过期提醒，`ack` 只记日志
 *
 * 不做调度：什么时候发由 [com.example.hamkit.data.reminder.ReminderScheduler]
 * 注册的闹钟决定，本类只负责"到了这一刻怎么把消息送到手环"。
 */
class WearableAlertDispatcher private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val link: WearableLink = WearableLinkFactory.get(appContext)

    /** 最近一条已下发的提醒，用于手环 `hello` 握手时补发 */
    @Volatile
    private var lastAlert: WearableAlert? = null

    /** 去重键：同一次过境只发一次（闹钟可能因重启/补调度重复触发） */
    @Volatile
    private var lastSentKey: String? = null

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        link.setInboundListener { msg -> handleInbound(msg) }
        link.connect()
    }

    fun stop() {
        if (!started) return
        started = false
        link.setInboundListener(null)
        link.disconnect()
    }

    /**
     * 下发一条过境提醒。
     *
     * 调用方（[com.example.hamkit.data.reminder.ReminderReceiver]）已经确认
     * `settings.wearableEnabled == true`，这里不再重复判断开关。
     */
    fun dispatch(item: ReminderItem) {
        val now = System.currentTimeMillis()

        // AOS 已经过去超过 2 分钟：迟到太久的提醒没有意义，直接丢弃
        if (now - item.aosTimeMillis > LATE_TOLERANCE_MS) {
            Log.i(TAG, "skip stale alert for ${item.name}")
            return
        }

        val alert = WearableAlert(
            id = WearableAlert.makeId(item.catalogNumber, item.aosTimeMillis),
            sat = item.name,
            norad = item.catalogNumber,
            aos = item.aosTimeMillis,
            los = item.losTimeMillis,
            el = item.maxElevation.roundToInt(),
            az = item.aosAzimuth,
            modes = item.modes,
            now = now
        )

        // 同一次过境重复触发（闹钟重排、进程重启后补发）只发一次
        if (alert.id == lastSentKey) {
            Log.i(TAG, "duplicated alert ignored: ${alert.id}")
            return
        }

        start()
        lastAlert = alert
        lastSentKey = alert.id

        val payload = alert.toJson()
        link.send(payload) { ok ->
            if (ok) {
                Log.i(TAG, "alert delivered to quick app: ${alert.sat}")
            } else {
                // 快应用多半不在前台（后台白名单不含 interconnect），
                // 退化成手环系统通知：仍然能震动提醒，只是没有全屏倒计时。
                Log.i(TAG, "quick app unreachable, fallback to system notify")
                sendFallbackNotification(alert)
            }
        }
    }

    /** 手环上行消息：目前只处理握手补发，ack 仅记录 */
    private fun handleInbound(msg: WearableInbound) {
        when (msg.t) {
            WearableInbound.TYPE_HELLO -> {
                val alert = lastAlert
                if (alert == null) {
                    Log.i(TAG, "hello received but no cached alert")
                    return
                }
                // 已经过期很久的不补发，避免手环一打开就弹一条陈年提醒
                if (System.currentTimeMillis() - alert.aos > LATE_TOLERANCE_MS) {
                    Log.i(TAG, "hello received, cached alert expired")
                    return
                }
                Log.i(TAG, "hello received, resend ${alert.sat}")
                link.send(alert.toJson()) { }
            }

            WearableInbound.TYPE_ACK -> {
                Log.i(TAG, "watch ack: ${msg.act} for ${msg.id}")
            }

            else -> Log.i(TAG, "unknown inbound type: ${msg.t}")
        }
    }

    private fun sendFallbackNotification(alert: WearableAlert) {
        val time = TIME_FORMATTER.format(Instant.ofEpochMilli(alert.aos))
        val title = "${alert.sat} 即将过境"
        val content = buildString {
            append("AOS ").append(time)
            if (alert.el > 0) append(" · 仰角 ").append(alert.el).append("°")
        }
        link.sendNotification(title, content) { ok ->
            Log.i(TAG, "fallback notify result: $ok")
        }
    }

    companion object {
        private const val TAG = "WearableAlert"

        /** 迟到容忍窗口：超过这个时间的提醒不再下发/补发 */
        private const val LATE_TOLERANCE_MS = 2 * 60 * 1000L

        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        @Volatile
        private var instance: WearableAlertDispatcher? = null

        fun get(context: Context): WearableAlertDispatcher {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: WearableAlertDispatcher(context).also { instance = it }
            }
        }
    }
}
