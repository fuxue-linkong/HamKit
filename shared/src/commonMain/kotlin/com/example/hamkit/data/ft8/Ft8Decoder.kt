package com.example.hamkit.data.ft8

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * FT8 解码器：音频 PCM → 频谱瀑布 → Costas 同步搜索 → 软对数似然提取 → LDPC BP 解码 → CRC → 消息解析。
 *
 * 完全按 FT4/FT8 协议标准（ft8_lib）实现。修复了原实现的关键缺陷：
 *  - 原实现只搜索整数频偏 -3..3，且用硬判决 argMax + 简易翻转解码，无法识别真实 FT8 信号；
 *  - 原实现用自创 (174,87) 码，无法与任何真实 FT8 软件互通；
 *  - 原实现缺失 Gray 码映射、软判决 BP、多候选、DT/频率上报。
 */
object Ft8Decoder {

    const val SYMBOL_SAMPLES = Ft8Encoder.SYMBOL_PERIOD_SAMPLES // 1920 @ 12kHz
    const val NUM_TONES = 8
    const val TONE_SPACING = Ft8Encoder.TONE_SPACING_HZ // 6.25
    const val LDPC_N = Ft8Ldpc.N

    // 同步搜索参数（与 ft8_lib demo 一致）
    private const val MIN_SYNC_SCORE = 8.0f
    private const val MAX_CANDIDATES = 40
    private const val LDPC_ITERATIONS = 25
    private const val TIME_OSR = 2
    private const val FREQ_OSR = 2
    private const val FREQ_MIN_HZ = 200f
    private const val FREQ_MAX_HZ = 3000f

    data class Candidate(
        val timeOffset: Int,  // 符号（半符号）偏移
        val timeSub: Int,
        val freqOffset: Int,  // 频率 bin 偏移（1 bin = 6.25/FREQ_OSR Hz）
        val freqSub: Int,
        val score: Float,
    )

    data class DecodeResult(
        val success: Boolean,
        val syncScore: Float,
        val freqOffset: Int,
        val messageText: String,
        val raw77Hex: String,
        val crcValid: Boolean,
        val snr: Float,
        val dt: Float,     // 时间偏移（秒）
        val freqHz: Float, // 音频频率（Hz）
    ) {
        companion object {
            fun failed(reason: String): DecodeResult =
                DecodeResult(false, 0f, 0, reason, "", false, 0f, 0f, 0f)
        }
    }

    /**
     * 解码一段 PCM（12000 Hz mono 16-bit）。
     */
    fun decode(pcm: ShortArray): DecodeResult {
        val numSamples = pcm.size
        val numSlots = numSamples / SYMBOL_SAMPLES
        if (numSlots < Ft8Ldpc.NUM_TONES) {
            return DecodeResult.failed("音频太短 (需至少 ${Ft8Ldpc.NUM_TONES} 个符号槽)")
        }

        val floatPcm = FloatArray(numSamples) { pcm[it] / 32768.0f }

        // 1. 构建频谱瀑布
        val power = computeWaterfall(floatPcm)

        // 2. 同步搜索（多候选）
        val candidates = findCandidates(power)

        if (candidates.isEmpty()) {
            return DecodeResult.failed("未检测到 FT8 同步信号")
        }

        // 3. 逐个候选解码
        val decoded = mutableListOf<DecodeResult>()
        for (cand in candidates) {
            val result = decodeCandidate(power, cand)
            if (result != null) decoded.add(result)
        }

        return decoded.firstOrNull() ?: DecodeResult.failed("未解码出有效 FT8 消息（CRC 校验失败）")
    }

