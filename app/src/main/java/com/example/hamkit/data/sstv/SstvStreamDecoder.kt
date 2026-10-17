package com.example.hamkit.data.sstv

import kotlin.math.abs
import kotlin.math.ceil

/**
 * 流式逐行 SSTV 解码器（SSTV 三期 Q0）。
 *
 * 上游设计：[docs/SSTV_STREAM_DECODER_DESIGN.md](../../../../../../../../../docs/SSTV_STREAM_DECODER_DESIGN.md)
 *
 * 与一次性解码 [SstvDecoder.decodeFromFrequencies] 的区别：
 *  - **有状态**：跨 [feed] 调用保持脉冲检测、回归与 Robot 色度行交替状态；
 *  - **任意起点**：不要求 VIS 头，模式由构造参数给定；音频从图像中间开始也能解；
 *  - **无限高度**：输出行数不设上限，由调用方通过 [consumedSamples] 驱动内存回收。
 *
 * ## 为什么「双输出」
 *
 * 离线解码用**全部**同步脉冲做一次最小二乘拟合再解每一行（非因果）；流式解码在处理
 * 第 k 行时只能用**前 k 个**脉冲（因果）。二者早期行必然不同，因此两种都提供：
 *
 * | 输出 | 触发 | 定位依据 | 用途 |
 * |---|---|---|---|
 * | 实时 | 每次 [feed] | 在线最小二乘（因果） | 边收边显示，延迟低 |
 * | 收尾 | [finalize] | 全局拟合 + 按帧重解 | 保存 / 跨帧融合；与一期**逐像素一致** |
 *
 * ## 行号语义（重要）
 *
 * 持续接收的音频没有「帧首」，因此行号**不是**模式内的行号，而是从「本解码器
 * 见到的第一条完整无线行」起从 0 单调递增的流水号 —— 不因一帧结束而重置，对应
 * UI 上「卷纸式」不断延长的画面。中途重新锁定后行号继续累加，不会回绕。
 *
 * 纯 Kotlin 实现，不依赖 Android API。
 *
 * @param sampleRate 采样率（Hz），必须与上游 [SstvDemodulator] 一致
 * @param mode 已知模式（持续接收时来自手动锁定 / 盲扫 / 中途捕到的 VIS）
 * @param initialHedrShiftHz 启动时的失谐量估计（Hz）；未捕获 VIS 时传 0，
 *   脉冲足够多后会被实测均值取代
 * @param onLine 每解码完**一个图像行**回调一次（PD 族一次无线行回调两次）
 */
