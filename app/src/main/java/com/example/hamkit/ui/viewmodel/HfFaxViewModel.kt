package com.example.hamkit.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.sstv.HfFaxDecoder
import com.example.hamkit.data.sstv.HfFaxImage
import com.example.hamkit.data.sstv.HfFaxReceiver
import com.example.hamkit.data.sstv.HfFaxSpec
import com.example.hamkit.data.sstv.SstvDemodulator
import com.example.hamkit.data.sstv.SstvImageStore
import com.example.hamkit.data.sstv.SstvMediaAudioReader
import com.example.hamkit.data.sstv.SstvPostProcess
import com.example.hamkit.data.sstv.SstvSettingsStore
import com.example.hamkit.data.sstv.SstvWavReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * HF Fax（WEFAX）界面的状态。
 *
 * 与 [SstvUiState] 相比刻意精简：WEFAX 没有模式、没有 VIS、没有行同步，因此
 * 「模式/失谐补偿/slant/同步脉冲数」这些字段在这里没有意义，全都不需要。
 * 保留的是「已收多少行」「有没有对齐」「启动音状态」「失谐估计」这四类真正可告知用户的信息。
 *
 * @param decodedLines 已解出的行数（图像自下而上增长）
 * @param imageHeight 当前位图的高度（可能略大于 [decodedLines] 的取整值）
 * @param shiftPixels 为把白边对齐到 x=0 而循环移位的列数
 * @param measuredOffsetHz 由 300 Hz 启动音实测得到的整体频偏
 */
data class HfFaxUiState(
    val isReceiving: Boolean = false,
    val status: String = "点击「开始接收」，把手机靠近电台扬声器",
    val decodedLines: Int = 0,
    val imageHeight: Int = 0,
    val maxLines: Int = HfFaxSpec.DEFAULT_MAX_LINES,
    val bitmap: Bitmap? = null,
    val aligned: Boolean = false,
    val shiftPixels: Int = 0,
    val waitingForStartTone: Boolean = true,
    val startToneDetected: Boolean = false,
    val measuredOffsetHz: Double? = null,
    val savedFileName: String? = null,
    val error: String? = null,
    /** 是否正在从录音文件解码。 */
    val importing: Boolean = false,
    val postProcess: SstvPostProcess.Options = SstvPostProcess.Options.NONE,
) {
    /** 是否已收到至少一行（决定图像区显示占位还是图像）。 */
    val hasImage: Boolean get() = decodedLines > 0
}

/**
 * HF Fax 的状态持有者。
 *
 * 采集与解码由 [HfFaxReceiver] 在后台完成；位图重建与落盘全部走协程 ——
 * 640×1200 的 ARGB 位图是 3 MB，放在主线程重建会明显掉帧。
 */
class HfFaxViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SstvSettingsStore(application)
    private val imageStore = SstvImageStore(application)
    private var receiver: HfFaxReceiver? = null

    /** 最近一次快照（未后处理），用于保存与停止后的兜底出图。 */
    @Volatile
    private var lastImage: HfFaxImage? = null

    /** 上一次重建位图时的行数，用于节流。 */
    private var lastBitmapLines = -1

    private val _uiState = MutableStateFlow(
        HfFaxUiState(postProcess = settingsStore.postProcess),
    )
    val uiState: StateFlow<HfFaxUiState> = _uiState.asStateFlow()

    fun startReceiving() {
        if (_uiState.value.isReceiving) return
        lastImage = null
        lastBitmapLines = -1
        _uiState.update {
            it.copy(
                isReceiving = true,
                status = "正在启动…",
                decodedLines = 0,
                imageHeight = 0,
                bitmap = null,
                aligned = false,
                shiftPixels = 0,
                waitingForStartTone = true,
                startToneDetected = false,
                measuredOffsetHz = null,
                savedFileName = null,
                error = null,
            )
        }

        receiver = HfFaxReceiver(
            context = getApplication(),
            onProgress = { progress -> onProgress(progress) },
            onStatus = { text -> _uiState.update { it.copy(status = text) } },
            onFinished = { onReceiverFinished() },
        ).also { it.start() }
    }

    fun stopReceiving() {
        receiver?.stop()
        receiver = null
        val snapshot = lastImage
        _uiState.update {
            it.copy(
                isReceiving = false,
                status = if (snapshot != null) "已停止：共收到 ${snapshot.height} 行" else "已停止",
            )
        }
        // 停止时强制出一次图：节流可能让最后几十行还没反映到位图上。
        // 重建必须放协程（本方法由 UI 事件调用，在主线程上）
        if (snapshot == null || snapshot.height <= 0) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { buildBitmap(snapshot, force = true) }
            _uiState.update { it.copy(bitmap = bitmap ?: it.bitmap, imageHeight = bitmap?.height ?: it.imageHeight) }
        }
    }

    /** 跳过启动音检测立即出图（素材从图像中间开始时用）。 */
    fun startImmediately() {
        receiver?.startImmediately()
        _uiState.update { it.copy(status = "已跳过启动音检测，开始按 120 行/分自由运行…") }
    }

    // ══════════════════════════════════════════════════════════════════
    // 离线解码（从录音文件）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 解码一段录音文件。
     *
     * **为什么离线路径对 WEFAX 特别重要**：气象传真台多在夜间/固定时刻播发，
     * 爱好者普遍的做法是「录下来再解」。而 WEFAX 解码不需要任何交互（没有模式要选、
     * 没有失谐要调），因此这里一次跑完、直接出图。
     *
     * 容器支持由 [SstvMediaAudioReader] 负责（WAV / FLAC / MP3 / AAC / OGG 等），
     * 全部重采样到 48 kHz 单声道；失败时回退到 [SstvWavReader]。
     */
    fun importAudio(uri: Uri) {
        if (_uiState.value.importing || _uiState.value.isReceiving) return
        _uiState.update { it.copy(importing = true, status = "正在读取音频…", error = null) }

        viewModelScope.launch {
            val context = getApplication<Application>()
            // 先落到缓存文件：读取器要 File，且这样只解一次码流
            val temp = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, IMPORT_TEMP_NAME)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                        target
                    }
                }.getOrNull()
            }
            if (temp == null) {
                _uiState.update { it.copy(importing = false, status = "无法读取所选文件") }
                return@launch
            }

            val outcome = withContext(Dispatchers.Default) {
                readAudioFile(temp).also { runCatching { temp.delete() } }
            }
            when (outcome) {
                is SstvWavReader.Outcome.Failure -> _uiState.update {
                    it.copy(importing = false, status = "解码失败", error = outcome.reason)
                }

                is SstvWavReader.Outcome.Success -> {
                    val describe = describeAudio(outcome)
                    _uiState.update { it.copy(status = "$describe，正在解码…") }
                    val result = withContext(Dispatchers.Default) { decodeOffline(outcome.samples) }
                    lastImage = result.image
                    val bitmap = withContext(Dispatchers.Default) { buildBitmap(result.image, force = true) }
                    _uiState.update {
                        it.copy(
                            importing = false,
                            bitmap = bitmap,
                            decodedLines = result.image.height,
                            imageHeight = bitmap?.height ?: 0,
                            aligned = result.image.aligned,
                            shiftPixels = result.image.shiftPixels,
                            waitingForStartTone = result.waitingForStartTone,
                            startToneDetected = result.startToneDetected,
                            measuredOffsetHz = result.measuredOffsetHz,
                            error = null,
                            status = if (result.image.height > 0) {
                                "$describe → 解出 ${result.image.height} 行" + result.note
                            } else {
                                "$describe → 未解出图像行（可能不是 WEFAX，或调谐误差超出 ±50 Hz）"
                            },
                        )
                    }
                }
            }
        }
    }

    private fun readAudioFile(file: File): SstvWavReader.Outcome =
        runCatching { SstvMediaAudioReader.read(file) }
            .getOrElse { SstvWavReader.read(file) }

    private fun describeAudio(audio: SstvWavReader.Outcome.Success): String = buildString {
        append("已读取 ")
        append("%.0f".format(audio.durationSeconds))
        append(" 秒")
        if (audio.resampled) append("（${audio.sourceSampleRate} Hz → ${SstvWavReader.TARGET_SAMPLE_RATE} Hz）")
        if (audio.sourceChannels > 1) append("，${audio.sourceChannels} 声道已下混")
        // 截断必须显式告知：WEFAX 单张图常超过 5 分钟，用户需要知道图是被截掉的
        if (audio.truncated) append("，已截断到 ${SstvWavReader.MAX_SECONDS.toInt()} 秒（单张长图可能不完整）")
    }

    /** 离线解码结果，连同「启动音/频偏」这些只有解码器才知道的状态。 */
    private data class OfflineResult(
        val image: HfFaxImage,
        val waitingForStartTone: Boolean,
        val startToneDetected: Boolean,
        val measuredOffsetHz: Double?,
        val note: String,
    )

    /**
     * 把整段音频解成图像。
     *
     * **按 30 秒分块解调**：一次 `process` 整段 300 秒音频会产生两个 57 MB 的 FloatArray
     * （输入 + 输出），在低端机上足以触发 OOM；分成 10 块后峰值降到约 12 MB，而解码器
     * 的启动音状态机与行时钟跨块连续，结果与一次跑完完全一致。
     *
     * 若整段都没有 300 Hz 启动音（录音从图像中间开始），自动回退为「立即开始」再解一遍 ——
     * 否则用户拿到的会是空白，而且无从知道为什么。
     */
    private fun decodeOffline(samples: FloatArray): OfflineResult {
        var note = ""
        var decoder = HfFaxDecoder(sampleRate = SstvWavReader.TARGET_SAMPLE_RATE)
        runChunkedDecode(decoder, samples)
        if (decoder.decodedLines == 0 && decoder.isWaitingForStartTone) {
            // 没有启动音：重新来一遍并强制开始。频偏只能用 0（无法从信号里标定），
            // 因此素材本身的调谐误差会直接反映为亮度偏移
            decoder = HfFaxDecoder(sampleRate = SstvWavReader.TARGET_SAMPLE_RATE)
            decoder.startImmediately()
            runChunkedDecode(decoder, samples)
            note = "（未检测到 300 Hz 启动音，已按「立即开始」解码；若整体偏亮/偏暗说明存在调谐误差）"
        }
        return OfflineResult(
            image = decoder.snapshot(),
            waitingForStartTone = decoder.isWaitingForStartTone,
            startToneDetected = decoder.isStartToneDetected,
            measuredOffsetHz = decoder.measuredOffsetHz,
            note = note,
        )
    }

    private fun runChunkedDecode(decoder: HfFaxDecoder, samples: FloatArray) {
        // 相位差分间隔必须与实时路径一致（300 Hz 启动音的无模糊范围约束，见 HfFaxReceiver）
        val demodulator = SstvDemodulator(
            sampleRate = SstvWavReader.TARGET_SAMPLE_RATE,
            phaseSpan = HfFaxReceiver.HF_FAX_PHASE_SPAN,
        )
        var offset = 0
        var first = true
        while (offset < samples.size) {
            val end = minOf(offset + OFFLINE_CHUNK_SAMPLES, samples.size)
            val freqs = demodulator.process(samples.copyOfRange(offset, end))
            // 只跳过第一块的解调瞬态；后续块的状态是连续的，跳过反而会丢音频
            val from = if (first) demodulator.transientSamples.coerceAtMost(freqs.size) else 0
            first = false
            decoder.feed(freqs, from)
            offset = end
        }
    }

    private fun onReceiverFinished() {
        if (!_uiState.value.isReceiving) return
        _uiState.update { it.copy(isReceiving = false) }
    }

    /** 保存当前图像到图库（复用 SSTV 的落盘链路，记录的模式名为 `HF Fax`）。 */
    fun saveCurrent() {
        val image = lastImage
        if (image == null) {
            _uiState.update { it.copy(error = "当前没有可保存的图像") }
            return
        }
        viewModelScope.launch {
            val options = settingsStore.postProcess
            val file = withContext(Dispatchers.Default) {
                val rgb = image.toRgbPixels()
                if (options.anyEnabled) {
                    imageStore.savePixels(
                        SstvPostProcess.process(rgb, image.width, image.height, options),
                        image.width,
                        image.height,
                        "HF Fax",
                        enhanced = true,
                    )
                } else {
                    imageStore.savePixels(rgb, image.width, image.height, "HF Fax")
                }
            }
            _uiState.update {
                it.copy(
                    savedFileName = file?.name,
                    error = if (file == null) "保存失败" else null,
                    status = if (file != null) "已保存到图库：${file.name}" else it.status,
                )
            }
        }
    }

    fun clearImage() {
        lastImage = null
        lastBitmapLines = -1
        _uiState.update {
            it.copy(
                decodedLines = 0,
                imageHeight = 0,
                bitmap = null,
                savedFileName = null,
                aligned = false,
                shiftPixels = 0,
                status = "已清空",
            )
        }
    }

    // ── 后处理（HF Fax 是灰度图，直方图拉伸与中值降噪都直接有效）──

    fun setHistogramStretch(enabled: Boolean) = updatePostProcess { it.copy(histogramStretch = enabled) }

    fun setMedianFilter(enabled: Boolean) = updatePostProcess { it.copy(medianFilter = enabled) }

    fun setRepairBadLines(enabled: Boolean) = updatePostProcess { it.copy(repairBadLines = enabled) }

    private fun updatePostProcess(transform: (SstvPostProcess.Options) -> SstvPostProcess.Options) {
        val next = transform(settingsStore.postProcess)
        settingsStore.setPostProcess(next)
        _uiState.update {
            it.copy(
                postProcess = next,
                status = if (next.anyEnabled) "后处理已开启" else "后处理已全部关闭",
            )
        }
        // 已有图时立即按新设置重建，让用户马上看到差别。
        // 必须放协程：640×1200 的后处理+位图重建在主线程会明显卡顿
        val snapshot = lastImage ?: return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { buildBitmap(snapshot, force = true) }
            _uiState.update { it.copy(bitmap = bitmap ?: it.bitmap) }
        }
    }

    override fun onCleared() {
        receiver?.stop()
        receiver = null
        super.onCleared()
    }

    // ══════════════════════════════════════════════════════════════════
    // 图像 → 位图（带节流）
    // ══════════════════════════════════════════════════════════════════

    private fun onProgress(progress: HfFaxReceiver.HfFaxProgress) {
        val image = progress.image
        lastImage = image
        val options = settingsStore.postProcess

        // 节流：位图重建是 640×H 的整幅拷贝（H=1200 时 3 MB），且开启后处理时还要
        // 额外跑一遍中值/拉伸。接收期间按里程碑刷新即可，停止时另有强制重建
        val stride = if (options.anyEnabled) POST_PROCESS_LINE_STRIDE else RAW_LINE_STRIDE
        val due = image.height > 0 && (lastBitmapLines < 0 || image.height - lastBitmapLines >= stride)
        val bitmap = if (due) buildBitmap(image) else _uiState.value.bitmap
        if (due) lastBitmapLines = image.height

        _uiState.update {
            it.copy(
                bitmap = bitmap,
                decodedLines = image.height,
                imageHeight = bitmap?.height ?: it.imageHeight,
                aligned = image.aligned,
                shiftPixels = image.shiftPixels,
                waitingForStartTone = progress.waitingForStartTone,
                startToneDetected = progress.startToneDetected,
                measuredOffsetHz = progress.measuredOffsetHz,
            )
        }
    }

    /**
     * 按当前后处理设置把快照转成位图。
     *
     * 位图**每次新建**而不是复用：内容变了但引用不变时 Compose 不会重绘
     * （一期 SSTV 预览已踩过这个坑）。本方法在采集线程或 [Dispatchers.Default] 上调用，
     * **不得**在主线程直接调用。
     */
    private fun buildBitmap(image: HfFaxImage, force: Boolean = false): Bitmap? {
        if (image.height <= 0) return null
        val options = settingsStore.postProcess
        val rgb = image.toRgbPixels()
        val pixels = if (options.anyEnabled) {
            SstvPostProcess.process(rgb, image.width, image.height, options)
        } else {
            rgb
        }
        if (force) lastBitmapLines = image.height
        return SstvImageStore.toBitmap(pixels, image.width, image.height)
    }

    private companion object {
        /** 未开启后处理时的位图重建间隔（行）。4 行 = 2 秒，肉眼刚好看到增长。 */
        const val RAW_LINE_STRIDE = 4

        /**
         * 开启后处理时的重建间隔（行）。
         *
         * 640×1200 的中值滤波要处理 76.8 万个像素（每像素 27 次比较），单次约 20–30 ms；
         * 若仍按 4 行（2 秒）重建，接收期间的 CPU 会被后处理吃掉一大块。16 行 = 8 秒，
         * 观感仍然连贯，代价降为原来的 1/4。
         */
        const val POST_PROCESS_LINE_STRIDE = 16

        /**
         * 离线解码的分块长度（样本）：30 秒 @48 kHz。
         *
         * 一帧 30 秒的解调输出是 1.44 M 个 float（5.8 MB），输入切片同量级 —— 峰值约 12 MB，
         * 与「一次解调整段 300 秒」的 114 MB 相比安全得多，而结果完全相同
         * （降频器与解码器都是有状态的，跨块连续）。
         */
        const val OFFLINE_CHUNK_SAMPLES = SstvWavReader.TARGET_SAMPLE_RATE * 30

        /** 导入时的缓存文件名（每次覆盖，避免缓存目录堆积）。 */
        const val IMPORT_TEMP_NAME = "hffax_import"
    }
}
