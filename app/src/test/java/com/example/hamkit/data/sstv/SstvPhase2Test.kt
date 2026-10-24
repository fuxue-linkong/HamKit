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
 * SSTV 二期新增能力的单元测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖四块：
 *  1. **模式扩展** —— PD-50/90/160/290 与 Robot 8/12/24 B/W 的参数、自环保真、奇校验 VIS；
 *  2. **流式编码** —— [SstvEncoder.encodeChunks] 与 [SstvEncoder.encode] 逐样本等价，
 *     且分块粒度受控（这是发射链路内存上限的前提）；
 *  3. **信号质量与自适应积分窗** —— 质量指标随噪声单调，分档与迟滞行为符合设计；
 *  4. **长模式的部分解码** —— 用流式接口截取前若干行，验证 PD-160/PD-290 的时序正确
 *     （整帧自环对这俩模式而言代价过高，见文件末的说明）。
 */
class SstvPhase2Test {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    // ══════════════════════════════════════════════════════════════════
    // 1. 模式扩展
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `新增 PD 模式的行周期与权威参数一致`() {
        // 数值取自 sstv crate modes/pd.rs 的每通道扫描时长 + 20 ms sync + 2.08 ms porch
        assertEquals(0.38816, SstvMode.PD_50.lineSeconds, 1e-9)
        assertEquals(0.70304, SstvMode.PD_90.lineSeconds, 1e-9)
        assertEquals(0.804416, SstvMode.PD_160.lineSeconds, 1e-9)
        assertEquals(0.93728, SstvMode.PD_290.lineSeconds, 1e-9)

        // 像素时长 = 每通道扫描时长 / 宽度
        assertEquals(0.000286, SstvMode.PD_50.pixelSeconds, 1e-9)
        assertEquals(0.000532, SstvMode.PD_90.pixelSeconds, 1e-9)
        assertEquals(0.000382, SstvMode.PD_160.pixelSeconds, 1e-9)
        assertEquals(0.000286, SstvMode.PD_290.pixelSeconds, 1e-9)
    }

    @Test
    fun `新增 PD 模式的几何参数与行对数一致`() {
        // crate 注释：「128 line pairs / 200 line pairs / 308 line pairs」→ 与
        // imageLines / 2 必须逐一相等，否则整帧时长会与标称值差一截
        assertEquals(128, SstvMode.PD_50.radioLines)
        assertEquals(128, SstvMode.PD_90.radioLines)
        assertEquals(200, SstvMode.PD_160.radioLines)
        assertEquals(308, SstvMode.PD_290.radioLines)

        assertEquals(320, SstvMode.PD_50.linePixels)
        assertEquals(256, SstvMode.PD_50.imageLines)
        assertEquals(512, SstvMode.PD_160.linePixels)
        assertEquals(400, SstvMode.PD_160.imageLines)
        assertEquals(800, SstvMode.PD_290.linePixels)
        assertEquals(616, SstvMode.PD_290.imageLines)
    }

    @Test
    fun `新增 PD 模式的帧时长符合标称秒数`() {
        // 模式名里的秒数就是设计目标，误差来自「行周期 × 行对数」的取整
        assertEquals(49.7, SstvMode.PD_50.frameSeconds, 0.3)
        assertEquals(90.0, SstvMode.PD_90.frameSeconds, 0.3)
        assertEquals(160.9, SstvMode.PD_160.frameSeconds, 0.3)
        assertEquals(288.7, SstvMode.PD_290.frameSeconds, 0.5)
    }

