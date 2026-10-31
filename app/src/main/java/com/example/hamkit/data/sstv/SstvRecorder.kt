package com.example.hamkit.data.sstv

import android.content.Context
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SSTV 实时接收器：音源采集 → VIS 识别 → 边收边解码。
 *
 * **音源可插拔**：采集部分抽象成 [SstvAudioSource]，默认是麦克风，也可以是
 * 「播放捕获」（Android 10+，直接解码其它应用正在播放的音频）—— 解码链路对两者完全
 * 一致。该抽象参照 robot36（xdsopl/robot36，0BSD）的 `AudioSource` 家族。
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
 * **增量 VIS 侦听（二期的性能修复）**：早期实现每一轮都新建 [SstvVisDetector] 并对
 * 最近 3 秒音频**全量**重新检测（约 300 次 FFT / 500 ms），其中绝大多数 hop 被反复
 * 计算。现在只保留**一个**检测器实例并只喂新增音频（[visFedAbs]），每轮开销降到
 * 「新增样本数 / 10 ms」量级；未锁定 VIS 时也不再需要 3 秒的音频回看缓冲，
 * 只保留 [VIS_TAIL_SECONDS] 秒用于覆盖「检测触发时刻」与「图像数据起点」之间的错位。
 *
 * **自适应积分窗（二期）**：VIS 侦听期并行喂一个 `phaseSpan = 1` 的探针解调器，
 * 用其相位抖动估计等效信噪比（[SstvDemodulator.estimatedQualityDb]），再据
 * [SstvSpanController] 选定本帧的 [SstvDemodulator] 相位差分间隔 —— 弱信号多平均、
 * 强信号保分辨率。**一帧之内不再切换**，避免频率流噪声特性前后不一致。
 *
 * **连续接收（二期）**：ISS 的 ARISS 活动常连发多张。`continuous = true` 时收满一帧
 * 后保留模式与失谐量、清空频率流、就地接续下一帧（最多 [maxFrames] 帧），
 * 全程只保留当前帧的频率流，内存上限不变。
 *
 * **内存**：原始音频在解调后即被丢弃（[compactAudioBufferLocked]），保留的只有
 * 频率流（一帧 24 MB @ PD-120）与不足 1 秒的音频尾巴；并在超过
 * [MAX_FRAME_SAMPLES] 或收满帧后停止。
 *
 * 采样率固定 48 kHz：SSTV 像素时长最短 137.5 µs，低采样率无法在像素中点稳定取值
 * （见 docs/REQUIREMENT_SSTV.md §4.2）。设备不支持 48 kHz 时回退 44.1 kHz，并由
 * 解码器的 slant 拟合自动吸收比例差异。
 *
 * @param context 应用上下文（仅用于权限查询与默认音源构造）
 * @param audioSource 采集音源；默认麦克风。播放捕获（Android 10+）由调用方构造后传入，
 *   见 [SstvAudioSource] 与 [PlaybackCaptureAudioSource]
 * @param forcedMode 手动锁定的模式；为 null 时依赖 VIS 自动识别
 * @param continuous 收满一帧后是否自动接续下一帧
 * @param maxFrames 连续模式下的最大帧数（内存与时长保护）
 * @param adaptiveSpan 是否启用弱信号自适应积分窗。为 false 时完全退化为一期行为
 *   （按像素时长取固定窗），供用户偏好水平锐度时使用
 * @param recordingFile 非空时把**从打开音源起的全部音频**流式写入该 WAV 文件。
 *   刻意包含 VIS 头与前面的静音段：只有带上 VIS 头，录下来的文件才能被
 *   [SstvWavReader] + [SstvDecoder] 重新解码，「录下过境 → 事后重解」这条闭环才成立
 * @param waterfall 非空时把解调出的频率流喂给它做瀑布图显示
 * @param onVisDetected VIS 识别成功回调（模式 + 失谐量）；手动锁定时不会触发
 * @param onProgress 部分/整帧解码结果回调（图像会逐步补全）
 * @param onStatus 状态文案回调，用于 UI 展示
 * @param onQuality 信号质量与所选积分窗回调（VIS 锁定或手动管线建立时触发一次）
 * @param onFinished 采集循环结束回调（正常收完、连续模式跑满、或出错退出都会触发）。
 *   UI 依赖它把「接收中」状态收敛回空闲 —— 单帧收满后是采集器自己决定停止的，
 *   调用方无法从外部推断这一时刻。
 * @param onRecordingFinished 录音结束回调，参数为最终文件（可能已按模式改名）与样本数
 */
