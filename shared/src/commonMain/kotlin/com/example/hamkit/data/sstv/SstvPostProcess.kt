package com.example.hamkit.data.sstv

/**
 * 解码图像的可选后处理。
 *
 * 一期自环实测已达 43–56 dB PSNR，也就是说**算法本身不是瓶颈**；真实电波成图的
 * 观感差距主要来自三处，本对象逐一对应：
 *
 * | 现象 | 成因 | 处理 |
 * |---|---|---|
 * | 整幅灰蒙蒙、对比度低 | 电台 AGC / 声学链路把电平压在中间，黑不到底也白不到顶 | [Options.histogramStretch] 直方图拉伸 |
 * | 孤立亮点/暗点（椒盐） | 脉冲噪声、点火噪声、数字链路丢包 | [Options.medianFilter] 3×3 中值 |
 * | 整行纯黑或纯色 | 行同步丢失，该行采到了同步脉冲或 porch 的频率 | [Options.repairBadLines] 坏行插值 |
 *
 * **三项默认全关**：处理不可逆，用户可能更想要原始结果。UI 侧始终保留原始解码
 * 结果用于「保存原图」，并在设置里给出开关。
 *
 * 全部为纯函数，不依赖 Android API，可直接在 JVM 单测中验证。
 */
object SstvPostProcess {

    /** 后处理开关组合。 */
    data class Options(
        val histogramStretch: Boolean = false,
        val medianFilter: Boolean = false,
        val repairBadLines: Boolean = false,
    ) {
        /** 是否有任何一项启用。 */
        val anyEnabled: Boolean get() = histogramStretch || medianFilter || repairBadLines

        companion object {
            /** 全关（默认）。 */
            val NONE = Options()
        }
    }

