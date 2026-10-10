package com.example.hamkit.data.sstv

import java.io.File
import java.io.RandomAccessFile

/**
 * 流式 WAV 录音器：边收边把 PCM16 追加到文件，结束时回填头部长度。
 *
 * 与 [SstvWavWriter] 的分工：后者一次性写出一段已知的样本流（发射导出用），
 * 本类面向**长度事先未知**的实时录音（接收时录制原始音频）。两者的头部格式完全一致，
 * 因此录下来的文件可以直接喂回 [SstvWavReader] 重新解码 —— 这正是「录下过境 → 事后重解」
 * 这条闭环的基础，也顺手解决了工程上一直缺「真实录音回归素材」的问题。
 *
 * 写入是**追加式**的：只在 [close] 时 seek 回文件头补长度，因此录音期间的内存占用与
 * 时长无关（PD-120 一帧约 12 MB，全部落在磁盘上）。
 *
 * 纯 JVM（`java.io`），可在单测中验证。
 *
 * @param file 目标文件（父目录需已存在）
 * @param sampleRate 采样率（Hz）
 */
class SstvWavRecorder(
    private val file: File,
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
) {

    private var out: RandomAccessFile? = null

    /** 已写入的样本数。 */
    var sampleCount: Long = 0L
        private set

    /** 录音时长（秒）。 */
    val seconds: Double get() = sampleCount.toDouble() / sampleRate

    /** 是否已打开。 */
    val isOpen: Boolean get() = out != null

    /**
     * 打开文件并写入占位头部。
     *
     * @return 失败原因；成功返回 null
     */
    fun open(): String? {
        if (out != null) return null
        return runCatching {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            raf.write(SstvWavWriter.header(0, sampleRate))
            out = raf
            sampleCount = 0
            null
        }.getOrElse { e -> "无法创建录音文件：${e.message ?: "IO 错误"}" }
    }

    /**
     * 追加一批 16 位单声道样本。
     *
     * 带 [offset] 参数是为了让调用方可以复用一块大缓冲、按实际读取长度增量落盘，
     * 而不必为每块都复制一个子数组。
     *
     * @param offset 起始下标（越界会被夹紧）
     * @param count 样本数（越界会被夹紧到数组内实际存在的部分）
     */
    fun append(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset) {
        val raf = out ?: return
        val from = offset.coerceIn(0, samples.size)
        val n = count.coerceIn(0, samples.size - from)
        if (n == 0) return
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = samples[from + i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        runCatching {
            raf.write(bytes)
            sampleCount += n
        }
    }

    /**
     * 回填头部并关闭。**可重复调用**（第二次是空操作）。
     *
     * @return 写入的样本总数
     */
    fun close(): Long {
        val raf = out ?: return sampleCount
        out = null
        runCatching {
            raf.seek(0)
            raf.write(SstvWavWriter.header(sampleCount * (SstvWavWriter.BITS_PER_SAMPLE / 8), sampleRate))
            raf.close()
        }
        return sampleCount
    }
}
