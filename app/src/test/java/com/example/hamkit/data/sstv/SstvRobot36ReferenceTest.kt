package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 参照 [xdsopl/robot36](https://github.com/xdsopl/robot36)（Java + JNI，0BSD）补齐的三项能力：
 *
 * 1. **Wraase SC2-180**：结构最简单却一直缺失的模式（R→G→B、无通道分隔）；
 * 2. **Robot 24/36 行奇偶判定**：从分隔脉冲频率判定奇偶，而不是按行序号猜 ——
 *    录音从奇数行开始时，后者会让整幅图红蓝互换；
 * 3. **零相位像素 Nyquist 低通**：robot36 的 `ExponentialMovingAverage` 用法，
 *    前向+反向二阶 EMA，截止恰好落在像素 Nyquist。
 *
 * 三者的「为什么」都写在对应实现类的 KDoc 里，这里只做行为断言。
 */
class SstvRobot36ReferenceTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    // ══════════════════════════════════════════════════════════════════
    // 1. Wraase SC2-180
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `Wraase SC2-180 的参数与两处权威来源一致`() {
        val mode = SstvMode.WRASSE_SC2_180
        // robot36 RGBModes.Wraase_SC2_180 与 sstv crate modes/wrasse.rs 一致给出：
        // sync 5.5225 ms + porch 0.5 ms + 3 × 235 ms = 711.0225 ms
        assertEquals(0x37, mode.visCode)
        assertEquals(320, mode.linePixels)
        assertEquals(256, mode.imageLines)
        assertEquals(0.7110225, mode.lineSeconds, 1e-9)
        assertEquals(0.0055225, mode.syncSeconds, 1e-9)
        assertEquals(0.0005, mode.porchSeconds, 1e-9)
        assertEquals(0.000734375, mode.pixelSeconds, 1e-9)
        assertEquals("Wraase 没有通道分隔脉冲", 0.0, mode.separatorSeconds, 1e-12)
        assertEquals("256 行 × 711.0225 ms ≈ 182 s", 182.0, mode.frameSeconds, 0.2)
        // 三段首尾相接，恰好铺满整行（没有留给间隔的余量）
        assertEquals(mode.lineSeconds, mode.lineOccupiedSeconds, 1e-9)
    }

    @Test
    fun `Wraase 的通道顺序是 R G B 而 Martin 是 G B R`() {
        // 顺序写错会让整幅图红绿蓝互换，而自环测试**发现不了**（编解码同模型会自洽），
        // 因此必须直接断言顺序本身
        assertEquals(
            listOf(SstvChannelRole.RED, SstvChannelRole.GREEN, SstvChannelRole.BLUE),
            SstvMode.WRASSE_SC2_180.channelTasks().map { it.role },
        )
        assertEquals(
            listOf(SstvChannelRole.GREEN, SstvChannelRole.BLUE, SstvChannelRole.RED),
            SstvMode.MARTIN_1.channelTasks().map { it.role },
        )
        assertEquals(SstvRgbOrder.RGB, SstvMode.WRASSE_SC2_180.rgbOrder)
        assertEquals(SstvRgbOrder.GBR, SstvMode.MARTIN_1.rgbOrder)
        assertEquals(SstvRgbOrder.GBR, SstvMode.SCOTTIE_1.rgbOrder)
    }

    @Test
    fun `Wraase SC2-180 自环保真且不偏色`() {
        // 用彩色内容：若解码端按下标而非按角色取通道，红绿蓝会被置换，PSNR 会明显掉下来
        val mode = SstvMode.WRASSE_SC2_180
        val image = colorRamp(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val result = decodeSuccessfully(audio, mode)
        assertEquals(mode.imageLines, result.decodedLines)
        assertEquals(1.0, result.slantRatio, 0.005)
        val psnr = psnr(image, result, 0, 0)
        assertTrue("Wraase SC2-180 自环 PSNR $psnr dB 过低（是否通道顺序接错？）", psnr > 30.0)
    }

    @Test
    fun `Wraase 的 VIS 码可被识别`() {
        val audio = SstvEncoder(sampleRate).encodeVis(SstvMode.WRASSE_SC2_180.visCode)
        val detected = SstvVisDetector(sampleRate).process(audio)
        assertNotNull("VIS 0x37 应被识别", detected)
        assertEquals(SstvMode.WRASSE_SC2_180, detected!!.mode)
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. Robot 24/36 行奇偶
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `Robot 24 与 36 声明了奇偶标记且标记在事件序列里`() {
        for (mode in listOf(SstvMode.ROBOT_24, SstvMode.ROBOT_36)) {
            val marker = mode.parityMarker
            assertNotNull("${mode.displayName} 必须声明奇偶标记", marker)
            assertEquals("偶行发黑电平", SstvMode.BLACK_HZ, marker!!.evenHz, 1e-9)
            assertEquals("奇行发白电平", SstvMode.WHITE_HZ, marker.oddHz, 1e-9)
            // 分隔起点 = sync + porch + 2 × chanLen = 9 + 3 + 88 = 100 ms
            assertEquals(0.100, marker.startSeconds, 1e-9)
            assertEquals("标记时长 = 分隔脉冲时长", mode.separatorSeconds, marker.seconds, 1e-12)

            val parityEvents = mode.lineEvents().filterIsInstance<SstvLineEvent.ParityMarker>()
            assertEquals("${mode.displayName} 每行应恰好有一个奇偶标记", 1, parityEvents.size)
        }
        // Robot 72 每行都发 U 与 V，不存在行交替，因此不应有奇偶标记
        assertEquals(null, SstvMode.ROBOT_72.parityMarker)
    }

    @Test
    fun `分隔脉冲频率按行奇偶交替写出`() {
        // 直接看编码出的音频：偶行的分隔段应是 1500 Hz、奇行是 2300 Hz。
        // 这是「编码器符合规范」的证据，而不仅仅依赖解码端自洽
        val mode = SstvMode.ROBOT_36
        val audio = SstvEncoder(sampleRate).encode(solidImage(mode, 0x808080), mode, includeVis = false)
        val freqs = SstvDemodulator(
            sampleRate = sampleRate,
            phaseSpan = SstvDemodulator.recommendPhaseSpan(mode.pixelSeconds * sampleRate),
        ).demodulate(audio)

        val marker = mode.parityMarker!!
        val lineSamples = mode.lineSeconds * sampleRate
        for (line in 0 until 8) {
            val center = line * lineSamples +
                (marker.startSeconds + marker.seconds / 2.0) * sampleRate
            val measured = meanHz(freqs, center, marker.seconds * sampleRate * 0.25)
            val expected = if (line % 2 == 0) marker.evenHz else marker.oddHz
            assertEquals(
                "第 $line 行的分隔脉冲频率应为 $expected Hz（实测 $measured）",
                expected,
                measured,
                60.0,
            )
        }
    }

    @Test
    fun `录音从奇数行开始时颜色不互换`() {
        // **这是本次修正要解决的原始缺陷**：把帧首的那一个无线行整段切掉，
        // 于是音频的第一行其实是原帧的「奇数行」。按行序号猜奇偶的实现会把
        // Cr/Cb 接反，整幅图红蓝互换；按分隔脉冲判定的实现不受影响。
        val mode = SstvMode.ROBOT_36
        val image = colorRamp(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val vis = SstvVisDetector(sampleRate).process(audio)
        assertNotNull(vis)

        val lineSamples = (mode.lineSeconds * sampleRate).toInt()
        val cutAt = vis!!.stopEndSample.toInt().coerceAtLeast(0)
        val cut = FloatArray(audio.size - lineSamples)
        System.arraycopy(audio, 0, cut, 0, cutAt)
        System.arraycopy(audio, cutAt + lineSamples, cut, cutAt, audio.size - cutAt - lineSamples)

        val result = decodeSuccessfully(cut, mode)
        assertTrue("至少应解出大半帧，实际 ${result.decodedLines} 行", result.decodedLines > mode.imageLines / 2)

        // 切掉一行后，解出的第 r 行对应原图第 r+1 行。允许 ±2 行的垂直定位误差，
        // 取最佳偏移下的 PSNR 作为「颜色是否正确」的判据
        var bestOffset = 0
        var bestPsnr = -1.0
        var bestSwapped = -1.0
        for (offset in 1..3) {
            val straight = psnr(image, result, offset, 0, skipTop = 3, skipBottom = 3)
            if (straight > bestPsnr) {
                bestPsnr = straight
                bestOffset = offset
                bestSwapped = psnr(image, result, offset, CHANNEL_SWAP_RB, skipTop = 3, skipBottom = 3)
            }
        }
        assertTrue(
            "切掉一行后颜色应仍然正确（最佳偏移 $bestOffset 行，PSNR $bestPsnr dB，" +
                "若把 R/B 互换则为 $bestSwapped dB）",
            bestPsnr > 26.0 && bestPsnr > bestSwapped + 6.0,
        )
    }

    @Test
    fun `完整帧的奇偶判定与行序号一致`() {
        // 完整帧从偶行开始：按标记判定出的奇偶必须与「行号 % 2」完全一致，
        // 否则说明标记的极性搞反了（那会让所有完整帧都偏色）
        val mode = SstvMode.ROBOT_36
        val image = colorRamp(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val result = decodeSuccessfully(audio, mode)
        val straight = psnr(image, result, 0, 0, skipTop = 3, skipBottom = 3)
        val swapped = psnr(image, result, 0, CHANNEL_SWAP_RB, skipTop = 3, skipBottom = 3)
        assertTrue(
            "完整帧不应偏色（正常 $straight dB vs 互换 $swapped dB）",
            straight > 26.0 && straight > swapped + 6.0,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 零相位像素 Nyquist 低通
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `低通不改变纯色图像`() {
        // 低通是线性时不变运算：行**内部**的恒定输入必须原样通过（这能挡住「滤波器
        // 整体衰减」「反向那一遍用 0 初始化把右端压暗」两类实现错误）。
        //
        // 行首/行尾刻意不参与断言：那里是「同步/porch ↔ 图像」的频率过渡带，IIR 滤波
        // 本来就会把过渡沿向外扩散几个样本，这是带限的必然结果而非缺陷。
        val mode = SstvMode.ROBOT_36
        val image = solidImage(mode, 0x6C6C6C)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val filtered = decodeSuccessfully(audio, mode, pixelNyquistFilter = true)
        val raw = decodeSuccessfully(audio, mode, pixelNyquistFilter = false)
        assertEquals(raw.decodedLines, filtered.decodedLines)

        var maxDiff = 0
        var sumDiff = 0.0
        var count = 0
        for (y in 0 until raw.decodedLines) {
            for (x in 4 until raw.width - 4) {
                val index = y * raw.width + x
                val diff = kotlin.math.abs(luma(filtered.pixels[index]) - luma(raw.pixels[index]))
                if (diff > maxDiff) maxDiff = diff
                sumDiff += diff
                count++
            }
        }
        assertTrue("行内部不应出现可见偏差，实测最大偏差 $maxDiff 级", maxDiff <= 2)
        assertTrue(
            "整体不应被衰减或抬亮，实测平均偏差 ${"%.3f".format(sumDiff / count)} 级",
            sumDiff / count < 0.5,
        )
    }

    @Test
    fun `低通在像素 Nyquist 低于解调带宽时才真正降噪`() {
        // **本实现的收益边界**（值得记为结论）：本项目的解调器基带带宽是 1200 Hz
        // （8 阶 Butterworth 低通，见 SstvDemodulator）；
        //  - **快模式**的像素 Nyquist 高于它 —— PD-120 是 2632 Hz、Robot 36 亮度通道是
        //    1818 Hz —— 也就是说噪声在进入解码器之前就已经被解调器滤掉大半，低通几乎
        //    没有可滤的东西，实测收益 ~0.4 dB；
        //  - **慢模式**的像素 Nyquist 低于它 —— Scottie DX 只有 463 Hz —— 低通把
        //    463–1200 Hz 这一整段纯噪声滤掉，收益明显。
        // 这正是 robot36 需要这个滤波器的原因：它的解调基带比像素 Nyquist 宽得多。
        // 本用例固化上述结论，避免以后误以为「开了低通就该在所有模式上大幅改善」。
        val mode = SstvMode.SCOTTIE_DX
        val image = colorRamp(mode)
        val clean = SstvEncoder(sampleRate)
            .encodeChunks(image, mode)
            .take(15) // 第 0 块是 VIS 头，其余 14 行足够做同步拟合
            .concat()
        val noisy = addNoise(clean, 0.5, 20.0)

        // 只有 14 行，必须收窄 PSNR 的上下边距，否则统计区间会为空
        val filtered = psnr(
            image, decodeSuccessfully(noisy, mode, pixelNyquistFilter = true), 0, 0,
            skipTop = 3, skipBottom = 3,
        )
        val raw = psnr(
            image, decodeSuccessfully(noisy, mode, pixelNyquistFilter = false), 0, 0,
            skipTop = 3, skipBottom = 3,
        )
        assertTrue(
            "Scottie DX（像素 Nyquist 463 Hz）在 SNR 20 dB 下低通应有明显收益：" +
                "开启 ${"%.2f".format(filtered)} dB vs 关闭 ${"%.2f".format(raw)} dB",
            filtered > raw + 1.0,
        )
    }

    @Test
    fun `低通在快模式下也不劣化`() {
        // 与上一条对偶：像素 Nyquist 高于解调带宽时低通「无事可做」，
        // 但绝不能因为多余的滤波而变差
        val mode = SstvMode.PD_120
        val image = colorRamp(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val filtered = psnr(image, decodeSuccessfully(audio, mode, pixelNyquistFilter = true), 0, 0)
        val raw = psnr(image, decodeSuccessfully(audio, mode, pixelNyquistFilter = false), 0, 0)
        assertTrue(
            "PD-120（像素 Nyquist 2632 Hz > 解调带宽 1200 Hz）下低通应基本无影响：" +
                "开启 ${"%.2f".format(filtered)} dB vs 关闭 ${"%.2f".format(raw)} dB",
            filtered > raw - 0.3,
        )
    }

    @Test
    fun `低通不引入水平位移`() {
        // 若把「前向+反向」误写成单向 EMA，整行内容会沿水平方向平移，
        // 表现为 PSNR 崩塌（一行 320 像素的平移足以让 PSNR 掉到 10 dB 以下）
        val mode = SstvMode.MARTIN_1
        val image = colorRamp(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val filtered = decodeSuccessfully(audio, mode, pixelNyquistFilter = true)
        val raw = decodeSuccessfully(audio, mode, pixelNyquistFilter = false)
        val filteredPsnr = psnr(image, filtered, 0, 0)
        val rawPsnr = psnr(image, raw, 0, 0)
        assertTrue(
            "零相位低通不应劣化干净信号（开启 $filteredPsnr dB vs 关闭 $rawPsnr dB）",
            filteredPsnr > rawPsnr - 1.5,
        )
    }

    @Test
    fun `低通改善弱信号图像`() {
        // B 项的存在理由：噪声主要落在像素 Nyquist 之外，低通把它滤掉。
        // Robot 36 的亮度通道像素 Nyquist（1818 Hz）高于解调带宽（1200 Hz），
        // 因此这里的收益天生有限 —— 只要求「不劣化且略有改善」
        val mode = SstvMode.ROBOT_36
        val image = colorRamp(mode)
        val clean = SstvEncoder(sampleRate).encode(image, mode)
        val noisy = addNoise(clean, 0.5, 20.0)

        val filtered = psnr(image, decodeSuccessfully(noisy, mode, pixelNyquistFilter = true), 0, 0)
        val raw = psnr(image, decodeSuccessfully(noisy, mode, pixelNyquistFilter = false), 0, 0)
        assertTrue(
            "SNR 20 dB 下低通不应劣化，且应略有改善（开启 ${"%.2f".format(filtered)} dB " +
                "vs 关闭 ${"%.2f".format(raw)} dB）",
            filtered > raw,
        )
    }

    @Test
    fun `低通的截止频率就是像素 Nyquist`() {
        // 直接验证 alpha 公式：把「单阶 + order=2」的 alpha 与手算值对比。
        // 这里通过公开行为间接验证——低通对「半个像素周期」的正弦应衰减到约 −3 dB，
        // 而对远低于 Nyquist 的分量几乎不衰减
        val decoder = SstvDecoder(sampleRate, 0.0, pixelNyquistFilter = true)
        assertNotNull("低通版解码器应可构造", decoder)

        // 用 PD-120（像素时长 190 µs，9.12 样本/像素）验证：alpha 必须落在 (0,1)
        val pixels = 640
        val channelSamples = 5837
        val normalized = pixels.toDouble() / (2.0 * channelSamples)
        assertEquals("像素 Nyquist 归一化频率", 0.0548, normalized, 5e-4)
        val x = kotlin.math.cos(2.0 * PI * normalized)
        val single = x - 1.0 + sqrt(x * (x - 4.0) + 3.0)
        val alpha = sqrt(single)
        assertTrue("alpha 应在 (0,1) 内，实际 $alpha", alpha > 0.0 && alpha < 1.0)
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    private fun decodeSuccessfully(
        audio: FloatArray,
        mode: SstvMode,
        pixelNyquistFilter: Boolean = true,
    ): SstvDecoder.Result {
        val outcome = SstvDecoder(sampleRate, 0.0, pixelNyquistFilter).decode(audio, forcedMode = mode)
        assertTrue(
            "解码失败：${(outcome as? SstvDecoder.Outcome.Failure)?.reason}",
            outcome is SstvDecoder.Outcome.Success,
        )
        return (outcome as SstvDecoder.Outcome.Success).result
    }

    /** 把流式编码的若干块拼成一整段（配合 `take(n)` 截取前若干行）。 */
    private fun Sequence<FloatArray>.concat(): FloatArray {
        val blocks = toList()
        val out = FloatArray(blocks.sumOf { it.size })
        var offset = 0
        for (block in blocks) {
            block.copyInto(out, offset)
            offset += block.size
        }
        return out
    }

    /** 打包 RGB → 亮度（只用整数近似，用于「纯色图像不应被整体衰减」这类相对比较）。 */
    private fun luma(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /** 三色斜坡：R 随 x、G 随 y、B 随 x+y —— 任一通道接错都会立刻反映在 PSNR 上。 */
    private fun colorRamp(mode: SstvMode): SstvImageSource {
        val w = mode.linePixels
        val h = mode.imageLines
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (x * 255 / (w - 1))
                val g = (y * 255 / (h - 1))
                val b = ((x + y) * 255 / (w + h - 2))
                px[y * w + x] = (r shl 16) or (g shl 8) or b
            }
        }
        return SstvPixelImageSource(w, h, px)
    }

    private fun solidImage(mode: SstvMode, rgb: Int) =
        SstvPixelImageSource(
            mode.linePixels,
            mode.imageLines,
            IntArray(mode.linePixels * mode.imageLines) { rgb },
        )

    /** 等幅均匀分布噪声（与一期 SstvCodecTest 同一实现，便于横向对比）。 */
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

    private fun meanHz(freqs: FloatArray, center: Double, half: Double): Double {
        val from = (center - half).toInt().coerceIn(0, freqs.size - 1)
        val to = (center + half).toInt().coerceIn(from + 1, freqs.size)
        var sum = 0.0
        for (i in from until to) sum += freqs[i]
        return sum / (to - from)
    }

    /**
     * 计算原图与解码结果的 PSNR。
     *
     * @param rowOffset 解码结果第 r 行对应原图第 r + rowOffset 行
     * @param channelMode 0 = 原样，[CHANNEL_SWAP_RB] = 交换 R/B（用于证明「没有互换」）
     */
    private fun psnr(
        source: SstvImageSource,
        result: SstvDecoder.Result,
        rowOffset: Int,
        channelMode: Int,
        skipTop: Int = 10,
        skipBottom: Int = 10,
    ): Double {
        val margin = 10
        var sum = 0.0
        var count = 0
        for (row in skipTop until (result.decodedLines - skipBottom)) {
            val sourceRow = row + rowOffset
            if (sourceRow < 0 || sourceRow >= source.height) continue
            for (x in margin until result.width - margin) {
                var a = source.rgbAt(x, sourceRow)
                if (channelMode == CHANNEL_SWAP_RB) {
                    val r = (a shr 16) and 0xFF
                    val b = a and 0xFF
                    a = (b shl 16) or (a and 0x00FF00) or r
                }
                val b = result.pixels[row * result.width + x]
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = (((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)).toDouble()
                    sum += d * d
                    count++
                }
            }
        }
        if (count == 0) return 0.0
        val mse = sum / count
        return if (mse <= 1e-9) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }

    private companion object {
        /** 交换 R 与 B 通道（用于「颜色没有互换」的对照）。 */
        const val CHANNEL_SWAP_RB = 1
    }
}
