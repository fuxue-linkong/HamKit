package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SSTV 三期 Q3 + Q4「重复帧识别 / 质量筛选 / 配准 / 跨帧融合」单元测试（纯 JVM）。
 *
 * 对应 [docs/SSTV_PHASE3_PLAN.md] §七 的验收标准：
 *  - 同一张图的 4 份带噪副本被识别为同簇并融合，PSNR 较**最佳单帧** ≥ +1 dB；
 *  - 故意混入一张错位帧，融合结果不劣于不混入时（验证筛选有效）；
 *  - 不同图像不会被错误合并。
 *
 * 素材走一期既有链路：编码 → 解调 → 解码，噪声加在**音频**上（与真实接收一致），
 * 而不是加在像素上 —— 这样测的是整条链路，而不是一个理想化的像素平均。
 */
class SstvPhase3FusionTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    // ══════════════════════════════════════════════════════════════════
    // 1. 同图不同噪声 → 同簇；不同图 → 不同簇
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `同一张图的副本判为同簇`() {
        val mode = SstvMode.ROBOT_36
        val image = barsImage(mode)
        val base = decode(image, mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        // 四份不同的噪声，模拟同一张图被独立接收四次
        val copies = (0 until 4).map { index ->
            decodeAudio(addNoise(audio, 0.5, 18.0, seed = 1013L + index * 7907L), mode)
        }
        for ((index, frame) in copies.withIndex()) {
            val similarity = SstvFrameFusion.similarity(base, frame)
            assertTrue("第 $index 份副本与基准的相似度 ${"%.2f".format(similarity)} 应 ≥ 0.7", similarity >= 0.7)
        }
    }

    @Test
    fun `不同图像不会被错误合并`() {
        val mode = SstvMode.ROBOT_36
        val bars = decode(barsImage(mode), mode)
        val gradient = decode(gradientImage(mode), mode)
        val checker = decode(checkerImage(mode), mode)
        // 三张内容差异明显的图两两相似度都应偏低 —— 高于门限就会被错误聚成一簇
        for ((a, b) in listOf(bars to gradient, bars to checker, gradient to checker)) {
            val similarity = SstvFrameFusion.similarity(a, b)
            assertTrue("不同图像相似度 ${"%.2f".format(similarity)} 应 < 0.6", similarity < 0.6)
        }
        // 端到端：三张不同的图各成一簇；簇内只有 1 帧，因此融合结果必须与基准**逐像素相同**
        // —— 一旦被错误合并，就会变成三张图的平均，逐像素一致性立刻失败
        val result = SstvFrameFusion.fuse(listOf(bars, gradient, checker), mode)
        assertNotNull("至少应选出一簇", result)
        assertEquals("每张图各一帧，融合只用其中一簇", 1, result!!.usedFrames)
        assertTrue("融合结果应就是基准图本身（说明没有跨图合并）", result.pixels.contentEquals(bars.pixels))
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. 复现实验二：K=4 融合 ≥ 最佳单帧 + 1 dB
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `四份带噪副本融合后优于最佳单帧`() {
        val mode = SstvMode.ROBOT_36
        val truth = barsImage(mode)
        val reference = decode(truth, mode)
        // SNR 18 dB：调研实测在 15 dB 增益约 +1.1 dB（K=4）；这里留 3 dB 余量，
        // 让用例在当前解调参数下稳定通过，而不是贴着门限
        val frames = (0 until 4).map { index ->
            val noisy = addNoise(
                SstvEncoder(sampleRate).encode(truth, mode),
                amplitude = 0.5,
                snrDb = 18.0,
                seed = 1013L + index * 7907L,
            )
            decodeAudio(noisy, mode)
        }

        val result = SstvFrameFusion.fuse(frames, mode)
        assertNotNull("应产出融合结果", result)
        result!!
        assertEquals("四份副本都应参与融合", 4, result.usedFrames)

        val bestSingle = frames.maxOf { psnrOf(it.pixels, reference.pixels, it.width, it.height) }
        val fused = psnrOf(result.pixels, reference.pixels, result.width, result.height)
        assertTrue(
            "融合 PSNR ${"%.2f".format(fused)} dB 应比最佳单帧 ${"%.2f".format(bestSingle)} dB 高 1 dB 以上",
            fused >= bestSingle + 1.0,
        )
        assertTrue("估计增益应为正（实得 ${"%.2f".format(result.gainDb)} dB）", result.gainDb > 0.0)
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 混入错位帧：筛选必须挡住
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `混入错位帧不会拉低融合质量`() {
        val mode = SstvMode.ROBOT_36
        val truth = barsImage(mode)
        val reference = decode(truth, mode)
        val clean = (0 until 4).map { index ->
            decodeAudio(
                addNoise(SstvEncoder(sampleRate).encode(truth, mode), 0.5, 18.0, seed = 2029L + index * 7919L),
                mode,
            )
        }
        val withoutBad = SstvFrameFusion.fuse(clean, mode)!!
        val pWithout = psnrOf(withoutBad.pixels, reference.pixels, withoutBad.width, withoutBad.height)

        // 错位帧：整体平移 6 行（模拟同步偏了半行并被拟合放大）
        val shifted = shiftRows(clean.first(), 6)
        val withBad = SstvFrameFusion.fuse(clean + shifted, mode)!!
        val pWith = psnrOf(withBad.pixels, reference.pixels, withBad.width, withBad.height)

        assertTrue(
            "混入错位帧后 PSNR ${"%.2f".format(pWith)} 不应低于 ${"%.2f".format(pWithout)}（容差 0.2 dB）",
            pWith >= pWithout - 0.2,
        )
        assertTrue("错位帧应被判为不合格", withBad.rejected.isNotEmpty() || withBad.usedFrames == clean.size)
    }

    @Test
    fun `质量不达标的帧被拒绝并给出原因`() {
        val mode = SstvMode.ROBOT_36
        val good = decodeAudio(
            addNoise(SstvEncoder(sampleRate).encode(barsImage(mode), mode), 0.5, 20.0, seed = 11L),
            mode,
        )
        // ① 同步命中率太低的帧
        val lowSync = SstvFrameFusion.Frame(good.pixels, good.width, good.height, syncHitRate = 0.1f)
        assertNotNull("低命中率应被拒", SstvFrameFusion.screen(lowSync, mode))
        // ② slant 偏太多的帧
        val skewed = SstvFrameFusion.Frame(good.pixels, good.width, good.height, slantRatio = 1.08)
        assertNotNull("行周期偏差过大应被拒", SstvFrameFusion.screen(skewed, mode))
        // ③ 行数不足的帧
        val partial = SstvFrameFusion.Frame(
            good.pixels, good.width, good.height, decodedLines = mode.imageLines / 2,
        )
        assertNotNull("行数不足应被拒", SstvFrameFusion.screen(partial, mode))
        // ④ 正常帧通过
        assertNull("正常帧不应被拒", SstvFrameFusion.screen(good, mode))
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 配准：能找回人为引入的整体偏移
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `配准能找回整行偏移`() {
        val mode = SstvMode.ROBOT_36
        val base = decode(barsImage(mode), mode)
        for (shift in listOf(-3, 2, 5)) {
            val moved = shiftRows(base, shift)
            val alignment = SstvFrameFusion.align(base, moved)
            assertEquals("行偏移 $shift 应被找回", shift, alignment.rowShift)
            assertTrue("配准后相关度应高（实得 ${"%.2f".format(alignment.score)}）", alignment.score > 0.8)
        }
    }

    @Test
    fun `配准能找回整列偏移`() {
        val mode = SstvMode.ROBOT_36
        val base = decode(barsImage(mode), mode)
        for (shift in listOf(-4, 3)) {
            val moved = shiftColumns(base, shift)
            val alignment = SstvFrameFusion.align(base, moved)
            assertEquals("列偏移 $shift 应被找回", shift, alignment.columnShift)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. 融合方式的取舍
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `中位数融合不劣于加权均值`() {
        val mode = SstvMode.ROBOT_36
        val truth = barsImage(mode)
        val reference = decode(truth, mode)
        val frames = (0 until 3).map { index ->
            decodeAudio(
                addNoise(SstvEncoder(sampleRate).encode(truth, mode), 0.5, 16.0, seed = 31L + index * 101L),
                mode,
            )
        }
        val median = SstvFrameFusion.fuse(frames, mode, SstvFrameFusion.Method.MEDIAN)!!
        val weighted = SstvFrameFusion.fuse(frames, mode, SstvFrameFusion.Method.WEIGHTED_MEAN)!!
        val pm = psnrOf(median.pixels, reference.pixels, median.width, median.height)
        val pw = psnrOf(weighted.pixels, reference.pixels, weighted.width, weighted.height)
        // 两者都应优于单帧，且中位数不应明显更差（调研结论：中位数抗离群）
        assertTrue("中位数融合 ${"%.2f".format(pm)} 应优于单帧", pm > psnrOf(frames[0].pixels, reference.pixels, frames[0].width, frames[0].height))
        assertTrue("中位数 ${"%.2f".format(pm)} 与加权均值 ${"%.2f".format(pw)} 不应相差过大", abs(pm - pw) < 3.0)
    }

    @Test
    fun `空输入与单帧输入的边界`() {
        val mode = SstvMode.ROBOT_36
        assertNull("空输入应返回 null", SstvFrameFusion.fuse(emptyList(), mode))

        val single = decode(barsImage(mode), mode)
        val result = SstvFrameFusion.fuse(listOf(single), mode)
        assertNotNull("单帧也应给出结果", result)
        assertEquals("单帧", 1, result!!.usedFrames)
        assertEquals("单帧增益为 0", 0.0, result.gainDb, 1e-9)
        assertTrue("单帧结果应与输入一致", result.pixels.contentEquals(single.pixels))
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /** 自环解码（无噪声）。 */
    private fun decode(source: SstvImageSource, mode: SstvMode): SstvFrameFusion.Frame =
        decodeAudio(SstvEncoder(sampleRate).encode(source, mode), mode)

    /** 音频 → 解调 → 解码 → 融合输入。 */
    private fun decodeAudio(audio: FloatArray, mode: SstvMode): SstvFrameFusion.Frame {
        val outcome = SstvDecoder(sampleRate).decode(audio, forcedMode = mode)
        assertTrue(
            "解码失败：${(outcome as? SstvDecoder.Outcome.Failure)?.reason}",
            outcome is SstvDecoder.Outcome.Success,
        )
        val result = (outcome as SstvDecoder.Outcome.Success).result
        return SstvFrameFusion.Frame(
            pixels = result.pixels,
            width = result.width,
            height = result.height,
            syncHitRate = 1f,
            slantRatio = result.slantRatio.coerceIn(0.9, 1.1),
            decodedLines = result.decodedLines,
        )
    }

    /** 加高斯类噪声（线性同余 + 三角分布，与一期用例同一写法），噪声可见。 */
    private fun addNoise(audio: FloatArray, amplitude: Double, snrDb: Double, seed: Long): FloatArray {
        var state = seed
        val noiseStd = amplitude / 10.0.pow(snrDb / 20.0)
        // 两个 [0,1) 均匀量之和减 1 → 方差 1/6 的对称三角分布（Bell 形，近似高斯）
        val spread = noiseStd * sqrt(6.0)
        return FloatArray(audio.size) { i ->
            state = state * 6364136223846793005L + 1442695040888963407L
            val a = ((state ushr 33).toInt() % 20000) / 20000.0
            state = state * 6364136223846793005L + 1442695040888963407L
            val b = ((state ushr 33).toInt() % 20000) / 20000.0
            (audio[i] + (a + b - 1.0) * spread).toFloat()
        }
    }

    /** 把整幅图向下平移 [rows] 行（负数为向上），空出的行用边缘行填充。 */
    private fun shiftRows(frame: SstvFrameFusion.Frame, rows: Int): SstvFrameFusion.Frame {
        val out = IntArray(frame.pixels.size)
        for (y in 0 until frame.height) {
            val sy = (y - rows).coerceIn(0, frame.height - 1)
            System.arraycopy(frame.pixels, sy * frame.width, out, y * frame.width, frame.width)
        }
        return SstvFrameFusion.Frame(out, frame.width, frame.height)
    }

    /** 把整幅图向右平移 [cols] 列（负数为向左），空出的列用边缘列填充。 */
    private fun shiftColumns(frame: SstvFrameFusion.Frame, cols: Int): SstvFrameFusion.Frame {
        val out = IntArray(frame.pixels.size)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val sx = (x - cols).coerceIn(0, frame.width - 1)
                out[y * frame.width + x] = frame.pixels[y * frame.width + sx]
            }
        }
        return SstvFrameFusion.Frame(out, frame.width, frame.height)
    }

    /**
     * 8 色彩条 + 垂直渐变。同一张图的多个副本都用它生成 —— 内容必须完全一致，
     * 各副本的差异只来自加在音频上的独立噪声。
     */
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
                px[y * w + x] = scale(c, gain)
            }
        }
        return SstvPixelImageSource(w, h, px)
    }

    /** 水平方向的平滑渐变 + 左上角的亮块：与色条图的结构完全不同，用于「不同图」用例。 */
    private fun gradientImage(mode: SstvMode): SstvImageSource {
        val w = mode.linePixels
        val h = mode.imageLines
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = (x * 255 / (w - 1))
                val bright = if (x < w / 4 && y < h / 4) 255 else v
                px[y * w + x] = (bright shl 16) or (bright shl 8) or bright
            }
        }
        return SstvPixelImageSource(w, h, px)
    }

    /** 大格子棋盘：与色条的水平边界、渐变的平滑都不同。 */
    private fun checkerImage(mode: SstvMode): SstvImageSource {
        val w = mode.linePixels
        val h = mode.imageLines
        val cell = maxOf(8, w / 8)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val on = ((x / cell) + (y / cell)) % 2 == 0
                val v = if (on) 240 else 20
                px[y * w + x] = (v shl 16) or (v shl 8) or v
            }
        }
        return SstvPixelImageSource(w, h, px)
    }

    private fun scale(color: Int, gain: Double): Int {
        val r = (((color shr 16) and 0xFF) * gain).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * gain).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * gain).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** 去掉每行首尾后的 PSNR（与一期用例同一口径）。 */
    private fun psnrOf(a: IntArray, b: IntArray, width: Int, height: Int): Double {
        val margin = minOf(10, width / 4)
        var sum = 0.0
        var count = 0
        for (y in 0 until height) {
            for (x in margin until width - margin) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = (((a[y * width + x] shr shift) and 0xFF) -
                        ((b[y * width + x] shr shift) and 0xFF)).toDouble()
                    sum += d * d
                    count++
                }
            }
        }
        if (count == 0) return 0.0
        val mse = sum / count
        if (mse <= 0.0) return 99.0
        return 10.0 * log10(255.0 * 255.0 / mse)
    }
}
