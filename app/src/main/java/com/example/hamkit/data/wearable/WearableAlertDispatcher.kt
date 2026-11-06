package com.example.hamkit.data.wearable

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.hamkit.data.reminder.ReminderItem
import com.example.hamkit.data.reminder.ReminderStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 把「卫星即将过境」推送到手环。
 *
 * 职责边界：
 * - 组装协议、去重、过期丢弃
 * - 发送后等 ack：超时没收到 `shown` 就用**手环系统通知**兜底（不依赖快应用在前台）
 * - 处理手环上行消息：`hello` 补发（内存没有就从 ReminderStore 恢复），`ack` 更新状态
 * - 维护 [WearableStatus] 供设置页展示
 *
 * 不做调度：什么时候发由 [com.example.hamkit.data.reminder.ReminderScheduler]
 * 注册的闹钟决定，本类只负责"到了这一刻怎么把消息送到手环"。
 */
class WearableAlertDispatcher private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val link: WearableLink = WearableLinkFactory.get(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一条已下发的提醒，用于手环 `hello` 握手时补发 */
    @Volatile
    private var lastAlert: WearableAlert? = null

    /** 去重键：同一次过境只发一次（闹钟可能因重启/补调度重复触发） */
    @Volatile
    private var lastSentKey: String? = null

    /** 正在等 ack 的提醒 id；收到 ack 后置空，用于取消兜底 */
    @Volatile
    private var pendingAckId: String? = null

    @Volatile
    private var started = false

    @Volatile
    var status: WearableStatus = WearableStatus.Disabled
        private set

    private var statusListener: ((WearableStatus) -> Unit)? = null

    private val fallbackTask = Runnable { runFallbackIfNoAck() }

    fun start() {
        if (started) return
        started = true
        link.setInboundListener { msg -> handleInbound(msg) }
        link.connect()
        // 状态刷新交给调用方（refreshStatus 会再调 start，放在这里会互相递归）
    }

    fun stop() {
        if (!started) {
            updateStatus(WearableStatus.Disabled)
            return
        }
        started = false
        mainHandler.removeCallbacks(fallbackTask)
        pendingAckId = null
        link.setInboundListener(null)
        link.disconnect()
        updateStatus(WearableStatus.Disabled)
    }

    fun setStatusListener(listener: ((WearableStatus) -> Unit)?) {
        statusListener = listener
        listener?.invoke(status)
    }

    /** 在前台调用：申请手环侧权限（通知 / 设备管理） */
    fun requestPermissions() {
        link.requestPermissions()
    }

    /**
     * 刷新状态：连上了吗？手环上装了快应用吗？
     *
     * 供设置页进入时、以及开关切换后调用。
     */
    fun refreshStatus() {
        val enabled = ReminderStore(appContext).loadSettings().wearableEnabled
        if (!enabled) {
            updateStatus(WearableStatus.Disabled)
            return
        }

        if (!started) start()

        if (!link.connected) {
            updateStatus(WearableStatus.Connecting)
            link.connect()
            // 节点查询是异步的，稍后再看一次；失败会停在 Connecting，由 UI 提示"未连接"
            mainHandler.postDelayed({ probeWhenReady(0) }, NODE_PROBE_DELAY_MS)
            return
        }
        probeWhenReady(0)
    }

    private fun probeWhenReady(attempt: Int) {
        if (!started) return
        if (!link.connected) {
            // 最多重试几轮，给穿戴服务绑定的时间
            if (attempt < NODE_PROBE_RETRY) {
                mainHandler.postDelayed({ probeWhenReady(attempt + 1) }, NODE_PROBE_DELAY_MS)
            } else {
                updateStatus(WearableStatus.NoDevice)
            }
            return
        }

        link.probeAppInstalled { installed ->
            updateStatus(
                if (installed) WearableStatus.Connected else WearableStatus.AppNotInstalled
            )
        }
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

        val alert = toAlert(item, now)

        // 同一次过境重复触发（闹钟重排、进程重启后补发）只发一次
        if (alert.id == lastSentKey) {
            Log.i(TAG, "duplicated alert ignored: ${alert.id}")
            return
        }

        start()
        lastAlert = alert
        lastSentKey = alert.id
        pendingAckId = alert.id
        mainHandler.removeCallbacks(fallbackTask)

        link.send(alert.toJson()) { ok ->
            if (ok) {
                Log.i(TAG, "alert handed to wearable service: ${alert.sat}")
                // 只是"交给服务"，快应用有没有真的弹出来要看 ack；
                // 没 ack 就用系统通知兜底，保证手环一定有反应。
                mainHandler.postDelayed(fallbackTask, ACK_TIMEOUT_MS)
            } else {
                Log.w(TAG, "send failed, fallback to system notify: ${alert.sat}")
                pendingAckId = null
                sendFallbackNotification(alert, "send-failed")
            }
        }
    }

    /** 设置页「发送测试提醒」：AOS 设为 15 秒后，走与真实提醒完全相同的链路 */
    fun sendTestAlert() {
        val now = System.currentTimeMillis()
        val item = ReminderItem(
            catalogNumber = TEST_CATALOG_NUMBER,
            name = "ISS（测试）",
            aosTimeMillis = now + TEST_LEAD_MS,
            losTimeMillis = now + TEST_LEAD_MS + 10 * 60 * 1000L,
            maxElevation = 42.0,
            aosAzimuth = 128,
            losAzimuth = 300,
            modes = listOf("FM"),
            enabled = true
        )
        // 测试提醒不受去重影响：先清掉 key，否则连点两次第二次会被吞
        lastSentKey = null
        dispatch(item)
    }

    /** 手环上行消息 */
    private fun handleInbound(msg: WearableInbound) {
        when (msg.t) {
            WearableInbound.TYPE_HELLO -> onHello()
            WearableInbound.TYPE_ACK -> onAck(msg)
            else -> Log.i(TAG, "unknown inbound type: ${msg.t}")
        }
    }

    private fun onHello() {
        // 应用被拉起/重新打开时握手：把最近一条有效提醒补发过去，
        // 否则手环会停在待命页，用户看到的还是"没提醒"。
        val alert = lastAlert ?: recoverAlertFromStore()
        if (alert == null) {
            Log.i(TAG, "hello received but no alert to resend")
            return
        }

        if (System.currentTimeMillis() - alert.aos > LATE_TOLERANCE_MS) {
            Log.i(TAG, "hello received, cached alert expired")
            return
        }

        Log.i(TAG, "hello received, resend ${alert.sat}")
        lastAlert = alert
        pendingAckId = alert.id
        mainHandler.removeCallbacks(fallbackTask)
        link.send(alert.toJson()) { ok ->
            if (ok) {
                mainHandler.postDelayed(fallbackTask, ACK_TIMEOUT_MS)
            } else {
                pendingAckId = null
                sendFallbackNotification(alert, "hello-resend-failed")
            }
        }
    }

    private fun onAck(msg: WearableInbound) {
        Log.i(TAG, "watch ack: ${msg.act} for ${msg.id}")

        // 收到 ack 说明快应用已经弹出来了，取消兜底通知
        if (msg.id != null && msg.id == pendingAckId) {
            pendingAckId = null
            mainHandler.removeCallbacks(fallbackTask)
        }
        if (msg.act == ACK_SHOWN) {
            updateStatus(WearableStatus.Connected)
        }
    }

    /** ack 超时：快应用多半不在前台，改用系统通知保证手环有反应 */
    private fun runFallbackIfNoAck() {
        val id = pendingAckId ?: return
        val alert = lastAlert
        if (alert == null || alert.id != id) return

        Log.i(TAG, "no ack within ${ACK_TIMEOUT_MS}ms, fallback to system notify")
        pendingAckId = null
        sendFallbackNotification(alert, "ack-timeout")
    }

    private fun sendFallbackNotification(alert: WearableAlert, reason: String) {
        val time = TIME_FORMATTER.format(Instant.ofEpochMilli(alert.aos))
        val title = "${alert.sat} 即将过境"
        val content = buildString {
            append("AOS ").append(time)
            if (alert.el > 0) append(" · 仰角 ").append(alert.el).append("°")
        }
        link.sendNotification(title, content) { ok ->
            Log.i(TAG, "fallback notify ($reason) result: $ok")
            updateStatus(
                if (ok) WearableStatus.Connected else WearableStatus.Failed("通知发送失败")
            )
        }
    }

    // ── 数据 ────────────────────────────────────────────────────────────────

    private fun toAlert(item: ReminderItem, now: Long) = WearableAlert(
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

    /**
     * 内存里没有提醒时（进程被杀过），从持久化的提醒列表里捞一条还在窗口内的。
     *
     * 覆盖场景：手环应用被 `launchWearApp` 拉起 → 发 hello → 手机端冷启动进程里
     * `lastAlert` 是空的 → 不恢复的话手环会停在待命页。
     */
    private fun recoverAlertFromStore(): WearableAlert? {
        val now = System.currentTimeMillis()
        val item = ReminderStore(appContext).loadItems().firstOrNull {
            it.enabled &&
                it.aosTimeMillis > now - LATE_TOLERANCE_MS &&
                it.aosTimeMillis < now + RECOVER_WINDOW_MS
        } ?: return null

        Log.i(TAG, "recovered alert from store: ${item.name}")
        return toAlert(item, now)
    }

    private fun updateStatus(next: WearableStatus) {
        if (status == next) return
        status = next
        // 状态是给 UI 的，统一在主线程回调
        mainHandler.post { statusListener?.invoke(next) }
    }

    companion object {
        private const val TAG = "WearableAlert"

        /** 迟到容忍窗口：超过这个时间的提醒不再下发/补发 */
        private const val LATE_TOLERANCE_MS = 2 * 60 * 1000L

        /** hello 补发时，允许从存储里恢复多久以内的提醒 */
        private const val RECOVER_WINDOW_MS = 10 * 60 * 1000L

        /** 等 ack 的时间：超过它就用系统通知兜底 */
        private const val ACK_TIMEOUT_MS = 8_000L

        /** 节点探测：首次延迟与重试次数 */
        private const val NODE_PROBE_DELAY_MS = 1_500L
        private const val NODE_PROBE_RETRY = 3

        /** 测试提醒：AOS 往前推 15 秒 */
        private const val TEST_LEAD_MS = 15_000L
        private const val TEST_CATALOG_NUMBER = 25544

        private const val ACK_SHOWN = "shown"

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
