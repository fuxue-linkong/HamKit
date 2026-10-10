package com.example.hamkit.data.sstv

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解码图像的落盘、历史管理与导出。
 *
 * 图像保存在应用私有目录（`filesDir/sstv/`），因此**不需要任何存储权限**；
 * 需要让别的应用看到时才走 [exportToGallery]（Android 10+ 用 MediaStore，无需权限）
 * 或 [shareUri]（FileProvider，manifest 中已有 `files-path` 声明）。
 *
 * 文件名形如 `SSTV_20260927_143012_PD-120.png`，后处理版本追加 `_enhanced`，
 * 便于在图库里一眼分辨原图与增强图。
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
        /** 是否为后处理（增强）版本。 */
        val enhanced: Boolean,
        /** 保存时间（毫秒）。 */
        val timestamp: Long,
        /** 文件大小（字节）。 */
        val sizeBytes: Long,
    )

    /** 导出到相册的结果。 */
    sealed interface ExportOutcome {
        data class Success(val uri: Uri) : ExportOutcome

        /** @param needsPermission true 表示补上存储权限后重试即可成功（Android 9 及以下） */
        data class Failure(val reason: String, val needsPermission: Boolean = false) : ExportOutcome
    }

    /**
     * 保存解码结果，返回写入的文件；失败返回 null。
     *
     * @param enhanced 是否为后处理版本（文件名追加 `_enhanced`）
     */
    fun save(result: SstvDecoder.Result, enhanced: Boolean = false): File? =
        savePixels(result.pixels, result.width, result.height, result.mode.displayName, enhanced)

    /** 保存任意像素数组（后处理结果、外部导入结果等）。 */
    fun savePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        modeName: String,
        enhanced: Boolean = false,
    ): File? {
        val stamp = FILE_STAMP_FORMAT.format(Date())
        val safeName = modeName.replace(' ', '_')
        val suffix = if (enhanced) ENHANCED_SUFFIX else ""
        val file = File(directory, "SSTV_${stamp}_$safeName$suffix.png")
        return runCatching {
            file.outputStream().use { stream ->
                toBitmap(pixels, width, height)
                    .compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            file
        }.getOrNull()
    }

    /** 列出历史图像，按时间倒序。 */
    fun list(): List<Entry> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.map { file -> entryOf(file) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    private fun entryOf(file: File): Entry {
        val stem = file.nameWithoutExtension
        val enhanced = stem.endsWith(ENHANCED_SUFFIX)
        val base = if (enhanced) stem.dropLast(ENHANCED_SUFFIX.length) else stem
        // 文件名形如 SSTV_<日期>_<时间>_<模式名>：前三段是固定前缀，其余都属于模式名
        val parts = base.split('_')
        val modeName = if (parts.size > 3) {
            parts.drop(3).joinToString("_").replace('_', ' ')
        } else {
            ""
        }
        return Entry(
            file = file,
            modeName = modeName,
            enhanced = enhanced,
            timestamp = file.lastModified(),
            sizeBytes = file.length(),
        )
    }

    /** 图库占用的总字节数（设置页/图库头部展示用）。 */
    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    /** 删除一张历史图像。 */
    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    /** 清空全部历史图像。 */
    fun clear() {
        directory.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * 生成缩略图。
     *
     * **必须只在协程里调用**：一期曾因为把文件 IO 与位图解码放在主线程而卡顿；
     * 这里用 [BitmapFactory.Options.inJustDecodeBounds] 先拿尺寸、再用
     * [calculateInSampleSize] 取 2 的幂次采样率，因此不会把整张 640×496 原图展开。
     */
    fun decodeThumbnail(file: File, reqWidth: Int, reqHeight: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()

    /**
     * 导出到系统相册。
     *
     * - **Android 10（API 29）及以上**：走 MediaStore + `IS_PENDING`，无需任何权限；
     * - **Android 9 及以下**：写 MediaStore 需要 `WRITE_EXTERNAL_STORAGE`，缺失时返回
     *   [ExportOutcome.Failure] 且 `needsPermission = true`，由 UI 申请后重试。
     *
     * @return 成功时给出新条目的 [Uri]
     */
    fun exportToGallery(context: Context, file: File): ExportOutcome {
        if (!file.isFile) return ExportOutcome.Failure("源文件不存在")
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            return ExportOutcome.Failure("Android 9 及以下导出到相册需要存储权限", needsPermission = true)
        }

        val resolver = context.contentResolver
        val isScoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val collection = if (isScoped) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (isScoped) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return ExportOutcome.Failure("无法在相册中创建条目")

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
        }.getOrDefault(false)

        if (!written) {
            // 写失败必须回滚条目，否则相册里会留下一个打不开的空文件
            runCatching { resolver.delete(uri, null, null) }
            return ExportOutcome.Failure("写入相册失败")
        }

        if (isScoped) {
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }
        return ExportOutcome.Success(uri)
    }

    /** FileProvider URI，用于 `ACTION_SEND` 分享（manifest 已声明 `files-path`）。 */
    fun shareUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    companion object {
        private const val DIR_NAME = "sstv"

        /** 导出到相册时的子相册名。 */
        const val GALLERY_ALBUM = "HamKit"

        /** 后处理版本的文件名后缀。 */
        const val ENHANCED_SUFFIX = "_enhanced"

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

        /**
         * 计算 2 的幂次采样率（与 `LandscapeImageStore` 同一套写法）。
         *
         * 用 2 的幂是因为 [BitmapFactory] 只在 2 的幂上做无损降采样；非 2 的幂会退化成
         * 「先全尺寸解码再缩放」，反而更吃内存。
         */
        fun calculateInSampleSize(
            width: Int,
            height: Int,
            reqWidth: Int,
            reqHeight: Int,
        ): Int {
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        private const val ALPHA_OPAQUE = 0xFF shl 24
    }
}