    /**
     * 对打包 RGB（0xRRGGBB）像素做后处理，返回**新数组**（不修改入参）。
     *
     * 处理顺序：坏行插值 → 中值滤波 → 直方图拉伸。顺序有讲究：
     * 先修结构（坏行），再去脉冲噪声（中值），最后才拉伸 —— 若先拉伸，被坏行和
     * 噪点污染的极值会把拉伸区间撑歪，反而压低有效对比度。
     */
    fun process(pixels: IntArray, width: Int, height: Int, options: Options): IntArray {
        if (!options.anyEnabled || width <= 0 || height <= 0) return pixels
        var working = pixels.copyOf()

        if (options.repairBadLines) working = repairBadLines(working, width, height)
        if (options.medianFilter) working = median3x3(working, width, height)
        if (options.histogramStretch) working = histogramStretch(working)

        return working
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 坏行插值
    // ══════════════════════════════════════════════════════════════════

    /**
     * 用上下相邻行替换「同步丢失」的坏行。
     *
     * 判定条件刻意保守（宁可漏修，不可误修），三项必须同时成立：
     *  1. 该行**内部几乎无变化**（极差 ≤ [UNIFORM_RANGE]）；
     *  2. 该行**接近纯黑**（均值 ≤ [NEAR_BLACK_LEVEL]）。这一条是关键：同步丢失时该行
     *     采到的是 1200 Hz 同步脉冲或 1500 Hz porch，两者都在黑电平（1500 Hz）或以下，
     *     因此必然映射为黑。加上这一条就不会把图像里正常存在的**中灰/亮色横条**误判；
     *  3. 上下两行都**有变化**，且均值彼此接近（≤ [NEIGHBOR_MEAN_TOLERANCE]）——
     *     真正的内容在大面积上不会突然变成一条横贯全宽的纯黑带。
     */
    private fun repairBadLines(pixels: IntArray, width: Int, height: Int): IntArray {
        if (height < 3) return pixels
        val uniform = BooleanArray(height)
        val mean = IntArray(height)
        for (y in 0 until height) {
            var min = 255
            var max = 0
            var sum = 0
            val base = y * width
            for (x in 0 until width) {
                val lum = luminance(pixels[base + x])
                if (lum < min) min = lum
                if (lum > max) max = lum
                sum += lum
            }
            uniform[y] = (max - min) <= UNIFORM_RANGE
            mean[y] = sum / width
        }

        val out = pixels.copyOf()
        for (y in 1 until height - 1) {
            if (!uniform[y] || mean[y] > NEAR_BLACK_LEVEL) continue
            if (uniform[y - 1] || uniform[y + 1]) continue
            if (kotlin.math.abs(mean[y - 1] - mean[y + 1]) > NEIGHBOR_MEAN_TOLERANCE) continue
            val above = (y - 1) * width
            val below = (y + 1) * width
            val target = y * width
            for (x in 0 until width) {
                val a = out[above + x]
                val b = out[below + x]
                out[target + x] = SstvColor.pack(
                    (SstvColor.redOf(a) + SstvColor.redOf(b)) / 2,
                    (SstvColor.greenOf(a) + SstvColor.greenOf(b)) / 2,
                    (SstvColor.blueOf(a) + SstvColor.blueOf(b)) / 2,
                )
            }
        }
        return out
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 3×3 中值滤波
    // ══════════════════════════════════════════════════════════════════

    /**
     * 逐通道 3×3 中值滤波。
     *
     * 选 3×3 中值而不是均值：SSTV 的噪点是**脉冲型**（孤立亮点），中值能在不模糊
     * 边缘的前提下把它抹掉，而均值会把噪点摊成一片灰雾。窗只有 3×3 也刻意 ——
     * SSTV 本身水平细节就有限（PD-120 一个像素 190 µs），更大的窗会糊掉真实细节。
     *
     * 边界用「夹紧坐标」复制边缘像素，避免图像四周出现暗边。
     */
    private fun median3x3(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(pixels.size)
        val r = IntArray(9)
        val g = IntArray(9)
        val b = IntArray(9)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var n = 0
                for (dy in -1..1) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val p = pixels[sy * width + sx]
                        r[n] = SstvColor.redOf(p)
                        g[n] = SstvColor.greenOf(p)
                        b[n] = SstvColor.blueOf(p)
                        n++
                    }
                }
                out[y * width + x] = SstvColor.pack(median9(r), median9(g), median9(b))
            }
        }
        return out
    }

    /** 9 个元素的插入排序取中位数（固定 9 元素时比通用排序快且无分配）。 */
    private fun median9(values: IntArray): Int {
        // 局部插入排序（values 会被就地打乱，但每次调用前都重新填充）
        for (i in 1 until 9) {
            val key = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > key) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = key
        }
        return values[4]
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 直方图拉伸
    // ══════════════════════════════════════════════════════════════════

    /**
     * 逐通道直方图拉伸（把实际动态范围拉满到 0–255）。
     *
     * 用**分位数**（0.5% / 99.5%）而不是 Min/Max 作为拉伸端点：只要有一个坏点或
     * 一处过冲，Min/Max 就会被拉到极值，拉伸后几乎等于没拉。分位数对少量离群值免疫。
     *
     * 端点过窄（动态范围 < [MIN_STRETCH_SPAN]）时判为「本来就接近纯色」，跳过以免
     * 把噪声放大成花纹。
     */
    private fun histogramStretch(pixels: IntArray): IntArray {
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        for (p in pixels) {
            red[SstvColor.redOf(p)]++
            green[SstvColor.greenOf(p)]++
            blue[SstvColor.blueOf(p)]++
        }
        val total = pixels.size
        val (rLow, rHigh) = percentileBounds(red, total)
        val (gLow, gHigh) = percentileBounds(green, total)
        val (bLow, bHigh) = percentileBounds(blue, total)

        val rSpan = rHigh - rLow
        val gSpan = gHigh - gLow
        val bSpan = bHigh - bLow

        // 逐通道查表，避免在每个像素上重复做除法
        val rLut = stretchLut(rLow, rSpan)
        val gLut = stretchLut(gLow, gSpan)
        val bLut = stretchLut(bLow, bSpan)

        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = SstvColor.redOf(p)
            val g = SstvColor.greenOf(p)
            val b = SstvColor.blueOf(p)
            SstvColor.pack(
                rLut?.get(r) ?: r,
                gLut?.get(g) ?: g,
                bLut?.get(b) ?: b,
            )
        }
    }

    /** 动态范围过窄时返回 null（表示该通道跳过拉伸）。 */
    private fun stretchLut(low: Int, span: Int): IntArray? {
        if (span < MIN_STRETCH_SPAN) return null
        return IntArray(256) { v ->
            ((v - low).coerceAtLeast(0) * 255 / span).coerceAtMost(255)
        }
    }

    /**
     * 求直方图中的 0.5% / 99.5% 分位点。
     *
     * @return `low to high`；直方图为空时返回 `0 to 255`
     */
    private fun percentileBounds(histogram: IntArray, total: Int): Pair<Int, Int> {
        if (total <= 0) return 0 to 255
        val lowTarget = (total * PERCENTILE_TAIL).toInt()
        val highTarget = total - lowTarget

        var cumulative = 0
        var low = 0
        var high = 255
        for (v in 0..255) {
            cumulative += histogram[v]
            if (cumulative <= lowTarget) low = v
        }
        cumulative = 0
        for (v in 0..255) {
            cumulative += histogram[v]
            if (cumulative >= highTarget) {
                high = v
                break
            }
        }
        return low to high
    }

    /** 打包 RGB → 亮度（只用 BT.601 权重，整数近似即可，仅用于坏行判定）。 */
    private fun luminance(rgb: Int): Int =
        (SstvColor.redOf(rgb) * 77 + SstvColor.greenOf(rgb) * 150 + SstvColor.blueOf(rgb) * 29) shr 8

    /** 判定「行内几乎无变化」的极差阈值（8 级亮度以内）。 */
    private const val UNIFORM_RANGE = 8

    /**
     * 判定「接近纯黑」的亮度上限。
     *
     * 依据：同步丢失时采到的是 1200 Hz（同步脉冲）或 1500 Hz（porch），扣掉黑电平后
     * 都落在 0 附近，因此坏行必然是黑的。用「黑」而不是「任意纯色」来限定，可以避免
     * 把图像里正常的中灰/亮色横条误当成坏行。
     */
    private const val NEAR_BLACK_LEVEL = 32

    /** 上下行均值允许的最大差异，超过说明这不是同一条内容的丢失行。 */
    private const val NEIGHBOR_MEAN_TOLERANCE = 60

    /** 拉伸端点靠得太近时放弃拉伸（否则会把噪声放大成花纹）。 */
    private const val MIN_STRETCH_SPAN = 16

    /** 分位端点：两侧各裁掉 0.5%。 */
    private const val PERCENTILE_TAIL = 0.005
}