    /**
     * 计算频谱瀑布：mag[block][bin]。
     * block 为符号槽，bin 的频率间隔为 TONE_SPACING (6.25 Hz)；
     * 亚 bin 频率（3.125 Hz）通过 magAt/magAtSym 的线性插值实现。
     */
    private fun computeWaterfall(pcm: FloatArray): Array<FloatArray> {
        val numBlocks = pcm.size / SYMBOL_SAMPLES
        val numBins = ((FREQ_MAX_HZ - FREQ_MIN_HZ) / TONE_SPACING).toInt() + 1
        val mag = Array(numBlocks) { FloatArray(numBins) }

        for (block in 0 until numBlocks) {
            val offset = block * SYMBOL_SAMPLES
            for (bin in 0 until numBins) {
                val freq = FREQ_MIN_HZ + bin * TONE_SPACING
                mag[block][bin] = goertzelMag(pcm, offset, SYMBOL_SAMPLES, freq)
            }
        }
        return mag
    }

    /** Goertzel 算法计算单频幅度 */
    private fun goertzelMag(
        pcm: FloatArray,
        offset: Int,
        n: Int,
        freq: Float
    ): Float {
        val w = 2.0 * PI * freq / Ft8Encoder.SAMPLE_RATE
        val coeff = 2.0 * cos(w)
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (i in 0 until n) {
            val sample = pcm[offset + i].toDouble()
            val s = sample + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        val power = sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
        return if (power > 0) sqrt(power).toFloat() else 0f
    }

    /** 访问指定 block、bin 的幅度（支持候选的 freq_sub 细调） */
    private fun magAt(power: Array<FloatArray>, block: Int, bin: Float): Float {
        if (block < 0 || block >= power.size) return 0f
        val lo = bin.toInt()
        if (lo < 0 || lo >= power[block].size) return 0f
        val frac = bin - lo
        val hi = lo + 1
        val v0 = power[block][lo]
        val v1 = if (hi < power[block].size) power[block][hi] else v0
        return v0 * (1 - frac) + v1 * frac
    }

    /**
     * 同步搜索：扫描时间和频率偏移，用 Costas 阵列的邻域差分评分找候选。
     */
    private fun findCandidates(power: Array<FloatArray>): List<Candidate> {
        val numBlocks = power.size
        val binStep = TONE_SPACING / FREQ_OSR // 3.125 Hz
        val maxBin = ((FREQ_MAX_HZ - FREQ_MIN_HZ) / binStep).toInt() - 8

        val heap = ArrayList<Candidate>()

        for (timeSub in 0 until TIME_OSR) {
            for (freqSub in 0 until FREQ_OSR) {
                // 允许帧起点超出缓冲区（缺几个 Costas 符号也能评分）
                for (timeOffset in -10 until 20) {
                    for (freqOffset in 0..maxBin) {
                        val score = syncScore(power, timeOffset, timeSub, freqOffset, freqSub)
                        if (score < MIN_SYNC_SCORE) continue

                        val cand = Candidate(timeOffset, timeSub, freqOffset, freqSub, score)
                        if (heap.size < MAX_CANDIDATES) {
                            heap.add(cand)
                            heap.sortByDescending { it.score }
                        } else if (score > heap.last().score) {
                            heap[heap.size - 1] = cand
                            heap.sortByDescending { it.score }
                        }
                    }
                }
            }
        }

        // 去重：相同时间/频率附近的候选只保留分数最高的
        val dedup = ArrayList<Candidate>()
        for (c in heap) {
            val tooClose = dedup.any { d ->
                kotlin.math.abs(d.timeOffset * TIME_OSR + d.timeSub - (c.timeOffset * TIME_OSR + c.timeSub)) <= 1 &&
                    kotlin.math.abs(d.freqOffset * FREQ_OSR + d.freqSub - (c.freqOffset * FREQ_OSR + c.freqSub)) <= 1
            }
            if (!tooClose) dedup.add(c)
        }
        return dedup
    }

    /** Costas 阵列邻域差分同步评分（与 ft8_lib ft8_sync_score 一致） */
    private fun syncScore(
        power: Array<FloatArray>,
        timeOffset: Int,
        timeSub: Int,
        freqOffset: Int,
        freqSub: Int,
    ): Float {
        var score = 0f
        var numAverage = 0
        val binBase = freqOffset + freqSub.toFloat() / FREQ_OSR

        for (m in 0 until Ft8Ldpc.NUM_SYNC) {
            for (k in 0 until Ft8Ldpc.LENGTH_SYNC) {
                val block = Ft8Ldpc.SYNC_OFFSET * m + k
                val symAbs = (timeOffset + block) * TIME_OSR + timeSub
                val sm = Ft8Ldpc.COSTAS[k]
                val expectedBin = binBase + sm

                val vCenter = magAtSym(power, symAbs, expectedBin)
                if (vCenter <= 0f) continue

                // 频率邻域（仅检查相邻音调）
                if (sm > 0) {
                    score += vCenter - magAtSym(power, symAbs, expectedBin - 1)
                    numAverage++
                }
                if (sm < 7) {
                    score += vCenter - magAtSym(power, symAbs, expectedBin + 1)
                    numAverage++
                }
                // 时间邻域
                if ((k > 0) && (symAbs - TIME_OSR >= 0)) {
                    score += vCenter - magAtSym(power, symAbs - TIME_OSR, expectedBin)
                    numAverage++
                }
                if ((k + 1 < Ft8Ldpc.LENGTH_SYNC) && (symAbs + TIME_OSR < power.size * TIME_OSR)) {
                    score += vCenter - magAtSym(power, symAbs + TIME_OSR, expectedBin)
                    numAverage++
                }
            }
        }

        return if (numAverage > 0) score / numAverage else 0f
    }

    /** 以半符号为单位访问幅度 */
    private fun magAtSym(power: Array<FloatArray>, symAbs: Int, bin: Float): Float {
        // 半符号间做时间插值
        val block = symAbs / TIME_OSR
        val frac = (symAbs % TIME_OSR) / TIME_OSR.toFloat()
        val v0 = magAt(power, block, bin)
        val v1 = magAt(power, block + 1, bin)
        return v0 * (1 - frac) + v1 * frac
    }

    /**
     * 解码单个候选：提取软对数似然 → 归一化 → BP 解码 → CRC → 消息解析。
     */
    private fun decodeCandidate(power: Array<FloatArray>, cand: Candidate): DecodeResult? {
        // 提取 174 位软对数似然
        val log174 = FloatArray(LDPC_N)

        for (k in 0 until Ft8Ldpc.ND) {
            val symIdx = k + if (k < 29) 7 else 14
            val bitIdx = 3 * k
            val symAbs = (cand.timeOffset + symIdx) * TIME_OSR + cand.timeSub
            if (symAbs < 0 || symAbs / TIME_OSR >= power.size) {
                log174[bitIdx] = 0f
                log174[bitIdx + 1] = 0f
                log174[bitIdx + 2] = 0f
                continue
            }
            extractSymbolLlr(power, symAbs, cand, log174, bitIdx)
        }

        // 归一化
        var sum = 0f
        var sum2 = 0f
        for (i in 0 until LDPC_N) {
            sum += log174[i]
            sum2 += log174[i] * log174[i]
        }
        val invN = 1.0f / LDPC_N
        val variance = (sum2 - sum * sum * invN) * invN
        if (variance <= 0f) return null
        val normFactor = sqrt(24.0f / variance)
        for (i in 0 until LDPC_N) log174[i] *= normFactor

        // BP 解码
        val (errors, plain174) = Ft8Ldpc.bpDecode(log174, LDPC_ITERATIONS)
        if (errors > 0) return null

        // 提取 a91（前 91 位）
        val a91 = UByteArray(Ft8Ldpc.K_BYTES)
        for (i in 0 until Ft8Ldpc.K) {
            if (plain174[i]) {
                a91[i / 8] = (a91[i / 8].toInt() or (0x80 shr (i % 8))).toUByte()
            }
        }

        // CRC 校验
        val crcExtracted = Ft8Protocol.extractCrc(a91)
        val a91Crc = a91.copyOf()
        a91Crc[9] = (a91Crc[9].toInt() and 0xF8).toUByte()
        a91Crc[10] = 0.toUByte()
        val crcCalculated = Ft8Protocol.computeCrc(a91Crc, 96 - 14)
        if (crcExtracted != crcCalculated) return null

        // 解包消息（前 77 位）
        val payload = UByteArray(10)
        for (i in 0 until 10) payload[i] = a91[i]
        val text = Ft8Protocol.unpackMessage(payload)

        val snr = estimateSnr(power, cand)
        val dt = (cand.timeOffset + cand.timeSub.toFloat() / TIME_OSR) * Ft8Encoder.SYMBOL_PERIOD
        val freqHz = FREQ_MIN_HZ + (cand.freqOffset + cand.freqSub.toFloat() / FREQ_OSR) * TONE_SPACING

        return DecodeResult(
            success = true,
            syncScore = cand.score,
            freqOffset = cand.freqOffset,
            messageText = text,
            raw77Hex = payload.joinToString("") { it.toInt().toString(16).padStart(2, '0') },
            crcValid = true,
            snr = snr,
            dt = dt,
            freqHz = freqHz,
        )
    }

    /** 提取一个 8-FSK 符号的 3 位软对数似然（与 ft8_lib ft8_extract_symbol 一致） */
    private fun extractSymbolLlr(
        power: Array<FloatArray>,
        symAbs: Int,
        cand: Candidate,
        log174: FloatArray,
        bitIdx: Int,
    ) {
        val s2 = FloatArray(8)
        val binBase = cand.freqOffset + cand.freqSub.toFloat() / FREQ_OSR
        for (j in 0 until 8) {
            s2[j] = magAtSym(power, symAbs, binBase + Ft8Ldpc.GRAY_MAP[j])
        }

        log174[bitIdx + 0] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3])
        log174[bitIdx + 1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5])
        log174[bitIdx + 2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6])
    }

    private fun max2(a: Float, b: Float): Float = if (a >= b) a else b

    private fun max4(a: Float, b: Float, c: Float, d: Float): Float =
        max2(max2(a, b), max2(c, d))

    /** 估算信噪比（dB）：信号峰值功率 vs 邻近噪声功率 */
    private fun estimateSnr(power: Array<FloatArray>, cand: Candidate): Float {
        var signal = 0f
        var noise = 0f
        var sigCount = 0
        var noiseCount = 0
        val binBase = cand.freqOffset + cand.freqSub.toFloat() / FREQ_OSR

        for (k in 0 until Ft8Ldpc.ND) {
            val symIdx = k + if (k < 29) 7 else 14
            val symAbs = (cand.timeOffset + symIdx) * TIME_OSR + cand.timeSub
            if (symAbs < 0 || symAbs / TIME_OSR >= power.size) continue
            // 检测到最强音调
            var maxV = -1f
            var maxBin = 0f
            for (j in 0 until 8) {
                val v = magAtSym(power, symAbs, binBase + j)
                if (v > maxV) { maxV = v; maxBin = j.toFloat() }
            }
            signal += maxV * maxV
            sigCount++
            // 噪声：其余 7 个音调的平均功率
            var nSum = 0f
            var nCount = 0
            for (j in 0 until 8) {
                if (j.toFloat() != maxBin) {
                    val v = magAtSym(power, symAbs, binBase + j)
                    nSum += v * v
                    nCount++
                }
            }
            noise += nSum / nCount
            noiseCount++
        }
        if (sigCount == 0 || noiseCount == 0) return 0f
        val ratio = (signal / sigCount) / (noise / noiseCount)
        if (ratio <= 0f) return 0f
        return (10.0 * kotlin.math.log10(ratio.toDouble())).toFloat()
    }
}
