package com.example.hamkit.data.sstv

import android.content.Context
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * HF Fax（WEFAX）实时接收器：采集 → 解调 → [HfFaxDecoder] → 周期回调图像。
 *
 * **为什么比 [SstvRecorder] 简单得多**：WEFAX 没有 VIS 头、没有逐行同步脉冲，因此一期
 * 为 SSTV 写的那一整套机制（增量 VIS 侦听、行同步拟合与 slant 校正、部分解码、模式锁定、
 * 连续多帧、盲扫）在这里**全都用不上**。剩下的只是：
 * ```
 * 读音频 → 增量解调成逐样本频率流 → 喂给 HfFaxDecoder → 周期性把快照交给 UI
 * ```
 * 行节拍完全由解码器按「120 行/分钟」自由运行，接收端不做任何垂直校正。
 *
 * **⚠ 相位差分间隔必须显式设为 [HF_FAX_PHASE_SPAN] = 8**，不能用
 * `SstvDemodulator.recommendPhaseSpan(...)`：后者按「每像素样本数」推荐，WEFAX 的
 * 每像素 37.5 样本会算出 span = 16，而 300 Hz 启动音距解调中心 1900 Hz 有 1600 Hz，
 * 相位差分的无模糊范围只有 `±fs/(2·span)` = ±1500 Hz —— 启动音会被折叠成约 3300 Hz，
 * 于是**永远检测不到启动音、一行图都出不来**。span = 8 时无模糊范围 ±3000 Hz，
 * 留近两倍余量。详细实测数据见 [HfFaxDecoder] 的类 KDoc。
 *
 * @param context 应用上下文（仅用于默认音源）
 * @param audioSource 采集音源；默认麦克风。可换成 [PlaybackCaptureAudioSource] 以解码
 *   其它应用正在播放的音频
 * @param maxLines 行数上限，透传给 [HfFaxDecoder]（内存保护，见其 KDoc）
 * @param onProgress 图像快照回调（每 [REPORT_INTERVAL_MS] 或行数增长时触发一次）
 * @param onStatus 状态文案回调
 * @param onFinished 接收循环结束回调（正常停止、出错都触发）。UI 依赖它把「接收中」
 *   收敛回空闲 —— 与 [SstvRecorder.onFinished] 同理
 */
