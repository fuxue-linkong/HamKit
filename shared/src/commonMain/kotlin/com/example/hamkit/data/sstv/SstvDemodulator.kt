package com.example.hamkit.data.sstv

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * SSTV 正交鉴频器：把 FM 音频转换为**逐样本**的瞬时频率流。
 *
 * 这是 SSTV 解码链路的第二级（见 docs/REQUIREMENT_SSTV.md §4.3）。与 VIS 检测
 * （10 ms 步长的短时 FFT 峰值跟踪）不同，像素解调要求的时间分辨率是
 * 137–457 µs，因此必须逐样本估计频率。
 *
 * **算法**（复数混频正交鉴频 / 数字下变频 + 鉴频器）：
 * ```
 * I(n) = LPF( x(n) ·  cos(2π·f₀·n/fs) )
 * Q(n) = LPF( x(n) · −sin(2π·f₀·n/fs) )
 * f(n) = f₀ + unwrap(φ(n) − φ(n−span)) · fs / (2π · span)
 * ```
 * - I/Q 两路使用**相同的滤波器**，群延迟一致，因此相位差不受滤波器相移影响；
 * - 相位差必须解缠（折叠到 `(−π, π]`），否则会出现 ±fs 的跳变；
 * - 中心频率取 1900 Hz（VIS leader），基带落在 ±400 Hz 内，远小于奈奎斯特。
 *
 * **低通必须用 8 阶 Butterworth** —— 这是实测得出的硬性要求，源自一对相互冲突的约束：
 *  1. **调制带宽**：图像的水平细节体现为频率的快速跳变。低通截止决定了水平过渡带宽度
 *     （约 `0.35/fc` 秒），截止过低会把相邻像素抹平 —— 600 Hz 截止时 PD-120 的过渡带
 *     跨越约 6 个像素，自环 PSNR 仅 18 dB。
 *  2. **镜像抑制**：混频后除所需基带外还有 **2f₀ ≈ 3800 Hz 的和频镜像**。信号频率接近
 *     中心频率时基带 I 分量趋近 0，镜像残留会直接放大成相位抖动，且该噪声与输入信噪比
 *     无关（无噪声输入下同样存在）。
 *
 * 两者靠「阶数」调和：截止越宽则镜像越强，只有提高阶数才能在放宽截止的同时把镜像压住。
 * ```
 * 2 阶 + 600 Hz → 3800 Hz 处仅 −32 dB → I 残留 0.0125 → ≈42 Hz 频率噪声
 * 4 阶 + 600 Hz → 3800 Hz 处 −64 dB   → I 残留 3e-4   → ≈1 Hz 频率噪声
 * 8 阶 + 1200 Hz → 3800 Hz 处 −80 dB  → 镜像可忽略，同时调制带宽翻倍
 * ```
 * 最终取 **8 阶 + 1200 Hz**：既把镜像压到可忽略，又把水平过渡带缩短一半。
 *
 * **降噪：多样本相位差分**
 *
 * 相邻样本的相位差只用到 2 个相位值，噪声标准差约 `σφ·√2·fs/2π`。因此这里采用
 * **间隔 [phaseSpan] 个样本的后向差分**，噪声标准差按 `1/span` 线性下降，而时间
 * 分辨率降为 `2·span` 个样本 —— 对 PD-120（9.12 样本/像素）取 span=4 恰好落在一个
 * 像素内，是噪声与分辨率的合理折中。解码器可用 [recommendPhaseSpan] 自适应选择。
 *
 * **瞬态**：IIR 滤波器启动后需要若干样本稳定，前 [transientSamples] 个输出不可信，
 * 解码器应跳过这段（每帧开始时调用 [reset]）。
 *
 * 纯 Kotlin 实现，不依赖 Android API，可直接在 JVM 单元测试中验证。
 *
 * @param sampleRate 采样率（Hz）
 * @param centerHz 混频中心频率（Hz），默认 1900
 * @param cutoffHz 低通截止频率（Hz），需覆盖 1500–2300 Hz 的基带偏移
 * @param phaseSpan 相位差分间隔（样本数），≥1；越大噪声越低、时间分辨率越差
 */
