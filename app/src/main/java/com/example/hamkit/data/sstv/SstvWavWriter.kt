package com.example.hamkit.data.sstv

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * WAV（RIFF/WAVE）写出器：把 [SstvEncoder] 生成的 PCM 存成标准 16 位单声道 WAV。
 *
 * **为什么需要「导出音频文件」这条路径**：手机扬声器 → 电台话筒的声学链路失真
 * （AGC、频响、环境噪声）是发射质量的瓶颈，App 侧无法控制。而把 WAV 导出后
 * 用音频线（或电台的 LINE IN / 数据口）接入，就能绕开声学环节，得到干净得多的
 * 发射信号。因此发射页同时提供「对着话筒播放」与「导出 WAV」两条出路。
 *
 * **流式**：最长模式 PD-290 一帧 13.9 M 样本，16 位即 27.8 MB。这里全程按块写出，
 * 并在最后回填头部长度字段，因此内存占用与帧长无关。
 *
 * 纯 Kotlin（`java.io` / `java.nio`），可在 JVM 单测中验证。
 */
object SstvWavWriter {

    /** 单样本位深：16 位足以承载 SSTV 约 8 位的有效动态范围。 */
    const val BITS_PER_SAMPLE = 16

    /** 标准 PCM WAV 头部长度。 */
    const val HEADER_BYTES = 44

    /**
     * 把 [chunks] 依次写成 16 位单声道 WAV，返回写入的样本总数。
     *
     * 头部先写占位值，全部数据落盘后通过 [RandomAccessFile.seek] 回填真实长度 ——
     * 这样无需预先知道样本数（[SstvEncoder.encodeChunks] 的实际长度由时间累加器
     * 决定，与估算值可能差几个样本）。
     *
     * @throws java.io.IOException 磁盘写入失败时抛出，由调用方转成用户提示
     */
    fun writePcm16(file: File, sampleRate: Int, chunks: Sequence<FloatArray>): Long =
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(header(0, sampleRate))
            var samples = 0L
            // 复用一个转换缓冲，避免每个块都新建 ShortArray
            var buffer = ShortArray(0)
            for (chunk in chunks) {
                if (buffer.size < chunk.size) buffer = ShortArray(chunk.size)
                for (i in chunk.indices) buffer[i] = toPcm16(chunk[i])
                out.write(shortsToBytesLE(buffer, chunk.size))
                samples += chunk.size
            }
            // 头部先写占位值、最后回填：编码器的实际样本数由时间累加器决定，
            // 与估算值可能差几个样本，因此不能提前写死长度
            out.seek(0)
            out.write(header(samples * (BITS_PER_SAMPLE / 8), sampleRate))
            samples
        }

    /**
     * 写到一个**不可回退**的输出流（例如 SAF 的 `openOutputStream` 结果）。
     *
     * WAV 头部需要长度字段，而长度只有全部样本产出后才确定，因此这里先在 [tempDir]
     * 落一个临时文件（可 seek，能回填头部），再整份拷进目标流并删除临时文件。
     * 代价是一次额外磁盘拷贝；收益是不必为了「能 seek」而要求调用方提供 `File`。
     *
     * @return 写入的样本总数
     */
    fun writePcm16ToStream(
        tempDir: File,
        sampleRate: Int,
        chunks: Sequence<FloatArray>,
        out: java.io.OutputStream,
    ): Long {
        val temp = File.createTempFile("sstv_tx", ".wav", tempDir)
        return try {
            val samples = writePcm16(temp, sampleRate, chunks)
            temp.inputStream().use { it.copyTo(out) }
            samples
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 整段样本 → WAV 字节（测试与小数据量场景）。 */
    fun toBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(HEADER_BYTES + samples.size * 2)
        out.write(header(samples.size.toLong() * 2, sampleRate))
        val pcm = ShortArray(samples.size) { toPcm16(samples[it]) }
        out.write(shortsToBytesLE(pcm, pcm.size))
        return out.toByteArray()
    }

    /**
     * 构造 44 字节的标准 PCM WAV 头。
     *
     * @param dataBytes `data` 块的有效字节数
     * @param sampleRate 采样率（Hz）
     */
    fun header(dataBytes: Long, sampleRate: Int): ByteArray {
        val out = ByteArray(HEADER_BYTES)
        writeTag(out, 0, "RIFF")
        writeIntLE(out, 4, (36 + dataBytes).toInt())
        writeTag(out, 8, "WAVE")
        writeTag(out, 12, "fmt ")
        writeIntLE(out, 16, 16)                       // fmt 块长度
        writeShortLE(out, 20, 1)                      // PCM
        writeShortLE(out, 22, 1)                      // 单声道
        writeIntLE(out, 24, sampleRate)
        writeIntLE(out, 28, sampleRate * BITS_PER_SAMPLE / 8) // byteRate
        writeShortLE(out, 32, BITS_PER_SAMPLE / 8)    // blockAlign
        writeShortLE(out, 34, BITS_PER_SAMPLE)
        writeTag(out, 36, "data")
        writeIntLE(out, 40, dataBytes.toInt())
        return out
    }

    /** 浮点样本（±1.0）→ 16 位有符号 PCM，带钳制。 */
    fun toPcm16(value: Float): Short {
        val scaled = (value * PCM16_MAX).roundToInt()
        return scaled.coerceIn(PCM16_MIN, PCM16_MAX).toShort()
    }

    private fun shortsToBytesLE(shorts: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = shorts[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun writeTag(out: ByteArray, offset: Int, value: String) {
        for (i in value.indices) out[offset + i] = value[i].code.toByte()
    }

    private fun writeIntLE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        out[offset + 2] = ((value shr 16) and 0xFF).toByte()
        out[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private const val PCM16_MAX = 32767
    private const val PCM16_MIN = -32768
}
