package com.example.hamkit.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.sstv.MicrophoneAudioSource
import com.example.hamkit.data.sstv.PlaybackCaptureAudioSource
import com.example.hamkit.data.sstv.SstvAudioSource
import com.example.hamkit.data.sstv.SstvBitmapSource
import com.example.hamkit.data.sstv.SstvBlindScan
import com.example.hamkit.data.sstv.SstvCaptureService
import com.example.hamkit.data.sstv.SstvDecoder
import com.example.hamkit.data.sstv.SstvEncoder
import com.example.hamkit.data.sstv.SstvImageSource
import com.example.hamkit.data.sstv.SstvImageStore
import com.example.hamkit.data.sstv.SstvMediaAudioReader
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.data.sstv.SstvPostProcess
import com.example.hamkit.data.sstv.SstvRecorder
import com.example.hamkit.data.sstv.SstvRecordingStore
import com.example.hamkit.data.sstv.SstvSettingsStore
import com.example.hamkit.data.sstv.SstvDemodulator
import com.example.hamkit.data.sstv.SstvRollingImage
import com.example.hamkit.data.sstv.SstvStreamDecoder
import com.example.hamkit.data.sstv.SstvWaterfall
import com.example.hamkit.data.sstv.SstvWavReader
import com.example.hamkit.data.sstv.SstvWavRecorder
import com.example.hamkit.data.sstv.SstvWavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * SSTV 接收界面的状态。
 *
 * 一期只有接收相关的字段，二期把**图库、离线导入、发射、后处理、盲扫**的状态也
 * 收进同一个 StateFlow：这几条链路共享「当前模式」「当前图像」「图库」这些概念，
 * 拆成多个 ViewModel 反而要来回同步（SSTV 是应用级单例 ViewModel，见
 * `WithApplicationViewModelStoreOwner`）。
 *
 * @param decodedLines 已解码的图像行数（图像自上而下逐步显现）
 * @param totalLines 当前模式的总行数
 * @param bitmap 当前预览图（每次部分解码后重建，靠引用变化触发 Compose 重绘）
 * @param enhancedPreview 是否正在预览后处理结果（原图始终保留在 [SstvViewModel] 内）
 * @param qualityDb 等效信噪比（dB），见 [com.example.hamkit.data.sstv.SstvDemodulator.estimatedQualityDb]
 * @param phaseSpan 本帧选定的相位差分间隔（样本）
 * @param blindCandidates 盲扫候选（按得分降序）；空表示未做盲扫或全部落选
 * @param thumbnails 图库缩略图缓存（文件名 → 位图），在协程中解码
 */
data class SstvUiState(
    // ── 接收 ──
    val isReceiving: Boolean = false,
    val status: String = "点击「开始接收」，把手机靠近电台扬声器",
    val visCode: Int? = null,
    val mode: SstvMode? = null,
    val hedrShiftHz: Double = 0.0,
    val slantRatio: Double = 1.0,
    val decodedLines: Int = 0,
    val totalLines: Int = 0,
    val syncPulseCount: Int = 0,
    val bitmap: Bitmap? = null,
    val savedFileName: String? = null,
    val manualModeName: String? = null,
    /** 当前模式是否由卫星详情页预置（仅供本次接收，不改变默认设置）。 */
    val presetApplied: Boolean = false,
    val autoSave: Boolean = true,
    val livePreview: Boolean = true,
    val continuous: Boolean = false,
    val maxFrames: Int = SstvRecorder.DEFAULT_MAX_FRAMES,
    /** 连续模式下已完成的帧数。 */
    val completedFrames: Int = 0,

    // ── 持续接收（三期 Q2）──
    /** 持续接收开关（不要求 VIS、不等帧边界，画面一直向下延长）。 */
    val persistent: Boolean = false,
    /** 持续接收中已累积的图像行数（「卷纸」长度）。 */
    val rollingRows: Int = 0,
    /** 是否已有行从「卷纸」顶部滚出（此时画面表示的是最近一段，而不是整段）。 */
    val rollingScrolled: Boolean = false,
    val galleryCount: Int = 0,
    val error: String? = null,

    // ── 信号质量 ──
    val qualityDb: Double = 0.0,
    val qualityTier: Int = 3,
    val phaseSpan: Int = 0,
    val noiseHz: Double = 0.0,
    val adaptiveSpan: Boolean = true,

    // ── 后处理 ──
    val postProcess: SstvPostProcess.Options = SstvPostProcess.Options.NONE,
    val enhancedPreview: Boolean = false,

    // ── 盲扫 ──
    val blindScanOnFailure: Boolean = true,
    val blindScanning: Boolean = false,
    val blindCandidates: List<SstvBlindScan.Candidate> = emptyList(),
    val blindConfidence: Double = 0.0,

    // ── 离线导入 ──
    val importing: Boolean = false,
    val importStatus: String? = null,

    // ── 图库 ──
    val gallery: List<SstvImageStore.Entry> = emptyList(),
    val galleryBytes: Long = 0L,
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val galleryBusy: String? = null,
    /** 图库查看器当前选中的文件（null 表示未打开）。 */
    val viewing: SstvImageStore.Entry? = null,
    val viewingBitmap: Bitmap? = null,

    // ── 发射 ──
    val txModeName: String = SstvMode.PD_120.name,
    val txSourceLabel: String? = null,
    val txPreview: Bitmap? = null,
    val txPlaying: Boolean = false,
    val txProgress: Float = 0f,
    val txStatus: String = "选择一张图片，编码成 SSTV 音频后播出",
    val txIncludeVis: Boolean = true,
    val txOverlayCallsign: Boolean = true,
    val callsign: String = "",

    // ── 音源 / 录音 / 瀑布图（参照 robot36 补齐） ──
    /** 当前音源：`MIC` 或 `PLAYBACK`（见 [SstvSettingsStore.audioSourceKind]）。 */
    val audioSourceKind: String = SstvSettingsStore.AUDIO_SOURCE_MIC,
    /** 播放捕获是否已拿到授权（没有授权时不能选它）。 */
    val playbackAuthorized: Boolean = false,
    /** 接收时是否录制原始音频。 */
    val recordAudio: Boolean = true,
    /** 最近一次录音的文件名（可一键重解）。 */
    val lastRecordingName: String? = null,
    val recordings: List<SstvRecordingStore.Entry> = emptyList(),
    val recordingsBytes: Long = 0L,
    /** 是否显示瀑布图。 */
    val waterfallEnabled: Boolean = true,
    /** 瀑布图位图（每次快照重建，靠引用变化触发 Compose 重绘）。 */
    val waterfall: Bitmap? = null,
    /** 像素级零相位低通开关（见 [SstvDecoder]）。 */
    val pixelNyquistFilter: Boolean = true,
) {
    /** 接收进度（0–1）。 */
    val progress: Float
        get() = if (totalLines > 0) decodedLines.toFloat() / totalLines else 0f

    /** 发射模式对象。 */
    val txMode: SstvMode
        get() = SstvMode.entries.find { it.name == txModeName } ?: SstvMode.PD_120

    /** 是否开启了任一项后处理。 */
    val postProcessEnabled: Boolean get() = postProcess.anyEnabled

    /** 信号质量文案（供 UI 直接显示）。 */
    val qualityLabel: String
        get() = when (qualityTier) {
            0 -> "强"
            1 -> "正常"
            2 -> "弱"
            else -> "极弱"
        }

    /** 是否使用「播放捕获」音源（Android 10+）。 */
    val usesPlaybackCapture: Boolean get() = audioSourceKind == SstvSettingsStore.AUDIO_SOURCE_PLAYBACK

    /** 录音总占用文案。 */
    val recordingsLabel: String
        get() = if (recordings.isEmpty()) {
            "暂无录音"
        } else {
            "共 ${recordings.size} 段 · ${formatBytes(recordingsBytes)}"
        }
}

/**
 * SSTV 功能的状态持有者。
 *
 * 采集与解码由 [SstvRecorder] 在后台完成；离线解码、图库文件 IO、图像编解码
 * 全部在 `Dispatchers.IO` / `Dispatchers.Default` 上执行 —— 一期曾因把文件 IO
 * 放主线程踩过一次（见 REQUIREMENT_SSTV.md §11.7）。
 */
class SstvViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SstvSettingsStore(application)
    private val imageStore = SstvImageStore(application)
    private val recordingStore = SstvRecordingStore(application)
    private var recorder: SstvRecorder? = null
    private var player: com.example.hamkit.data.sstv.SstvAudioPlayer? = null

    /** 瀑布图缓冲：由 [SstvRecorder] 的采集线程填充，UI 侧周期性快照成位图。 */
    @Volatile
    private var waterfall: SstvWaterfall? = null

    /** 瀑布图快照刷新任务（仅在接收期间运行）。 */
    private var waterfallJob: Job? = null

    /** 播放捕获的投影（Android 10+）；由 UI 授权后交进来。 */
    @Volatile
    private var mediaProjection: MediaProjection? = null

    /**
     * 由卫星详情页带入的**临时**模式覆盖。
     *
     * 刻意不写入 [SstvSettingsStore]：从 ISS 详情页进入一次后，用户回到首页再打开
     * SSTV 时仍应是「自动识别」，而不该被上一次的预置悄悄改掉。
     */
    private var presetOverride: SstvMode? = null

    /**
     * 最近一次**原始**解码结果（未经后处理），用于保存原图与二次处理。
     *
     * 由采集协程（IO 线程）写入、UI 线程读取，故标记 `@Volatile` 保证可见性。
     */
    @Volatile
    private var lastResult: SstvDecoder.Result? = null

    /** 本次接收是否已尝试过自动保存（避免保存失败后每轮部分解码都重写文件）。 */
    private var autoSaveAttempted = false

    /** 发射用的图像源（在协程中装配，UI 只持有预览位图）。 */
    @Volatile
    private var txSource: SstvImageSource? = null

    /**
     * 最近一次导入的音频样本。
     *
     * 保留它是为了让「盲扫候选一键切换」无需重新导入 —— 盲扫常有 2–3 个难以区分的
     * 候选（行周期相同的模式本质不可分），不能指望用户为每个候选重新选一次文件。
     * 长度受 [SstvWavReader.MAX_SECONDS] 限制（300 秒 ≈ 57 MB），且在
     * [clearImage] 与新导入时释放。
     */
    @Volatile
    private var importedAudio: FloatArray? = null

    /** 图库查看器当前展示的文件，离开时用于释放位图引用。 */
    private var viewingFile: File? = null

    // ══ 持续接收（三期 Q2）══

    /** 非空表示正在持续接收；关闭时置空（与 [recorder] 互斥）。 */
    private var persistent: PersistentSession? = null

    /** 持续接收的音频采集协程。 */
    private var persistentJob: Job? = null

    /**
     * 持续接收的流式解码器。
     *
     * 主线程创建/清空，预览协程（Default）读取，故为 `@Volatile`。
     */
    @Volatile
    private var persistentDecoder: SstvStreamDecoder? = null

    /**
     * 「卷纸」图像缓冲（动态高度，到顶滚出）。
     *
     * 解码线程追加、预览协程建图、主线程收尾，故为 `@Volatile`。
     */
    @Volatile
    private var rollingImage: SstvRollingImage? = null

    /**
     * 上一次成功建图时，卷纸**累计追加**的行数（不是当前缓冲行数）。
     *
     * 必须用累计值：卷纸到 [SstvRollingImage.DEFAULT_MAX_ROWS] 后会滚出最老行，
     * 当前行数恒定为上限；用当前行数比较会让预览在长时间接收后永久停住。
     */
    @Volatile
    private var rollingRenderedRows = 0

    /** 持续接收的刷新协程（节流建图，避免每行都新建位图）。 */
    private var rollingRefreshJob: Job? = null

    /**
     * 持续接收的刷新请求计数器（详见 [scheduleRollingRefresh]）。
     *
     * 用「递增计数 + StateFlow 自带合并」而不是「协程活着就丢弃新请求」：后者会让刷新协程
     * 长期处于活跃态，最后一次请求永远得不到执行，画面停在几行不动。
     */
    private var rollingRefreshRequests = MutableStateFlow(0)

    private val _uiState = MutableStateFlow(
        SstvUiState(
            manualModeName = settingsStore.manualModeName,
            autoSave = settingsStore.autoSave,
            livePreview = settingsStore.livePreview,
            continuous = settingsStore.continuous,
            persistent = settingsStore.persistent,
            maxFrames = settingsStore.maxFrames,
            adaptiveSpan = settingsStore.adaptiveSpan,
            blindScanOnFailure = settingsStore.blindScanOnFailure,
            postProcess = settingsStore.postProcess,
            txModeName = settingsStore.txMode.name,
            txOverlayCallsign = settingsStore.txOverlayCallsign,
            callsign = settingsStore.callsign,
            audioSourceKind = settingsStore.audioSourceKind,
            recordAudio = settingsStore.recordAudio,
            waterfallEnabled = settingsStore.waterfall,
            pixelNyquistFilter = settingsStore.pixelNyquistFilter,
        ),
    )
    val uiState: StateFlow<SstvUiState> = _uiState.asStateFlow()

    init {
        refreshGallery()
        refreshRecordings()
    }

    // ══════════════════════════════════════════════════════════════════
    // 音源 / 录音 / 瀑布图（参照 robot36 补齐）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 授权结果交进来（Android 10+ 播放捕获）。
     *
     * **顺序**：调用方必须**先**通过 `createScreenCaptureIntent()` 拿到用户授权，
     * 再调用本方法；本方法内部才启动 `SstvCaptureService`，最后取投影。
     *
     * 为什么不是「先起服务、再要授权」：AOSP 只要求取投影前有该类型前台服务，但国产
     * ROM（MIUI / HyperOS 等）会额外校验 `CAPTURE_VIDEO_OUTPUT` / `project_media`，
     * 在尚无有效授权时启动 mediaProjection 前台服务会抛 `SecurityException` 并**崩溃进程**。
     * 「先授权、后起服务」同时满足两边。
     *
     * @param resultCode `Activity.RESULT_OK` 表示用户已同意录屏（播放捕获）授权
     * @param data 授权返回的 Intent（含投影 token），取消时为 null
     */
    fun usePlaybackCapture(resultCode: Int, data: Intent?) {
        val context = getApplication<Application>()
        val granted = resultCode == Activity.RESULT_OK && data != null &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        if (!granted) {
            finishPlaybackCapture()
            _uiState.update { it.copy(status = "播放捕获授权失败或已取消") }
            return
        }
        // 1) 先起前台服务：拿到授权后启动才能通过厂商 ROM 的校验
        SstvCaptureService.setStartFailureListener { error ->
            // 前台服务被系统/ROM 拦下：不崩溃，但这里**不能**再调 stop() —— 服务刚要起来
            // 就被停掉，会和它自己 onStartCommand 里的 stopSelf() 打架。只释放本地状态。
            SstvCaptureService.setStartFailureListener(null)
            SstvCaptureService.setStartReadyListener(null)
            mediaProjection?.let { runCatching { it.stop() } }
            mediaProjection = null
            settingsStore.audioSourceKind = SstvSettingsStore.AUDIO_SOURCE_MIC
            _uiState.update {
                it.copy(
                    audioSourceKind = SstvSettingsStore.AUDIO_SOURCE_MIC,
                    playbackAuthorized = false,
                    status = "播放捕获启动失败（${error.message?.take(80) ?: "前台服务被拦截"}），已回退麦克风",
                )
            }
        }
        // 2) 服务真正进入前台后再取投影。
        //
        // startForegroundService() 是异步投递，start() 返回 true 不代表服务已在前台；
        // Android 14+ 在服务尚未前台时调 getMediaProjection() 会抛 SecurityException。
        // 因此把取投影的动作放到「前台已就绪」回调里，而不是紧跟 start() 之后同步执行。
        SstvCaptureService.setStartReadyListener {
            SstvCaptureService.setStartReadyListener(null)
            SstvCaptureService.setStartFailureListener(null)
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            val projection = manager?.let {
                runCatching { it.getMediaProjection(resultCode, data) }.getOrNull()
            }
            if (projection == null) {
                finishPlaybackCapture()
                _uiState.update { it.copy(status = "播放捕获授权失败或已取消") }
                return@setStartReadyListener
            }
            mediaProjection?.let { runCatching { it.stop() } }
            mediaProjection = projection
            settingsStore.audioSourceKind = SstvSettingsStore.AUDIO_SOURCE_PLAYBACK
            _uiState.update {
                it.copy(
                    audioSourceKind = SstvSettingsStore.AUDIO_SOURCE_PLAYBACK,
                    playbackAuthorized = true,
                    status = "音源已切换为「播放捕获」：在其它应用里播放 SSTV 录音，然后点开始接收",
                )
            }
        }
        if (!SstvCaptureService.start(context)) {
            SstvCaptureService.setStartReadyListener(null)
            finishPlaybackCapture()
            _uiState.update { it.copy(status = "播放捕获不可用：缺少前台服务或通知权限，请在系统设置中允许") }
            return
        }
    }

    /** 放弃本次播放捕获：释放投影、摘掉监听、停掉前台服务（所有失败分支共用）。 */
    private fun finishPlaybackCapture() {
        SstvCaptureService.setStartFailureListener(null)
        SstvCaptureService.setStartReadyListener(null)
        SstvCaptureService.stop(getApplication())
        mediaProjection?.let { runCatching { it.stop() } }
        mediaProjection = null
        settingsStore.audioSourceKind = SstvSettingsStore.AUDIO_SOURCE_MIC
        _uiState.update {
            it.copy(
                audioSourceKind = SstvSettingsStore.AUDIO_SOURCE_MIC,
                playbackAuthorized = false,
            )
        }
    }

    /** 切回麦克风音源，并释放播放捕获的投影。 */
    fun useMicrophone() {
        finishPlaybackCapture()
        _uiState.update { it.copy(status = "音源已切换为麦克风") }
    }

    /** 构造本次接收使用的音源（见 [SstvAudioSource]）。 */
    private fun buildAudioSource(): SstvAudioSource {
        val context = getApplication<Application>()
        val playback = mediaProjection
        return if (_uiState.value.usesPlaybackCapture && playback != null &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        ) {
            PlaybackCaptureAudioSource(playback)
        } else {
            MicrophoneAudioSource(context)
        }
    }

    fun setRecordAudio(enabled: Boolean) {
        settingsStore.recordAudio = enabled
        _uiState.update {
            it.copy(
                recordAudio = enabled,
                status = if (enabled) "接收时将录制原始音频（可用「导入录音」重解）" else "已关闭接收录音",
            )
        }
    }

    fun setWaterfallEnabled(enabled: Boolean) {
        settingsStore.waterfall = enabled
        if (!enabled) {
            waterfall = null
            _uiState.update { it.copy(waterfallEnabled = false, waterfall = null) }
        } else {
            _uiState.update { it.copy(waterfallEnabled = true) }
        }
    }

    fun setPixelNyquistFilter(enabled: Boolean) {
        settingsStore.pixelNyquistFilter = enabled
        _uiState.update {
            it.copy(
                pixelNyquistFilter = enabled,
                status = if (enabled) "已开启像素级低通（弱信号更干净）" else "已关闭像素级低通",
            )
        }
    }

    /** 在协程中刷新录音列表（文件 IO 不落在主线程）。 */
    fun refreshRecordings() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { recordingStore.list() }
            _uiState.update {
                it.copy(
                    recordings = entries,
                    recordingsBytes = entries.sumOf { entry -> entry.sizeBytes },
                )
            }
        }
    }

    fun deleteRecording(entry: SstvRecordingStore.Entry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recordingStore.delete(entry.file) }
            _uiState.update { it.copy(status = "已删除录音 ${entry.file.name}") }
            refreshRecordings()
        }
    }

    fun clearRecordings() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recordingStore.clear() }
            _uiState.update { it.copy(status = "录音已全部删除") }
            refreshRecordings()
        }
    }

    /**
     * 把一段已录下的接收音频重新解码。
     *
     * 这是「录下过境 → 事后重解」的闭环入口：走与「导入录音」完全相同的解码与盲扫逻辑，
     * 只是文件来自应用私有的录音目录（因此不需要文件选择器，也不需要任何权限）。
     */
    fun decodeRecording(entry: SstvRecordingStore.Entry) {
        if (_uiState.value.importing) return
        _uiState.update {
            it.copy(importing = true, importStatus = "正在读取录音 ${entry.file.name}…", error = null)
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) { readAudioFile(entry.file) }
            when (outcome) {
                is SstvWavReader.Outcome.Failure -> _uiState.update {
                    it.copy(importing = false, importStatus = null, error = outcome.reason)
                }

                is SstvWavReader.Outcome.Success -> decodeImported(outcome)
            }
        }
    }

    /** 启动瀑布图快照循环（仅接收期间）。 */
    private fun startWaterfallUpdates() {
        waterfallJob?.cancel()
        if (!settingsStore.waterfall) {
            waterfall = null
            _uiState.update { it.copy(waterfall = null) }
            return
        }
        val buffer = SstvWaterfall()
        waterfall = buffer
        waterfallJob = viewModelScope.launch {
            while (isActive) {
                delay(WATERFALL_REFRESH_MS)
                val snapshot = withContext(Dispatchers.Default) { buffer.snapshot() }
                // 只有真正有新内容时才重建位图（空快照 → 不画）
                val pixels = withContext(Dispatchers.Default) { waterfallPixels(snapshot, buffer) }
                _uiState.update { it.copy(waterfall = pixels) }
            }
        }
    }

    private fun stopWaterfallUpdates() {
        waterfallJob?.cancel()
        waterfallJob = null
    }

    /**
     * 把瀑布图的强度快照转成位图。
     *
     * 配色刻意是「频率 → 色相」的彩虹映射而不是灰度：灰度下无法一眼分清
     * 「1200 Hz 同步线」与「1500 Hz 黑电平」，而那恰恰是判断失谐与信号质量的关键。
     */
    private fun waterfallPixels(snapshot: IntArray, buffer: SstvWaterfall): Bitmap {
        val bins = buffer.bins
        val rows = buffer.rows
        val pixels = IntArray(bins * rows)
        for (row in 0 until rows) {
            for (bin in 0 until bins) {
                val intensity = snapshot[row * bins + bin]
                if (intensity == 0) {
                    pixels[row * bins + bin] = WATERFALL_EMPTY
                } else {
                    val hue = 240f * bin / (bins - 1).coerceAtLeast(1)
                    val color = android.graphics.Color.HSVToColor(
                        floatArrayOf(hue, 0.85f, 0.25f + 0.75f * intensity / 255f),
                    )
                    pixels[row * bins + bin] = color
                }
            }
        }
        return Bitmap.createBitmap(bins, rows, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, bins, 0, 0, bins, rows)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 接收
    // ══════════════════════════════════════════════════════════════════

    /** 在协程中刷新图库（文件 IO 不落在主线程）。 */
    fun refreshGallery() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { imageStore.list() }
            _uiState.update {
                it.copy(
                    gallery = entries,
                    galleryCount = entries.size,
                    galleryBytes = entries.sumOf { entry -> entry.sizeBytes },
                )
            }
        }
    }

    /** 由卫星详情页调用：预置本次接收的模式（不改变默认设置）。 */
    fun applyPreset(mode: SstvMode) {
        presetOverride = mode
        _uiState.update {
            it.copy(
                manualModeName = mode.name,
                presetApplied = true,
                status = "已按卫星预置 ${mode.displayName}（仅本次接收，不改变默认设置）",
            )
        }
    }

    /**
     * 清除卫星预置。
     *
     * [SstvViewModel] 是应用级（`appViewModel`）单例，预置若不清理会一直黏住 ——
     * 用户从 ISS 页面进入过一次之后，再从首页进入也会是手动 PD-120。因此页面销毁时
     * 必须清掉，让 `manualModeName` 回到用户自己的设置。
     */
    fun clearPreset() {
        if (presetOverride == null) return
        presetOverride = null
        _uiState.update {
            it.copy(presetApplied = false, manualModeName = settingsStore.manualModeName)
        }
    }

    fun startReceiving() {
        if (_uiState.value.isReceiving) return
        // 上一段持续接收还在收尾（保存录音）时不能开新会话：
        // 收尾协程最后会调用 finishPersistentReceiving()，它按当前 `persistent` 收尾，
        // 若此时已换成新会话，会把刚启动的新会话一起拆掉（新会话瞬间变回未接收）。
        if (persistent != null) {
            _uiState.update { it.copy(status = "上一段持续接收正在收尾，请稍候再开始") }
            return
        }
        // 持续接收优先：它没有帧的概念，因此不走 SstvRecorder 的帧状态机
        if (settingsStore.persistent) {
            startPersistentReceiving()
            return
        }
        // 预置优先于用户默认设置
        val manualMode = presetOverride ?: settingsStore.manualMode
        autoSaveAttempted = false
        val recordName = if (settingsStore.recordAudio) recordingStore.newFile() else null
        _uiState.update {
            it.copy(
                isReceiving = true,
                status = if (manualMode != null) {
                    "已锁定 ${manualMode.displayName}，正在接收…"
                } else {
                    "正在侦听…"
                },
                visCode = null,
                mode = manualMode,
                decodedLines = 0,
                totalLines = manualMode?.imageLines ?: 0,
                syncPulseCount = 0,
                bitmap = null,
                savedFileName = null,
                error = null,
                completedFrames = 0,
                blindCandidates = emptyList(),
                blindConfidence = 0.0,
                noiseHz = 0.0,
                phaseSpan = 0,
                lastRecordingName = null,
            )
        }
        startWaterfallUpdates()

        recorder = SstvRecorder(
            context = getApplication(),
            audioSource = buildAudioSource(),
            forcedMode = manualMode,
            continuous = settingsStore.continuous,
            maxFrames = settingsStore.maxFrames,
            adaptiveSpan = settingsStore.adaptiveSpan,
            pixelNyquistFilter = settingsStore.pixelNyquistFilter,
            recordingFile = recordName,
            waterfall = if (settingsStore.waterfall) waterfall else null,
            onVisDetected = { detected ->
                _uiState.update {
                    it.copy(
                        visCode = detected.visCode,
                        mode = detected.mode,
                        hedrShiftHz = detected.hedrShiftHz,
                        totalLines = detected.mode?.imageLines ?: 0,
                        status = detected.mode?.let { mode ->
                            "已识别 ${mode.displayName}（VIS 0x${detected.visCode.toString(16).uppercase()}）"
                        } ?: "识别到未收录的模式（VIS 0x${detected.visCode.toString(16).uppercase()}）",
                        // 仅当 VIS 码未录入模式表时才报错（如私有/非标模式）；
                        // 已收录模式即使信号差也走各自的解码失败路径，不在此处报错
                        error = if (detected.mode == null) {
                            "该 VIS 码未收录，暂不支持解码（0x${detected.visCode.toString(16).uppercase()}）"
                        } else null,
                    )
                }
            },
            onProgress = { frame -> onDecoded(frame) },
            onStatus = { text -> _uiState.update { it.copy(status = text) } },
            onQuality = { quality ->
                _uiState.update {
                    it.copy(
                        qualityDb = quality.qualityDb,
                        qualityTier = quality.tier,
                        phaseSpan = quality.phaseSpan,
                    )
                }
            },
            onFinished = { onRecorderFinished() },
            onRecordingFinished = { file, samples ->
                // **在文件关闭之后**再补模式名：改名发生在写入期间虽然合法（fd 仍然有效），
                // 但会让回调里的路径与实际文件不一致；放到最后一步最干净
                viewModelScope.launch {
                    val modeName = _uiState.value.mode?.displayName
                    val renamed = withContext(Dispatchers.IO) {
                        recordingStore.renameWithMode(file, modeName)
                    }
                    _uiState.update {
                        it.copy(
                            lastRecordingName = renamed.name,
                            status = it.status +
                                "（已录音 ${"%.0f".format(samples.toDouble() / 48000)} 秒）",
                        )
                    }
                    refreshRecordings()
                }
            },
        ).also { it.start() }
    }

    /**
     * 采集循环结束（正常收完一帧 / 连续模式跑满 / 出错）。
     *
     * 单帧收满后是 [SstvRecorder] **自己**决定停止的，UI 侧无从推断这一时刻，
     * 因此必须由采集器回调 —— 否则「开始接收」会一直显示为「停止接收」。
     * 用户主动 [stopReceiving] 时 `isReceiving` 已先被置为 false，这里直接返回，
     * 不会覆盖停止时给出的文案。
     */
    private fun onRecorderFinished() {
        stopWaterfallUpdates()
        // 采集结束就撤掉 mediaProjection 前台服务：它只为满足平台顺序要求而存在，
        // 多留一刻都是无谓的常驻通知
        SstvCaptureService.stop(getApplication())
        if (!_uiState.value.isReceiving) return
        _uiState.update { it.copy(isReceiving = false) }
    }

    fun stopReceiving() {
        if (persistent != null) {
            stopPersistentReceiving()
            return
        }
        recorder?.stop()
        recorder = null
        stopWaterfallUpdates()

        // 关闭实时预览时，过程中不会建图，这里补一次 —— 让用户能看到已收到的部分，
        // 而不是面对一块空白却要凭猜测去点保存
        val snapshot = lastResult
        val current = _uiState.value.bitmap
        val bitmap = if (current == null && snapshot != null) {
            toDisplayBitmap(snapshot)
        } else {
            current
        }

        _uiState.update {
            val partial = bitmap != null && snapshot != null &&
                snapshot.decodedLines < snapshot.mode.imageLines
            it.copy(
                isReceiving = false,
                bitmap = bitmap,
                status = when {
                    partial -> "已停止：收到 ${snapshot.decodedLines}/${snapshot.mode.imageLines} 行，可手动保存"
                    bitmap != null -> "接收已停止"
                    else -> "已停止"
                },
            )
        }
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(isReceiving = false, status = "缺少录音权限", error = "需要麦克风权限才能接收 SSTV")
        }
    }

    /** 保存当前解码结果到图库。 */
    fun saveCurrent() {
        val snapshot = lastResult
        if (snapshot == null) {
            _uiState.update { it.copy(error = "当前没有可保存的图像") }
            return
        }
        val options = settingsStore.postProcess
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                if (options.anyEnabled) {
                    val processed = SstvPostProcess.process(
                        snapshot.pixels, snapshot.width, snapshot.height, options,
                    )
                    imageStore.savePixels(
                        processed, snapshot.width, snapshot.height,
                        snapshot.mode.displayName, enhanced = true,
                    )
                } else {
                    imageStore.save(snapshot)
                }
            }
            if (file != null) refreshGallery()
            _uiState.update {
                it.copy(
                    savedFileName = file?.name,
                    error = if (file == null) "保存失败" else null,
                    status = if (file != null) "已保存到图库：${file.name}" else it.status,
                )
            }
        }
    }

    /** 保存**未经后处理**的原始解码结果（后处理开启时的对照）。 */
    fun saveRaw() {
        val snapshot = lastResult
        if (snapshot == null) {
            _uiState.update { it.copy(error = "当前没有可保存的图像") }
            return
        }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { imageStore.save(snapshot) }
            if (file != null) refreshGallery()
            _uiState.update {
                it.copy(
                    savedFileName = file?.name,
                    status = if (file != null) "已保存原图：${file.name}" else it.status,
                    error = if (file == null) "保存失败" else null,
                )
            }
        }
    }

    /**
     * 手动选择模式。
     *
     * - 用户显式选择会**取消卫星预置**（预置只影响它自己那一次进入）；
     * - 接收过程中修改不会影响正在进行的那一帧（模式在 [startReceiving] 时已固化
     *   进采集器），因此这里明确提示「下次接收生效」。
     */
    fun setManualMode(mode: SstvMode?) {
        val receiving = _uiState.value.isReceiving
        presetOverride = null
        settingsStore.manualModeName = mode?.name
        val chosen = mode?.displayName ?: "自动识别"
        _uiState.update {
            it.copy(
                manualModeName = mode?.name,
                presetApplied = false,
                status = if (receiving) {
                    "已选择 $chosen，将在下次接收时生效"
                } else {
                    "已选择 $chosen"
                },
            )
        }
    }

    fun setAutoSave(enabled: Boolean) {
        settingsStore.autoSave = enabled
        _uiState.update { it.copy(autoSave = enabled) }
    }

    fun setLivePreview(enabled: Boolean) {
        settingsStore.livePreview = enabled
        _uiState.update { it.copy(livePreview = enabled) }
    }

    fun setContinuous(enabled: Boolean) {
        settingsStore.continuous = enabled
        _uiState.update {
            it.copy(
                continuous = enabled,
                status = if (enabled) "连续接收已开启：收满一帧后自动接续" else "连续接收已关闭",
            )
        }
    }

    /**
     * 切换持续接收。
     *
     * **接收过程中切换不会自动重启**：两种接收的状态机完全不同（有没有「帧」），无法
     * 就地切换，而静默重启会让用户以为「开关一拨画面就变」—— 实际是丢掉已收内容重新开始。
     * 因此这里只落设置并明确提示「停止后重新开始才生效」。
     */
    fun setPersistent(enabled: Boolean) {
        settingsStore.persistent = enabled
        val receiving = _uiState.value.isReceiving
        _uiState.update {
            it.copy(
                persistent = enabled,
                status = when {
                    !enabled -> "持续接收已关闭，回到按帧接收"
                    receiving -> "持续接收已开启：停止后重新开始才生效"
                    else -> "持续接收已开启：不要求 VIS 头，从任意时刻开始，画面会一直向下延长"
                },
            )
        }
    }

    fun setMaxFrames(frames: Int) {
        settingsStore.maxFrames = frames
        _uiState.update { it.copy(maxFrames = settingsStore.maxFrames) }
    }

    fun setAdaptiveSpan(enabled: Boolean) {
        settingsStore.adaptiveSpan = enabled
        _uiState.update { it.copy(adaptiveSpan = enabled) }
    }

    fun setBlindScanOnFailure(enabled: Boolean) {
        settingsStore.blindScanOnFailure = enabled
        _uiState.update { it.copy(blindScanOnFailure = enabled) }
    }

    fun clearImage() {
        // 持续接收中清空 = 丢弃「卷纸」内容并重新开始，否则下一次建图又会把它画回来
        rollingImage?.clear()
        rollingRenderedRows = 0
        lastResult = null
        importedAudio = null
        autoSaveAttempted = false
        _uiState.update {
            it.copy(
                bitmap = null,
                decodedLines = 0,
                totalLines = 0,
                savedFileName = null,
                visCode = null,
                blindCandidates = emptyList(),
                blindConfidence = 0.0,
                rollingRows = 0,
                rollingScrolled = false,
                status = "已清空",
            )
        }
    }

    fun clearGallery() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { imageStore.clear() }
            _uiState.update {
                it.copy(
                    status = "图库已清空",
                    galleryCount = 0,
                    gallery = emptyList(),
                    galleryBytes = 0,
                    thumbnails = emptyMap(),
                    viewing = null,
                    viewingBitmap = null,
                )
            }
        }
    }

    override fun onCleared() {
        // 持续接收的音频源与录音文件必须在这里显式释放：onCleared 之后不会再走
        // stopPersistentReceiving() 的收尾路径，否则麦克风/播放捕获会一直被占用，
        // WAV 头也来不及回填（录音文件会坏掉）。
        persistent?.let { session ->
            runCatching { session.source.close() }
            runCatching { session.recording?.close() }
        }
        persistentJob?.cancel()
        persistentJob = null
        persistentDecoder = null
        persistent = null
        rollingRefreshJob?.cancel()
        rollingRefreshJob = null
        recorder?.stop()
        recorder = null
        stopWaterfallUpdates()
        player?.stop()
        player = null
        mediaProjection?.let { runCatching { it.stop() } }
        mediaProjection = null
        // 播放捕获的前台服务只为投影而存在，页面销毁后必须一起停掉
        SstvCaptureService.stop(getApplication())
        super.onCleared()
    }

    // ══════════════════════════════════════════════════════════════════
    // 持续接收（三期 Q2）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 一次持续接收会话的可变状态。
     *
     * 与 [SstvRecorder] 的区别：采集器按「帧」工作（VIS → 收满即停），持续接收没有帧，
     * 因此这里自己驱动「音源 → 解调 → 流式解码 → 卷纸缓冲」这条更短的链路。
     *
     * @param source 音源（麦克风 / 播放捕获）
     * @param mode 锁定的模式。持续接收的音频通常没有 VIS 头，模式只能来自
     *   手动锁定、卫星预置或中途捕到的 VIS
     * @param recording 录音文件（可选）
     */
    private class PersistentSession(
        val source: SstvAudioSource,
        val mode: SstvMode,
        val recording: SstvWavRecorder?,
        val recordingFile: File?,
    )

    /** 收尾阶段在后台算好的素材（避免在主线程里做全局拟合）。 */
    private class PersistentOutcome(val frames: List<SstvDecoder.Result>, val shiftHz: Double)

    /**
     * 开始持续接收。
     *
     * 模式优先级：卫星预置 → 用户手动锁定 → **最近一次解出的模式**。持续接收要求
     * 事先知道模式（没有 VIS 时无从反推行周期），所以没有可用模式时直接给出提示，
     * 而不是开一条永远解不出图的热麦克风。
     */
    private fun startPersistentReceiving() {
        val mode = presetOverride ?: settingsStore.manualMode ?: _uiState.value.mode
        if (mode == null) {
            _uiState.update {
                it.copy(
                    error = "持续接收需要先锁定模式（没有 VIS 头时无法反推行周期）",
                    status = "请先选择模式，再开始持续接收",
                )
            }
            return
        }

        val source = buildAudioSource()
        val failure = source.open()
        if (failure != null) {
            _uiState.update { it.copy(error = failure, status = "打开音源失败：$failure") }
            return
        }
        val rate = source.sampleRate

        // 录音：持续接收的素材往往事后还要用「导入录音」重解，顺带把音频留下
        val recorderFile = if (settingsStore.recordAudio) recordingStore.newFile() else null
        val wav = recorderFile?.let { file ->
            // 打开失败不阻断接收：录音是附带能力，解不出来才是致命问题
            SstvWavRecorder(file, rate).also { recorder -> runCatching { recorder.open() } }
        }

        // 图像缓冲：默认上限 20000 行；PD 族一行两行图像、640 宽，占用约 51 MB
        val rolling = SstvRollingImage(width = mode.linePixels)
        rollingImage = rolling
        rollingRenderedRows = 0

        val decoder = SstvStreamDecoder(
            sampleRate = rate,
            mode = mode,
            onLine = { event -> onPersistentLine(event) },
        )
        persistentDecoder = decoder

        persistent = PersistentSession(source, mode, wav, recorderFile)
        _uiState.update {
            it.copy(
                isReceiving = true,
                mode = mode,
                totalLines = 0,
                decodedLines = 0,
                syncPulseCount = 0,
                bitmap = null,
                savedFileName = null,
                error = null,
                completedFrames = 0,
                rollingRows = 0,
                rollingScrolled = false,
                status = "持续接收中：${mode.displayName}，从任意时刻开始解，画面会不断延长",
            )
        }
        startWaterfallUpdates()
        // 预览刷新协程常驻一个：解码线程只改计数，建图节奏由这里控制。
        // 「实时预览」关闭时也启动但保持空转 —— 持续接收的卷纸在收尾时才建图落盘，
        // 与按帧接收的 needBitmap 口径一致；设置页文案已注明此差异
        launchRollingRefresher(allowRender = settingsStore.livePreview)

        persistentJob = viewModelScope.launch(Dispatchers.IO) {
            val demodulator = SstvDemodulator(
                sampleRate = rate,
                phaseSpan = SstvDemodulator.recommendPhaseSpan(mode.pixelSeconds * rate),
            )
            val buffer = ShortArray(PERSISTENT_READ_SAMPLES)
            val pcm = FloatArray(PERSISTENT_READ_SAMPLES)
            var consecutiveErrors = 0
            while (isActive) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    // 伪读（音频系统没数据）：让出线程并给出提示，避免界面看起来「卡住了」
                    consecutiveErrors++
                    if (consecutiveErrors % 20 == 0) {
                        _uiState.update { it.copy(status = "持续接收中：音源暂无数据…") }
                    }
                    delay(100)
                    continue
                }
                consecutiveErrors = 0
                for (i in 0 until read) pcm[i] = buffer[i] / 32768f
                val block = if (read == pcm.size) pcm else pcm.copyOf(read)
                // 录音只写**实际读到的**样本（伪读的零头不能落盘，否则时间轴会错）
                wav?.append(buffer, 0, read)
                // 喂原始音频给内部 VIS 检测器：收尾时若本段起点带 VIS（通常只有流的
                // 首帧 —— 后续帧的段起点都在 VIS 之后），对应帧就能走与一期完全相同的
                // 路径（该帧 finalize 逐像素一致）；落在段外的 VIS 被忽略，交给全局拟合。
                // 模式仍以锁定值为准，避免中途误判导致行号连续性被破坏
                decoder.feedAudio(block)
                val freqs = demodulator.process(block)
                decoder.feed(freqs)
                // 瀑布图与解码器共用同一条频率流（与按帧接收一致）
                waterfall?.feed(freqs)
            }
            // 音源自行结束（设备被占用、播放捕获被系统回收）时也走收尾，
            // 否则界面会一直停在「持续接收中」而实际早就不采了
            withContext(Dispatchers.Main) {
                if (persistent != null) {
                    _uiState.update { it.copy(status = "持续接收已结束：音源已断开") }
                    finishPersistentReceiving()
                }
            }
        }
    }

    /** 流式解码回调（在 IO 线程）：追加到卷纸、统计，并且**节流**重建预览。 */
    private fun onPersistentLine(event: SstvStreamDecoder.LineEvent) {
        val rolling = rollingImage ?: return
        rolling.append(event.pixels)
        // 用「累计追加行数」而不是「当前缓冲行数」：卷纸到 maxRows 后当前行数不再增长，
        // 旧写法会让预览在长时间接收后永久停住（解码其实还在继续）。
        // 第一行立即刷新：否则用户要等满 PERSISTENT_RENDER_ROWS 行才看到画面。
        if (shouldRenderPreview(rolling.appendedRows, rollingRenderedRows, PERSISTENT_RENDER_ROWS)) {
            scheduleRollingRefresh()
        }
    }

    /**
     * 节流建图：把短时间内的多次请求**合并**成一次，而不是丢弃。
     *
     * 历史实现用「刷新协程还在跑就直接 return」，而刷新协程每次都要等 250 ms +
     * 重建整幅位图（高度随接收不断增长，越来越慢）。解码线程每 4 行就请求一次，
     * 于是一旦建图变慢，新请求全被丢弃 —— 画面更新频率随「卷纸」变长而降到接近 0，
     * 表现为「持续接收时不实时显示图像」。现在改为计数信号 + StateFlow 合并：
     *
     *  - 刷新协程始终常驻一个（只在开始持续接收时启动、收尾时取消）；
     *  - StateFlow 本身带合并语义：忙时中间计数被覆盖，只保留最新一次，不排队、不丢尾；
     *  - 每次重建前按 [PERSISTENT_MIN_RENDER_INTERVAL_MS] 检查时间，避免高频重建。
     */
    private fun scheduleRollingRefresh() {
        // 只递增计数，不启动新协程（见 launchRollingRefresher 的说明）。
        // 必须用 update 而不是 `value++`：后者是「读-改-写」，跨线程并发时会丢请求
        rollingRefreshRequests.update { it + 1 }
    }

    /**
     * 启动常驻的预览刷新协程。
     *
     * 只在一次持续接收开始时调用一次，并把后续频繁调用的 [scheduleRollingRefresh]
     * 降级为「改一个计数」—— 这样解码线程从不为建图付出任何代价。
     *
     * @param allowRender 「实时预览」开关：关闭时协程仍常驻但跳过建图
     *   （省电，与按帧接收的 `livePreview` 口径一致），
     *   收尾落盘不依赖它 —— [stopPersistentReceiving] 用 finalize 的定稿结果建图
     */
    private fun launchRollingRefresher(allowRender: Boolean) {
        rollingRefreshJob?.cancel()
        // 耗时协程放到 Default 上跑：collect 里的 delay/节流与 Bitmap 重建都不占主线程
        rollingRefreshJob = viewModelScope.launch(Dispatchers.Default) {
            launchRollingRefresher(
                requests = rollingRefreshRequests,
                minIntervalMs = PERSISTENT_MIN_RENDER_INTERVAL_MS,
                now = SystemClock::uptimeMillis,
                onRender = { if (allowRender) renderRollingPreview() },
            )
        }
    }

    /**
     * 重建一次卷纸预览位图并推给 UI。
     *
     * 高度超过 [SstvRollingImage.DEFAULT_MAX_BITMAP_ROWS] 时 [SstvRollingImage.toPixels]
     * 会按行等间隔抽样 —— 此时位图高度不再等于已收行数，所以预览高度取「实际位图高度」，
     * 而不是卷纸的真实行数（原先直接用 `height`，超过上限后两者会不一致）。
     */
    private suspend fun renderRollingPreview() {
        val rolling = rollingImage ?: return
        val decoder = persistentDecoder ?: return
        // 先记下这次要画到哪一行：建图期间还会继续来新行，不能把它们算作已渲染，
        // 否则下一次刷新会被白白推迟几行。
        val renderedTotal = rolling.appendedRows
        val (pixels, height) = rolling.toPixels()
        if (height == 0) return
        val bitmap = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(pixels, rolling.width, height, Bitmap.Config.ARGB_8888)
        }
        // 收尾 / 新会话已接管：这是过期结果，直接丢弃，不能覆盖定稿画面与状态
        if (rollingImage !== rolling || persistentDecoder !== decoder) return
        rollingRenderedRows = renderedTotal
        val stats = decoder.stats()
        _uiState.update {
            it.copy(
                bitmap = bitmap,
                decodedLines = stats.decodedLines,
                rollingRows = height,
                rollingScrolled = rolling.scrolled,
                syncPulseCount = stats.syncPulses,
                hedrShiftHz = stats.hedrShiftHz,
                slantRatio = stats.slantRatio,
                status = if (stats.locked) {
                    "持续接收中：已 ${stats.decodedLines} 行，同步命中 " +
                        "${"%.0f".format(stats.syncHitRate * 100)}%"
                } else {
                    "持续接收中：正在等待同步脉冲…"
                },
            )
        }
    }

    /**
     * 用户停止持续接收：收起解码器，把卷纸内容落盘。
     */
    private fun stopPersistentReceiving() {
        val session = persistent
        val decoder = persistentDecoder
        val collectJob = persistentJob
        persistentJob = null
        collectJob?.cancel()
        // 关音源会打断阻塞的 read，采集协程随之退出
        session?.source?.close()
        // 预览刷新先停：收尾期间继续建图只会用卷纸内容盖掉即将生成的定稿画面
        rollingRefreshJob?.cancel()
        rollingRefreshJob = null

        if (decoder == null || session == null) {
            finishPersistentReceiving()
            return
        }
        _uiState.update { it.copy(isReceiving = false) }

        viewModelScope.launch {
            try {
                // 等采集协程真正退出再读解码器：cancel() 只是投递取消，若它还在 feed() 里，
                // finalizeFrames() 会和它并发改同一份解码器内部状态（解码器不是线程安全的）。
                // 加超时兜底：万一阻塞读没有及时返回，也不能让收尾永远卡住（否则无法再开始）。
                collectJob?.let { job ->
                    withTimeoutOrNull(PERSISTENT_STOP_JOIN_TIMEOUT_MS) { job.join() }
                }
                val mode = _uiState.value.mode
                // ① 先在后台收集素材：全局拟合出的各帧定稿 + 当前失谐量
                val collected = withContext(Dispatchers.Default) {
                    PersistentOutcome(
                        frames = decoder.finalizeFrames(),
                        shiftHz = decoder.stats().hedrShiftHz,
                    )
                }
                val frames = collected.frames
                val rollingFrameCount = frames.size

                // 选最终图：本次会话最近一帧定稿 > 卷纸内容。
                //    刻意**不用上一个会话的 lastResult** —— 那会保存出一张与本次接收无关的旧图
                lastResult = null
                val finalResult = frames.lastOrNull()
                    ?: rollingImage?.toResult(
                        mode = mode ?: SstvMode.ROBOT_36,
                        hedrShiftHz = collected.shiftHz,
                    )

                if (finalResult != null) {
                    lastResult = finalResult
                    val bitmap = withContext(Dispatchers.Default) { toDisplayBitmap(finalResult) }
                    val saved = if (settingsStore.autoSave) {
                        withContext(Dispatchers.IO) { imageStore.save(finalResult) }
                    } else {
                        null
                    }
                    if (saved != null) refreshGallery()
                    _uiState.update {
                        it.copy(
                            bitmap = bitmap,
                            decodedLines = finalResult.decodedLines,
                            savedFileName = saved?.name ?: it.savedFileName,
                            status = buildStopStatus(rollingFrameCount, saved?.name),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(status = "已停止：这段音频里没有解出完整的图像行")
                    }
                }
                // 收尾统一放在 finally：正常结束、保存失败、协程取消都要释放会话，
                // 否则 `persistent` 永远非空，开始按钮会被一直挡住。
                // 若音频先自行结束（采集协程自己退出），它也会在主线程调用同一个收尾，
                // 由 `persistent == null` 的守卫保证只执行一次。
            } catch (t: kotlinx.coroutines.CancellationException) {
                // 协程被取消（例如 ViewModel 销毁）不是收尾失败，交给 finally 释放资源即可
                throw t
            } catch (t: Throwable) {
                // 保存失败也必须收尾：否则 persistent 永远非空，开始按钮会被一直挡住
                _uiState.update { it.copy(status = "收尾失败：${t.message ?: "未知错误"}") }
            } finally {
                finishPersistentReceiving()
            }
        }
    }

    /** 停止后的说明文案。 */
    private fun buildStopStatus(
        frameCount: Int,
        savedName: String?,
    ): String {
        val base = when {
            frameCount >= 1 -> "已收到 $frameCount 段图像"
            else -> "已停止"
        }
        val saved = savedName?.let { "，已保存 $it" } ?: ""
        return base + saved
    }

    /** 收尾（采集结束 / 用户停止都会走到）：释放会话资源。 */
    private fun finishPersistentReceiving() {
        // 只允许收尾一次：采集协程正常结束与用户停止可能先后触发
        val session = persistent ?: return
        persistent = null
        persistentJob = null
        persistentDecoder = null
        // 刷新协程先停：它持有 rollingImage 的引用，收尾后再建图只会覆盖掉定稿画面
        rollingRefreshJob?.cancel()
        rollingRefreshJob = null
        // 定稿画面已经复制到 bitmap / lastResult，卷纸缓冲可以释放了。
        // PD 族 20000 行 × 640 宽 × 4 字节 ≈ 51 MB，ViewModel 是应用级单例，留着就是长期占用。
        rollingImage = null
        rollingRenderedRows = 0
        runCatching { session.source.close() }
        // close() 回填 WAV 头并返回写入的样本数（0 表示什么都没录到，直接丢弃）
        val recorded = session.recording?.let { wav -> runCatching { wav.close() }.getOrDefault(0L) } ?: 0L
        if (recorded > 0 && session.recordingFile != null) {
            val file = session.recordingFile
            viewModelScope.launch {
                val modeName = _uiState.value.mode?.displayName
                val renamed = withContext(Dispatchers.IO) {
                    recordingStore.renameWithMode(file, modeName)
                }
                _uiState.update { it.copy(lastRecordingName = renamed.name) }
                refreshRecordings()
            }
        } else if (session.recordingFile != null) {
            // 一秒都没录到（打开即停）：删掉空文件，避免录音列表里出现 0 字节条目
            runCatching { session.recordingFile.delete() }
        }
        stopWaterfallUpdates()
        SstvCaptureService.stop(getApplication())
        _uiState.update { it.copy(isReceiving = false) }
    }

    // ══════════════════════════════════════════════════════════════════
    // 解码结果 → UI 状态
    // ══════════════════════════════════════════════════════════════════

    private fun onDecoded(frame: SstvRecorder.SstvFrame) {
        val result = frame.result
        lastResult = result
        val complete = frame.complete

        // 「实时预览」只决定**接收过程中**是否逐行重建画面；收满一帧必须出图，
        // 否则关闭预览的用户连最终结果都看不到（只能盲点保存）。
        // 每次重建 Bitmap：内容变化但引用不变时 Compose 不会重绘。
        val needBitmap = settingsStore.livePreview || complete
        val bitmap = if (needBitmap) toDisplayBitmap(result) else _uiState.value.bitmap

        var savedName = _uiState.value.savedFileName
        var saveError: String? = null
        // 自动保存：整帧收齐后只尝试一次，失败不重试（否则每轮部分解码都会重写文件）
        if (complete && !autoSaveAttempted && settingsStore.autoSave) {
            autoSaveAttempted = true
            val options = settingsStore.postProcess
            val saved = if (options.anyEnabled) {
                imageStore.savePixels(
                    SstvPostProcess.process(result.pixels, result.width, result.height, options),
                    result.width,
                    result.height,
                    result.mode.displayName,
                    enhanced = true,
                )
            } else {
                imageStore.save(result)
            }
            savedName = saved?.name
            if (saved == null) saveError = "自动保存失败，可手动重试"
        }

        val continuous = _uiState.value.continuous
        _uiState.update {
            it.copy(
                bitmap = bitmap,
                mode = result.mode,
                hedrShiftHz = result.hedrShiftHz,
                slantRatio = result.slantRatio,
                decodedLines = result.decodedLines,
                totalLines = result.mode.imageLines,
                syncPulseCount = result.syncPulseCount,
                savedFileName = savedName,
                noiseHz = frame.noiseHz,
                completedFrames = if (complete) frame.index + 1 else it.completedFrames,
                error = saveError ?: it.error,
                status = if (complete) {
                    val prefix = if (continuous) "第 ${frame.index + 1} 张：" else ""
                    "$prefix${result.mode.displayName} 接收完成" +
                        (savedName?.let { n -> "，已保存 $n" } ?: "")
                } else {
                    "${result.mode.displayName} 接收中… ${result.decodedLines}/${result.mode.imageLines} 行"
                },
            )
        }

        if (complete) refreshGallery()
    }

    /** 按当前后处理设置生成显示用位图。 */
    private fun toDisplayBitmap(result: SstvDecoder.Result): Bitmap {
        val options = settingsStore.postProcess
        return if (options.anyEnabled) {
            SstvImageStore.toBitmap(
                SstvPostProcess.process(result.pixels, result.width, result.height, options),
                result.width,
                result.height,
            )
        } else {
            SstvImageStore.toBitmap(result.pixels, result.width, result.height)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 后处理开关
    // ══════════════════════════════════════════════════════════════════

    fun setHistogramStretch(enabled: Boolean) = updatePostProcess { it.copy(histogramStretch = enabled) }

    fun setMedianFilter(enabled: Boolean) = updatePostProcess { it.copy(medianFilter = enabled) }

    fun setRepairBadLines(enabled: Boolean) = updatePostProcess { it.copy(repairBadLines = enabled) }

    /**
     * 一次性开关三项后处理（主界面的总开关用）。
     *
     * 刻意不做成「连续调用三个单项 setter」：那会触发三次状态更新，每次都重建一遍
     * 640×496 的位图（三项处理 + PNG 编码），在弱机上足以感到卡顿。
     */
    fun setPostProcessAll(enabled: Boolean) {
        updatePostProcess {
            SstvPostProcess.Options(
                histogramStretch = enabled,
                medianFilter = enabled,
                repairBadLines = enabled,
            )
        }
    }

    private fun updatePostProcess(transform: (SstvPostProcess.Options) -> SstvPostProcess.Options) {
        val next = transform(settingsStore.postProcess)
        settingsStore.setPostProcess(next)
        // 已有结果时立即按新设置重建预览，让用户马上看到差别
        val snapshot = lastResult
        val bitmap = if (snapshot != null && _uiState.value.bitmap != null) {
            toDisplayBitmap(snapshot)
        } else {
            _uiState.value.bitmap
        }
        _uiState.update {
            it.copy(
                postProcess = next,
                bitmap = bitmap,
                enhancedPreview = next.anyEnabled,
                status = postProcessStatus(next),
            )
        }
    }

    private fun postProcessStatus(options: SstvPostProcess.Options): String {
        if (!options.anyEnabled) return "后处理已全部关闭"
        val parts = buildList {
            if (options.histogramStretch) add("直方图拉伸")
            if (options.medianFilter) add("中值降噪")
            if (options.repairBadLines) add("坏行修复")
        }
        return "后处理已开启：${parts.joinToString(" + ")}"
    }

    // ══════════════════════════════════════════════════════════════════
    // 离线导入（T2）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 导入一段录音并离线解码。
     *
     * 流程：**按内容选通路**读成 48 kHz 单声道（RIFF/WAVE 走 [SstvWavReader] 的纯 Kotlin
     * 解析，其余容器走 [SstvMediaAudioReader] 的平台解码器）→ VIS 解码；失败且开启了
     * 盲扫时用 [SstvBlindScan] 反推模式再解一次。全程在 IO 线程，UI 只看到「导入中…」
     * 与最终结果。
     *
     * **为什么按内容而不是按扩展名/mime 选路**：SAF 返回的 URI 常常没有可用后缀
     * （形如 `content://…/document/1234`），`ContentResolver.getType` 也可能为 null 或
     * 与实际内容不符；而「是不是 RIFF/WAVE」只需读 12 个字节就能确定，比任何元数据都可靠。
     * 只有 WAV 解析失败时才退回平台解码器 —— 那正好覆盖 IMA ADPCM / µ-law 这类
     * 本解析器刻意不支持的 WAV 变体。
     *
     * @param uri 由 `ActivityResultContracts.OpenDocument` 返回的文件 URI
     */
    fun importAudio(uri: Uri) {
        if (_uiState.value.importing) return
        _uiState.update {
            it.copy(importing = true, importStatus = "正在读取音频…", error = null, blindCandidates = emptyList())
        }

        viewModelScope.launch {
            val context = getApplication<Application>()
            // ① 先落到缓存文件：既能拿到大小（挡住误选的大文件），也能让读取器用 File API
            val temp = withContext(Dispatchers.IO) {
                runCatching {
                    val target = importTempFile(context, uri)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                        target
                    }
                }.getOrNull()
            }
            if (temp == null) {
                _uiState.update {
                    it.copy(importing = false, importStatus = null, error = "无法读取所选文件")
                }
                return@launch
            }
            if (temp.length() > MAX_IMPORT_BYTES) {
                val mb = temp.length() / 1024 / 1024
                temp.delete()
                _uiState.update {
                    it.copy(
                        importing = false,
                        importStatus = null,
                        error = "音频文件过大（${mb} MB），请先截取单帧片段",
                    )
                }
                return@launch
            }

            // ② 读音频：可能走平台解码器（MediaCodec 的 dequeue 是阻塞调用），放 IO
            val readOutcome = withContext(Dispatchers.IO) {
                try {
                    readAudioFile(temp)
                } finally {
                    // 无论成败都要清掉缓存文件，否则解码异常时会在 cacheDir 里留下垃圾
                    temp.delete()
                }
            }
            when (readOutcome) {
                is SstvWavReader.Outcome.Failure -> _uiState.update {
                    it.copy(importing = false, importStatus = null, error = readOutcome.reason)
                }

                is SstvWavReader.Outcome.Success -> decodeImported(readOutcome)
            }
        }
    }

    /**
     * 按**内容**选择导入通路，两条通路返回同一种 [SstvWavReader.Outcome]。
     *
     * 先看是不是 RIFF/WAVE（读 12 字节，不占内存）：是就交给纯 Kotlin 解析器 ——
     * 它对 24/32 位整数与 32/64 位浮点 WAV 的支持比系统提取器可靠，而且逻辑有单测；
     * 不是就直接交给平台解码器（FLAC / MP3 / AAC / OGG 都在这里）。
     *
     * WAV 解析失败时再兜一次平台解码器：IMA ADPCM、µ-law 这类 WAV 变体本解析器
     * 刻意不支持，但系统解码器往往能解。两者都失败时把两个原因都报出来 ——
     * WAV 的原因更具体（能说出是哪个编码/位深），平台的原因能说明系统侧也救不了，
     * 合在一起才对排查有用。
     */
    private fun readAudioFile(temp: File): SstvWavReader.Outcome {
        if (!SstvWavReader.isWavFile(temp)) {
            return SstvMediaAudioReader.read(temp)
        }
        when (val wav = SstvWavReader.read(temp)) {
            is SstvWavReader.Outcome.Success -> return wav
            is SstvWavReader.Outcome.Failure -> {
                val media = SstvMediaAudioReader.read(temp)
                return when (media) {
                    is SstvWavReader.Outcome.Success -> media
                    is SstvWavReader.Outcome.Failure ->
                        SstvWavReader.Outcome.Failure("${wav.reason}；备用解码器亦失败：${media.reason}")
                }
            }
        }
    }

    /**
     * 导入时的缓存文件。
     *
     * 刻意**保留真实扩展名**（早先固定写死 `sstv_import.wav`）：`MediaExtractor` 在部分
     * 系统版本上会把文件后缀当作选择提取器的提示，把 MP3 命名成 `.wav` 有被 WAV 提取器
     * 错误接管的先例。拿不到 mime 也拿不到后缀时就用无后缀名 —— 无后缀比错误后缀安全，
     * 因为两条通路本来都是按内容判断的。
     */
    private fun importTempFile(context: Application, uri: Uri): File {
        val extension = audioExtensionOf(context, uri)
        val name = if (extension.isEmpty()) IMPORT_TEMP_BASE else "$IMPORT_TEMP_BASE.$extension"
        return File(context.cacheDir, name)
    }

    /** 由 mime 推断扩展名；mime 不可用或不在已知表里时返回空串。 */
    private fun audioExtensionOf(context: Application, uri: Uri): String {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime != null) {
            MIME_EXTENSIONS[mime.lowercase()]?.let { return it }
        }
        // mime 靠不住时退一步看 URI 后缀（Downloads 里选来的文件常带真实文件名）
        val segment = runCatching { uri.lastPathSegment }.getOrNull().orEmpty()
        val candidate = segment.substringAfterLast('.', "").lowercase()
        return if (candidate in KNOWN_AUDIO_EXTENSIONS) candidate else ""
    }

    private suspend fun decodeImported(audio: SstvWavReader.Outcome.Success) {
        val rate = SstvWavReader.TARGET_SAMPLE_RATE
        val manualMode = presetOverride ?: settingsStore.manualMode
        val describe = describeAudio(audio)
        // 保留样本：盲扫候选之间切换时无需让用户重新导入
        importedAudio = audio.samples
        _uiState.update { it.copy(importStatus = "$describe，正在解码…") }

        val decoded = withContext(Dispatchers.Default) {
            SstvDecoder(rate).decode(audio.samples, forcedMode = manualMode)
        }

        when (decoded) {
            is SstvDecoder.Outcome.Success -> showImported(decoded.result, describe, candidates = emptyList())
            is SstvDecoder.Outcome.Failure -> {
                if (!settingsStore.blindScanOnFailure) {
                    _uiState.update {
                        it.copy(
                            importing = false,
                            importStatus = null,
                            error = "${decoded.reason}（可在设置中开启盲扫自动判定）",
                        )
                    }
                    return
                }
                blindScanImported(audio.samples, describe, decoded.reason)
            }
        }
    }

    /** VIS 失败后的盲扫兜底：反推模式 → 用该模式再解一次。 */
    private suspend fun blindScanImported(
        samples: FloatArray,
        describe: String,
        visFailure: String,
    ) {
        _uiState.update { it.copy(blindScanning = true, importStatus = "$describe；VIS 缺失，正在盲扫模式…") }
        val scan = withContext(Dispatchers.Default) {
            SstvBlindScan.scan(samples, SstvWavReader.TARGET_SAMPLE_RATE)
        }

        val best = scan.best
        if (best == null) {
            _uiState.update {
                it.copy(
                    importing = false,
                    blindScanning = false,
                    importStatus = null,
                    blindCandidates = emptyList(),
                    error = "$visFailure；盲扫亦失败：${scan.reason ?: "未找到候选模式"}",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                blindCandidates = scan.candidates,
                blindConfidence = scan.confidence,
                importStatus = "$describe；盲扫判定为 ${best.mode.displayName}，正在解码…",
            )
        }

        val decoded = withContext(Dispatchers.Default) {
            SstvDecoder(SstvWavReader.TARGET_SAMPLE_RATE).decode(samples, forcedMode = best.mode)
        }
        _uiState.update { it.copy(blindScanning = false) }
        when (decoded) {
            is SstvDecoder.Outcome.Success -> showImported(
                decoded.result,
                "$describe；VIS 缺失，盲扫判定 ${best.mode.displayName}" +
                    "（置信度 ${(scan.confidence * 100).toInt()}%）",
                candidates = scan.candidates,
            )

            is SstvDecoder.Outcome.Failure -> _uiState.update {
                it.copy(
                    importing = false,
                    importStatus = null,
                    error = "盲扫判定为 ${best.mode.displayName} 但解码失败：${decoded.reason}",
                )
            }
        }
    }

    /**
     * 用盲扫候选中的另一个模式重新解码**已导入的音频**。
     *
     * 盲扫的置信度不可能总是 100%（同一行周期的模式本质上不可分），因此必须让用户
     * 能一键换候选 —— 这正是二期风险项 R3（盲扫误判）的缓解手段。
     */
    fun decodeImportedWith(mode: SstvMode) {
        val samples = importedAudio ?: run {
            _uiState.update { it.copy(error = "没有可重解的音频，请重新导入") }
            return
        }
        if (_uiState.value.importing) return
        _uiState.update { it.copy(importing = true, importStatus = "正在按 ${mode.displayName} 重新解码…") }
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.Default) {
                SstvDecoder(SstvWavReader.TARGET_SAMPLE_RATE).decode(samples, forcedMode = mode)
            }
            when (decoded) {
                is SstvDecoder.Outcome.Success -> showImported(
                    decoded.result,
                    "按候选 ${mode.displayName} 重新解码",
                    candidates = _uiState.value.blindCandidates,
                )

                is SstvDecoder.Outcome.Failure -> _uiState.update {
                    it.copy(importing = false, importStatus = null, error = "${mode.displayName}：${decoded.reason}")
                }
            }
        }
    }

    private fun describeAudio(audio: SstvWavReader.Outcome.Success): String = buildString {
        append("已读取 ")
        append("%.1f".format(audio.durationSeconds))
        append(" 秒")
        if (audio.resampled) append("（${audio.sourceSampleRate} Hz → ${SstvWavReader.TARGET_SAMPLE_RATE} Hz）")
        if (audio.sourceChannels > 1) append("，${audio.sourceChannels} 声道已下混")
        if (audio.truncated) append("，已截断到 ${SstvWavReader.MAX_SECONDS.toInt()} 秒")
    }

    private suspend fun showImported(
        result: SstvDecoder.Result,
        describe: String,
        candidates: List<SstvBlindScan.Candidate>,
    ) {
        lastResult = result
        autoSaveAttempted = true
        // 位图重建与落盘都是重活，必须离开主线程（本函数在 viewModelScope 的 Main 上）
        val bitmap = withContext(Dispatchers.Default) { toDisplayBitmap(result) }
        var savedName: String? = null
        if (settingsStore.autoSave) {
            savedName = withContext(Dispatchers.IO) {
                val options = settingsStore.postProcess
                if (options.anyEnabled) {
                    imageStore.savePixels(
                        SstvPostProcess.process(result.pixels, result.width, result.height, options),
                        result.width,
                        result.height,
                        result.mode.displayName,
                        enhanced = true,
                    )?.name
                } else {
                    imageStore.save(result)?.name
                }
            }
        }
        _uiState.update {
            it.copy(
                importing = false,
                importStatus = null,
                bitmap = bitmap,
                mode = result.mode,
                visCode = result.mode.visCode,
                hedrShiftHz = result.hedrShiftHz,
                slantRatio = result.slantRatio,
                decodedLines = result.decodedLines,
                totalLines = result.mode.imageLines,
                syncPulseCount = result.syncPulseCount,
                savedFileName = savedName,
                blindCandidates = candidates,
                status = "$describe → ${result.mode.displayName} 解码完成 ${result.decodedLines} 行" +
                    (savedName?.let { n -> "，已保存 $n" } ?: ""),
                error = null,
            )
        }
        refreshGallery()
    }

    /** 清除导入/盲扫的临时提示。 */
    fun clearImportStatus() {
        _uiState.update { it.copy(importStatus = null, error = null) }
    }

    // ══════════════════════════════════════════════════════════════════
    // 图库（T1）
    // ══════════════════════════════════════════════════════════════════

    /** 为图库列表解码缩略图（只在协程中调用）。 */
    fun loadThumbnails(reqWidth: Int = 240, reqHeight: Int = 240) {
        val entries = _uiState.value.gallery
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                entries.mapNotNull { entry ->
                    if (_uiState.value.thumbnails.containsKey(entry.file.name)) return@mapNotNull null
                    val bitmap = imageStore.decodeThumbnail(entry.file, reqWidth, reqHeight)
                    bitmap?.let { entry.file.name to it }
                }
            }
            if (decoded.isEmpty()) return@launch
            _uiState.update { it.copy(thumbnails = it.thumbnails + decoded) }
        }
    }

    fun deleteImage(entry: SstvImageStore.Entry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { imageStore.delete(entry.file) }
            _uiState.update {
                it.copy(
                    thumbnails = it.thumbnails - entry.file.name,
                    viewing = if (it.viewing?.file == entry.file) null else it.viewing,
                    viewingBitmap = if (it.viewing?.file == entry.file) null else it.viewingBitmap,
                    status = "已删除 ${entry.file.name}",
                )
            }
            refreshGallery()
        }
    }

    /** 打开全屏查看（大图解码必须放协程）。 */
    fun openViewer(entry: SstvImageStore.Entry) {
        viewingFile = entry.file
        _uiState.update { it.copy(viewing = entry, viewingBitmap = null) }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { imageStore.decodeThumbnail(entry.file, 1600, 1600) }
            // 用户可能已经切走/关闭，避免把过期结果写回
            if (viewingFile != entry.file) return@launch
            _uiState.update { it.copy(viewingBitmap = bitmap) }
        }
    }

    fun closeViewer() {
        viewingFile = null
        _uiState.update { it.copy(viewing = null, viewingBitmap = null) }
    }

    /** 导出到系统相册。 */
    fun exportToGallery(entry: SstvImageStore.Entry) {
        val context = getApplication<Application>()
        _uiState.update { it.copy(galleryBusy = "正在导出 ${entry.file.name}…") }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { imageStore.exportToGallery(context, entry.file) }
            val message = when (outcome) {
                is SstvImageStore.ExportOutcome.Success ->
                    "已导出到相册（Pictures/${SstvImageStore.GALLERY_ALBUM}）"

                is SstvImageStore.ExportOutcome.Failure -> outcome.reason
            }
            _uiState.update { it.copy(galleryBusy = null, status = message) }
        }
    }

    /** 通过 FileProvider 分享（返回 URI 由 UI 发起 `ACTION_SEND`）。 */
    fun shareUri(entry: SstvImageStore.Entry): Uri? =
        imageStore.shareUri(getApplication(), entry.file)

    // ══════════════════════════════════════════════════════════════════
    // 发射（T3）
    // ══════════════════════════════════════════════════════════════════

    fun setTxMode(mode: SstvMode) {
        settingsStore.txModeName = mode.name
        _uiState.update { it.copy(txModeName = mode.name, txStatus = "已选择 ${mode.displayName}") }
        // 图像按新分辨率重新装配（源图已按旧模式缩放，直接复用会变形）
        reloadTxSource()
    }

    fun setTxIncludeVis(include: Boolean) {
        _uiState.update { it.copy(txIncludeVis = include) }
        // 载入时的「约 X 秒」是按当时的 VIS 开关估的：切开关后重算一次，
        // 否则状态行时长与实际发射时长不一致（预览位图不受影响，无需重载图像）
        if (_uiState.value.txPreview != null && _uiState.value.txMode != null) {
            reloadTxStatusEstimate()
        }
    }

    /** 按当前模式与 VIS 开关重算载入状态行里的「约 X 秒」。 */
    private fun reloadTxStatusEstimate() {
        val mode = _uiState.value.txMode ?: return
        val seconds = mode.frameSeconds +
            (SstvEncoder.VIS_TOTAL_SECONDS.takeIf { _uiState.value.txIncludeVis } ?: 0.0)
        val current = _uiState.value.txStatus
        // 只替换「约 X 秒」这一段，保留可能存在的裁剪说明等其他内容
        val updated = current.replaceFirst(Regex("约 \\d+ 秒"), "约 ${"%.0f".format(seconds)} 秒")
        if (updated != current) {
            _uiState.update { it.copy(txStatus = updated) }
        }
    }

    fun setCallsign(value: String) {
        settingsStore.callsign = value
        _uiState.update { it.copy(callsign = settingsStore.callsign) }
    }

    /**
     * 开关呼号叠加。
     *
     * 叠加呼号只影响「位图 → 图像源」这一步，源图本身没变，因此这里只做**本地重装配**：
     * 不再回到相册/文件的 URI 重新读一遍（旧实现这么做，遇到只授权一次的 provider 就会
     * 把已经载入好的图弄丢）。
     */
    fun setTxOverlayCallsign(enabled: Boolean) {
        settingsStore.txOverlayCallsign = enabled
        _uiState.update { it.copy(txOverlayCallsign = enabled) }
        reloadTxSource()
    }

    /**
     * 从系统相册/文件选择器选图作为发射源。
     *
     * **关键（HK 修复）**：先用 `contentResolver.openInputStream` 把所选内容**复制到一个**
     * 缓存文件，再用这个文件解码。原因见 [SstvBitmapSource] 的类注释 —— 同一 URI 反复
     * 打开在不少 provider 上会失败，表现为「选完图毫无反应」；而缓存文件一次落盘后，
     * 切模式、开关呼号都只需读本地文件，稳定且不受 URI 授权有效期影响。
     */
    fun setTxImage(uri: Uri) {
        // 每次选图都作废之前还在读的请求：慢 provider 上旧请求可能后完成，
        // 不加令牌会把用户刚选的新图又覆盖成上一张（预览/发射源随之错乱）。
        val token = ++txLoadToken
        _uiState.update { it.copy(txStatus = "正在读取所选图片…") }
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { copyTxImageToCache(uri, token) }
            if (token != txLoadToken) return@launch
            if (cached == null) {
                _uiState.update {
                    it.copy(
                        txStatus = "无法读取所选图片（可能没有读取权限或该来源不支持），请换一张或从图库选择",
                    )
                }
                return@launch
            }
            txOrigin = cached
            loadTxSource()
        }
    }

    /** 从 SSTV 图库（已解码图像）选图作为发射源。 */
    fun setTxFromGallery(entry: SstvImageStore.Entry) {
        txOrigin = entry.file
        loadTxSource()
    }

    /**
     * 发射源文件。
     *
     * 相册选择会先复制到 `cacheDir/sstv_tx` 下的缓存副本；图库选择直接用图库文件。
     * 只保存文件而不是 URI —— URI 授权可能过期，也不能保证可重复打开（见 [setTxImage]）。
     */
    private var txOrigin: java.io.File? = null

    /**
     * 发射源装载令牌：每次选图 / 重装配 +1，异步结果回来时对不上就丢弃。
     *
     * 主线程自增、IO/Default 线程读取，故为 `@Volatile`。
     */
    @Volatile
    private var txLoadToken = 0

    /**
     * 切模式后重装配。
     *
     * 直接读缓存副本重新解码：模式不同 => 目标分辨率不同，必须从原图重新缩放，
     * 复用按旧分辨率缩过的位图会变形。
     */
    private fun reloadTxSource() {
        if (txOrigin != null) loadTxSource()
    }

    /**
     * 把所选 URI 复制到缓存目录，文件名用 provider 给出的显示名（失败时退化为固定名）。
     *
     * @return 缓存文件；读取失败返回 null
     */
    private fun copyTxImageToCache(uri: Uri, token: Int): java.io.File? = runCatching {
        val context = getApplication<Application>()
        val dir = java.io.File(context.cacheDir, TX_IMAGE_CACHE_DIR).apply { mkdirs() }
        // 显示名只做「展示与扩展名提示」用途，这里清洗掉路径分隔符，避免写出目录外
        val displayName = runCatching { queryDisplayName(context.contentResolver, uri) }.getOrNull()
        val safeName = displayName?.replace('/', '_')?.replace('\\', '_')?.takeIf { it.isNotBlank() }
        val target = java.io.File(dir, safeName ?: TX_IMAGE_CACHE_NAME)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        // 写盘期间用户又选了一张：删掉这份过期副本后放弃。
        // 必须赶在清理其它文件之前判断，否则会误删新请求刚写好的缓存。
        if (token != txLoadToken) {
            runCatching { target.delete() }
            return null
        }
        // 清掉上一次的旧缓存，避免缓存目录无限增长
        dir.listFiles()?.forEach { if (it != target) runCatching { it.delete() } }
        target.takeIf { it.length() > 0 }
    }.getOrNull()

    /** 查询 SAF URI 的显示名（`OpenableColumns.DISPLAY_NAME`）。 */
    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun loadTxSource() {
        val origin = txOrigin ?: return
        val mode = _uiState.value.txMode
        val callsign = if (_uiState.value.txOverlayCallsign) _uiState.value.callsign else null
        // 切模式 / 开关呼号会触发重装配；更晚发起的请求才是用户想要的，旧结果直接丢弃
        val token = ++txLoadToken
        _uiState.update { it.copy(txStatus = "正在载入图片…") }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                SstvBitmapSource.loadOrError(origin, mode, callsign)
            }
            if (token != txLoadToken) return@launch
            val loaded = outcome.getOrNull()
            if (loaded == null) {
                txSource = null
                val reason = outcome.exceptionOrNull()?.message ?: "未知原因"
                _uiState.update {
                    it.copy(txPreview = null, txSourceLabel = null, txStatus = "图片载入失败：$reason")
                }
                return@launch
            }
            val result = loaded.source
            txSource = result
            val preview = withContext(Dispatchers.Default) {
                val pixels = IntArray(result.width * result.height)
                for (y in 0 until result.height) {
                    for (x in 0 until result.width) {
                        pixels[y * result.width + x] = result.rgbAt(x, y)
                    }
                }
                SstvImageStore.toBitmap(pixels, result.width, result.height)
            }
            if (token != txLoadToken) return@launch
            // 与实际编码一致：VIS 头受「包含 VIS 头」开关控制，估算不能恒按含 VIS
            val seconds = mode.frameSeconds +
                (SstvEncoder.VIS_TOTAL_SECONDS.takeIf { _uiState.value.txIncludeVis } ?: 0.0)
            // 源图比例与模式比例不一致时会被居中裁掉一圈，明确告知用户裁了多少
            val cropNote = if (loaded.cropped) {
                "；已按 ${mode.displayName} 比例自动裁剪原图 " +
                    "${loaded.originalWidth}×${loaded.originalHeight}"
            } else {
                ""
            }
            _uiState.update {
                it.copy(
                    txPreview = preview,
                    txSourceLabel = origin.name,
                    txStatus = "已载入 ${result.width}×${result.height}，按 ${mode.displayName} 发射约 " +
                        "${"%.0f".format(seconds)} 秒$cropNote",
                )
            }
        }
    }

    /** 开始发射（对着电台话筒播放）。 */
    fun startTx() {
        val source = txSource
        if (source == null) {
            _uiState.update { it.copy(txStatus = "请先选择一张图片") }
            return
        }
        if (_uiState.value.txPlaying) return
        val mode = _uiState.value.txMode
        val includeVis = _uiState.value.txIncludeVis
        val encoder = SstvEncoder(SstvWavReader.TARGET_SAMPLE_RATE)
        val total = encoder.estimateSampleCount(mode, includeVis)

        player?.stop()
        player = com.example.hamkit.data.sstv.SstvAudioPlayer(
            sampleRate = SstvWavReader.TARGET_SAMPLE_RATE,
            onProgress = { progress -> _uiState.update { it.copy(txProgress = progress) } },
            onFinished = {
                _uiState.update { it.copy(txPlaying = false, txProgress = 1f, txStatus = "发射完成") }
            },
            onError = { reason ->
                _uiState.update { it.copy(txPlaying = false, txStatus = reason) }
            },
        ).also { it.play(encoder.encodeChunks(source, mode, includeVis = includeVis), total) }

        _uiState.update {
            it.copy(
                txPlaying = true,
                txProgress = 0f,
                txStatus = "正在发射 ${mode.displayName}（约 ${"%.0f".format(total.toDouble() / SstvWavReader.TARGET_SAMPLE_RATE)} 秒）",
            )
        }
    }

    fun stopTx() {
        player?.stop()
        player = null
        _uiState.update { it.copy(txPlaying = false, txStatus = "已停止发射") }
    }

    /**
     * 导出音频文件（SAF 创建文档返回的 URI）。
     *
     * 这是发射页的「替代路径」：手机扬声器 → 电台话筒的声学链路失真不可控（见
     * [com.example.hamkit.data.sstv.SstvWavWriter] 的说明），导出后用音频线接入能得到
     * 干净得多的信号。导出同样按块进行，PD-290（约 28 MB）也不会撑爆内存。
     */
    fun exportTxAudio(uri: Uri) {
        val source = txSource
        if (source == null) {
            _uiState.update { it.copy(txStatus = "请先选择一张图片") }
            return
        }
        if (_uiState.value.txPlaying) {
            _uiState.update { it.copy(txStatus = "请先停止发射再导出") }
            return
        }
        val mode = _uiState.value.txMode
        val includeVis = _uiState.value.txIncludeVis
        _uiState.update { it.copy(txStatus = "正在导出音频…") }

        viewModelScope.launch {
            val context = getApplication<Application>()
            val samples = withContext(Dispatchers.IO) {
                runCatching {
                    val encoder = SstvEncoder(SstvWavReader.TARGET_SAMPLE_RATE)
                    val chunks = encoder.encodeChunks(source, mode, includeVis = includeVis)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        SstvWavWriter.writePcm16ToStream(
                            tempDir = context.cacheDir,
                            sampleRate = SstvWavReader.TARGET_SAMPLE_RATE,
                            chunks = chunks,
                            out = out,
                        )
                    }
                }.getOrNull()
            }
            _uiState.update {
                it.copy(
                    txStatus = if (samples != null) {
                        "已导出 WAV（${"%.1f".format(samples.toDouble() / SstvWavReader.TARGET_SAMPLE_RATE)} 秒）"
                    } else {
                        "导出失败"
                    },
                )
            }
        }
    }

    private companion object {
        /**
         * 导入音频的大小上限。
         *
         * WAV 通路会把整个文件读成字节数组再转成 FloatArray，峰值内存约为文件大小的
         * 3 倍；96 MB 对应约 8 分钟 48 kHz/16 位单声道录音，已远超单帧上限
         * （[SstvWavReader.MAX_SECONDS] = 300 秒），此处只用于挡住误选的大文件。
         * 压缩容器（FLAC/MP3）走平台解码器、边解边在 [com.example.hamkit.data.sstv.SstvAudioConvert.MonoAccumulator]
         * 里封顶，因此同一个上限对它们更宽松，无需单独设阈值。
         */
        const val MAX_IMPORT_BYTES = 96L * 1024 * 1024

        /**
         * 导入缓存的文件名主干（真实扩展名由 mime 推断后拼上）。
         *
         * 每次导入都覆盖同名文件：缓存目录里只留一份，不会随导入次数堆积。
         */
        const val IMPORT_TEMP_BASE = "sstv_import"

        /** 发射源图片的缓存子目录（在 `cacheDir` 下）：每次选图只保留最新一份。 */
        const val TX_IMAGE_CACHE_DIR = "sstv_tx"

        /** 拿不到 provider 显示名时的兜底文件名（扩展名不影响 `decodeStream` 判定格式）。 */
        const val TX_IMAGE_CACHE_NAME = "sstv_tx_source.img"

        /**
         * 持续接收每次读取的样本数（48 kHz 下约 42 ms）。
         *
         * 取 2048：既能让解调器与解码器保持「块级」输入（每块开销可摊薄），
         * 又不至于让画面延迟明显（一帧 42 ms 远低于人眼可感阈值）。
         */
        private const val PERSISTENT_READ_SAMPLES = 2048

        /** 持续接收新增多少行才重建预览（节流：640 宽的位图重建并不便宜）。 */
        private const val PERSISTENT_RENDER_ROWS = 4

        /**
         * 预览重建的最小间隔（毫秒）。
         *
         * 约 8 帧/秒：肉眼已是连续增长，又给建图（高度越大越慢）留出余量。
         * 与 [PERSISTENT_RENDER_ROWS] 双闸门 —— 行数闸门（[shouldRenderPreview]）发出请求，
         * 本间隔再卡一次时间：距上次重建不足 120 ms 时先 delay 补齐，两关都过才真正建图。
         */
        private const val PERSISTENT_MIN_RENDER_INTERVAL_MS = 120L

        /** 停止接收时等待采集协程退出的上限（毫秒）；超时后仍继续收尾，避免永远卡住。 */
        private const val PERSISTENT_STOP_JOIN_TIMEOUT_MS = 3_000L

        /** 瀑布图快照刷新间隔：约 6–7 帧/秒，肉眼看是连续滚动，开销可忽略。 */
        const val WATERFALL_REFRESH_MS = 150L

        /** 瀑布图的背景色（无信号处）。 */
        const val WATERFALL_EMPTY = 0xFF101014.toInt()

        /** mime → 扩展名。只列业余录音会遇到的容器，其余走无后缀名。 */        private val MIME_EXTENSIONS = mapOf(
            "audio/wav" to "wav",
            "audio/wave" to "wav",
            "audio/x-wav" to "wav",
            "audio/vnd.wave" to "wav",
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
            "audio/mp4" to "m4a",
            "audio/m4a" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg",
            "application/ogg" to "ogg",
            "audio/opus" to "opus",
            "audio/3gpp" to "3gp",
        )

        /** URI 后缀的合法取值（mime 不可用时的兜底）。 */
        private val KNOWN_AUDIO_EXTENSIONS = MIME_EXTENSIONS.values.toSet()
    }
}

/**
 * 人类可读的字节数。
 *
 * 刻意放在**文件顶层**而不是 ViewModel 的成员：`SstvUiState` 的属性（`recordingsLabel`）
 * 也要用它，而成员函数无法从另一个类里访问。
 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
