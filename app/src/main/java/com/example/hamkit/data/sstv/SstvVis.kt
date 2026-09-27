package com.example.hamkit.data.sstv

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln

/**
 * SSTV VIS 头检测器：短时频谱峰值跟踪 + 滑窗模板匹配。
 *
 * 算法逐项对照 slowrx 的 `vis.c` / slowrx.rs 的 `vis.rs` 实现：
 *  - **每 10 ms 步长**取一个 **20 ms Hann 窗**，零填充到 [FFT_LEN] 点做 FFT；
 *  - 在 500–3300 Hz 内找峰值 bin，用**高斯-对数插值**细化到亚 bin 精度；
 *  - 维护 **[HISTORY_LEN] 项（450 ms）**频率环形历史；
 *  - 每个新 hop 后用 45 项模板匹配 VIS（9 种对齐 = 3 相位 × 3 leader 候选）。
 *
 * 模板覆盖的是 VIS 头的**最后 450 ms**：
 * ```
 * hop 索引:  0   3   6   9  12 | 15 | 18+3k (k=0..6) | 39 | 42
 * 信号段:    ← Leader 2（150 ms）→|起始位|  7 个数据位   |校验|停止
 * ```
 * 因此**无需**检测 1900→1200 的跳变，第一个 leader 与 10 ms break 不参与判定。
 *
 * **失谐补偿**：所有频率判定都以**实测 leader 频率**为基准做相对比较（±25 Hz），
 * 并输出 `hedrShiftHz = 实测 leader − 1900`，供像素解调级校正黑白基准。
 *
 * 本类为纯 Kotlin 实现，不依赖任何 Android API，可直接在 JVM 单元测试中验证。
 *
 * @param sampleRate 采样率（Hz），默认 [DEFAULT_SAMPLE_RATE]
 */
