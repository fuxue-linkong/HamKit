package com.example.hamkit.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.sstv.SstvDecoder
import com.example.hamkit.data.sstv.SstvImageStore
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.data.sstv.SstvRecorder
import com.example.hamkit.data.sstv.SstvSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SSTV 接收界面的状态。
 *
 * @param decodedLines 已解码的图像行数（图像自上而下逐步显现）
 * @param totalLines 当前模式的总行数
 * @param bitmap 解码预览图（每次部分解码后重建，靠引用变化触发 Compose 重绘）
 * @param galleryCount 图库中的图像数量（在协程中刷新，避免主线程做文件 IO）
 */
data class SstvUiState(
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
    val galleryCount: Int = 0,
    val error: String? = null,
) {
    /** 接收进度（0–1）。 */
    val progress: Float
        get() = if (totalLines > 0) decodedLines.toFloat() / totalLines else 0f
}

/**
 * SSTV 功能的状态持有者。
 *
 * 采集与解码由 [SstvRecorder] 在后台完成，这里只负责把回调结果转换为 UI 状态。
 */
class SstvViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SstvSettingsStore(application)
    private val imageStore = SstvImageStore(application)
    private var recorder: SstvRecorder? = null

    /**
     * 由卫星详情页带入的**临时**模式覆盖。
     *
     * 刻意不写入 [SstvSettingsStore]：从 ISS 详情页进入一次后，用户回到首页再打开
     * SSTV 时仍应是「自动识别」，而不该被上一次的预置悄悄改掉。
     */
    private var presetOverride: SstvMode? = null

    /**
     * 最近一次解码结果，用于保存。
     *
     * 由采集协程（IO 线程）写入、UI 线程读取，故标记 `@Volatile` 保证可见性。
     */
    @Volatile
    private var lastResult: SstvDecoder.Result? = null

    /** 本次接收是否已尝试过自动保存（避免保存失败后每轮部分解码都重写文件）。 */
    private var autoSaveAttempted = false

    private val _uiState = MutableStateFlow(
        SstvUiState(
            manualModeName = settingsStore.manualModeName,
            autoSave = settingsStore.autoSave,
            livePreview = settingsStore.livePreview,
        ),
    )
    val uiState: StateFlow<SstvUiState> = _uiState.asStateFlow()

    init {
        refreshGalleryCount()
    }

    /** 在协程中刷新图库数量（文件 IO 不落在主线程）。 */
    private fun refreshGalleryCount() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { imageStore.list().size }
            _uiState.update { it.copy(galleryCount = count) }
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
        // 预置优先于用户默认设置
        val manualMode = presetOverride ?: settingsStore.manualMode
        autoSaveAttempted = false
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
            )
        }

        recorder = SstvRecorder(
            context = getApplication(),
            forcedMode = manualMode,
            onVisDetected = { detected ->
                _uiState.update {
                    it.copy(
                        visCode = detected.visCode,
                        mode = detected.mode,
                        hedrShiftHz = detected.hedrShiftHz,
                        totalLines = detected.mode?.imageLines ?: 0,
                        status = detected.mode?.let { mode ->
                            "已识别 ${mode.displayName}（VIS 0x${detected.visCode.toString(16).uppercase()}）"
                        } ?: "识别到未支持的模式（VIS 0x${detected.visCode.toString(16).uppercase()}）",
                        error = detected.mode?.let { _ -> null } ?: "该模式暂不支持解码",
                    )
                }
            },
            onProgress = { result -> onDecoded(result) },
            onStatus = { text -> _uiState.update { it.copy(status = text) } },
        ).also { it.start() }
    }

    fun stopReceiving() {
        recorder?.stop()
        recorder = null
        _uiState.update {
            val partial = it.bitmap != null && it.decodedLines < it.totalLines
            it.copy(
                isReceiving = false,
                status = when {
                    partial -> "已停止：收到 ${it.decodedLines}/${it.totalLines} 行，可手动保存"
                    it.bitmap != null -> "接收已停止"
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
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { imageStore.save(snapshot) }
            if (file != null) refreshGalleryCount()
            _uiState.update {
                it.copy(
                    savedFileName = file?.name,
                    error = if (file == null) "保存失败" else null,
                    status = if (file != null) "已保存到图库：${file.name}" else it.status,
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

    fun clearImage() {
        lastResult = null
        autoSaveAttempted = false
        _uiState.update {
            it.copy(
                bitmap = null,
                decodedLines = 0,
                totalLines = 0,
                savedFileName = null,
                visCode = null,
                status = "已清空",
            )
        }
    }

    fun clearGallery() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { imageStore.clear() }
            _uiState.update { it.copy(status = "图库已清空", galleryCount = 0) }
        }
    }

    override fun onCleared() {
        recorder?.stop()
        recorder = null
        super.onCleared()
    }

    private fun onDecoded(result: SstvDecoder.Result) {
        lastResult = result
        val shouldUpdatePreview = settingsStore.livePreview
        // 每次重建 Bitmap：内容变化但引用不变时 Compose 不会重绘
        val bitmap = if (shouldUpdatePreview) {
            SstvImageStore.toBitmap(result.pixels, result.width, result.height)
        } else {
            _uiState.value.bitmap
        }

        val complete = result.decodedLines >= result.mode.imageLines
        var savedName = _uiState.value.savedFileName
        var saveError: String? = null
        // 自动保存：整帧收齐后只尝试一次，失败不重试（否则每轮部分解码都会重写文件）
        if (complete && !autoSaveAttempted && settingsStore.autoSave) {
            autoSaveAttempted = true
            val saved = imageStore.save(result)
            savedName = saved?.name
            if (saved == null) saveError = "自动保存失败，可手动重试"
        }

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
                galleryCount = if (savedName != null && it.savedFileName == null) {
                    it.galleryCount + 1
                } else {
                    it.galleryCount
                },
                error = saveError ?: it.error,
                status = if (complete) {
                    "${result.mode.displayName} 接收完成" + (savedName?.let { n -> "，已保存 $n" } ?: "")
                } else {
                    "${result.mode.displayName} 接收中… ${result.decodedLines}/${result.mode.imageLines} 行"
                },
            )
        }

        // 收满一帧后由采集器自行停止；这里异步收敛 UI 状态与资源
        if (complete && _uiState.value.isReceiving) {
            viewModelScope.launch {
                recorder?.stop()
                recorder = null
                _uiState.update { it.copy(isReceiving = false) }
            }
        }
    }
}
