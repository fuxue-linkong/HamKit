package com.example.hamkit.data.sstv

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解码图像的落盘与历史管理。
 *
 * 图像保存在应用私有目录（`filesDir/sstv/`），因此**不需要任何存储权限**；
 * 导出到系统相册属于二期范围（需适配 MediaStore）。
 */
class SstvImageStore(context: Context) {

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** 一条解码历史。 */
    data class Entry(
        /** 图像文件。 */
        val file: File,
        /** 模式展示名（从文件名解析，失败时为空串）。 */
        val modeName: String,
        /** 保存时间（毫秒）。 */
        val timestamp: Long,
    )

    /**
     * 保存解码结果，返回写入的文件；失败返回 null。
     *
     * 文件名形如 `SSTV_20260927_143012_PD-120.png`，便于在图库中直接辨认。
     */
    fun save(result: SstvDecoder.Result): File? {
        val stamp = FILE_STAMP_FORMAT.format(Date())
        val safeName = result.mode.displayName.replace(' ', '_')
        val file = File(directory, "SSTV_${stamp}_$safeName.png")
        return runCatching {
            file.outputStream().use { stream ->
                toBitmap(result.pixels, result.width, result.height)
                    .compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            file
        }.getOrNull()
    }

    /** 列出历史图像，按时间倒序。 */
    fun list(): List<Entry> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.map { file ->
                val parts = file.nameWithoutExtension.split('_')
                Entry(
                    file = file,
                    modeName = parts.drop(3).joinToString("_").replace('_', ' '),
                    timestamp = file.lastModified(),
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    /** 删除一张历史图像。 */
    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** 清空全部历史图像。 */
    fun clear() {
        directory.listFiles()?.forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val DIR_NAME = "sstv"

        private val FILE_STAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        /**
         * 把解码像素数组（打包 0xRRGGBB）转换为 [Bitmap]。
         *
         * Android 要求 ARGB_8888 的数组元素带 alpha 通道，故补上不透明的 0xFF。
         */
        fun toBitmap(pixels: IntArray, width: Int, height: Int): Bitmap {
            val argb = IntArray(pixels.size) { pixels[it] or ALPHA_OPAQUE }
            return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        }

        private const val ALPHA_OPAQUE = 0xFF shl 24
    }
}
