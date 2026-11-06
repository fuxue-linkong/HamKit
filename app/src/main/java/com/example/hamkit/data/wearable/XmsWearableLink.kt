package com.example.hamkit.data.wearable

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener

/**
 * 小米穿戴开放能力（`xms-wearable-lib`）的适配层。
 *
 * 这是**唯一**直接依赖厂商 SDK 的文件：SDK 升级或换实现时只改这里。
 * 用到的 API（已从 AAR `1.4` 反编译确认）：
 *
 * - `Wearable.getNodeApi(ctx).connectedNodes` → 已配对手环节点列表
 * - `Wearable.getMessageApi(ctx).sendMessage(nodeId, byte[])` → 发给手环快应用
 * - `Wearable.getMessageApi(ctx).addListener(nodeId, listener)` → 收手环上行消息
 * - `Wearable.getNotifyApi(ctx).sendNotify(nodeId, title, content)` → 手环系统通知（兜底）
 * - `Wearable.getAuthApi(ctx).checkPermission / requestPermission(nodeId, NOTIFY)`
 *
 * 通信前提（官方文档明确要求）：手环 rpk 的 `manifest.package` 必须等于本 App 的
 * `applicationId`，且 rpk 签名证书必须与本 App 的签名证书一致，否则连接会被拒绝。
 */
class XmsWearableLink(context: Context) : WearableLink {

    private val appContext: Context = context.applicationContext

    // 延迟到首次使用再取 API 对象：应用启动阶段不碰手环服务，避免拖慢冷启动
    private val nodeApi by lazy { Wearable.getNodeApi(appContext) }
    private val messageApi by lazy { Wearable.getMessageApi(appContext) }
    private val serviceApi by lazy { Wearable.getServiceApi(appContext) }
    private val notifyApi by lazy { Wearable.getNotifyApi(appContext) }
    private val authApi by lazy { Wearable.getAuthApi(appContext) }

    /** 当前配对手环的节点 id；null 表示尚未连上 */
    @Volatile
    private var nodeId: String? = null

    /** 已经注册过消息监听的节点 id，避免重复 addListener */
    @Volatile
    private var listenerNodeId: String? = null

    /** 通知权限只问一次，避免每次提醒都弹授权 */
    @Volatile
    private var notifyPermissionAsked = false

    private var inboundListener: ((WearableInbound) -> Unit)? = null

    @Volatile
    private var serviceListenerRegistered = false

    /**
     * 待发送队列。
     *
     * 为什么需要：闹钟会唤醒已死的进程，此时 [connect] 里的节点查询是异步的，
     * 第一次 [send] 时 `nodeId` 必然还是 null。若直接判失败，就会误走"系统通知兜底"，
     * 手环端明明装好了却永远收不到消息。这里先入队，节点就绪后立即补发；
     * 超过 [NODE_WAIT_MS] 仍未连上才算真失败，再交给调用方降级。
     */
    private data class Pending(val json: String, val onResult: (Boolean) -> Unit)

    private val pendingLock = Any()
    private val pendingMessages = mutableListOf<Pending>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val failPendingTask = Runnable { failPending() }

    private val serviceListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() {
            Log.i(TAG, "wearable service connected")
            refreshNode()
        }

