package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 共享转换层（[SstvAudioConvert]）与媒体读取器纯逻辑（[SstvMediaAudioReader]）的单元测试。
 *
 * **为什么这些用例值得写**：所有「导入录音」的素材都要经过 [SstvAudioConvert]，
 * 而它最容易错的地方恰好是**不会抛异常**的地方 —— 8 位的无符号偏移、24 位的符号扩展、
 * 截断与重采样的先后顺序、多声道下混的除数。这些写错以后程序照常运行，只是出图偏色、
 * 偏亮或时长被裁掉一半，单靠肉眼很难定位。所以这里对每一种编码都从**已知的浮点值**
 * 反推字节，再断言解回来还是那个值。
 *
 * [SstvMediaAudioReader] 的 `MediaCodec` 解码循环在 JVM 里跑不了（Android 桩会抛
 * "not mocked"），因此只测它**纯函数**的那部分：PCM 编码映射与格式校验。
 * 解码循环本身的状态见该类的 KDoc。
 */
class SstvAudioConvertTest {

    private val format48kMono = SstvAudioConvert.Format(
        SstvAudioConvert.Encoding.SIGNED_16,
        channels = 1,
        sampleRate = 48_000,
    )

    // ══════════════════════════════════════════════════════════════════
    // 位级解码：每种编码都从已知浮点值反推字节再解回来
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `全部编码的零与正负峰值都能正确还原`() {
        // 8 位无符号的量化台阶是 1/128，容差取两倍；其余编码的往返误差都远小于此
        val tolerance = 2.0f / 128.0f
        for (encoding in SstvAudioConvert.Encoding.entries) {
            val bytes = packSamples(encoding, listOf(0.0, 1.0, -1.0))
            val decoded = SstvAudioConvert.decodeMono(
                bytes, 0, bytes.size,
                SstvAudioConvert.Format(encoding, channels = 1, sampleRate = 48_000),
            )
            assertEquals("${encoding} 应解出 3 个样本", 3, decoded.size)
            assertEquals("${encoding} 的零点应为 0", 0.0f, decoded[0], 1e-6f)
            assertEquals("${encoding} 的正峰值应接近 +1", 1.0f, decoded[1], tolerance)
            assertEquals("${encoding} 的负峰值应接近 −1", -1.0f, decoded[2], tolerance)
            assertTrue(
                "${encoding} 的负峰值必须为负（符号扩展写错就会变成正的大数）",
                decoded[2] < 0f,
            )
        }
    }

    @Test
    fun `8 位无符号以 128 为零点`() {
        // WAV 与 Android 的 ENCODING_PCM_8BIT 都是无符号：字节 128 才是零。
        // 若按有符号解释（Java 的 AudioFormat 习惯），整段会有 −1.0 的直流偏置
        val bytes = byteArrayOf(0, 128.toByte(), 255.toByte())
        val decoded = SstvAudioConvert.decodeMono(
            bytes, 0, 3,
            SstvAudioConvert.Format(SstvAudioConvert.Encoding.UNSIGNED_8, 1, 48_000),
        )
        assertEquals("字节 0 应为 −1.0", -1.0f, decoded[0], 1e-6f)
        assertEquals("字节 128 应为 0.0", 0.0f, decoded[1], 1e-6f)
        // 255/128 − 1 = 0.9921875：刻意除以 128 而不是 127
        assertEquals("字节 255 应为 +0.9922", 0.9921875f, decoded[2], 1e-6f)
    }

    @Test
    fun `24 位负值做符号扩展`() {
        // 0x800000 是 24 位能表示的最小负数（−8388608）。若漏掉符号扩展，
        // 它会变成 +8388608，所有负样本都挤到正半边
        val bytes = byteArrayOf(
            0x00, 0x00, 0x80.toByte(), // −8388608 → −1.0
            0xFF.toByte(), 0xFF.toByte(), 0x7F, // +8388607 → ≈ +1.0
        )
        val decoded = SstvAudioConvert.decodeMono(
            bytes, 0, 6,
            SstvAudioConvert.Format(SstvAudioConvert.Encoding.SIGNED_24, 1, 48_000),
        )
        assertEquals("最小负数应为 −1.0", -1.0f, decoded[0], 1e-6f)
        assertEquals("最大正数应为 ≈ +1.0", 1.0f, decoded[1], 1e-6f)
    }