class SstvDemodulator(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    private val centerHz: Double = SstvMode.VIS_LEADER_HZ,
    private val cutoffHz: Double = DEFAULT_CUTOFF_HZ,
    private val phaseSpan: Int = DEFAULT_PHASE_SPAN,
) {

    init {
        require(phaseSpan >= 1) { "相位差分间隔必须 ≥ 1，实际为 $phaseSpan" }
        require(centerHz > cutoffHz) { "中心频率必须高于低通截止频率" }
    }

    // ── I / Q 两路的 8 阶 Butterworth 低通（各自独立状态，系数相同） ──
    private val inPhaseFilter = ButterworthLowpass(FILTER_ORDER, sampleRate, cutoffHz)
    private val quadratureFilter = ButterworthLowpass(FILTER_ORDER, sampleRate, cutoffHz)

    /** 本振相位（弧度）。 */
    private var localPhase = 0.0

    /**
     * 相位环形缓冲，长度 = phaseSpan + 1。
     *
     * 写入并前移后，`phiRing[phiWrite]` 恰好是 phaseSpan 个样本前的相位。
     */
    private val phiRing = DoubleArray(phaseSpan + 1)
    private var phiWrite = 0
    private var phiFilled = 0

    // ── 信号质量估计：与 phaseSpan 无关的「相位二阶差分」统计 ──
    //
    // 刻意用**二阶**相位差（相邻两个逐样本相位差之差）而不是一阶：
    // 一阶差分的期望值是载波相位步进（1900 Hz @48 kHz ≈ 0.249 rad/样本），
    // 它比噪声大两三个数量级，直接取中位数只会量到载波本身（实测等效信噪比恒为 0 dB）。
    // 二阶差分把这个常量项完全消掉，同时压低缓慢变化的调制分量，剩下的主要是噪声；
    // 真实的内容跳变只是少数野值，用中位数即可剔除。
    private val noiseRing = FloatArray(NOISE_WINDOW)
    private var noisePtr = 0
    private var noiseFilled = 0
    private var previousPhi = Double.NaN
    private var previousDeltaPhi = Double.NaN

    private val phaseStep = TWO_PI * centerHz / sampleRate

    /** 滤波器与差分窗的瞬态样本数，此区间内的输出不可信。 */
    val transientSamples: Int get() = TRANSIENT_SAMPLES + phaseSpan

    /**
     * 实测频率噪声（Hz，1σ 估计）。
     *
     * 由「相位二阶差分的中位绝对值」换算：对零均值高斯噪声 `median(|X|) = 0.6745σ`，
     * 而二阶差分 `φ[n] − 2φ[n−1] + φ[n−2]` 把噪声标准差放大了 `√6` 倍，
     * 故 `σφ = median / (0.6745 × √6)`，再按 `σf = σφ·fs / 2π` 折算到频率。
     * 样本不足时返回 [NOISE_UNKNOWN_HZ]；中位数为 0（无任何相位抖动）时返回 0.0。
     */
    fun estimatedFrequencyNoiseHz(): Double {
        if (noiseFilled < NOISE_WINDOW / 4) return NOISE_UNKNOWN_HZ
        val sorted = FloatArray(noiseFilled) { noiseRing[it] }
        sorted.sort()
        val median = sorted[sorted.size / 2].toDouble()
        if (median <= 0.0) return 0.0
        val sigmaPhi = median / (GAUSSIAN_MEDIAN_FACTOR * SECOND_DIFFERENCE_GAIN)
        return sigmaPhi * sampleRate / TWO_PI
    }

    /**
     * 等效信噪比指标（dB）。
     *
     * **不是严格的带内 SNR**，而是把实测频率噪声与 SSTV 的最大频偏（±400 Hz）相比：
     * `20·log10(400 / σf)`。它的价值在于**单调**且与积分窗选择直接相关 ——
     * 数值越低说明频率流越脏、越该多平均一些。跨设备的绝对值不必可比，
     * 分档阈值由单元测试（已知 SNR 的合成信号）标定。
     */
    fun estimatedQualityDb(): Double {
        val noiseHz = estimatedFrequencyNoiseHz()
        if (noiseHz <= 0.0) return QUALITY_MAX_DB
        if (noiseHz >= NOISE_UNKNOWN_HZ) return QUALITY_MIN_DB
        return (20.0 * kotlin.math.log10(DEVIATION_HZ / noiseHz)).coerceIn(QUALITY_MIN_DB, QUALITY_MAX_DB)
    }

    /** 清空滤波器与鉴相状态（每帧解码前调用）。 */
    fun reset() {
        inPhaseFilter.reset()
        quadratureFilter.reset()
        localPhase = 0.0
        phiRing.fill(0.0)
        phiWrite = 0
        phiFilled = 0
        noiseRing.fill(0f)
        noisePtr = 0
        noiseFilled = 0
        previousPhi = Double.NaN
        previousDeltaPhi = Double.NaN
    }

    /**
     * 流式处理一个音频块，返回等长的瞬时频率估计（Hz）。
     *
     * 块的边界不会破坏状态（滤波器与相位跨块连续），因此调用方可以任意分块。
     */
    fun process(block: FloatArray): FloatArray {
        val out = FloatArray(block.size)
        var phase = localPhase

        for (n in block.indices) {
            val x = block[n].toDouble()

            // 本振（复数混频）
            val cosP = cos(phase)
            val sinP = sin(phase)
            phase += phaseStep
            if (phase >= TWO_PI) phase -= TWO_PI

            // 8 阶低通：抑制 2f₀ 镜像，否则中心频率附近相位会剧烈抖动
            val inPhase = inPhaseFilter.process(x * cosP)
            val quad = quadratureFilter.process(-x * sinP)

            // 鉴相 → 瞬时频率（间隔 phaseSpan 的后向差分，噪声按 1/span 下降）
            val phi = atan2(quad, inPhase)

            // 信号质量：独立的二阶相位差分（不受 phaseSpan 影响，见类内说明）
            if (!previousPhi.isNaN()) {
                val deltaPhi = unwrap(phi - previousPhi)
                if (!previousDeltaPhi.isNaN()) {
                    noiseRing[noisePtr] = kotlin.math.abs(deltaPhi - previousDeltaPhi).toFloat()
                    noisePtr = (noisePtr + 1) % NOISE_WINDOW
                    if (noiseFilled < NOISE_WINDOW) noiseFilled++
                }
                previousDeltaPhi = deltaPhi
            }
            previousPhi = phi

            phiRing[phiWrite] = phi
            phiWrite = (phiWrite + 1) % phiRing.size
            if (phiFilled < phiRing.size) phiFilled++

            val deltaPhi = if (phiFilled >= phiRing.size) {
                unwrap(phi - phiRing[phiWrite])
            } else {
                0.0
            }

            out[n] = (centerHz + deltaPhi * sampleRate / (TWO_PI * phaseSpan)).toFloat()
        }

        localPhase = phase
        return out
    }

    /** 一次性解调整段音频（便于测试；长音频请用 [process] 分块以控制内存）。 */
    fun demodulate(audio: FloatArray): FloatArray = process(audio)

    /** 把相位差折叠回 (−π, π]，避免频率出现 ±fs 的跳变。 */
    private fun unwrap(delta: Double): Double = when {
        delta > PI -> delta - TWO_PI
        delta < -PI -> delta + TWO_PI
        else -> delta
    }

    companion object {
        private const val TWO_PI = 2.0 * PI

        /**
         * 低通截止频率（Hz）。
         *
         * 混频后 SSTV 的 1500–2300 Hz 落在 ±400 Hz 基带内，但图像的水平细节需要远宽于
         * 400 Hz 的调制带宽。取 1200 Hz 并把阶数提到 8，可在 3800 Hz 镜像处保持 −80 dB
         * 抑制的同时，把水平过渡带缩短一半。
         */
        const val DEFAULT_CUTOFF_HZ = 1200.0

        /** Butterworth 低通阶数（偶数）。 */
        const val FILTER_ORDER = 8

        /** 滤波器稳定时间（@48 kHz 约 5.3 ms）。 */
        const val TRANSIENT_SAMPLES = 256

        /**
         * 默认相位差分间隔。
         *
         * 取 4 是折中：PD-120 每像素 9.12 样本，span=4 的差分窗（8 样本）恰好落在
         * 一个像素内；更短的间隔噪声过大，更长的间隔会跨越像素边界。
         */
        const val DEFAULT_PHASE_SPAN = 4

        /**
         * 按「每像素样本数」推荐相位差分间隔。
         *
         * 约定 `span = round(samplesPerPixel / 2)`，限制在 1..16：
         *  - Robot 36（6.6 样本/像素）→ 3
         *  - PD-120（9.12 样本/像素）→ 5
         *  - Martin 1（22 样本/像素）→ 11
         */
        fun recommendPhaseSpan(samplesPerPixel: Double): Int =
            (samplesPerPixel / 2.0).roundToInt().coerceIn(1, 16)

        // ══════════════════════════════════════════════════════════════
        // 信号质量 → 自适应积分窗
        // ══════════════════════════════════════════════════════════════

        /**
         * 用一段音频估计等效信噪比（dB）。
         *
         * 内部固定用 `phaseSpan = 1` 的解调器：噪声估计必须与最终选用的积分窗
         * **无关**，否则就成了「用结论推结论」。因此调用顺序应是
         * 「先 [estimateQualityDb] 测质量 → 再 [recommendAdaptiveSpan] 定窗 → 最后
         * 用定好的窗建立真正的解调管线」。
         *
         * 建议喂入含**纯音**的一段音频：SSTV 的 300 ms leader 或同步脉冲串都是恒定频率，
         * 其相位抖动全部来自噪声，是最干净的观测窗口。
         *
         * @return 0–[QUALITY_MAX_DB]；音频过短时返回 [QUALITY_MIN_DB]
         */
        fun estimateQualityDb(
            audio: FloatArray,
            sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
        ): Double {
            if (audio.size < sampleRate / 20) return QUALITY_MIN_DB
            val probe = SstvDemodulator(sampleRate = sampleRate, phaseSpan = 1)
            probe.process(audio)
            return probe.estimatedQualityDb()
        }

        /**
         * 把等效信噪比分成 4 档（0 最好，3 最差）。
         *
         * 阈值由合成信号的单元测试标定，含义见 [QUALITY_TIER_BOUNDS_DB]。
         */
        fun qualityTier(qualityDb: Double): Int {
            var tier = QUALITY_TIER_COUNT - 1
            for (t in 0 until QUALITY_TIER_COUNT - 1) {
                if (qualityDb >= QUALITY_TIER_BOUNDS_DB[t]) {
                    tier = t
                    break
                }
            }
            return tier
        }

        /**
         * 分档推荐相位差分间隔。
         *
         * **为什么弱信号值得牺牲水平分辨率**：相位差分的噪声标准差按 `1/span` 线性下降，
         * 而时间分辨率的损失只体现为水平方向的轻微模糊。弱信号下的主要矛盾是「看不清」
         * 而不是「不够锐」，因此这一交换是划算的。
         *
         * 上限取 `round(1.5 × samplesPerPixel)`：整数窗一旦超过约 1.5 个像素，
         * 相邻像素就会被平均到一起，图像开始「糊成一片」，收益转为负。
         */
        fun recommendAdaptiveSpan(samplesPerPixel: Double, tier: Int): Int {
            val base = recommendPhaseSpan(samplesPerPixel)
            val scaled = (base * SPAN_TIER_FACTOR[tier.coerceIn(0, QUALITY_TIER_COUNT - 1)]).roundToInt()
            val maxSpan = (samplesPerPixel * MAX_SPAN_PIXELS).roundToInt()
                .coerceAtLeast(base)
                .coerceAtMost(MAX_ADAPTIVE_SPAN)
            return scaled.coerceIn(1, maxSpan)
        }

        /**
         * 估算低通滤波器在低频段的群延迟（样本数）。
         *
         * IIR 滤波器不是线性相位，输出的频率流相对真实时间轴存在群延迟
         * （`τ(ω) = Σ 1/(Q_k·ωc)` 的低频近似，@48 kHz / 1200 Hz / 8 阶约 33 样本）。
         * 若解码时不做补偿，每行的前几个像素会采到上一段（同步脉冲或 porch）的
         * 频率，表现为行首/行尾的亮暗撕边。
         *
         * @return 群延迟的样本数估计（可为小数）
         */
        fun estimateGroupDelaySamples(
            sampleRate: Int,
            cutoffHz: Double = DEFAULT_CUTOFF_HZ,
            order: Int = FILTER_ORDER,
        ): Double {
            val omegaC = TWO_PI * cutoffHz
            var sumInverseQ = 0.0
            for (k in 0 until order / 2) {
                val q = 1.0 / (2.0 * kotlin.math.cos((2 * k + 1) * PI / (2.0 * order)))
                sumInverseQ += 1.0 / q
            }
            return sumInverseQ / omegaC * sampleRate
        }

        /** 噪声统计窗口（样本）。约 170 ms @48 kHz，足以覆盖多段纯音。 */
        const val NOISE_WINDOW = 8192

        /** 数据不足时的噪声占位值（远大于任何真实噪声）。 */
        private const val NOISE_UNKNOWN_HZ = 1.0e9

        /** 零均值高斯分布下 `median(|X|) / σ` 的比值。 */
        private const val GAUSSIAN_MEDIAN_FACTOR = 0.6745

        /**
         * 二阶差分对噪声标准差的放大倍数。
         *
         * `Δ²φ = (φ[n]−φ[n−1]) − (φ[n−1]−φ[n−2])` 是三个独立同分布噪声项的线性组合
         * `φ[n] − 2φ[n−1] + φ[n−2]`，方差为 `(1+4+1)σφ² = 6σφ²`，故 σ(Δ²φ) = √6·σφ。
         * 这里用 √6 ≈ 2.449 —— 而不是朴素的 2 —— 才是正确的归一化。
         */
        private val SECOND_DIFFERENCE_GAIN = kotlin.math.sqrt(6.0)

        /** SSTV 的最大频偏 `(2300 − 1500) / 2`，作为等效信噪比的信号参考。 */
        private const val DEVIATION_HZ = 400.0

        /** 等效信噪比的下界（0 dB）。 */
        const val QUALITY_MIN_DB = 0.0

        /**
         * 等效信噪比的上界（90 dB）。
         *
         * 刻意留得比实测干净信号（约 55–65 dB，受 2f₀ 镜像抑制限制）高不少：
         * 若把上界压到 60，干净信号与 SNR 30 dB 的信号会一起被夹到顶，
         * 指标在最不需要它分辨的区间反而丢掉了全部分辨率。
         */
        const val QUALITY_MAX_DB = 90.0

        /**
         * 分档下界（dB）：`[强, 正常, 弱]`，低于最后一档即「极弱」。
         *
         * **标定方式**：用 `SstvCodecTest`/`SstvPhase2Test` 的合成信号（编码后的 VIS 头
         * + 均匀分布噪声）实测，本指标随噪声的变化约为「标称 SNR 每降 10 dB → 指标降约
         * 8 dB」：
         * ```
         * 干净 ≈ 68 dB   SNR 40 dB ≈ 69 dB   SNR 30 dB ≈ 64 dB
         * SNR 20 dB ≈ 55 dB                  SNR 10 dB ≈ 45 dB
         * ```
         * 40 dB 以上本指标已不再由噪声决定（而是由 8 阶低通对 2f₀ 镜像的抑制深度决定），
         * 因此「干净」与「SNR 40 dB」的先后没有物理意义，无需区分。
         *
         * 据此把「弱」的边界放在 48 dB（对应标称 SNR 10–20 dB，正是自适应积分窗开始
         * 划算的区间），「极弱」放在 42 dB。实测分档结果为
         * `干净/40/30/20/10 dB → 0/0/0/0/2`，积分窗 `5/5/5/5/8` 样本 ——
         * **强信号完全沿用一期的 5 样本窗**，不存在「开了反而变差」的风险。
         */
        private val QUALITY_TIER_BOUNDS_DB = doubleArrayOf(52.0, 48.0, 42.0)

        /** 第 [tier] 档的下界（dB）；越界时夹紧到有效范围。 */
        fun tierLowerBound(tier: Int): Double =
            QUALITY_TIER_BOUNDS_DB[tier.coerceIn(0, QUALITY_TIER_COUNT - 2)]

        /** 分档数。 */
        const val QUALITY_TIER_COUNT = 4

        /** 各档的积分窗放大系数（档 0 最好）。弱信号档取 1.6：噪声按 1/span 下降， */
        /* 5 → 8 样本即约 37% 的 RMS 改善，且仍在 1.5 像素的窗口上限之内。 */
        private val SPAN_TIER_FACTOR = doubleArrayOf(1.0, 1.0, 1.6, 2.0)

        /** 积分窗允许跨越的像素数上限。 */
        private const val MAX_SPAN_PIXELS = 1.5

        /** 积分窗的绝对上限（样本），避免极端像素时长下失控。 */
        const val MAX_ADAPTIVE_SPAN = 32

        /** 档位切换的迟滞带（dB）。 */
        const val QUALITY_HYSTERESIS_DB = 1.0
    }
}

