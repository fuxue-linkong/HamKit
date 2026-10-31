package com.example.hamkit.data.ft8

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FT8 编码器：消息打包 → CRC-14 → LDPC(174,91) → Gray 码映射 → GFSK 调制。
 *
 * 严格遵循 FT4/FT8 协议标准（ft8_lib / WSJT-X 生成器）。
 * 修复了原实现中的多处不兼容问题：
 *  - 原 (174,87) 自创 LDPC 码 → 标准 (174,91) LDPC 码
 *  - 原 CRC 算法 → 标准 CRC-14（在 77 位消息上补 5 位零扩展后计算）
 *  - 原网格/呼号哈希/自由文本编码 → 标准算法
 *  - 原直接 FSK 正弦 → 标准 GFSK（高斯滤波频移键控，BT=2.0）
 */
object Ft8Encoder {

    const val SAMPLE_RATE = 12000
    const val SYMBOL_PERIOD_SAMPLES = 1920 // 12000/6.25
    const val TONE_SPACING_HZ = 6.25f
    const val BASE_FREQ_HZ = 1000f // FT8 常用基频（音频偏移）
    const val NUM_TONES = 79
    const val SYMBOL_PERIOD = 0.160f
    const val SLOT_TIME = 15.0f
    const val SYMBOL_BT = 2.0f

    private const val GFSK_CONST_K = 5.336446f // pi * sqrt(2 / ln 2)

    /**
     * 编码 FT8 消息为 PCM 音频样本（16-bit signed, 12000 sps）。
     *
     * @param messageText 消息文本，如 "CQ BH4XYZ OM44"、"BH4XYZ OM44 -05"
     * @param callsign 本台呼号（用于标准消息）
     * @param grid 本台网格
     * @return 15 秒的 PCM 音频
     */
    fun encodeToPcm(messageText: String, callsign: String, grid: String): ShortArray {
        // 1. 打包 77 位消息
        val payload77 = Ft8Protocol.packMessage(messageText)
            ?: return ShortArray(0)

        // 2. 加 CRC 得到 91 位
        val a91 = Ft8Protocol.addCrc(payload77)

        // 3. LDPC(174,91) 编码
        val codeword = Ft8Ldpc.encode174(a91)

        // 4. 映射到 79 个传输音调
        val tones = tonesFromCodeword(codeword)

        // 5. GFSK 调制
        val nSpsym = (0.5f + SAMPLE_RATE * SYMBOL_PERIOD).toInt()
        val numSamples = (0.5f + NUM_TONES * SYMBOL_PERIOD * SAMPLE_RATE).toInt()
        val numSilence = ((SLOT_TIME * SAMPLE_RATE - numSamples) / 2).toInt()
        val total = numSilence + numSamples + numSilence

        val floatSignal = FloatArray(total)
        synthGfsk(tones, BASE_FREQ_HZ, SAMPLE_RATE, floatSignal, numSilence)

        // 转 16-bit PCM
        val pcm = ShortArray(total)
        for (i in floatSignal.indices) {
            val s = (floatSignal[i] * 32000.0)
            pcm[i] = s.toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    /**
     * 由 174 位码字生成 79 个传输音调（含 Costas 同步阵列与 Gray 码映射）。
     * 帧结构：S7 D29 S7 D29 S7
     */
    fun tonesFromCodeword(codeword: UByteArray): IntArray {
        val tones = IntArray(NUM_TONES)
        var mask = 0x80
        var iByte = 0

        for (iTone in 0 until NUM_TONES) {
            when (iTone) {
                in 0..6 -> tones[iTone] = Ft8Ldpc.COSTAS[iTone]
                in 36..42 -> tones[iTone] = Ft8Ldpc.COSTAS[iTone - 36]
                in 72..78 -> tones[iTone] = Ft8Ldpc.COSTAS[iTone - 72]
                else -> {
                    // 依次取 3 位，MSB first
                    var bits3 = 0
                    if ((codeword[iByte].toInt() and mask) != 0) bits3 = bits3 or 4
                    mask = mask ushr 1
                    if (mask == 0) { mask = 0x80; iByte++ }
                    if ((codeword[iByte].toInt() and mask) != 0) bits3 = bits3 or 2
                    mask = mask ushr 1
                    if (mask == 0) { mask = 0x80; iByte++ }
                    if ((codeword[iByte].toInt() and mask) != 0) bits3 = bits3 or 1
                    mask = mask ushr 1
                    if (mask == 0) { mask = 0x80; iByte++ }
                    tones[iTone] = Ft8Ldpc.GRAY_MAP[bits3]
                }
            }
        }
        return tones
    }

    /**
     * 生成 GFSK 平滑脉冲（高斯脉冲，截断在 3 个符号长度）。
     */
    private fun gfskPulse(nSpsym: Int, symbolBt: Float): FloatArray {
        val pulse = FloatArray(3 * nSpsym)
        for (i in 0 until 3 * nSpsym) {
            val t = i.toFloat() / nSpsym - 1.5f
            val arg1 = GFSK_CONST_K * symbolBt * (t + 0.5f)
            val arg2 = GFSK_CONST_K * symbolBt * (t - 0.5f)
            pulse[i] = (erff(arg1) - erff(arg2)) / 2.0f
        }
        return pulse
    }

    /**
     * GFSK 波形合成（与 ft8_lib synth_gfsk 一致）。
     *
     * @param symbols 音调序列（0-7）
     * @param f0 基频
     * @param signalRate 采样率
     * @param signal 输出信号（需包含 offset 后的位置）
     * @param offset 写入信号的起始偏移
     */
    fun synthGfsk(symbols: IntArray, f0: Float, signalRate: Int, signal: FloatArray, offset: Int) {
        val nSym = symbols.size
        val nSpsym = (0.5f + signalRate * SYMBOL_PERIOD).toInt()
        val nWave = nSym * nSpsym
        val hmod = 1.0f
        val dphiPeak = 2.0f * PI.toFloat() * hmod / nSpsym

        val dphi = FloatArray(nWave + 2 * nSpsym) { 2.0f * PI.toFloat() * f0 / signalRate }

        val pulse = gfskPulse(nSpsym, SYMBOL_BT)

        for (i in 0 until nSym) {
            val ib = i * nSpsym
            for (j in 0 until 3 * nSpsym) {
                dphi[j + ib] += dphiPeak * symbols[i] * pulse[j]
            }
        }

        // 首尾补虚拟符号
        for (j in 0 until 2 * nSpsym) {
            dphi[j] += dphiPeak * pulse[j + nSpsym] * symbols[0]
            dphi[j + nSym * nSpsym] += dphiPeak * pulse[j] * symbols[nSym - 1]
        }

        // 积分相位生成波形
        var phi = 0.0f
        for (k in 0 until nWave) {
            signal[offset + k] = sin(phi)
            phi = (phi + dphi[k + nSpsym]) % (2.0f * PI.toFloat())
        }

        // 首尾符号包络整形
        val nRamp = nSpsym / 8
        for (i in 0 until nRamp) {
            val env = (1.0f - cos(2.0f * PI.toFloat() * i / (2 * nRamp))) / 2.0f
            signal[offset + i] *= env
            signal[offset + nWave - 1 - i] *= env
        }
    }

    /** 误差函数（数值近似） */
    private fun erff(x0: Float): Float {
        val sign = if (x0 < 0) -1 else 1
        val x = kotlin.math.abs(x0)
        // Abramowitz-Stegun 近似
        val t = 1.0f / (1.0f + 0.3275911f * x)
        val y = 1.0f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t * kotlin.math.exp(-x * x)
        return sign * y
    }
}
