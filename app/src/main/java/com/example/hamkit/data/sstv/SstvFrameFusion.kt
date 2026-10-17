package com.example.hamkit.data.sstv

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 跨帧高质量融合（SSTV 三期 Q3 + Q4）。
 *
 * 上游调研：[docs/SSTV_PHASE3_PLAN.md](../../../../../../../../../docs/SSTV_PHASE3_PLAN.md) §四
 *
 * ## 为什么是「跨帧」而不是「同一段音频反复解析」
 *
 * 调研的实验一已经证伪了朴素形式：同一段音频换积分窗再融合是**负收益**（最佳单次
 * 23.8 dB → 融合 23.0 dB），因为不同参数给出的是「同一条偏差-方差曲线上的不同点」，
 * 不是对同一个量的独立观测。真正能平均掉的只有**独立噪声**，而 SSTV 里独立噪声只有
 * 一个来源：**同一张图被重复传输**（ARISS 活动会轮播同一批图）。
 *
 * ## 五步链路
 *
 * ```
 * ① 重复帧识别  缩略图归一化互相关 + 最近邻聚类（多簇，因为一次活动会轮播多张图）
 * ② 帧质量筛选  同步命中率 / slant / 行数 / 互相关峰值 —— 不达标不参与融合
 * ③ 帧间配准    行偏移（整数）+ 列偏移（整数）逐级搜索，行内线性伸缩由解码器 slant 校正覆盖
 * ④ 鲁棒融合    逐像素中位数（抗离群）或按相关性加权均值
 * ⑤ 质量反馈    给出「已累积 K 帧」与基于实测残差的增益估计
 * ```
 *
 * ## 收益预期（调研实测，不要夸大）
 *
 * SNR 15 dB 时 K=8 实测 **+1.3 dB**，K=2 为 +0.8 dB，远低于理论 √K（+9 dB），因为
 * 误差里系统误差（量化、频率-亮度映射、滤波过渡带、对齐残差）主导，平均不掉。
 * 弱信号下**必须先筛选**：实验二里 SNR 10 dB 时未筛选的 K=2 融合反而**掉 1.3 dB**。
 *
 * 纯 Kotlin 实现，不依赖 Android API。
 */
object SstvFrameFusion {

    /** 缩略图边长（灰度），用于重复帧识别。16×16 = 256 维，足够区分 ARISS 的轮播图。 */
    const val THUMB_SIDE = 16

    /** 质量筛选门限。 */
    data class Thresholds(
        /** 同步命中率下限（[SstvStreamDecoder.Stats.syncHitRate]）。 */
        val minSyncHitRate: Float = 0.6f,
        /** slant 与 1.0 的最大偏差（超过说明行周期拟合偏了，图像会被拉伸）。 */
        val maxSlantDeviation: Double = 0.03,
        /** 解码行数占模式标称行数的比例下限。 */
        val minLineCoverage: Double = 0.8,
        /** 与簇中心的归一化互相关下限 —— 低于它说明「对不齐」，混进去只会糊。 */
        val minCorrelation: Double = 0.6,
    )

    /** 融合方式。 */
    enum class Method {
        /** 逐像素中位数：抗某帧局部错位的离群，弱信号下更稳（默认）。 */
        MEDIAN,

        /** 按与簇中心的相关性加权求均值：相关性高的帧权重更大。 */
        WEIGHTED_MEAN,
    }

