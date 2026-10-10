package com.example.hamkit.data.sstv

import kotlin.math.ln

/**
 * SSTV 解调信号的**瀑布图**（滚动频率-时间显示）。
 *
 * **为什么不是 FFT 频谱图**：SSTV 是单载波模拟调频，任一时刻只有一个瞬时频率，
 * 因此「逐样本瞬时频率」本身就是最忠实的谱图 —— 把每个 4 ms 内的频率分布按格累加，
 * 得到的就是教科书上那张「同步脉冲是一条 1200 Hz 直线、图像内容是一条游走的带」
 * 的瀑布图。这样做的成本是每样本一次比较（48 kHz 下可忽略），而真正的 FFT 谱图要
 * 每 4 ms 做一次 2048 点 FFT（约 1.2 万次/秒），代价高一个数量级却看不出更多信息。
 *
 * 用途（对应 robot36 的 `postProcessScopeImage` / `scopeBufferWidth`）：
 *  - 调谐时判断「到底有没有信号」「失谐多少」（同步线是否偏离 1200 Hz 格）；
 *  - 判断信号质量（谱线越细越干净，散开成一片说明信噪比差）；
 *  - 让用户看懂 VIS 头、行同步、通道结构，而不是面对一张慢慢浮现的图干等。
 *
 * 纯 Kotlin，不依赖 Android API，可直接单测。
 *
 * @param bins 频率格数（横向分辨率）
 * @param rows 保留的历史行数（纵向）
 * @param rowMilliseconds 每行覆盖的时长（毫秒）—— 越小时间分辨率越高、可见历史越短
 * @param sampleRate 采样率（Hz）
 * @param lowHz 最低显示频率（Hz），低于此值的内容归入第 0 格
 * @param highHz 最高显示频率（Hz），高于此值的内容归入最后一格
 */
