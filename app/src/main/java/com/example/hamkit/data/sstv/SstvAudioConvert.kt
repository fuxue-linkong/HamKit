package com.example.hamkit.data.sstv

/**
 * PCM 字节流 → 48 kHz 单声道浮点的**共享**转换层。
 *
 * **为什么必须抽出一层**：[SstvWavReader]（纯 Kotlin 自解析 RIFF）与
 * [SstvMediaAudioReader]（平台 MediaExtractor/MediaCodec）拿到的原始数据长相完全不同
 * —— 一个是文件里连续的 `data` 块，另一个是解码器按缓冲区吐出的碎片 —— 但**收尾工作
 * 必须一模一样**：位级归一化、下混、按原始采样率截断到 [MAX_SECONDS]、
 * 线性插值重采样到 [TARGET_SAMPLE_RATE]、过短检查，以及失败原因的措辞。
 *
 * 两条路径各写一份的话，最容易出问题的恰恰是最不容易被发现的地方：
 *  - 8 位 WAV 是**无符号**（128 为零）而 16/24/32 位是有符号，写错就是整段直流偏置；
 *  - 24 位要手动符号扩展（符号位在第三个字节），漏了就变成 0…8.4M 的单极性；
 *  - 截断必须发生在**重采样之前**并按**原始**采样率换算，否则 8 kHz 的录音会被
 *    当成 48 kHz 少截 6 倍；
 *  - 线性插值的边界夹紧（`coerceIn`）决定了末尾几个样本会不会被拉成 0。
 * 所以这些只实现一次。
 *
 * 纯 Kotlin（不含任何 Android API），可直接在 JVM 单测中验证 —— 这也是把
 * [SstvMediaAudioReader] 里唯一能单测的部分（格式映射、校验、累加封顶）放到这里的理由。
 *
 * @see SstvWavReader
 * @see SstvMediaAudioReader
 */
object SstvAudioConvert {

    /** 解码链路唯一的工作采样率（理由见 docs/REQUIREMENT_SSTV.md §4.2）。 */
    const val TARGET_SAMPLE_RATE: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE

    /**
     * 单次读取的最长时长（秒）。
     *
     * 与 [SstvRecorder] 的单帧上限同源：300 秒足以容纳最长的 PD-290（289 s），
     * 同时把「一段 1 小时的录音」这类误选挡在内存之外（48 kHz 单声道 float
     * 每小时约 690 MB）。
     */
    const val MAX_SECONDS: Double = 300.0

    /** 允许下混的最大声道数（超出直接拒绝，避免读到 5.1/7.1 素材时语义含混）。 */
    const val MAX_CHANNELS: Int = 8

    /**
     * PCM 采样编码。
     *
     * 覆盖 WAV 与 Android `AudioFormat` 两侧的全部编码：WAV 的 8 位是**无符号**，
     * Android 的 `ENCODING_PCM_8BIT` 同样是**无符号**（两边的 128 都表示零），
     * 因此可以共用同一个分支。
     */
    enum class Encoding(val bytesPerSample: Int) {
        /** 8 位无符号（WAV 与 `ENCODING_PCM_8BIT` 都是这个语义）。 */
        UNSIGNED_8(1),

        /** 16 位有符号小端。 */
        SIGNED_16(2),

        /** 24 位有符号小端（打包成 3 字节，需要手动符号扩展）。 */
        SIGNED_24(3),

        /** 32 位有符号小端。 */
        SIGNED_32(4),

        /** 32 位 IEEE 浮点小端。 */
        FLOAT_32(4),

        /** 64 位 IEEE 双精度小端（WAV 里少见，但标准允许）。 */
        FLOAT_64(8),
    }

