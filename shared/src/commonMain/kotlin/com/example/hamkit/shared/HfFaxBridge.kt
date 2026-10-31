package com.example.hamkit.shared

import com.example.hamkit.data.sstv.HfFaxDecoder
import com.example.hamkit.data.sstv.HfFaxSpec
import com.example.hamkit.data.sstv.SstvDemodulator
import com.example.hamkit.data.sstv.SstvVisDetector

/**
 * HF Fax（WEFAX）解码的 Swift 友好入口。
 *
 * 解码器从 Android `HfFaxDecoder.kt` 移植；本对象负责「PCM → 瞬时频率 → 灰度图」，
 * iOS 只负责录音与 UIImage 转换。
 */
data class HfFaxDecodeOutcome(
    val success: Boolean,
    val error: String,
    val width: Int,
    val height: Int,
    /** 行优先 0xRRGGBB */
    val pixels: IntArray,
    val aligned: Boolean,
    val shiftPixels: Int,
)

object HfFaxBridge {

    val sampleRate: Int get() = SstvVisDetector.DEFAULT_SAMPLE_RATE

    /** 每秒线数（120 行/分） */
    val linesPerMinute: Double get() = HfFaxSpec.LINES_PER_MINUTE

    /**
     * 解码一段 PCM16 小端音频。
     *
     * @param startImmediately 跳过 300Hz 启动音检测，直接把第一个样本当作图像起点
     *        （用户手动掐准录音时使用）
     */
    fun decodePcm(bytes: ByteArray, startImmediately: Boolean): HfFaxDecodeOutcome {
        if (bytes.size < 4) return failure("音频过短")
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            val v = ((hi shl 8) or lo).toShort().toInt()
            samples[i] = v / 32768.0f
        }

        val pixelSeconds = HfFaxSpec.LINE_SECONDS / HfFaxSpec.WIDTH
        val samplesPerPixel = pixelSeconds * sampleRate
        val demodulator = SstvDemodulator(
            sampleRate = sampleRate,
            phaseSpan = SstvDemodulator.recommendPhaseSpan(samplesPerPixel),
        )
        val freqs = demodulator.demodulate(samples)
        val decoder = HfFaxDecoder(sampleRate)
        if (startImmediately) decoder.startImmediately()
        decoder.feed(freqs, demodulator.transientSamples)
        val image = decoder.snapshot()
        if (image.height <= 0) return failure("没有解出有效行（检查录音是否包含 WEFAX 信号）")
        return HfFaxDecodeOutcome(
            success = true,
            error = "",
            width = image.width,
            height = image.height,
            pixels = image.toRgbPixels(),
            aligned = image.aligned,
            shiftPixels = image.shiftPixels,
        )
    }

    private fun failure(reason: String) = HfFaxDecodeOutcome(
        success = false,
        error = reason,
        width = 0,
        height = 0,
        pixels = IntArray(0),
        aligned = false,
        shiftPixels = 0,
    )
}
