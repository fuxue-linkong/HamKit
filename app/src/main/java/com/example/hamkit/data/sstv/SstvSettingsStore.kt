package com.example.hamkit.data.sstv

import android.content.Context
import android.content.SharedPreferences

/**
 * SSTV 设置持久化（与 `Ft8SettingsStore` 同样的轻量方案）。
 *
 * 二期新增的开关都遵循同一条原则：**默认值取「不加处理、不改变既有行为」**。
 * 后处理三项默认全关（处理不可逆，用户可能想要原始结果），连续接收默认关闭
 * （它会持续占用麦克风与内存），自适应积分窗默认开启（只影响弱信号下的噪声，
 * 强信号下与一期行为完全一致）。
 */
class SstvSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sstv_settings", Context.MODE_PRIVATE)

    // ══════════════════════════════════════════════════════════════════
    // 接收
    // ══════════════════════════════════════════════════════════════════

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

    /**
     * 连续接收：收满一帧后保留模式与失谐量、就地接续下一帧。
     *
     * 面向 ISS 的 ARISS 活动（常连发 2–4 张）与固定台长时间守听。
     */
    var continuous: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS, value).apply()

    /**
     * 持续接收（SSTV 三期 R1）：不要求 VIS 头、不等帧边界，从任意时刻起一直解，
     * 预览像「卷纸」一样不断向下延长。
     *
     * 与 [continuous] 的区别：连续接收是「一帧接一帧地收」，仍以帧为单位保存；
     * 持续接收是「一直收」，没有帧的概念，退出时才把整段（或最近若干帧）落盘。
     * 默认关闭：它会持续占用麦克风与 CPU，属于用户显式开启的模式。
     */
    var persistent: Boolean
        get() = prefs.getBoolean(KEY_PERSISTENT, false)
        set(value) = prefs.edit().putBoolean(KEY_PERSISTENT, value).apply()

    /** 连续接收的最大帧数（内存与时长保护）。 */
    var maxFrames: Int
        get() = prefs.getInt(KEY_MAX_FRAMES, SstvRecorder.DEFAULT_MAX_FRAMES)
        set(value) = prefs.edit().putInt(KEY_MAX_FRAMES, value.coerceIn(2, 20)).apply()

    /**
     * 弱信号自适应积分窗（见 [SstvSpanController]）。
     *
     * 默认开启：强信号下选出的窗与一期完全相同，因此不存在「开了反而变差」的风险；
     * 保留开关是为了在极少数情况下（例如用户更看重水平锐度）能退回一期行为。
     */
    var adaptiveSpan: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_SPAN, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_SPAN, value).apply()

    /** VIS 失败时自动盲扫（见 [SstvBlindScan]）。 */
    var blindScanOnFailure: Boolean
        get() = prefs.getBoolean(KEY_BLIND_SCAN, true)
        set(value) = prefs.edit().putBoolean(KEY_BLIND_SCAN, value).apply()

    /**
     * 像素级零相位低通（见 [SstvDecoder] 的 `pixelNyquistFilter`）。
     *
     * 默认开启。实测收益与模式有关：像素 Nyquist 低于解调器基带带宽（1200 Hz）的慢模式
     * （如 Scottie DX 的 463 Hz）收益明显，而快模式（PD-120 的 2632 Hz）几乎无影响、
     * 也不会劣化。留开关是为了极端偏好锐度时能退回。
     */
    var pixelNyquistFilter: Boolean
        get() = prefs.getBoolean(KEY_PIXEL_FILTER, true)
        set(value) = prefs.edit().putBoolean(KEY_PIXEL_FILTER, value).apply()

    /** 接收过程中显示解调信号的瀑布图。 */
    var waterfall: Boolean
        get() = prefs.getBoolean(KEY_WATERFALL, true)
        set(value) = prefs.edit().putBoolean(KEY_WATERFALL, value).apply()

    /**
     * 接收时把原始音频录成 WAV。
     *
     * 默认开启：录下来的文件带 VIS 头，可以直接用「导入录音」重新解码，
     * 既能挽救当时没解好的帧，也为工程积累真实素材。一帧 PD-120 约 12 MB。
     */
    var recordAudio: Boolean
        get() = prefs.getBoolean(KEY_RECORD_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_RECORD_AUDIO, value).apply()

    /** 音源选择（见 [SstvAudioSource]）：`MIC` 或 `PLAYBACK`。 */
    var audioSourceKind: String
        get() = prefs.getString(KEY_AUDIO_SOURCE, AUDIO_SOURCE_MIC) ?: AUDIO_SOURCE_MIC
        set(value) = prefs.edit().putString(KEY_AUDIO_SOURCE, value).apply()

    // ══════════════════════════════════════════════════════════════════
    // 后处理
    // ══════════════════════════════════════════════════════════════════

    /** 直方图拉伸（修复自动增益压缩导致的灰蒙蒙）。 */
    var histogramStretch: Boolean
        get() = prefs.getBoolean(KEY_HISTOGRAM, false)
        set(value) = prefs.edit().putBoolean(KEY_HISTOGRAM, value).apply()

    /** 3×3 中值滤波（去脉冲噪点）。 */
    var medianFilter: Boolean
        get() = prefs.getBoolean(KEY_MEDIAN, false)
        set(value) = prefs.edit().putBoolean(KEY_MEDIAN, value).apply()

    /** 坏行修复（同步丢失导致的纯色行用相邻行插值）。 */
    var repairBadLines: Boolean
        get() = prefs.getBoolean(KEY_REPAIR_LINES, false)
        set(value) = prefs.edit().putBoolean(KEY_REPAIR_LINES, value).apply()

    /** 当前后处理组合。 */
    val postProcess: SstvPostProcess.Options
        get() = SstvPostProcess.Options(
            histogramStretch = histogramStretch,
            medianFilter = medianFilter,
            repairBadLines = repairBadLines,
        )

    /** 一次性写入整套后处理开关（设置页的三连开关用）。 */
    fun setPostProcess(options: SstvPostProcess.Options) {
        prefs.edit()
            .putBoolean(KEY_HISTOGRAM, options.histogramStretch)
            .putBoolean(KEY_MEDIAN, options.medianFilter)
            .putBoolean(KEY_REPAIR_LINES, options.repairBadLines)
            .apply()
    }

    // ══════════════════════════════════════════════════════════════════
    // 发射
    // ══════════════════════════════════════════════════════════════════

    /** 发射页上次选用的模式（默认 PD-120：ARISS 现行 ISS 活动的模式）。 */
    var txModeName: String
        get() = prefs.getString(KEY_TX_MODE, SstvMode.PD_120.name) ?: SstvMode.PD_120.name
        set(value) = prefs.edit().putString(KEY_TX_MODE, value).apply()

    /** 发射页上次选用的模式对象（名称失效时回退 PD-120）。 */
    val txMode: SstvMode
        get() = SstvMode.entries.find { it.name == txModeName } ?: SstvMode.PD_120

    /**
     * 发射前是否叠加呼号。
     *
     * 合规与惯例上，图像里带上自己的呼号便于对方确认来源。
     */
    var txOverlayCallsign: Boolean
        get() = prefs.getBoolean(KEY_TX_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_TX_OVERLAY, value).apply()

    /** 叠加用的呼号文本（默认留空，由用户在发射页填写）。 */
    var callsign: String
        get() = prefs.getString(KEY_CALLSIGN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALLSIGN, value.trim().uppercase()).apply()

    companion object {
        private const val KEY_MANUAL_MODE = "manual_mode"
        private const val KEY_AUTO_SAVE = "auto_save"
        private const val KEY_LIVE_PREVIEW = "live_preview"
        private const val KEY_CONTINUOUS = "continuous"
        private const val KEY_MAX_FRAMES = "max_frames"
        private const val KEY_PERSISTENT = "persistent_receive"
        private const val KEY_ADAPTIVE_SPAN = "adaptive_span"
        private const val KEY_BLIND_SCAN = "blind_scan"
        private const val KEY_PIXEL_FILTER = "pixel_nyquist_filter"
        private const val KEY_WATERFALL = "waterfall"
        private const val KEY_RECORD_AUDIO = "record_audio"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_HISTOGRAM = "post_histogram"
        private const val KEY_MEDIAN = "post_median"
        private const val KEY_REPAIR_LINES = "post_repair_lines"
        private const val KEY_TX_MODE = "tx_mode"
        private const val KEY_TX_OVERLAY = "tx_overlay"
        private const val KEY_CALLSIGN = "callsign"

        /** 音源标识：麦克风。 */
        const val AUDIO_SOURCE_MIC = "MIC"

        /** 音源标识：播放捕获（Android 10+）。 */
        const val AUDIO_SOURCE_PLAYBACK = "PLAYBACK"
    }
}
