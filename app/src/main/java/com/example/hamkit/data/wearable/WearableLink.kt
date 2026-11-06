package com.example.hamkit.data.wearable

import android.content.Context
import android.util.Log

/**
 * 手机 ↔ 手环 quick app 的通信抽象。
 *
 * 存在的意义：厂商 SDK（`xms-wearable-lib`）是本地 AAR，缺文件时整个工程会编译失败。
 * 把实现收敛到 [XmsWearableLink] 一个文件，其余代码只依赖本接口，
 * 将来 SDK 升级/替换时改动面最小。
 */
interface WearableLink {

    /** 是否已经解析到手环节点（不代表快应用在前台） */
    val connected: Boolean

    /** 建立/刷新与手环的连接（幂等，可重复调用） */
    fun connect()

    /** 释放监听（进程退出或功能关闭时调用） */
    fun disconnect()

    /**
     * 发一条消息给手环快应用。
     *
     * 实现内部会先尝试把手环应用拉到前台再发送；`onResult(true)` 只代表
     * "消息已交给穿戴服务"，不代表快应用已经展示（那要靠 ack 回执）。
     */
    fun send(json: String, onResult: (Boolean) -> Unit = {})

    /**
     * 直接向手环推一条系统通知（不依赖快应用是否在前台）。
     *
     * 用作快应用不可达时的兜底：后台白名单不含 interconnect，
     * 快应用退到后台就收不到 [send] 的消息，但手环系统通知仍然能弹。
     */
    fun sendNotification(title: String, content: String, onResult: (Boolean) -> Unit = {})

    /**
     * 在前台调用：向手环申请通知 / 设备管理权限。
     *
     * 必须在有 UI 的时机调（例如用户在设置页打开开关时），
     * 后台线程里 `requestPermission` 弹不出授权框。
     */
    fun requestPermissions()

    /** 探测手环上是否安装了本 App 的快应用；结果会缓存供 [connected] 之外的状态展示用 */
    fun probeAppInstalled(onResult: (Boolean) -> Unit)

    /** 注册手环上行消息（hello / ack）回调 */
    fun setInboundListener(listener: ((WearableInbound) -> Unit)?)
}

/** SDK 不可用时的空实现：只打日志，绝不让调用方崩溃 */
internal class NoopWearableLink : WearableLink {

    override val connected: Boolean = false

    override fun connect() {
        Log.i(TAG, "wearable sdk unavailable, skip connect")
    }

    override fun disconnect() = Unit

    override fun send(json: String, onResult: (Boolean) -> Unit) {
        Log.i(TAG, "wearable sdk unavailable, drop message: ${json.take(80)}")
        onResult(false)
    }

    override fun sendNotification(title: String, content: String, onResult: (Boolean) -> Unit) {
        Log.i(TAG, "wearable sdk unavailable, drop notify: $title")
        onResult(false)
    }

    override fun requestPermissions() = Unit

    override fun probeAppInstalled(onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    override fun setInboundListener(listener: ((WearableInbound) -> Unit)?) = Unit

    private companion object {
        const val TAG = "WearableLink"
    }
}

/**
 * 单例工厂。
 *
 * 用类名探测而不是硬编码开关：AAR 没放进 `app/libs/` 时返回空实现，
 * 其余功能（手机通知）照常工作，不会因为一个可选能力把主流程拖挂。
 */
object WearableLinkFactory {

    private const val SDK_ENTRY_CLASS = "com.xiaomi.xms.wearable.Wearable"

    @Volatile
    private var instance: WearableLink? = null

    /** 探测结果只算一次，避免每次发提醒都做类加载 */
    private val sdkPresent: Boolean by lazy {
        runCatching { Class.forName(SDK_ENTRY_CLASS) }.isSuccess
    }

    fun get(context: Context): WearableLink {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                val created: WearableLink = if (sdkPresent) {
                    runCatching { XmsWearableLink(appContext) }
                        .getOrElse {
                            Log.w("WearableLink", "create XmsWearableLink failed", it)
                            NoopWearableLink()
                        }
                } else {
                    NoopWearableLink()
                }
                instance = created
                created
            }
        }
    }
}
