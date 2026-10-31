package com.example.hamkit.shared

import com.example.hamkit.data.ft8.Ft8Decoder
import com.example.hamkit.data.ft8.Ft8Encoder
import com.example.hamkit.data.ft8.Ft8Protocol

/**
 * FT8 编解码的 Swift 友好入口。
 *
 * 协议/LDPC/GFSK 实现全部来自从 Android 移植的
 * `com.example.hamkit.data.ft8`（Ft8Protocol / Ft8Ldpc / Ft8Encoder / Ft8Decoder）。
 * 音频采集与播放由 iOS 原生 AVAudioEngine 完成：
 * - 发射：本对象产出 12kHz / 16-bit PCM 字节流；
 * - 接收：Swift 录 12kHz PCM16，转成 ByteArray 传入解码。
 */
data class Ft8DecodedMessage(
    val message: String,
    val snr: Int,
    /** 时间偏移（秒） */
    val dtSeconds: Double,
    /** 音频频率（Hz） */
    val freqHz: Double,
    val rawHex: String,
)

object Ft8Bridge {

    const val SAMPLE_RATE = 12000
    const val SLOT_SECONDS = 15.0

    /** 编码为 15 秒、12kHz、PCM16 小端字节流；消息无法打包时返回空数组。 */
    fun encodeToPcm(message: String, callsign: String, grid: String): ByteArray {
        val pcm = Ft8Encoder.encodeToPcm(message, callsign, grid)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** 解码一段 12kHz PCM16 小端字节流；失败返回 null。 */
    fun decodePcm(bytes: ByteArray): Ft8DecodedMessage? {
        if (bytes.size < 4) return null
        val samples = ShortArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        val result = Ft8Decoder.decode(samples)
        if (!result.success) return null
        return Ft8DecodedMessage(
            message = result.messageText,
            snr = result.snr.toInt(),
            dtSeconds = result.dt.toDouble(),
            freqHz = result.freqHz.toDouble(),
            rawHex = result.raw77Hex,
        )
    }

    /** 消息文本是否能打包成标准 77-bit FT8 消息。 */
    fun canPack(message: String): Boolean = Ft8Protocol.packMessage(message) != null
}