class SstvWaterfall(
    val bins: Int = DEFAULT_BINS,
    val rows: Int = DEFAULT_ROWS,
    val rowMilliseconds: Int = DEFAULT_ROW_MS,
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    val lowHz: Double = DEFAULT_LOW_HZ,
    val highHz: Double = DEFAULT_HIGH_HZ,
) {

    init {
        require(bins > 1) { "频率格数必须 > 1，实际 $bins" }
        require(rows > 1) { "行数必须 > 1，实际 $rows" }
        require(rowMilliseconds > 0) { "每行时长必须 > 0，实际 $rowMilliseconds" }
        require(highHz > lowHz) { "频率上限必须高于下限" }
    }

    /** 每行覆盖的样本数。 */
    val samplesPerRow: Int = (sampleRate.toLong() * rowMilliseconds / 1000L).toInt().coerceAtLeast(1)

    /** 可见历史总时长（秒）。 */
    val historySeconds: Double get() = rows.toDouble() * rowMilliseconds / 1000.0

    /** 频率格的宽度（Hz）。 */
    val binHz: Double get() = (highHz - lowHz) / bins

    /** 环形缓冲：`intensity[row * bins + bin]`，0–255。 */
    private val intensity = IntArray(bins * rows)

    /** 当前正在累加的行在环形缓冲中的下标。 */
    private var currentRow = -1

    /** 当前行已累加的样本数。 */
    private var currentFilled = 0

    /** 上一行落盘后，环形缓冲里最新一行的下标；尚未产出任何行时为 -1。 */
    var headRow: Int = -1
        private set

    /** 已产出的总行数（可超过 [rows]，用于判断是否已滚过一圈）。 */
    var producedRows: Long = 0L
        private set

    /**
     * 送入一段逐样本瞬时频率（Hz）。
     *
     * @return 本次落盘的行数
     */
    fun feed(freqs: FloatArray, fromIndex: Int = 0): Int {
        if (freqs.isEmpty()) return 0
        val start = fromIndex.coerceIn(0, freqs.size)
        var completed = 0

        if (currentRow < 0) {
            currentRow = 0
            currentFilled = 0
        }

        var index = start
        while (index < freqs.size) {
            if (currentFilled == 0) {
                // 新的一行：先把这一行的强度清零
                java.util.Arrays.fill(intensity, currentRow * bins, currentRow * bins + bins, 0)
            }
            val take = minOf(samplesPerRow - currentFilled, freqs.size - index)
            for (i in index until index + take) {
                val bin = binOf(freqs[i])
                if (bin >= 0) intensity[currentRow * bins + bin]++
            }
            currentFilled += take
            index += take

            if (currentFilled >= samplesPerRow) {
                normalizeRow(currentRow)
                headRow = currentRow
                producedRows++
                completed++
                currentRow = (currentRow + 1) % rows
                currentFilled = 0
            }
        }
        return completed
    }

    /** 清空全部历史。 */
    fun reset() {
        intensity.fill(0)
        currentRow = -1
        currentFilled = 0
        headRow = -1
        producedRows = 0L
    }

    /**
     * 把某个频率映射到频率格；不可用（NaN / 无穷）返回 -1。
     *
     * 越界不是丢弃而是**夹紧到首/末格**：失谐很大时用户仍应看到「信号在视野边缘」，
     * 而不是一片空白 —— 那会让人误以为没信号。
     */
    fun binOf(hz: Float): Int {
        if (!hz.isFinite()) return -1
        val normalized = (hz - lowHz) / (highHz - lowHz)
        val bin = (normalized * bins).toInt()
        return bin.coerceIn(0, bins - 1)
    }

    /**
     * 取一份按「最旧行在前、最新行在后」排列的强度快照。
     *
     * 每次调用都会新建数组（UI 以约 10 Hz 调用，384×96 的拷贝约 150 KB，可接受）——
     * 换成复用缓冲会让 Compose 无法通过引用变化感知更新。
     */
    fun snapshot(): IntArray {
        val out = IntArray(bins * rows)
        if (headRow < 0) return out
        // 最新行是 headRow；从它往前数 rows 行，依次写入 out
        for (i in 0 until rows) {
            val sourceRow = ((headRow - (rows - 1 - i)) % rows + rows) % rows
            System.arraycopy(intensity, sourceRow * bins, out, i * bins, bins)
        }
        return out
    }

    /**
     * 把一行的原始计数映射到 0–255。
     *
     * 用对数映射而不是线性：单音信号的能量集中在极少数格（计数可达样本数），而噪声
     * 均匀铺满所有格（每格个位数）。线性映射会让噪声几乎不可见、信号全白；对数映射
     * 同时保留「谱线粗细」这一判断信噪比的关键信息。
     */
    private fun normalizeRow(row: Int) {
        val base = row * bins
        val peak = samplesPerRow.toDouble()
        for (b in 0 until bins) {
            val count = intensity[base + b]
            if (count <= 0) continue
            val scaled = 255.0 * ln(1.0 + count) / ln(1.0 + peak)
            intensity[base + b] = scaled.toInt().coerceIn(1, 255)
        }
    }

    companion object {
        /** 默认频率格数：1500 Hz 跨度 / 96 ≈ 15.6 Hz 一格，足以分辨 1200/1300 Hz 的 VIS 位。 */
        const val DEFAULT_BINS = 96

        /** 默认保留行数。 */
        const val DEFAULT_ROWS = 384

        /** 默认每行时长（毫秒）。 */
        const val DEFAULT_ROW_MS = 4

        /** 默认显示下限（略低于 VIS 的 1100 Hz 位音）。 */
        const val DEFAULT_LOW_HZ = 1000.0

        /** 默认显示上限（高于白电平 2300 Hz 留出失谐余量）。 */
        const val DEFAULT_HIGH_HZ = 2500.0

        /** 默认可见历史时长（秒）= 384 × 4 ms。 */
        const val DEFAULT_HISTORY_SECONDS = 1.536
    }
}
