package com.example.hamkit.data.sstv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import kotlin.math.max

/**
 * Android 侧的图像输入装配：把「用户选的图片」变成编码器能吃的 [SstvImageSource]。
 *
 * 与 [SstvEncoder] 刻意分层 —— 编码器只认 [SstvImageSource]（纯 Kotlin，可在 JVM 单测里
 * 驱动），系统相册、`Bitmap`、呼号叠加这些 Android 相关的事都留在这里。
 *
 * **为什么要先缩放到模式分辨率**：一张 4000×3000 的手机照片解成 ARGB_8888 是 48 MB，
 * 而编码器只按模式分辨率（最大 PD-290 的 800×616）取点。提前用
 * [BitmapFactory.Options.inSampleSize] + `createScaledBitmap` 降到目标尺寸，内存从
 * 48 MB 降到不足 2 MB，编码时的最近邻取点也变成顺序访问。
 */
object SstvBitmapSource {

    /**
     * 载入图片并适配为 [SstvImageSource]。
     *
     * @param mode 目标模式（决定缩放后的分辨率）
     * @param callsign 非空时在图像右下角叠加呼号
     * @return 载入失败（文件损坏 / 不是图片 / 无权限）时返回 null
     */
    fun load(context: Context, uri: Uri, mode: SstvMode, callsign: String? = null): SstvImageSource? =
        load(mode, callsign) { context.contentResolver.openInputStream(uri) }

    /**
     * 从应用私有文件载入（用于「把图库里已解码的图重新发射」）。
     *
     * 与 URI 版本共用同一套缩放与叠加逻辑，只是打开方式不同。
     */
    fun load(context: Context, file: java.io.File, mode: SstvMode, callsign: String? = null): SstvImageSource? =
        load(mode, callsign) { file.inputStream() }

    private fun load(
        mode: SstvMode,
        callsign: String?,
        openStream: () -> java.io.InputStream?,
    ): SstvImageSource? {
        val bitmap = decodeScaled(openStream, mode.linePixels, mode.imageLines) ?: return null
        val withOverlay = if (callsign.isNullOrBlank()) bitmap else overlayCallsign(bitmap, callsign)
        val source = fromBitmap(withOverlay)
        if (withOverlay !== bitmap) withOverlay.recycle()
        bitmap.recycle()
        return source
    }

    /** 载入图片并 **不回收**（供 UI 预览；调用方负责 recycle）。 */
    fun loadPreview(context: Context, uri: Uri, maxWidth: Int = 1280): Bitmap? =
        decodeScaled({ context.contentResolver.openInputStream(uri) }, maxWidth, maxWidth)

    /** 从已解码的像素数组构造图像源（用于「把图库里已解码的图重新发射」）。 */
    fun fromPixels(pixels: IntArray, width: Int, height: Int): SstvImageSource =
        SstvPixelImageSource(width, height, pixels)

    /** 从 [Bitmap] 构造图像源（复制像素，不持有 Bitmap）。 */
    fun fromBitmap(bitmap: Bitmap): SstvImageSource {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // Bitmap 的像素带 alpha 通道，这里剥掉以免编码端把高位当红色
        for (i in pixels.indices) pixels[i] = pixels[i] and RGB_MASK
        return SstvPixelImageSource(width, height, pixels)
    }

    /**
     * 在图像右下角叠加呼号。
     *
     * 白字 + 黑色描边：SSTV 图像亮度分布很宽，纯白字压在浅色区域会消失，描边能保证
     * 在任何底色上可读。字号按图像宽度取 1/16，并留 2% 边距。
     */
    fun overlayCallsign(bitmap: Bitmap, callsign: String): Bitmap {
        val mutable = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val textSize = max(MIN_TEXT_SIZE, mutable.width / 16f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val bounds = Rect()
        paint.getTextBounds(callsign, 0, callsign.length, bounds)
        val margin = mutable.width * 0.02f
        val x = mutable.width - margin
        val y = mutable.height - margin

        // 先描边后填色：描边宽度随字号缩放，保证在不同分辨率下观感一致
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2f, textSize / 8f)
        paint.color = Color.BLACK
        canvas.drawText(callsign, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawText(callsign, x, y, paint)
        return mutable
    }

    /**
     * 按目标尺寸解码（2 的幂次降采样 + 精确缩放）。
     *
     * 两步走是内存与画质的折中：`inSampleSize` 只能取 2 的幂，若只用它，缩放结果会在
     * 目标尺寸的 1–2 倍之间浮动；再补一次 `createScaledBitmap` 才能精确落到模式分辨率。
     */
    private fun decodeScaled(
        openStream: () -> java.io.InputStream?,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return@runCatching null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val options = BitmapFactory.Options().apply {
            inSampleSize = SstvImageStore.calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidth,
                targetHeight,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null

        if (decoded.width == targetWidth && decoded.height == targetHeight) {
            decoded
        } else {
            val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        }
    }.getOrNull()

    private const val RGB_MASK = 0x00FFFFFF

    private const val MIN_TEXT_SIZE = 10f
}
