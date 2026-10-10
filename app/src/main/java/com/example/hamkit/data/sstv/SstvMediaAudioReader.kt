package com.example.hamkit.data.sstv

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * 用平台解码器读取任意容器：FLAC、MP3、AAC/M4A、OGG、以及 WAV。
 *
 * **为什么要有它**：只支持 WAV 会把相当一部分实际素材挡在门外 —— 网上流传的 ISS
 * SSTV 通联录音以 MP3/OGG 居多，手机自带录音机默认输出 M4A/AAC，SDR 软件常存 FLAC。
 * 这些容器自己解不现实（FLAC 要 LPC 残差还原、MP3 要 IMDCT …），而系统里就有解码器，
 * 直接用 [MediaExtractor] + [MediaCodec] 即可，**不引入任何第三方依赖**。
 *
 * **与 [SstvWavReader] 的分工**：WAV 优先走纯 Kotlin 解析（对 24/32 位整数与浮点
 * WAV 的支持比系统提取器可靠，且可单测）；本类负责压缩容器，以及「WAV 变体本解析器
 * 不认识、但系统解码器能解」的兜底（例如 IMA ADPCM / µ-law 的 WAV）。
 * 两条路径的收尾（下混、截断、重采样、过短检查）共用 [SstvAudioConvert]，
 * 返回值也共用 [SstvWavReader.Outcome]，因此调用方无需区分数据来自哪条路。
 *
 * **两条解码通路**：
 * 1. `audio/raw` 轨道（WAV 常见）：轨道里本来就是 PCM，用
 *    [MediaExtractor.readSampleData] 直接取，**不需要 codec**（少一层拷贝与状态机）；
 * 2. 其余 mime：`MediaCodec.createDecoderByType` 建解码器，喂压缩帧、收 PCM。
 *
 * **几处刻意为之的取舍**：
 *  - **采样率/声道数以*输出*格式为准**，不假定等于输入：解码器完全有权重采样或改变
 *    声道数，用输入格式去解释输出字节就是「把 44.1 kHz 的数据当 48 kHz 用」，
 *    图像会整体水平拉伸。
 *  - **输出缓冲用「设定 position/limit 后整体拷出」再解码**，而不是在
 *    `ByteBuffer` 上做绝对读取：`BufferInfo.offset` 不保证为 0，先按 offset/size
 *    圈定范围才能保证不漏不错；而且拷进 `ByteArray` 之后字节序完全由
 *    [SstvAudioConvert] 的小端读取原语决定，不必再操心 `ByteBuffer.order`。
 *  - **边解边累加、到 [SstvWavReader.MAX_SECONDS] 就停**（[SstvAudioConvert.MonoAccumulator]）：
 *    若先解完再截断，一段 1 小时的 FLAC 会先在内存里撑出 690 MB 才被裁掉。
 *  - **只取第一条音频轨道**：SSTV 录音不会有第二条有用的音轨，取第一条最可预测。
 *
 * **⚠️ 验证状态：Android 层仅编译验证，未做真机解码验证。**
 * `MediaCodec` / `MediaExtractor` 在 JVM 单元测试里只会抛 "not mocked"，因此本类的
 * 解码循环没有自动化测试覆盖；能单测的部分（PCM 编码映射、格式校验、累加封顶）
 * 全部下沉到了 [SstvAudioConvert] 与下面标了注释的 `internal` 纯函数里。
 * 首次上机验证建议按此顺序：16 位 WAV（走已单测的 [SstvWavReader]）→ 用同一段内容
 * 转成 FLAC/MP3 → 比较两次出图是否一致（不一致说明解码通路有问题，而不是算法有问题）。
 *
 * @see SstvWavReader
 * @see SstvAudioConvert
 */
object SstvMediaAudioReader {

