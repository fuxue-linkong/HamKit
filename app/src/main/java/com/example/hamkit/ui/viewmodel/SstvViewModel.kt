package com.example.hamkit.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.example.hamkit.data.sstv.SstvDecoder
import com.example.hamkit.data.sstv.SstvImageStore
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.data.sstv.SstvRecorder
import com.example.hamkit.data.sstv.SstvSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * SSTV 接收界面的状态。
 *
 * @param decodedLines 已解码的图像行数（图像自上而下逐步显现）
 * @param totalLines 当前模式的总行数
 * @param bitmap 解码预览图（每次部分解码后重建，靠引用变化触发 Compose 重绘）
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
    val autoSave: Boolean = true,
    val livePreview: Boolean = true,
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

    private val _uiState = MutableStateFlow(
        SstvUiState(
            manualModeName = settingsStore.manualModeName,
            autoSave = settingsStore.autoSave,
            livePreview = settingsStore.livePreview,
        ),
    )
    val uiState: StateFlow<SstvUiState> = _uiState.asStateFlow()

    /** 图库中的历史条目（供图库界面使用）。 */
    fun galleryEntries(): List<SstvImageStore.Entry> = imageStore.list()

    fun startReceiving() {
        if (_uiState.value.isReceiving) return
        val manualMode = settingsStore.manualMode
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
            it.copy(
                isReceiving = false,
                status = if (it.bitmap != null) "接收已停止" else "已停止",
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
        val file = imageStore.save(snapshot)
        _uiState.update {
            it.copy(
                savedFileName = file?.name,
                error = if (file == null) "保存失败" else null,
                status = if (file != null) "已保存到图库：${file.name}" else it.status,
            )
        }
    }

    fun setManualMode(mode: SstvMode?) {
        settingsStore.manualModeName = mode?.name
        _uiState.update {
            it.copy(
                manualModeName = mode?.name,
                status = mode?.let { m -> "已手动锁定 ${m.displayName}" } ?: "已切回 VIS 自动识别",
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
        imageStore.clear()
        _uiState.update { it.copy(status = "图库已清空") }
    }

    override fun onCleared() {
        recorder?.stop()
        recorder = null
        super.onCleared()
    }

    /** 最近一次解码结果，用于保存。 */
    private var lastResult: SstvDecoder.Result? = null

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
        // 自动保存：仅在整帧收齐后触发一次
        if (complete && savedName == null && settingsStore.autoSave) {
            savedName = imageStore.save(result)?.name
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
                status = if (complete) {
                    "${result.mode.displayName} 接收完成" + (savedName?.let { n -> "，已保存 $n" } ?: "")
                } else {
                    "${result.mode.displayName} 接收中… ${result.decodedLines}/${result.mode.imageLines} 行"
                },
            )
        }
    }
}