    @Test
    fun `64 位浮点用双精度还原`() {
        // 64 位浮点必须走 readLongLE + Double.fromBits。若沿用 32 位的 4 字节路径，
        // 会把一个样本从中间切开，得到的是噪声
        val bytes = packSamples(SstvAudioConvert.Encoding.FLOAT_64, listOf(0.375, -0.375, 0.0))
        assertEquals("双精度样本占 8 字节", 24, bytes.size)
        val decoded = SstvAudioConvert.decodeMono(
            bytes, 0, bytes.size,
            SstvAudioConvert.Format(SstvAudioConvert.Encoding.FLOAT_64, 1, 48_000),
        )
        assertEquals(0.375f, decoded[0], 1e-6f)
        assertEquals(-0.375f, decoded[1], 1e-6f)
        assertEquals(0.0f, decoded[2], 1e-6f)
    }

    // ══════════════════════════════════════════════════════════════════
    // 偏移、长度与帧边界
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `offset 非零时从正确位置开始读`() {
        // MediaCodec 的 BufferInfo.offset 不保证为 0；若忽略它就会把上一块的数据
        // 当成这一块的开头，表现为周期性「咔哒」声与原图错位
        val filler = ByteArray(8) { 0x7F }
        val payload = packSamples(SstvAudioConvert.Encoding.SIGNED_16, listOf(0.5, -0.5))
        val bytes = filler + payload
        val decoded = SstvAudioConvert.decodeMono(
            bytes, offset = 8, length = payload.size,
            SstvAudioConvert.Format(SstvAudioConvert.Encoding.SIGNED_16, 1, 48_000),
        )
        assertEquals(2, decoded.size)
        // 容差取 1e-4：打包用 ×32767、解码用 ÷32768，两者之间天然有约 3e-5 的
        // 半 LSB 不对称（这是通行做法，不是缺陷），因此不能用 1e-6
        assertEquals(0.5f, decoded[0], 1e-4f)
        assertEquals(-0.5f, decoded[1], 1e-4f)
    }

    @Test
    fun `末尾残缺帧被丢弃而不是凑成一帧`() {
        // 解码器在缓冲区边界切开的帧由下一块补齐；最后一块剩下的 1 个字节是无效数据，
        // 硬凑一帧会凭空造出一个样本（听感上是末尾的爆音，图像上是最后一行错位）
        val bytes = packSamples(SstvAudioConvert.Encoding.SIGNED_16, listOf(0.25, 0.25)) + byteArrayOf(0x11)
        val decoded = SstvAudioConvert.decodeMono(
            bytes, 0, bytes.size,
            SstvAudioConvert.Format(SstvAudioConvert.Encoding.SIGNED_16, 1, 48_000),
        )
        assertEquals("5 字节的 16 位单声道只能解出 2 帧", 2, decoded.size)
    }

    @Test
    fun `length 超过可用字节时按可用长度处理`() {
        // WAV 的块长度字段与 MediaCodec 的 info.size 都可能报得比缓冲区大，
        // 直接按它去读会抛越界异常，把整次导入变成「解码失败」
        val bytes = packSamples(SstvAudioConvert.Encoding.SIGNED_16, listOf(0.1, 0.2))
        val decoded = SstvAudioConvert.decodeMono(
            bytes, 0, length = 4096,
            SstvAudioConvert.Format(SstvAudioConvert.Encoding.SIGNED_16, 1, 48_000),
        )
        assertEquals("应按实际可用的 4 字节解出 2 帧", 2, decoded.size)
    }

