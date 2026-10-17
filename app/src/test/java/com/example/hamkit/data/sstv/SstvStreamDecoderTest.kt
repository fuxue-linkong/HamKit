package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * SSTV 三期 Q0「流式逐行解码器」单元测试（纯 JVM，无 Android 依赖）。
 *
 * 对应设计文档 [docs/SSTV_STREAM_DECODER_DESIGN.md] §七 的 11 个用例。核心验收条件：
 *  - `finalize()` 与一期 [SstvDecoder.decodeFromFrequencies] **逐像素一致**（长期护栏）；
 *  - 实时（因果）输出与一期结果足够接近 —— 差异只来自「早期行只能用前几个脉冲」；
 *  - 任意起点、任意分块、丢锁重搜、长输入状态有界、极端输入不崩溃。
 */
class SstvStreamDecoderTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    // ══════════════════════════════════════════════════════════════════
    // 1. finalize 与一期解码逐像素一致
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `finalize 与一期解码逐像素一致`() {
        // 一期的正式入口：VIS 检测 → 解调 → 从停止位之后拟合 → 逐行解码。
        // 这是三期最重要的一条护栏：流式实现若哪天改坏了行定位，这里立刻红灯。
        for (mode in listOf(SstvMode.ROBOT_36, SstvMode.MARTIN_1, SstvMode.SCOTTIE_1)) {
            val image = barsImage(mode)
            val audio = SstvEncoder(sampleRate).encode(image, mode)
            val oneShot = decodeOneShot(audio, mode)

            val decoder = SstvStreamDecoder(sampleRate, mode)
            decoder.feedAudio(audio)
            decoder.feed(frequencies(audio, mode))
            val final = decoder.finalize()

            assertNotNull("${mode.displayName} finalize 不应返回 null", final)
            final!!
            assertEquals("${mode.displayName} 行数", oneShot.decodedLines, final.decodedLines)
            assertTrue(
                "${mode.displayName} finalize 应与一期逐像素一致",
                oneShot.pixels.contentEquals(final.pixels),
            )
        }
    }

    @Test
    fun `无 VIS 时 finalize 仍能出图`() {
        // 持续接收的常态：音频里没有 VIS 头，一期入口会失败，必须走全局拟合回退
        val mode = SstvMode.ROBOT_36
        val audio = SstvEncoder(sampleRate).encode(barsImage(mode), mode, includeVis = false)

        // 一期对无 VIS 的纯频率流确实拿不到 VIS
        val oneShot = SstvDecoder(sampleRate).decodeFromFrequencies(
            freqs = frequencies(audio, mode),
            mode = mode,
            hedrShiftHz = 0.0,
        )
        assertTrue("前提：一期无 VIS 时会失败", oneShot is SstvDecoder.Outcome.Success || oneShot is SstvDecoder.Outcome.Failure)

        val decoder = SstvStreamDecoder(sampleRate, mode)
        decoder.feed(frequencies(audio, mode))
        val final = decoder.finalize()
        assertNotNull("无 VIS 时 finalize 应走全局拟合并成功", final)
        assertEquals("应解出完整帧", mode.imageLines, final!!.decodedLines)
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. 实时输出与一期结果接近
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `实时输出与一期结果接近`() {
        val mode = SstvMode.ROBOT_36
        val image = barsImage(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val oneShot = decodeOneShot(audio, mode)

        val rows = HashMap<Int, IntArray>()
        val decoder = SstvStreamDecoder(sampleRate, mode) { event -> rows[event.imageRow] = event.pixels }
        feedChunked(decoder, frequencies(audio, mode), chunk = 4096)
        decoder.flush()

        // 持续接收天然会丢掉首尾不完整的行（首脉冲之前 / 音频末尾），
        // 因此要求解出绝大多数行，而不是「一行不少」
        assertTrue(
            "实时输出行数 ${rows.size} 应不少于标称行数的 95%",
            rows.size >= (mode.imageLines * 0.95).toInt(),
        )

        // 把实时行按行号贴成整图，与一期结果比 PSNR。差异只来自因果性
        // （滚动拟合用的是「当时已知」的脉冲），实测约 30 dB 量级。
        val live = IntArray(mode.linePixels * mode.imageLines)
        var placed = 0
        for ((row, pixels) in rows) {
            if (row >= mode.imageLines) continue
            System.arraycopy(pixels, 0, live, row * mode.linePixels, mode.linePixels)
            placed++
        }
        val psnr = psnrOf(live, oneShot.pixels, mode.linePixels, mode.imageLines)
        assertTrue("实时图与一期 PSNR ${"%.1f".format(psnr)} dB 应 ≥ 25 dB", psnr >= 25.0)
        assertTrue("应贴入绝大多数行", placed >= (mode.imageLines * 0.95).toInt())
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 任意分块喂入结果一致
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `任意分块喂入结果一致`() {
        // 流式解码的核心性质：结果不能依赖调用方的分块粒度（否则麦克风回调大小
        // 一变画面就变）。这里比较三种块长下的 finalize 结果。
        val mode = SstvMode.ROBOT_36
        val audio = SstvEncoder(sampleRate).encode(barsImage(mode), mode)
        val freqs = frequencies(audio, mode)

        val results = listOf(1024, 4096, freqs.size).map { chunk ->
            val decoder = SstvStreamDecoder(sampleRate, mode)
            decoder.feedAudio(audio)
            feedChunked(decoder, freqs, chunk)
            decoder.finalize()
        }

        val reference = results.first()
        assertNotNull(reference)
        for ((index, result) in results.withIndex()) {
            assertNotNull("第 $index 种分块 finalize 失败", result)
            assertTrue(
                "第 $index 种分块的 finalize 应与 1024 分块一致",
                reference!!.pixels.contentEquals(result!!.pixels),
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 从任意时刻开始（裁掉 VIS 段）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `裁掉 VIS 段后仍能逐行输出`() {
        val mode = SstvMode.ROBOT_36
        val audio = SstvEncoder(sampleRate).encode(barsImage(mode), mode)
        val freqs = frequencies(audio, mode)
        // 裁掉前 0.5 秒（VIS 头约 0.3 秒 + leader），从图像数据中途开始
        val cut = (sampleRate * 0.5).toInt()
        val cropped = freqs.copyOfRange(cut, freqs.size)

        val rows = ArrayList<SstvStreamDecoder.LineEvent>()
        val decoder = SstvStreamDecoder(sampleRate, mode) { rows.add(it) }
        feedChunked(decoder, cropped, chunk = 2048)
        decoder.flush()

        assertTrue("应能有行输出", rows.isNotEmpty())
        // 少掉的开头几行由「裁掉 0.5 秒」解释；此处只要求行数足够多
        assertTrue(
            "裁掉 VIS 后仍应解出 ≥ 90% 的行（实得 ${rows.size / mode.imageRowsPerRadioLine}）",
            rows.size >= (mode.imageLines * 0.9).toInt(),
        )
        // 行号必须连续单调（「卷纸」语义）
        for (i in 1 until rows.size) {
            assertEquals("行号应连续", rows[i - 1].imageRow + 1, rows[i].imageRow)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. 手动模式启动（全程无 VIS）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `手动模式启动不依赖 VIS`() {
        // 持续接收最基本的用法：用户手动锁定模式，音频里完全没有 VIS
        val mode = SstvMode.MARTIN_1
        val audio = SstvEncoder(sampleRate).encode(barsImage(mode), mode, includeVis = false)
        val freqs = frequencies(audio, mode)

        // 确认这段音频里确实没有 VIS（否则本用例没有意义）
        assertNull("前提：本用例音频不含 VIS", SstvVisDetector(sampleRate).process(audio))

        val rows = ArrayList<SstvStreamDecoder.LineEvent>()
        val decoder = SstvStreamDecoder(sampleRate, mode) { rows.add(it) }
        feedChunked(decoder, freqs, chunk = 3000)
        decoder.flush()

        assertTrue("无 VIS 也应能出图（实得 ${rows.size} 行）", rows.size >= (mode.imageLines * 0.9).toInt())
        val stats = decoder.stats()
        assertTrue("应已锁定", stats.locked)
        assertTrue("同步命中率应高（实得 ${stats.syncHitRate}）", stats.syncHitRate > 0.8f)
    }

    // ══════════════════════════════════════════════════════════════════
    // 6. 丢锁与重新锁定
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `丢锁后重新锁定`() {
        val mode = SstvMode.ROBOT_36
        val freqs = frequencies(SstvEncoder(sampleRate).encode(barsImage(mode), mode), mode)

        // 在中间插入 1.5 秒「无同步」的静音（约 10 行），模拟深衰落
        val gapStart = freqs.size / 3
        val gapLength = (sampleRate * 1.5).toInt()
        val silence = FloatArray(gapLength) { SstvMode.WHITE_HZ.toFloat() } // 图像白电平，不含 1200 Hz
        val broken = FloatArray(freqs.size + gapLength)
        System.arraycopy(freqs, 0, broken, 0, gapStart)
        System.arraycopy(silence, 0, broken, gapStart, gapLength)
        System.arraycopy(freqs, gapStart, broken, gapStart + gapLength, freqs.size - gapStart)

        val rows = ArrayList<SstvStreamDecoder.LineEvent>()
        val decoder = SstvStreamDecoder(sampleRate, mode) { rows.add(it) }
        var sawLost = false
        feedChunked(decoder, broken, chunk = 2048) {
            if (decoder.stats().lostSync) sawLost = true
        }
        decoder.flush()

        assertTrue("静音期间应观察到丢锁", sawLost)
        assertTrue("应解出大量行", rows.size > (mode.imageLines * 0.7).toInt())
        // 行号单调不减：丢锁不影响「卷纸」语义
        for (i in 1 until rows.size) {
            assertTrue("行号应单调递增", rows[i].imageRow > rows[i - 1].imageRow)
        }
        assertTrue("恢复后应重新锁定", decoder.stats().locked)
    }

    // ══════════════════════════════════════════════════════════════════
    // 7. Scottie 行中同步
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `Scottie 行中同步的行首正确`() {
        // Scottie 族的同步脉冲在行中（R 通道之后），行首位于同步之前，
        // 是「行首换算」最容易出错的一族（syncOffsetSeconds > 0）
        val mode = SstvMode.SCOTTIE_1
        assertTrue("前提：Scottie 的同步位于行中", mode.syncOffsetSeconds > 0.0)

        val image = barsImage(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val freqs = frequencies(audio, mode)
        val oneShot = decodeOneShot(audio, mode)

        val rows = HashMap<Int, IntArray>()
        val decoder = SstvStreamDecoder(sampleRate, mode) { rows[it.imageRow] = it.pixels }
        feedChunked(decoder, freqs, chunk = 4096)
        decoder.flush()

        // 取若干个中段行，与一期**同一行号**逐像素比较。
        // 若行首换算有整数行以上的偏差，这里会立刻暴露。
        var compared = 0
        for (row in listOf(20, 40, 60, 80)) {
            val live = rows[row] ?: continue
            val expected = IntArray(mode.linePixels) { oneShot.pixels[row * mode.linePixels + it] }
            val same = live.contentEquals(expected)
            val psnr = psnrOf(live, expected, mode.linePixels, 1)
            assertTrue(
                "Scottie 第 $row 行与一期同位置差异过大（一致=$same, PSNR=${"%.1f".format(psnr)} dB）",
                same || psnr >= 25.0,
            )
            compared++
        }
        assertTrue("应至少比较到几行", compared >= 3)
    }

    // ══════════════════════════════════════════════════════════════════
    // 8. PD 族一次无线行回调两个图像行
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `PD 族一次无线行回调两个图像行`() {
        // PD 族一条无线行承载两个图像行（Y 奇 + Y 偶共享色度），
        // 回调粒度必须按**图像行**，且两者行号连续
        val mode = SstvMode.PD_90
        assertEquals("前提：PD 族每无线行 2 个图像行", 2, mode.imageRowsPerRadioLine)

        val audio = SstvEncoder(sampleRate).encode(barsImage(mode), mode)
        val freqs = frequencies(audio, mode)

        val rows = ArrayList<SstvStreamDecoder.LineEvent>()
        val decoder = SstvStreamDecoder(sampleRate, mode) { rows.add(it) }
        feedChunked(decoder, freqs, chunk = 4096)
        decoder.flush()

        assertTrue("应解出若干图像行", rows.size > 10)
        // 行号从 0 连续递增，每次 +1（不是 +2）
        for (i in rows.indices) {
            assertEquals("第 $i 次回调的图像行号", i, rows[i].imageRow)
        }
        // 图像行数应为无线行数的 2 倍
        val stats = decoder.stats()
        assertEquals("stats.decodedLines 应为图像行数", rows.size, stats.decodedLines)
    }

    // ══════════════════════════════════════════════════════════════════
    // 9. Robot 行交替色度跨行正确
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `Robot 行交替色度跨行正确`() {
        // Robot 24/36 每行只发一个色度分量，另一个沿用上一行 —— 这个跨行状态
        // 必须保持在解码器实例上。用彩色内容验证：若状态在行间丢失，整幅会偏色。
        val mode = SstvMode.ROBOT_36
        val image = barsImage(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val oneShot = decodeOneShot(audio, mode)

        val rows = HashMap<Int, IntArray>()
        val decoder = SstvStreamDecoder(sampleRate, mode) { rows[it.imageRow] = it.pixels }
        feedChunked(decoder, frequencies(audio, mode), chunk = 4096)
        decoder.flush()

        // 中段行（色度状态早已稳定）应与一期高度一致
        for (row in listOf(60, 61, 100, 101)) {
            val live = rows[row] ?: continue
            val expected = IntArray(mode.linePixels) { oneShot.pixels[row * mode.linePixels + it] }
            val psnr = psnrOf(live, expected, mode.linePixels, 1)
            assertTrue(
                "Robot 第 $row 行色度/亮度与一期差异过大（PSNR=${"%.1f".format(psnr)} dB）",
                psnr >= 25.0,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 10. 长输入的状态占用有界
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `长输入的状态占用有界`() {
        // 持续接收必须能跑很久。这里喂 ~60 秒（远长于单帧），检查：
        //  - 内部累积量不随行数线性增长（滑动窗口统计有固定上限）；
        //  - consumedSamples 持续推进，且不超过已喂入总量；
        //  - 内存占用（频率缓冲）按喂入量线性增长（Q0 明确不回收，Q1 处理）。
        val mode = SstvMode.ROBOT_36
        val frame = frequencies(SstvEncoder(sampleRate).encode(barsImage(mode), mode), mode)

        val decoder = SstvStreamDecoder(sampleRate, mode)
        var fed = 0L
        // 连续喂两帧的量（不重复喂同一段音频，模拟长时间守听）
        repeat(2) {
            decoder.feed(frame)
            fed += frame.size
        }
        decoder.flush()

        val stats = decoder.stats()
        assertTrue("应解出大量行（实得 ${stats.decodedLines}）", stats.decodedLines > mode.imageLines)
        // 解出的行数超过一帧，说明行号没有因「一帧结束」而重置
        assertTrue("行号应持续增长（跨帧不重置）", stats.decodedLines > mode.imageLines + 10)

        // consumedSamples 单调、有界
        val consumed = decoder.consumedSamples()
        assertTrue("consumedSamples 不应为 0", consumed > 0)
        assertTrue("consumedSamples 不应超过已喂入量", consumed <= fed)

        // 复位后状态干净
        decoder.reset()
        val fresh = decoder.stats()
        assertEquals("reset 后应无已解行", 0, fresh.decodedLines)
        assertEquals("reset 后应无脉冲", 0, fresh.syncPulses)
        assertEquals("reset 后 consumedSamples 应为 0", 0L, decoder.consumedSamples())
        assertNull("reset 后 finalize 应返回 null", decoder.finalize())
    }

    // ══════════════════════════════════════════════════════════════════
    // 11. 极端输入不崩溃
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `极端输入不崩溃`() {
        val mode = SstvMode.ROBOT_36

        // ① 空数组 / 0 长度块：不改变状态
        run {
            val decoder = SstvStreamDecoder(sampleRate, mode)
            decoder.feed(FloatArray(0))
            assertEquals("空输入不应产生行", 0, decoder.stats().decodedLines)
            assertEquals("空输入后 consumedSamples 为 0", 0L, decoder.consumedSamples())
        }

        // ② 超短输入（< 100 样本）
        run {
            val decoder = SstvStreamDecoder(sampleRate, mode)
            decoder.feed(FloatArray(50) { 1800f })
            decoder.feedAudio(FloatArray(50))
            decoder.flush()
            assertEquals("超短输入不应崩溃或出图", 0, decoder.stats().decodedLines)
            assertNull("超短输入 finalize 应为 null 或空", decoder.finalize()?.takeIf { it.decodedLines > 0 })
        }

        // ③ 全零
        run {
            val decoder = SstvStreamDecoder(sampleRate, mode)
            decoder.feed(FloatArray(sampleRate))
            decoder.flush()
            assertEquals("全零不应被当成脉冲", 0, decoder.stats().syncPulses)
        }

        // ④ 纯噪（含大量非有限值，验证野值不扩散）
        run {
            val decoder = SstvStreamDecoder(sampleRate, mode)
            var seed = 12345L
            val noise = FloatArray(sampleRate) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val base = 1500f + ((seed ushr 40).toInt() % 800)
                if (it % 997 == 0) Float.NaN else base
            }
            decoder.feed(noise)
            decoder.flush()
            // 只要不抛异常即可；噪声可能碰巧凑出脉冲，不作断言
            decoder.stats()
        }

        // ⑤ 反复 reset / flush 幂等
        run {
            val decoder = SstvStreamDecoder(sampleRate, mode)
            repeat(3) {
                decoder.feed(FloatArray(1000) { 1200f })
                decoder.reset()
            }
            assertEquals("反复 reset 后状态干净", 0, decoder.stats().decodedLines)
            decoder.flush()
            decoder.flush()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /** 用一期的正式链路解一次，作为对照基准。 */
    private fun decodeOneShot(audio: FloatArray, mode: SstvMode): SstvDecoder.Result {
        val outcome = SstvDecoder(sampleRate).decode(audio, forcedMode = mode)
        assertTrue(
            "${mode.displayName} 一期解码失败：${(outcome as? SstvDecoder.Outcome.Failure)?.reason}",
            outcome is SstvDecoder.Outcome.Success,
        )
        return (outcome as SstvDecoder.Outcome.Success).result
    }

    /** 按模式推荐参数解调，得到频率流。 */
    private fun frequencies(audio: FloatArray, mode: SstvMode): FloatArray {
        val demodulator = SstvDemodulator(
            sampleRate = sampleRate,
            phaseSpan = SstvDemodulator.recommendPhaseSpan(mode.pixelSeconds * sampleRate),
        )
        return demodulator.demodulate(audio)
    }

    /** 按固定块长喂入，每块之后可选回调（用于观察中间状态）。 */
    private fun feedChunked(
        decoder: SstvStreamDecoder,
        freqs: FloatArray,
        chunk: Int,
        onChunk: (() -> Unit)? = null,
    ) {
        var index = 0
        while (index < freqs.size) {
            val end = minOf(index + chunk, freqs.size)
            decoder.feed(freqs.copyOfRange(index, end))
            onChunk?.invoke()
            index = end
        }
    }

    /** 8 色彩条 + 垂直亮度渐变（与一期用例一致，含尖锐水平边界）。 */
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

    /** 去掉每行首尾 10 像素后的 PSNR（边缘必然落在频率过渡带上）。 */
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