    /**
     * 读取一个音频文件（任意平台可解析的容器）。
     *
     * 与 [SstvWavReader.read] 返回同一种结果类型，失败原因同样可直接展示给用户。
     *
     * @param file 已落在本地磁盘上的文件（[android.content.ContentResolver] 的流由调用方先落盘）
     * @param targetSampleRate 目标采样率，默认 48 kHz
     */
    fun read(
        file: File,
        targetSampleRate: Int = SstvWavReader.TARGET_SAMPLE_RATE,
    ): SstvWavReader.Outcome {
        if (!file.isFile || file.length() <= 0L) {
            return SstvWavReader.Outcome.Failure("音频文件不存在或为空")
        }

        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                return SstvWavReader.Outcome.Failure(
                    "无法解析音频容器：${e.message ?: "平台不支持该格式"}",
                )
            }

            val trackCount = runCatching { extractor.trackCount }.getOrDefault(0)
            val trackIndex = (0 until trackCount).firstOrNull { index ->
                val mime = runCatching {
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                }.getOrNull()
                mime != null && mime.startsWith("audio/")
            } ?: return SstvWavReader.Outcome.Failure("文件中没有音频轨道")

            extractor.selectTrack(trackIndex)
            val trackFormat = runCatching { extractor.getTrackFormat(trackIndex) }.getOrNull()
                ?: return SstvWavReader.Outcome.Failure("无法读取音频轨道信息")
            val mime = runCatching { trackFormat.getString(MediaFormat.KEY_MIME) }.getOrNull()
                ?: return SstvWavReader.Outcome.Failure("音频轨道未声明编码格式")

            return if (mime == MIME_AUDIO_RAW) {
                readRawTrack(extractor, trackFormat, targetSampleRate)
            } else {
                readCompressedTrack(extractor, trackFormat, mime, targetSampleRate)
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 通路一：audio/raw（轨道里已经是 PCM，无需 codec）
    // ══════════════════════════════════════════════════════════════════

    private fun readRawTrack(
        extractor: MediaExtractor,
        trackFormat: MediaFormat,
        targetSampleRate: Int,
    ): SstvWavReader.Outcome {
        val sampleRate = trackFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 0)
        val channels = trackFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 0)
        val pcmEncoding = pcmEncodingOf(trackFormat)
        val encoding = encodingFor(pcmEncoding)
        validateFormat(sampleRate, channels, encoding)?.let { return SstvWavReader.Outcome.Failure(it) }
        val resolved = encoding
            ?: return SstvWavReader.Outcome.Failure(unsupportedEncodingReason(pcmEncoding))
        val format = SstvAudioConvert.Format(resolved, channels, sampleRate)
        val accumulator = SstvAudioConvert.MonoAccumulator(sampleRate)

        var capacity = initialRawCapacity(trackFormat)
        var buffer = ByteBuffer.allocateDirect(capacity)
        var scratch = ByteArray(0)
        var stoppedAtLimit = false

        while (true) {
            if (capacity > buffer.capacity()) buffer = ByteBuffer.allocateDirect(capacity)

            val size = runCatching { extractor.readSampleData(buffer, 0) }.getOrElse { e ->
                return SstvWavReader.Outcome.Failure("读取音频数据失败：${e.message ?: "提取器错误"}")
            }
            if (size < 0) break

            if (size >= capacity) {
                // 缓冲区装不下这一整个样本。没有 advance()，样本还在原地，扩容后重读即可。
                // 用「返回长度 == 容量」而不是「抛异常」来判断，是因为不同提取器在
                // 缓冲区不足时的表现并不统一（有的截断后照常返回，有的直接返回 −1）
                if (capacity >= MAX_RAW_BUFFER_BYTES) {
                    return SstvWavReader.Outcome.Failure("单个音频样本过大（$size 字节），无法读取")
                }
                capacity = (capacity * 4).coerceAtMost(MAX_RAW_BUFFER_BYTES)
                continue
            }

            if (scratch.size < size) scratch = ByteArray(size)
            // readSampleData(offset = 0) 一定把数据写在缓冲区的 0 号位置，
            // 但框架会把 position/limit 挪到 [offset, offset+size)，因此显式复位
            buffer.position(0)
            buffer.get(scratch, 0, size)

            val block = SstvAudioConvert.decodeMono(scratch, 0, size, format)
            if (!accumulator.append(block)) {
                stoppedAtLimit = true
                break
            }
            if (!extractor.advance()) break
        }