class SstvStreamDecoder(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    private val mode: SstvMode,
    initialHedrShiftHz: Double = 0.0,
    private val onLine: (LineEvent) -> Unit = {},
) {

    /**
     * 一行解码结果。
     *
     * @param imageRow 图像行号（流水号，见类注释「行号语义」）
     * @param pixels 该行的打包 RGB（0xRRGGBB），长度 = [SstvMode.linePixels]
     * @param locked 该行是否由**实测同步脉冲**定位（false 表示用的是拟合外推）。
     *   跨帧融合需要这个信号来判断「这一行值不值得采信」
     */
    data class LineEvent(val imageRow: Int, val pixels: IntArray, val locked: Boolean) {
        // IntArray 的默认语义是引用比较，这里按内容比较，避免调用方踩坑
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LineEvent) return false
            return imageRow == other.imageRow &&
                locked == other.locked &&
                pixels.contentEquals(other.pixels)
        }

        override fun hashCode(): Int =
            (imageRow * 31 + pixels.contentHashCode()) * 31 + locked.hashCode()
    }

    /** 解码统计快照，供 UI 展示与跨帧融合的质量筛选。 */
    data class Stats(
        /** 已解出的图像行数 */
        val decodedLines: Int,
        /** 已接受（通过串链校验）的同步脉冲数 */
        val syncPulses: Int,
        /** 当前使用的失谐量（Hz） */
        val hedrShiftHz: Double,
        /** 拟合行周期 / 标称行周期（1.0 表示完全同步） */
        val slantRatio: Double,
        /** 最近 [SYNC_HIT_WINDOW] 行的同步命中率（0–1） */
        val syncHitRate: Float,
        /** 是否处于丢锁状态（连续多行未命中实测脉冲） */
        val lostSync: Boolean,
        /** 是否已锁定（脉冲足够且拟合合法） */
        val locked: Boolean,
    )

    // ══════════════════════════════════════════════════════════════════
    // 频率流缓冲
    // ══════════════════════════════════════════════════════════════════

    /**
     * 已喂入的全部频率样本。
     *
     * Q0 **不做回收**：设计文档 D3 把内存策略显式划给 Q1。[consumedSamples] 只报告
     * 「到哪里为止实时路径不会再读」，真正回收留在 Q1。
     */
    private var freqs = FloatArray(INITIAL_CAPACITY)
    private var freqSize = 0

    /** 累计喂入的样本总数（绝对坐标基准；Q0 中恒等于 [freqSize]）。 */
    private var baseSample = 0L

    /**
     * 与 [freqs] 同步喂入的原始音频（[feedAudio]），仅用于收尾时的 VIS 识别。
     *
     * 有 VIS 时 [finalize] 可以走与一期完全相同的路径；没有 VIS 时退回全局拟合。
     * 未调用 [feedAudio] 时保持为空，[finalize] 直接走全局拟合。
     */
    private var audio = FloatArray(0)
    private var audioSize = 0

    /** 增量 VIS 检测器（有状态），随原始音频一起喂入。 */
    private val visDetector = SstvVisDetector(sampleRate)

    /** 脉冲扫描游标（绝对样本位置），保证每个样本只扫一次。 */
    private var scanCursor = 0L

    /** 脉冲段状态机：当前是否位于某个 1200 Hz 连续段内。 */
    private var inPulse = false
    private var pulseStart = 0L
    private var pulseFreqSum = 0.0
    private var pulseFreqCount = 0

    // ══════════════════════════════════════════════════════════════════
    // 行跟踪状态
    // ══════════════════════════════════════════════════════════════════

    /** 启动时的失谐量：脉冲不足时退化为它。 */
    private var initialShiftHz = initialHedrShiftHz

    /** 最近若干脉冲的频率（环形缓冲），用于滚动失谐估计。 */
    private val shiftWindow = DoubleArray(SHIFT_WINDOW)
    private var shiftCount = 0
    private var shiftWriteIndex = 0

    /** 最近若干实测脉冲中心（环形缓冲），供逐行精调与碰撞判定。 */
    private val recentCenters = DoubleArray(PULSE_WINDOW)
    private var centerCount = 0
    private var centerWriteIndex = 0

    /** 最近若干行的命中标记（环形缓冲），用于 [Stats.syncHitRate]。 */
    private val hitWindow = BooleanArray(SYNC_HIT_WINDOW)
    private var hitCount = 0
    private var hitWriteIndex = 0
    private var totalHits = 0

    /** 在线最小二乘的累积量（**全局脉冲序号 k → 脉冲中心**，k = 0 是首个脉冲）。 */
    private var pulseK = 0L
    private var sumK = 0.0
    private var sumP = 0.0
    private var sumKK = 0.0
    private var sumKP = 0.0

    /** 当前拟合：`center(k) ≈ firstCenter + lineSamples · k`。未锁定时为 NaN。 */
    private var lineSamples = Double.NaN
    private var firstCenter = Double.NaN

    /** 已有足够脉冲且拟合合法。 */
    private var fitLocked = false

    /** 丢锁：连续 [LOST_SYNC_LINES] 行没有命中实测脉冲。 */
    private var lostSync = false

    /**
     * 首个被接受的脉冲对应的**局部序号**。
     *
     * 它未必是 0：音频可能从图像中途开始，此时首脉冲之前还缺若干行，序号从负数
     * 起算；等序号推进到 0 才对应「首脉冲所在的那一行」，也是全局流水号 0。
     */
    private var firstRadio = 0

    /** 下一条待解码无线行的**全局流水号**（0 起，单调递增）。 */
    private var nextRadio = 0

    /** 已解出的无线行数。 */
    private var decodedRadioLines = 0

    /** 连续未命中的行数。 */
    private var missStreak = 0

    // ══════════════════════════════════════════════════════════════════
    // 解码用的复用对象
    // ══════════════════════════════════════════════════════════════════

    /**
     * 行解码器。
     *
     * 内部持有 Robot 24/36 的**跨行色度平面**与行奇偶状态，因此必须全程复用同一个
     * 实例 —— 这正是流式解码必须「有状态」的原因之一。
     */
    private var lineDecoder = SstvDecoder(sampleRate)
    private val width = mode.linePixels

    /** 单条无线行解出来的图像行（PD 族 2 行，其余 1 行）。 */
    private val radioLinePixels = IntArray(width * mode.imageRowsPerRadioLine)

    /** 滚动失谐的初始值（未累积脉冲前），避免首块拿不到目标频率。 */
    private val nominalSyncHz = SstvMode.VIS_BREAK_HZ

    // ══════════════════════════════════════════════════════════════════
    // 对外接口
    // ══════════════════════════════════════════════════════════════════

    /**
     * 喂入一段**新的**频率流（必须是上次之后的新样本，长度任意、可为 0）。
     *
     * 内部会：检测同步脉冲 → 更新滚动回归 → 把能确定已完整到达的无线行解出来并回调。
     * 尚未锁定（脉冲不足 [MIN_PULSES_FOR_FIT] 个）时只做脉冲搜索。
     */
    fun feed(newFreqs: FloatArray) {
        if (newFreqs.isEmpty()) return
        append(newFreqs)
        scanPulses()
        drainRadioLines()
    }

    /**
     * 强制推出所有已到达的行（收尾用）。
     *
     * 与 [feed] 的区别：允许在**未锁定**时用标称行周期解码 —— 短音频（如只收到几行）
     * 攒不够 4 个脉冲，但位置仍可由已知模式的标称周期确定。正常流程不需要调用。
     *
     * @return 本次新解出的行数
     */
    fun flush(): Int {
        val before = decodedRadioLines
        drainRadioLines(force = true)
        return decodedRadioLines - before
    }

    /**
     * 喂入一段**原始音频**（可选）。
     *
     * 只用于收尾时的 VIS 识别：有 VIS 才能让 [finalize] 与一期走完全相同的路径。
     * 持续接收的常态（无 VIS）可以完全不调用它。
     *
     * 频次与 [feed] 一致即可；音频起点必须与频率流起点对齐。
     */
    fun feedAudio(pcm: FloatArray) {
        if (pcm.isEmpty()) return
        val required = audioSize + pcm.size
        if (required > audio.size) {
            var capacity = maxOf(audio.size, INITIAL_CAPACITY)
            while (capacity < required) capacity *= 2
            audio = audio.copyOf(capacity)
        }
        System.arraycopy(pcm, 0, audio, audioSize, pcm.size)
        audioSize = required
        // 增量喂给 VIS 检测器：它内部自己维护窗口与丢弃策略
        visDetector.process(pcm)
    }

    /** 当前统计快照。 */
    fun stats(): Stats {
        val slant = if (fitLocked && lineSamples.isFinite()) {
            lineSamples / nominalLineSamples
        } else {
            1.0
        }
        return Stats(
            decodedLines = decodedRadioLines * mode.imageRowsPerRadioLine,
            syncPulses = pulseK.toInt(),
            hedrShiftHz = currentShiftHz(),
            slantRatio = slant,
            syncHitRate = hitRate(),
            lostSync = lostSync,
            locked = fitLocked,
        )
    }

    /**
     * 已消费的样本数：调用方可安全丢弃 `feed` 累计前 [consumedSamples] 个样本。
     *
     * Q0 只提供这个数字，不实现回收（见设计文档 D3 / Q1）。语义是「当前解码进度
     * 之前、未来不可能再被实时路径读取的样本数」。
     */
    fun consumedSamples(): Long {
        if (nextRadio == 0) return 0L
        val samples = if (fitLocked) lineSamples else nominalLineSamples
        if (!samples.isFinite()) return 0L

        // 回收点 = 下一行行首 − 一个行周期的余量
        //
        // 为什么不是「已解完那一行的行尾」：
        //  - 下一行的**同步脉冲**必须先被检测到（它位于下一行行首附近），
        //    否则流式解码会永远等不到该行的行锚点；
        //  - 逐行精调还会回读该行同步脉冲的中心，正常情况下落在行首之后，
        //    但 Scottie 族的同步在行中、脉冲中心最晚可比行首晚近一个行周期；
        //  - 已经喂入的音频尾部也必须在窗内（VIS 检测与脉冲段状态机要跨块保持）。
        //
        // 保守地留整整一个行周期：丢样本的代价（整行错乱）远高于多留一段内存，
        // 而一个行周期相对整段缓冲是小量（Robot 36 约 0.15 s）。
        val nextStart = radioStart(firstRadio + nextRadio, samples)
        if (!nextStart.isFinite()) return 0L
        val cut = nextStart - samples
        return cut.toLong().coerceIn(0L, baseSample)
    }

    /**
     * 收尾：用累积的**全部**脉冲做一次全局拟合，重解所有行，得到与一期
     * [SstvDecoder.decodeFromFrequencies] 逐像素一致的最终图像。
     *
     * 与实时输出的分工见类注释；重解的原因是非因果拟合能修正早期行的定位偏差
     * （设计文档 D2）。
     *
     * 与一期的一致性策略：
     *  - 若频率流里**存在 VIS 头**（例如从帧首开始接收），走与一期完全相同的路径
     *    （沿用 VIS 给出的起点与失谐量）—— 此时结果**逐像素一致**；
     *  - 否则（持续接收的常态：音频从图像中间开始，或调用方已裁掉 VIS）用全局脉冲
     *    拟合，并保留首脉冲作为有效锚点，得到「一期在同样数据上会给的结果」。
     *
     * **要求调用方保留完整频率流**（Q0 期间不回收）。
     *
     * @return 最终图像；数据不足以定位时返回 null
     */
    fun finalize(): SstvDecoder.Result? {
        if (freqSize <= 0) return null
        val full = freqs.copyOf(freqSize)
        val decoder = SstvDecoder(sampleRate = sampleRate)

        // ① 若音频里确实有 VIS 头，走与一期完全相同的路径 —— 此时逐像素一致
        //
        //    VIS 检测必须在**原始音频**上做（模板匹配 1900/1200 Hz 串），不能在频率流上做。
        //    检测器是有状态的增量实现，[feedAudio] 已随手势喂入，这里直接取结果。
        val vis = visDetector.result
        if (vis != null && vis.mode == mode) {
            val viaVis = decoder.decodeFromFrequencies(
                freqs = full,
                mode = mode,
                hedrShiftHz = vis.hedrShiftHz,
                searchFromSample = vis.stopEndSample.toInt().coerceAtLeast(0),
                validFromSample = 0,
            )
            if (viaVis is SstvDecoder.Outcome.Success) return viaVis.result
        }

        // ② 回退：无 VIS 时的全局拟合（保留首脉冲，见 SstvSync.dropFirstPulse）
        val fit = SstvSync.findSyncPulses(
            freqs = full,
            mode = mode,
            sampleRate = sampleRate,
            hedrShiftHz = initialShiftHz,
            startIndex = 0,
            dropFirstPulse = false,
        ) ?: return null

        val shift = if (fit.pulseCount >= MIN_PULSES_FOR_SHIFT_REFINE) {
            fit.measuredShiftHz
        } else {
            initialShiftHz
        }
        val effectiveRate = fit.effectiveRate(sampleRate)
        val pixels = IntArray(width * mode.imageLines)
        // 与一期一致：每次独立解码都要重置行奇偶状态
        decoder.resetFrameState()
        var decoded = 0
        for (radio in 0 until mode.radioLines) {
            if (!decoder.decodeRadioLine(
                    freqs = full,
                    fit = fit,
                    mode = mode,
                    line = radio,
                    hedrShiftHz = shift,
                    pixels = pixels,
                    validFrom = 0,
                    effectiveRate = effectiveRate,
                )
            ) {
                break
            }
            decoded += mode.imageRowsPerRadioLine
        }
        if (decoded == 0) return null
        return SstvDecoder.Result(
            mode = mode,
            width = width,
            height = mode.imageLines,
            pixels = pixels,
            hedrShiftHz = shift,
            slantRatio = fit.slantRatio,
            syncPulseCount = fit.pulseCount,
            decodedLines = decoded.coerceAtMost(mode.imageLines),
        )
    }

    /** 清空全部状态（重新开始一次持续接收）。幂等。 */
    fun reset() {
        freqSize = 0
        baseSample = 0L
        scanCursor = 0L
        audioSize = 0
        audio = FloatArray(0)
        // 频率缓冲保留已分配容量即可；内容不再有意义
        inPulse = false
        pulseStart = 0L
        pulseFreqSum = 0.0
        pulseFreqCount = 0
        shiftCount = 0
        shiftWriteIndex = 0
        centerCount = 0
        centerWriteIndex = 0
        candidateCount = 0
        recentCenters.fill(Double.NaN)
        hitWindow.fill(false)
        hitCount = 0
        hitWriteIndex = 0
        totalHits = 0
        pulseK = 0
        sumK = 0.0
        sumP = 0.0
        sumKK = 0.0
        sumKP = 0.0
        lineSamples = Double.NaN
        firstCenter = Double.NaN
        fitLocked = false
        lostSync = false
        nextRadio = 0
        firstRadio = 0
        decodedRadioLines = 0
        missStreak = 0
        // 行解码器带着 Robot 色度平面与奇偶状态，一并换新
        resetLineDecoder()
    }

    /**
     * 运行期更新失谐量估计（例如中途捕到 VIS 头后调用）。
     *
     * 只影响「脉冲不足时」的退化值；脉冲足够多后仍以滚动实测均值为准。
     */
    fun updateHedrShift(hedrShiftHz: Double) {
        initialShiftHz = hedrShiftHz
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 缓冲
    // ══════════════════════════════════════════════════════════════════

    private fun append(newFreqs: FloatArray) {
        val required = freqSize + newFreqs.size
        if (required > freqs.size) {
            // 翻倍扩容，摊还 O(1)
            var capacity = maxOf(freqs.size, INITIAL_CAPACITY)
            while (capacity < required) capacity *= 2
            freqs = freqs.copyOf(capacity)
        }
        System.arraycopy(newFreqs, 0, freqs, freqSize, newFreqs.size)
        freqSize = required
        baseSample = freqSize.toLong()
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 流式脉冲检测（跨块延续的段状态机）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 逐样本扫描 1200 Hz 连续段。
     *
     * 与 [SstvSync.findSyncPulses] 的「一次扫出全部脉冲」不同，这里是**跨块延续**的
     * 段状态机：输入块可能在脉冲中间断开，`inPulse` / `pulseStart` 等字段必须跨调用
     * 保持，否则会把一个脉冲切成两个、或产生半个脉冲的误判。
     *
     * 1200 Hz 位于亮度映射区间（1500–2300 Hz）之外，因此图像内容不会误触发；
     * 再加长度门限 [minPulseSamples] 挡住过渡带毛刺。
     */
    private fun scanPulses() {
        val targetHz = nominalSyncHz + currentShiftHz()
        // 游标是绝对位置；Q0 中绝对位置 == 缓冲下标
        val start = scanCursor.toInt().coerceIn(0, freqSize)
        var i = start
        while (i < freqSize) {
            val value = freqs[i]
            if (abs(value - targetHz) <= SYNC_TOLERANCE_HZ) {
                if (!inPulse) {
                    inPulse = true
                    pulseStart = i.toLong()
                    pulseFreqSum = 0.0
                    pulseFreqCount = 0
                }
                pulseFreqSum += value
                pulseFreqCount++
            } else if (inPulse) {
                inPulse = false
                val end = i.toLong()
                val length = end - pulseStart
                // 上限门限：过长的段是「合并段」，它的中心不能当行锚点（详见 onPulse）
                if (length >= minPulseSamples && length <= maxPulseSamples && pulseFreqCount > 0) {
                    onPulse(pulseStart.toDouble(), end.toDouble(), pulseFreqSum / pulseFreqCount)
                }
            }
            i++
        }
        scanCursor = freqSize.toLong()
    }

    /**
     * 接受一个同步脉冲。
     *
     * 防线（设计文档 D4「最小二乘对离群脉冲敏感」的缓解）：
     *  1. 长度门限 —— 已在 [scanPulses] 上游过滤；
     *  2. 节拍门限 —— 真实同步脉冲的间距恒为 1 个行周期；未锁定时按「连续差一个
     *     行周期」攒候选链，攒够 4 个才锁定（见 [attachCandidate]）；已锁定时用拟合
     *     直线外推校验，偏差超过半行周期即丢弃。这一条同时挡住图像内容里偶然的
     *     1200 Hz 波动，以及一个同步脉冲被噪声切成两段产生的碎片。
     */
    private fun onPulse(start: Double, end: Double, meanHz: Double) {
        val center = (start + end) / 2.0
        if (!center.isFinite()) return

        // 串联校验（设计文档 D4 的两道防线之二）
        //
        // 真实同步脉冲的间距**恒为 1 个行周期**，而误检的间隔是随机的
        // （实测有 78、240、480、1675 样本等多种）。
        //
        // 锁定前后策略不同，因为二者掌握的信息不同：
        //  - **未锁定**：还不知道谁的节拍是真的，把候选按「连续差一个行周期」攒成链
        //    （见 [attachCandidate]），攒够 [MIN_PULSES_FOR_FIT] 个才认定这一链就是
        //    真实行同步。这样才能跨过 VIS 停止位、图像内容误检等干扰。
        //  - **已锁定**：用拟合直线外推该脉冲的位置，偏差超过半行周期即丢弃。
        if (fitLocked && lineSamples.isFinite()) {
            val predicted = firstCenter + lineSamples * pulseK.toDouble()
            if (abs(center - predicted) > lineSamples * CHAIN_TOLERANCE) return
        }
        attachCandidate(center, meanHz)
    }

    /**
     * 锁定前的候选链：连续差一个行周期的脉冲中心。
     *
     * 只用最近 [MIN_PULSES_FOR_FIT] 个（够了就能直接拟合），因此容量固定，O(1) 追加。
     */
    private val candidateChain = DoubleArray(MIN_PULSES_FOR_FIT)
    private var candidateCount = 0

    /**
     * 把候选脉冲并入链，必要时锁定。
     *
     * 链的维护规则：
     *  - 链为空 → 直接收；
     *  - 与链尾相差约一个行周期 → 追加；
     *  - 否则 → 链重置为只含本候选（说明前面那串不是真实行同步）。
     *
     * 一旦链满 [MIN_PULSES_FOR_FIT] 个，就以它们为起点建立拟合（k 由此起算），
     * 之后的脉冲全部按「已锁定」的规则校验。
     */
    private fun attachCandidate(center: Double, meanHz: Double) {
        if (fitLocked) {
            appendPulse(center, meanHz)
            if (pulseK >= MIN_PULSES_FOR_FIT) refit()
            return
        }

        val toleranceMin = nominalLineSamples * CHAIN_MIN_TOLERANCE
        val toleranceMax = nominalLineSamples * CHAIN_MAX_TOLERANCE
        val last = if (candidateCount > 0) candidateChain[candidateCount - 1] else Double.NaN
        if (last.isFinite()) {
            val distance = abs(center - last)
            if (distance < toleranceMin || distance > toleranceMax) {
                // 节拍断了：以本候选开启新链
                candidateCount = 0
            }
        }
        candidateChain[candidateCount++] = center
        if (candidateCount < MIN_PULSES_FOR_FIT) return

        // 链满：这就是真实行同步，一次性入账并拟合
        candidateCount = 0
        for (index in 0 until MIN_PULSES_FOR_FIT) {
            appendPulse(candidateChain[index], meanHz)
        }
        refit()
    }

    /** 入账一个脉冲：滚动失谐、在线最小二乘累积量、环形缓冲。 */
    private fun appendPulse(center: Double, meanHz: Double) {
        shiftWindow[shiftWriteIndex] = meanHz
        shiftWriteIndex = (shiftWriteIndex + 1) % SHIFT_WINDOW
        if (shiftCount < SHIFT_WINDOW) shiftCount++

        val k = pulseK.toDouble()
        sumK += k
        sumP += center
        sumKK += k * k
        sumKP += k * center
        pulseK++

        recentCenters[centerWriteIndex % PULSE_WINDOW] = center
        centerWriteIndex++
        if (centerCount < PULSE_WINDOW) centerCount++
    }

    /** 最近一个被接受的脉冲中心。 */
    private fun lastAcceptedCenter(): Double {
        if (centerCount == 0) return Double.NaN
        val index = (centerWriteIndex - 1 + PULSE_WINDOW) % PULSE_WINDOW
        return recentCenters[index]
    }

    /** 用累积量重算直线 `center(k) ≈ firstCenter + lineSamples · k`。 */
    private fun refit() {
        val n = pulseK.toDouble()
        val denominator = n * sumKK - sumK * sumK
        if (denominator <= 0.0) return
        val slope = (n * sumKP - sumK * sumP) / denominator
        val intercept = (sumP - slope * sumK) / n

        // 行周期必须接近标称值，否则说明串链错误（与一期同样的守卫）
        if (slope < nominalLineSamples * MIN_SLANT_RATIO ||
            slope > nominalLineSamples * MAX_SLANT_RATIO
        ) {
            resetRegression()
            return
        }

        lineSamples = slope
        firstCenter = intercept
        if (!fitLocked) {
            fitLocked = true
            lostSync = false
            // 首个接受脉冲之前还有多少行？
            //
            // 音频可能从图像中途开始，此时首脉冲所在行的行首为负（行已残缺），
            // 该行及其之前的所有行都不能解，因此 firstRadio 取负把它们跳过；
            // 与 radioStart 的换算是互逆关系。
            if (slope > 0.0) {
                val syncSamples = mode.syncSeconds * sampleRate
                val anchorStart = intercept - syncSamples / 2.0 -
                    mode.syncOffsetSeconds * sampleRate
                // 行首为负时缺 ceil(-行首 / 行周期) 行；行首为正则为 0（不能是正数，
                // 那会让流水号从一个不存在的行开始）
                val missing = ceil(-anchorStart / slope).toInt()
                firstRadio = -missing.coerceAtLeast(0)
            }
        }
    }

    /** 清空回归累积量并回到搜索态（脉冲序号保留，行号语义因此保持连续）。 */
    private fun resetRegression() {
        sumK = 0.0
        sumP = 0.0
        sumKK = 0.0
        sumKP = 0.0
        lineSamples = Double.NaN
        firstCenter = Double.NaN
        fitLocked = false
        lostSync = false
        missStreak = 0
    }

    /** 重建行解码器（清掉 Robot 跨行色度与奇偶状态）。 */
    private fun resetLineDecoder() {
        lineDecoder = SstvDecoder(sampleRate)
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 逐行解码
    // ══════════════════════════════════════════════════════════════════

    /**
     * 推出所有「已完整到达」的无线行。
     *
     * 行边界的判定与一期**逐条一致**，这是 `finalize` 能逐像素对齐的前提：
     * 一期对每个通道检查
     *   ① `channelStart < freqs.size - 1`（起点未越界）
     *   ② `channelStart + pixels·pixelSamples >= validFrom`（未全落在瞬态区）
     * 在 `validFrom = 0` 时等价于「该通道终点已到达」；再加上
     *   ③ `lineStartRefined` 命中的脉冲中心 `< freqs.size - 1`（精调读数不越界）
     * 三条都满足才解，否则 `decodeRadioLine` 会解到不完整的数据。
     *
     * @param force 未锁定时也用标称行周期解码（[flush] 用）
     */
    private fun drainRadioLines(force: Boolean = false) {
        if (!fitLocked && !force) return
        if (!fitLocked && pulseK == 0L) return

        val samples = if (fitLocked) lineSamples else nominalLineSamples
        if (!samples.isFinite()) return
        val effectiveRate = sampleRate * (samples / nominalLineSamples)

        // 一行内最晚结束的通道终点（相对行首，秒）→ 该行被完整解出所需的最小样本数
        val tasks = mode.channelTasks()
        val lastChannelEnd = tasks.maxOf { it.startSeconds + it.pixels * it.pixelSeconds }

        while (true) {
            val start = radioStart(firstRadio + nextRadio, samples)
            if (!start.isFinite()) return

            // 起点在音频之前（音频从行中间开始）→ 这一行不完整，跳过
            if (start < 0.0) {
                if (start + samples >= 0.0) {
                    // 部分落在音频之前：该行不完整，跳过（冷启动常态）
                    nextRadio++
                    continue
                }
                // 整行都在起点之前只可能是坐标异常；停止以免死循环
                break
            }

            // 条件②：最晚通道的终点必须已到达
            val lastChannelEndSample = start + lastChannelEnd * effectiveRate
            if (lastChannelEndSample > freqSize - 1) break

            // 条件③：**精调实际会命中的那个脉冲中心**必须已到达（否则读数越界）
            //
            // 注意不能用「由 refinedStart 反推的中心」来判 —— 未命中时 refinedStart
            // 退回到拟合预测值，反推出来的永远是预测中心，条件永远成立；
            // 而真正被读取的是 pulseCenters 里的实测值。
            val fit = fitForRadioLine(start, samples, effectiveRate)
            val syncSamples = mode.syncSeconds * effectiveRate
            val predictedCenter = start + mode.syncOffsetSeconds * effectiveRate + syncSamples / 2.0
            val matched = nearestPulse(predictedCenter, samples * SstvSync.LINE_REFINE_TOLERANCE)
            if (matched > freqSize - 1) break

            val locked = fit.pulseCenters.isNotEmpty()
            radioLinePixels.fill(0)
            // line = 0：时序在行内是相对的，绝对位置由 fit.firstLineStart 承载
            val ok = lineDecoder.decodeRadioLine(
                freqs = freqs,
                fit = fit,
                mode = mode,
                line = 0,
                hedrShiftHz = currentShiftHz(),
                pixels = radioLinePixels,
                validFrom = 0,
                effectiveRate = effectiveRate,
            )
            if (ok) emitRadioLine(locked)
            // 解码失败也要前进，否则会卡在同一行
            nextRadio++
        }
    }

    /**
     * 全局流水号为 [radio] 的无线行的**行首**样本位置。
     *
     * 几何推导（与 [SstvSync.SyncFit.lineStartRefined] 严格互逆）：
     * 某行的同步脉冲中心
     * ```
     * center(k) = lineStart(k) + syncOffset + syncSamples/2
     * ```
     * 由拟合直线 `center(k) = firstCenter + k·L` 反解
     * ```
     * lineStart(k) = firstCenter + k·L − syncSamples/2 − syncOffset
     * ```
     * 流水号 0 就是首个脉冲所在的那一行（k = 0）。早期版本的音频（例如带 VIS 头时）
     * 被合并段挡住之后，首个脉冲恰好就是第 0 行同步，因此从 k=0 起算才不会整幅错一行。
     *
     * 未锁定时退回标称周期与最早那个已接受脉冲的中心。
     */
    private fun radioStart(radio: Int, samples: Double): Double {
        if (!samples.isFinite() || samples <= 0.0) return Double.NaN
        val syncSamples = mode.syncSeconds * sampleRate
        val syncOffset = mode.syncOffsetSeconds * sampleRate

        // 永用「最早的那个**实际接受的**脉冲」，不要用拟合截距 firstCenter：
        // 截距是直线外推到 k=0 的结果，而 k=0 未必是首个接受的脉冲（图像内容误检、
        // 或音频起点落在图像中途时都不同）。二者混用会整体差一个行周期。
        val anchorCenter = firstAcceptedCenter()
        if (!anchorCenter.isFinite()) return Double.NaN

        // 行首 = 脉冲中心 − 半个同步脉冲 − Scottie 偏移
        //
        // 二者都要扣，且各扣一次：
        //  - 半个同步脉冲：锚点是脉冲**中心**，行首量的是脉冲**起点**；
        //  - Scottie 偏移：该族同步在行中，行首在同步之前 2·sep + 2·chanLen。
        // 用固定关系 `center(k) = lineStart(k) + syncOffset + sync/2` 反解即可，
        // 这里不需要再考虑拟合截距（那是另一套坐标）。
        //
        // [radio] 是**相对首个接受脉冲**的局部序号；全局流水号与它的换算在
        // [drainRadioLines] 里通过 [firstRadio] 完成。
        return anchorCenter + radio.toDouble() * samples - syncSamples / 2.0 - syncOffset
    }

    /**
     * **行几何的锚点**：首个被接受的脉冲中心。
     *
     * ⚠ 不能从 [recentCenters] 环形缓冲里取：它只保留最近 [PULSE_WINDOW] 个脉冲，
     * 接收一会儿就会被覆盖（早期实现正是踩了这个坑 —— 长音频下锚点漂到 64 个脉冲
     * 之后，整幅图错位）。改用「拟合直线在全局脉冲序号 0 处的取值」：直线是全体
     * 脉冲的最小二乘结果，序号 0 恒指首脉冲，且对首脉冲自身的量测误差还有平滑作用。
     *
     * 拟合尚未建立时（脉冲不足 4 个）退回最早入账的那个脉冲。
     */
    private fun firstAcceptedCenter(): Double {
        if (fitLocked && firstCenter.isFinite()) return firstCenter
        if (centerCount == 0) return Double.NaN
        val firstIndex = (centerWriteIndex - centerCount + PULSE_WINDOW) % PULSE_WINDOW
        return recentCenters[firstIndex]
    }

    /**
     * 为一行的**局部**索引构造 [SstvSync.SyncFit]。
     *
     * 让 `firstLineStart` 正好落在该行行首、`lineSamples` 取拟合周期，则
     * `lineStart(0)` 就是这一行 —— 于是可以直接复用一期的 [SstvDecoder.decodeRadioLine]。
     * `pulseCenters` 只放附近（±1.5 行周期）的实测脉冲，供 `lineStartRefined` 精调；
     * 精调命中与否，就是 [LineEvent.locked]。
     */
    private fun fitForRadioLine(
        start: Double,
        samples: Double,
        effectiveRate: Double,
    ): SstvSync.SyncFit {
        val syncSamples = mode.syncSeconds * effectiveRate
        val syncOffset = mode.syncOffsetSeconds * effectiveRate
        val predictedCenter = start + syncOffset + syncSamples / 2.0
        val tolerance = samples * 1.5
        val nearby = ArrayList<Double>(4)
        for (index in 0 until centerCount) {
            val center = recentCenters[index]
            if (center.isFinite() && abs(center - predictedCenter) <= tolerance) nearby.add(center)
        }
        return SstvSync.SyncFit(
            pulseCount = nearby.size,
            firstLineStart = start,
            lineSamples = samples,
            nominalLineSamples = nominalLineSamples,
            syncFrequencyHz = nominalSyncHz + currentShiftHz(),
            pulseCenters = nearby,
            syncOffsetSamples = syncOffset,
        )
    }

    /**
     * 在实测脉冲环里找离 [target] 最近的一个。
     *
     * 判定口径与 [SstvSync.SyncFit.lineStartRefined] 完全一致（容差都是
     * `lineSamples × LINE_REFINE_TOLERANCE`，取最近者），因此「是否命中」
     * 与「命中哪一个」在两边得到同一答案。未命中返回 `NaN`。
     */
    private fun nearestPulse(target: Double, tolerance: Double): Double {
        var best = Double.NaN
        var bestDistance = Double.MAX_VALUE
        for (index in 0 until centerCount) {
            val center = recentCenters[index]
            if (!center.isFinite()) continue
            val distance = abs(center - target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = center
            }
        }
        return if (bestDistance <= tolerance) best else Double.NaN
    }

    /** 回调一行图像并更新命中统计与丢锁状态。 */
    private fun emitRadioLine(locked: Boolean) {
        val rows = mode.imageRowsPerRadioLine
        val baseRow = decodedRadioLines * rows
        for (r in 0 until rows) {
            // 复制一份：radioLinePixels 会被下一行覆盖
            onLine(
                LineEvent(
                    imageRow = baseRow + r,
                    pixels = radioLinePixels.copyOfRange(r * width, (r + 1) * width),
                    locked = locked,
                ),
            )
        }
        decodedRadioLines++

        // 命中统计（环形窗口）
        hitWindow[hitWriteIndex] = locked
        hitWriteIndex = (hitWriteIndex + 1) % SYNC_HIT_WINDOW
        if (hitCount < SYNC_HIT_WINDOW) hitCount++
        if (locked) totalHits++

        // 丢锁判定：连续多行没命中实测脉冲 → 标记；重新命中即恢复（行号不重置）
        if (locked) {
            missStreak = 0
            lostSync = false
        } else {
            missStreak++
            if (missStreak >= LOST_SYNC_LINES) lostSync = true
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 失谐与统计
    // ══════════════════════════════════════════════════════════════════

    /** 当前失谐量估计：脉冲足够时用滚动实测均值，否则用初始值。 */
    private fun currentShiftHz(): Double {
        if (shiftCount < MIN_PULSES_FOR_SHIFT_REFINE) return initialShiftHz
        var sum = 0.0
        for (i in 0 until shiftCount) sum += shiftWindow[i]
        return sum / shiftCount - SstvMode.VIS_BREAK_HZ
    }

    private fun hitRate(): Float {
        if (hitCount == 0) return 0f
        var hits = 0
        for (i in 0 until hitCount) if (hitWindow[i]) hits++
        return hits.toFloat() / hitCount
    }

    /** 标称行周期（样本）。 */
    private val nominalLineSamples: Double get() = mode.lineSeconds * sampleRate

    /** 脉冲最短持续样本数（与一期 0.45 的比例一致）。 */
    private val minPulseSamples: Int
        get() = (mode.syncSeconds * sampleRate * MIN_PULSE_RATIO).toInt().coerceAtLeast(2)

    /**
     * 脉冲最长持续样本数：超过它判为**合并段**而非单个同步脉冲。
     *
     * 最典型的合并段是「VIS 停止位 + 第 0 行同步」——二者都是 1200 Hz 且首尾相接。
     * 实测 Robot 36 下正常脉冲约 134 样本，而该合并段长达 622 样本；若把它当普通
     * 脉冲，其中心会比真实同步中心早约 244 样本，整幅图随之错位一行（且被拟合斜率
     * 吸收成缓慢漂移）。这与一期 `findSyncPulses` 剔除首脉冲是同一个道理，
     * 只是这里用长度门限来判定，不必假设「首脉冲必然被污染」。
     */
    private val maxPulseSamples: Int
        get() = (mode.syncSeconds * sampleRate * MAX_PULSE_RATIO).toInt().coerceAtLeast(4)

    companion object {
        /** 同步频率容差（Hz），与 [SstvSync] 一致。 */
        private const val SYNC_TOLERANCE_HZ = 60.0

        /** 脉冲最短持续比例（相对标称同步时长），与一期一致。 */
        private const val MIN_PULSE_RATIO = 0.45

        /**
         * 脉冲最长持续比例（相对标称同步时长）。
         *
         * 正常同步脉冲长度≈1.0 倍标称（过渡带会让它略长）；取 1.6 留出噪声余量，
         * 同时能稳稳挡住「VIS 停止位 + 首脉冲」这类 3 倍以上的合并段。
         */
        private const val MAX_PULSE_RATIO = 1.6

        /** 锁定所需的最少脉冲数，与一期 `SstvSync.MIN_PULSES_FOR_FIT` 一致。 */
        private const val MIN_PULSES_FOR_FIT = 4

        /** 采用实测失谐均值所需的最少脉冲数（与一期一致）。 */
        private const val MIN_PULSES_FOR_SHIFT_REFINE = 8

        /** 已锁定时，与拟合直线外推位置的最大偏差（行周期的倍数）。 */
        private const val CHAIN_TOLERANCE = 0.5

        /**
         * 节拍门限（行周期的倍数）。
         *
         * 下限 [CHAIN_MIN_TOLERANCE] / 上限 [CHAIN_MAX_TOLERANCE] 界定「与上一个已接受
         * 脉冲相差约一个行周期」：真实同步脉冲严格落在这个区间内；图像内容的误检间隔
         * 通常远小于下限，或（跳行的）远大于上限。
         */
        private const val CHAIN_MIN_TOLERANCE = 0.85
        private const val CHAIN_MAX_TOLERANCE = 1.15

        /** 连续多少行未命中实测脉冲就判为丢锁。 */
        private const val LOST_SYNC_LINES = 3

        /** 滚动失谐窗口（脉冲数）。 */
        private const val SHIFT_WINDOW = 32

        /** 实测脉冲中心环形缓冲（行数）。 */
        private const val PULSE_WINDOW = 64

        /** 命中率窗口（行数）。 */
        private const val SYNC_HIT_WINDOW = 64

        /** slant 合法范围。 */
        private const val MIN_SLANT_RATIO = 0.95
        private const val MAX_SLANT_RATIO = 1.05

        /** 频率缓冲初始容量（样本）。 */
        private const val INITIAL_CAPACITY = 1 shl 16
    }
}
