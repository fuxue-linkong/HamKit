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
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

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
 *
 * **为什么必须先落缓存文件再解码（HK 修复）**：旧实现对同一个 URI 调了两次
 * `openInputStream`（第一次只取尺寸）。系统相册、文件的 `ContentProvider`、云盘/网盘、
 * 聊天软件这类「一次授权、非 seekable」的 provider，第二次打开常拿不到流，于是解码返回
 * null —— 界面上就表现为「选完图片毫无反应 / 图片载入失败」。现在统一由调用方先把所选
 * 文件复制成缓存文件（见 [SstvViewModel.setTxImage]），这里只用 [File] 读，一次选中后可
 * 反复重装配（切模式、开关呼号）而不依赖 URI 权限是否还有效。
 */
object SstvBitmapSource {

    /**
     * 载入图片并适配为 [SstvImageSource]。
     *
     * **注意**：直接传 `content://` URI 时本方法只保证「只打开一次流」，但仍依赖该 URI 的
     * 读取权限；发射页应优先走「复制到缓存文件」的路径。保留此重载是为了兼容既有调用点。
     *
     * @param mode 目标模式（决定缩放后的分辨率）
     * @param callsign 非空时在图像右下角叠加呼号
     * @return 载入失败（文件损坏 / 不是图片 / 无权限）时返回 null
     */
    fun load(context: Context, uri: Uri, mode: SstvMode, callsign: String? = null): SstvImageSource? =
        load(mode, callsign) { readAll(context, uri) }

    /**
     * 从本地文件载入（应用缓存文件，或图库里已解码的图）。
     *
     * 与 URI 版本共用同一套缩放与叠加逻辑，只是打开方式不同。
     */
    fun load(context: Context, file: File, mode: SstvMode, callsign: String? = null): SstvImageSource? =
        load(mode, callsign) { runCatching { file.readBytes() }.getOrNull() }

    /**
     * 装载结果。
     *
     * @param source 编码器用的图像源（已裁成模式宽高比、已按模式分辨率缩放）
     * @param originalWidth 源图（裁剪前）宽度，用于提示用户「被裁了多少」
     * @param originalHeight 源图（裁剪前）高度
     */
    data class LoadedImage(
        val source: SstvImageSource,
        val originalWidth: Int,
        val originalHeight: Int,
    ) {
        /** 源图是否被裁过（源比例与目标比例不一致即会裁）。 */
        val cropped: Boolean
            get() = source.width != originalWidth || source.height != originalHeight
    }

    /**
     * 带失败原因版本：UI 需要把「为什么载入失败」显示给用户，而不是一律「图片载入失败」。
     *
     * 注意 [runCatching] 会吞掉 `OutOfMemoryError` 之外的异常并把 message 原样上抛，
     * 调用方据此拼出「图片载入失败：<原因>」。
     *
     * @return 成功给出 [LoadedImage]（含源图原始尺寸），失败给出可读的中文原因
     */
    fun loadOrError(file: File, mode: SstvMode, callsign: String? = null): Result<LoadedImage> =
        runCatching {
            require(file.isFile && file.length() > 0) { "所选的图片文件为空或已被移除，请重新选择" }
            // 只读一次：SstvBitmapSource 的重载都基于字节数组，避免重复打开数据源
            val bytes = file.readBytes()
            // 一次读出源图尺寸，既用于失败判定，也用于「是否被裁剪」的提示
            val bounds = decodableSize(bytes)
                ?: error("无法识别该图片格式（支持 JPEG / PNG / WebP / GIF / BMP / HEIF），请换一张试试")
            // decodeScaled 内部按模式宽高比自动裁剪，再缩放到模式分辨率
            val bitmap = decodeScaled(bytes, mode.linePixels, mode.imageLines, bounds)
                ?: error("无法完成图片适配（可能是文件已损坏或内存不足），请换一张试试")
            LoadedImage(
                source = bitmap.toSource(callsign),
                originalWidth = bounds.outWidth,
                originalHeight = bounds.outHeight,
            )
        }

