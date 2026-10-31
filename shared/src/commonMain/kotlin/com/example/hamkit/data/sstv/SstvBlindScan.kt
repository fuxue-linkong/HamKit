package com.example.hamkit.data.sstv

import kotlin.math.abs

/**
 * 盲扫：VIS 头丢失（或被噪声吃掉）时，直接由音频反推模式。
 *
 * **为什么值得做**：VIS 是 SSTV 里最脆弱的一段 —— 只有 910 ms、音调间隔仅 200 Hz，
 * 弱信号、SSB 调谐偏差、录音开头被切掉都会让它失效。一期在这种情况下的唯一出路是
 * 「让用户逐个模式手动试」，而模式表已有 18 项，试错成本很高。
 *
 * **判定依据（按强度排序）**：
 * 1. **行同步间隔**是最强的指纹。各模式行周期互不相同（67 / 100 / 150 / 226.8 /
 *    277.7 / 300 / 388.16 / 428.38 / 446.446 / 508.48 / 703.04 / 754.24 / 804.416 /
 *    937.28 / 1000 / 1050.3 ms），把实测同步脉冲串起来一拟合就能筛掉绝大多数候选。
 *    这里直接复用 [SstvSync.findSyncPulses]：它内部已经带了「相邻脉冲间隔必须落在
 *    标称周期的 ±18% 内」与「拟合斜率必须在标称值的 0.95–1.05 之间」两道闸门。
 * 2. **失谐量**：同步脉冲是 1200 Hz，且行行都有。对 1000–1400 Hz 区间做频率直方图，
 *    取峰值即可得到实测同步频率，从而在**未知失谐**的情况下也能正确判定 —— 这是
 *    VIS 缺失时最容易踩的坑（VIS 原本是失谐量的来源）。
 * 3. **黑电平间隙结构**：这是区分「行周期接近」的模式的**唯一可靠手段**。同步位置
 *    本身不携带信息（拟合时已按模型的 `syncOffsetSeconds` 折算过），但**间隙（porch
 *    与通道分隔）在行内的位置**是模式指纹：Martin 的三个间隙都在同步之后，
 *    Scottie 有两个在同步之前。图像内容很难长时间稳定停在 1500 Hz，因此「模型预期的
 *    间隙处是不是真的黑电平」能直接判出族别。采样时补偿了解调器约 33 样本的群延迟。
 * 4. **试解码的平整度**：对通过前三步的少数候选真的解一次，比较「相邻像素亮度差」
 *    的均值。时序错的模式会把同步脉冲/porch 采进图像，横向差异明显变大。
 *
 * **已知局限**（UI 因此必须展示候选列表而不是只给一个答案）：
 * - 行周期相同的模式无法靠第 1 步区分，只能靠第 3 步。本实现里
 *   `ROBOT_24 / ROBOT_36`（都是 150 ms）**时序完全等价**，解出来的图像本身相同，
 *   区分只剩 VIS 码语义，因此按模式表顺序取先者；
 *   `ROBOT_12_BW / ROBOT_24_BW`（都是 100 ms）同理，靠行数（120 vs 240）与平整度弱区分。
 * - Scottie 1（428.38 ms）与 Martin 1（446.446 ms）相差仅 4.2%，落在 5% 斜率闸门内，
 *   必须依赖第 3 步；两者的同步位置（行中 vs 行首）不同，试解码差异通常足够明显。
 * - 解调器只能用**一个**相位差分间隔（各模式像素时长相差 8 倍），对极端模式略有损失。
 *
 * 纯 Kotlin 实现，不依赖 Android API。
 */
object SstvBlindScan {

