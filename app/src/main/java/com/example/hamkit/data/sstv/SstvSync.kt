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
        /**
         * 同步脉冲起点相对行首的偏移（样本）。
         *
         * 除 Scottie 族外为 0（同步就在行首）；Scottie 为 `2·sep + 2·chanLen`，
         * 即行首位于同步脉冲之前。所有行首计算都必须扣掉它。
         */
        val syncOffsetSamples: Double,
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
            val predictedCenter = lineStart(line) + syncOffsetSamples + syncSamples / 2.0
            val tolerance = lineSamples * LINE_REFINE_TOLERANCE
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
                // 由实测脉冲中心反推行首：扣掉「同步脉冲中心相对行首」的总偏移
                matched - syncSamples / 2.0 - syncOffsetSamples
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
     * @param dropFirstPulse 是否在拟合前剔除首脉冲（默认 true）。
     *
     *   只在**能确定音频里没有 VIS 头**（例如三期的持续接收、纯图像数据流）时传 false：
     *   此时首脉冲就是真实的第 0 行同步，剔除它反而会丢掉一个锚点；
     *   而若起点落在 VIS 的 1900 Hz leader 之后，停止位会与首脉冲粘连成一个
     *   被污染的长脉冲，必须剔除。
     * @return 拟合结果；同步脉冲不足或拟合异常时返回 null
     */
    fun findSyncPulses(
        freqs: FloatArray,
        mode: SstvMode,
        sampleRate: Int,
        hedrShiftHz: Double,
        startIndex: Int = 0,
        dropFirstPulse: Boolean = true,
    ): SyncFit? {
        val nominalLineSamples = mode.lineSeconds * sampleRate
        val syncSamples = mode.syncSeconds * sampleRate
        val minPulseSamples = (syncSamples * MIN_PULSE_RATIO).toInt().coerceAtLeast(2)
        val targetHz = SstvMode.VIS_BREAK_HZ + hedrShiftHz

        // 0. 先做 2 ms **零相位**滑动平均，再在**平滑流**上找同步段。
        //
        //    为什么要平滑：正交鉴频的逐样本频率噪声在真实信道录音上可达 σ≈56 Hz
        //    （实测用户反馈的 Robot 36 录音：同步段内 std 56 Hz），而同步判据是
        //    「|f − 1200| ≤ 60 Hz 且连续 ≥45% 同步时长」—— 逐样本流上的 1200 Hz
        //    平台会被噪声打得千疮百孔，一个候选段都找不到，整段音频直接判
        //    「未检测到足够的行同步脉冲」。2 ms 平均把噪声压低 √96 ≈ 10 倍
        //    （σ≈6 Hz），平台恢复完整；实测同一段录音平滑后 240 个行同步
        //    全部串成一条链（行周期拟合比 1.0000）。
        //
        //    为什么必须是**零相位（居中）**窗口：像素采样在**原频率流**上进行，
        //    行首若被平滑偏移 (window−1)/2 ≈ 47.5 样本，Robot 36（6.6 样本/像素）
        //    就整体水平错位约 7 个像素 —— 合成自环 PSNR 断言会全部崩掉（CI 实测）。
        //    居中窗口让平滑流与原流下标 1:1 对齐，段中心无偏。
        //
        //    为什么放在这里而不是解调器里：像素采样有自己的零相位低通与积分窗，
        //    全局平滑只会白损水平分辨率；同步搜索只需要「段在哪」，2 ms 的时间
        //    模糊（远小于 5–9 ms 的同步脉冲宽度）对段中心的影响可忽略。
        //    合成自环信号噪声接近零，平滑对它无影响，既有测试不受扰动。
        val smoothWindow = (sampleRate * SYNC_SMOOTH_SECONDS).toInt().coerceAtLeast(1)
        val syncFlow = movingAverage(freqs, smoothWindow)

        // 1. 找出所有落在 1200 Hz 附近的连续段（在平滑流上判定，索引与原流对齐）
        val candidates = ArrayList<Pulse>()
        var i = startIndex.coerceIn(0, syncFlow.size)
        while (i < syncFlow.size) {
            if (isSync(syncFlow[i], targetHz)) {
                val start = i
                while (i < syncFlow.size && isSync(syncFlow[i], targetHz)) i++
                if (i - start >= minPulseSamples) {
                    candidates.add(Pulse(start, i))
                }
            } else {
                i++
            }
        }
        if (candidates.size < MIN_PULSES_FOR_FIT) return null

        // 2. 串联：取**最长**的等间距脉冲串，而不是从第一个候选硬串下去。
        //
        //    早期实现只从 candidates.first() 开始贪心：只要首个候选是野值（VIS 前的
        //    频率过渡、图像内容里偶发的 1200 Hz 波动），下一次预测就落空、链长为 1，
        //    于是直接判成「未检测到足够的行同步脉冲」—— 整段音频连同其中十几个
        //    完整帧一起被丢弃（实测：连续接收 10 分钟后的 finalize 永远返回 null）。
        //
        //    改为动态规划：每个候选只需找它之后「一个行周期」附近最近的候选作为后继，
        //    再做二分定位，总复杂度 O(n log n)。这样即使首候选是野值，也能从真正的
        //    脉冲串开始拟合；漏检一个脉冲只是把链切成两段，取较长的那段即可。
        val n = candidates.size
        val centers = DoubleArray(n) { candidates[it].center }
        val next = IntArray(n) { -1 }
        val length = IntArray(n) { 1 }
        val tolerance = nominalLineSamples * PULSE_CHAIN_TOLERANCE
        for (i in n - 1 downTo 0) {
            val expected = centers[i] + nominalLineSamples
            // 二分定位窗口左端：第一个 center >= expected - tolerance
            var left = i + 1
            var right = n
            while (left < right) {
                val mid = (left + right) ushr 1
                if (centers[mid] < expected - tolerance) left = mid + 1 else right = mid
            }
            var bestDistance = Double.MAX_VALUE
            var found = -1
            var index = left
            while (index < n && centers[index] <= expected + tolerance) {
                val distance = kotlin.math.abs(centers[index] - expected)
                if (distance < bestDistance) {
                    bestDistance = distance
                    found = index
                }
                index++
            }
            if (found >= 0) {
                next[i] = found
                length[i] = length[found] + 1
            }
        }
        var bestStart = 0
        for (i in 1 until n) if (length[i] > length[bestStart]) bestStart = i
        val chain = ArrayList<Pulse>(length[bestStart])
        var cursor = bestStart
        while (cursor >= 0) {
            chain.add(candidates[cursor])
            cursor = next[cursor]
        }
        if (chain.size < MIN_PULSES_FOR_FIT) return null

        // 3. 最小二乘拟合：center(k) ≈ a + b·k
        //
        //    默认剔除第一个脉冲：VIS 的停止位（1200 Hz）与图像第一行的同步脉冲
        //    同为 1200 Hz 且首尾相接，极易被检测成一个被污染的「合并段」。剔除后
        //    由拟合直线外推行 0 的位置，使整体对齐不受该污染影响。
        //    音频里确定没有 VIS 时（见 [dropFirstPulse]）则保留，多一个有效锚点。
        val startOffset = if (dropFirstPulse && chain.size > MIN_PULSES_FOR_FIT) 1 else 0
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

        // 截距是「同步脉冲中心」。换算到行首要扣掉两段：
        //   1) 半个同步脉冲（中心 → 起点）
        //   2) 同步起点相对行首的偏移（Scottie 的同步位于行中，行首在其之前）
        val syncOffsetSamples = mode.syncOffsetSeconds * sampleRate
        val firstLineStart = intercept - syncSamples / 2.0 - syncOffsetSamples

        // 统计同步脉冲内的平均频率（只取段中部，避开两侧的频率过渡带），
        // 用于得到比 VIS leader 更精确的失谐量。
        // 在**平滑流**上统计（与段判定同一数据源），并把索引夹紧到平滑流的
        // 有效范围 —— 滑动平均的尾部会比原流短 smoothWindow−1 个样本。
        var frequencySum = 0.0
        var frequencyCount = 0
        for (pulse in chain) {
            val from = (pulse.start + pulse.length / 4).coerceIn(0, syncFlow.size)
            val to = (pulse.end - pulse.length / 4).coerceIn(from, syncFlow.size)
            for (idx in from until to) {
                frequencySum += syncFlow[idx]
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
            syncOffsetSamples = syncOffsetSamples,
        )
    }

    /** 判断某个频率样本是否落在同步脉冲频率附近。 */
    private fun isSync(value: Float, targetHz: Double): Boolean =
        kotlin.math.abs(value - targetHz) <= SYNC_TOLERANCE_HZ

    /**
     * **零相位**滑动平均（居中窗口，输出与输入**下标 1:1 对齐**，长度不变）。
     *
     * 输出下标 i 对应输入 [i − half, i + half] 的均值（half = window/2，窗长取奇数），
     * 即整条流被对称地低通、但**没有任何时间偏移**。这至关重要：像素采样在原流上
     * 进行，若同步段位置被平滑推移，行首就会整体错位（窗长 96 样本 → Robot 36 错
     * 约 7 像素，合成自环 PSNR 全崩）。
     *
     * 边界用「窗内实际已有的项数」归一（等价于部分窗平均），避免开头/结尾被 0 拉偏。
     * 累加用单状态 Double 前向差分：顺序扫描、无随机访问，省掉 O(n) 中间数组。
     */
    private fun movingAverage(input: FloatArray, window: Int): FloatArray {
        // 强制奇数窗长：居中窗口必须左右对称，否则残余相位偏移
        val span = if (window % 2 == 1) window else window + 1
        val half = span / 2
        if (span <= 1 || input.size <= span) return input
        val out = FloatArray(input.size)
        var sum = 0.0
        // 初始窗：[0, span)，首个输出下标 = half
        for (i in 0 until span) sum += input[i]
        out[half] = (sum / span).toFloat()
        for (i in half + 1 until input.size - half) {
            sum += input[i + half] - input[i - half - 1]
            out[i] = (sum / span).toFloat()
        }
        // 边界：前 half 项与后 half 项用「已有项数」归一的部分窗
        for (i in 0 until half) {
            var s = 0.0
            for (k in 0..i + half) s += input[k]
            out[i] = (s / (i + half + 1)).toFloat()
        }
        for (i in input.size - half until input.size) {
            var s = 0.0
            for (k in i - half until input.size) s += input[k]
            out[i] = (s / (input.size - i + half)).toFloat()
        }
        return out
    }

    /**
     * 逐行精调时，在拟合预测位置附近搜索实测脉冲的容差（行周期的倍数）。
     *
     * 三期 `SstvStreamDecoder` 判定某行是否「命中实测脉冲」（`LineEvent.locked`）
     * 时复用同一常量，保证两者对「命中」的定义严格一致。
     */
    internal const val LINE_REFINE_TOLERANCE = 0.2

    /** 同步频率容差（Hz）。 */
    private const val SYNC_TOLERANCE_HZ = 60.0

    /**
     * 同步搜索前对频率流做滑动平均的窗长（秒）。
     *
     * 2 ms 实测够用：Robot 36 真实录音（用户反馈的失败样例）平滑后 240 个行同步
     * 全部串成一条链，行周期拟合比 1.0000；更长的窗会把 5 ms 的同步脉冲与前后
     * 图像内容互相污染，反而模糊段边界。
     */
    private const val SYNC_SMOOTH_SECONDS = 0.002

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
