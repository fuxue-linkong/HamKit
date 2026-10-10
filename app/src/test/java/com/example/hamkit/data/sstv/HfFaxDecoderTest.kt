package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * HF Fax（WEFAX）解码器的单元测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖六类容易被想当然的地方：
 *  1. **灰度保真** —— 阶梯图自环，量化 + 水平边缘的误差上界；
 *  2. **启动音状态机** —— 有/无启动音两条路径，以及「强制开始」的取舍；
 *  3. **分块无关性** —— 采集是按 100 ms 一块喂的，结果必须与一次性喂完全一致；
 *  4. **白边对齐** —— 这是 WEFAX 唯一可用的水平锚点，必须真的把白边搬回 x≈0；
 *  5. **失谐** —— 由启动音自动估计频偏，以及手动指定的等价路径；
 *  6. **健壮性** —— 纯噪声、NaN 与野值。
 *
 * 另外补一条**集成约束**的回归用例：上游 [SstvDemodulator] 的相位差分间隔过大时，
 * 300 Hz 启动音会被相位解缠折叠成假频率（见 [HfFaxDecoder] 的类 KDoc）。
 *
 * 编码侧刻意**不复用** [SstvEncoder] —— 它是 SSTV 专用的（VIS 头、逐行同步脉冲、
 * PD/Robot/Martin/Scottie 的通道时序），与 WEFAX 的「120 行/分钟 + 灰度单通道」没有
 * 任何共同结构。测试自带一个最小编码器（见文件末的 [WefaxTestEncoder]）。
 */
class HfFaxDecoderTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE
    private val width = HfFaxSpec.WIDTH

    /**
     * 上游解调器的相位差分间隔。
     *
     * 取 8 而不是「按像素时长推荐」的值（1280 像素/秒 → 37.5 样本/像素 → 推荐 16）：
     * 300 Hz 启动音距中心频率 1900 Hz 有 1600 Hz，而 span=16 的无模糊范围只有 ±1500 Hz，
     * 会把启动音折叠成约 3300 Hz。见 [相位差分间隔过大时启动音会被折叠]。
     */
    private val demodSpan = 8

    // ══════════════════════════════════════════════════════════════════
    // 1. 灰度保真
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `灰度阶梯图自环`() {
        // 内容最高只到 220，让左侧纯白边（255）成为唯一的「最亮列」——
        // 这样白边对齐不会把图像旋转到一个奇怪的位置
        val levels = intArrayOf(20, 70, 120, 170, 220)
        val row = staircaseRow(levels)
        val rowsData = List(3) { row }

        val image = decodeAudio(wefaxAudio(rowsData, startToneSeconds = 4.0))

        assertEquals("应解出全部行", rowsData.size, image.height)
        assertTrue("应已应用白边对齐", image.aligned)
        assertTrue(
            "白边应被搬回左侧（移位量 ${image.shiftPixels}）",
            image.shiftPixels in 0..8,
        )
        assertLevelsMatch(image, row, tolerance = 3, label = "阶梯图")
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. 启动音状态机
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `有启动音时不对启动音出图`() {
        val rowsData = List(4) { staircaseRow(intArrayOf(40, 160)) }
        val startToneSeconds = 5.0
        val audio = wefaxAudio(rowsData, startToneSeconds = startToneSeconds)
        val freqs = demodulate(audio)
        val toneEnd = (startToneSeconds * sampleRate).roundToInt()

        // ① 分段喂：先只喂启动音（留 1000 个样本确保仍在音内）
        val segmented = HfFaxDecoder(sampleRate)
        segmented.feed(freqs.copyOfRange(0, toneEnd - 1000))
        assertTrue("启动音期间应仍处于等待状态", segmented.isWaitingForStartTone)
        assertTrue("应已确认检测到启动音（正在等它结束）", segmented.isStartToneDetected)
        assertEquals("启动音不得产出任何图像行", 0, segmented.decodedLines)

        // 接着从启动音结束处继续喂
        segmented.feed(freqs, toneEnd)
        assertFalse("检测到启动音后应进入出图状态", segmented.isWaitingForStartTone)
        assertEquals("图像部分应解出全部行", rowsData.size, segmented.decodedLines)
        assertTrue("启动音结束处就是图像起点，行时钟应对齐到那里", segmented.snapshot().aligned)

        // ② 一次性喂完整段：行数必须与分段喂一致（状态机不依赖分块）
        val whole = HfFaxDecoder(sampleRate)
        whole.feed(freqs)
        assertEquals("一次性喂完整段应得到同样的行数", rowsData.size, whole.decodedLines)
    }

    @Test
    fun `无启动音时一直等待且强制开始后可出图`() {
        val rowsData = List(4) { staircaseRow(intArrayOf(40, 160)) }
        // 完全没有启动音：图像直接从第一个样本开始
        val audio = wefaxAudio(rowsData, startToneSeconds = 0.0)
        val freqs = demodulate(audio)
        val decoder = HfFaxDecoder(sampleRate)

        decoder.feed(freqs)
        assertTrue("没有启动音时应一直等待", decoder.isWaitingForStartTone)
        assertFalse("不应误报检测到启动音", decoder.isStartToneDetected)
        assertEquals("等待期间不得产出图像行", 0, decoder.decodedLines)

        // 强制开始：已消费的样本不回放，因此要再喂一段才会出图
        decoder.startImmediately()
        assertFalse("强制开始后不应再处于等待状态", decoder.isWaitingForStartTone)
        assertEquals("强制开始本身不产生行", 0, decoder.decodedLines)

        val more = wefaxAudio(rowsData, startToneSeconds = 0.0)
        decoder.feed(demodulate(more))
        assertEquals("强制开始后应解出全部行", rowsData.size, decoder.decodedLines)
        assertTrue("强制开始后仍应能对齐（列统计不依赖启动音）", decoder.snapshot().aligned)
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 分块无关性
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `分块喂入与一次性喂入结果逐像素一致`() {
        val rowsData = List(3) { staircaseRow(intArrayOf(30, 110, 200)) }
        val audio = wefaxAudio(rowsData, startToneSeconds = 4.0)
        // 两条路径共用同一条频率流，排除解调差异的干扰
        val freqs = demodulate(audio)

        val once = HfFaxDecoder(sampleRate).also { it.feed(freqs) }.snapshot()

        val chunked = HfFaxDecoder(sampleRate)
        // 每次只喂半行（12000 样本）——刻意让分块边界落在行中间
        val chunk = chunked.lineSamples / 2
        var index = 0
        while (index < freqs.size) {
            val end = minOf(index + chunk, freqs.size)
            chunked.feed(freqs.copyOfRange(index, end))
            index = end
        }
        val chunkedImage = chunked.snapshot()

        assertEquals("行数应一致", once.height, chunkedImage.height)
        assertEquals("对齐移位量应一致", once.shiftPixels, chunkedImage.shiftPixels)
        assertEquals("对齐标记应一致", once.aligned, chunkedImage.aligned)
        assertTrue(
            "分块喂入与一次性喂入必须逐像素一致",
            once.pixels.contentEquals(chunkedImage.pixels),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 白边对齐
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `白边偏移时快照把它移回 x 附近`() {
        // 真实场景：接收端自由运行行时钟，白边落在哪一列完全未知
        val edgeAt = 50
        val row = shiftedEdgeRow(edgeAt)
        val rowsData = List(8) { row }

        val image = decodeAudio(wefaxAudio(rowsData, startToneSeconds = 4.0))

        assertTrue("应有对齐结果", image.aligned)
        assertTrue(
            "移位量应落在白边所在的 [$edgeAt, ${edgeAt + 7}]，实际 ${image.shiftPixels}",
            image.shiftPixels in edgeAt..(edgeAt + 7),
        )
        // 白边中心被搬到 x=0 后，最前面几列必须是亮像素
        assertTrue("对齐后第 0 列应接近纯白，实际 ${image.pixels[0]}", image.pixels[0] >= 200)
        assertTrue("对齐后第 1 列应接近纯白，实际 ${image.pixels[1]}", image.pixels[1] >= 200)
        // 暗背景没有被拉伸/压缩：除白边（含循环绕回右端）以外的列都还是暗的
        for (x in 20 until width - 20) {
            assertTrue(
                "第 $x 列本应是暗背景，实际 ${image.pixels[x]}",
                image.pixels[x] < 120,
            )
        }
        // 未被对齐的原始行必须在偏移处：
        assertTrue(
            "原始行白边应在 $edgeAt 附近，实际最亮列 ${brightestColumn(row)}",
            brightestColumn(row) in edgeAt..(edgeAt + 7),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. 失谐
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `失谐偏移下仍能出图且自动估计频偏`() {
        val offsetHz = 50.0
        val levels = intArrayOf(24, 96, 168, 232)
        val row = staircaseRow(levels)
        val rowsData = List(3) { row }
        // 启动音与图像一起被抬高 50 Hz（模拟 SSB 调谐偏高）
        val audio = wefaxAudio(rowsData, startToneSeconds = 4.0, freqOffsetHz = offsetHz)

        // ① 自动路径：调用方不需要知道频偏
        val auto = HfFaxDecoder(sampleRate)
        auto.feed(demodulate(audio))
        val measured = auto.measuredOffsetHz
        assertTrue("应自动估计出频偏，实际 $measured", measured != null)
        assertEquals("自动估计的频偏应接近 $offsetHz Hz", offsetHz, measured!!, 6.0)
        val autoImage = auto.snapshot()
        assertEquals(rowsData.size, autoImage.height)
        assertLevelsMatch(autoImage, row, tolerance = 4, label = "频偏自动估计")

        // ② 手动路径：用于没有启动音的素材，必须与自动路径等价
        val manual = HfFaxDecoder(sampleRate)
        manual.frequencyOffsetHz = offsetHz
        manual.feed(demodulate(audio))
        assertEquals(rowsData.size, manual.decodedLines)
        assertTrue("手动指定频偏后启动音也应能被识别", !manual.isWaitingForStartTone)
        assertLevelsMatch(manual.snapshot(), row, tolerance = 4, label = "频偏手动指定")
    }

    // ══════════════════════════════════════════════════════════════════
    // 6. 健壮性
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `纯噪声不产生有意义的图像`() {
        val freqs = demodulate(uniformNoise(sampleRate * 8))

        // ① 没有强制开始时：不应误判启动音，也不应出图
        val waiting = HfFaxDecoder(sampleRate)
        waiting.feed(freqs)
        assertTrue("纯噪声不得误判出启动音", waiting.isWaitingForStartTone)
        assertEquals("等待期间不得产出图像行", 0, waiting.decodedLines)

        // ② 强制开始后：行数有界、像素值合法、不崩
        val forced = HfFaxDecoder(sampleRate, maxLines = 20)
        forced.startImmediately()
        forced.feed(freqs)
        assertEquals("8 秒噪声应填满 16 行", 16, forced.decodedLines)
        val image = forced.snapshot()
        assertEquals(16, image.height)
        for ((index, value) in image.pixels.withIndex()) {
            assertTrue("第 $index 个像素越界：$value", value in 0..255)
        }
    }

    @Test
    fun `到达行数上限后停止增长`() {
        val rowsData = List(12) { staircaseRow(intArrayOf(60, 180)) }
        val decoder = HfFaxDecoder(sampleRate, maxLines = 5)
        decoder.feed(demodulate(wefaxAudio(rowsData, startToneSeconds = 3.0)))

        assertEquals("到达上限后不得继续增长", 5, decoder.decodedLines)
        assertEquals("快照高度也应封顶", 5, decoder.snapshot().height)
    }

    @Test
    fun `NaN 与野值被夹紧且不污染图像`() {
        val row = IntArray(width) { 128 } // 纯中灰：恰好落在解调中心频率上
        val rowsData = List(2) { row }
        val startToneSeconds = 3.0
        val freqs = demodulate(wefaxAudio(rowsData, startToneSeconds = startToneSeconds)).copyOf()

        // 只在**启动音之后**注入：启动音期间的野值会把「连续低频音」打断，
        // 那样测的就不是「坏样本会不会污染图像」，而是「坏样本会不会打断启动音检测」了
        val imageFrom = (startToneSeconds * sampleRate).roundToInt()

        // 两类不可信样本，间距取足够大以免彼此叠加：
        //  · NaN —— 应被「沿用上一次有效值」吸收，理论上零影响；
        //  · 远超出黑白范围的野值 —— 应被夹紧到 +2.0 亮度（相当于 4000 Hz 的野值）
        var nanCount = 0
        var wildCount = 0
        for (i in imageFrom until freqs.size step 501) {
            freqs[i] = Float.NaN
            nanCount++
        }
        for (i in imageFrom until freqs.size step 4000) {
            freqs[(i + 3).coerceAtMost(freqs.size - 1)] = 99_999f
            wildCount++
        }
        assertTrue("应确实注入了样本（NaN=$nanCount，野值=$wildCount）", nanCount + wildCount > 20)

        val decoder = HfFaxDecoder(sampleRate)
        decoder.feed(freqs)
        assertEquals("少量坏样本不应影响行数", rowsData.size, decoder.decodedLines)

        val image = decoder.snapshot()
        for ((index, value) in image.pixels.withIndex()) {
            assertTrue("第 $index 个像素越界：$value", value in 0..255)
        }

        // 断言的是「绝大多数像素不受影响 + 整体偏移有界」，而不是逐像素精确：
        // 单点野值经过零相位低通后必然摊开成一小段条纹（低通的固有行为，方向对称、
        // 不产生位移），所以「毫发无损」本就不是正确预期
        val sorted = image.pixels.sorted()
        val median = sorted[sorted.size / 2]
        assertTrue("中位灰度应仍为 128，实际 $median", abs(median - 128) <= 3)
        val mean = image.pixels.average()
        assertTrue("平均灰度 $mean 的整体偏移应有界（±10）", abs(mean - 128.0) < 10.0)
        val clean = image.pixels.count { abs(it - 128) <= 20 }
        assertTrue(
            "至少 80% 的像素应仍接近 128，实际 ${clean * 100 / image.pixels.size}%",
            clean * 100 / image.pixels.size >= 80,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 7. 集成约束：上游解调器的相位差分间隔
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `相位差分间隔过大时启动音会被折叠`() {
        // 这不是本解码器的缺陷，而是上游参数选择的硬约束：300 Hz 距中心 1900 Hz 有
        // 1600 Hz，而 span 的无模糊范围是 ±sampleRate/(2·span)。
        // 48 kHz 下 span=16 → 只有 ±1500 Hz，300 Hz 会被解缠折叠成约 3300 Hz。
        val rowsData = List(2) { staircaseRow(intArrayOf(60, 180)) }
        val audio = wefaxAudio(rowsData, startToneSeconds = 4.0)

        val good = HfFaxDecoder(sampleRate)
        good.feed(demodulate(audio, span = demodSpan))
        assertFalse("span=$demodSpan 应能检测到启动音", good.isWaitingForStartTone)
        assertEquals(rowsData.size, good.decodedLines)

        val folded = HfFaxDecoder(sampleRate)
        val foldedFreqs = demodulate(audio, span = 16)
        folded.feed(foldedFreqs)
        assertEquals(
            "span=16 会把 300 Hz 折叠成约 3300 Hz，启动音不可能被检测到",
            0,
            folded.decodedLines,
        )
        // 顺带把折叠后的实际频率固定下来，避免以后有人「顺手」放宽 span
        val toneSample = (3.0 * sampleRate).toInt()
        assertTrue(
            "折叠后的频率应远离 300 Hz（实际 ${foldedFreqs[toneSample]} Hz）",
            abs(foldedFreqs[toneSample] - HfFaxSpec.START_TONE_HZ) > 1000.0,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /** 生成音频 → 解调 → 解码，返回快照。 */
    private fun decodeAudio(audio: FloatArray, span: Int = demodSpan): HfFaxImage {
        val decoder = HfFaxDecoder(sampleRate)
        decoder.feed(demodulate(audio, span))
        return decoder.snapshot()
    }

    private fun demodulate(audio: FloatArray, span: Int = demodSpan): FloatArray =
        SstvDemodulator(sampleRate = sampleRate, phaseSpan = span).demodulate(audio)

    private fun wefaxAudio(
        rows: List<IntArray>,
        startToneSeconds: Double,
        freqOffsetHz: Double = 0.0,
    ): FloatArray {
        val encoder = WefaxTestEncoder(sampleRate)
        if (startToneSeconds > 0.0) encoder.startTone(startToneSeconds, freqOffsetHz)
        encoder.rows(rows, freqOffsetHz)
        // 尾部补 0.2 s 黑电平：解码器的行时钟从「启动音结束」起算，会吃掉起点处
        // 几个样本，没有尾巴时最后一行会因缺样本而不完整
        encoder.tail(0.2, freqOffsetHz)
        return encoder.build()
    }

    /**
     * 逐像素比较解出的灰度与源行。
     *
     * 只比较**平台内部**的像素：距平台边界 [MARGIN] 列以内的像素会被像素 Nyquist 低通
     * 抹平（这是传真信号本身的带宽限制，不是实现缺陷）。同时因为对齐最多旋转 [MARGIN]
     * 列，平台内部的期望值与旋转无关，可以直接拿源行同一下标比较。
     */
    private fun assertLevelsMatch(
        image: HfFaxImage,
        sourceRow: IntArray,
        tolerance: Int,
        label: String,
    ) {
        assertTrue("$label：移位量 ${image.shiftPixels} 超出假定范围", image.shiftPixels <= MARGIN)
        var checked = 0
        var maxError = 0
        var worst = ""
        for (y in 0 until image.height) {
            for (x in MARGIN until width - MARGIN) {
                val expected = sourceRow[x]
                if (sourceRow[x - MARGIN] != expected || sourceRow[x + MARGIN] != expected) continue
                val actual = image.pixels[y * width + x]
                val error = abs(expected - actual)
                if (error > maxError) {
                    maxError = error
                    worst = "行 $y 第 $x 列：期望 $expected，实际 $actual"
                }
                checked++
            }
        }
        assertTrue("$label：参与比较的像素过少（$checked）", checked > 500)
        assertTrue(
            "$label：最大灰度误差 $maxError 级超出容差 $tolerance（$worst）",
            maxError <= tolerance,
        )
    }

    /** 最亮列的下标（用于核对源图内容）。 */
    private fun brightestColumn(row: IntArray): Int {
        var best = 0
        for (x in 1 until row.size) if (row[x] > row[best]) best = x
        return best
    }

    /** 阶梯行：最左一条纯白粗边（真实 WEFAX 图像的特征），其后等宽的灰度台阶。 */
    private fun staircaseRow(levels: IntArray, edgeWidth: Int = 8, edgeLevel: Int = 255): IntArray {
        val content = width - edgeWidth
        return IntArray(width) { x ->
            if (x < edgeWidth) {
                edgeLevel
            } else {
                levels[((x - edgeWidth) * levels.size / content).coerceIn(0, levels.size - 1)]
            }
        }
    }

    /** 白边整体偏移到 [edgeAt] 的行：暗背景 + 一条中间更亮的粗白边。 */
    private fun shiftedEdgeRow(edgeAt: Int, background: Int = 40, edgeWidth: Int = 8): IntArray =
        IntArray(width) { x ->
            when {
                // 中间两列更亮 → 最亮列唯一（否则白边平台内部会出现 argmax 并列）
                x == edgeAt + 3 || x == edgeAt + 4 -> 255
                x in edgeAt until (edgeAt + edgeWidth) -> 220
                else -> background
            }
        }

    /** 均匀分布噪声（确定性的 LCG，便于复现）。 */
    private fun uniformNoise(samples: Int): FloatArray {
        var seed = 20_260_927L
        return FloatArray(samples) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val u = ((seed ushr 33).toInt() % 20000) / 10000.0 - 1.0
            (u * 0.6).toFloat()
        }
    }

    private companion object {
        /** 平台内部判定的边距：必须大于对齐可能产生的最大旋转列数。 */
        const val MARGIN = 12
    }
}

/**
 * 极简 WEFAX 编码器（**仅测试用**）：灰度行 → 音频。
 *
 * 只做三件事：连续相位正弦、每像素一个频率、每个图像行恰好
 * `round(LINE_SECONDS * sampleRate)` 个样本。刻意不做启动音之外的任何「协议」内容
 * （真实信号在启动音之后还有一段黑白相间的 phasing 信号，本解码器不处理，见其 KDoc）。
 */
private class WefaxTestEncoder(private val sampleRate: Int) {

    private val lineSamples = (HfFaxSpec.LINE_SECONDS * sampleRate).roundToInt()
    private var buffer = FloatArray(1 shl 16)
    private var size = 0

    /** 振荡器相位跨段连续 —— 相位不连续会在解调输出里炸出一片野值。 */
    private var phase = 0.0

    /** 追加启动音（默认 300 Hz）。 */
    fun startTone(seconds: Double, offsetHz: Double = 0.0) {
        appendTone(HfFaxSpec.START_TONE_HZ + offsetHz, (seconds * sampleRate).roundToInt())
    }

    /** 追加尾部黑电平（让最后一行有足够样本收满）。 */
    fun tail(seconds: Double, offsetHz: Double = 0.0) {
        appendTone(HfFaxSpec.BLACK_HZ + offsetHz, (seconds * sampleRate).roundToInt())
    }

    /**
     * 追加若干灰度行。
     *
     * 像素时长用**时间累加器**换算（与 `SstvEncoder` 同一手法）：640 像素要铺满
     * 24000 个样本，每像素 37.5 个样本不是整数，逐像素四舍五入会让每行短掉 320 个样本
     * （6.4 ms，约 1.3% 的行长），接收端按标称时序切行时会逐行漂移。
     */
    fun rows(rows: List<IntArray>, offsetHz: Double = 0.0) {
        for (row in rows) {
            var written = 0
            for (x in row.indices) {
                val end = ((x + 1).toDouble() * lineSamples / row.size).roundToInt()
                val level = row[x] / 255.0
                val freq = HfFaxSpec.BLACK_HZ +
                    level * (HfFaxSpec.WHITE_HZ - HfFaxSpec.BLACK_HZ) + offsetHz
                appendTone(freq, end - written)
                written = end
            }
        }
    }

    fun build(): FloatArray = buffer.copyOf(size)

    private fun appendTone(freqHz: Double, samples: Int) {
        if (samples <= 0) return
        ensureCapacity(size + samples)
        val step = 2.0 * PI * freqHz / sampleRate
        repeat(samples) {
            buffer[size++] = sin(phase).toFloat()
            phase += step
            if (phase >= TWO_PI) phase -= TWO_PI
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var newSize = buffer.size
        while (newSize < required) newSize = newSize shl 1
        buffer = buffer.copyOf(newSize)
    }

    private companion object {
        const val TWO_PI = 2.0 * PI
    }
}