    /**
     * 一种 PCM 布局：采样编码 + 声道数 + 采样率。
     *
     * 刻意把三者打包成一个不可变值：解码器可以在中途用
     * `INFO_OUTPUT_FORMAT_CHANGED` 换输出格式，调用方只需要换一个 [Format] 实例，
     * 不必在解码循环里散落三个可变字段。
     */
    data class Format(
        val encoding: Encoding,
        val channels: Int,
        val sampleRate: Int,
    ) {
        /** 单个样本的字节数。 */
        val bytesPerSample: Int get() = encoding.bytesPerSample

        /** 一帧（含全部声道）的字节数。 */
        val frameBytes: Int get() = bytesPerSample * channels

        /** 位深（用于向用户交代「原始位深」）。 */
        val bitsPerSample: Int get() = bytesPerSample * 8
    }

    /** 按原始采样率截断后的结果。 */
    data class Limited(val samples: FloatArray, val truncated: Boolean)

    // ══════════════════════════════════════════════════════════════════
    // 采样解码
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把一块 PCM 字节解码并下混为单声道浮点（**不重采样、不截断**）。
     *
     * 末尾不足一帧的残缺字节被丢弃（`frames = usable / frameBytes`）：解码器在
     * 缓冲区边界上切开的帧由下一次调用补齐，而在最后一块上留下的半帧本来就是
     * 无效数据，硬凑一帧会凭空造出一个样本。
     *
     * `length` 超过实际可用字节数时按可用长度处理 —— 上游（WAV 的块长度字段、
     * MediaCodec 的 `info.size`）都可能给出比缓冲区更大的值，这里兜住不让它越界。
     *
     * @param bytes 原始字节
     * @param offset 数据起点在 [bytes] 中的下标（MediaCodec 的 `info.offset` 不为 0 时很关键）
     * @param length 可用字节数
     * @param format PCM 布局
     * @return 长度 = `min(length, 可用) / frameBytes` 的单声道样本
     */
    fun decodeMono(bytes: ByteArray, offset: Int, length: Int, format: Format): FloatArray {
        val frameBytes = format.frameBytes
        if (frameBytes <= 0 || format.channels <= 0) return FloatArray(0)
        val available = (bytes.size - offset).coerceAtLeast(0)
        val usable = minOf(length, available)
        val frames = usable / frameBytes
        if (frames <= 0) return FloatArray(0)

        val channels = format.channels
        val sampleBytes = format.bytesPerSample
        val encoding = format.encoding
        val out = FloatArray(frames)
        val invChannels = 1.0f / channels
        for (frame in 0 until frames) {
            var sum = 0.0f
            val frameStart = offset + frame * frameBytes
            for (c in 0 until channels) {
                sum += sampleAt(bytes, frameStart + c * sampleBytes, encoding)
            }
            // 下混后再钳制：单个声道已是 ±1.0 时，多声道之和仍可能溢出
            out[frame] = (sum * invChannels).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    /** 单个样本 → ±1.0 浮点。**累加刻意用 Float**，与既有路径逐位一致。 */
    private fun sampleAt(bytes: ByteArray, at: Int, encoding: Encoding): Float = when (encoding) {
        // 8 位无符号：128 为零点，除以 128 而不是 127（0…255 → −1.0…+0.992）
        Encoding.UNSIGNED_8 -> (bytes[at].toInt() and 0xFF) / 128.0f - 1.0f
        Encoding.SIGNED_16 -> readShortLE(bytes, at) / 32768.0f
        Encoding.SIGNED_24 -> readInt24LE(bytes, at) / 8388608.0f
        Encoding.SIGNED_32 -> readIntLE(bytes, at) / 2147483648.0f
        Encoding.FLOAT_32 -> Float.fromBits(readIntLE(bytes, at))
        Encoding.FLOAT_64 -> Double.fromBits(readLongLE(bytes, at)).toFloat()
    }

    // ══════════════════════════════════════════════════════════════════
    // 收尾：截断 → 重采样 → 过短检查
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把任意长度的单声道样本收尾成解码器可用的输入。
     *
     * 顺序**不可调换**：先按原始采样率截断，再重采样，最后查过短。若先重采样，
     * 8 kHz 的录音会先被插值成 6 倍长度再按 48 kHz 截断 —— 实际裁掉的时长变成
     * 50 秒而不是 300 秒，用户的录音会被莫名其妙切掉大半。
     *
     * @param mono 单声道样本（幅度约 ±1.0），来自 [decodeMono] 的分块拼接或一次性解码
     * @param format 原始 PCM 布局（其中的采样率决定截断阈值）
     * @param targetSampleRate 目标采样率
     * @param sourceTruncated 上游**已经**因为达到上限而主动停止读取时传 true。
     *   [SstvMediaAudioReader] 用 [MonoAccumulator] 边解边丢弃，样本数正好等于上限，
     *   仅靠长度判断会漏报截断，必须由它显式告知
     */
    fun finish(
        mono: FloatArray,
        format: Format,
        targetSampleRate: Int = TARGET_SAMPLE_RATE,
        sourceTruncated: Boolean = false,
    ): SstvWavReader.Outcome {
        if (mono.isEmpty()) return SstvWavReader.Outcome.Failure("音频数据为空")

        val sourceRate = format.sampleRate
        if (sourceRate <= 0 || targetSampleRate <= 0) {
            return SstvWavReader.Outcome.Failure("采样率非法（$sourceRate Hz）")
        }

        val limited = limitToMaxSeconds(mono, sourceRate)
        val resampled = if (sourceRate == targetSampleRate) {
            limited.samples
        } else {
            resampleLinear(limited.samples, sourceRate, targetSampleRate)
        }

        // 0.5 秒是解码器的硬性下限（见 SstvDecoder.decode），提前给出更友好的提示。
        // 阈值刻意写成整数除 `targetSampleRate / 2`（与既有实现一致），不要改成
        // 0.5 * targetSampleRate：目标采样率一定是 48 kHz，两者等价，但改了会让
        // 这个文件与一期行为出现无意义的差异
        if (resampled.size < targetSampleRate / 2) {
            val seconds = "%.2f".format(resampled.size.toDouble() / targetSampleRate)
            return SstvWavReader.Outcome.Failure("音频过短（需至少 0.5 秒，实际 $seconds 秒）")
        }

        return SstvWavReader.Outcome.Success(
            samples = resampled,
            sourceSampleRate = sourceRate,
            sourceChannels = format.channels,
            bitsPerSample = format.bitsPerSample,
            truncated = limited.truncated || sourceTruncated,
        )
    }

    /**
     * 按**原始**采样率截断到 [MAX_SECONDS]。
     *
     * 未超限时直接返回入参（不复制）：WAV 路径上这一步的温度是零拷贝，
     * 而重采样本身就会产出新数组，没必要白白多一份几十 MB 的副本。
     */
    fun limitToMaxSeconds(mono: FloatArray, sourceRate: Int): Limited {
        if (sourceRate <= 0) return Limited(mono, false)
        val maxSourceSamples = (MAX_SECONDS * sourceRate).toInt()
        if (mono.size <= maxSourceSamples) return Limited(mono, false)
        return Limited(mono.copyOf(maxSourceSamples), true)
    }

    /**
     * 线性插值重采样。
     *
     * 刻意不用「抽取/补零」这类会引入混叠的做法：SSTV 关心的是**瞬时频率的时间轴**，
     * 线性插值虽然频响一般，但对 1500–2300 Hz 的窄带信号足够，且不会引入额外相位抖动。
     *
     * 越界下标用 `coerceIn` 夹到首尾样本，而不是补 0：补 0 会在录音末尾制造一段
     * 人为的「黑电平突变」，解码器会把最后几行画歪。
     */
    fun resampleLinear(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (input.isEmpty()) return input
        if (sourceRate <= 0 || targetRate <= 0 || sourceRate == targetRate) return input
        val ratio = sourceRate.toDouble() / targetRate
        val outLength = (input.size.toDouble() / ratio).toInt()
        if (outLength <= 0) return FloatArray(0)
        val out = FloatArray(outLength)
        for (i in 0 until outLength) {
            val position = i * ratio
            val index = position.toInt()
            val frac = (position - index).toFloat()
            val a = input[index.coerceIn(0, input.size - 1)]
            val b = input[(index + 1).coerceIn(0, input.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    // ══════════════════════════════════════════════════════════════════
    // 流式累加（MediaCodec 按缓冲区吐数据时用）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 单声道样本累加器：边解边拼，并**在达到 [MAX_SECONDS] 上限时立刻停手**。
     *
     * **为什么不能先解完再截断**：压缩音频（FLAC/MP3）的时长上限只受文件大小约束，
     * MediaCodec 又是一块一块吐数据的。若先拼完再交给 [finish] 截断，一段 1 小时的
     * FLAC 会在内存里先撑出 690 MB 的 FloatArray 才被裁掉 —— 这正是「导入录音」
     * 最容易 OOM 的路径。这里在累加阶段就丢弃超限样本，峰值内存恒等于上限本身。
     *
     * **截断标记的保守方向**：停止读取时无法区分「恰好 [MAX_SECONDS] 秒」与
     * 「后面还有更多」，因此只要触到上限就标记 `truncated`。宁可在恰好 300 秒的
     * 录音上多提示一句「已截断」，也不要让用户以为拿到了完整录音。
     */
    class MonoAccumulator(
        private val sourceRate: Int,
        maxSeconds: Double = MAX_SECONDS,
        initialCapacity: Int = 1 shl 16,
    ) {

        private var buffer = FloatArray(initialCapacity.coerceAtLeast(1024))
        private var size = 0

        /** 上限对应的样本数；无法确定上限（采样率非法）时按「不限」处理。 */
        private val limit: Int =
            if (maxSeconds > 0.0 && sourceRate > 0) (maxSeconds * sourceRate).toInt() else Int.MAX_VALUE

        /** 是否已经丢弃过样本、或触到了时长上限。 */
        var truncated: Boolean = false
            private set

        /** 已累积的样本数。 */
        val sampleCount: Int get() = size

        /** 是否已到达时长上限（调用方据此停止解码）。 */
        val limitReached: Boolean get() = size >= limit

        /**
         * 追加一块单声道样本。
         *
         * @return true 表示还可以继续追加；false 表示已达上限，调用方应停止解码
         */
        fun append(block: FloatArray): Boolean {
            if (block.isEmpty()) return !limitReached
            val remaining = limit - size
            if (remaining <= 0) {
                truncated = true
                return false
            }
            val take = minOf(block.size, remaining)
            ensureCapacity(size + take)
            System.arraycopy(block, 0, buffer, size, take)
            size += take
            if (size >= limit) {
                // 触到上限就**保守地**标记截断：此刻无法区分「文件正好 300 秒」与
                // 「后面还有更多」，宁可多提示一句，也不要让用户以为拿到了完整录音
                truncated = true
            }
            return size < limit
        }

        /** 取走已累积的样本（复制一份，累加器仍可继续使用）。 */
        fun toFloatArray(): FloatArray = buffer.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= buffer.size) return
            var newSize = buffer.size
            while (newSize < required) newSize = newSize shl 1
            buffer = buffer.copyOf(newSize)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 小端读取原语
    //
    // 供 WAV 块解析与 PCM 采样解码共用。刻意只留一份实现：位级读取一旦在两条
    // 导入路径上分叉，出现的偏差（少读一个字节、符号扩展写错）就是最难排查的那类
    // bug，而它只会在某一种具体格式的文件上暴露。
    // ══════════════════════════════════════════════════════════════════

    /** 读 16 位有符号小端；越界返回 0（上游长度字段不可信时兜底）。 */
    internal fun readShortLE(bytes: ByteArray, offset: Int): Short {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    /** 读 32 位有符号小端；越界返回 0。 */
    internal fun readIntLE(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** 读 64 位有符号小端；越界返回 0。 */
    internal fun readLongLE(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 8 > bytes.size) return 0L
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    /**
     * 读 24 位有符号小端（符号位在第三个字节）。
     *
     * 必须手动做符号扩展：只把三个字节拼成 Int 会得到 0…16777215 的无符号值，
     * 所有负样本都变成正的大数 —— 表现为整幅图像被削掉一半动态范围、且始终偏亮。
     */
    internal fun readInt24LE(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 > bytes.size) return 0
        var value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
        if (value and 0x800000 != 0) value = value or 0xFF000000.toInt()
        return value
    }
}
