package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * WAV 读取 / 写出的单元测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖三类真实痛点：
 *  1. **格式兼容** —— 用户手上的录音五花八门（8/16/24/32 位、单/双声道、44.1/48/8 kHz、
 *     甚至 32 位浮点），任何一种读不出来都会让「导入录音」直接失败；
 *  2. **容器健壮性** —— RIFF 的块顺序不固定、块长度按偶数字节对齐、部分录音软件把
 *     `data` 长度写成 0；解析必须能容忍这些；
 *  3. **错误信息可展示** —— 失败必须给出「不是 WAV / 不支持的编码 / 音频过短」这类
 *     能直接放到 UI 上的原因，而不是抛异常。
 */
class SstvWavTest {

    // ══════════════════════════════════════════════════════════════════
    // 读取
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `16 位单声道往返误差在量化范围内`() {
        val tone = sine(1000.0, 1.0, 48_000)
        val bytes = SstvWavWriter.toBytes(tone, 48_000)
        val outcome = SstvWavReader.read(bytes)
        assertTrue("应读取成功", outcome is SstvWavReader.Outcome.Success)
        val success = outcome as SstvWavReader.Outcome.Success
        assertEquals(48_000, success.sourceSampleRate)
        assertEquals(1, success.sourceChannels)
        assertEquals(16, success.bitsPerSample)
        assertTrue("不应被截断", !success.truncated)
        assertTrue("不应重采样", !success.resampled)
        assertEquals(tone.size, success.samples.size)
        // 16 位量化台阶为 1/32768，取两倍余量
        for (i in tone.indices step 501) {
            assertTrue(
                "第 $i 个样本偏差 ${abs(tone[i] - success.samples[i])} 超出量化范围",
                abs(tone[i] - success.samples[i]) < 2.0 / 32768,
            )
        }
    }

