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
 * - `Wearable.getNodeApi(ctx).launchWearApp(nodeId, package)` → **把手环快应用拉到前台**
 * - `Wearable.getNodeApi(ctx).isWearAppInstalled(package)` → 手环上装没装
 * - `Wearable.getMessageApi(ctx).sendMessage(nodeId, byte[])` → 发给手环快应用
 * - `Wearable.getMessageApi(ctx).addListener(nodeId, listener)` → 收手环上行消息
 * - `Wearable.getNotifyApi(ctx).sendNotify(nodeId, title, content)` → 手环系统通知（兜底）
 * - `Wearable.getAuthApi(ctx).checkPermission / requestPermission(nodeId, permission)`
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

    /** 权限只对同一节点申请一次，避免每次提醒都弹授权 */
    @Volatile
    private var permissionRequestedNode: String? = null

    /**
     * 是否允许在 [onNodes] 回调里直接申请权限。
     *
     * 只有用户在前台点了开关（[requestPermissions]）才置 true；
     * 闹钟从后台唤醒时只检查不申请 —— 后台弹不出授权框，还会打一堆失败日志。
     */
    @Volatile
    private var allowPermissionRequest = false

    /** 快应用是否已安装（探测结果缓存，探测失败时为 null 表示未知） */
    @Volatile
    private var appInstalled: Boolean? = null

    @Volatile
    private var serviceListenerRegistered = false

    private var inboundListener: ((WearableInbound) -> Unit)? = null

    /** 待发送动作的类型 */
    private enum class Kind { MESSAGE, NOTIFICATION }

    private data class Pending(
        val kind: Kind,
        val payload: String,
        val title: String,
        val onResult: (Boolean) -> Unit,
    )

    /**
     * 待发送队列。
     *
     * 为什么需要：闹钟会唤醒已死的进程，此时 [connect] 里的节点查询是异步的，
     * 第一次 [send] 时 `nodeId` 必然还是 null。若直接判失败，就会误走"系统通知兜底"，
     * 手环端明明装好了却永远收不到消息。这里先入队，节点就绪后立即补发；
     * 超过 [NODE_WAIT_MS] 仍未连上才算真失败，再交给调用方降级。
     *
     * 通知也走同一个队列：否则冷启动时 `sendNotification` 会因为 nodeId 为空直接失败，
     * 兜底本身也失效（这正是"到点什么都没有"的第二个原因）。
     */
    private val pendingLock = Any()
    private val pendingActions = mutableListOf<Pending>()
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

    override val connected: Boolean get() = nodeId != null

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
        allowPermissionRequest = false

        // 显式关闭时把队列里没发出去的动作按失败回调出去，避免调用方一直等降级
        mainHandler.removeCallbacks(failPendingTask)
        failPending()
    }

    override fun send(json: String, onResult: (Boolean) -> Unit) {
        val id = nodeId
        if (id == null) {
            // 节点还没查回来：入队等 onNodes 再发，避免第一次提醒因为"没连上"被误判失败
            enqueue(Kind.MESSAGE, json, "", onResult)
            refreshNode()
            return
        }
        deliverMessage(id, json, onResult)
    }

    override fun sendNotification(title: String, content: String, onResult: (Boolean) -> Unit) {
        val id = nodeId
        if (id == null) {
            enqueue(Kind.NOTIFICATION, content, title, onResult)
            refreshNode()
            return
        }
        deliverNotification(id, title, content, onResult)
    }

    override fun requestPermissions() {
        allowPermissionRequest = true
        val id = nodeId
        if (id == null) {
            // 还没解析到节点：refreshNode 之后的 onNodes 会自动走权限申请
            refreshNode()
            return
        }
        requestPermissionsFor(id)
    }

    override fun probeAppInstalled(onResult: (Boolean) -> Unit) {
        val id = nodeId
        if (id == null) {
            refreshNode()
            onResult(appInstalled == true)
            return
        }

        runCatching {
            nodeApi.isWearAppInstalled(WATCH_PACKAGE)
                .addOnSuccessListener { installed ->
                    appInstalled = installed
                    Log.i(TAG, "wear app installed = $installed")
                    onResult(installed)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "isWearAppInstalled failed: ${e.message}")
                    onResult(appInstalled == true)
                }
        }.onFailure {
            Log.w(TAG, "isWearAppInstalled threw", it)
            onResult(appInstalled == true)
        }
    }

    override fun setInboundListener(listener: ((WearableInbound) -> Unit)?) {
        inboundListener = listener
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    /**
     * 发消息给快应用。
     *
     * 先 [launchApp] 把手环应用拉到前台，等 [LAUNCH_GRACE_MS] 再发：
     * 快应用冷启动需要时间注册 interconnect 通道，立刻发会丢消息。
     * 应用已经在前台时，多等这一小会儿对 60 秒倒计时没有影响。
     */
    private fun deliverMessage(id: String, json: String, onResult: (Boolean) -> Unit) {
        launchApp(id)
        mainHandler.postDelayed({
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
        }, LAUNCH_GRACE_MS)
    }

    private fun deliverNotification(
        id: String,
        title: String,
        content: String,
        onResult: (Boolean) -> Unit,
    ) {
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

    /**
     * 把手环快应用拉到前台。
     *
     * 这是"应用不在前台就收不到消息"的解药：Vela 的后台运行白名单不含 interconnect，
     * 但手机端可以主动拉起应用，再由应用发 `hello` 换回补发的提醒。
     * 失败只记日志——不同固件对该能力的支持程度不一样，不能让它影响后续发送。
     */
    private fun launchApp(id: String) {
        runCatching {
            nodeApi.launchWearApp(id, WATCH_PACKAGE)
                .addOnSuccessListener { Log.i(TAG, "launchWearApp ok ($WATCH_PACKAGE)") }
                .addOnFailureListener { e -> Log.w(TAG, "launchWearApp failed: ${e.message}") }
        }.onFailure { Log.w(TAG, "launchWearApp threw", it) }
    }

    // ── 队列 ────────────────────────────────────────────────────────────────

    private fun enqueue(kind: Kind, payload: String, title: String, onResult: (Boolean) -> Unit) {
        synchronized(pendingLock) {
            // 队列只用于"进程刚被闹钟唤醒"的瞬时窗口，超过几条说明链路有问题，丢弃最旧的
            if (pendingActions.size >= MAX_PENDING) {
                val dropped = pendingActions.removeAt(0)
                dropped.onResult(false)
            }
            if (pendingActions.isEmpty()) {
                mainHandler.postDelayed(failPendingTask, NODE_WAIT_MS)
            }
            pendingActions.add(Pending(kind, payload, title, onResult))
        }
    }

    private fun flushPending(id: String) {
        val items = drainPending()
        if (items.isEmpty()) return
        mainHandler.removeCallbacks(failPendingTask)
        Log.i(TAG, "node ready, flushing ${items.size} pending action(s)")
        items.forEach { item ->
            when (item.kind) {
                Kind.MESSAGE -> deliverMessage(id, item.payload, item.onResult)
                Kind.NOTIFICATION -> deliverNotification(id, item.title, item.payload, item.onResult)
            }
        }
    }

    private fun failPending() {
        val items = drainPending()
        if (items.isEmpty()) return
        Log.w(TAG, "wearable not ready, drop ${items.size} action(s)")
        items.forEach { it.onResult(false) }
    }

    private fun drainPending(): List<Pending> = synchronized(pendingLock) {
        val copy = pendingActions.toList()
        pendingActions.clear()
        copy
    }

    // ── 节点 & 权限 ─────────────────────────────────────────────────────────

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

        if (allowPermissionRequest) {
            requestPermissionsFor(node.id)
        }

        // 节点就绪：把"进程刚醒、还没查到节点"时入队的动作补发出去
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
     * 对同一节点只申请一次权限。
     *
     * 只有在 [allowPermissionRequest] 为 true（用户在前台操作）时才会真正弹授权框；
     * 后台唤醒场景下静默跳过，避免无效调用与噪音日志。
     *
     * NOTIFY 给兜底通知用，DEVICE_MANAGER 给 launchWearApp 用。
     */
    private fun requestPermissionsFor(id: String) {
        if (!allowPermissionRequest) return
        if (permissionRequestedNode == id) return
        permissionRequestedNode = id

        checkAndRequest(id, Permission.NOTIFY)
        checkAndRequest(id, Permission.DEVICE_MANAGER)
    }

    private fun checkAndRequest(id: String, permission: Permission) {
        runCatching {
            authApi.checkPermission(id, permission)
                .addOnSuccessListener { granted ->
                    if (granted != true) {
                        Log.i(TAG, "permission ${permission.name} not granted, request it")
                        runCatching { authApi.requestPermission(id, permission) }
                            .onFailure { Log.w(TAG, "requestPermission threw", it) }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "checkPermission(${permission.name}) failed: ${e.message}")
                }
        }.onFailure { Log.w(TAG, "permission flow threw", it) }
    }

    private companion object {
        const val TAG = "XmsWearableLink"

        /** 手环端快应用的包名，必须与 App 的 applicationId / rpk 的 package 一致 */
        const val WATCH_PACKAGE = "com.example.hamkit"

        /** 进程刚被闹钟唤醒时，等待"查到手环节点"的最长时间（含穿戴服务绑定） */
        const val NODE_WAIT_MS = 12_000L

        /** launchWearApp 之后给快应用冷启动留的时间 */
        const val LAUNCH_GRACE_MS = 1_500L

        /** 待发队列上限：只覆盖瞬时窗口，堆多了说明链路本身有问题 */
        const val MAX_PENDING = 5
    }
}
