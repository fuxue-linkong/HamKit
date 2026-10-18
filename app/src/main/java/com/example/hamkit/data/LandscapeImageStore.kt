package com.example.hamkit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class LandscapeImageStore(context: Context) {

    private val appContext = context.applicationContext

    private val bgDir = File(appContext.filesDir, BG_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    val customImageFile: File get() = File(bgDir, CUSTOM_IMAGE_NAME)

    val defaultImageFile: File get() = File(bgDir, DEFAULT_IMAGE_NAME)

    val hasCustomImage: Boolean
        get() = customImageFile.exists() && customImageFile.length() > 0

    val effectiveImageFile: File?
        get() = customImageFile.takeIf { it.exists() && it.length() > 0 }
            ?: defaultImageFile.takeIf { it.exists() && it.length() > 0 }

    init {
        ensureDefaultImage()
    }

    /**
     * 确保默认时间卡背景（the_moon）存在。
     *
     * 通过 assets 原始字节复制到本地，不经过 Bitmap 解码/压缩，保证无损；
     * 仅在文件缺失时复制一次，避免重复 IO。
     */
    fun ensureDefaultImage(): File? {
        val file = defaultImageFile
        if (file.exists() && file.length() > 0) return file
        return try {
            appContext.assets.open(DEFAULT_IMAGE_ASSET).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun saveCustomImage(sourceUri: Uri, context: Context): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            // 用 use 保证流在异常路径（解码失败/OOM）下也被关闭
            val opened = context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
                true
            } ?: return fail("无法打开输入流，uri=${sourceUri.scheme}:$sourceUri")

            if (!opened) return fail("打开输入流失败")
            val maxWidth = 1920
            options.inSampleSize = calculateInSampleSize(
                options.outWidth, options.outHeight, maxWidth, maxWidth * 9 / 16
            )
            options.inJustDecodeBounds = false

            val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return fail("解码失败，uri=${sourceUri.scheme}:$sourceUri")

            saveBitmapAsCustomImage(bitmap)
        } catch (e: Exception) {
            // 跨进程临时授权失效（MIUI/HyperOS 常见 SecurityException）会走到这里
            fail("读取裁剪结果失败", e)
        }
    }

    /**
     * 直接用内存中的位图保存自定义背景。
     *
     * 裁剪页返回的 uriContent 可能是相册原图的 MediaStore uri，其临时授权可能在
     * 跨进程回传后失效；此时直接使用裁剪结果位图可绕开授权问题。
     */
    fun saveCustomImage(bitmap: Bitmap): Boolean {
        return try {
            saveBitmapAsCustomImage(bitmap)
        } catch (e: Exception) {
            fail("保存裁剪位图失败", e)
        }
    }

    /**
     * 缩放并原子写入目标文件。调用方负责 recycle 传入的 bitmap。
     */
    private fun saveBitmapAsCustomImage(bitmap: Bitmap): Boolean {
        val maxWidth = 1920
        val scaledBitmap = if (bitmap.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bitmap.width
            val scaledHeight = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, maxWidth, scaledHeight, true)
        } else {
            bitmap
        }

        // 原子替换：先写临时文件，成功后再替换目标文件，避免写入失败导致旧背景丢失
        val tmpFile = File(bgDir, "$CUSTOM_IMAGE_NAME.tmp")
        var saved = false
        try {
            FileOutputStream(tmpFile).use { fos ->
                saved = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            if (!saved) {
                fail("JPEG 压缩失败")
            } else {
                // 写入成功后删除旧文件并重命名临时文件为目标文件
                if (customImageFile.exists()) customImageFile.delete()
                saved = tmpFile.renameTo(customImageFile)
                if (!saved) fail("临时文件重命名失败: ${tmpFile.absolutePath}")
            }
            if (!saved) tmpFile.delete()
        } catch (e: Exception) {
            tmpFile.delete()
            // 注意：不能再调用 fail()，它返回 false 会被误赋给 saved 掩盖真实结果
            Log.e(TAG, "写入背景文件失败：${tmpFile.absolutePath}", e)
            saved = false
        }
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        return saved
    }

    /** 统一记录失败原因，避免 saveCustomImage 静默返回 false 导致无从排查 */
    private fun fail(message: String, e: Throwable? = null): Boolean {
        Log.e(TAG, "自定义背景导入失败：$message", e)
        return false
    }

    fun clearCustomImage() {
        customImageFile.delete()
        // 同时清理可能残留的临时文件
        File(bgDir, "$CUSTOM_IMAGE_NAME.tmp").delete()
    }

    companion object {
        private const val TAG = "BgImport"
        private const val BG_DIR_NAME = "bg_landscape"
        private const val CUSTOM_IMAGE_NAME = "custom_image.jpg"
        private const val DEFAULT_IMAGE_NAME = "default_image.jpg"
        private const val DEFAULT_IMAGE_ASSET = "the_moon.jpg"

        private fun calculateInSampleSize(
            rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int
        ): Int {
            var inSampleSize = 1
            if (rawHeight > reqHeight || rawWidth > reqWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while (halfHeight / inSampleSize >= reqHeight ||
                    halfWidth / inSampleSize >= reqWidth
                ) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }
}
