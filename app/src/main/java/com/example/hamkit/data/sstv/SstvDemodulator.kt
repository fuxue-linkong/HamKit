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

    private val phaseStep = TWO_PI * centerHz / sampleRate

    /** 滤波器与差分窗的瞬态样本数，此区间内的输出不可信。 */
    val transientSamples: Int get() = TRANSIENT_SAMPLES + phaseSpan

    /** 清空滤波器与鉴相状态（每帧解码前调用）。 */
    fun reset() {
        inPhaseFilter.reset()
        quadratureFilter.reset()
        localPhase = 0.0
        phiRing.fill(0.0)
        phiWrite = 0
        phiFilled = 0
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

            // 4 阶低通：抑制 2f₀ 镜像，否则中心频率附近相位会剧烈抖动
            val inPhase = inPhaseFilter.process(x * cosP)
            val quad = quadratureFilter.process(-x * sinP)

            // 鉴相 → 瞬时频率（间隔 phaseSpan 的后向差分，噪声按 1/span 下降）
            val phi = atan2(quad, inPhase)
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
