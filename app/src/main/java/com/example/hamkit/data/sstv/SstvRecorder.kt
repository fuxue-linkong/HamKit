package com.example.hamkit.data.sstv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SSTV 实时接收器：麦克风采集 → VIS 识别 → 边收边解码。
 *
 * **为什么要「边收边解码」**：PD-120 单帧长达 126 秒，若等整帧收完再解码，用户要
 * 盯着空屏两分钟。这里的做法是：VIS 命中后持续累积，并周期性对**已解调的频率流**
 * 重新解码（[SstvDecoder] 遇到数据末尾会自然停止，未收到的行保持黑色），因此图像
 * 会自上而下逐步显现。
 *
 * **增量解调（关键性能设计）**：降频器 [SstvDemodulator] 是**有状态**的，这里保持
 * 一个实例只对新增音频解调一次，把结果追加到频率流；每轮部分解码直接复用该频率流。
 * 早期实现每轮都从头解调整段音频，一帧内累计解调约 15 倍单帧样本量（O(n²)），
 * 后期 CPU 占用可达 25% 且不断制造数十 MB 的临时数组。
 *
 * **内存**：原始音频在解调后即被压缩丢弃（[compactAudioBufferLocked]），保留的只有
 * 频率流（一帧 24 MB @ PD-120）与不足 1 秒的音频尾巴；并在超过
 * [MAX_FRAME_SAMPLES] 或收满一帧时停止，避免无限增长。
 *
 * 采样率固定 48 kHz：SSTV 像素时长最短 137.5 µs，低采样率无法在像素中点稳定取值
 * （见 docs/REQUIREMENT_SSTV.md §4.2）。设备不支持 48 kHz 时回退 44.1 kHz，并由
 * 解码器的 slant 拟合自动吸收比例差异。
 *
 * @param context 应用上下文
 * @param forcedMode 手动锁定的模式；为 null 时依赖 VIS 自动识别
 * @param onVisDetected VIS 识别成功回调（模式 + 失谐量）；手动锁定时不会触发
 * @param onProgress 部分解码结果回调（图像会逐步补全）
 * @param onStatus 状态文案回调，用于 UI 展示
 */