    @Test
    fun `流式写出与整段写出结果一致`() {
        // 发射导出走 writePcm16（流式、可回填头部），必须与整段写出等价
        val tone = sine(1500.0, 0.5, 48_000)
        val temp = File.createTempFile("sstv_wav_test", ".wav")
        try {
            val chunks = sequenceOf(tone.copyOfRange(0, 8000), tone.copyOfRange(8000, tone.size))
            val samples = SstvWavWriter.writePcm16(temp, 48_000, chunks)
            assertEquals(tone.size.toLong(), samples)
            assertEquals(
                "文件长度应为头部 + 样本字节数",
                SstvWavWriter.HEADER_BYTES + tone.size * 2L,
                temp.length(),
            )

            val outcome = SstvWavReader.read(temp)
            assertTrue(outcome is SstvWavReader.Outcome.Success)
            val read = (outcome as SstvWavReader.Outcome.Success).samples
            assertEquals(tone.size, read.size)
            assertTrue(
                "流式写出的数据应与整段写出逐样本一致",
                read.contentEquals(SstvWavReader.read(SstvWavWriter.toBytes(tone, 48_000)).let {
                    (it as SstvWavReader.Outcome.Success).samples
                }),
            )
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `立体声被下混为均值`() {
        // 左 +0.5 / 右 −0.5：均值应为 0，而不是取其中一路
        val bytes = buildWav(48_000, channels = 2, bits = 16, format = 1) { _, channel ->
            if (channel == 0) 0.5f else -0.5f
        }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        val success = outcome as SstvWavReader.Outcome.Success
        assertEquals(2, success.sourceChannels)
        for (v in success.samples) {
            assertTrue("下混后应接近 0，实际 $v", abs(v) < 1e-3)
        }
    }

    @Test
    fun `非 48 kHz 被重采样且时长保持`() {
        val sourceRate = 8_000
        val seconds = 2.0
        val bytes = buildWav(
            sourceRate,
            channels = 1,
            bits = 16,
            format = 1,
            frames = (seconds * sourceRate).toInt(),
        ) { frame, _ ->
            sin(2.0 * PI * 1500.0 * frame / sourceRate).toFloat()
        }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        val success = outcome as SstvWavReader.Outcome.Success
        assertEquals(sourceRate, success.sourceSampleRate)
        assertTrue("应标记为已重采样", success.resampled)
        assertEquals(
            "重采样后时长应保持约 $seconds 秒",
            seconds,
            success.durationSeconds,
            0.01,
        )
    }

    @Test
    fun `8 位无符号样本正确还原`() {
        // 8 位 WAV 是无符号的，128 表示零：写死「按有符号处理」会整段偏置
        val bytes = buildWav(48_000, channels = 1, bits = 8, format = 1) { frame, _ ->
            if (frame < 2400) 1.0f else -1.0f
        }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        val samples = (outcome as SstvWavReader.Outcome.Success).samples
        assertTrue("前半段应为正，实际 ${samples[0]}", samples[0] > 0.9f)
        assertTrue("后半段应为负，实际 ${samples[3000]}", samples[3000] < -0.9f)
    }

    @Test
    fun `24 位样本符号扩展正确`() {
        val bytes = buildWav(48_000, channels = 1, bits = 24, format = 1) { frame, _ ->
            if (frame < 2400) 1.0f else -1.0f
        }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        val samples = (outcome as SstvWavReader.Outcome.Success).samples
        assertEquals(24, (outcome as SstvWavReader.Outcome.Success).bitsPerSample)
        assertTrue("正峰值应接近 +1，实际 ${samples[0]}", samples[0] > 0.99f)
        assertTrue("负峰值应接近 −1，实际 ${samples[3000]}", samples[3000] < -0.99f)
    }

    @Test
    fun `32 位浮点样本被支持`() {
        val bytes = buildWav(48_000, channels = 1, bits = 32, format = 3) { frame, _ ->
            if (frame < 2400) 0.25f else -0.25f
        }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        val samples = (outcome as SstvWavReader.Outcome.Success).samples
        assertEquals("浮点应被精确读出", 0.25f, samples[10], 1e-6f)
        assertEquals(-0.25f, samples[3000], 1e-6f)
    }

    @Test
    fun `64 位浮点样本被支持`() {
        // WAVE_FORMAT_IEEE_FLOAT 允许 64 位双精度。读取要用 Double.fromBits(readLongLE)，
        // 若沿用 32 位的 4 字节路径，样本会被从中间切开 —— 得到的是噪声而不是音频
        val bytes = buildWav(48_000, channels = 1, bits = 64, format = 3) { frame, _ ->
            if (frame < 2400) 0.375f else -0.375f
        }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(
            "64 位浮点应读取成功：${(outcome as? SstvWavReader.Outcome.Failure)?.reason}",
            outcome is SstvWavReader.Outcome.Success,
        )
        val success = outcome as SstvWavReader.Outcome.Success
        assertEquals("位深应如实上报", 64, success.bitsPerSample)
        assertEquals(48_000, success.sourceSampleRate)
        assertEquals("双精度应被精确读出", 0.375f, success.samples[10], 1e-6f)
        assertEquals(-0.375f, success.samples[3000], 1e-6f)
    }

    @Test
    fun `64 位浮点的非标准位深仍被拒绝`() {
        // 16 位浮点不存在于标准里，必须给出「不支持的浮点位深」而不是硬解
        val bytes = buildWav(48_000, channels = 1, bits = 16, format = 3) { _, _ -> 0f }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Failure)
        assertTrue(
            "原因应指明浮点位深不支持：${(outcome as SstvWavReader.Outcome.Failure).reason}",
            outcome.reason.contains("不支持的浮点位深"),
        )
    }

    @Test
    fun `嗅探按内容判断 RIFF 容器`() {
        // ViewModel 用它在「纯 Kotlin 解析」与「平台解码器」之间选路，
        // 因此它必须只看内容，不看扩展名
        val wav = File.createTempFile("sstv_sniff", ".bin")
        val notWav = File.createTempFile("sstv_sniff", ".wav")
        try {
            wav.writeBytes(SstvWavWriter.toBytes(sine(1000.0, 0.6, 48_000), 48_000))
            // 扩展名是 .wav，内容却是 MP3 帧同步头：必须判为「不是 WAV」
            notWav.writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(2000))
            assertTrue("RIFF/WAVE 内容应被识别", SstvWavReader.isWavFile(wav))
            assertTrue("仅有 .wav 后缀不应被识别", !SstvWavReader.isWavFile(notWav))
        } finally {
            wav.delete()
            notWav.delete()
        }
    }