    @Test
    fun `VIS 码全表唯一且关键值与权威表一致`() {
        // 唯一性：fromVisCode 用 firstOrNull 查表，重复码会让先定义的模式
        // 永远遮住后定义的，收/发两侧都可能选错模式
        val codes = SstvMode.entries.map { it.visCode }
        assertEquals(
            "存在重复的 VIS 码: ${codes.groupingBy { it }.eachCount().filterValues { it > 1 }}",
            codes.size,
            codes.toSet().size,
        )
        // 权威值抽查（KB4YZ VIS 表 / pySSTV / slowrx 三源一致）：
        // PD-290 曾误写 0x64（未分配的码），导致真实 PD-290 信号无法识别
        assertEquals(0x5E, SstvMode.PD_290.visCode)
        assertEquals(0x5F, SstvMode.PD_120.visCode)
        assertEquals(0x5D, SstvMode.PD_50.visCode)
        assertEquals(0x08, SstvMode.ROBOT_36.visCode)
        assertEquals(0x3C, SstvMode.SCOTTIE_1.visCode)
        // 每个码都能反查回同一个模式
        for (mode in SstvMode.entries) {
            assertEquals(mode, SstvMode.fromVisCode(mode.visCode))
        }
    }

    @Test
    fun `黑白模式为单通道且无 porch 无分隔`() {
        for (mode in listOf(SstvMode.ROBOT_8_BW, SstvMode.ROBOT_12_BW, SstvMode.ROBOT_24_BW)) {
            assertTrue("${mode.displayName} 应为灰度模式", mode.isMonochrome)
            val tasks = mode.channelTasks()
            assertEquals("${mode.displayName} 只应有一个通道", 1, tasks.size)
            assertEquals(SstvChannelRole.MONO, tasks[0].role)
            assertEquals("${mode.displayName} porch 应为 0", 0.0, mode.porchSeconds, 1e-12)
            assertEquals("${mode.displayName} 分隔应为 0", 0.0, mode.separatorSeconds, 1e-12)
            // 行占用时间应精确等于行周期（单通道铺满整行）
            assertEquals(
                "${mode.displayName} 通道应铺满整行",
                mode.lineSeconds,
                mode.lineOccupiedSeconds,
                1e-9,
            )
        }
    }

    @Test
    fun `黑白模式的帧时长符合标称秒数`() {
        // pySSTV grayscale.py：(sync + scan) × 行数 = 标称秒数
        assertEquals(67.0, SstvMode.ROBOT_8_BW.lineSeconds * 1000, 1e-3)
        assertEquals(8.04, SstvMode.ROBOT_8_BW.frameSeconds, 0.05)
        assertEquals(12.0, SstvMode.ROBOT_12_BW.frameSeconds, 0.05)
        assertEquals(24.0, SstvMode.ROBOT_24_BW.frameSeconds, 0.05)
    }

    @Test
    fun `行占用时间不超出行周期（含全部新增模式）`() {
        for (mode in SstvMode.entries) {
            assertTrue(
                "${mode.displayName} 行占用 ${mode.lineOccupiedSeconds}s > 行周期 ${mode.lineSeconds}s",
                mode.lineOccupiedSeconds <= mode.lineSeconds + 1e-6,
            )
        }
    }

    @Test
    fun `Robot 12 B W 的奇校验 VIS 能被识别`() {
        // 0x06 是唯一使用奇校验的 VIS 码（slowrx vis.c:116 特意翻转），
        // 一期就写了这个分支但模式表里没有 0x06，因此从未被真正触发
        val audio = SstvEncoder(sampleRate).encodeVis(0x06)
        val detected = SstvVisDetector(sampleRate).process(audio)
        assertNotNull("奇校验 VIS 应被识别", detected)
        assertEquals(0x06, detected!!.visCode)
        assertEquals(SstvMode.ROBOT_12_BW, detected.mode)
    }

    @Test
    fun `全部新增模式的 VIS 码可反查`() {
        val expected = mapOf(
            0x5D to SstvMode.PD_50,
            0x63 to SstvMode.PD_90,
            0x62 to SstvMode.PD_160,
            0x64 to SstvMode.PD_290,
            0x02 to SstvMode.ROBOT_8_BW,
            0x06 to SstvMode.ROBOT_12_BW,
            0x0A to SstvMode.ROBOT_24_BW,
        )
        for ((code, mode) in expected) {
            assertEquals("VIS 0x${code.toString(16)} 应解析为 ${mode.displayName}", mode, SstvMode.fromVisCode(code))
        }
    }