    /**
     * 一个候选模式的评分明细。
     *
     * @param pulseCount 串链成功的同步脉冲数
     * @param slantRatio 拟合行周期与标称值之比
     * @param measuredShiftHz 由同步脉冲实测频率反推的失谐量
     * @param startSample 拟合出的第 0 行行首样本位置（可直接喂给解码器）
     * @param coverage 试解码覆盖率：已解出行数 / 该窗口理论上应有的行数
     * @param smoothness 相邻像素亮度平均差异（越小越像真图像）
     * @param score 综合得分（0–1，越大越好）
     */
    data class Candidate(
        val mode: SstvMode,
        val pulseCount: Int,
        val slantRatio: Double,
        val measuredShiftHz: Double,
        val startSample: Double,
        val coverage: Double,
        val smoothness: Double,
        val score: Double,
    )

    /**
     * 盲扫结果。
     *
     * @param best 得分最高的模式；为 null 表示所有候选都被否掉
     * @param candidates 按得分降序的全部候选（UI 应展示前几名供用户一键切换）
     * @param confidence 最高分与次高分的相对差距（0–1；只有单一候选时为 1）
     * @param reason `best` 为 null 时的原因
     */
    data class Result(
        val best: Candidate?,
        val candidates: List<Candidate>,
        val confidence: Double,
        val reason: String? = null,
    ) {
        /** 是否可以直接按 [best] 解码。 */
        val usable: Boolean get() = best != null
    }