    @Test
    fun `嗅探对过小文件返回假`() {
        val tiny = File.createTempFile("sstv_sniff_tiny", ".wav")
        try {
            tiny.writeBytes(ByteArray(8))
            assertTrue("过小文件不应被当成 WAV", !SstvWavReader.isWavFile(tiny))
        } finally {
            tiny.delete()
        }
    }

    @Test
    fun `data 长度写成 0 时退化为读到文件尾`() {
        // 部分录音软件的流式写法会把长度留成占位值
        val bytes = buildWav(48_000, channels = 1, bits = 16, format = 1, dataLengthOverride = 0) { _, _ -> 0.5f }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(
            "应退化为读到文件尾：${(outcome as? SstvWavReader.Outcome.Failure)?.reason}",
            outcome is SstvWavReader.Outcome.Success,
        )
        assertTrue((outcome as SstvWavReader.Outcome.Success).samples.isNotEmpty())
    }

    @Test
    fun `块顺序任意且含奇数长度块时仍能解析`() {
        // LIST 块放在 fmt 之前，且长度为奇数（RIFF 要求补 1 字节填充）
        val bytes = buildWav(
            48_000, channels = 1, bits = 16, format = 1,
            leadingChunk = "LIST" to ByteArray(5),
        ) { _, _ -> 0.75f }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(
            "应跳过无关块：${(outcome as? SstvWavReader.Outcome.Failure)?.reason}",
            outcome is SstvWavReader.Outcome.Success,
        )
        assertEquals(0.75f, (outcome as SstvWavReader.Outcome.Success).samples[7], 1e-3f)
    }

    // ══════════════════════════════════════════════════════════════════
    // 失败路径
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `非 WAV 文件给出明确原因`() {
        val outcome = SstvWavReader.read("这不是音频".toByteArray() + ByteArray(64))
        assertTrue(outcome is SstvWavReader.Outcome.Failure)
        assertTrue(
            "原因应指明不是 WAV：${(outcome as SstvWavReader.Outcome.Failure).reason}",
            outcome.reason.contains("WAV"),
        )
    }

    @Test
    fun `文件过小给出明确原因`() {
        val outcome = SstvWavReader.read(ByteArray(8))
        assertTrue(outcome is SstvWavReader.Outcome.Failure)
        assertTrue((outcome as SstvWavReader.Outcome.Failure).reason.contains("过小"))
    }

