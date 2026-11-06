package com.example.hamkit.data.wearable

/**
 * 手环链路的当前状态，供设置页展示。
 *
 * 之所以要把它暴露出来：手环提醒横跨「App → 穿戴服务 → 快应用 → 手环屏幕」四段，
 * 任何一段断了，用户看到的都是"什么都没有"。没有状态就没法排查。
 */
sealed interface WearableStatus {

    /** 用户在设置里关掉了手环提醒 */
    data object Disabled : WearableStatus

    /** 正在连接/正在查手环节点 */
    data object Connecting : WearableStatus

    /** 已连上手环，且手环上装了快应用 */
    data object Connected : WearableStatus

    /** 已连上手环，但手环上没装快应用（rpk 没装或包名不对） */
    data object AppNotInstalled : WearableStatus

    /** 手机侧找不到手环：没配对、或没装/没启动小米运动健康 */
    data object NoDevice : WearableStatus

    /** 最近一次发送失败（reason 直接展示给用户） */
    data class Failed(val reason: String) : WearableStatus
}
