package com.example.hamkit.data.sstv

import android.content.Context
import android.content.SharedPreferences

/**
 * SSTV 设置持久化（与 `Ft8SettingsStore` 同样的轻量方案）。
 */
class SstvSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sstv_settings", Context.MODE_PRIVATE)

    /**
     * 手动锁定的模式；为空表示使用 VIS 自动识别。
     *
     * 弱信号下 VIS 识别失败率不低，手动锁定是必需的兜底手段。
     */
    var manualModeName: String?
        get() = prefs.getString(KEY_MANUAL_MODE, null)
        set(value) = prefs.edit().putString(KEY_MANUAL_MODE, value).apply()

    /** 手动锁定的模式对象（名称失效时回退为自动）。 */
    val manualMode: SstvMode?
        get() = manualModeName?.let { name -> SstvMode.entries.find { it.name == name } }

    /** 收完一帧后自动保存到图库。 */
    var autoSave: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SAVE, value).apply()

    /** 是否在接收过程中显示实时预览（关闭可省电）。 */
    var livePreview: Boolean
        get() = prefs.getBoolean(KEY_LIVE_PREVIEW, true)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_PREVIEW, value).apply()

    companion object {
        private const val KEY_MANUAL_MODE = "manual_mode"
        private const val KEY_AUTO_SAVE = "auto_save"
        private const val KEY_LIVE_PREVIEW = "live_preview"
    }
}
