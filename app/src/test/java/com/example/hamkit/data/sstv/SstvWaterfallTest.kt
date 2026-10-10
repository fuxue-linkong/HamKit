package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 瀑布图（[SstvWaterfall]）单元测试。
 *
 * 瀑布图是纯显示组件，但它有三处容易写错又很难在真机上发现的地方，因此必须有测试：
 *  1. **频率 → 格的映射**：偏一格看起来只是「信号略微偏频」，但用户是拿它判断失谐量的；
 *  2. **环形缓冲的方向**：上下颠倒会让时间轴反向，用户看到的同步脉冲会「往上跑」；
 *  3. **分块喂入的连续性**：实时接收是按 500 ms 一块喂的，若块边界把一行切断处理错，
 *     会周期性地插入假行。
 */
class SstvWaterfallTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    /** 构造一个「每行样本数」便于计算的小瀑布图。 */
    private fun waterfall(bins: Int = 20, rows: Int = 8, rowMs: Int = 10) =
        SstvWaterfall(bins = bins, rows = rows, rowMilliseconds = rowMs, sampleRate = sampleRate)

    @Test
    fun `频率格映射覆盖 1000 到 2500 Hz`() {
        val w = waterfall()
        assertEquals("1000 Hz 应落在第 0 格", 0, w.binOf(1000f))
        assertEquals("2500 Hz 应落在最后一格", w.bins - 1, w.binOf(2500f))
        // 1200 Hz（同步）与 1500 Hz（黑）必须落在不同的格上，否则看不出同步线
        assertTrue("1200 与 1500 Hz 不应同格", w.binOf(1200f) != w.binOf(1500f))
    }

    @Test
    fun `越界与非法频率被夹紧或忽略`() {
        val w = waterfall()
        assertEquals("低于下限应夹到第 0 格", 0, w.binOf(10f))
        assertEquals("高于上限应夹到最后一格", w.bins - 1, w.binOf(99999f))
        assertEquals("NaN 应返回 -1（不记入任何格）", -1, w.binOf(Float.NaN))
        assertEquals("无穷应返回 -1", -1, w.binOf(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `纯音在正确的格上形成亮线`() {
        val w = waterfall()
        val tone = FloatArray(w.samplesPerRow) { 1500f }
        assertEquals(1, w.feed(tone))

        val snapshot = w.snapshot()
        val base = (w.rows - 1) * w.bins // 最新行在最后
        var brightest = 0
        for (b in 0 until w.bins) {
            if (snapshot[base + b] > snapshot[base + brightest]) brightest = b
        }
        assertEquals("最亮格应为 1500 Hz 对应的格", w.binOf(1500f), brightest)
        assertTrue("纯音的亮格应接近饱和，实际 ${snapshot[base + brightest]}", snapshot[base + brightest] > 200)
    }

    @Test
    fun `频率阶跃使亮格逐行移动`() {
        val w = waterfall()
        w.feed(FloatArray(w.samplesPerRow) { 1500f })
        w.feed(FloatArray(w.samplesPerRow) { 2300f })

        val snapshot = w.snapshot()
        val oldBase = (w.rows - 2) * w.bins
        val newBase = (w.rows - 1) * w.bins
        assertEquals(w.binOf(1500f), brightestBin(snapshot, oldBase, w.bins))
        assertEquals(w.binOf(2300f), brightestBin(snapshot, newBase, w.bins))
    }

    @Test
    fun `最新行位于快照的最后一行`() {
        // 时间轴方向写反是这类组件最常见的缺陷，且只表现为「看起来在回放」
        val w = waterfall(rows = 4)
        val tones = listOf(1100f, 1300f, 1500f, 2300f)
        for (hz in tones) w.feed(FloatArray(w.samplesPerRow) { hz })

        val snapshot = w.snapshot()
        for ((index, hz) in tones.withIndex()) {
            assertEquals(
                "第 $index 行（自旧到新）应为 ${hz} Hz",
                w.binOf(hz),
                brightestBin(snapshot, index * w.bins, w.bins),
            )
        }
    }

    @Test
    fun `分块喂入与一次喂入结果完全一致`() {
        // 实时接收按 500 ms 分块；块边界若处理不当会周期性插入假行
        val samples = FloatArray(7 * sampleRate / 100).mapIndexed { i, _ ->
            // 每 700 个样本换一次频率，制造跨越块边界的变化
            (1100f + (i / 700 % 4) * 400f)
        }.toFloatArray()

        val whole = waterfall(rows = 64)
        whole.feed(samples)

        val chunked = waterfall(rows = 64)
        var offset = 0
        var chunk = 1
        while (offset < samples.size) {
            val size = minOf(chunk * 137, samples.size - offset)
            chunked.feed(samples.copyOfRange(offset, offset + size))
            offset += size
            chunk++
        }

        assertTrue(
            "分块与整段喂入的快照应逐像素一致",
            whole.snapshot().contentEquals(chunked.snapshot()),
        )
        assertEquals(whole.producedRows, chunked.producedRows)
    }

    @Test
    fun `滚过一圈后只保留最近 rows 行`() {
        val w = waterfall(rows = 3)
        val history = mutableListOf<Int>()
        for (hz in listOf(1100f, 1300f, 1500f, 1700f, 1900f)) {
            w.feed(FloatArray(w.samplesPerRow) { hz })
            history.add(w.binOf(hz))
        }
        val snapshot = w.snapshot()
        // 最新三行应是最后三个频率
        for (i in 0 until 3) {
            assertEquals(
                "第 $i 行（自旧到新）应为倒数第 ${3 - i} 个频率",
                history[history.size - 3 + i],
                brightestBin(snapshot, i * w.bins, w.bins),
            )
        }
        assertEquals(5L, w.producedRows)
    }

    @Test
    fun `reset 清空全部历史`() {
        val w = waterfall()
        w.feed(FloatArray(w.samplesPerRow * 3) { 1500f })
        assertTrue(w.producedRows > 0)
        w.reset()
        assertEquals(0L, w.producedRows)
        assertEquals(-1, w.headRow)
        assertTrue("快照应全为 0", w.snapshot().all { it == 0 })
    }

    @Test
    fun `可见历史时长与参数一致`() {
        val w = waterfall(rows = 384, rowMs = 4)
        assertEquals(1.536, w.historySeconds, 1e-9)
        assertEquals(192, w.samplesPerRow) // 4 ms @48 kHz
    }

    private fun brightestBin(snapshot: IntArray, base: Int, bins: Int): Int {
        var best = 0
        for (b in 1 until bins) if (snapshot[base + b] > snapshot[base + best]) best = b
        return best
    }
}