class SstvRecorder(
    private val context: Context,
    private val audioSource: SstvAudioSource = MicrophoneAudioSource(context),
    private val forcedMode: SstvMode? = null,
    private val continuous: Boolean = false,
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val adaptiveSpan: Boolean = true,
    private val pixelNyquistFilter: Boolean = true,
    private val recordingFile: File? = null,
    private val waterfall: SstvWaterfall? = null,
    private val onVisDetected: (SstvVisDetector.Result) -> Unit,
    private val onProgress: (SstvFrame) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onQuality: (SstvSignalQuality) -> Unit = {},
    private val onFinished: () -> Unit = {},
    private val onRecordingFinished: (File, Long) -> Unit = { _, _ -> },
) {

    /** 一次解码产出（含帧序号与信号质量，供连续模式显示「第 k 张」）。 */
    data class SstvFrame(
        /** 帧序号，从 0 开始。 */
        val index: Int,
        /** 解码结果（可能是部分解码）。 */
        val result: SstvDecoder.Result,
        /** 是否已收满整帧。 */
        val complete: Boolean,
        /** 当前实测频率噪声（Hz）；解调器未建立时为 0，样本不足（不可测）时为 `SstvDemodulator.NOISE_UNKNOWN_HZ` 占位值。 */
        val noiseHz: Double,
    )

    /** 信号质量快照（供 UI 显示与用户判断是否需要调整音量/位置）。 */
    data class SstvSignalQuality(
        /** 等效信噪比（dB），含义见 [SstvDemodulator.estimatedQualityDb]。 */
        val qualityDb: Double,
        /** 分档（0 最好，3 最差）。 */
        val tier: Int,
        /** 本次选定并冻结的相位差分间隔（样本）。 */
        val phaseSpan: Int,
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    /** 当前实际采样率（设备可能回退到 44.1 kHz）。 */
    @Volatile
    var actualSampleRate: Int = TARGET_SAMPLE_RATE
        private set

    val isRecording: Boolean
        get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        audioSource.close()
    }

    // ══════════════════════════════════════════════════════════════════
    // 录音：把原始音频流式落盘，供事后重解
    // ══════════════════════════════════════════════════════════════════

    private val recordingLock = Any()
    private var wavRecorder: SstvWavRecorder? = null

    /**
     * 待落盘的样本暂存区。
     *
     * 刻意不直接在读取线程里写盘：读取线程一旦被磁盘卡住就会丢音频，而录音本身就是为了
     * 「留下可重解的素材」。这里只做一次内存拷贝，真正的写盘交给 [flushRecording]（在
     * 解码循环里、约每 500 ms 一次）。500 ms 的音频是 48 KB，暂存代价可以忽略。
     */
    private var stageBuffer = ShortArray(0)
    private var stageCount = 0

    private fun openRecording(): String? {
        val target = recordingFile ?: return null
        val recorder = SstvWavRecorder(target, audioSource.sampleRate)
        val failure = recorder.open()
        if (failure != null) return failure
        synchronized(recordingLock) { wavRecorder = recorder }
        ensureStageCapacity(RECORD_STAGE_SAMPLES)
        return null
    }

    private fun ensureStageCapacity(required: Int) {
        if (stageBuffer.size >= required) return
        stageBuffer = ShortArray(required)
    }

    /** 在读取线程调用：只做内存拷贝。 */
    private fun stageForRecording(shorts: ShortArray, count: Int) {
        if (recordingFile == null) return
        synchronized(recordingLock) {
            if (wavRecorder?.isOpen != true) return
            if (stageCount + count > stageBuffer.size) {
                ensureStageCapacity((stageCount + count).coerceAtLeast(stageBuffer.size * 2))
            }
            System.arraycopy(shorts, 0, stageBuffer, stageCount, count)
            stageCount += count
        }
    }

    /** 在解码循环调用：把暂存区落盘。 */
    private fun flushRecording() {
        if (recordingFile == null) return
        val pending: ShortArray
        val pendingCount: Int
        synchronized(recordingLock) {
            val recorder = wavRecorder
            if (recorder?.isOpen != true || stageCount == 0) return
            pending = stageBuffer.copyOf(stageCount)
            pendingCount = stageCount
            stageCount = 0
        }
        // append(samples, offset, count)：pending 是 stageCount 的完整拷贝，
        // offset=0、count=pendingCount。曾误写成 append(pending, pendingCount)
        // 把长度传到 offset 位 → count=0 → 录音文件全程零写入（静默空文件）。
        wavRecorder?.append(pending, 0, pendingCount)
    }

    /** 收尾：落盘剩余暂存、回填 WAV 头、按模式改名，并回调结果。 */
    private fun closeRecording() {
        val target = recordingFile ?: return
        flushRecording()
        val recorder = synchronized(recordingLock) {
            val value = wavRecorder
            wavRecorder = null
            value
        } ?: return
        val samples = recorder.close()
        onRecordingFinished(target, samples)
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

    /**
     * 已经喂给 [visDetector] 的样本数（**相对检测器的绝对计数**）。
     *
     * 检测器内部自己维护待处理缓冲，因此这里必须用「绝对累计量」而不是 frameBuffer
     * 下标 —— frameBuffer 会随丢弃前移，两者不是同一坐标系。
     */
    private var visFedAbs = 0L

    /** frameBuffer 第 0 个样本对应的绝对样本号（用于检测器坐标 ↔ 缓冲下标换算）。 */
    private var bufferOriginAbs = 0L

    private fun appendSamples(shorts: ShortArray, count: Int) {
        stageForRecording(shorts, count)
        synchronized(bufferLock) {
            ensureCapacity(frameCount + count)
            for (i in 0 until count) {
                frameBuffer[frameCount + i] = shorts[i] / 32768.0f
            }
            frameCount += count
            // 兜底：万一消费端长时间不推进（例如解码卡住），也不能无限增长
            val limit = hardBufferLimitSamples
            if (frameCount > limit) {
                val drop = frameCount - limit
                frameBuffer.copyInto(frameBuffer, 0, drop, frameCount)
                frameCount -= drop
                bufferOriginAbs += drop
            }
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
        dropConsumedLocked(consumed)
    }

    /** 无条件丢弃前 [consumed] 个样本（连续模式换帧时用）。 */
    private fun compactAudioBufferForceLocked() {
        val consumed = (visOffset + audioConsumed).coerceAtLeast(0)
        dropConsumedLocked(consumed)
    }

    private fun dropConsumedLocked(consumed: Int) {
        val remaining = frameCount - consumed
        if (remaining > 0) {
            frameBuffer.copyInto(frameBuffer, 0, consumed, frameCount)
        }
        frameCount = remaining.coerceAtLeast(0)
        bufferOriginAbs += consumed
        visOffset = if (visOffset >= 0) 0 else -1
        audioConsumed = 0
    }

    /**
     * 未锁定 VIS 时保留的音频尾巴长度（样本）。
     *
     * 只需覆盖「检测器触发时刻」到「图像数据起点」的最大错位：检测器要等覆盖停止位的
     * 20 ms 窗做完才会触发，而图像起点又向前留了 10 ms 余量，再加上最长一个轮询间隔
     * （500 ms），1 秒足够且远小于原来的 3 秒。
     */
    private val visTailSamples: Int get() = actualSampleRate * VIS_TAIL_SECONDS

    /** 手动锁定模式下建管线前先攒的音频（样本）。 */
    private val manualPrepareSamples: Int get() = (actualSampleRate * MANUAL_PREPARE_SECONDS).toInt()

    /** 音频缓冲的硬上限（样本）：仅用于防止消费端卡死导致无限增长。 */
    private val hardBufferLimitSamples: Int get() = actualSampleRate * HARD_BUFFER_LIMIT_SECONDS

    /** 未进入管线时裁掉过老音频（VIS 侦听只需尾巴；手动锁定则等探针攒够）。 */
    private fun trimListeningBufferLocked() {
        val keep = if (forcedMode != null) manualPrepareSamples else visTailSamples
        if (frameCount <= keep) return
        dropConsumedLocked(frameCount - keep)
    }

    // ══════════════════════════════════════════════════════════════════
    // 频率流：解码的唯一输入，增量追加
    // ══════════════════════════════════════════════════════════════════

    private val freqLock = Any()
    private var freqBuffer = FloatArray(INITIAL_FREQ_SAMPLES)
    private var freqCount = 0

    /** 有状态的降频器；管线建立后创建，跨轮次复用。 */
    private var demodulator: SstvDemodulator? = null

    /** 当前接收使用的模式（手动锁定或 VIS 识别所得）。 */
    private var pipelineMode: SstvMode? = null

    /** VIS 测得的失谐量（同步脉冲足够多时解码器会用实测值取代）。 */
    private var visHedrShiftHz = 0.0

    /** 本帧选定的相位差分间隔（样本）。 */
    private var phaseSpan = SstvDemodulator.DEFAULT_PHASE_SPAN

    /** VIS 侦听期的质量探针（固定 span=1，与最终积分窗无关）。 */
    private var qualityProbe: SstvDemodulator? = null

    /**
     * 自适应积分窗控制器。
     *
     * **必须跨帧复用**：控制器内部的档位状态承载了 1 dB 迟滞。若每帧新建一个实例，
     * 决策就退化成「以正常档为起点的单次阈值判断」，迟滞完全失效 —— 连续接收时
     * 只要质量在边界附近抖动，积分窗就会逐帧来回切换，图像清晰度整体跳动。
     * 换模式（像素时长变了）时才重建。
     */
    private var spanController: SstvSpanController? = null
    private var spanControllerMode: SstvMode? = null

    /** 按累积的质量历史选择本帧的积分窗（保留迟滞状态）。 */
    private fun adaptiveSpanFor(mode: SstvMode, samplesPerPixel: Double, qualityDb: Double): Int {
        if (spanControllerMode != mode) {
            spanControllerMode = mode
            spanController = SstvSpanController(samplesPerPixel)
        }
        return spanController?.update(qualityDb)
            ?: SstvDemodulator.recommendPhaseSpan(samplesPerPixel)
    }

    /** 已完成（或已判定收满）的帧数，用于连续模式计数。 */
    private var frameIndex = 0

    /** 是否应结束主循环（收满且不再连续）。 */
    private var stopRequested = false

    /**
     * VIS 已识别出码值、但该模式未录入参数表。
     *
     * 必须单独标记：否则主循环会一遍遍重新侦听同一个 VIS 头，既停不下来也白耗 CPU。
     * （二期补齐 PD-50/90/160/290 与 Robot B/W 后，未支持码已很少见。）
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
        // 瀑布图吃的是同一条频率流：与解码共用数据源，因此用户看到的波形与实际
        // 用于出图的内容**必然一致**（不会出现「看着有信号却解不出」的错觉）
        waterfall?.feed(produced)
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
                val outcome = SstvDecoder(
                    sampleRate = rate,
                    pixelNyquistFilter = pixelNyquistFilter,
                ).decodeFromFrequencies(
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
            val openFailure = audioSource.open()
            if (openFailure != null) {
                onStatus(openFailure)
                return
            }
            actualSampleRate = audioSource.sampleRate

            // 录音从**打开音源的第一刻**开始：VIS 头必须录进去，否则录下来的文件
            // 无法被离线解码器识别（那正是「录下过境 → 事后重解」的前提）
            val recordFailure = openRecording()
            if (recordFailure != null) onStatus(recordFailure)

            // 质量探针只在侦听/准备阶段使用（固定 span=1，测量与最终窗无关）
            qualityProbe = SstvDemodulator(sampleRate = actualSampleRate, phaseSpan = 1)

            onStatus(
                when {
                    forcedMode != null -> "已锁定 ${forcedMode.displayName}，正在准备接收…"
                    recordFailure != null -> "正在侦听 VIS 头…（${actualSampleRate} Hz；录音不可用）"
                    else -> "正在侦听 VIS 头…（${actualSampleRate} Hz，${audioSource.displayName}）"
                },
            )

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

            decodeLoop()
            readThread.join(1000)
            audioSource.close()
        } catch (e: Exception) {
            onStatus("采集错误：${e.message}")
            audioSource.close()
        } finally {
            // 无论正常收完、连续模式跑满、被 stop() 取消还是异常退出，都要通知调用方 ——
            // 否则 UI 会永远停在「接收中」
            closeRecording()
            onFinished()
        }
    }

    /**
     * 建立解调管线。
     *
     * @param span 相位差分间隔；为 null 时按 [SstvSpanController] 依质量自适应选择
     *   （若 [adaptiveSpan] 为 false，则退回一期按像素时长取固定值的行为）
     */
    private fun startPipeline(mode: SstvMode, hedrShiftHz: Double, span: Int? = null) {
        pipelineMode = mode
        visHedrShiftHz = hedrShiftHz
        val samplesPerPixel = mode.pixelSeconds * actualSampleRate
        val qualityDb = qualityProbe?.estimatedQualityDb() ?: SstvDemodulator.QUALITY_MIN_DB
        val chosen = span ?: if (adaptiveSpan) {
            adaptiveSpanFor(mode, samplesPerPixel, qualityDb)
        } else {
            SstvDemodulator.recommendPhaseSpan(samplesPerPixel)
        }
        phaseSpan = chosen
        demodulator = SstvDemodulator(
            sampleRate = actualSampleRate,
            phaseSpan = chosen,
        )
        onQuality(
            SstvSignalQuality(
                qualityDb = qualityDb,
                tier = SstvDemodulator.qualityTier(qualityDb),
                phaseSpan = chosen,
            ),
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
            if (stopRequested) break

            if (pipelineMode == null) {
                val started = if (forcedMode != null) {
                    tryStartManualPipeline()
                } else {
                    tryDetectVis()
                }
                if (!started) continue
            }
            if (!announced) {
                announced = true
                onStatus("正在接收图像…")
            }

            // ① 只对新增音频做一次解调
            pumpFrequencies()

            // ② 把暂存的录音样本落盘（读线程只做内存拷贝，写盘放在这里）
            flushRecording()

            // ③ 周期性用频率流重新解码，让图像逐步显现
            val now = System.currentTimeMillis()
            if (now - lastDecodeAt >= PARTIAL_DECODE_INTERVAL_MS) {
                lastDecodeAt = now
                onStatus("解码中…（已采集 ${freqSamples() / actualSampleRate} 秒）")
                val result = decodePartial(actualSampleRate)
                if (result != null) {
                    emit(result)
                } else {
                    onStatus("正在接收图像…（等待足够的同步脉冲）")
                }
            }

            // ④ 兜底上限：避免异常信号让缓冲无限增长
            if (freqSamples() > MAX_FRAME_SAMPLES) {
                onStatus("已达最大单帧接收时长，自动停止")
                break
            }
        }
    }

    /** 回调一次解码结果，并在收满整帧时决定「停止」还是「接续下一帧」。 */
    private fun emit(result: SstvDecoder.Result) {
        val complete = result.decodedLines >= result.mode.imageLines
        onProgress(
            SstvFrame(
                index = frameIndex,
                result = result,
                complete = complete,
                noiseHz = demodulator?.estimatedFrequencyNoiseHz() ?: 0.0,
            ),
        )
        if (!complete) return

        frameIndex++
        if (!continuous || frameIndex >= maxFrames) {
            onStatus("${result.mode.displayName} 接收完成")
            stopRequested = true
            return
        }
        onStatus("已收到第 $frameIndex 张，继续等待下一帧…（最多 $maxFrames 张）")
        restartForNextFrame()
    }

    /**
     * 连续模式：清空频率流、保留模式与失谐量，就地接续下一帧。
     *
     * 刻意**不**清空音频缓冲：换帧时刻可能还有几十毫秒音频尚未解调，丢掉它会让
     * 下一帧的开头缺一小段（同步脉冲通常还在，但没必要冒险）。
     *
     * 也刻意**不**重新侦听 VIS：ARISS 连发时每张图用的是同一模式，保留模式可以立刻
     * 接续、零等待；而重新侦听 VIS 反而有把图像内容里的偶然音调串成假 VIS 的风险。
     * 若两帧之间确实换了模式，用户手动锁定或重新接收即可。
     */
    private fun restartForNextFrame() {
        synchronized(freqLock) { freqCount = 0 }
        synchronized(bufferLock) { compactAudioBufferForceLocked() }
        val mode = pipelineMode
        if (mode != null) {
            // 沿用本帧已定的积分窗：同一段通联里信噪比不会突变，重新探测只会引入抖动
            startPipeline(mode, visHedrShiftHz, phaseSpan)
        }
    }

    /** 手动锁定模式：等攒够一小段音频先测质量，再建管线。 */
    private fun tryStartManualPipeline(): Boolean {
        val mode = forcedMode ?: return false
        val ready = synchronized(bufferLock) {
            if (frameCount < manualPrepareSamples) {
                null
            } else {
                visOffset = 0
                audioConsumed = 0
                frameBuffer.copyOf(frameCount)
            }
        } ?: return false

        feedProbe(ready)
        startPipeline(mode, hedrShiftHz = 0.0)
        return true
    }

    /**
     * 在最近一段音频上尝试识别 VIS；命中后建立解调管线。
     *
     * **增量**：只把上次喂过之后的新音频交给同一个检测器实例，因此每个 10 ms hop
     * 只被计算一次（早期实现每轮都对最近 3 秒全量重算）。
     *
     * @return 是否已建立管线
     */
    private suspend fun tryDetectVis(): Boolean {
        val rate = actualSampleRate
        val detector = visDetector ?: SstvVisDetector(rate).also { visDetector = it }

        // ① 取出尚未喂给检测器的新音频（连同检测器坐标系下的起点）
        val pending = synchronized(bufferLock) {
            val from = (visFedAbs - bufferOriginAbs).toInt().coerceIn(0, frameCount)
            if (frameCount <= from) null else frameBuffer.copyOfRange(from, frameCount)
        }
        if (pending == null) {
            synchronized(bufferLock) { trimListeningBufferLocked() }
            return false
        }

        // ② 质量探针与检测器吃同一段音频（探针固定 span=1，与最终积分窗无关）
        feedProbe(pending)

        val fedStartAbs = visFedAbs
        val detected = withContext(Dispatchers.Default) { detector.process(pending) }
        visFedAbs = fedStartAbs + pending.size

        if (detected == null) {
            synchronized(bufferLock) { trimListeningBufferLocked() }
            return false
        }

        onVisDetected(detected)

        // ③ 把检测器的绝对样本坐标换算回当前 frameBuffer 下标
        val imageStartAbs = (detected.stopEndSample - rate / 100).coerceAtLeast(0L)
        val localFrom = (imageStartAbs - bufferOriginAbs).toInt().coerceIn(0, frameCount)
        synchronized(bufferLock) {
            if (localFrom > 0) {
                frameBuffer.copyInto(frameBuffer, 0, localFrom, frameCount)
                frameCount -= localFrom
                bufferOriginAbs += localFrom
            }
            visOffset = 0
            audioConsumed = 0
        }

        val mode = forcedMode ?: detected.mode
        if (mode == null) {
            // 码值已识别但未录入模式表：置位标记，由主循环提示并停止
            unsupportedVis = true
            return false
        }
        startPipeline(mode, detected.hedrShiftHz)
        return true
    }

    /** 把音频喂给质量探针（仅在管线建立前有意义）。 */
    private fun feedProbe(samples: FloatArray) {
        qualityProbe?.process(samples)
    }

    /** VIS 检测器实例（跨轮次复用，实现增量检测）。 */
    private var visDetector: SstvVisDetector? = null

    companion object {
        /** 目标采样率：SSTV 像素时长的要求见 §4.2。 */
        const val TARGET_SAMPLE_RATE = 48_000

        /** 连续模式的默认最大帧数。 */
        const val DEFAULT_MAX_FRAMES = 5

        /** 未锁定 VIS 时保留的音频尾巴（秒），见 [visTailSamples]。 */
        private const val VIS_TAIL_SECONDS = 1

        /** 手动锁定模式下，建管线前先攒的音频（秒）—— 用于质量探测。 */
        private const val MANUAL_PREPARE_SECONDS = 0.3

        private const val INITIAL_BUFFER_SAMPLES = TARGET_SAMPLE_RATE * 4
        private const val INITIAL_FREQ_SAMPLES = TARGET_SAMPLE_RATE * 16

        /** 录音暂存区初值（1 秒）：远大于一个轮询周期的音频量。 */
        private const val RECORD_STAGE_SAMPLES = TARGET_SAMPLE_RATE

        /** 音频缓冲的硬上限（秒）：仅用于防止消费端卡死导致无限增长。 */
        private const val HARD_BUFFER_LIMIT_SECONDS = 5

        /** 已解调音频累积到该量才做一次搬移，避免频繁拷贝。 */
        private const val COMPACT_THRESHOLD_SAMPLES = TARGET_SAMPLE_RATE * 2

        /** 单帧频率流上限：300 秒，覆盖最长的 PD-240/PD-290 并留余量。 */
        private const val MAX_FRAME_SAMPLES = TARGET_SAMPLE_RATE * 300

        /** 轮询间隔。 */
        private const val POLL_INTERVAL_MS = 500L

        /** 部分解码间隔：太短会浪费 CPU，太长则图像显现不连贯。 */
        private const val PARTIAL_DECODE_INTERVAL_MS = 4_000L
    }
}