    @Test
    fun `多声道下混为均值而不是取第一路`() {
        // 立体声里 SSTV 常常只在其中一路（或两路反相），取第一路会丢掉一半信号
        val format = SstvAudioConvert.Format(SstvAudioConvert.Encoding.SIGNED_16, 2, 48_000)
        val interleaved = packInterleaved(
            SstvAudioConvert.Encoding.SIGNED_16,
            listOf(listOf(0.5f, 0.5f), listOf(-0.5f, 0.5f), listOf(0.0f, 1.0f)),
        )
        val decoded = SstvAudioConvert.decodeMono(interleaved, 0, interleaved.size, format)
        assertEquals(3, decoded.size)
        // 容差 1e-4 的理由同 `offset 非零时从正确位置开始读`（×32767 打包 / ÷32768 解码）
        assertEquals("两路同相时均值等于原值", 0.5f, decoded[0], 1e-4f)
        assertEquals("一路正一路负应相消为 0", 0.0f, decoded[1], 1e-4f)
        assertEquals("一路 0 一路 1 应为 0.5", 0.5f, decoded[2], 1e-4f)
    }

    @Test
    fun `声道数为零或非法时返回空数组而不是崩溃`() {
        val bytes = packSamples(SstvAudioConvert.Encoding.SIGNED_16, listOf(0.5))
        val decoded = SstvAudioConvert.decodeMono(
            bytes, 0, bytes.size,
            SstvAudioConvert.Format(SstvAudioConvert.Encoding.SIGNED_16, channels = 0, sampleRate = 48_000),
        )
        assertEquals("声道数为 0 时应返回空而不是除零", 0, decoded.size)
    }

    // ══════════════════════════════════════════════════════════════════
    // 截断与重采样
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `截断阈值按原始采样率换算`() {
        // 8 kHz 与 48 kHz 的「300 秒」相差 6 倍。若错用固定样本数，
        // 8 kHz 的录音会被裁成 50 秒，或 48 kHz 的录音根本裁不掉
        val eightK = FloatArray((301 * 8_000)) { 0.1f }
        val limited8k = SstvAudioConvert.limitToMaxSeconds(eightK, 8_000)
        assertTrue("8 kHz 的 301 秒应被截断", limited8k.truncated)
        assertEquals("截断后应为 300 秒 × 8000", 300 * 8_000, limited8k.samples.size)

        val justEnough = FloatArray(300 * 48_000) { 0.1f }
        val notLimited = SstvAudioConvert.limitToMaxSeconds(justEnough, 48_000)
        assertTrue("恰好 300 秒不应标记截断", !notLimited.truncated)
        assertSame("未截断时应零拷贝返回入参", justEnough, notLimited.samples)
    }

    @Test
    fun `重采样保持时长且目标采样率下零拷贝`() {
        val seconds = 1.5
        val sourceRate = 8_000
        val input = FloatArray((seconds * sourceRate).roundToInt()) {
            sin(2.0 * PI * 1500.0 * it / sourceRate).toFloat()
        }
        val upsampled = SstvAudioConvert.resampleLinear(input, sourceRate, 48_000)
        assertEquals(
            "升采样后长度应约为 6 倍",
            (seconds * 48_000).roundToInt().toDouble(),
            upsampled.size.toDouble(),
            6.0,
        )
        // 波形仍是 1500 Hz：取过零点间距核对（不能用「值相等」，插值必然改变采样值）
        assertEquals("起点应接近 0", 0.0f, upsampled[0], 1e-3f)

        val same = SstvAudioConvert.resampleLinear(input, sourceRate, sourceRate)
        assertSame("源与目标采样率相同时应原样返回", input, same)
    }

