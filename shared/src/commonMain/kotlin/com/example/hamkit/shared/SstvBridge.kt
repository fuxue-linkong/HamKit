package com.example.hamkit.shared

import com.example.hamkit.data.sstv.SstvDecoder
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.data.sstv.SstvVisDetector

/**
 * SSTV 的 Swift 友好入口。
 *
 * 协议核心（模式表、VIS、同步、解调、解码、编码）来自 Android `data/sstv`
 * 移植到 commonMain 的纯 Kotlin 实现；iOS 负责录音/播放与 UIImage 转换。
 */
data class SstvModeInfo(
    val visCode: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val frameSeconds: Double,
)

data class SstvDecodeOutcome(
    val success: Boolean,
    val error: String,
    val modeName: String,
    val width: Int,
    val height: Int,
    /** 行优先 0xRRGGBB */
    val pixels: IntArray,
    val hedrShiftHz: Double,
    val slantRatio: Double,
    val decodedLines: Int,
)

object SstvBridge {

    /** 解码默认采样率（与 SstvVisDetector 一致）。 */
    val sampleRate: Int get() = SstvVisDetector.DEFAULT_SAMPLE_RATE

    /** 可解码的模式列表。 */
    fun modes(): List<SstvModeInfo> =
        SstvMode.entries
            .filter { it.decodable }
            .map {
                SstvModeInfo(
                    visCode = it.visCode,
                    name = it.displayName,
                    width = it.linePixels,
                    height = it.imageLines,
                    frameSeconds = it.frameSeconds,
                )
            }

    /**
     * 把一张图片编码为 SSTV 音频（PCM16 小端，内核采样率）。
     *
     * @param pixels 行优先 0xRRGGBB，长度须为 width×height
     * @param forcedVis 目标模式 VIS（不能为 0）
     * @param freqOffsetHz 频率偏移（模拟失谐/多普勒）
     * @return 编码成功返回 PCM 字节；参数非法或模式不可编码时返回空数组
     */
    fun encodePcm(pixels: IntArray, width: Int, height: Int, forcedVis: Int, freqOffsetHz: Double): ByteArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return ByteArray(0)
        val mode = SstvMode.fromVisCode(forcedVis) ?: return ByteArray(0)
        if (!mode.decodable) return ByteArray(0)
        val encoder = com.example.hamkit.data.sstv.SstvEncoder(SstvVisDetector.DEFAULT_SAMPLE_RATE)
        val source = com.example.hamkit.data.sstv.SstvPixelImageSource(width, height, pixels)
        val audio = encoder.encode(source, mode, freqOffsetHz, true)
        val bytes = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val v = (audio[i] * 32767.0f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * 解码一段单声道 PCM（16-bit 小端）。
     *
     * @param forcedVis 强制模式 VIS；0 表示自动识别
     */
    fun decodePcm(bytes: ByteArray, forcedVis: Int): SstvDecodeOutcome {
        if (bytes.size < 4) return failure("音频过短")
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            val v = ((hi shl 8) or lo).toShort().toInt()
            samples[i] = v / 32768.0f
        }
        val forced = if (forcedVis == 0) null else SstvMode.fromVisCode(forcedVis)
        val outcome = SstvDecoder(sampleRate).decode(samples, forced)
        return when (outcome) {
            is SstvDecoder.Outcome.Failure -> failure(outcome.reason)
            is SstvDecoder.Outcome.Success -> {
                val r = outcome.result
                SstvDecodeOutcome(
                    success = true,
                    error = "",
                    modeName = r.mode.displayName,
                    width = r.width,
                    height = r.height,
                    pixels = r.pixels,
                    hedrShiftHz = r.hedrShiftHz,
                    slantRatio = r.slantRatio,
                    decodedLines = r.decodedLines,
                )
            }
        }
    }

    private fun failure(reason: String) = SstvDecodeOutcome(
        success = false,
        error = reason,
        modeName = "",
        width = 0,
        height = 0,
        pixels = IntArray(0),
        hedrShiftHz = 0.0,
        slantRatio = 0.0,
        decodedLines = 0,
    )
}
