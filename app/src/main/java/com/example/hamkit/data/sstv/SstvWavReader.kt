package com.example.hamkit.data.sstv

import java.io.File

/**
 * WAV（RIFF/WAVE）音频读取器：把用户手上的录音变成解码器需要的 48 kHz 单声道浮点流。
 *
 * **为什么需要它**：一期只能「守着电台实时收」。而 SSTV 的典型场景恰恰是**事后解码**
 * —— 从网上公开的 ARISS 通联录音、或自己用录音笔存下的通联里出图。有了这条离线路径，
 * 「用一段已知内容的真实录音做回归基准」也才成为可能（一期唯一缺失的验证环节）。
 *
 * **为什么 WAV 要自己解析、而不是交给平台解码器**：Android 的 WAV 提取器对
 * 24/32 位整数与浮点 WAV 的支持并不一致（部分机型直接不给轨道），而业余场景里
 * 录音笔/软件导出的恰恰常是这些格式。自解析只需几十行字节操作，却能把
 * 8/16/24/32 位整数与 32/64 位浮点全部覆盖，并且**行为可单测**。
 * 压缩容器（FLAC/MP3/AAC/OGG）由 [SstvMediaAudioReader] 走平台解码器，两条路径的
 * 收尾逻辑共用 [SstvAudioConvert]。
 *
 * **支持的输入**（刻意覆盖业余场景里的绝大多数录音格式）：
 * | 分类 | 支持 |
 * |---|---|
 * | 容器 | `RIFF/WAVE`，含 `fmt `/`data` 块顺序任意、块长度带填充字节的情况 |
 * | 编码 | PCM 整数 8/16/24/32 位、IEEE float 32/64 位、`WAVE_FORMAT_EXTENSIBLE` 包装的上述两种 |
 * | 声道 | 单声道、双声道（下混为均值）；最多 [SstvAudioConvert.MAX_CHANNELS] 声道按下混公式处理 |
 * | 采样率 | 任意；线性插值重采样到 [TARGET_SAMPLE_RATE] |
 *
 * **采样率为什么要重采样**：SSTV 解码器按「模式标称像素时长 × 采样率」换算像素位置，
 * 采样率不匹配会让整幅图像在水平方向被拉伸。虽然 [SstvSync] 的 slant 拟合能吸收
 * 比例误差，但那是给「时钟略有偏差」用的（±5%），不能拿来兜 44.1 kHz vs 48 kHz
 * 之外的任意比例 —— 尤其是 8 kHz 这类远低于像素分辨率的录音，重采样只能保证
 * 时间轴正确，无法补回已经丢失的细节。
 *
 * 纯 Kotlin 实现（只用 `java.io.File`），不依赖任何 Android API，可直接在 JVM 单测中验证。
 *
 * @see SstvAudioConvert 位级解码、下混、截断与重采样（与媒体解码路径共用）
 * @see SstvMediaAudioReader 压缩容器
 */
object SstvWavReader {

    /** 解码链路唯一的工作采样率（理由见 docs/REQUIREMENT_SSTV.md §4.2）。 */
    const val TARGET_SAMPLE_RATE: Int = SstvAudioConvert.TARGET_SAMPLE_RATE

    /**
     * 单次读取的最长时长（秒）。
     *
     * 与 [SstvRecorder] 的单帧上限同源：300 秒足以容纳最长的 PD-290（289 s），
     * 同时把「一段 1 小时的录音」这类误选挡在内存之外（48 kHz 单声道 float
     * 每小时约 690 MB）。
     */
    const val MAX_SECONDS: Double = SstvAudioConvert.MAX_SECONDS

    /** 读取结果：成功或失败（失败必须给出可展示的原因）。 */
    sealed interface Outcome {
        /**
         * @param samples 48 kHz 单声道样本，幅度约 ±1.0
         * @param sourceSampleRate 原始采样率（用于向用户交代「已重采样」）
         * @param sourceChannels 原始声道数
         * @param bitsPerSample 原始位深
         * @param truncated 是否因为超过 [MAX_SECONDS] 而被截断
         */
        data class Success(
            val samples: FloatArray,
            val sourceSampleRate: Int,
            val sourceChannels: Int,
            val bitsPerSample: Int,
            val truncated: Boolean,
        ) : Outcome {
            val durationSeconds: Double get() = samples.size.toDouble() / TARGET_SAMPLE_RATE

            /** 是否需要重采样（用于 UI 提示）。 */
            val resampled: Boolean get() = sourceSampleRate != TARGET_SAMPLE_RATE
        }