    /**
     * 一帧融合输入。
     *
     * @param pixels 行优先打包 RGB
     * @param syncHitRate 同步命中率（0–1）
     * @param slantRatio 拟合行周期 / 标称行周期
     * @param decodedLines 实际解出的行数
     */
    data class Frame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val syncHitRate: Float = 1f,
        val slantRatio: Double = 1.0,
        val decodedLines: Int = height,
    ) {
        /** 像素尺寸的稳定键（宽度 × 高度），只有同尺寸的帧才可能属于同一簇。 */
        val shape: Int get() = width * 31 + height
    }

    /**
     * 一帧的筛选结论。
     *
     * @param accepted 是否参与融合
     * @param reason 拒绝原因（accepted 为 false 时非空，用于 UI 如实说明）
     * @param columnShift 配准得到的列偏移（像素）
     * @param rowShift 配准得到的行偏移（行）
     * @param correlation 与簇中心的归一化互相关（-1..1）
     */
    data class Verdict(
        val accepted: Boolean,
        val reason: String? = null,
        val columnShift: Int = 0,
        val rowShift: Int = 0,
        val correlation: Double = 1.0,
    )

    /**
     * 融合结果。
     *
     * @param pixels 融合后的图像（行优先打包 RGB，尺寸与簇中心一致）
     * @param usedFrames 实际参与融合的帧数
     * @param rejected 被筛掉的帧数与原因
     * @param gainDb 估计增益（dB）。按「融合前后残差」实测估计，单帧时为 0
     * @param correlationMean 参与融合的帧与簇中心的平均互相关
     */
    data class Result(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val usedFrames: Int,
        val rejected: List<Pair<Int, String>>,
        val gainDb: Double,
        val correlationMean: Double,
    )

    /**
     * 判断两帧是否是「同一张图」。
     *
     * 用 16×16 灰度缩略图的归一化互相关（NCC）。选它而不是 MSE：接收增益 / 白平衡
     * 在两次传输之间可能不同（AGC、电台音量），NCC 对整体线性变化不敏感。
     *
     * @return 相关系数（-1..1）；尺寸不同直接返回 -1
     */
    fun similarity(a: Frame, b: Frame): Double {
        if (a.width != b.width || a.height != b.height) return -1.0
        return ncc(thumbnail(a), thumbnail(b))
    }

    /** 生成 [THUMB_SIDE]² 灰度缩略图（区域平均，抗噪）。 */
    fun thumbnail(frame: Frame): FloatArray {
        val side = THUMB_SIDE
        val out = FloatArray(side * side)
        val counts = IntArray(side * side)
        for (y in 0 until frame.height) {
            val ty = (y * side / frame.height).coerceIn(0, side - 1)
            for (x in 0 until frame.width) {
                val tx = (x * side / frame.width).coerceIn(0, side - 1)
                val index = ty * side + tx
                out[index] += luminance(frame.pixels[y * frame.width + x]).toFloat()
                counts[index]++
            }
        }
        for (i in out.indices) {
            if (counts[i] > 0) out[i] /= counts[i]
        }
        return out
    }

    /** 把一帧按 [thresholds] 做质量筛选（不含互相关，那一项需要簇中心）。 */
    fun screen(frame: Frame, mode: SstvMode, thresholds: Thresholds = Thresholds()): String? {
        if (abs(frame.slantRatio - 1.0) > thresholds.maxSlantDeviation) {
            return "行周期偏差 ${"%.1f".format((frame.slantRatio - 1) * 100)}%"
        }
        val coverage = frame.decodedLines.toDouble() / mode.imageLines
        if (coverage < thresholds.minLineCoverage) {
            return "只解出 ${"%.0f".format(coverage * 100)}% 的行"
        }
        if (frame.syncHitRate < thresholds.minSyncHitRate) {
            return "同步命中率 ${"%.0f".format(frame.syncHitRate * 100)}%"
        }
        return null
    }

    /**
     * 完整识别 + 筛选 + 配准 + 融合。
     *
     * @param frames 候选帧（一般来自 [SstvStreamDecoder.finalizeFrames]，即持续接收的产物）
     * @param mode 本次接收的模式（用于判断行数覆盖）
     * @param method 融合方式
     * @param thresholds 质量门限
     * @return 融合结果；没有合格帧时返回 null
     */
    fun fuse(
        frames: List<Frame>,
        mode: SstvMode,
        method: Method = Method.MEDIAN,
        thresholds: Thresholds = Thresholds(),
    ): Result? {
        if (frames.isEmpty()) return null

        // ① 重复帧识别：按缩略图互相关做最近邻聚类（多簇，顺序即「首次收到」的顺序）
        val clusters = ArrayList<MutableList<Frame>>()
        val clusterThumbs = ArrayList<FloatArray>()
        for (frame in frames) {
            val thumb = thumbnail(frame)
            // 先按尺寸过滤：不同尺寸绝不可能同簇（也避免缩略图维度错配）
            var bestCluster = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (index in clusters.indices) {
                val center = clusters[index][0]
                if (center.width != frame.width || center.height != frame.height) continue
                val score = ncc(thumb, clusterThumbs[index])
                if (score > bestScore) {
                    bestScore = score
                    bestCluster = index
                }
            }
            if (bestCluster >= 0 && bestScore >= thresholds.minCorrelation) {
                clusters[bestCluster].add(frame)
            } else {
                clusters.add(mutableListOf(frame))
                clusterThumbs.add(thumb)
            }
        }

        // ② 取「成员最多」的簇：用户最关心反复收到的那张图
        val cluster = clusters.maxByOrNull { it.size } ?: return null
        val center = cluster.first()
        val centerThumb = thumbnail(center)
        val rejected = ArrayList<Pair<Int, String>>()
        val accepted = ArrayList<Frame>()
        val shifts = ArrayList<Pair<Int, Int>>()
        var correlationSum = 0.0

        for ((index, frame) in cluster.withIndex()) {
            val screenReason = screen(frame, mode, thresholds)
            if (screenReason != null) {
                rejected.add(index to screenReason)
                continue
            }
            val alignment = align(center, frame)
            if (alignment.score < thresholds.minCorrelation) {
                rejected.add(index to "与首帧对不齐（相关 ${"%.2f".format(alignment.score)}）")
                continue
            }
            accepted.add(frame)
            shifts.add(alignment.rowShift to alignment.columnShift)
            correlationSum += alignment.score
        }
        if (accepted.isEmpty()) return null

        // ③④ 融合
        val pixels = when (method) {
            Method.MEDIAN -> fuseMedian(center, accepted, shifts)
            Method.WEIGHTED_MEAN -> fuseWeightedMean(center, accepted, shifts, centerThumb)
        }

        // ⑤ 增益估计：用「参与融合的帧与融合结果的平均差」对比「首帧与融合结果的差」。
        //    这是实测残差而不是 √K 理论值 —— 系统误差平均不掉，理论值会把预期抬得过高。
        val gain = estimateGain(center, accepted, shifts, pixels, center.width, center.height)

        return Result(
            pixels = pixels,
            width = center.width,
            height = center.height,
            usedFrames = accepted.size,
            rejected = rejected,
            gainDb = gain,
            correlationMean = correlationSum / accepted.size,
        )
    }

    /**
     * 按中位数融合：逐像素对 (R, G, B) 三个通道分别取中位数。
     *
     * 中位数抗「某帧局部错位」的离群（实验二弱信号下的反噬就来自这种离群），
     * 比均值稳，代价是多一次排序 —— 帧数与尺寸都有限，开销可接受。
     */
    private fun fuseMedian(
        center: Frame,
        frames: List<Frame>,
        shifts: List<Pair<Int, Int>>,
    ): IntArray {
        val w = center.width
        val h = center.height
        val out = IntArray(w * h)
        val n = frames.size
        val r = IntArray(n)
        val g = IntArray(n)
        val b = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (i in 0 until n) {
                    val color = pixelAt(frames[i], x + shifts[i].second, y + shifts[i].first)
                    r[i] = (color shr 16) and 0xFF
                    g[i] = (color shr 8) and 0xFF
                    b[i] = color and 0xFF
                }
                r.sort()
                g.sort()
                b.sort()
                val mid = if (n % 2 == 1) n / 2 else n / 2 - 1
                out[y * w + x] = (r[mid] shl 16) or (g[mid] shl 8) or b[mid]
            }
        }
        return out
    }

    /** 按「与簇中心的相关性」加权求均值（相关性越高权重越大，低于 0 的权重置 0）。 */
    private fun fuseWeightedMean(
        center: Frame,
        frames: List<Frame>,
        shifts: List<Pair<Int, Int>>,
        centerThumb: FloatArray,
    ): IntArray {
        val w = center.width
        val h = center.height
        val weights = DoubleArray(frames.size)
        for (i in frames.indices) {
            // 用配准后的实际帧算权重：直接比缩略图会把「对得准但偏移了」的帧算低
            val score = ncc(centerThumb, thumbnail(shifted(frames[i], shifts[i].rowShift, shifts[i].columnShift)))
            weights[i] = max(0.0, score)
        }
        if (weights.all { it <= 0.0 }) weights.fill(1.0)
        val weightSum = weights.sum()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (i in frames.indices) {
                    val color = pixelAt(frames[i], x + shifts[i].second, y + shifts[i].first)
                    val weight = weights[i]
                    r += ((color shr 16) and 0xFF) * weight
                    g += ((color shr 8) and 0xFF) * weight
                    b += (color and 0xFF) * weight
                }
                out[y * w + x] = (clamp((r / weightSum).toInt()) shl 16) or
                    (clamp((g / weightSum).toInt()) shl 8) or
                    clamp((b / weightSum).toInt())
            }
        }
        return out
    }

    /**
     * 实测增益（dB）：`10·log10(单帧偏离 / 融合偏离)`。
     *
     * 偏离以「各帧（按配准偏移）的逐像素均值」为参考量：
     *  - **融合后偏离** = 融合结果与参考量的差 —— 平均掉多少随机噪声，这里就剩多少；
     *  - **单帧偏离** = 各帧与参考量的差的均值 —— 除以 K 之后即「平均掉的部分」。
     *
     * 二者之比与 √K 理论同量级，但会把系统误差（各帧相同的偏差）排除在外，
     * 因此给出的是**实测收益**而不是理论上限。单帧融合返回 0。
     */
    private fun estimateGain(
        center: Frame,
        frames: List<Frame>,
        shifts: List<Pair<Int, Int>>,
        fused: IntArray,
        w: Int,
        h: Int,
    ): Double {
        if (frames.size < 2) return 0.0
        val margin = min(8, w / 4)
        var fusedDeviation = 0.0
        var frameDeviation = 0.0
        var count = 0L
        for (y in 0 until h) {
            for (x in margin until w - margin) {
                // 参考量：参与融合各帧的亮度均值
                var mean = 0.0
                for (i in frames.indices) {
                    mean += luminance(pixelAt(frames[i], x + shifts[i].second, y + shifts[i].first))
                }
                mean /= frames.size
                val d = luminance(fused[y * w + x]) - mean
                fusedDeviation += d * d
                for (i in frames.indices) {
                    val fi = luminance(pixelAt(frames[i], x + shifts[i].second, y + shifts[i].first))
                    frameDeviation += (fi - mean) * (fi - mean)
                }
                count++
            }
        }
        if (count == 0L || fusedDeviation <= 0.0) return 0.0
        val n = frames.size.toDouble()
        // 单帧相对平均量的方差 ≈ frameDeviation / (count·(K−1))；融合结果的是 fusedDeviation / count
        val singleMse = frameDeviation / (count * (n - 1)).coerceAtLeast(1.0)
        val fusedMse = fusedDeviation / count
        if (singleMse <= 0.0) return 0.0
        return max(0.0, 10.0 * log10(singleMse / fusedMse))
    }

    /**
     * 配准：找出让 [frame] 与 [center] 最相似的整数偏移。
     *
     * 先搜行偏移（±[MAX_ROW_SHIFT]，粗 4 行步长 + 细化），再在最佳行偏移下搜列偏移
     * （±[MAX_COLUMN_SHIFT]）。不做亚像素：调研已说明收益有限，而整像素错位才是要害。
     */
    fun align(center: Frame, frame: Frame): Alignment {
        if (center.width != frame.width || center.height != frame.height) {
            return Alignment(0, 0, -1.0)
        }
        val w = min(center.width, frame.width)
        val h = min(center.height, frame.height)
        // 只在中部区域比对，避开边缘过渡带
        val x0 = w / 8
        val x1 = w - w / 8
        val step = max(1, (x1 - x0) / 64)
        val yStep = max(1, h / 64)

        fun score(rowShift: Int, colShift: Int): Double {
            var sumA = 0.0
            var sumB = 0.0
            var sumAA = 0.0
            var sumBB = 0.0
            var sumAB = 0.0
            var count = 0
            for (y in 0 until h step yStep) {
                for (x in x0 until x1 step step) {
                    val a = luminance(center.pixels[y * center.width + x]).toDouble()
                    val sx = x + colShift
                    val sy = y + rowShift
                    if (sx < 0 || sx >= frame.width || sy < 0 || sy >= frame.height) continue
                    val b = luminance(frame.pixels[sy * frame.width + sx]).toDouble()
                    sumA += a
                    sumB += b
                    sumAA += a * a
                    sumBB += b * b
                    sumAB += a * b
                    count++
                }
            }
            if (count < 16) return -1.0
            val n = count.toDouble()
            val cov = sumAB / n - (sumA / n) * (sumB / n)
            val varA = sumAA / n - (sumA / n) * (sumA / n)
            val varB = sumBB / n - (sumB / n) * (sumB / n)
            val denom = sqrt(max(varA, 1e-9) * max(varB, 1e-9))
            return cov / denom
        }

        var bestRow = 0
        var bestScore = score(0, 0)
        // 行偏移：粗搜（步长 4）后细化 ±3
        var row = -MAX_ROW_SHIFT
        while (row <= MAX_ROW_SHIFT) {
            val value = score(row, 0)
            if (value > bestScore) {
                bestScore = value
                bestRow = row
            }
            row += 4
        }
        for (candidate in (bestRow - 3)..(bestRow + 3)) {
            if (candidate < -MAX_ROW_SHIFT || candidate > MAX_ROW_SHIFT) continue
            val value = score(candidate, 0)
            if (value > bestScore) {
                bestScore = value
                bestRow = candidate
            }
        }

        // 列偏移：在最佳行偏移下逐个尝试
        var bestCol = 0
        for (candidate in -MAX_COLUMN_SHIFT..MAX_COLUMN_SHIFT) {
            val value = score(bestRow, candidate)
            if (value > bestScore) {
                bestScore = value
                bestCol = candidate
            }
        }
        return Alignment(bestRow, bestCol, bestScore)
    }

    /** 配准结果：行偏移、列偏移与相关度。 */
    data class Alignment(val rowShift: Int, val columnShift: Int, val score: Double)

    /**
     * 逐行互相关（NCC）—— 比缩略图更细的判据。
     *
     * 缩略图只能回答「像不像同一张图」，对「差一两行」这种错位不敏感；融合前的
     * 对齐检查必须用行级 NCC，否则错位帧会被当成好帧混进来。
     */
    fun rowCorrelation(a: Frame, b: Frame, rowLimit: Int = 32): Double {
        val rows = min(min(a.height, b.height), rowLimit)
        if (rows < 4) return -1.0
        val w = min(a.width, b.width)
        val margin = min(8, w / 4)
        var sum = 0.0
        for (r in 0 until rows) {
            sum += nccSlices(a.pixels, r * a.width, b.pixels, r * b.width, margin, w)
        }
        return sum / rows
    }

    // ══════════════════════════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════════════════════════

    /** 把帧按偏移平移后返回新帧（越界按边缘像素填充），供缩略图相关性使用。 */
    private fun shifted(frame: Frame, rowShift: Int, columnShift: Int): Frame {
        if (rowShift == 0 && columnShift == 0) return frame
        val out = IntArray(frame.width * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                out[y * frame.width + x] = pixelAt(frame, x + columnShift, y + rowShift)
            }
        }
        return Frame(out, frame.width, frame.height)
    }

    private fun pixelAt(frame: Frame, x: Int, y: Int): Int {
        val cx = x.coerceIn(0, frame.width - 1)
        val cy = y.coerceIn(0, frame.height - 1)
        return frame.pixels[cy * frame.width + cx]
    }

    private fun luminance(color: Int): Int =
        (((color shr 16) and 0xFF) * 299 + ((color shr 8) and 0xFF) * 587 + (color and 0xFF) * 114) / 1000

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)

    /** 归一化互相关。 */
    private fun ncc(a: FloatArray, b: FloatArray): Double {
        val n = min(a.size, b.size)
        if (n == 0) return -1.0
        var sumA = 0.0
        var sumB = 0.0
        for (i in 0 until n) {
            sumA += a[i]
            sumB += b[i]
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        val denom = sqrt(varA * varB)
        return if (denom <= 1e-9) 0.0 else cov / denom
    }

    private fun nccSlices(
        a: IntArray,
        aOffset: Int,
        b: IntArray,
        bOffset: Int,
        from: Int,
        to: Int,
    ): Double {
        var sumA = 0.0
        var sumB = 0.0
        val n = (to - from).coerceAtLeast(0)
        if (n == 0) return 0.0
        for (x in from until to) {
            sumA += luminance(a[aOffset + x])
            sumB += luminance(b[bOffset + x])
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (x in from until to) {
            val da = luminance(a[aOffset + x]) - meanA
            val db = luminance(b[bOffset + x]) - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        val denom = sqrt(varA * varB)
        return if (denom <= 1e-9) 0.0 else cov / denom
    }

    /** 最大行偏移（行）。 */
    private const val MAX_ROW_SHIFT = 12

    /** 最大列偏移（像素）。 */
    private const val MAX_COLUMN_SHIFT = 8
}
