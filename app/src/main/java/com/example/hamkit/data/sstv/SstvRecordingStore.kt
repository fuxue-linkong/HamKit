package com.example.hamkit.data.sstv

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接收录音的存储与索引。
 *
 * 与 [SstvImageStore]（解码图像）分开存放，理由有两条：
 *  1. 录音是**中间物**，用户清空图库时未必想连带删掉可重解的素材；
 *  2. 两者大小差一个数量级（一帧 PD-120 的录音约 12 MB，图像只有几十 KB），
 *     混在一起会让「图库占用」这个数字失去意义。
 *
 * 文件名为 `SSTV_RX_<时间戳>[_<模式>].wav`：模式在收到 VIS 之后才知道，因此先按时间戳
 * 落盘、结束时再改名（见 [renameWithMode]）—— 这样即使用户中途停止也能拿到可用的录音。
 */
class SstvRecordingStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** 一条录音。 */
    data class Entry(
        val file: File,
        /** 从文件名解析出的模式名；未知为空串。 */
        val modeName: String,
        val timestamp: Long,
        val sizeBytes: Long,
    ) {
        /** 时长（秒）：按 16 位单声道 48 kHz 推算（本项目所有录音都是这个格式）。 */
        val seconds: Double
            get() = (sizeBytes - SstvWavWriter.HEADER_BYTES).coerceAtLeast(0) / 2.0 /
                SstvVisDetector.DEFAULT_SAMPLE_RATE
    }

    /** 创建一个新的录音文件（尚未写入）。 */
    fun newFile(): File =
        File(directory, "SSTV_RX_${STAMP_FORMAT.format(Date())}.wav")

    /**
     * 收到 VIS、知道模式之后给文件补上模式名。
     *
     * @return 改名后的文件；改名失败（目标已存在、文件被占用）时返回原文件
     */
    fun renameWithMode(file: File, modeName: String?): File {
        if (modeName.isNullOrBlank() || !file.isFile) return file
        val safe = modeName.replace(' ', '_').replace('/', '_')
        val target = File(file.parentFile, "${file.nameWithoutExtension}_$safe.wav")
        if (target.exists()) return file
        return if (runCatching { file.renameTo(target) }.getOrDefault(false)) target else file
    }

    /** 列出录音，按时间倒序。 */
    fun list(): List<Entry> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.map { file ->
                // 文件名形如 SSTV_RX_<日期>_<时间>[_<模式>]：前四段是固定前缀
                val parts = file.nameWithoutExtension.split('_')
                Entry(
                    file = file,
                    modeName = if (parts.size > 4) {
                        parts.drop(4).joinToString("_").replace('_', ' ')
                    } else {
                        ""
                    },
                    timestamp = file.lastModified(),
                    sizeBytes = file.length(),
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    /** 占用总字节数。 */
    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    fun clear() {
        directory.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val DIR_NAME = "sstv_recordings"

        val STAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