/**
 * 自适应积分窗控制器（含迟滞）。
 *
 * **为什么需要迟滞**：信号质量在阈值附近抖动时，若无迟滞，积分窗会逐帧来回切换，
 * 图像上表现为清晰度的整体跳动。这里要求「越过边界 ≥ [SstvDemodulator.QUALITY_HYSTERESIS_DB]」
 * 才真正切换档位（与 slowrx 的 `window_idx_for_snr_with_hysteresis` 同思路）。
 *
 * 相位差分会改变输出频率流的噪声特性，因此**一帧之内不应切换** —— 使用方式是
 * 「每帧开始时测一次质量、定一次窗，然后整帧冻结」。
 *
 * 纯 Kotlin，无 Android 依赖，可单测。
 */
class SstvSpanController(private val samplesPerPixel: Double) {

    private var tier: Int = NORMAL_TIER

    /** 当前积分窗（样本数）。 */
    var span: Int = SstvDemodulator.recommendAdaptiveSpan(samplesPerPixel, NORMAL_TIER)
        private set

    /**
     * 用新的质量估计更新积分窗。
     *
     * @return 当前的积分窗样本数（未切换时即原值）
     */
    fun update(qualityDb: Double): Int {
        val desired = SstvDemodulator.qualityTier(qualityDb)
        if (desired == tier) return span

        val shouldSwitch = if (desired > tier) {
            // 变差：必须明显低于「所要跨过的那个边界」
            qualityDb < SstvDemodulator.tierLowerBound(tier) - SstvDemodulator.QUALITY_HYSTERESIS_DB
        } else {
            // 变好：必须明显高于「所要跨过的那个边界」
            qualityDb > SstvDemodulator.tierLowerBound(desired) + SstvDemodulator.QUALITY_HYSTERESIS_DB
        }
        if (!shouldSwitch) return span

        tier = desired
        span = SstvDemodulator.recommendAdaptiveSpan(samplesPerPixel, desired)
        return span
    }