    /**
     * 对一段音频做盲扫。
     *
     * @param audio 单声道浮点音频（±1.0）
     * @param sampleRate 采样率（Hz）
     * @param windowSeconds 参与判定的音频长度上限。默认 8 秒 —— 足以让最慢的
     *   Scottie DX（1050 ms/行）凑够拟合所需的脉冲，同时把 18 个候选的总开销
     *   压在百毫秒量级（18 × 8 s × 48 kHz ≈ 7 M 次比较）
     * @param skipSeconds 跳过开头的时长。默认 1.0 秒：VIS 头总长 0.91 s，跳掉它可
     *   避免把 VIS 的 1200 Hz 分隔位当成行同步脉冲串进拟合
     */
    fun scan(
        audio: FloatArray,
        sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
        windowSeconds: Double = DEFAULT_WINDOW_SECONDS,
        skipSeconds: Double = DEFAULT_SKIP_SECONDS,
    ): Result {
        if (audio.size < (MIN_SECONDS * sampleRate).toInt()) {
            return Result(null, emptyList(), 0.0, "音频过短（需至少 ${MIN_SECONDS} 秒）")
        }

        val window = if (audio.size > (windowSeconds * sampleRate).toInt()) {
            audio.copyOf((windowSeconds * sampleRate).toInt())
        } else {
            audio
        }

        // ── 一次解调，全部候选共用 ──
        val demodulator = SstvDemodulator(
            sampleRate = sampleRate,
            phaseSpan = SstvDemodulator.recommendPhaseSpan(medianSamplesPerPixel(sampleRate)),
        )
        val freqs = demodulator.demodulate(window)
        val validFrom = demodulator.transientSamples
        val searchFrom = (skipSeconds * sampleRate).toInt().coerceAtLeast(validFrom)

        // ── 失谐量：同步脉冲是唯一长期停在 1200 Hz 附近的内容 ──
        val shift = estimateSyncShift(freqs, sampleRate, searchFrom)

        // ── 第一轮：行周期筛选 ──
        val passed = ArrayList<SyncCandidate>()
        for (mode in SstvMode.entries) {
            if (!mode.decodable) continue
            val fit = SstvSync.findSyncPulses(
                freqs = freqs,
                mode = mode,
                sampleRate = sampleRate,
                hedrShiftHz = shift,
                startIndex = searchFrom,
            ) ?: continue
            if (fit.pulseCount < MIN_PULSES) continue
            passed.add(SyncCandidate(mode, fit))
        }
        if (passed.isEmpty()) {
            return Result(null, emptyList(), 0.0, "未能在任何已知模式下串出同步脉冲串")
        }

        // ── 第二轮：只对脉冲数接近最优的候选做试解码（试解码是这里最贵的一步）──
        val maxPulses = passed.maxOf { it.fit.pulseCount }
        val shortlist = passed.filter { it.fit.pulseCount >= maxPulses * SHORTLIST_RATIO }

        val decoder = SstvDecoder(sampleRate)
        val scored = shortlist.map { candidate ->
            val mode = candidate.mode
            val fit = candidate.fit
            val outcome = decoder.decodeFromFrequencies(
                freqs = freqs,
                mode = mode,
                hedrShiftHz = fit.measuredShiftHz,
                searchFromSample = fit.firstLineStart.toInt().coerceAtLeast(0),
                validFromSample = validFrom,
            )
            val pixels = (outcome as? SstvDecoder.Outcome.Success)?.result
            val decodedLines = pixels?.decodedLines ?: 0
            val expectedLines = expectedLinesInWindow(mode, freqs.size, fit.lineSamples)
            val coverage = if (expectedLines > 0) {
                (decodedLines.toDouble() / expectedLines).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val smoothness = pixels
                ?.let { horizontalRoughness(it.pixels, it.width, it.height, decodedLines) }
                ?: MAX_SMOOTHNESS

            val structure = gapStructureScore(
                freqs = freqs,
                mode = mode,
                fit = fit,
                sampleRate = sampleRate,
                shiftHz = fit.measuredShiftHz,
                maxLines = STRUCTURE_LINES,
            )

            val pulseScore = fit.pulseCount.toDouble() / maxPulses
            val smoothScore = (1.0 - smoothness / MAX_SMOOTHNESS).coerceIn(0.0, 1.0)
            val score = WEIGHT_PULSES * pulseScore +
                WEIGHT_STRUCTURE * structure +
                WEIGHT_COVERAGE * coverage +
                WEIGHT_SMOOTHNESS * smoothScore

            Candidate(
                mode = mode,
                pulseCount = fit.pulseCount,
                slantRatio = fit.slantRatio,
                measuredShiftHz = fit.measuredShiftHz,
                startSample = fit.firstLineStart,
                coverage = coverage,
                smoothness = smoothness,
                score = score,
            )
        }.sortedWith(
            // 得分相同时按模式表的声明顺序（等于按历史/常见度排），保证结果稳定可复现
            compareByDescending<Candidate> { it.score }
                .thenBy { SstvMode.entries.indexOf(it.mode) },
        )

        val best = scored.first()
        val confidence = if (scored.size == 1) {
            1.0
        } else {
            ((best.score - scored[1].score) / best.score.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        }
        return Result(best, scored, confidence)
    }

    /** 内部：同步拟合通过的候选。 */
    private class SyncCandidate(val mode: SstvMode, val fit: SstvSync.SyncFit)

    /**
     * 由频率直方图估计实测同步频率，返回相对标称 1200 Hz 的偏移。
     *
     * 只在 1000–1400 Hz 内统计：SSTV 里长期停在该区间的只有同步脉冲与 VIS 的分隔位，
     * 而后者已被 [DEFAULT_SKIP_SECONDS] 跳过。峰值箱必须同时满足两个条件才被采信
     * （否则返回 0，退化为「按标称频率判定」）：
     *  1. 绝对量足够：至少 [SHIFT_MIN_MS] 毫秒的音调落进该箱；
     *  2. 相对量足够：至少是全部箱均值的 [SHIFT_PEAK_RATIO] 倍，避免在纯噪声上瞎猜。
     */
    public fun estimateSyncShift(freqs: FloatArray, sampleRate: Int, from: Int): Double {
        val bins = IntArray(SHIFT_BINS)
        val start = from.coerceIn(0, freqs.size)
        var counted = 0
        for (i in start until freqs.size) {
            val hz = freqs[i]
            if (hz < SHIFT_LO_HZ || hz > SHIFT_HI_HZ) continue
            val bin = ((hz - SHIFT_LO_HZ) / SHIFT_BIN_HZ).toInt().coerceIn(0, SHIFT_BINS - 1)
            bins[bin]++
            counted++
        }
        if (counted <= 0) return 0.0
        var bestBin = 0
        var total = 0
        for (b in 0 until SHIFT_BINS) {
            total += bins[b]
            if (bins[b] > bins[bestBin]) bestBin = b
        }
        val peak = bins[bestBin]
        val mean = total.toDouble() / SHIFT_BINS
        val minAbsolute = sampleRate * SHIFT_MIN_MS / 1000
        if (peak < minAbsolute || peak < mean * SHIFT_PEAK_RATIO) return 0.0
        val centerHz = SHIFT_LO_HZ + (bestBin + 0.5) * SHIFT_BIN_HZ
        return centerHz - SstvMode.VIS_BREAK_HZ
    }

    /** 该窗口在给定行周期下理论上能容纳多少图像行（不超过模式本身的行数）。 */
    private fun expectedLinesInWindow(
        mode: SstvMode,
        windowSamples: Int,
        lineSamples: Double,
    ): Int {
        if (lineSamples <= 0.0) return 0
        val lines = (windowSamples / lineSamples).toInt()
        return lines.coerceIn(1, mode.radioLines) * mode.imageRowsPerRadioLine
    }

    /**
     * 结构一致性：模型预期的**黑电平间隙（porch / 通道分隔）**位置上是否真的是黑电平。
     *
     * 这是区分「行周期接近」的候选的唯一可靠手段。同步脉冲位置本身不含信息 ——
     * 拟合出的行首已经按模型的 `syncOffsetSeconds` 折算过，所以任何模型都能让同步
     * 对齐；但**间隙在行内的分布**无法伪造：Martin 的三个间隙全在同步之后
     * （0.57 / 147 / 294 ms），Scottie 有两个在同步之前（−290 / −150 ms）。
     *
     * **刻意不做群延迟补偿**：[SstvSync] 拟合出的行首本身就是**已延迟的频率流**上的
     * 位置（脉冲中心也是在这条流上测的），两者在同一时间轴上，直接相加即可。
     * 额外补偿会把采样点推出 0.572 ms 的 Martin 间隙，导致正确模式也判为不命中
     * （与解码器默认 `groupDelaySamples = 0` 是同一个道理）。
     *
     * @return 命中比例（0–1）；模型没有间隙时给中性值 0.5，避免该项变成惩罚
     */
    public fun gapStructureScore(
        freqs: FloatArray,
        mode: SstvMode,
        fit: SstvSync.SyncFit,
        sampleRate: Int,
        shiftHz: Double,
        maxLines: Int,
    ): Double {
        val blackTarget = SstvMode.BLACK_HZ + shiftHz
        val effectiveRate = fit.effectiveRate(sampleRate)
        val syncSamples = mode.syncSeconds * effectiveRate
        val lines = minOf(mode.radioLines, maxLines)
        var hits = 0
        var total = 0

        for (line in 0 until lines) {
            val lineStart = fit.lineStartRefined(line, syncSamples)
            var elapsed = 0.0
            for (event in mode.lineEvents()) {
                when (event) {
                    is SstvLineEvent.Sync -> elapsed += event.seconds
                    is SstvLineEvent.Channel ->
                        elapsed += event.task.pixels * event.task.pixelSeconds
                    // 奇偶标记刻意**不**参与结构判据：它只在偶行是黑电平，
                    // 奇行反而是白电平，把它当「预期黑电平」会给一半的行记错分
                    is SstvLineEvent.ParityMarker -> elapsed += event.seconds
                    is SstvLineEvent.Gap -> {
                        val centerSamples = lineStart + (elapsed + event.seconds / 2.0) * effectiveRate
                        elapsed += event.seconds
                        total++
                        if (isBlackAt(freqs, centerSamples, event.seconds * effectiveRate, blackTarget)) {
                            hits++
                        }
                    }
                }
            }
        }
        return if (total == 0) 0.5 else hits.toDouble() / total
    }

    /** 取间隙中部的一小段求平均频率，判断是否落在黑电平附近。 */
    private fun isBlackAt(
        freqs: FloatArray,
        centerSample: Double,
        gapSamples: Double,
        blackTarget: Double,
    ): Boolean {
        val half = maxOf(1.0, gapSamples / 4.0)
        val from = (centerSample - half).toInt()
        val to = (centerSample + half).toInt() + 1
        if (from < 0 || to > freqs.size || to <= from) return false
        var sum = 0.0
        for (i in from until to) sum += freqs[i]
        val mean = sum / (to - from)
        return abs(mean - blackTarget) <= BLACK_TOLERANCE_HZ
    }

    /**
     * 「横向粗糙度」：相邻像素亮度差的均值。
     *
     * 时序正确时，图像在水平方向是连续的自然过渡；时序错位（采到同步脉冲、通道
     * 边界错位、行配对错误）会在行内插入大量高频跳变，该指标随之显著变大。
     * 只在 [decodedLines] 行内统计，避免把未解码的全黑区域算进来。
     */
    public fun horizontalRoughness(
        pixels: IntArray,
        width: Int,
        height: Int,
        decodedLines: Int,
    ): Double {
        if (width < 2 || decodedLines < 2) return MAX_SMOOTHNESS
        val rows = decodedLines.coerceAtMost(height)
        var sum = 0.0
        var count = 0
        for (y in 0 until rows) {
            val base = y * width
            for (x in 0 until width - 1) {
                sum += abs(luminance(pixels[base + x + 1]) - luminance(pixels[base + x]))
                count++
            }
        }
        return if (count == 0) MAX_SMOOTHNESS else sum / count
    }

    /** 打包 RGB → 亮度（整数近似，仅用于相对比较）。 */
    private fun luminance(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /** 全部模式像素时长的中位数换算成样本数（用于选一个折中的相位差分间隔）。 */
    private fun medianSamplesPerPixel(sampleRate: Int): Double {
        val sorted = SstvMode.entries.map { it.pixelSeconds * sampleRate }.sorted()
        return sorted[sorted.size / 2]
    }

    /** 默认判定窗口（秒）。 */
    const val DEFAULT_WINDOW_SECONDS = 8.0

    /** 默认跳过的开头（秒），用于避开 VIS 头。 */
    const val DEFAULT_SKIP_SECONDS = 1.0

    /** 参与拟合所需的最少同步脉冲数。 */
    private const val MIN_PULSES = 4

    /** 进入试解码的门槛：脉冲数不低于最优者的该比例。 */
    private const val SHORTLIST_RATIO = 0.9

    /** 最短可判定音频（秒）。 */
    private const val MIN_SECONDS = 1.5

    private const val SHIFT_LO_HZ = 1000.0
    private const val SHIFT_HI_HZ = 1400.0
    private const val SHIFT_BIN_HZ = 10.0
    private const val SHIFT_BINS = 40

    /** 采信失谐估计所需的最少音调时长（毫秒，落在同一频率箱内）。 */
    private const val SHIFT_MIN_MS = 5

    /** 峰值箱相对全部箱均值的倍数下限。 */
    private const val SHIFT_PEAK_RATIO = 3.0

    /** 粗糙度的参考上限（亮度差均值）。达到此值即认为「完全不成图像」。 */
    public const val MAX_SMOOTHNESS = 48.0

    /** 结构一致性判据的参与行数（够用即可，越多越慢）。 */
    private const val STRUCTURE_LINES = 40

    /** 间隙处判定为「黑电平」的频率容差（Hz）。 */
    private const val BLACK_TOLERANCE_HZ = 120.0

    private const val WEIGHT_PULSES = 0.35
    private const val WEIGHT_STRUCTURE = 0.25
    private const val WEIGHT_COVERAGE = 0.15
    private const val WEIGHT_SMOOTHNESS = 0.25
}