    @Test
    fun `新增黑白模式自环保真`() {
        // 灰度内容：编码取亮度、解码写回 R=G=B，全程不经色彩空间往返
        assertMonoRoundTrip(SstvMode.ROBOT_8_BW, 36.0)
        assertMonoRoundTrip(SstvMode.ROBOT_12_BW, 36.0)
        assertMonoRoundTrip(SstvMode.ROBOT_24_BW, 36.0)
    }

    @Test
    fun `PD 50 与 PD 90 自环保真`() {
        assertRoundTrip(SstvMode.PD_50, graySineImage(SstvMode.PD_50, cycles = 2), 30.0)
        assertRoundTrip(SstvMode.PD_90, graySineImage(SstvMode.PD_90, cycles = 2), 30.0)
    }

    /**
     * 长模式用「流式编码截取前若干行」验证时序。
     *
     * PD-160（161 s）与 PD-290（289 s）整帧自环要处理 7.7 M / 13.9 M 样本，
     * 单条用例就要几十秒与上百 MB 内存，不适合放进常规单测。截取前 [PARTIAL_LINES]
     * 行已经足够验证「通道时序 + 行同步间隔」是否正确 —— 一旦行周期录错，
     * 前 8 行的拟合就会失败或图像明显错位。
     */
    @Test
    fun `PD 160 与 PD 290 的前若干行可正确解码`() {
        assertPartialDecode(SstvMode.PD_160, 30.0)
        assertPartialDecode(SstvMode.PD_290, 30.0)
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. 流式编码
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `流式编码与整段编码逐样本一致`() {
        // 发射链路走 encodeChunks、自环测试走 encode，两者必须完全等价，
        // 否则「对着电台播出去的」和「单测验证过的」就不是同一段音频
        for (mode in listOf(SstvMode.ROBOT_8_BW, SstvMode.ROBOT_36, SstvMode.PD_50)) {
            val image = graySineImage(mode, cycles = 1)
            val whole = SstvEncoder(sampleRate).encode(image, mode)
            val streamed = SstvEncoder(sampleRate).encodeChunks(image, mode).concat()
            assertEquals("${mode.displayName} 样本数不一致", whole.size, streamed.size)
            assertTrue(
                "${mode.displayName} 流式与整段编码存在差异",
                whole.contentEquals(streamed),
            )
        }
    }

    @Test
    fun `流式编码的分块粒度受控`() {
        // 内存上限的前提：任何一块都不应接近整帧长度
        val mode = SstvMode.PD_50
        val image = graySineImage(mode, cycles = 1)
        val chunks = SstvEncoder(sampleRate).encodeChunks(image, mode).toList()
        val frame = SstvEncoder(sampleRate).estimateSampleCount(mode)

        assertEquals("VIS 一块 + 每个无线行一块", mode.radioLines + 1, chunks.size)
        // VIS 头本身是一块（0.91 秒），快模式下它比一个无线行还长，因此上界取两者较大值
        val visSamples = (SstvEncoder.VIS_TOTAL_SECONDS * sampleRate).toInt()
        val lineSamples = (mode.lineSeconds * sampleRate).toInt()
        val chunkBound = maxOf(visSamples, lineSamples) + 4096
        for ((index, chunk) in chunks.withIndex()) {
            assertTrue(
                "第 $index 块 ${chunk.size} 样本超过单块上限 $chunkBound",
                chunk.size <= chunkBound,
            )
        }
        // 整帧样本量远超单块，说明确实没有整段驻留
        assertTrue("整帧应远大于单块", frame > chunks.maxOf { it.size } * 4)
    }

    @Test
    fun `流式编码可中断且不写完整个序列`() {
        // 发射中途停止时，序列必须能被提前抛弃而不继续生成
        val mode = SstvMode.PD_290
        val image = graySineImage(mode, cycles = 1)
        var produced = 0
        for (chunk in SstvEncoder(sampleRate).encodeChunks(image, mode)) {
            produced += chunk.size
            break
        }
        // 只应产出 VIS 头（约 0.91 秒）
        assertTrue("提前中断后只应产出 VIS 头，实际 $produced 样本", produced <= sampleRate)
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 信号质量与自适应积分窗
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `等效信噪比随噪声增大而单调下降`() {
        // 质量估计需要**恒定频率**的一段音频（SSTV 的 leader / 同步脉冲就是），
        // 因为图像内容本身就在快速改变频率，无法与噪声区分。这里直接用 VIS 头：
        // 它由 300 ms leader（1900 Hz）、1200/1100/1300 Hz 的位音组成，每段都是纯音。
        val probe = SstvEncoder(sampleRate).encodeVis(SstvMode.PD_120.visCode)
        val qClean = SstvDemodulator.estimateQualityDb(probe, sampleRate)
        val q40 = SstvDemodulator.estimateQualityDb(addNoise(probe, 0.5, 40.0), sampleRate)
        val q30 = SstvDemodulator.estimateQualityDb(addNoise(probe, 0.5, 30.0), sampleRate)
        val q20 = SstvDemodulator.estimateQualityDb(addNoise(probe, 0.5, 20.0), sampleRate)
        val q10 = SstvDemodulator.estimateQualityDb(addNoise(probe, 0.5, 10.0), sampleRate)

        // 打印实测值：阈值标定依赖这组数据（见 QUALITY_TIER_BOUNDS_DB 的说明）
        println(
            "等效信噪比实测：clean=$qClean, 40dB=$q40, 30dB=$q30, 20dB=$q20, 10dB=$q10 " +
                "→ 档位 ${SstvDemodulator.qualityTier(qClean)}/" +
                "${SstvDemodulator.qualityTier(q40)}/${SstvDemodulator.qualityTier(q30)}/" +
                "${SstvDemodulator.qualityTier(q20)}/${SstvDemodulator.qualityTier(q10)} " +
                "→ 积分窗 ${listOf(qClean, q40, q30, q20, q10).joinToString { "${SstvDemodulator.recommendAdaptiveSpan(9.12, SstvDemodulator.qualityTier(it))}" }}",
        )

        // 单调性只在**噪声主导**的区间断言。40 dB 以上本指标已不再由噪声决定，
        // 而是由 8 阶低通对 2f₀ 镜像的抑制深度（≈ −80 dB）决定，因此
        // 「干净」与「SNR 40 dB」谁高谁低没有物理意义（实测两者相差 < 1 dB）
        assertTrue("SNR 40 dB（$q40）应高于 30 dB（$q30）", q40 > q30)
        assertTrue("SNR 30 dB（$q30）应高于 20 dB（$q20）", q30 > q20)
        assertTrue("SNR 20 dB（$q20）应高于 10 dB（$q10）", q20 > q10)
        // 两端必须真的分得开，否则指标就没有分辨力
        assertTrue("干净信号 $qClean 应明显高于 SNR 10 dB 的 $q10", qClean > q10 + 10.0)

        // T6 的量化验收：在「SNR 10 dB」这个工作点上，指标要落到会**真正增大积分窗**
        // 的档位（否则自适应窗形同虚设）。该断言与合成信号的噪声模型绑定，
        // 因此噪声生成器（addNoise）的约定变更时必须同步复核
        assertTrue(
            "SNR 10 dB（$q10 dB）应落入弱信号档，实际档位 ${SstvDemodulator.qualityTier(q10)}",
            SstvDemodulator.qualityTier(q10) >= 2,
        )
        assertTrue(
            "SNR 30 dB（$q30 dB）不应改变一期的积分窗（档位 ${SstvDemodulator.qualityTier(q30)}）",
            SstvDemodulator.qualityTier(q30) <= 1,
        )
        assertTrue(
            "弱信号档必须给出更大的积分窗，实际 " +
                "${SstvDemodulator.recommendAdaptiveSpan(9.12, SstvDemodulator.qualityTier(q10))} vs 一期 5",
            SstvDemodulator.recommendAdaptiveSpan(9.12, SstvDemodulator.qualityTier(q10)) > 5,
        )
    }

    @Test
    fun `弱信号档位给出更大的积分窗`() {
        val samplesPerPixel = SstvMode.PD_120.pixelSeconds * sampleRate // 9.12
        val strong = SstvDemodulator.recommendAdaptiveSpan(samplesPerPixel, tier = 0)
        val weak = SstvDemodulator.recommendAdaptiveSpan(samplesPerPixel, tier = 3)
        assertEquals(
            "强信号档应与一期推荐值一致（不改变既有行为）",
            SstvDemodulator.recommendPhaseSpan(samplesPerPixel),
            strong,
        )
        assertTrue("弱信号档应给出更大的积分窗（$weak vs $strong）", weak > strong)
        // 上限不得超过 1.5 像素，否则相邻像素被平均到一起，图像会糊
        val maxAllowed = (samplesPerPixel * 1.5).toInt()
        assertTrue("积分窗 $weak 超过 1.5 像素上限 $maxAllowed", weak <= maxAllowed)
    }

    @Test
    fun `积分窗控制器带 1 dB 迟滞`() {
        val samplesPerPixel = SstvMode.PD_120.pixelSeconds * sampleRate
        val controller = SstvSpanController(samplesPerPixel)
        val normal = controller.span
        assertEquals(
            "初始应为正常档",
            SstvDemodulator.recommendAdaptiveSpan(samplesPerPixel, 1),
            normal,
        )

        // 变差：跨到下一档需要低于边界 1 dB 以上。
        // 「正常」档的下界是 tierLowerBound(1)（48 dB）→ 需 < 47 dB
        val justBelow = SstvDemodulator.tierLowerBound(1) - 0.5
        assertEquals("仅略低于边界不应切换", normal, controller.update(justBelow))
        val weakSpan = controller.update(SstvDemodulator.tierLowerBound(1) - 2.0)
        assertTrue("明显变差后应增大积分窗（$weakSpan vs $normal）", weakSpan > normal)

        // 变好：跨回正常档需要高于该档下界 1 dB 以上
        val justAbove = SstvDemodulator.tierLowerBound(1) + 0.5
        assertEquals("刚过边界不应立刻切回", weakSpan, controller.update(justAbove))
        assertEquals("明显变好后应切回正常档", normal, controller.update(SstvDemodulator.tierLowerBound(1) + 2.0))
    }

    @Test
    fun `质量估计对过短音频给出下界`() {
        assertEquals(
            SstvDemodulator.QUALITY_MIN_DB,
            SstvDemodulator.estimateQualityDb(FloatArray(16), sampleRate),
            1e-9,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /** 把流式编码的所有块拼成一整段（测试用）。 */
    private fun Sequence<FloatArray>.concat(): FloatArray {
        val blocks = toList()
        val total = blocks.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (block in blocks) {
            block.copyInto(out, offset)
            offset += block.size
        }
        return out
    }

    private fun assertRoundTrip(mode: SstvMode, image: SstvImageSource, minCorePsnr: Double) {
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val result = decodeSuccessfully(audio)
        assertEquals("${mode.displayName} 行数", mode.imageLines, result.decodedLines)
        assertEquals("${mode.displayName} slant", 1.0, result.slantRatio, 0.005)
        val core = psnrCentral(image, result, result.height)
        assertTrue("${mode.displayName} 主体 PSNR $core dB < $minCorePsnr dB", core > minCorePsnr)
    }

    /** 灰度模式自环境：编码取亮度、解码写回 R=G=B，因此灰度图应几乎无损。 */
    private fun assertMonoRoundTrip(mode: SstvMode, minCorePsnr: Double) {
        val image = graySineImage(mode, cycles = 1)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val result = decodeSuccessfully(audio)
        assertEquals("${mode.displayName} 行数", mode.imageLines, result.decodedLines)
        // 灰度必须保持：任取一像素，R/G/B 三分量应相等
        for (index in result.pixels.indices step 977) {
            val p = result.pixels[index]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            assertEquals("${mode.displayName} 灰度图不应产生色偏", r, g)
            assertEquals("${mode.displayName} 灰度图不应产生色偏", g, b)
        }
        val core = psnrCentral(image, result, result.height)
        assertTrue("${mode.displayName} 主体 PSNR $core dB < $minCorePsnr dB", core > minCorePsnr)
    }

    /** 只编码前 [PARTIAL_LINES] 个无线行，再整段解码（长模式的低成本验证）。 */
    private fun assertPartialDecode(mode: SstvMode, minCorePsnr: Double) {
        val image = graySineImage(mode, cycles = 1)
        val audio = SstvEncoder(sampleRate)
            .encodeChunks(image, mode)
            .take(PARTIAL_LINES + 1) // 第 0 块是 VIS 头
            .concat()

        val result = decodeSuccessfully(audio)
        val expectedRows = PARTIAL_LINES * mode.imageRowsPerRadioLine
        assertTrue(
            "${mode.displayName} 应解出约 $expectedRows 行，实际 ${result.decodedLines}",
            result.decodedLines >= expectedRows - 2 * mode.imageRowsPerRadioLine,
        )
        assertTrue("${mode.displayName} 不应解满整帧", result.decodedLines < mode.imageLines)
        val core = psnrCentral(image, result, result.decodedLines)
        assertTrue("${mode.displayName} 前 $expectedRows 行 PSNR $core dB < $minCorePsnr dB", core > minCorePsnr)
    }

    private fun decodeSuccessfully(audio: FloatArray): SstvDecoder.Result {
        val outcome = SstvDecoder(sampleRate).decode(audio)
        assertTrue(
            "解码失败：${(outcome as? SstvDecoder.Outcome.Failure)?.reason}",
            outcome is SstvDecoder.Outcome.Success,
        )
        return (outcome as SstvDecoder.Outcome.Success).result
    }

    /**
     * 灰度正弦渐变图（R=G=B）。
     *
     * 刻意用灰度而非彩色：本文件的自环用例同时覆盖彩色与灰度路径，灰度图能让
     * 「黑白模式不应产生色偏」这一断言直接成立。
     */
    private fun graySineImage(mode: SstvMode, cycles: Int): SstvImageSource {
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

    /** 等幅均匀分布噪声，信噪比按 [snrDb] 施加（与一期 SstvCodecTest 同一实现）。 */
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

    /** 去掉每行首尾各 10 像素、只统计前 [rows] 行的 PSNR。 */
    private fun psnrCentral(source: SstvImageSource, result: SstvDecoder.Result, rows: Int): Double {
        // 行数很少时（部分解码用例只有几十行）按比例收窄边距，否则统计区间会为空
        val margin = minOf(10, rows / 4)
        var sum = 0.0
        var count = 0
        for (y in margin until (rows - margin)) {
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
        if (count == 0) return 0.0
        val mse = sum / count
        return if (mse <= 1e-9) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }

    private companion object {
        /**
         * 部分解码验证时截取的无线行数。
         *
         * 取 16 是两方面的折中：既要 ≥ 拟合所需的最少 4 个同步脉冲（8 就够），
         * 又要让 PSNR 的统计区间非空（PD 族 16 行 = 32 图像行，去掉上下边距后仍有余量）。
         */
        const val PARTIAL_LINES = 16
    }
}