    @Test
    fun `不支持的编码给出明确原因`() {
        // format = 0x11（IMA ADPCM）：本读取器刻意只支持 PCM 与 IEEE float。
        // 位深仍写 16，以确保拒绝的理由是**编码**而不是位深
        val bytes = buildWav(48_000, channels = 1, bits = 16, format = 0x11) { _, _ -> 0f }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Failure)
        assertTrue(
            "原因应指明编码不支持：${(outcome as SstvWavReader.Outcome.Failure).reason}",
            outcome.reason.contains("不支持"),
        )
    }

    @Test
    fun `音频过短给出明确原因`() {
        val bytes = buildWav(48_000, channels = 1, bits = 16, format = 1, frames = 1000) { _, _ -> 0f }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Failure)
        assertTrue(
            "原因应指明过短：${(outcome as SstvWavReader.Outcome.Failure).reason}",
            outcome.reason.contains("过短"),
        )
    }

    @Test
    fun `超过时长上限时截断并标记`() {
        // 构造 301 秒的 8 kHz 音频：截断应在**原始**采样率上按 MAX_SECONDS 生效
        val sourceRate = 8_000
        val bytes = buildWav(sourceRate, channels = 1, bits = 16, format = 1, frames = 301 * sourceRate) { _, _ -> 0f }
        val outcome = SstvWavReader.read(bytes)
        assertTrue(outcome is SstvWavReader.Outcome.Success)
        val success = outcome as SstvWavReader.Outcome.Success
        assertTrue("应标记为已截断", success.truncated)
        assertEquals(
            "截断后时长应约等于上限",
            SstvWavReader.MAX_SECONDS,
            success.durationSeconds,
            0.05,
        )
    }

    @Test
    fun `头部长度字段被破坏时按实际数据解析`() {
        val good = buildWav(48_000, channels = 1, bits = 16, format = 1, frames = 24_000) { _, _ -> 0.2f }
        // 把 data 块声明的长度改成远超文件尾的值
        val broken = good.copyOf()
        val dataTagAt = indexOfTag(broken, "data")
        assertNotNull(dataTagAt)
        val at = dataTagAt!!
        val huge = Int.MAX_VALUE
        broken[at + 4] = (huge and 0xFF).toByte()
        broken[at + 5] = ((huge shr 8) and 0xFF).toByte()
        broken[at + 6] = ((huge shr 16) and 0xFF).toByte()
        broken[at + 7] = ((huge shr 24) and 0xFF).toByte()

        val outcome = SstvWavReader.read(broken)
        assertTrue(
            "应按实际可用长度解析：${(outcome as? SstvWavReader.Outcome.Failure)?.reason}",
            outcome is SstvWavReader.Outcome.Success,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    private fun sine(freq: Double, seconds: Double, rate: Int): FloatArray {
        val n = (seconds * rate).roundToInt()
        return FloatArray(n) { sin(2.0 * PI * freq * it / rate).toFloat() }
    }

    /**
     * 手工构造一个 WAV（可指定任意编码/位深/声道/采样率）。
     *
     * 刻意不用 [SstvWavWriter] —— 那样只能验证「自己写出的能被自己读回」，
     * 无法覆盖外部录音软件的写法（奇数长度块、0 长度 data、非 PCM 编码等）。
     */
    private fun buildWav(
        sampleRate: Int,
        channels: Int,
        bits: Int,
        format: Int,
        frames: Int = 24_000,
        dataLengthOverride: Int? = null,
        leadingChunk: Pair<String, ByteArray>? = null,
        sampleAt: (frame: Int, channel: Int) -> Float,
    ): ByteArray {
        val bytesPerSample = bits / 8
        val frameBytes = bytesPerSample * channels
        val data = ByteArray(frames * frameBytes)
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val v = sampleAt(frame, channel).coerceIn(-1f, 1f)
                val at = frame * frameBytes + channel * bytesPerSample
                when {
                    // 64 位浮点：每样本 8 字节（必须先于 32 位分支判断，否则会被截成两半）
                    format == 3 && bits == 64 -> writeLongLE(data, at, v.toDouble().toRawBits())
                    format == 3 && bits == 32 -> writeIntLE(data, at, v.toRawBits())
                    bits == 8 -> data[at] = ((v + 1f) * 127.5f).roundToInt().coerceIn(0, 255).toByte()
                    bits == 16 -> writeShortLE(data, at, (v * 32767f).roundToInt().coerceIn(-32768, 32767))
                    bits == 24 -> writeInt24LE(data, at, (v * 8388607f).roundToInt().coerceIn(-8388608, 8388607))
                    bits == 32 -> writeIntLE(data, at, (v * 2147483647f).toLong().toInt())
                    else -> {
                        // 非标准位深（只用于「必须被拒绝」的用例）：按该位深允许的字节数
                        // 写入低位。若照 4 字节写会越过为这个位深分配的缓冲区大小
                        val raw = if (format == 3) v.toRawBits() else (v * 2147483647f).toLong().toInt()
                        for (i in 0 until bytesPerSample) {
                            data[at + i] = ((raw shr (8 * i)) and 0xFF).toByte()
                        }
                    }
                }
            }
        }

        val out = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        leadingChunk?.let { (id, payload) ->
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(intLE(payload.size))
            body.write(payload)
            if (payload.size % 2 == 1) body.write(0) // RIFF 偶数对齐
        }
        body.write("fmt ".toByteArray(Charsets.US_ASCII))
        body.write(intLE(16))
        body.write(shortLE(format))
        body.write(shortLE(channels))
        body.write(intLE(sampleRate))
        body.write(intLE(sampleRate * frameBytes))
        body.write(shortLE(frameBytes))
        body.write(shortLE(bits))
        body.write("data".toByteArray(Charsets.US_ASCII))
        body.write(intLE(dataLengthOverride ?: data.size))
        body.write(data)

        val bodyBytes = body.toByteArray()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intLE(4 + bodyBytes.size))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun indexOfTag(bytes: ByteArray, tag: String): Int? {
        val target = tag.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - 4) {
            for (j in target.indices) {
                if (bytes[i + j] != target[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun intLE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortLE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private fun writeIntLE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt24LE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun writeLongLE(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }
}
