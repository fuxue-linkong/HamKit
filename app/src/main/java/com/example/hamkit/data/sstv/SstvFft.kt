package com.example.hamkit.data.sstv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 定长基 2 快速傅里叶变换（迭代式 Cooley-Tukey，就地运算）。
 *
 * 自研实现，不依赖第三方 DSP 库（与项目既有的自研 FT8/SGP4 路线一致）。
 * 旋转因子与位反转表在构造时预计算，避免每次变换重复计算三角函数。
 *
 * 用途：
 *  - [SstvVisDetector] 的短时频谱峰值跟踪（每 10 ms 一次 2048 点 FFT）
 *  - 备选的 FFT-Hilbert 鉴频方案
 *
 * @param size 变换长度，必须是 2 的幂
 */
internal class SstvFft(val size: Int) {

    init {
        require(size >= 2 && (size and (size - 1)) == 0) {
            "FFT 长度必须是 2 的幂，实际为 $size"
        }
    }

    /** log2(size)，蝶形运算的级数。 */
    private val levels: Int = Integer.numberOfTrailingZeros(size)

    /** 旋转因子实部 cos(2πk/N)，k ∈ [0, N/2)。 */
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }

    /** 旋转因子虚部 sin(2πk/N)。 */
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }

    /** 位反转置换表，用于把输入重排成蝶形运算所需的顺序。 */
    private val reverseTable = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    /**
     * 就地正向变换（符号约定为 e^(-i2πkn/N)）。
     *
     * @param re 实部数组，长度必须 ≥ [size]
     * @param im 虚部数组，长度必须 ≥ [size]
     */
    fun forward(re: FloatArray, im: FloatArray) {
        // 1. 位反转置换
        for (i in 0 until size) {
            val j = reverseTable[i]
            if (j > i) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // 2. 逐级蝶形运算
        var blockSize = 2
        while (blockSize <= size) {
            val halfBlock = blockSize / 2
            val tableStep = size / blockSize
            var blockStart = 0
            while (blockStart < size) {
                var j = blockStart
                var k = 0
                while (j < blockStart + halfBlock) {
                    val l = j + halfBlock
                    val tr = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val ti = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    j++
                    k += tableStep
                }
                blockStart += blockSize
            }
            blockSize = blockSize shl 1
        }
    }

    companion object {
        /** 返回 ≥ [value] 的最小 2 的幂（至少 2）。 */
        fun nextPowerOfTwo(value: Int): Int {
            var n = 2
            while (n < value) n = n shl 1
            return n
        }
    }
}