    private companion object {
        /** 初始按「正常」档，避免第一帧就跳到极端档位。 */
        const val NORMAL_TIER = 1
    }
}

/**
 * N 阶 Butterworth 低通，由 N/2 个二阶节级联而成。
 *
 * 第 k 个二阶节的品质因数为 `Q_k = 1 / (2·cos((2k+1)π / (2N)))`，与节的顺序无关。
 * 阶数越高，过渡带越陡 —— 这正是「放宽调制带宽」与「压制 2f₀ 镜像」能够兼得的
 * 原因（见 [SstvDemodulator] 的类注释）。
 */
private class ButterworthLowpass(order: Int, sampleRate: Int, cutoffHz: Double) {
    private val sections: List<BiquadSection> = (0 until order / 2).map { k ->
        val q = 1.0 / (2.0 * kotlin.math.cos((2 * k + 1) * PI / (2.0 * order)))
        BiquadSection(q, sampleRate, cutoffHz)
    }

    fun process(x: Double): Double {
        var value = x
        for (section in sections) value = section.process(value)
        return value
    }

    fun reset() {
        for (section in sections) section.reset()
    }
}

/**
 * 单个二阶 IIR 节（双线性变换设计的低通）。
 *
 * 差分方程：`y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] − a1·y[n-1] − a2·y[n-2]`
 */
private class BiquadSection(q: Double, sampleRate: Int, cutoffHz: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val k = tan(PI * cutoffHz / sampleRate)
        val k2 = k * k
        val norm = 1.0 / (1.0 + k / q + k2)
        b0 = k2 * norm
        b1 = 2.0 * b0
        b2 = b0
        a1 = 2.0 * (k2 - 1.0) * norm
        a2 = (1.0 - k / q + k2) * norm
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}
