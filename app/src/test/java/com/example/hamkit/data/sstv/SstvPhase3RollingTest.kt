package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSTV 三期 Q1「滚动缓冲」单元测试（纯 JVM）。
 *
 * 覆盖决策文档 [docs/SSTV_PHASE3_PLAN.md] §六 Q1 的验收：
 *  - 连续喂 10 分钟等效音频，内存有界；
 *  - 滚动回收之后 `finalize()` 仍与一期逐像素一致（回收不能吃掉任何解码要用的样本）；
 *  - 结果与分块粒度无关（回收点必须只由「已解码行位置」驱动）；
 *  - [SstvRollingImage] 的「卷纸」语义：到顶滚出最老行、流水号回写、按上限抽样导出。
 */
class SstvPhase3RollingTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    // ══════════════════════════════════════════════════════════════════
    // 1. 滚动回收后仍与一期逐像素一致
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `滚动回收后 finalize 仍与一期逐像素一致`() {
        // Q0 的护栏是「finalize 与一期逐像素一致」。Q1 引入了两段式缓冲与回收，
        // 这条护栏必须继续成立 —— 否则回收点算错了（吃掉了解码还要用的样本）。
        for (mode in listOf(SstvMode.ROBOT_36, SstvMode.SCOTTIE_1, SstvMode.MARTIN_1)) {
            val image = barsImage(mode)
            val audio = SstvEncoder(sampleRate).encode(image, mode)
            val oneShot = decodeOneShot(audio, mode)

            val decoder = SstvStreamDecoder(sampleRate, mode)
            decoder.feedAudio(audio)
            feedChunked(decoder, frequencies(audio, mode), chunk = 4096)
            decoder.flush()
            val final = decoder.finalize()

            assertNotNull("${mode.displayName} finalize 不应返回 null", final)
            final!!
            assertEquals("${mode.displayName} 行数", oneShot.decodedLines, final.decodedLines)
            assertTrue(
                "${mode.displayName} 回收后 finalize 应与一期逐像素一致",
                oneShot.pixels.contentEquals(final.pixels),
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. 长时间接收内存有界
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `连续接收十分钟内存有界`() {
        // 压力测试：连续喂 10 分钟等效音频（远长于单帧），检查两处内存都不随行数线性增长：
        //  - 高频段（最近样本环）恒定为环容量；
        //  - 历史段按帧保留并受预算约束（超出后抽样，占用不再增长）。
        val mode = SstvMode.ROBOT_36
        val frame = frequencies(SstvEncoder(sampleRate).encode(barsImage(mode), mode), mode)

        val decoder = SstvStreamDecoder(sampleRate, mode)
        val target = sampleRate * 600 // 10 分钟
        var fed = 0L
        // 循环喂同一帧的音频：模拟「持续守着」而不是「一帧即停」
        while (fed < target) {
            decoder.feed(frame)
            fed += frame.size
        }
        decoder.flush()

        val stats = decoder.stats()
        val tailBudget = SstvStreamDecoder.tailCapacityFor(mode, sampleRate)
        assertTrue(
            "高频段占用 ${stats.bufferedSamples} 应不超过环容量 $tailBudget",
            stats.bufferedSamples <= tailBudget,
        )
        // 历史段总预算：默认 15 分钟原始密度 @ 48 kHz，这里只保留一段时间就会抽样
        assertTrue(
            "历史段占用 ${stats.historySamples} 应受预算约束",
            stats.historySamples <= SstvStreamDecoder.DEFAULT_MAX_HISTORY_SAMPLES,
        )
        // 行号跨帧持续增长（「卷纸」语义）
        assertTrue("应解出远超一帧的行数（实得 ${stats.decodedLines}）", stats.decodedLines > mode.imageLines * 10)
        // finalize 仍需可用（历史段保留了可解码的帧）
        assertNotNull("长时间接收后仍应有可 finalize 的帧", decoder.finalize())
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 分块粒度无关
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `回收后结果与分块粒度无关`() {
        val mode = SstvMode.SCOTTIE_1
        val audio = SstvEncoder(sampleRate).encode(barsImage(mode), mode)
        val freqs = frequencies(audio, mode)
        val results = listOf(997, 4096, 30000, freqs.size).map { chunk ->
            val decoder = SstvStreamDecoder(sampleRate, mode)
            decoder.feedAudio(audio)
            feedChunked(decoder, freqs, chunk)
            decoder.flush()
            decoder.finalize()
        }
        val reference = results.first()
        assertNotNull(reference)
        for ((index, result) in results.withIndex()) {
            assertNotNull("第 $index 种分块 finalize 失败", result)
            assertTrue(
                "第 $index 种分块应与第一种逐像素一致",
                reference!!.pixels.contentEquals(result!!.pixels),
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 丢弃历史后仍可持续接收（内存最小化）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `丢弃历史后实时路径不受影响`() {
        val mode = SstvMode.ROBOT_36
        val frame = frequencies(SstvEncoder(sampleRate).encode(barsImage(mode), mode), mode)

        var rows = 0
        // onLine 是最后一个参数，因此可以用尾随 lambda（Q1 新增 maxSegments 时特意保持）
        val decoder = SstvStreamDecoder(sampleRate, mode) { rows++ }
        decoder.feed(frame)
        val afterFirst = rows
        assertTrue("第一帧应能解出大量行", afterFirst > mode.imageLines / 2)

        decoder.dropHistory()
        assertEquals("dropHistory 后历史段应为空", 0L, decoder.stats().historySamples)
        // 此时只剩「当前段」（起点在丢弃时刻之后），不可能再拼出整帧
        assertTrue(
            "dropHistory 后 finalize 不应再给出完整帧",
            decoder.finalizeFrames().none { it.decodedLines >= mode.imageLines },
        )

        decoder.feed(frame)
        assertTrue("dropHistory 后实时解码应继续", rows > afterFirst)
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. 滚动图像的「卷纸」语义
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `滚动图像到顶后滚出最老行`() {
        val rolling = SstvRollingImage(width = 4, maxRows = 3)
        for (i in 0 until 5) {
            rolling.append(IntArray(4) { i })
        }
        assertEquals("行数应达到上限", 3, rolling.size)
        assertEquals("累计写入应包含滚出的行", 5, rolling.appendedRows)
        assertTrue("应标记为已滚动", rolling.scrolled)

        val (pixels, height) = rolling.toPixels()
        assertEquals(3, height)
        // 留下的应是最后三行：2、3、4
        for (row in 0 until height) {
            assertEquals("第 $row 行", row + 2, pixels[row * 4])
        }
    }

    @Test
    fun `滚动图像按流水号回写已有行`() {
        val rolling = SstvRollingImage(width = 2, maxRows = 10)
        rolling.append(IntArray(2) { 100 })
        rolling.append(IntArray(2) { 101 })
        // 解码器给出行号 1 的修正值（例如锁定后重新定位）
        rolling.writeRow(firstAppendedRow = 0, row = 1, pixels = IntArray(2) { 7 })
        assertEquals("第 1 行应被覆盖", 7, rolling.rowAt(1)!![0])
        // 越界行号不得写入、也不得崩溃
        rolling.writeRow(firstAppendedRow = 0, row = 99, pixels = IntArray(2) { 9 })
        assertEquals("行数不变", 2, rolling.size)
    }

    @Test
    fun `滚动图像导出时按上限纵向抽样`() {
        val rolling = SstvRollingImage(width = 2, maxRows = 100, maxHeightRows = 10)
        for (i in 0 until 40) {
            rolling.append(IntArray(2) { i })
        }
        val (pixels, height) = rolling.toPixels(maxHeight = 8)
        assertEquals("导出高度应被压到上限", 8, height)
        assertEquals("像素长度应与高度一致", 16, pixels.size)
        // 抽样应覆盖整段时间：首行来自开头、末行来自结尾
        assertTrue("首行应接近最早的行", pixels[0] <= 1)
        assertTrue("末行应接近最新的行", pixels[(height - 1) * 2] >= 30)

        // toResult 可直接交给图库/后处理：默认按 maxHeightRows（本对象为 10）抽样，
        // 与上面显式要求 8 行的 toPixels 不是同一档；需要同一档时把 maxHeight 传下去。
        val result = rolling.toResult(SstvMode.ROBOT_36)
        assertNotNull(result)
        assertEquals("默认导出高度应受 maxHeightRows 约束", minOf(rolling.size, 10), result!!.height)
        assertEquals(
            "显式 maxHeight 应与 toPixels 一致",
            height,
            rolling.toResult(SstvMode.ROBOT_36, maxHeight = 8)!!.height,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助（与 SstvStreamDecoderTest 同一套素材生成方式）
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
    private fun frequencies(audio: FloatArray, mode: SstvMode): FloatArray = SstvDemodulator(
        sampleRate = sampleRate,
        phaseSpan = SstvDemodulator.recommendPhaseSpan(mode.pixelSeconds * sampleRate),
    ).demodulate(audio)

    /** 按固定块长喂入。 */
    private fun feedChunked(decoder: SstvStreamDecoder, freqs: FloatArray, chunk: Int) {
        var index = 0
        while (index < freqs.size) {
            val end = minOf(index + chunk, freqs.size)
            decoder.feed(freqs.copyOfRange(index, end))
            index = end
        }
    }

    /** 8 色彩条 + 垂直亮度渐变（含尖锐水平边界）。 */
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
}