    /** 读出图片原始尺寸（只解析文件头，不解码像素）。 */
    private fun decodableSize(bytes: ByteArray): BitmapFactory.Options? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    /** 位图 → 图像源（可选叠加呼号），并负责回收中间位图。 */
    private fun Bitmap.toSource(callsign: String?): SstvImageSource {
        // 解码得到的 Bitmap 由这里负责回收：叠加呼号会产出新位图，两者都不能泄漏
        val withOverlay = if (callsign.isNullOrBlank()) this else overlayCallsign(this, callsign)
        val source = fromBitmap(withOverlay)
        if (withOverlay !== this) withOverlay.recycle()
        recycle()
        return source
    }

    /** 为看图预览等场景提供「只解码不叠加」的公开入口（失败返回 null）。 */
    fun decodeForPreview(file: File, maxWidth: Int, maxHeight: Int): Bitmap? = runCatching {
        decodeScaled(file.readBytes(), maxWidth, maxHeight)
    }.getOrNull()

    private fun load(
        mode: SstvMode,
        callsign: String?,
        readBytes: () -> ByteArray?,
    ): SstvImageSource? = runCatching {
        val bytes = readBytes() ?: return@runCatching null
        // decodeScaled 会按模式宽高比自动裁剪，再缩放到模式分辨率
        val bitmap = decodeScaled(bytes, mode.linePixels, mode.imageLines) ?: return@runCatching null
        bitmap.toSource(callsign)
    }.getOrNull()

    /**
     * 把 URI 内容整体读成字节数组。
     *
     * 只打开一次流；读失败（无权限 / provider 拒绝）时返回 null。SSTV 发射的源图最多几 MB，
     * 一次性读进内存比「同一 URI 开两次流」可靠得多。
     */
    private fun readAll(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /** 载入图片并 **不回收**（供 UI 预览；调用方负责 recycle）。 */
    fun loadPreview(context: Context, uri: Uri, maxWidth: Int = 1280): Bitmap? =
        decodeScaled(readAll(context, uri) ?: return null, maxWidth, maxWidth)

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
     * 按目标尺寸解码并做**等比裁剪**适配（自动裁剪）。
     *
     * 两步走是内存与画质的折中：`inSampleSize` 只能取 2 的幂，若只用它，缩放结果会在
     * 目标尺寸的 1–2 倍之间浮动；再补一次 `createScaledBitmap` 才能精确落到模式分辨率。
     *
     * **关键（自动裁剪）**：旧实现直接 `createScaledBitmap` 到目标宽高，源图只要不是
     * 模式比例（如 16:9 手机照片发 PD-120 的 640×496）就会被**横向拉伸变形**。
     * 现在先按目标宽高比居中裁掉多余部分，再缩放 —— 画面不变形，代价是裁掉一圈边缘。
     *
     * 改为从字节数组解码（`decodeByteArray`）而不是流：一张图只解析一份内存缓冲，
     * 既不重复打开数据源，也避开了 provider 流不可回退的问题。
     */
    private fun decodeScaled(
        bytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        knownBounds: BitmapFactory.Options? = null,
    ): Bitmap? = runCatching {
        val bounds = knownBounds ?: BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (knownBounds == null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
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
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null

        // ① 先裁成目标宽高比：比较「源图比例」与「目标比例」，
        //    源图更宽就裁左右，更高就裁上下，居中裁以保证主体不丢
        val targetRatio = targetWidth.toFloat() / targetHeight
        val sourceRatio = decoded.width.toFloat() / decoded.height
        val cropped = if (sourceRatio > targetRatio) {
            // 源更宽：裁掉左右，保留高度
            val newWidth = max(1, (decoded.height * targetRatio).roundToInt())
            val left = (decoded.width - newWidth) / 2
            Bitmap.createBitmap(decoded, left, 0, newWidth, decoded.height)
        } else if (sourceRatio < targetRatio) {
            // 源更高：裁掉上下，保留宽度
            val newHeight = max(1, (decoded.width / targetRatio).roundToInt())
            val top = (decoded.height - newHeight) / 2
            Bitmap.createBitmap(decoded, 0, top, decoded.width, newHeight)
        } else {
            decoded
        }
        if (cropped !== decoded) decoded.recycle()

        // ② 再精确缩放到模式分辨率（此时比例已一致，不会变形）
        if (cropped.width == targetWidth && cropped.height == targetHeight) {
            cropped
        } else {
            val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        }
    }.getOrNull()

    private const val RGB_MASK = 0x00FFFFFF

    private const val MIN_TEXT_SIZE = 10f
}