        data class Failure(val reason: String) : Outcome
    }

    /**
     * 只读开头 12 字节判断是不是 RIFF/WAVE。
     *
     * **为什么需要它**：[SstvViewModel] 要在「纯 Kotlin 解析」与「平台解码器」之间选路。
     * 按扩展名/mime 判断都不可靠（SAF 给出的 URI 常常没有可用后缀，`ContentResolver.getType`
     * 也可能返回 null 或与实际内容不符），而**按内容判断**只需读 12 个字节，
     * 既不必把整个文件读进内存，也不会像「先试 WAV 解析、失败再兜底」那样为一段
     * 50 MB 的 FLAC 白白读满一遍内存。
     */
    fun isWavFile(file: File): Boolean = runCatching {
        if (file.length() < MIN_HEADER_BYTES) return@runCatching false
        val header = ByteArray(12)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < header.size) return@runCatching false
        }
        tag(header, 0) == "RIFF" && tag(header, 8) == "WAVE"
    }.getOrDefault(false)

    /** 读取一个 WAV 文件。 */
    fun read(file: File, targetSampleRate: Int = TARGET_SAMPLE_RATE): Outcome {
        val bytes = runCatching { file.readBytes() }.getOrElse { e ->
            return Outcome.Failure("无法读取文件：${e.message ?: "IO 错误"}")
        }
        return read(bytes, targetSampleRate)
    }

    /** 从内存中的 WAV 字节读取（便于单元测试与从 `ContentResolver` 读入的流）。 */
    fun read(bytes: ByteArray, targetSampleRate: Int = TARGET_SAMPLE_RATE): Outcome {
        if (bytes.size < MIN_HEADER_BYTES) {
            return Outcome.Failure("文件过小，不是有效的 WAV")
        }
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") {
            return Outcome.Failure("不是 WAV 文件（RIFF/WAVE 头缺失）")
        }

        var format: Format? = null
        var dataOffset = -1
        var dataLength = 0

        // ── 遍历 RIFF 块：顺序任意，长度按偶数字节对齐（不足补 1 字节）──
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = tag(bytes, cursor)
            val declared = SstvAudioConvert.readIntLE(bytes, cursor + 4)
            val body = cursor + 8
            // 声明长度可能超出文件尾（写盘中断），也可能被写成 0 / 0xFFFFFFFF
            // （流式录音常见的占位值），两种情况都退化为「用到文件尾」。
            val available = (bytes.size - body).coerceAtLeast(0)
            val length = if (declared in 1..available) declared else available

            when (id) {
                "fmt " -> format = parseFormat(bytes, body, length)
                "data" -> {
                    dataOffset = body
                    dataLength = length
                }
            }
            // 块长度按偶数字节对齐；body = cursor + 8 保证游标至少前进 8 字节
            cursor = body + length + (length and 1)
        }

        val fmt = format ?: return Outcome.Failure("缺少 fmt 块，无法确定音频格式")
        // 格式问题优先于「没有数据」报告：编码不支持时即便有数据也解不出来，
        // 先给出更具体的原因对用户更有用
        fmt.unsupportedReason?.let { return Outcome.Failure(it) }
        if (dataOffset < 0 || dataLength <= 0) return Outcome.Failure("缺少 data 块或音频数据为空")

        // 位级解码、下混、截断、重采样与「音频数据为空 / 过短」的判定都在共享层，
        // 保证与 SstvMediaAudioReader（平台解码器）路径逐项一致
        val mono = SstvAudioConvert.decodeMono(bytes, dataOffset, dataLength, fmt.toConvertFormat())
        return SstvAudioConvert.finish(mono, fmt.toConvertFormat(), targetSampleRate)
    }

    // ══════════════════════════════════════════════════════════════════
    // 内部实现
    // ══════════════════════════════════════════════════════════════════

    /** 解析后的 `fmt ` 块。 */
    private class Format(
        /** 采样编码；不支持时为占位值（一定伴随 `unsupportedReason`，不会被使用）。 */
        val encoding: SstvAudioConvert.Encoding,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
    ) {
        /** 非空表示该格式不被支持，字符串即原因。 */
        var unsupportedReason: String? = null

        /** 转换为共享层需要的布局。 */
        fun toConvertFormat(): SstvAudioConvert.Format =
            SstvAudioConvert.Format(encoding, channels, sampleRate)
    }

    private fun parseFormat(bytes: ByteArray, offset: Int, length: Int): Format? {
        if (length < 16 || offset + 16 > bytes.size) return null
        var audioFormat = SstvAudioConvert.readShortLE(bytes, offset).toInt() and 0xFFFF
        val channels = SstvAudioConvert.readShortLE(bytes, offset + 2).toInt() and 0xFFFF
        val sampleRate = SstvAudioConvert.readIntLE(bytes, offset + 4)
        val bitsPerSample = SstvAudioConvert.readShortLE(bytes, offset + 14).toInt() and 0xFFFF

        // WAVE_FORMAT_EXTENSIBLE (0xFFFE)：真正的编码在 GUID 的前 2 字节
        if (audioFormat == WAVE_FORMAT_EXTENSIBLE && length >= 26 && offset + 26 <= bytes.size) {
            audioFormat = SstvAudioConvert.readShortLE(bytes, offset + 24).toInt() and 0xFFFF
        }

        val encoding = when {
            audioFormat == WAVE_FORMAT_PCM -> when (bitsPerSample) {
                8 -> SstvAudioConvert.Encoding.UNSIGNED_8
                16 -> SstvAudioConvert.Encoding.SIGNED_16
                24 -> SstvAudioConvert.Encoding.SIGNED_24
                else -> SstvAudioConvert.Encoding.SIGNED_32
            }
            // 浮点只有 32/64 两种；其余落到 FLOAT_64 仅为占位
            bitsPerSample == 32 -> SstvAudioConvert.Encoding.FLOAT_32
            else -> SstvAudioConvert.Encoding.FLOAT_64
        }

        val format = Format(encoding, channels, sampleRate, bitsPerSample)
        // 分支顺序与一期一致：先报声道/采样率这类「整条轨道都不可用」的问题，
        // 再报编码与位深，让用户看到的第一个原因始终是最根本的那个
        when {
            channels <= 0 -> format.unsupportedReason = "声道数非法（$channels）"
            channels > SstvAudioConvert.MAX_CHANNELS ->
                format.unsupportedReason =
                    "声道数过多（$channels，最多支持 ${SstvAudioConvert.MAX_CHANNELS}）"
            sampleRate <= 0 -> format.unsupportedReason = "采样率非法（$sampleRate Hz）"
            audioFormat != WAVE_FORMAT_PCM && audioFormat != WAVE_FORMAT_FLOAT ->
                format.unsupportedReason = "不支持的编码（format=$audioFormat，仅支持 PCM 与 IEEE float）"
            audioFormat == WAVE_FORMAT_PCM && bitsPerSample !in SUPPORTED_PCM_BITS ->
                format.unsupportedReason =
                    "不支持的位深（$bitsPerSample 位，支持 ${SUPPORTED_PCM_BITS.joinToString("/")}）"
            audioFormat == WAVE_FORMAT_FLOAT && bitsPerSample != 32 && bitsPerSample != 64 ->
                format.unsupportedReason = "不支持的浮点位深（$bitsPerSample 位，仅支持 32/64 位）"
        }
        return format
    }

    /** 取 4 字节 ASCII 标签（越界返回空串）。 */
    private fun tag(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 4 > bytes.size) return ""
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }

    private const val MIN_HEADER_BYTES = 12 + 8 + 16 + 8

    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_FLOAT = 3
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

    private val SUPPORTED_PCM_BITS = intArrayOf(8, 16, 24, 32).toList()
}