class SstvVisDetector(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {

    /** VIS 检测结果。 */
    data class Result(
        /** 7 位 VIS 码（0x00–0x7F）。 */
        val visCode: Int,
        /** 对应的模式；为 null 表示该码未录入模式表（未知或未实现）。 */
        val mode: SstvMode?,
        /** 实测 leader 频率与标称 1900 Hz 的偏差（Hz）。 */
        val hedrShiftHz: Double,
        /** 停止位结束处的绝对样本索引（相对本检测器收到的全部音频）。 */
        val stopEndSample: Long,
    )

    /** 模板匹配的内部结果。 */
    private data class Match(val code: Int, val hedrShiftHz: Double, val phase: Int)

    private val hopSamples = sampleRate * HOP_MS / 1000
    private val windowSamples = sampleRate * WINDOW_MS / 1000
    private val fft = SstvFft(FFT_LEN)

    private val hann = FloatArray(windowSamples) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (windowSamples - 1))).toFloat()
    }

    private val fftRe = FloatArray(FFT_LEN)
    private val fftIm = FloatArray(FFT_LEN)

    // ── 流式音频缓冲：buffer[0] 对应绝对样本 originSample ──
    private var buffer = FloatArray(1 shl 16)
    private var bufferCount = 0
    private var originSample = 0L

    // ── 频率历史与 hop 计数 ──
    private val history = DoubleArray(HISTORY_LEN)
    private var historyPtr = 0
    private var historyFilled = 0
    private var hopsDone = 0L

    private var detected: Result? = null

    /**
     * 送入一段音频（单声道浮点，范围约 ±1.0），返回本次检出结果；未检出返回 null。
     *
     * 一旦检出成功，后续调用将直接返回同一结果，直至 [reset] —— 与 slowrx
     * `vis.c` 的 `if (gotvis) break;` 语义一致。
     */
    fun process(samples: FloatArray): Result? {
        detected?.let { return it }

        ensureCapacity(bufferCount + samples.size)
        System.arraycopy(samples, 0, buffer, bufferCount, samples.size)
        bufferCount += samples.size

        while (true) {
            val windowStartAbs = hopsDone * hopSamples
            val offset = (windowStartAbs - originSample).toInt()
            if (offset < 0) break // 理论不可达：origin 只会前进到已消费位置
            if (offset + windowSamples > bufferCount) break

            processHop(offset)
            hopsDone++

            if (historyFilled >= HISTORY_LEN) {
                val match = matchTemplate()
                if (match != null) {
                    // 停止位窗口 = 模板索引 42+phase，结束位置见 slowrx.rs 的推导
                    val stopEndAbs = (hopsDone + match.phase) * hopSamples
                    val result = Result(
                        visCode = match.code,
                        mode = SstvMode.fromVisCode(match.code),
                        hedrShiftHz = match.hedrShiftHz,
                        stopEndSample = stopEndAbs,
                    )
                    detected = result
                    return result
                }
            }

            // 丢弃不会再有窗口覆盖的样本（下一窗口从 hopsDone*hopSamples 开始）
            val keepFromAbs = hopsDone * hopSamples
            val drop = (keepFromAbs - originSample).toInt()
            if (drop > 0) {
                System.arraycopy(buffer, drop, buffer, 0, bufferCount - drop)
                bufferCount -= drop
                originSample = keepFromAbs
            }
        }
        return null
    }

    /** 重置检测器状态，准备检测下一帧的 VIS 头。 */
    fun reset() {
        bufferCount = 0
        originSample = 0L
        history.fill(0.0)
        historyPtr = 0
        historyFilled = 0
        hopsDone = 0L
        detected = null
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var newSize = buffer.size
        while (newSize < required) newSize = newSize shl 1
        buffer = buffer.copyOf(newSize)
    }

    /** 对窗口起点在 buffer 中偏移 [offset] 的 20 ms 窗做 FFT，并把峰值频率推入历史。 */
    private fun processHop(offset: Int) {
        for (i in 0 until windowSamples) {
            fftRe[i] = buffer[offset + i] * hann[i]
            fftIm[i] = 0f
        }
        for (i in windowSamples until FFT_LEN) {
            fftRe[i] = 0f
            fftIm[i] = 0f
        }
        fft.forward(fftRe, fftIm)

        val peakHz = estimatePeakFrequency()
        // 峰值越界或不单调时沿用上一 hop 的值，避免野值污染历史（slowrx vis.c:67）
        val prevIdx = (historyPtr + HISTORY_LEN - 1) % HISTORY_LEN
        history[historyPtr] = if (peakHz.isFinite()) peakHz else history[prevIdx]
        historyPtr = (historyPtr + 1) % HISTORY_LEN
        if (historyFilled < HISTORY_LEN) historyFilled++
    }

    /**
     * 在 500–3300 Hz 内寻找峰值频率，并用高斯-对数插值细化：
     * ```
     * bin = k_max + ln(P[k+1]/P[k−1]) / (2·ln(P[k]² / (P[k+1]·P[k−1])))
     * ```
     * 峰值落在搜索边界或邻 bin 非正时返回 NaN（调用方沿用上一 hop 的值）。
     */
    private fun estimatePeakFrequency(): Double {
        val binHz = sampleRate.toDouble() / FFT_LEN
        val lo = ceil(SEARCH_LO_HZ / binHz).toInt().coerceAtLeast(1)
        val hi = floor(SEARCH_HI_HZ / binHz).toInt().coerceAtMost(FFT_LEN / 2 - 2)
        if (lo >= hi) return Double.NaN

        var maxBin = lo
        var maxPower = powerAt(lo)
        for (k in lo + 1..hi) {
            val p = powerAt(k)
            if (p > maxPower) {
                maxPower = p
                maxBin = k
            }
        }
        if (maxBin <= lo || maxBin >= hi) return Double.NaN

        val pPrev = powerAt(maxBin - 1)
        val pNext = powerAt(maxBin + 1)
        if (pPrev <= 0.0 || maxPower <= 0.0 || pNext <= 0.0) return Double.NaN

        val denominator = 2.0 * ln(maxPower * maxPower / (pNext * pPrev))
        val refinedBin = if (abs(denominator) > 1e-12) {
            maxBin + ln(pNext / pPrev) / denominator
        } else {
            maxBin.toDouble()
        }
        return refinedBin * binHz
    }

    private fun powerAt(bin: Int): Double {
        val re = fftRe[bin].toDouble()
        val im = fftIm[bin].toDouble()
        return re * re + im * im
    }

    /**
     * 在 45 项频率历史上尝试 VIS 模板匹配（9 种对齐）。
     *
     * 返回第一个**已知模式**且校验通过的对齐；若只有未知码通过校验，
     * 则返回其中第一个（供上层提示「识别到未支持的模式」）。
     */
    private fun matchTemplate(): Match? {
        val tones = DoubleArray(HISTORY_LEN) { history[(historyPtr + it) % HISTORY_LEN] }
        var firstUnknown: Match? = null

        for (phase in 0..2) {
            for (leaderCandidate in 0..2) {
                val leader = tones[leaderCandidate]
                // leader 的 5 个检查点（跨 120 ms）
                if (!within(tones[3 + phase], leader)) continue
                if (!within(tones[6 + phase], leader)) continue
                if (!within(tones[9 + phase], leader)) continue
                if (!within(tones[12 + phase], leader)) continue

                // 起始位与停止位均为 1200 Hz（= leader − 700）
                val breakTarget = leader + BREAK_OFFSET_HZ
                if (!within(tones[15 + phase], breakTarget)) continue
                if (!within(tones[42 + phase], breakTarget)) continue

                val zeroTarget = leader + BIT_ZERO_OFFSET_HZ // 1300 Hz = 0
                val oneTarget = leader + BIT_ONE_OFFSET_HZ   // 1100 Hz = 1

                var code = 0
                var parity = 0
                var ok = true
                for (k in 0..7) {
                    val tone = tones[18 + phase + 3 * k]
                    val bit = when {
                        within(tone, zeroTarget) -> 0
                        within(tone, oneTarget) -> 1
                        else -> {
                            ok = false
                            break
                        }
                    }
                    if (k < 7) {
                        code = code or (bit shl k) // LSB 先传
                        parity = parity xor bit
                    } else {
                        // Robot 12 B/W（0x06）使用奇校验（slowrx vis.c:116）
                        if (code == R12BW_VIS_CODE) parity = parity xor 1
                        if (parity != bit) ok = false
                        break
                    }
                }
                if (!ok) continue

                val match = Match(code, leader - SstvMode.VIS_LEADER_HZ, phase)
                if (SstvMode.fromVisCode(code) != null) return match
                if (firstUnknown == null) firstUnknown = match
            }
        }
        return firstUnknown
    }

    private fun within(value: Double, target: Double): Boolean =
        abs(value - target) < TONE_TOLERANCE_HZ

    companion object {
        /** 默认工作采样率：SSTV 的时间分辨率要求见 docs/REQUIREMENT_SSTV.md §4.2。 */
        const val DEFAULT_SAMPLE_RATE = 48000

        /** 频谱更新步长（ms）。 */
        const val HOP_MS = 10

        /** 短时窗长（ms）。 */
        const val WINDOW_MS = 20

        /** FFT 长度（零填充后），@48 kHz 对应 ≈23.4 Hz/bin。 */
        const val FFT_LEN = 2048

        /** 频率历史长度：45 × 10 ms = 450 ms，覆盖 VIS 尾段模板。 */
        const val HISTORY_LEN = 45

        /** 音调匹配容差（Hz），相对实测 leader。 */
        const val TONE_TOLERANCE_HZ = 25.0

        /** 起始位/停止位相对 leader 的偏移（1200 − 1900）。 */
        const val BREAK_OFFSET_HZ = -700.0

        /** 数据位 0（1300 Hz）相对 leader 的偏移。 */
        const val BIT_ZERO_OFFSET_HZ = -600.0

        /** 数据位 1（1100 Hz）相对 leader 的偏移。 */
        const val BIT_ONE_OFFSET_HZ = -800.0

        private const val SEARCH_LO_HZ = 500.0
        private const val SEARCH_HI_HZ = 3300.0

        /** Robot 12 B/W 的 VIS 码，其校验位为奇校验。 */
        private const val R12BW_VIS_CODE = 0x06
    }
}