class SstvRecorder(
    private val context: Context,
    private val forcedMode: SstvMode? = null,
    private val onVisDetected: (SstvVisDetector.Result) -> Unit,
    private val onProgress: (SstvDecoder.Result) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val recordLock = Any()
    private var audioRecord: AudioRecord? = null

    /** 当前实际采样率（设备可能回退到 44.1 kHz）。 */
    @Volatile
    var actualSampleRate: Int = TARGET_SAMPLE_RATE
        private set

    val isRecording: Boolean
        get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        if (!hasPermission()) {
            onStatus("缺少录音权限")
            return
        }
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseAudioRecord()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun releaseAudioRecord() {
        synchronized(recordLock) {
            audioRecord?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            audioRecord = null
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 音频缓冲：只保留「尚未解调」的尾巴
    // ══════════════════════════════════════════════════════════════════

    private val bufferLock = Any()
    private var frameBuffer = FloatArray(INITIAL_BUFFER_SAMPLES)
    private var frameCount = 0

    /** VIS 停止位之后的首个样本在 [frameBuffer] 中的下标；-1 表示尚未锁定。 */
    private var visOffset = -1

    /** `[visOffset, visOffset + audioConsumed)` 区间的样本已经解调过。 */
    private var audioConsumed = 0

    private fun appendSamples(shorts: ShortArray, count: Int) {
        synchronized(bufferLock) {
            // 未锁定 VIS 时只保留最近一小段，避免长时间侦听吃内存
            if (visOffset < 0 && frameCount > LISTEN_WINDOW_SAMPLES) {
                val drop = frameCount - LISTEN_WINDOW_SAMPLES
                frameBuffer.copyInto(frameBuffer, 0, drop, frameCount)
                frameCount -= drop
            }
            ensureCapacity(frameCount + count)
            for (i in 0 until count) {
                frameBuffer[frameCount + i] = shorts[i] / 32768.0f
            }
            frameCount += count
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= frameBuffer.size) return
        var newSize = frameBuffer.size
        while (newSize < required) newSize = newSize shl 1
        frameBuffer = frameBuffer.copyOf(newSize)
    }

    /** 丢掉已经解调的音频样本，让原始音频缓冲保持在小体积。 */
    private fun compactAudioBufferLocked() {
        val consumed = visOffset + audioConsumed
        if (consumed < COMPACT_THRESHOLD_SAMPLES) return
        val remaining = frameCount - consumed
        if (remaining > 0) {
            frameBuffer.copyInto(frameBuffer, 0, consumed, frameCount)
        }
        frameCount = remaining
        visOffset = 0
        audioConsumed = 0
    }

    // ══════════════════════════════════════════════════════════════════
    // 频率流：解码的唯一输入，增量追加
    // ══════════════════════════════════════════════════════════════════

    private val freqLock = Any()
    private var freqBuffer = FloatArray(INITIAL_FREQ_SAMPLES)
    private var freqCount = 0

    /** 有状态的降频器；VIS 锁定后创建，跨轮次复用。 */
    private var demodulator: SstvDemodulator? = null

    /** 当前接收使用的模式（手动锁定或 VIS 识别所得）。 */
    private var pipelineMode: SstvMode? = null

    /** VIS 测得的失谐量（同步脉冲足够多时解码器会用实测值取代）。 */
    private var visHedrShiftHz = 0.0

    /**
     * VIS 已识别出码值、但该模式未录入参数表（如 PD-50）。
     *
     * 必须单独标记：否则主循环会一遍遍重新侦听同一个 VIS 头，既停不下来也白耗 CPU。
     */
    @Volatile
    private var unsupportedVis = false

    /** 把未解调的音频解调并追加到频率流。 */
    private fun pumpFrequencies() {
        val demod = demodulator ?: return
        val chunk = synchronized(bufferLock) {
            val from = visOffset + audioConsumed
            if (visOffset < 0 || frameCount <= from) {
                null
            } else {
                frameBuffer.copyOfRange(from, frameCount)
            }
        } ?: return

        val produced = demod.process(chunk)

        synchronized(freqLock) {
            ensureFreqCapacity(freqCount + produced.size)
            System.arraycopy(produced, 0, freqBuffer, freqCount, produced.size)
            freqCount += produced.size
        }
        synchronized(bufferLock) {
            audioConsumed += chunk.size
            compactAudioBufferLocked()
        }
    }

    private fun ensureFreqCapacity(required: Int) {
        if (required <= freqBuffer.size) return
        var newSize = freqBuffer.size
        while (newSize < required) newSize = newSize shl 1
        freqBuffer = freqBuffer.copyOf(newSize)
    }

    private fun freqSamples(): Int = synchronized(freqLock) { freqCount }

    /** 用当前频率流做一次部分解码；数据不足或解码失败时返回 null。 */
    private suspend fun decodePartial(rate: Int): SstvDecoder.Result? {
        val mode = pipelineMode ?: return null
        val freqs = synchronized(freqLock) {
            if (freqCount < rate / 4) return null
            freqBuffer.copyOf(freqCount)
        }
        val transient = demodulator?.transientSamples ?: 0
        return withContext(Dispatchers.Default) {
            when (
                val outcome = SstvDecoder(rate).decodeFromFrequencies(
                    freqs = freqs,
                    mode = mode,
                    hedrShiftHz = visHedrShiftHz,
                    searchFromSample = 0,
                    validFromSample = transient,
                )
            ) {
                is SstvDecoder.Outcome.Success -> outcome.result
                is SstvDecoder.Outcome.Failure -> null
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 主循环
    // ══════════════════════════════════════════════════════════════════

    private suspend fun runLoop() {
        try {
            val opened = openAudioRecord() ?: run {
                onStatus("录音初始化失败（无可用采样率）")
                return
            }
            val record = opened.first
            actualSampleRate = opened.second
            audioRecord = record
            record.startRecording()
            onStatus(
                if (forcedMode != null) {
                    "已锁定 ${forcedMode.displayName}，正在接收图像…"
                } else {
                    "正在侦听 VIS 头…（${actualSampleRate} Hz）"
                },
            )

            // 手动锁定时无需等待 VIS：从第一个样本起就纳入缓冲
            if (forcedMode != null) {
                synchronized(bufferLock) { visOffset = 0 }
                startPipeline(forcedMode, hedrShiftHz = 0.0)
            }

            val readThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val readBuffer = ShortArray(actualSampleRate / 10)
                try {
                    while (true) {
                        val count = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            appendSamples(readBuffer, count)
                        } else if (count < 0) {
                            break
                        }
                    }
                } catch (_: Exception) {
                    // release 后 read() 可能抛异常，正常退出
                }
            }
            readThread.start()

            decodeLoop()
            readThread.join(1000)
            releaseAudioRecord()
        } catch (e: Exception) {
            onStatus("录音错误：${e.message}")
            releaseAudioRecord()
        }
    }

    /** 打开一个可用的 AudioRecord，优先 48 kHz，回退 44.1 kHz。 */
    private fun openAudioRecord(): Pair<AudioRecord, Int>? {
        for (rate in CANDIDATE_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, rate / 5) * 2)
                    .build()
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate to rate
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    /** 锁定模式并建立解调管线。 */
    private fun startPipeline(mode: SstvMode, hedrShiftHz: Double) {
        pipelineMode = mode
        visHedrShiftHz = hedrShiftHz
        // 相位差分间隔按像素时长自适应（不同族的像素时长相差数倍）
        val samplesPerPixel = mode.pixelSeconds * actualSampleRate
        demodulator = SstvDemodulator(
            sampleRate = actualSampleRate,
            phaseSpan = SstvDemodulator.recommendPhaseSpan(samplesPerPixel),
        )
    }

    private suspend fun decodeLoop() {
        var lastDecodeAt = 0L
        var announced = false
        while (scope.isActive) {
            delay(POLL_INTERVAL_MS)

            if (unsupportedVis) {
                onStatus("识别到未支持的模式，已停止接收")
                break
            }
            if (pipelineMode == null && forcedMode == null) {
                tryDetectVis()
                continue
            }
            if (!announced) {
                announced = true
                onStatus("正在接收图像…")
            }

            // ① 只对新增音频做一次解调
            pumpFrequencies()

            // ② 周期性用频率流重新解码，让图像逐步显现
            val now = System.currentTimeMillis()
            if (now - lastDecodeAt >= PARTIAL_DECODE_INTERVAL_MS) {
                lastDecodeAt = now
                val result = decodePartial(actualSampleRate)
                if (result != null) {
                    onProgress(result)
                    if (result.decodedLines >= result.mode.imageLines) {
                        // 收满一帧：主动停止，释放采集与缓冲
                        onStatus("${result.mode.displayName} 接收完成")
                        break
                    }
                }
            }

            // ③ 兜底上限：避免异常信号让缓冲无限增长
            if (freqSamples() > MAX_FRAME_SAMPLES) {
                onStatus("已达最大单帧接收时长，自动停止")
                break
            }
        }
    }

    /** 在最近一段音频上尝试识别 VIS；命中后建立解调管线。 */
    private suspend fun tryDetectVis() {
        val rate = actualSampleRate
        val chunk = synchronized(bufferLock) {
            if (frameCount < rate / 2) return
            frameBuffer.copyOfRange(0, frameCount)
        }
        val detected = withContext(Dispatchers.Default) {
            SstvVisDetector(rate).process(chunk)
        } ?: return

        // VIS 停止位之后即图像数据；保留少量余量以覆盖 hop 量化误差
        val imageStart = (detected.stopEndSample - rate / 100).toInt().coerceAtLeast(0)
        synchronized(bufferLock) {
            visOffset = imageStart.coerceAtMost(frameCount)
            audioConsumed = 0
        }
        onVisDetected(detected)

        val mode = forcedMode ?: detected.mode
        if (mode == null) {
            // 码值已识别但未录入模式表（如 PD-50）：置位标记，由主循环提示并停止
            unsupportedVis = true
            return
        }
        startPipeline(mode, detected.hedrShiftHz)
    }

    companion object {
        /** 目标采样率：SSTV 像素时长的要求见 §4.2。 */
        const val TARGET_SAMPLE_RATE = 48_000

        /** 候选采样率（按优先级）。 */
        private val CANDIDATE_RATES = intArrayOf(TARGET_SAMPLE_RATE, 44_100)

        /** 未锁定 VIS 时保留的侦听窗口（约 3 秒）。 */
        private const val LISTEN_WINDOW_SAMPLES = TARGET_SAMPLE_RATE * 3

        private const val INITIAL_BUFFER_SAMPLES = TARGET_SAMPLE_RATE * 8
        private const val INITIAL_FREQ_SAMPLES = TARGET_SAMPLE_RATE * 16

        /** 已解调音频累积到该量才做一次搬移，避免频繁拷贝。 */
        private const val COMPACT_THRESHOLD_SAMPLES = TARGET_SAMPLE_RATE * 2

        /** 单帧频率流上限：300 秒，覆盖最长的 PD-240（248 秒）并留余量。 */
        private const val MAX_FRAME_SAMPLES = TARGET_SAMPLE_RATE * 300

        /** 轮询间隔。 */
        private const val POLL_INTERVAL_MS = 500L

        /** 部分解码间隔：太短会浪费 CPU，太长则图像显现不连贯。 */
        private const val PARTIAL_DECODE_INTERVAL_MS = 4_000L
    }
}
