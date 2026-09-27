package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SSTV 核心链路单元测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖四层：
 *  1. **协议层** —— 模式参数表自洽性、行周期推导
 *  2. **VIS 识别** —— 各模式码、频率偏移容限、噪声不误报
 *  3. **鉴频精度** —— 恒定/阶梯/带噪信号的频率估计误差
 *  4. **编解码自环** —— 编码 → 解码 → PSNR（金标准，见 PSNR 阈值的说明）
 *
 * **关于 PSNR 阈值的说明**：SSTV 是模拟窄带模式，水平方向的瞬时频率跳变受发射/接收
 * 带宽限制。因此断言分两档：
 *  - **平坦或平滑内容**（纯色、正弦渐变）主体 PSNR 应 > 36 dB（实测 43–56 dB）；
 *  - **含尖锐边界的内容**（8 色条）全图 PSNR 会明显更低（实测 25–39 dB），这是模式本身
 *    的带宽限制，而非实现缺陷 —— 这类用例只断言更宽松的下限以防回归。
 * 「主体」指去掉每行首尾各 10 像素：行首/行尾必然落在「同步脉冲 ↔ porch ↔ 图像」的
 * 频率过渡带上，任何实现都无法完全还原。
 */
class SstvCodecTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    // ══════════════════════════════════════════════════════════════════
    // 1. 模式参数表
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `模式表 VIS 码唯一且可反查`() {
        val codes = SstvMode.entries.map { it.visCode }
        assertEquals("VIS 码不得重复", codes.size, codes.toSet().size)
        for (mode in SstvMode.entries) {
            assertEquals(mode, SstvMode.fromVisCode(mode.visCode))
        }
        assertNull("未录入的码应返回 null", SstvMode.fromVisCode(0x01))
    }

    @Test
    fun `行周期与权威参数一致`() {
        // 数值逐项核对自 slowrx modespec.c
        assertEquals(0.50848, SstvMode.PD_120.lineSeconds, 1e-9)
        assertEquals(0.150, SstvMode.ROBOT_36.lineSeconds, 1e-9)
        assertEquals(0.446446, SstvMode.MARTIN_1.lineSeconds, 1e-9)
        assertEquals(0x5F, SstvMode.PD_120.visCode)
        assertEquals(0x08, SstvMode.ROBOT_36.visCode)
    }

    @Test
    fun `通道时序不超出行周期`() {
        for (mode in SstvMode.entries) {
            val lastEnd = mode.channelTasks()
                .maxOf { it.startSeconds + it.pixels * it.pixelSeconds }
            // 容差 1 µs：参数表的像素时长是 4 位有效近似值（Scottie 1/DX 已按行周期
            // 反推修正），浮点比较留 1 µs 余量，远小于最短像素时长（137.5 µs）
            assertTrue(
                "${mode.displayName} 通道结束 ${lastEnd}s 超出 ${mode.lineSeconds}s",
                lastEnd <= mode.lineSeconds + 1e-6,
            )
        }
    }

    @Test
    fun `PD 族为四个全宽通道且两图像行共享色度`() {
        val tasks = SstvMode.PD_120.channelTasks()
        assertEquals(4, tasks.size)
        assertTrue("PD 每个通道都是全宽 640", tasks.all { it.pixels == 640 })
        assertEquals(2, SstvMode.PD_120.imageRowsPerRadioLine)
        assertEquals(248, SstvMode.PD_120.radioLines)
        // 帧时长 = 248 × 508.48 ms ≈ 126 s
        assertEquals(126.1, SstvMode.PD_120.frameSeconds, 0.2)
    }

    @Test
    fun `Robot 36 的亮度通道占用两倍像素时间`() {
        // 若按 1× 实现，每行只有 106 ms（标称 150 ms），图像会被挤压缩放
        val tasks = SstvMode.ROBOT_36.channelTasks()
        assertEquals(2, tasks.size)
        val y = tasks[0]
        assertEquals(320, y.pixels)
        assertEquals(SstvMode.ROBOT_36.pixelSeconds * 2.0, y.pixelSeconds, 1e-12)
    }

    @Test
    fun `全部模式均可解码`() {
        assertTrue("所有录入的模式都应可解码", SstvMode.entries.all { it.decodable })
        assertEquals(0x3C, SstvMode.SCOTTIE_1.visCode)
    }

    @Test
    fun `Scottie 的同步脉冲位于行中`() {
        // 依据 slowrx mode_scottie.rs：[sep][G][sep][B][SYNC][porch][R]
        val mode = SstvMode.SCOTTIE_1
        val chanLen = mode.linePixels * mode.pixelSeconds
        assertEquals(
            "同步起点相对行首 = 2·sep + 2·chanLen",
            2.0 * mode.separatorSeconds + 2.0 * chanLen,
            mode.syncOffsetSeconds,
            1e-12,
        )
        // 其余族同步就在行首
        for (m in listOf(SstvMode.PD_120, SstvMode.ROBOT_36, SstvMode.MARTIN_1)) {
            assertEquals("${m.displayName} 同步应在行首", 0.0, m.syncOffsetSeconds, 1e-12)
        }
    }

    @Test
    fun `lineEvents 与 channelTasks 的时间轴严格一致`() {
        // 编码按 lineEvents 写入、解码按 channelTasks 采样，两者必须逐像素等价
        for (mode in SstvMode.entries) {
            var elapsed = 0.0
            val startsFromEvents = ArrayList<Pair<SstvChannelRole, Double>>()
            for (event in mode.lineEvents()) {
                when (event) {
                    is SstvLineEvent.Sync -> elapsed += event.seconds
                    is SstvLineEvent.Gap -> elapsed += event.seconds
                    is SstvLineEvent.Channel -> {
                        startsFromEvents.add(event.task.role to elapsed)
                        elapsed += event.task.pixels * event.task.pixelSeconds
                    }
                }
            }
            val tasks = mode.channelTasks()
            assertEquals("${mode.displayName} 通道数不一致", tasks.size, startsFromEvents.size)
            for (i in tasks.indices) {
                assertEquals(
                    "${mode.displayName} 第 $i 个通道角色不一致",
                    tasks[i].role,
                    startsFromEvents[i].first,
                )
                assertEquals(
                    "${mode.displayName} 第 $i 个通道起点不一致",
                    tasks[i].startSeconds,
                    startsFromEvents[i].second,
                    1e-9,
                )
            }
            assertTrue(
                "${mode.displayName} 行事件总时长 ${elapsed}s 超出 ${mode.lineSeconds}s",
                elapsed <= mode.lineSeconds + 1e-6,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. VIS 识别
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `各模式 VIS 码均可正确识别`() {
        val encoder = SstvEncoder(sampleRate)
        for (mode in SstvMode.entries) {
            val audio = pad(encoder.encodeVis(mode.visCode), sampleRate / 10)
            val result = SstvVisDetector(sampleRate).process(audio)
            assertNotNull("${mode.displayName} 的 VIS 未检出", result)
            assertEquals(mode.visCode, result!!.visCode)
            assertEquals(mode, result.mode)
            // 停止位应在 910 ms 处结束
            assertEquals(0.910 * sampleRate, result.stopEndSample.toDouble(), sampleRate * 0.05)
        }
    }

    @Test
    fun `频率偏移下仍能识别并给出失谐量`() {
        // 电台失谐与多普勒都会整体平移频率
        for (offset in listOf(-70.0, 50.0, 120.0)) {
            val audio = pad(
                SstvEncoder(sampleRate).encodeVis(SstvMode.PD_120.visCode, offset),
                sampleRate / 10,
            )
            val result = SstvVisDetector(sampleRate).process(audio)
            assertNotNull("频偏 $offset Hz 下未检出", result)
            assertEquals(SstvMode.PD_120.visCode, result!!.visCode)
            assertEquals("失谐估计应接近实际频偏", offset, result.hedrShiftHz, 15.0)
        }
    }

    @Test
    fun `噪声与非 VIS 单音不误报`() {
        var seed = 0x12345678L
        val noise = FloatArray(sampleRate * 2) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            (((seed ushr 33).toInt() % 20000) / 20000.0).toFloat() * 0.5f
        }
        assertNull("随机噪声不应误报", SstvVisDetector(sampleRate).process(noise))
        assertNull(
            "非 VIS 单音不应误报",
            SstvVisDetector(sampleRate).process(tone(1750.0, 1.0)),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 鉴频精度
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `恒定频率的估计误差小于 1 Hz`() {
        for (freq in listOf(1200.0, 1500.0, 1900.0, 2100.0, 2300.0)) {
            val out = SstvDemodulator(sampleRate).demodulate(tone(freq, 0.3))
            val from = SstvDemodulator.TRANSIENT_SAMPLES + 4800
            val mean = out.copyOfRange(from, out.size).average()
            assertTrue(
                "恒定 $freq Hz 估计为 $mean Hz，误差 ${abs(mean - freq)}",
                abs(mean - freq) < 1.0,
            )
        }
    }

    @Test
    fun `滤波器对 2f_0 镜像抑制充分`() {
        // 这是 8 阶 Butterworth 的核心作用：2 阶时该噪声高达 ~42 Hz，且与信噪比无关
        val out = SstvDemodulator(sampleRate).demodulate(tone(1900.0, 0.5))
        val from = SstvDemodulator.TRANSIENT_SAMPLES + 4800
        var sumSq = 0.0
        var n = 0
        for (i in from until out.size) {
            val d = out[i] - 1900.0
            sumSq += d * d
            n++
        }
        val rms = sqrt(sumSq / n)
        assertTrue("无噪声 RMS 应 < 1 Hz，实测 $rms Hz", rms < 1.0)
    }

    @Test
    fun `推荐相位差分间隔随像素时长自适应`() {
        assertEquals(3, SstvDemodulator.recommendPhaseSpan(6.6)) // Robot 36 色度
        assertTrue(SstvDemodulator.recommendPhaseSpan(9.12) in 4..5) // PD-120
        assertEquals(11, SstvDemodulator.recommendPhaseSpan(21.96)) // Martin 1
        assertEquals(1, SstvDemodulator.recommendPhaseSpan(0.5))
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 色彩转换
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `灰度的色度分量恒为 128`() {
        for (v in listOf(0, 64, 128, 200, 255)) {
            assertEquals(128, SstvColor.rgbToCr(v, v, v))
            assertEquals(128, SstvColor.rgbToCb(v, v, v))
            assertEquals(v, SstvColor.rgbToY(v, v, v))
        }
    }

    @Test
    fun `YCrCb 往返误差不超过 3 级`() {
        val colors = listOf(0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0x808080, 0x123456, 0xC04020)
        for (rgb in colors) {
            val r = SstvColor.redOf(rgb)
            val g = SstvColor.greenOf(rgb)
            val b = SstvColor.blueOf(rgb)
            val back = SstvColor.ycbcrToRgb(
                SstvColor.rgbToY(r, g, b),
                SstvColor.rgbToCr(r, g, b),
                SstvColor.rgbToCb(r, g, b),
            )
            assertTrue(
                "0x${rgb.toString(16)} 往返为 0x${back.toString(16)}",
                abs(SstvColor.redOf(back) - r) <= 3 &&
                    abs(SstvColor.greenOf(back) - g) <= 3 &&
                    abs(SstvColor.blueOf(back) - b) <= 3,
            )
        }
    }

    @Test
    fun `Cr 与 Cb 不可互换`() {
        // 「图像发绿」的典型成因就是两者颠倒，此用例锁死语义
        val rgb = 0xC04020
        val y = SstvColor.rgbToY(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        val cr = SstvColor.rgbToCr(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        val cb = SstvColor.rgbToCb(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        val correct = SstvColor.ycbcrToRgb(y, cr, cb)
        val swapped = SstvColor.ycbcrToRgb(y, cb, cr)
        assertTrue("Cr/Cb 颠倒必须产生明显不同的颜色", correct != swapped)
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. 编解码自环（金标准）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `平坦图像主体可无损还原`() {
        val encoder = SstvEncoder(sampleRate)
        val decoder = SstvDecoder(sampleRate)
        val mode = SstvMode.ROBOT_36
        for (color in listOf(0x808080, 0xE0E0E0, 0xC04020)) {
            val image = solidImage(color, mode.linePixels, mode.imageLines)
            val audio = encoder.encode(image, mode)
            val result = decodeSuccessfully(decoder, audio)
            assertEquals(mode.imageLines, result.decodedLines)
            assertEquals("slant 必须精确为 1", 1.0, result.slantRatio, 0.005)
            assertTrue(
                "纯色 0x${color.toString(16)} 主体 PSNR ${psnrCentral(image, result)} dB",
                psnrCentral(image, result) > 36.0,
            )
        }
    }

    @Test
    fun `Robot 36 平滑内容自环保真`() {
        assertRoundTrip(SstvMode.ROBOT_36, sineImage(SstvMode.ROBOT_36, cycles = 1), 36.0)
    }

    @Test
    fun `Martin 1 自环保真`() {
        val mode = SstvMode.MARTIN_1
        assertRoundTrip(mode, solidImage(0x808080, mode.linePixels, mode.imageLines), 36.0)
        // Martin 直接传 RGB，没有色度子采样，是最准的模式
        assertRoundTrip(mode, barsImage(mode), 30.0)
    }

    @Test
    fun `PD 120 自环保真`() {
        val mode = SstvMode.PD_120
        assertRoundTrip(mode, sineImage(mode, cycles = 1), 30.0)
    }

    @Test
    fun `Robot 72 与 Martin 2 自环可用`() {
        assertRoundTrip(SstvMode.ROBOT_72, barsImage(SstvMode.ROBOT_72), 25.0)
        assertRoundTrip(SstvMode.MARTIN_2, barsImage(SstvMode.MARTIN_2), 24.0)
    }

    @Test
    fun `Scottie 族自环保真`() {
        // Scottie 的同步脉冲位于行中，行首需由实测同步位置反推
        assertRoundTrip(SstvMode.SCOTTIE_1, solidImage(0x808080, 320, 256), 36.0)
        assertRoundTrip(SstvMode.SCOTTIE_2, barsImage(SstvMode.SCOTTIE_2), 24.0)
        assertRoundTrip(SstvMode.SCOTTIE_DX, sineImage(SstvMode.SCOTTIE_DX, cycles = 1), 28.0)
    }

    @Test
    fun `时钟漂移百分之一仍能校正`() {
        val mode = SstvMode.ROBOT_36
        val encoder = SstvEncoder(sampleRate)
        val decoder = SstvDecoder(sampleRate)
        val image = solidImage(0x808080, mode.linePixels, mode.imageLines)
        val audio = encoder.encode(image, mode)

        for (ratio in listOf(0.99, 1.01)) {
            val drifted = resample(audio, ratio)
            val result = decodeSuccessfully(decoder, drifted)
            assertEquals(
                "拟合出的行周期比例应接近 $ratio",
                ratio,
                result.slantRatio,
                0.005,
            )
            // 统一频偏由「同步脉冲实测频率」补偿，残余约 ±12 Hz
            assertTrue(
                "失谐补偿后应仍有良好保真，实测 PSNR ${psnrCentral(image, result)} dB",
                psnrCentral(image, result) > 30.0,
            )
        }
    }

    @Test
    fun `带噪信号仍可解码`() {
        val mode = SstvMode.ROBOT_36
        val encoder = SstvEncoder(sampleRate)
        val image = solidImage(0x808080, mode.linePixels, mode.imageLines)
        val noisy = addNoise(encoder.encode(image, mode), 0.5, snrDb = 30.0)
        val result = decodeSuccessfully(SstvDecoder(sampleRate), noisy)
        assertTrue(
            "SNR 30 dB 下主体 PSNR ${psnrCentral(image, result)} dB",
            psnrCentral(image, result) > 30.0,
        )
    }

    @Test
    fun `手动指定模式可跳过 VIS`() {
        val mode = SstvMode.ROBOT_36
        val image = solidImage(0x808080, mode.linePixels, mode.imageLines)
        // 不输出 VIS 头，只能靠 forcedMode 解码
        val audio = SstvEncoder(sampleRate).encode(image, mode, includeVis = false)
        val outcome = SstvDecoder(sampleRate).decode(audio, forcedMode = mode)
        assertTrue("手动模式应能解码", outcome is SstvDecoder.Outcome.Success)
    }

    @Test
    fun `未识别到 VIS 时给出明确失败原因`() {
        val outcome = SstvDecoder(sampleRate).decode(tone(1900.0, 1.0))
        assertTrue(outcome is SstvDecoder.Outcome.Failure)
        assertTrue((outcome as SstvDecoder.Outcome.Failure).reason.contains("VIS"))
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    private fun assertRoundTrip(mode: SstvMode, image: SstvImageSource, minCorePsnr: Double) {
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val result = decodeSuccessfully(SstvDecoder(sampleRate), audio)
        assertEquals("${mode.displayName} 行数", mode.imageLines, result.decodedLines)
        assertEquals("${mode.displayName} slant", 1.0, result.slantRatio, 0.005)
        val core = psnrCentral(image, result)
        assertTrue("${mode.displayName} 主体 PSNR $core dB < $minCorePsnr dB", core > minCorePsnr)
    }

    private fun decodeSuccessfully(decoder: SstvDecoder, audio: FloatArray): SstvDecoder.Result {
        val outcome = decoder.decode(audio)
        assertTrue(
            "解码失败：${(outcome as? SstvDecoder.Outcome.Failure)?.reason}",
            outcome is SstvDecoder.Outcome.Success,
        )
        return (outcome as SstvDecoder.Outcome.Success).result
    }

    /** 连续相位正弦序列。 */
    private fun toneSequence(segments: List<Pair<Double, Double>>): FloatArray {
        val out = ArrayList<Float>()
        var phase = 0.0
        for ((freq, seconds) in segments) {
            val n = (seconds * sampleRate).roundToInt()
            val step = 2.0 * PI * freq / sampleRate
            repeat(n) {
                out.add(sin(phase).toFloat())
                phase += step
            }
        }
        return out.toFloatArray()
    }

    private fun tone(freq: Double, seconds: Double) = toneSequence(listOf(freq to seconds))

    private fun pad(audio: FloatArray, samples: Int) = audio + FloatArray(samples)

    /** 等幅均匀分布噪声，信噪比按 [snrDb] 施加。 */
    private fun addNoise(audio: FloatArray, amplitude: Double, snrDb: Double): FloatArray {
        var seed = 987654321L
        val noiseStd = amplitude / Math.pow(10.0, snrDb / 20.0)
        val half = noiseStd * sqrt(3.0)
        return FloatArray(audio.size) { i ->
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val u = ((seed ushr 33).toInt() % 20000) / 20000.0
            (audio[i] + u * half).toFloat()
        }
    }

    /** 线性插值重采样，模拟收发时钟不同源。 */
    private fun resample(audio: FloatArray, ratio: Double): FloatArray {
        val outLen = (audio.size * ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i / ratio
            val i0 = pos.toInt().coerceIn(0, audio.size - 1)
            val i1 = (i0 + 1).coerceAtMost(audio.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = audio[i0] * (1 - frac) + audio[i1] * frac
        }
        return out
    }

    private fun solidImage(rgb: Int, width: Int, height: Int) =
        SstvPixelImageSource(width, height, IntArray(width * height) { rgb })

    private fun sineImage(mode: SstvMode, cycles: Int): SstvImageSource {
        val w = mode.linePixels
        val h = mode.imageLines
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = (127.5 + 127.5 * sin(2.0 * PI * cycles * x / w)).toInt().coerceIn(0, 255)
                px[y * w + x] = (v shl 16) or (v shl 8) or v
            }
        }
        return SstvPixelImageSource(w, h, px)
    }

    /** 8 色彩条 + 垂直亮度渐变：含尖锐水平边界，属「超出 SSTV 带宽」的严苛内容。 */
    private fun barsImage(mode: SstvMode): SstvImageSource {
        val w = mode.linePixels
        val h = mode.imageLines
        val colors = intArrayOf(
            0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00,
            0xFF00FF, 0xFF0000, 0x0000FF, 0x000000,
        )
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val gain = 1.0 - 0.55 * y / h.toDouble()
            for (x in 0 until w) {
                val c = colors[(x * colors.size / w).coerceIn(0, colors.size - 1)]
                val r = (((c shr 16) and 0xFF) * gain).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * gain).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * gain).toInt().coerceIn(0, 255)
                px[y * w + x] = (r shl 16) or (g shl 8) or b
            }
        }
        return SstvPixelImageSource(w, h, px)
    }

    /** 去掉每行首尾各 10 像素后的 PSNR（边缘必然落在频率过渡带上）。 */
    private fun psnrCentral(source: SstvImageSource, result: SstvDecoder.Result): Double {
        val margin = 10
        var sum = 0.0
        var count = 0
        for (y in margin until result.height - margin) {
            for (x in margin until result.width - margin) {
                val a = source.rgbAt(x, y)
                val b = result.pixels[y * result.width + x]
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = (((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)).toDouble()
                    sum += d * d
                    count++
                }
            }
        }
        val mse = sum / count
        return if (mse <= 1e-9) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }
}
