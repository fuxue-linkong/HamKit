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
 * 盯着空屏两分钟。这里的做法是：VIS 命中后持续累积音频，并**周期性对已累积的音频
 * 重新解码**（[SstvDecoder] 遇到音频末尾会自然停止，未收到的行保持黑色），因此图像
 * 会自上而下逐步显现。
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

            // 采集线程：把样本写入环形缓冲
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

    // ── 音频累积缓冲：从当前 VIS 起点开始保存整帧音频 ──
    private val bufferLock = Any()
    private var frameBuffer = FloatArray(INITIAL_BUFFER_SAMPLES)
    private var frameCount = 0

    /** 已识别到的 VIS 起点在 [frameBuffer] 中的偏移（VIS 之前的样本会被丢弃）。 */
    private var visOffset = -1

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

    /** 取出当前用于解码的音频（VIS 之后的全部样本）。 */
    private fun snapshot(): FloatArray? = synchronized(bufferLock) {
        if (visOffset < 0 || frameCount <= visOffset) return null
        frameBuffer.copyOfRange(visOffset, frameCount)
    }

    private suspend fun decodeLoop() {
        var lastDecodeAt = 0L
        var announcedMode = false
        // 手动锁定时无需等待 VIS：从第一个样本起就纳入累积缓冲
        if (forcedMode != null) {
            synchronized(bufferLock) { visOffset = 0 }
        }
        while (scope.isActive) {
            delay(POLL_INTERVAL_MS)
            if (visOffset < 0) {
                tryDetectVis()
                continue
            }
            if (!announcedMode) {
                announcedMode = true
                onStatus("正在接收图像…")
            }
            // 周期性重解：图像自上而下逐步显现
            val now = System.currentTimeMillis()
            if (now - lastDecodeAt < PARTIAL_DECODE_INTERVAL_MS) continue
            lastDecodeAt = now

            val audio = snapshot() ?: continue
            val rate = actualSampleRate
            val result = withContext(Dispatchers.Default) {
                when (val outcome = SstvDecoder(rate).decode(audio, forcedMode)) {
                    is SstvDecoder.Outcome.Success -> outcome.result
                    is SstvDecoder.Outcome.Failure -> null
                }
            }
            if (result != null) onProgress(result)
        }
    }

    /** 在最近一段音频上尝试识别 VIS；命中后把 [visOffset] 定位到 VIS 之后。 */
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
        }
        onVisDetected(detected)
    }

    companion object {
        /** 目标采样率：SSTV 像素时长的要求见 §4.2。 */
        const val TARGET_SAMPLE_RATE = 48_000

        /** 候选采样率（按优先级）。 */
        private val CANDIDATE_RATES = intArrayOf(TARGET_SAMPLE_RATE, 44_100)

        /** 未锁定 VIS 时保留的侦听窗口（约 3 秒）。 */
        private const val LISTEN_WINDOW_SAMPLES = TARGET_SAMPLE_RATE * 3

        private const val INITIAL_BUFFER_SAMPLES = TARGET_SAMPLE_RATE * 8

        /** 轮询间隔。 */
        private const val POLL_INTERVAL_MS = 500L

        /** 部分解码间隔：太短会浪费 CPU，太长则图像显现不连贯。 */
        private const val PARTIAL_DECODE_INTERVAL_MS = 4_000L
    }
}
