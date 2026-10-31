package com.example.hamkit.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** 附件类型（决定解析方式）。 */
enum class AiAttachmentKind { TEXT, AUDIO, IMAGE, UNSUPPORTED }

enum class AiAttachmentStatus { PARSING, READY, ERROR }

/** 一条待发送附件。 */
data class AiAttachment(
    val id: String,
    val uri: Uri,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val kind: AiAttachmentKind,
    val status: AiAttachmentStatus = AiAttachmentStatus.PARSING,
    val text: String? = null,
    val error: String? = null,
    val sstvMode: String? = null,
    val sstvFile: String? = null,
    val imageBase64: String? = null,
)

/**
 * 附件识别与文本读取。
 *
 * 音频附件由 ViewModel 调用 [SstvViewModel.importAudio] 解码（需要应用级 VM），
 * 这里只负责类型判断与文本读取，保持可单测。
 */
object AiAttachmentParser {

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "csv", "xml", "yaml", "yml", "log",
        "kt", "java", "py", "js", "ts", "html", "css", "ini", "properties", "gradle",
    )
    private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "flac", "aac", "ogg", "m4a", "opus", "amr")

    fun kindOf(name: String, mime: String): AiAttachmentKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        val lowerMime = mime.lowercase()
        return when {
            ext in AUDIO_EXTENSIONS || lowerMime.startsWith("audio/") -> AiAttachmentKind.AUDIO
            ext in TEXT_EXTENSIONS || lowerMime.startsWith("text/") ||
                lowerMime == "application/json" || lowerMime == "application/xml" ->
                AiAttachmentKind.TEXT
            lowerMime.startsWith("image/") -> AiAttachmentKind.IMAGE
            else -> AiAttachmentKind.UNSUPPORTED
        }
    }

    /** 读取文本附件（最多 [maxChars] 字符），失败返回 null。 */
    fun readText(context: Context, uri: Uri, maxChars: Int = MAX_TEXT_CHARS): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                val limit = maxChars * 4 // UTF-8 下字节数通常大于字符数
                var total = 0
                while (total < limit) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val take = minOf(read, limit - total)
                    output.write(buffer, 0, take)
                    total += take
                }
                String(output.toByteArray(), Charsets.UTF_8).take(maxChars)
            }
        }.getOrNull()

    /**
     * 读取图片并压缩成 JPEG base64（长边 ≤ [maxDimension]，体积尽量 ≤ 1.5MB）。
     * 失败返回 null（调用方标记附件错误）。
     */
    fun readImageBase64(context: Context, uri: Uri, maxDimension: Int = 1024): String? {
        return runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null

            val output = ByteArrayOutputStream()
            var quality = 85
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            while (output.size() > 1_500_000 && quality > 40) {
                output.reset()
                quality -= 15
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
            val bytes = output.toByteArray()
            bitmap.recycle()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    const val MAX_TEXT_CHARS = 20_000
}

/**
 * 默认系统提示词。
 *
 * 用户可在设置里覆盖（留空即使用本默认值）。内容要点：
 * 1. 明确 App 能力与工具清单；
 * 2. 明确附件处理方式（音频已用 SSTV 解码、文本附件是上下文）；
 * 3. 禁止编造实时数据；
 * 4. 回答风格。
 */
object AiPrompts {
    val DEFAULT_SYSTEM_PROMPT: String = """
        你是 HamKit 内置的业余无线电 AI 助手。HamKit 是一个 Android 端的业余无线电工具箱，
        提供双区定位（CQ/ITU 分区与 Maidenhead 网格）、卫星过境预测、SSTV 收发、HF Fax、
        CW 摩尔斯、FT8、APRS、QSO 日志等功能。

        你可以调用以下工具获取真实数据（不要假装调用，也不要编造结果）：
        - get_zone_info：当前位置/指定坐标的 CQ、ITU 分区与网格
        - get_satellite_passes：卫星未来过境（AOS/LOS、最大仰角、方位、白天/夜间）
        - get_satellite_live：卫星实时方位/仰角/距离/星下点/多普勒/蚀状态
        - get_satellite_transmitters：转发器上行/下行频率、模式、状态
        - search_satellites：按名称/模式/是否在境搜索卫星
        - get_amsat_status：AMSAT 状态报告
        - get_weather / get_utc_time：天气与时间
        - cw_encode：文本转摩斯电码
        - open_screen / start_sstv_receive：打开页面、开始 SSTV 接收
        - add_pass_reminder：创建过境提醒

        关于附件：
        - 文本附件（txt/md/json/csv/代码等）会以代码块附在用户消息里，请把它当作上下文阅读；
        - 音频附件已由 App 的 SSTV 解码器离线解码，结果（模式、是否保存到图库）会在消息中说明；
        - 图片/PDF/Office 文档目前不支持解析，若用户上传请直接说明限制。

        回答要求：
        1. 涉及位置、卫星、频率、状态等实时数据时优先调用工具；工具失败就如实说明。
        2. 不要编造工具没有返回的数据（过境时间、频率、状态）。
        3. 中文提问用中文回答，英文提问用英文回答；简洁，必要时使用 Markdown（标题/列表/代码块）。
        4. 如需用户提供呼号、网格或时间范围才能回答，直接问清楚。
    """.trimIndent()
}