        return SstvAudioConvert.finish(
            mono = accumulator.toFloatArray(),
            format = format,
            targetSampleRate = targetSampleRate,
            sourceTruncated = accumulator.truncated || stoppedAtLimit,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 通路二：压缩音频（走 MediaCodec）
    // ══════════════════════════════════════════════════════════════════

    private fun readCompressedTrack(
        extractor: MediaExtractor,
        trackFormat: MediaFormat,
        mime: String,
        targetSampleRate: Int,
    ): SstvWavReader.Outcome {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            return SstvWavReader.Outcome.Failure(
                "设备缺少 $mime 解码器：${e.message ?: "系统未提供该编码的解码器"}",
            )
        }

        var scratch = ByteArray(0)
        val info = MediaCodec.BufferInfo()

        try {
            try {
                codec.configure(trackFormat, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                return SstvWavReader.Outcome.Failure(
                    "解码器初始化失败：${e.message ?: "不支持的编码参数"}",
                )
            }

            // 输出格式必须在拿到第一块数据之前先读一次：解码器可以在 configure 之后
            // 就确定输出布局，也可能等到第一块输出才通过 INFO_OUTPUT_FORMAT_CHANGED
            // 通知，两处都要处理
            var layout = when (val initial = layoutOf(codec.outputFormat)) {
                is LayoutResult.Bad -> return SstvWavReader.Outcome.Failure(initial.reason)
                is LayoutResult.Ok -> initial.layout
            }

            var inputDone = false
            var outputDone = false
            var idleRounds = 0
            var rounds = 0
            var stoppedAtLimit = false

            while (!outputDone) {
                // 双重保护：idleRounds 抓「完全没有进展」，rounds 是绝对兜底
                if (idleRounds > MAX_IDLE_ROUNDS || rounds++ > MAX_ROUNDS) {
                    return SstvWavReader.Outcome.Failure(
                        "解码超时（解码器长时间没有输出），文件可能损坏或编码不受支持",
                    )
                }

                // ── 喂输入 ──
                if (!inputDone) {
                    val inIndex = runCatching { codec.dequeueInputBuffer(TIMEOUT_US) }.getOrDefault(-1)
                    if (inIndex >= 0) {
                        val inputBuffer = runCatching { codec.getInputBuffer(inIndex) }.getOrNull()
                        val size = if (inputBuffer == null) {
                            -1
                        } else {
                            runCatching { extractor.readSampleData(inputBuffer, 0) }.getOrDefault(-1)
                        }
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        idleRounds = 0
                    } else {
                        idleRounds++
                    }
                }

                // ── 收输出 ──
                val outIndex = runCatching { codec.dequeueOutputBuffer(info, TIMEOUT_US) }.getOrDefault(-1)
                when {
                    outIndex >= 0 -> {
                        idleRounds = 0
                        val isCodecConfig =
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isCodecConfig && info.size > 0) {
                            val outBuffer = runCatching { codec.getOutputBuffer(outIndex) }.getOrNull()
                            if (outBuffer != null &&
                                info.offset >= 0 &&
                                info.offset + info.size <= outBuffer.capacity()
                            ) {
                                if (scratch.size < info.size) scratch = ByteArray(info.size)
                                // 先按 BufferInfo 圈定范围：offset 不保证为 0，
                                // 直接从头读会把上一块的数据当成这一块
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                outBuffer.get(scratch, 0, info.size)

                                val block = SstvAudioConvert.decodeMono(
                                    scratch, 0, info.size, layout.format,
                                )
                                if (!layout.accumulator.append(block)) stoppedAtLimit = true
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                            stoppedAtLimit
                        runCatching { codec.releaseOutputBuffer(outIndex, false) }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        when (val changed = layoutOf(codec.outputFormat)) {
                            is LayoutResult.Bad -> return SstvWavReader.Outcome.Failure(changed.reason)
                            is LayoutResult.Ok -> {
                                layout = if (
                                    changed.layout.format.sampleRate == layout.format.sampleRate
                                ) {
                                    // 采样率不变、编码或声道数变了（例如 16 位 → 浮点）：
                                    // 沿用同一个累加器，已解出的样本不作废
                                    OutputLayout(changed.layout.format, layout.accumulator)
                                } else {
                                    return SstvWavReader.Outcome.Failure(
                                        "解码器中途更换输出采样率" +
                                            "（${layout.format.sampleRate} → " +
                                            "${changed.layout.format.sampleRate} Hz），暂不支持",
                                    )
                                }
                            }
                        }
                        idleRounds = 0
                    }

                    else -> idleRounds++
                }
            }

            return SstvAudioConvert.finish(
                mono = layout.accumulator.toFloatArray(),
                format = layout.format,
                targetSampleRate = targetSampleRate,
                sourceTruncated = layout.accumulator.truncated || stoppedAtLimit,
            )
        } catch (e: Exception) {
            return SstvWavReader.Outcome.Failure("解码失败：${e.message ?: e::class.java.simpleName}")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 输出布局（纯逻辑，可在 JVM 单测中验证）
    // ══════════════════════════════════════════════════════════════════

    /** 一份可用的输出布局：PCM 布局 + 与之匹配的样本累加器。 */
    private class OutputLayout(
        val format: SstvAudioConvert.Format,
        val accumulator: SstvAudioConvert.MonoAccumulator,
    )

    private sealed interface LayoutResult {
        data class Ok(val layout: OutputLayout) : LayoutResult
        data class Bad(val reason: String) : LayoutResult
    }

    private fun layoutOf(codecFormat: MediaFormat): LayoutResult {
        val sampleRate = codecFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 0)
        val channels = codecFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 0)
        val pcmEncoding = pcmEncodingOf(codecFormat)
        val encoding = encodingFor(pcmEncoding)
        validateFormat(sampleRate, channels, encoding)?.let { return LayoutResult.Bad(it) }
        val resolved = encoding ?: return LayoutResult.Bad(unsupportedEncodingReason(pcmEncoding))
        val format = SstvAudioConvert.Format(resolved, channels, sampleRate)
        return LayoutResult.Ok(OutputLayout(format, SstvAudioConvert.MonoAccumulator(sampleRate)))
    }

    /**
     * `AudioFormat` 的 PCM 编码 → 共享层的采样编码；无法转换时返回 null。
     *
     * 刻意**写字面量而不是引用 `AudioFormat.ENCODING_PCM_*`**：这些是编译期常量，
     * 值被 `AudioFormat` 的 ABI 钉死（2/3/4/21/22），写字面量既让本函数成为
     * 纯函数、可以在 JVM 单测里直接验证（引用 Android 常量在单测里虽然也能内联，
     * 但一旦哪天不再是编译期常量就会变成 "not mocked" 运行时炸弹），
     * 也避开了 lint 对 API 31 常量 `ENCODING_PCM_24BIT_PACKED` / `ENCODING_PCM_32BIT`
     * 在 minSdk 26 上的 NewApi 误报。取值由 `SstvAudioConvertTest` 固定。
     */
    internal fun encodingFor(pcmEncoding: Int): SstvAudioConvert.Encoding? = when (pcmEncoding) {
        // AudioFormat.ENCODING_PCM_16BIT
        ENCODING_PCM_16BIT -> SstvAudioConvert.Encoding.SIGNED_16
        // AudioFormat.ENCODING_PCM_8BIT（Android 与 WAV 一样按**无符号**解释）
        ENCODING_PCM_8BIT -> SstvAudioConvert.Encoding.UNSIGNED_8
        // AudioFormat.ENCODING_PCM_FLOAT（API 21+）
        ENCODING_PCM_FLOAT -> SstvAudioConvert.Encoding.FLOAT_32
        // AudioFormat.ENCODING_PCM_24BIT_PACKED（API 31，3 字节打包，有符号小端）
        ENCODING_PCM_24BIT_PACKED -> SstvAudioConvert.Encoding.SIGNED_24
        // AudioFormat.ENCODING_PCM_32BIT（API 31，有符号小端）
        ENCODING_PCM_32BIT -> SstvAudioConvert.Encoding.SIGNED_32
        else -> null
    }

    /**
     * 校验输出布局，返回 null 表示可用，否则是可直接展示给用户的原因。
     *
     * 采样率不假定 48 kHz：解码器可以输出 8 kHz（窄带语音编码）到 192 kHz
     * （高解析 FLAC）之间的任何值，全部按原值交给 [SstvAudioConvert] 重采样。
     * 超出这个范围的值一律判为非法而不是硬着头皮解 —— 那样的字节流几乎肯定是
     * 我们把 `MediaFormat` 读错了（例如把声道数当成了采样率），继续解只会得到噪声。
     */
    internal fun validateFormat(
        sampleRate: Int,
        channels: Int,
        encoding: SstvAudioConvert.Encoding?,
    ): String? = when {
        encoding == null -> "解码器输出的 PCM 编码不受支持（无法转换为单声道浮点）"
        channels <= 0 -> "声道数非法（$channels）"
        channels > SstvAudioConvert.MAX_CHANNELS ->
            "声道数过多（$channels，最多支持 ${SstvAudioConvert.MAX_CHANNELS}）"
        sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE ->
            "采样率超出支持范围（$sampleRate Hz，支持 $MIN_SAMPLE_RATE–$MAX_SAMPLE_RATE Hz）"
        else -> null
    }

    private fun unsupportedEncodingReason(pcmEncoding: Int): String =
        "解码器输出的 PCM 编码不受支持（encoding=$pcmEncoding）"

    // ══════════════════════════════════════════════════════════════════
    // MediaFormat 辅助
    // ══════════════════════════════════════════════════════════════════

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    /**
     * 取轨道的 PCM 编码。
     *
     * `KEY_PCM_ENCODING` 缺失是**正常**情况（很多 WAV 根本不写），此时按 16 位处理 ——
     * 这是 WAV 与绝大多数 raw 轨道的事实标准。
     */
    private fun pcmEncodingOf(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                .getOrDefault(ENCODING_PCM_16BIT)
        } else {
            ENCODING_PCM_16BIT
        }

    private fun initialRawCapacity(format: MediaFormat): Int {
        val declared = format.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
        return if (declared in 1..MAX_RAW_BUFFER_BYTES) {
            declared.coerceAtLeast(DEFAULT_RAW_BUFFER_BYTES)
        } else {
            DEFAULT_RAW_BUFFER_BYTES
        }
    }

    /** `MediaFormat` 里 raw PCM 轨道的 mime。 */
    private const val MIME_AUDIO_RAW = "audio/raw"

    // AudioFormat 的 PCM 编码常量（值是 ABI 冻结的编译期常量，此处写字面量，理由见 encodingFor）
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_8BIT = 3
    private const val ENCODING_PCM_FLOAT = 4
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    /** 采样率支持范围（Hz）。 */
    private const val MIN_SAMPLE_RATE = 8
    private const val MAX_SAMPLE_RATE = 192_000

    /** 每次 dequeue 的等待时间（微秒）。 */
    private const val TIMEOUT_US = 10_000L

    /** 连续多少轮毫无进展就判为超时（≈ 5 秒）。 */
    private const val MAX_IDLE_ROUNDS = 500

    /** 循环轮数绝对上限（兜底，正常文件远达不到）。 */
    private const val MAX_ROUNDS = 2_000_000

    /** raw 通路的初始缓冲区；不足时按 4 倍扩容。 */
    private const val DEFAULT_RAW_BUFFER_BYTES = 64 * 1024

    /** raw 通路单个样本的缓冲区上限。 */
    private const val MAX_RAW_BUFFER_BYTES = 8 * 1024 * 1024
}