class HfFaxReceiver(
    private val context: Context,
    private val audioSource: SstvAudioSource = MicrophoneAudioSource(context),
    private val maxLines: Int = HfFaxSpec.DEFAULT_MAX_LINES,
    private val onProgress: (HfFaxProgress) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onFinished: () -> Unit = {},
) {

    /**
     * 一次进度上报。
     *
     * 刻意把「启动音状态」与「频偏估计」和图像一起传出去，而不是让 UI 反过来查询解码器：
     * 回调发生在采集线程上，查询式访问会引入跨线程可见性问题（而且这些值必须与图像
     * **来自同一次快照**才有意义，例如「已对齐 X 列」与「启动音已锁定」）。
     *
     * @param image 当前图像快照（可能高度为 0，表示尚未开始出图）
     * @param waitingForStartTone 是否仍在等 300 Hz 启动音
     * @param startToneDetected 是否已确认启动音（正在等它结束、即将出图）
     * @param measuredOffsetHz 由启动音实测的整体频偏（Hz）；未检测到时为 null
     */
    data class HfFaxProgress(
        val image: HfFaxImage,
        val waitingForStartTone: Boolean,
        val startToneDetected: Boolean,
        val measuredOffsetHz: Double?,
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    /** 实际采样率（打开音源后确定）。 */
    @Volatile
    var actualSampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE
        private set

    val isReceiving: Boolean
        get() = job?.isActive == true

    /** 解码器；打开音源后创建，供 `startImmediately` 与状态查询使用。 */
    @Volatile
    private var decoder: HfFaxDecoder? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        audioSource.close()
    }

    /**
     * 跳过启动音检测，立刻开始出图。
     *
     * 用于「录音从图像中间开始」的场景 —— 此时信号里没有 300 Hz 启动音，
     * 等下去永远是空屏。代价是：频偏必须由调用方另行保证（例如先用已知频率校准），
     * 否则 1500/2300 Hz 的黑白基准会整体偏移，图像表现为一片死白或死黑。
     */
    fun startImmediately() {
        decoder?.startImmediately()
    }

    /** 当前图像快照；尚未开始时为 null。 */
    fun snapshot(): HfFaxImage? = decoder?.snapshot()

    // ══════════════════════════════════════════════════════════════════
    // 音频缓冲：只保留「尚未解调」的尾巴
    // ══════════════════════════════════════════════════════════════════

    private val bufferLock = Any()
    private var audioBuffer = FloatArray(INITIAL_BUFFER_SAMPLES)
    private var audioCount = 0
    private var audioConsumed = 0

    private fun appendSamples(shorts: ShortArray, count: Int) {
        synchronized(bufferLock) {
            if (audioCount + count > audioBuffer.size) {
                var size = audioBuffer.size
                while (size < audioCount + count) size = size shl 1
                audioBuffer = audioBuffer.copyOf(size)
            }
            for (i in 0 until count) {
                audioBuffer[audioCount + i] = shorts[i] / 32768.0f
            }
            audioCount += count
        }
    }

    /** 丢弃已解调的音频，避免长时间接收时缓冲无限增长。 */
    private fun compactLocked() {
        if (audioConsumed < COMPACT_THRESHOLD_SAMPLES) return
        val remaining = audioCount - audioConsumed
        if (remaining > 0) audioBuffer.copyInto(audioBuffer, 0, audioConsumed, audioCount)
        audioCount = remaining
        audioConsumed = 0
    }

    /** 取出尚未解调的音频（不移动游标，由调用方在解调成功后推进）。 */
    private fun pendingAudio(): FloatArray? = synchronized(bufferLock) {
        if (audioCount <= audioConsumed) {
            compactLocked()
            null
        } else {
            audioBuffer.copyOfRange(audioConsumed, audioCount)
        }
    }

    private fun markConsumed(count: Int) {
        synchronized(bufferLock) {
            audioConsumed = (audioConsumed + count).coerceAtMost(audioCount)
            compactLocked()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 主循环
    // ══════════════════════════════════════════════════════════════════

    private suspend fun runLoop() {
        try {
            val openFailure = audioSource.open()
            if (openFailure != null) {
                onStatus(openFailure)
                return
            }
            actualSampleRate = audioSource.sampleRate
            val faxDecoder = HfFaxDecoder(sampleRate = actualSampleRate, maxLines = maxLines)
            decoder = faxDecoder

            // 相位差分间隔固定为 8：理由见类 KDoc（300 Hz 启动音的无模糊范围）
            val demodulator = SstvDemodulator(
                sampleRate = actualSampleRate,
                phaseSpan = HF_FAX_PHASE_SPAN,
            )

            onStatus("正在侦听 300 Hz 启动音…（${actualSampleRate} Hz，${audioSource.displayName}）")

            val readThread = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val readBuffer = ShortArray(actualSampleRate / 10)
                try {
                    while (true) {
                        val count = audioSource.read(readBuffer)
                        if (count > 0) {
                            appendSamples(readBuffer, count)
                        } else if (count < 0) {
                            break
                        }
                    }
                } catch (_: Exception) {
                    // close() 之后 read() 可能抛异常，正常退出
                }
            }
            readThread.start()

            var lastReportAt = 0L
            var lastReportedLines = -1
            while (scope.isActive && job?.isActive == true) {
                delay(POLL_INTERVAL_MS)

                // ① 只对新增音频解调一次（降频器有状态，跨轮次连续）
                val pending = pendingAudio()
                if (pending != null && pending.isNotEmpty()) {
                    val freqs = demodulator.process(pending)
                    markConsumed(pending.size)
                    // ② 喂给解码器；它内部按 0.5 s 自由运行的行时钟切行
                    faxDecoder.feed(freqs, demodulator.transientSamples.coerceAtMost(freqs.size))
                }

                // ③ 周期性（或行数增长时）把快照交给 UI
                val now = System.currentTimeMillis()
                val lines = faxDecoder.decodedLines
                if (now - lastReportAt >= REPORT_INTERVAL_MS || lines != lastReportedLines) {
                    lastReportAt = now
                    lastReportedLines = lines
                    onStatus(
                        when {
                            faxDecoder.isStartToneDetected -> "已检测到启动音，等待图像开始…"
                            faxDecoder.isWaitingForStartTone ->
                                "正在侦听 300 Hz 启动音…（若录音从中间开始，请点「立即开始」）"
                            else -> buildStatus(faxDecoder)
                        },
                    )
                    onProgress(
                        HfFaxProgress(
                            image = faxDecoder.snapshot(),
                            waitingForStartTone = faxDecoder.isWaitingForStartTone,
                            startToneDetected = faxDecoder.isStartToneDetected,
                            measuredOffsetHz = faxDecoder.measuredOffsetHz,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            onStatus("采集错误：${e.message ?: "未知错误"}")
        } finally {
            audioSource.close()
            onFinished()
        }
    }

    private fun buildStatus(decoder: HfFaxDecoder): String {
        val offset = decoder.measuredOffsetHz
        val offsetText = if (offset != null) "，失谐 ${"%.0f".format(offset)} Hz" else ""
        return "正在接收：第 ${decoder.decodedLines} 行" +
            "（上限 ${decoder.maxLines} 行，每行 0.5 秒）$offsetText"
    }

    companion object {
        /**
         * 解调器的相位差分间隔（样本）。
         *
         * **不要改大**：300 Hz 启动音需要 `span ≤ 14` 才不被相位解缠折叠，而边界附近必然
         * 出错，故取 8（无模糊范围 ±3000 Hz @48 kHz）。这一约束的实测数据见
         * [HfFaxDecoder] 的类 KDoc。
         */
        const val HF_FAX_PHASE_SPAN = 8

        private const val POLL_INTERVAL_MS = 250L

        /** 周期性上报间隔：太密会让位图重建吃掉 CPU，太疏则看不到「一行行长出来」。 */
        private const val REPORT_INTERVAL_MS = 1_500L

        private const val INITIAL_BUFFER_SAMPLES = SstvVisDetector.DEFAULT_SAMPLE_RATE * 4

        private const val COMPACT_THRESHOLD_SAMPLES = SstvVisDetector.DEFAULT_SAMPLE_RATE * 2
    }
}