        override fun onServiceDisconnected() {
            Log.i(TAG, "wearable service disconnected")
            nodeId = null
            listenerNodeId = null
        }
    }

    override fun connect() {
        runCatching {
            if (!serviceListenerRegistered) {
                serviceApi.registerServiceConnectionListener(serviceListener)
                serviceListenerRegistered = true
            }
        }.onFailure { Log.w(TAG, "register service listener failed", it) }

        refreshNode()
    }

    override fun disconnect() {
        val listened = listenerNodeId
        if (listened != null) {
            runCatching { messageApi.removeListener(listened) }
                .onFailure { Log.w(TAG, "removeListener failed", it) }
        }
        listenerNodeId = null

        runCatching {
            if (serviceListenerRegistered) {
                serviceApi.unregisterServiceConnectionListener(serviceListener)
            }
        }
        serviceListenerRegistered = false
        nodeId = null

        // 显式关闭时把队列里没发出去的消息按失败回调出去，避免调用方一直等降级
        mainHandler.removeCallbacks(failPendingTask)
        failPending()
    }

    override fun send(json: String, onResult: (Boolean) -> Unit) {
        val id = nodeId
        if (id == null) {
            // 节点还没查回来：入队等 onNodes 再发，避免第一次提醒因为"没连上"被误判失败
            enqueuePending(json, onResult)
            refreshNode()
            return
        }
        deliver(id, json, onResult)
    }

    /** 真正调用 SDK 发送；回调保证只触发一次 */
    private fun deliver(id: String, json: String, onResult: (Boolean) -> Unit) {
        runCatching {
            messageApi.sendMessage(id, json.toByteArray(Charsets.UTF_8))
                .addOnSuccessListener { onResult(true) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "sendMessage failed: ${e.message}")
                    onResult(false)
                }
        }.onFailure {
            Log.w(TAG, "sendMessage threw", it)
            onResult(false)
        }
    }

    private fun enqueuePending(json: String, onResult: (Boolean) -> Unit) {
        synchronized(pendingLock) {
            // 队列只用于"进程刚被闹钟唤醒"的瞬时窗口，超过几条说明链路有问题，丢弃最旧的
            if (pendingMessages.size >= MAX_PENDING) {
                val dropped = pendingMessages.removeAt(0)
                dropped.onResult(false)
            }
            if (pendingMessages.isEmpty()) {
                mainHandler.postDelayed(failPendingTask, NODE_WAIT_MS)
            }
            pendingMessages.add(Pending(json, onResult))
        }
    }

    private fun flushPending(id: String) {
        val items = drainPending()
        if (items.isEmpty()) return
        mainHandler.removeCallbacks(failPendingTask)
        Log.i(TAG, "node ready, flushing ${items.size} pending message(s)")
        items.forEach { deliver(id, it.json, it.onResult) }
    }

    private fun failPending() {
        val items = drainPending()
        if (items.isEmpty()) return
        Log.w(TAG, "wearable not ready after ${NODE_WAIT_MS}ms, drop ${items.size} message(s)")
        items.forEach { it.onResult(false) }
    }

    private fun drainPending(): List<Pending> = synchronized(pendingLock) {
        val copy = pendingMessages.toList()
        pendingMessages.clear()
        copy
    }

    override fun sendNotification(title: String, content: String, onResult: (Boolean) -> Unit) {
        val id = nodeId
        if (id == null) {
            refreshNode()
            onResult(false)
            return
        }

        runCatching {
            notifyApi.sendNotify(id, title, content)
                .addOnSuccessListener { status -> onResult(status?.isSuccess == true) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "sendNotify failed: ${e.message}")
                    onResult(false)
                }
        }.onFailure {
            Log.w(TAG, "sendNotify threw", it)
            onResult(false)
        }
    }

    override fun setInboundListener(listener: ((WearableInbound) -> Unit)?) {
        inboundListener = listener
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private fun refreshNode() {
        runCatching {
            nodeApi.connectedNodes
                .addOnSuccessListener { nodes -> onNodes(nodes) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "getConnectedNodes failed: ${e.message}")
                }
        }.onFailure { Log.w(TAG, "query nodes threw", it) }
    }

    private fun onNodes(nodes: List<Node>?) {
        val node = nodes?.firstOrNull()
        if (node == null) {
            Log.i(TAG, "no paired wearable node")
            return
        }

        nodeId = node.id
        Log.i(TAG, "wearable node ready: ${node.name}")

        registerMessageListener(node.id)
        ensureNotifyPermission(node.id)
        // 节点就绪：把"进程刚醒、还没查到节点"时入队的消息补发出去
        flushPending(node.id)
    }

    private fun registerMessageListener(id: String) {
        if (listenerNodeId == id) return

        runCatching {
            messageApi.addListener(
                id,
                OnMessageReceivedListener { from, data ->
                    val msg = WearableInbound.decodeBytes(data)
                    if (msg != null) {
                        Log.i(TAG, "inbound from $from: ${msg.t} ${msg.id ?: ""}")
                        inboundListener?.invoke(msg)
                    } else {
                        Log.w(TAG, "undecodable inbound message (${data.size} bytes)")
                    }
                }
            ).addOnFailureListener { e -> Log.w(TAG, "addListener failed: ${e.message}") }

            listenerNodeId = id
        }.onFailure { Log.w(TAG, "addListener threw", it) }
    }

    /**
     * 兜底通知需要 `Permission.NOTIFY`。
     *
     * 首次连接时检查一次；未授权则请求（手环上会弹授权框）。
     * 这里不阻塞主流程：拿不到权限只是少了兜底，主链路（快应用消息）不受影响。
     */
    private fun ensureNotifyPermission(id: String) {
        if (notifyPermissionAsked) return
        notifyPermissionAsked = true

        runCatching {
            authApi.checkPermission(id, Permission.NOTIFY)
                .addOnSuccessListener { granted ->
                    if (granted != true) {
                        Log.i(TAG, "notify permission not granted, request it")
                        runCatching { authApi.requestPermission(id, Permission.NOTIFY) }
                            .onFailure { Log.w(TAG, "requestPermission threw", it) }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "checkPermission failed: ${e.message}")
                }
        }.onFailure { Log.w(TAG, "permission flow threw", it) }
    }

    private companion object {
        const val TAG = "XmsWearableLink"

        /** 进程刚被闹钟唤醒时，等待"查到手环节点"的最长时间 */
        const val NODE_WAIT_MS = 4_000L

        /** 待发队列上限：只覆盖瞬时窗口，堆多了说明链路本身有问题 */
        const val MAX_PENDING = 5
    }
}
