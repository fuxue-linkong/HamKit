package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图像后处理的单元测试。
 *
 * 三项处理都是「可选、默认关闭、不可逆」，因此测试的重点除了效果本身，还有：
 *  - **不修改入参**（调用方要保留原始解码结果用于「保存原图」）；
 *  - **灰度图处理后仍是灰度**（R=G=B 不能被破坏，否则黑白模式会莫名出彩边）。
 */
class SstvPostProcessTest {

    private val width = 320
    private val height = 240

    @Test
    fun `全部关闭时原样返回`() {
        val pixels = gradientPixels()
        val out = SstvPostProcess.process(pixels, width, height, SstvPostProcess.Options.NONE)
        assertSame("未启用任何处理时不应复制数组", pixels, out)
    }

    @Test
    fun `不修改入参`() {
        val pixels = noisyGradient()
        val before = pixels.copyOf()
        SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(histogramStretch = true, medianFilter = true, repairBadLines = true),
        )
        assertTrue("入参数组不得被修改", pixels.contentEquals(before))
    }

    @Test
    fun `直方图拉伸把窄动态范围拉满`() {
        // 模拟 AGC 压缩：真实内容只占 60–90 这一小段
        val pixels = IntArray(width * height) { index ->
            val v = 60 + (index % width) * 30 / width
            (v shl 16) or (v shl 8) or v
        }
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(histogramStretch = true),
        )
        val reds = out.map { (it shr 16) and 0xFF }
        val min = reds.min()
        val max = reds.max()
        assertTrue("拉伸后最小值应接近 0，实际 $min", min <= 8)
        assertTrue("拉伸后最大值应接近 255，实际 $max", max >= 247)
    }

    @Test
    fun `内容本就接近纯色时不拉伸`() {
        // 动态范围过窄（< 16 级）时拉伸只会把噪声放大成花纹
        val pixels = IntArray(width * height) { index ->
            val v = 128 + (index % 3)
            (v shl 16) or (v shl 8) or v
        }
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(histogramStretch = true),
        )
        assertTrue("接近纯色的图像不应被拉伸", out.contentEquals(pixels))
    }

    @Test
    fun `中值滤波去掉孤立噪点`() {
        val pixels = IntArray(width * height) { 0x808080 }
        // 均匀灰底上放几个孤立亮点/暗点
        for (pos in intArrayOf(1000, 2000, 3000, 4000)) pixels[pos] = 0xFFFFFF
        for (pos in intArrayOf(5000, 6000)) pixels[pos] = 0x000000

        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(medianFilter = true),
        )
        for (pos in intArrayOf(1000, 2000, 3000, 4000, 5000, 6000)) {
            assertEquals(
                "第 $pos 个孤立噪点应被中值抹掉",
                0x808080,
                out[pos],
            )
        }
    }

    @Test
    fun `中值滤波保留真实边界`() {
        // 左半黑右半白：中值滤波不应把边界抹成灰带（那是均值滤波的问题）
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) 0x000000 else 0xFFFFFF
        }
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(medianFilter = true),
        )
        assertEquals("边界左侧一格应仍为黑", 0x000000, out[(height / 2) * width + width / 2 - 2])
        assertEquals("边界右侧一格应仍为白", 0xFFFFFF, out[(height / 2) * width + width / 2 + 1])
    }

    @Test
    fun `坏行被相邻行插值替换`() {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 上下行都是横向渐变，只有第 100 行是「同步丢失」的纯黑
                val v = if (y == 100) 0 else 40 + (x * 200 / width)
                pixels[y * width + x] = (v shl 16) or (v shl 8) or v
            }
        }
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(repairBadLines = true),
        )
        // 修复后该行应接近上下行的平均，而不再是纯黑
        val sampleX = width / 2
        val repaired = (out[100 * width + sampleX] shr 16) and 0xFF
        val above = (out[99 * width + sampleX] shr 16) and 0xFF
        val below = (out[101 * width + sampleX] shr 16) and 0xFF
        assertEquals("修复值应为上下行均值", (above + below) / 2, repaired)
        assertTrue("修复后不应仍是纯黑，实际 $repaired", repaired > 20)
    }

    @Test
    fun `正常的纯色横条不应被误当成坏行`() {
        // 图像中间本来就有一条中灰横条：坏行判定要求「接近纯黑」，
        // 因此中灰横条必须保持原样（否则会把正常内容抹掉）
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = when {
                    y == 100 -> 200
                    else -> if (x % 2 == 0) 0 else 255
                }
                pixels[y * width + x] = (v shl 16) or (v shl 8) or v
            }
        }
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(repairBadLines = true),
        )
        assertEquals(
            "中灰横条（亮度 200）不是同步丢失的特征，不得被替换",
            200,
            (out[100 * width + 50] shr 16) and 0xFF,
        )
    }

    @Test
    fun `图像上下边缘的坏行不会被越界访问`() {
        // 第 0 行与最后一行没有「上下两行」，必须原样跳过而不是越界
        val pixels = IntArray(width * height) { 0x000000 }
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(repairBadLines = true),
        )
        assertEquals(pixels.size, out.size)
        assertTrue("全黑图像没有可用的参考行，应保持原样", out.all { it == 0x000000 })
    }

    @Test
    fun `灰度图处理后仍为灰度`() {
        val pixels = gradientPixels()
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(histogramStretch = true, medianFilter = true, repairBadLines = true),
        )
        for (index in out.indices step 373) {
            val p = out[index]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            assertEquals("后处理不得引入色偏", r, g)
            assertEquals("后处理不得引入色偏", g, b)
        }
    }

    @Test
    fun `处理结果与入参不是同一个数组`() {
        val pixels = noisyGradient()
        val out = SstvPostProcess.process(
            pixels,
            width,
            height,
            SstvPostProcess.Options(medianFilter = true),
        )
        assertNotSame("启用处理后必须返回新数组", pixels, out)
        assertEquals(pixels.size, out.size)
    }

    @Test
    fun `单帧后处理耗时在可接受范围`() {
        // 验收标准是 < 200 ms/帧（PD-120 的 640×496）。这里给足余量只为拦住
        // 「算法退化成 O(n²)」这类量级问题，避免在慢速 CI 上偶发失败
        val frameWidth = 640
        val frameHeight = 496
        val pixels = IntArray(frameWidth * frameHeight) { index ->
            val x = index % frameWidth
            val v = (x * 255 / frameWidth)
            (v shl 16) or (v shl 8) or v
        }
        val options = SstvPostProcess.Options(
            histogramStretch = true,
            medianFilter = true,
            repairBadLines = true,
        )
        // 预热一次，排除 JIT 的影响
        SstvPostProcess.process(pixels, frameWidth, frameHeight, options)
        val start = System.nanoTime()
        SstvPostProcess.process(pixels, frameWidth, frameHeight, options)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("640×496 三项后处理耗时 ${"%.0f".format(elapsedMs)} ms，超出可接受范围", elapsedMs < 2000.0)
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    private fun gradientPixels(): IntArray = IntArray(width * height) { index ->
        val v = (index % width) * 255 / width
        (v shl 16) or (v shl 8) or v
    }

    /** 渐变 + 少量脉冲噪声（供「不修改入参」与耗时用例使用）。 */
    private fun noisyGradient(): IntArray {
        val pixels = gradientPixels()
        var seed = 12345L
        for (i in pixels.indices step 97) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            pixels[i] = if ((seed ushr 40).toInt() and 1 == 0) 0xFFFFFF else 0x000000
        }
        return pixels
    }
}
