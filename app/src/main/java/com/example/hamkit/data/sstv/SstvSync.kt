package com.example.hamkit.data.sstv

/**
 * 行同步检测与 slant（斜率）校正。
 *
 * SSTV 每行以 **1200 Hz 同步脉冲**开头，而 1200 Hz 位于亮度映射区间（1500–2300 Hz）
 * 之外，因此不会被图像内容误触发，是天然可靠的行锚点。
 *
 * **为什么必须做 slant 校正**：收发双方时钟不同源（电台 vs 手机声卡），加上多普勒
 * 与声学路径，实际行周期与标称值常有 0.1%–1% 偏差。若按标称周期推算行位置，
 * 误差会随行数线性累积 —— 1% 偏差在 100 行后就是整整一行的错位，图像会明显斜切。
 *
 * 这里用**最小二乘线性回归**拟合「同步脉冲位置 vs 行号」，得到实际行周期，
 * 再用拟合直线反推每行的起点，从根源上消除累积误差。
 *
 * 纯 Kotlin 实现，不依赖 Android API。
 */
object SstvSync {

    /**
     * 同步脉冲的拟合结果。
     *
     * @param pulseCount 参与拟合的同步脉冲数
     * @param firstLineStart 第 0 行的行首（同步脉冲起点）样本位置
     * @param lineSamples 拟合出的实际行周期（样本数）
     * @param nominalLineSamples 模式标称行周期（样本数）
     */
    data class SyncFit(
        val pulseCount: Int,
        val firstLineStart: Double,
        val lineSamples: Double,
        val nominalLineSamples: Double,
        /**
         * 所有同步脉冲内的平均频率（Hz）。
         *
         * 同步脉冲的标称频率是 1200 Hz，因此 `syncFrequencyHz − 1200` 就是实测的
         * 频率偏移（失谐 + 多普勒）。它由成百上千个脉冲平均而来，精度远高于 VIS
         * leader 的单次估计（后者只有 300 ms 观察时间）。
         */
        val syncFrequencyHz: Double,
        /**
         * 串联成功的各同步脉冲中心位置（样本）。
         *
         * 逐行解码时用它在预测位置附近取最近的真实脉冲做**逐行精调**：拟合直线
         * 只给出平均行周期，无法反映单行的定位抖动（多普勒、采样抖动），而每行
         * 1–2 样本的偏差在变化剧烈的图像内容上就会显现为可见误差。
         */
        val pulseCenters: List<Double>,
    ) {
        /** 由同步脉冲实测频率反推的失谐量（Hz）。 */
        val measuredShiftHz: Double get() = syncFrequencyHz - SstvMode.VIS_BREAK_HZ

        /** 实际行周期与标称值之比，正常范围 0.99–1.01。 */
        val slantRatio: Double get() = lineSamples / nominalLineSamples

        /**
         * slant 校正后的有效采样率。
         *
         * **行内时间换算也必须用它**（而不是标称采样率），否则图像会在水平方向
         * 被拉伸/压缩相同比例 —— slowrx 也是把 slant 校正后的速率同时用于像素时间
         * （`mode_pd.rs` 的 `rate_hz` 参数）。
         */
        fun effectiveRate(sampleRate: Int): Double = sampleRate * slantRatio

        /** 推算第 [line] 行的行首样本位置。 */
        fun lineStart(line: Int): Double = firstLineStart + line * lineSamples

        /**
         * 取第 [line] 行的行首位置，优先使用该行**实际检测到的**同步脉冲中心。
         *
         * 在拟合预测位置附近（±20% 行周期）寻找最近的实测脉冲；命中则以其中心反推
         * 行首，否则退回拟合预测值。这样既保持整体 slant 校正，又吸收单行抖动。
         */
        fun lineStartRefined(line: Int, syncSamples: Double): Double {
            val predictedCenter = lineStart(line) + syncSamples / 2.0
            val tolerance = lineSamples * 0.2
            var best: Double? = null
            var bestDistance = Double.MAX_VALUE
            for (center in pulseCenters) {
                val distance = kotlin.math.abs(center - predictedCenter)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = center
                }
            }
            val matched = best
            return if (matched != null && bestDistance <= tolerance) {
                matched - syncSamples / 2.0
            } else {
                lineStart(line)
            }
        }
    }

    /** 一个候选同步脉冲段（在频率流中的位置与长度）。 */
    private data class Pulse(val start: Int, val end: Int) {
        val center: Double get() = (start + end) / 2.0
        val length: Int get() = end - start
    }

    /**
     * 在频率流中检测行同步脉冲并拟合行位置。
     *
     * @param freqs 逐样本瞬时频率（Hz），来自 [SstvDemodulator]
     * @param mode 目标模式
     * @param sampleRate 采样率（Hz）
     * @param hedrShiftHz 实测 leader 与标称 1900 Hz 的偏差，用于校正同步频率目标
     * @param startIndex 从该样本位置开始搜索（通常为 VIS 停止位之后）
     * @return 拟合结果；同步脉冲不足或拟合异常时返回 null
     */
    fun findSyncPulses(
        freqs: FloatArray,
        mode: SstvMode,
        sampleRate: Int,
        hedrShiftHz: Double,
        startIndex: Int = 0,
    ): SyncFit? {
        val nominalLineSamples = mode.lineSeconds * sampleRate
        val syncSamples = mode.syncSeconds * sampleRate
        val minPulseSamples = (syncSamples * MIN_PULSE_RATIO).toInt().coerceAtLeast(2)
        val targetHz = SstvMode.VIS_BREAK_HZ + hedrShiftHz

        // 1. 找出所有落在 1200 Hz 附近的连续段
        val candidates = ArrayList<Pulse>()
        var i = startIndex.coerceIn(0, freqs.size)
        while (i < freqs.size) {
            if (isSync(freqs[i], targetHz)) {
                val start = i
                while (i < freqs.size && isSync(freqs[i], targetHz)) i++
                if (i - start >= minPulseSamples) {
                    candidates.add(Pulse(start, i))
                }
            } else {
                i++
            }
        }
        if (candidates.size < MIN_PULSES_FOR_FIT) return null

        // 2. 贪心串联：按标称行周期预测下一脉冲位置，在容差窗内取最近候选，
        //    避免把图像内容中的偶然 1200 Hz 附近波动串进来。
        val chain = ArrayList<Pulse>()
        var current = candidates.first()
        chain.add(current)
        var searchFrom = 1
        while (true) {
            val expected = current.center + nominalLineSamples
            val tolerance = nominalLineSamples * PULSE_CHAIN_TOLERANCE
            var best: Pulse? = null
            var bestDistance = Double.MAX_VALUE
            for (idx in searchFrom until candidates.size) {
                val candidate = candidates[idx]
                val distance = kotlin.math.abs(candidate.center - expected)
                if (distance <= tolerance && distance < bestDistance) {
                    best = candidate
                    bestDistance = distance
                }
                // 候选已明显超出预测窗，后续更不可能命中
                if (candidate.center - expected > tolerance) break
            }
            if (best == null) break
            chain.add(best)
            searchFrom = candidates.indexOf(best) + 1
            current = best
        }
        if (chain.size < MIN_PULSES_FOR_FIT) return null

        // 3. 最小二乘拟合：center(k) ≈ a + b·k
        //
        //    刻意剔除第一个脉冲：VIS 的停止位（1200 Hz）与图像第一行的同步脉冲
        //    同为 1200 Hz 且首尾相接，极易被检测成一个被污染的「合并段」。剔除后
        //    由拟合直线外推行 0 的位置，使整体对齐不受该污染影响。
        val startOffset = if (chain.size > MIN_PULSES_FOR_FIT) 1 else 0
        val m = chain.size - startOffset
        var sumK = 0.0
        var sumP = 0.0
        var sumKK = 0.0
        var sumKP = 0.0
        for (idx in startOffset until chain.size) {
            val k = idx.toDouble()
            val p = chain[idx].center
            sumK += k
            sumP += p
            sumKK += k * k
            sumKP += k * p
        }
        val denominator = m * sumKK - sumK * sumK
        if (denominator <= 0.0) return null
        val slope = (m * sumKP - sumK * sumP) / denominator
        // 拟合直线在 k = 0 处的取值（行 0 的同步脉冲中心）
        val intercept = (sumP - slope * sumK) / m

        // 行周期必须接近标称值，否则说明串链错误
        if (slope <= 0.0 || slope < nominalLineSamples * MIN_SLANT_RATIO ||
            slope > nominalLineSamples * MAX_SLANT_RATIO
        ) {
            return null
        }

        // 截距是「同步脉冲中心」，换算到行首（同步脉冲起点）
        val firstLineStart = intercept - syncSamples / 2.0

        // 统计同步脉冲内的平均频率（只取段中部，避开两侧的频率过渡带），
        // 用于得到比 VIS leader 更精确的失谐量
        var frequencySum = 0.0
        var frequencyCount = 0
        for (pulse in chain) {
            val from = pulse.start + pulse.length / 4
            val to = pulse.end - pulse.length / 4
            for (idx in from until to) {
                frequencySum += freqs[idx]
                frequencyCount++
            }
        }
        val syncFrequencyHz = if (frequencyCount > 0) {
            frequencySum / frequencyCount
        } else {
            SstvMode.VIS_BREAK_HZ + hedrShiftHz
        }

        return SyncFit(
            pulseCount = chain.size,
            firstLineStart = firstLineStart,
            lineSamples = slope,
            nominalLineSamples = nominalLineSamples,
            syncFrequencyHz = syncFrequencyHz,
            pulseCenters = chain.map { it.center },
        )
    }

    /** 判断某个频率样本是否落在同步脉冲频率附近。 */
    private fun isSync(value: Float, targetHz: Double): Boolean =
        kotlin.math.abs(value - targetHz) <= SYNC_TOLERANCE_HZ

    /** 同步频率容差（Hz）。 */
    private const val SYNC_TOLERANCE_HZ = 60.0

    /** 脉冲最短持续比例（相对标称同步时长）。 */
    private const val MIN_PULSE_RATIO = 0.45

    /** 串联时允许的预测位置偏差比例。 */
    private const val PULSE_CHAIN_TOLERANCE = 0.18

    /** 参与拟合所需的最少同步脉冲数。 */
    private const val MIN_PULSES_FOR_FIT = 4

    /** slant 比例的下限（低于此值判为异常）。 */
    private const val MIN_SLANT_RATIO = 0.95

    /** slant 比例的上限。 */
    private const val MAX_SLANT_RATIO = 1.05
}