    @Test
    fun `重采样越界处夹紧而不是补零`() {
        // 补零会在录音末尾制造一段人为的黑电平跳变，解码器会把最后几行画歪
        val input = FloatArray(10) { 0.8f }
        val out = SstvAudioConvert.resampleLinear(input, 8_000, 48_000)
        for (i in out.indices) {
            assertTrue("第 $i 个样本 ${out[i]} 不应被补零拉低", out[i] > 0.7f)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 收尾流程
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `收尾流程在空数据与过短时给出明确原因`() {
        val empty = SstvAudioConvert.finish(FloatArray(0), format48kMono)
        assertTrue(empty is SstvWavReader.Outcome.Failure)
        assertTrue(
            "空数据应报「音频数据为空」：${(empty as SstvWavReader.Outcome.Failure).reason}",
            empty.reason.contains("音频数据为空"),
        )

        val tooShort = SstvAudioConvert.finish(FloatArray(100) { 0.1f }, format48kMono)
        assertTrue(tooShort is SstvWavReader.Outcome.Failure)
        assertTrue(
            "过短应报「音频过短」：${(tooShort as SstvWavReader.Outcome.Failure).reason}",
            tooShort.reason.contains("过短"),
        )
    }

    @Test
    fun `收尾流程上报上游已经截断`() {
        // MediaCodec 通路边解边丢弃，样本数正好等于上限，仅靠长度判断会漏报截断
        val mono = FloatArray(48_000) { 0.2f }
        val outcome = SstvAudioConvert.finish(
            mono, format48kMono, sourceTruncated = true,
        )
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        assertTrue("上游已截断时必须如实上报", (outcome as SstvWavReader.Outcome.Success).truncated)
    }

    @Test
    fun `收尾流程的元信息如实反映原始格式`() {
        val format = SstvAudioConvert.Format(SstvAudioConvert.Encoding.SIGNED_24, 2, 44_100)
        val mono = FloatArray(44_100) { 0.3f }
        val outcome = SstvAudioConvert.finish(mono, format)
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        val success = outcome as SstvWavReader.Outcome.Success
        assertEquals(44_100, success.sourceSampleRate)
        assertEquals(2, success.sourceChannels)
        assertEquals(24, success.bitsPerSample)
        assertTrue("44.1 kHz 应被标记为已重采样", success.resampled)
        assertEquals("时长应保持 1 秒", 1.0, success.durationSeconds, 0.01)
    }

    // ══════════════════════════════════════════════════════════════════
    // 流式累加器（MediaCodec 按块吐数据时用）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `累加器跨块拼接保持顺序`() {
        val accumulator = SstvAudioConvert.MonoAccumulator(sourceRate = 48_000)
        assertTrue(accumulator.append(floatArrayOf(1f, 2f, 3f)))
        assertTrue(accumulator.append(floatArrayOf(4f, 5f)))
        assertEquals(5, accumulator.sampleCount)
        assertTrue("未超限时不应标记截断", !accumulator.truncated)
        assertTrue(
            "拼接顺序必须保持",
            accumulator.toFloatArray().contentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f)),
        )
    }

    @Test
    fun `累加器达到上限即停手并标记截断`() {
        // 1 秒 @ 8 kHz = 8000 样本上限
        val accumulator = SstvAudioConvert.MonoAccumulator(sourceRate = 8_000, maxSeconds = 1.0)
        // 契约：返回 true 表示「还可以继续追加」。第一块正好填满 → 返回 false（调用方应停止）
        assertTrue(
            "正好填满时就应报告「停止解码」",
            !accumulator.append(FloatArray(8_000) { 0.5f }),
        )
        assertTrue("填满后应报告已达上限", accumulator.limitReached)
        assertTrue("触到上限即保守标记截断", accumulator.truncated)
        assertEquals("不应多存一个样本", 8_000, accumulator.sampleCount)
        assertTrue(
            "再追加必须返回 false",
            !accumulator.append(FloatArray(4_000) { 0.5f }),
        )
        assertEquals("超出上限的样本必须被丢弃", 8_000, accumulator.sampleCount)
    }

    @Test
    fun `累加器未达上限时返回 true 且不标记截断`() {
        // 与上一条对偶：真正的短录音不能被误报为「已截断」，否则用户会以为素材不完整
        val accumulator = SstvAudioConvert.MonoAccumulator(sourceRate = 8_000, maxSeconds = 1.0)
        assertTrue("还有余量时应返回 true", accumulator.append(FloatArray(4_000) { 0.5f }))
        assertTrue(!accumulator.limitReached)
        assertTrue("尚未触到上限不应标记截断", !accumulator.truncated)
        assertEquals(4_000, accumulator.sampleCount)
    }

    @Test
    fun `累加器装不下的部分被丢弃并标记截断`() {
        val accumulator = SstvAudioConvert.MonoAccumulator(sourceRate = 8_000, maxSeconds = 1.0)
        assertTrue(accumulator.append(FloatArray(6_000) { 0.5f }))
        // 一块 6000 只能装下 2000：多余部分必须丢弃，并标记截断
        assertTrue(!accumulator.append(FloatArray(6_000) { 0.5f }))
        assertEquals(8_000, accumulator.sampleCount)
        assertTrue(accumulator.truncated)
        assertEquals("取走的结果长度应等于样本数", 8_000, accumulator.toFloatArray().size)
    }

    // ══════════════════════════════════════════════════════════════════
    // 媒体读取器的纯逻辑（MediaCodec 循环本身无法在 JVM 测，见类 KDoc）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `PCM 编码映射覆盖常见输出`() {
        // 取值对应 AudioFormat 的 ABI 冻结常量，写错一个就会把浮点当整数解
        assertEquals(
            SstvAudioConvert.Encoding.SIGNED_16,
            SstvMediaAudioReader.encodingFor(2), // ENCODING_PCM_16BIT
        )
        assertEquals(
            SstvAudioConvert.Encoding.UNSIGNED_8,
            SstvMediaAudioReader.encodingFor(3), // ENCODING_PCM_8BIT
        )
        assertEquals(
            SstvAudioConvert.Encoding.FLOAT_32,
            SstvMediaAudioReader.encodingFor(4), // ENCODING_PCM_FLOAT
        )
        assertEquals(
            SstvAudioConvert.Encoding.SIGNED_24,
            SstvMediaAudioReader.encodingFor(21), // ENCODING_PCM_24BIT_PACKED（API 31）
        )
        assertEquals(
            SstvAudioConvert.Encoding.SIGNED_32,
            SstvMediaAudioReader.encodingFor(22), // ENCODING_PCM_32BIT（API 31）
        )
        // 其余（DEFAULT / INVALID / AC3 / AAC …）都不是 PCM，必须拒绝而不是猜
        assertNull("ENCODING_DEFAULT 不应被当成 PCM", SstvMediaAudioReader.encodingFor(1))
        assertNull("ENCODING_INVALID 必须被拒绝", SstvMediaAudioReader.encodingFor(0))
        assertNull("AAC 不是 PCM 输出", SstvMediaAudioReader.encodingFor(10))
    }

    @Test
    fun `格式校验接受 8 到 192 kHz 的常见布局`() {
        val encoding = SstvAudioConvert.Encoding.SIGNED_16
        for (rate in intArrayOf(8_000, 16_000, 22_050, 32_000, 44_100, 48_000, 96_000, 192_000)) {
            assertNull("$rate Hz 单声道应被接受", SstvMediaAudioReader.validateFormat(rate, 1, encoding))
        }
        assertNull("多声道在允许范围内", SstvMediaAudioReader.validateFormat(48_000, 8, encoding))
        assertNull("浮点同样接受", SstvMediaAudioReader.validateFormat(192_000, 2, SstvAudioConvert.Encoding.FLOAT_32))
    }

    @Test
    fun `格式校验拒绝非法布局并给出可展示原因`() {
        val encoding = SstvAudioConvert.Encoding.SIGNED_16
        val noEncoding = SstvMediaAudioReader.validateFormat(48_000, 1, null)
        assertNotNull("未知 PCM 编码必须被拒绝", noEncoding)
        assertTrue("应说明编码不受支持：$noEncoding", noEncoding!!.contains("PCM 编码"))

        val badChannels = SstvMediaAudioReader.validateFormat(48_000, 0, encoding)
        assertTrue("声道数为 0 应被拒绝：$badChannels", badChannels!!.contains("声道数非法"))

        val tooManyChannels = SstvMediaAudioReader.validateFormat(48_000, 9, encoding)
        assertTrue("超过 8 声道应被拒绝：$tooManyChannels", tooManyChannels!!.contains("声道数过多"))

        val tooLow = SstvMediaAudioReader.validateFormat(7, 1, encoding)
        assertTrue("低于 8 Hz 应被拒绝：$tooLow", tooLow!!.contains("采样率超出支持范围"))

        val tooHigh = SstvMediaAudioReader.validateFormat(192_001, 1, encoding)
        assertTrue("高于 192 kHz 应被拒绝：$tooHigh", tooHigh!!.contains("采样率超出支持范围"))
    }

    @Test
    fun `未知 PCM 编码可通过共享层给出原因`() {
        // validateFormat 在编码为 null 时就已经拦下，因此这里确认的是「先报编码、
        // 再报其他问题」的优先顺序 —— 编码不对时其余信息没有诊断价值
        val reason = SstvMediaAudioReader.validateFormat(0, 0, null)
        assertTrue("应优先报编码问题：$reason", reason!!.contains("PCM 编码"))
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助：从已知浮点值构造字节
    // ══════════════════════════════════════════════════════════════════

    /** 把一串 ±1.0 的值按指定编码打包成单声道字节流。 */
    private fun packSamples(encoding: SstvAudioConvert.Encoding, values: List<Double>): ByteArray {
        val bytes = ByteArray(values.size * encoding.bytesPerSample)
        values.forEachIndexed { index, value ->
            writeSample(bytes, index * encoding.bytesPerSample, encoding, value)
        }
        return bytes
    }

    /** 把「每帧多声道」的样本打包成交错字节流。 */
    private fun packInterleaved(
        encoding: SstvAudioConvert.Encoding,
        frames: List<List<Float>>,
    ): ByteArray {
        val channels = frames.first().size
        val bytes = ByteArray(frames.size * channels * encoding.bytesPerSample)
        var at = 0
        for (frame in frames) {
            for (channel in 0 until channels) {
                writeSample(bytes, at, encoding, frame[channel].toDouble())
                at += encoding.bytesPerSample
            }
        }
        return bytes
    }

    private fun writeSample(
        target: ByteArray,
        offset: Int,
        encoding: SstvAudioConvert.Encoding,
        value: Double,
    ) {
        when (encoding) {
            SstvAudioConvert.Encoding.UNSIGNED_8 ->
                target[offset] = ((value + 1.0) * 127.5).roundToInt().coerceIn(0, 255).toByte()

            SstvAudioConvert.Encoding.SIGNED_16 -> {
                val v = (value * 32767.0).roundToInt().coerceIn(-32768, 32767)
                target[offset] = (v and 0xFF).toByte()
                target[offset + 1] = ((v shr 8) and 0xFF).toByte()
            }

            SstvAudioConvert.Encoding.SIGNED_24 -> {
                val v = (value * 8388607.0).roundToInt().coerceIn(-8388608, 8388607)
                target[offset] = (v and 0xFF).toByte()
                target[offset + 1] = ((v shr 8) and 0xFF).toByte()
                target[offset + 2] = ((v shr 16) and 0xFF).toByte()
            }

            SstvAudioConvert.Encoding.SIGNED_32 -> {
                val v = (value * 2147483647.0).roundToLong().toInt()
                writeInt(target, offset, v)
            }

            SstvAudioConvert.Encoding.FLOAT_32 -> writeInt(target, offset, value.toFloat().toRawBits())

            SstvAudioConvert.Encoding.FLOAT_64 -> {
                val bits = value.toRawBits()
                for (i in 0 until 8) {
                    target[offset + i] = ((bits shr (8 * i)) and 0xFF).toByte()
                }
            }
        }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    @Suppress("unused")
    private fun unusedAbsReference(value: Float) = abs(value)
}
