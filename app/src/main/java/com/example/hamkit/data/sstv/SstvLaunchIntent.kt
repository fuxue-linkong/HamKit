package com.example.hamkit.data.sstv

import android.content.Intent

/**
 * 「过境提醒 → SSTV 接收态」的 Intent 契约（二期 T8）。
 *
 * 通知侧（[com.example.hamkit.data.reminder.ReminderNotificationHelper]）负责写，
 * Activity 侧（[com.example.hamkit.ui.MainActivity]）负责读。抽成独立对象是为了：
 * - 两侧共用同一组 key，不会因为字符串笔误而静默失效（这类 bug 只会在真机点通知时暴露）；
 * - 读写都在一处，便于单元测试直接断言，不必启动 Activity 或真的发一条通知。
 *
 * 不写入 `action`：MainActivity 已有 `MAIN`/`LAUNCHER` 的 intent-filter，加自定义 action
 * 只会让 `PendingIntent` 的 `filterEquals` 语义变复杂，而对「同一个 Activity 换一组 extra」
 * 这件事没有帮助。
 */
object SstvLaunchIntent {

    /** 置 true 表示「进入 SSTV 页后立即开始接收」（过境提醒直达接收态）。 */
    const val EXTRA_AUTO_START = "com.example.hamkit.extra.SSTV_AUTO_START"

    /** 预置锁定的 SSTV 模式名（如 `PD_120`）；缺省表示交由 VIS 自动识别。 */
    const val EXTRA_PRESET_MODE = "com.example.hamkit.extra.SSTV_PRESET_MODE"

    /**
     * 写入「直达接收态」请求。
     *
     * @param presetModeName 预置模式名，null 表示不预置（仍可自动开始）
     */
    fun putLaunchRequest(intent: Intent, presetModeName: String? = null): Intent = intent.apply {
        putExtra(EXTRA_AUTO_START, true)
        if (presetModeName != null) putExtra(EXTRA_PRESET_MODE, presetModeName)
    }

    /** 该 Intent 是否要求直达接收态。 */
    fun isAutoStart(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true

    /** 该 Intent 携带的预置模式名（未携带时为 null）。 */
    fun presetModeName(intent: Intent?): String? = intent?.getStringExtra(EXTRA_PRESET_MODE)
}

/**
 * 一次「直达 SSTV 接收态」的导航请求。
 *
 * 由 [SstvLaunchIntent] 从 Activity 的 Intent 中解析而来，交给 Compose 侧消费一次即清空。
 */
data class SstvLaunchRequest(
    /** 预置模式名；null 表示交由 VIS 自动识别。 */
    val presetModeName: String?,
    /** 是否进入页面后立即开始接收。 */
    val autoStart: Boolean,
)
