package com.example.hamkit.data.sstv

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HF Fax（WEFAX 天气传真）的协议常量。
 *
 * **与 SSTV 的根本差异**（决定了本文件的所有设计取舍）：WEFAX 既没有 VIS 头，也没有
 * 逐行同步脉冲。行节拍完全靠「**120 行/分钟**」这个约定（每行恰好 0.5 秒），接收端
 * 只能自由运行这个时钟。因此：
 *  - **垂直方向无法逐行校正**（没有可用的行锚点）；
 *  - **水平方向有救**：图像左侧有一条粗白边，统计各列平均亮度即可估出它落在哪一列，
 *    再把整行循环移位过去（见 [HfFaxDecoder.snapshot]）。
 *
 * 灰度单通道，频率映射与 SSTV 相同：[BLACK_HZ] = 黑、[WHITE_HZ] = 白。
 */
object HfFaxSpec {
    /** 标准线速率：120 行/分钟（IOC 576 的 WEFAX 约定）。 */
    const val LINES_PER_MINUTE = 120.0

    /** 每行时长（秒）。由线速率推出而非硬编码：[LINES_PER_MINUTE] = 120 → 0.5 s。 */
    const val LINE_SECONDS = 60.0 / LINES_PER_MINUTE

    /**
     * 每行像素数。
     *
     * IOC 576 图像本身只有 576 像素宽；这里沿用 640（与 robot36 一致），
     * 多出来的部分是相位区/白边，宁可比真实内容宽一点，也不要把右侧内容截掉。
     */
    const val WIDTH = 640

    /** 默认行数上限（约 10 分钟）。见 [HfFaxDecoder.maxLines] 的内存考量。 */
    const val DEFAULT_MAX_LINES = 1200

    /** 黑电平频率（Hz）。 */
    const val BLACK_HZ = 1500.0

    /** 白电平频率（Hz）。 */
    const val WHITE_HZ = 2300.0

    /** 启动音频率（Hz）。WEFAX 图像前有 5–10 秒的 300 Hz 纯音，是唯一的绝对时间锚点。 */
    const val START_TONE_HZ = 300.0

    /** 启动音判定容差（Hz，±）。见 [HfFaxDecoder.startToneToleranceHz]。 */
    const val DEFAULT_START_TONE_TOLERANCE_HZ = 50.0

    /** 判定为启动音所需的最短连续时长（秒）。 */
    const val DEFAULT_START_TONE_SECONDS = 2.0

    /** 列亮度指数滑动平均的衰减系数（每行衰减 1%）。 */
    const val COLUMN_DECAY = 0.99

    /** 零相位像素低通的阶数（两次一阶 EMA 级联 = 二阶，双向跑 = 零相位）。 */
    const val FILTER_ORDER = 2
}

/**
 * 一次 [HfFaxDecoder.snapshot] 的结果。
 *
 * @param width 行宽（恒为 [HfFaxSpec.WIDTH]）
 * @param height 已解出的行数
 * @param pixels 行优先灰度（0–255），**已按 [shiftPixels] 循环移位**
 * @param aligned 是否已应用白边对齐（即列统计至少累积过一行）
 * @param shiftPixels 为对齐而循环移位的列数。语义：`pixels[y*width + x]` 等于原始行第
 *   `(x + shiftPixels) % width` 列。把移位量一起返回，是为了让调用方（与单测）能在
 *   「未对齐的原始行」坐标系里复核结果，而不必猜对齐发生了多少
 */
data class HfFaxImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val aligned: Boolean,
    val shiftPixels: Int = 0,
) {
    /** 取 (x, y) 处灰度；越界返回 0（调用方应自行保证坐标合法）。 */
    fun grayAt(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

    /**
     * 转成打包 RGB（0xRRGGBB）。
     *
     * 与 SSTV 侧共用落盘/分享链路时用得上（`SstvImageStore.savePixels` 收的就是这个格式），
     * 因此这里只做灰度复制、不留 Android 依赖。
     */
    fun toRgbPixels(): IntArray = IntArray(pixels.size) {
        val v = pixels[it]
        (v shl 16) or (v shl 8) or v
    }
}

/**
 * HF Fax（WEFAX）解码器：逐样本瞬时频率流 → 灰度图像。
 *
 * 链路（每一步的「为什么」都在下面对应成员上）：
 * ```
 * ① 启动音检测   → 找到 300 Hz 纯音的结束点，把自由运行的行时钟对齐到那里
 * ② 切行         → 每 [HfFaxSpec.LINE_SECONDS] 秒一行，无任何行同步校正
 * ③ 频率→亮度    → 1500 Hz 黑 / 2300 Hz 白，整体频偏由启动音实测反推
 * ④ 零相位低通   → 截止在**像素 Nyquist**，前向 + 反向各跑一遍 EMA（不产生水平位移）
 * ⑤ 像素采样     → 在像素中心线性插值（每像素 37.5 样本，不是整数倍）
 * ⑥ 白边对齐     → 列亮度 EMA 找最亮列，渲染时循环移位到 x = 0
 * ```
 *
 * ## 与参考实现（xdsopl/robot36 `HFFax.java`）的差异
 *
 * 低通系数公式与「前向 + 反向跑两遍」的做法照抄 robot36；以下是刻意不同之处：
 *  1. **反向那一遍用该遍的首个样本预热滤波器**，而不是用 0 初始化。用 0 初始化会让
 *     每个滤波器在启动阶段被拉向 0（即黑），表现为**每行最右侧几个像素偏暗**。
 *     前向那一遍同理（否则每行最左侧偏暗）—— 这一点 robot36 只在反向提到，但方向对称，
 *     两遍都必须预热；
 *  2. **像素采样用线性插值**，不用「每 N 个样本取一个」。48000 Hz 下每像素 37.5 个样本，
 *     不是整数倍，取整采样会带来最多半个样本（约 0.013 像素）的抖动并丢掉一部分可用
 *     信息；插值几乎不增加成本且严格更好；
 *  3. **频偏由启动音自动估计**（见 [frequencyOffsetHz]）。robot36 在归一化单位下工作，
 *     频偏由调用方负责；这里让「启动音与图像走同一条 SSB 链路、整体调谐误差相同」这一
 *     事实直接变成一次标定，与 SSTV 用 VIS leader 反推 `hedr_shift` 是同一思路；
 *  4. 亮度映射在 **Hz** 域完成（`(f − offset − 1500) / 800`），而不是 robot36 的
 *     `0.5 * (f − offset + 1)` 归一化写法 —— 本项目的 [SstvDemodulator] 直接输出 Hz，
 *     再做一次单位变换只会多一处出错的地方。
 *
 * ## 调用方必须注意：解调器的相位差分间隔不能太大
 *
 * 300 Hz 启动音距解调中心频率（1900 Hz）有 **1600 Hz**，而相位差分的无模糊范围是
 * `±sampleRate / (2 · phaseSpan)`。一旦 1600 Hz 落到范围之外，相位解缠就会把 300 Hz
 * **折叠**成假频率，启动音直接检测不到。实测（48 kHz）：
 * ```
 * span = 8  → 300 Hz 读数 257.9 – 348.7 Hz，均值 300.000 Hz    ✓ 正常
 * span = 15 → 300 Hz 折叠成 300 – 3497 Hz，均值 1940 Hz        ✗ 数学边界（范围恰为 ±1600）
 * span = 16 → 折叠成约 3300 Hz                                 ✗ 彻底失效
 * ```
 * 数学上要求 `phaseSpan ≤ 14`，但边界附近必然折叠，因此**推荐 `phaseSpan = 8`**
 * （无模糊范围 ±3000 Hz，留近 2 倍余量）。这一条在 `HfFaxDecoderTest` 里有专门的回归
 * 用例（对比 span=8 与 span=16）。注意这**不是**本解码器的限制，而是上游解调器参数
 * 选择的约束；图像本身（1500–2300 Hz）只在中心附近，任何 span 都能正常解出。
 *
 * ## 已实测的另一个坑：启动音的瞬时值会抖，但均值很准
 *
 * 300 Hz 远在解调器 1200 Hz 基带之外（混频后落在 −1600 Hz），而混频的**镜像分量**
 * （+2200 Hz 附近）只被 8 阶低通压到约 −42 dB，相对有用分量并不够低，于是解调输出
 * 出现幅度可观的周期性抖动。48 kHz 实测：
 * ```
 * 300 Hz → 瞬时读数 257.9 – 348.7 Hz（±45 Hz），均值 300.000 Hz
 * 350 Hz → 瞬时读数 317.6 – 385.6 Hz（±34 Hz），均值 349.992 Hz
 * ```
 * 由此得出两条设计结论（都不是「顺手加的容差」，而是被实测逼出来的）：
 *  1. **不能用「瞬时值连续落在 ±容差内」判启动音** —— 失谐 +50 Hz 的素材读数会周期性
 *     冲出 ±50 的边界，连续 2 秒的要求永远凑不满（这正是最初的实现踩的坑）；
 *     改为「瞬时值落在**加宽**的门内累计时长，再要求**段内均值**落在 ±容差内」，
 *     因为均值误差实测 < 0.01 Hz；
 *  2. 自动频偏估计用均值，精度因此远高于瞬时值，不受这个抖动影响。
 *
 * ## 内存
 *
 * 像素缓冲一次性按 [maxLines] 分配（640 × 1200 × 4 B ≈ 3 MB），不随已解出行数增长；
 * 到达上限后不再增长也不再做滤波（图像冻结在最后一帧），见 [maxLines]。
 *
 * 纯 Kotlin 实现，不依赖任何 Android API，可直接在 JVM 单测中驱动。
 *
 * @param sampleRate 采样率（Hz），必须与上游解调器一致
 * @param maxLines 行数上限，默认 [HfFaxSpec.DEFAULT_MAX_LINES]
 * @param startToneSeconds 判定启动音所需的最短连续时长（秒）
 * @param startToneToleranceHz 启动音**段内均值**相对 300 Hz 的允许偏差（Hz）。它同时是
 *   「调谐误差」的允许范围；瞬时读数另有 80 Hz 的加宽门（常量 `MIRROR_JITTER_HZ`，
 *   原因见类 KDoc）。若素材的调谐误差超出此值，请改用 [frequencyOffsetHz] 显式指定
 */
class HfFaxDecoder(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    val maxLines: Int = HfFaxSpec.DEFAULT_MAX_LINES,
    private val startToneSeconds: Double = HfFaxSpec.DEFAULT_START_TONE_SECONDS,
    private val startToneToleranceHz: Double = HfFaxSpec.DEFAULT_START_TONE_TOLERANCE_HZ,
) {

    init {
        require(sampleRate > 0) { "采样率必须为正，实际为 $sampleRate" }
        require(maxLines > 0) { "行数上限必须为正，实际为 $maxLines" }
        require(startToneToleranceHz > 0.0) { "启动音容差必须为正，实际为 $startToneToleranceHz" }
    }

    /** 行宽（像素），恒为 [HfFaxSpec.WIDTH]。 */
    val width: Int = HfFaxSpec.WIDTH

    /** 每行的样本数（48000 Hz 下为 24000）。 */
    val lineSamples: Int = (HfFaxSpec.LINE_SECONDS * sampleRate).roundToInt()

    /** 已解出的行数。 */
    var decodedLines: Int = 0
        private set

    /**
     * 是否仍在等待启动音（也就是还没有图像行产出）。
     *
     * 为 true 时 [feed] 只会推进启动音检测状态机，不产生任何图像行。
     * 从录音中间开始播放、或信号里根本没有启动音时，它会一直是 true ——
     * 这时调用 [startImmediately] 强制开始（取舍见该方法说明）。
     */
    val isWaitingForStartTone: Boolean
        get() = state != State.DECODING

    /** 是否已经确认检测到启动音（正在等它结束）。用于 UI 区分「找音中」与「等图像开始」。 */
    val isStartToneDetected: Boolean
        get() = state == State.IN_START_TONE

    /**
     * 手动频率偏移（Hz）。**默认 `null` 表示由启动音自动估计**。
     *
     * 正值表示收到的所有音调都比标称高（SSB 调谐偏高、或接近的多普勒）。
     * 自动估计的依据是：启动音与图像由同一台发射机、经同一条链路传达，整体调谐误差
     * 对两者的影响完全相同。检测到启动音后，用其实测频率与 300 Hz 之差作为偏移。
     *
     * **未检测到启动音时必须显式给出**（例如从中间开始录的素材）：否则 1500/2300 Hz 的
     * 黑白基准会整体偏移，图像表现为一片死白或死黑。若调用方显式赋值，则不会再被自动
     * 估计覆盖。
     */
    var frequencyOffsetHz: Double? = null

    /** 由启动音实测得到的偏移（Hz）；未检测到启动音时为 null。 */
    var measuredOffsetHz: Double? = null
        private set

    /** 图像缓冲，行优先灰度，按 [maxLines] 一次性分配。 */
    private val rows = IntArray(width * maxLines)

    /** 当前行的原始亮度（未经低通），复用以避免每行分配 96 KB。 */
    private val lineLevels = FloatArray(lineSamples)

    /** 当前行已填充的样本数。 */
    private var lineFill = 0

    /** 逐列平均亮度（0–1），跨行以 [HfFaxSpec.COLUMN_DECAY] 衰减。 */
    private val columnAverage = DoubleArray(width)

    /** 已参与列统计的行数；为 0 表示还没法对齐。 */
    private var columnStatLines = 0

    /** 上一次有效的频率值，用于「非有限值沿用上一次」的夹紧策略。 */
    private var lastValidHz = HfFaxSpec.BLACK_HZ

    /** 启动音连跑计数与频率累加（用于估计频偏）。 */
    private var toneRun = 0
    private var toneSum = 0.0
    private val minToneSamples = (startToneSeconds * sampleRate).roundToInt().coerceAtLeast(1)

    private var state = State.WAITING_START_TONE

    private enum class State {
        /** 还没找到启动音。 */
        WAITING_START_TONE,

        /** 已确认在启动音里，等它结束。 */
        IN_START_TONE,

        /** 正在出图。 */
        DECODING,
    }

    /**
     * 零相位像素 Nyquist 低通的 EMA 系数（所有行共用）。
     *
     * **为什么截止频率恰好等于「像素数」**：像素周期 = `lineSamples / width` 个样本，
     * 其 Nyquist 频率 = `0.5 · width / lineSamples · sampleRate` 样本/秒。把
     * `lineSamples = LINE_SECONDS · sampleRate` 代入，得到 `width / (2 · LINE_SECONDS)`
     * = 640 Hz —— 与采样率无关。这正是 robot36 里
     * `cutoff(horizontalPixels, 2 * scanLineSamples, order)` 的等价写法（其第二参数
     * `2 * scanLineSamples` 就是采样率）。
     *
     * 阶数取 2 会级联两次一阶 EMA，**双向跑一遍**后等效阶数翻倍，实际 −3 dB 点因此
     * 略低于设计值 —— robot36 也是如此，这不是缺陷：像素 Nyquist 本身已经比真实需要的
     * 带宽（IOC 576 只有 576 像素/行）宽，略微收紧反而抑制了传真信号的边缘振铃。
     */
    private val filterAlpha = emaAlpha(
        cutoffHz = width / (2.0 * HfFaxSpec.LINE_SECONDS),
        rate = sampleRate,
        order = HfFaxSpec.FILTER_ORDER,
    )

    /**
     * 送入一段逐样本瞬时频率（Hz），解码出尽可能多的完整行。
     *
     * 状态跨调用连续：不足一行的尾巴留在内部缓冲等下次 [feed]，因此可以被任意分块
     * （例如音频采集每 100 ms 一块）。返回值是**本次新增的行数**。
     *
     * @param freqs 逐样本瞬时频率（来自 [SstvDemodulator]）
     * @param fromIndex 从该下标开始处理
     */
    fun feed(freqs: FloatArray, fromIndex: Int = 0): Int {
        var produced = 0
        var i = fromIndex.coerceAtLeast(0)
        while (i < freqs.size) {
            val hz = sanitize(freqs[i])
            when (state) {
                State.WAITING_START_TONE -> {
                    if (isInStartToneBand(hz)) {
                        toneRun++
                        toneSum += hz
                        // 两个条件都满足才算确认：时长够，且**段内均值**落在容差内
                        if (toneRun >= minToneSamples && isRunMeanWithinTolerance()) {
                            state = State.IN_START_TONE
                        }
                    } else {
                        // 一旦离开（加宽的）门就清零：要求的是**连续**时长，不是累计时长
                        resetToneRun()
                    }
                }

                State.IN_START_TONE -> {
                    if (isInStartToneBand(hz)) {
                        toneRun++
                        toneSum += hz
                    } else {
                        // 启动音在此结束 → 图像从**本样本**开始，行时钟对齐到这里。
                        // 不 i++ 地重入循环，让本样本按图像样本处理（它是图像的第一个样本，
                        // 不是启动音的一部分）
                        measuredOffsetHz = toneMeanHz() - HfFaxSpec.START_TONE_HZ
                        state = State.DECODING
                        lineFill = 0
                        continue
                    }
                }

                State.DECODING -> {
                    // 到达上限后什么都不做：既不再滤波也不再统计（图像冻结），
                    // 但继续消费样本，保持状态机行为与「上限无关」
                    if (decodedLines >= maxLines) return produced
                    lineLevels[lineFill++] = rawLevel(hz).toFloat()
                    if (lineFill >= lineSamples) {
                        if (emitLine()) produced++
                        lineFill = 0
                    }
                }
            }
            i++
        }
        return produced
    }

    /**
     * 不等启动音，立刻按当前样本位置开始出图。
     *
     * **取舍**：没有启动音就没有绝对时间基准，行时钟只能从「调用时刻」开始自由运行，
     * 因此整幅图像在垂直方向可能有任意偏移（最多半行），且 [measuredOffsetHz] 保持为
     * null —— 黑白基准只能靠调用方显式设置 [frequencyOffsetHz]。
     * 这仍然比「什么都不出」有用：从中间开始的录音本来就没有可对齐的东西，
     * 用户要的是把内容解出来看，而不是等着一个永远不会来的锚点。
     *
     * 只把**尚未送入**的样本当作图像起点；调用前已消费的样本不回放（不保留历史音频，
     * 否则内存会随接收时长线性增长）。
     */
    fun startImmediately() {
        if (state == State.DECODING) return
        state = State.DECODING
        lineFill = 0
        resetToneRun()
    }

    /**
     * 取当前图像（供 UI 渐进显示）。
     *
     * 每次调用都会按最新列统计重新计算对齐并复制整幅图像，复杂度 `O(width × height)`；
     * UI 应以数秒为间隔调用（例如与 SSTV 侧的部分解码同频），不要每行都调。
     */
    fun snapshot(): HfFaxImage {
        val height = decodedLines
        val shift = computeShift()
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                out[base + x] = rows[base + (x + shift) % width]
            }
        }
        return HfFaxImage(
            width = width,
            height = height,
            pixels = out,
            aligned = columnStatLines > 0,
            shiftPixels = shift,
        )
    }

    /** 清空全部状态，准备解码下一段信号（含重新等待启动音）。 */
    fun reset() {
        decodedLines = 0
        lineFill = 0
        columnStatLines = 0
        columnAverage.fill(0.0)
        rows.fill(0)
        lastValidHz = HfFaxSpec.BLACK_HZ
        resetToneRun()
        measuredOffsetHz = null
        state = State.WAITING_START_TONE
    }

    // ══════════════════════════════════════════════════════════════════
    // 内部实现
    // ══════════════════════════════════════════════════════════════════

    /** 当前生效的频率偏移：手动优先，其次启动音实测，最后退回 0。 */
    private val effectiveOffsetHz: Double
        get() = frequencyOffsetHz ?: measuredOffsetHz ?: 0.0

    /**
     * 夹紧上游来的频率样本。
     *
     * `NaN`/`Inf` 表示这一样本不可信，**沿用上一次有效值**而不是当作黑电平：
     * 塞一个黑点进去就是往图像里画一个暗像素，而丢弃相位信息的样本本来就不携带
     * 任何亮度信息，保持连续更接近真实内容。越界（例如解调瞬态期的野值）交由
     * [rawLevel] 夹紧。
     */
    private fun sanitize(freq: Float): Double {
        val v = freq.toDouble()
        if (!v.isFinite()) return lastValidHz
        lastValidHz = v
        return v
    }

    /**
     * 瞬时值是否落在**加宽**后的启动音门内。
     *
     * 门宽 = 容差 + 80 Hz（常量 `MIRROR_JITTER_HZ`）。加宽不是「随便留的余量」，而是被实测逼出来的：
     * 300 Hz 的瞬时读数实测在 257.9–348.7 Hz 之间摆动（镜像分量残留），失谐 +50 Hz 的
     * 素材（350 Hz）则摆到 317.6–385.6 Hz —— 若门宽只有 ±50，350 Hz 的读数会周期性冲出
     * 边界，把「连续 2 秒」的要求永远打断。
     *
     * 因此这层门只负责「累计一段低频纯音」，**是否真是 300 Hz 启动音由段内均值判定**
     * （见 [isRunMeanWithinTolerance]）—— 均值的实测误差 < 0.01 Hz。
     */
    private fun isInStartToneBand(hz: Double): Boolean {
        val target = HfFaxSpec.START_TONE_HZ + (frequencyOffsetHz ?: 0.0)
        return abs(hz - target) <= startToneToleranceHz + MIRROR_JITTER_HZ + TONE_TOLERANCE_EPSILON
    }

    /**
     * 已累计段的**均值**是否落在容差内（容差带随手动 [frequencyOffsetHz] 平移）。
     *
     * 留 [TONE_TOLERANCE_EPSILON] 的浮点余量：容差边界有实际含义（正好失谐 +50 Hz 的素材
     * 就落在 ±50 的边界上），若因浮点舍入在边界上判否，整段启动音就都找不到了。
     */
    private fun isRunMeanWithinTolerance(): Boolean {
        if (toneRun <= 0) return false
        val target = HfFaxSpec.START_TONE_HZ + (frequencyOffsetHz ?: 0.0)
        return abs(toneMeanHz() - target) <= startToneToleranceHz + TONE_TOLERANCE_EPSILON
    }

    /** 已累计段内频率的均值（Hz）。 */
    private fun toneMeanHz(): Double = if (toneRun > 0) toneSum / toneRun else 0.0

    /** 清空启动音累计段。 */
    private fun resetToneRun() {
        toneRun = 0
        toneSum = 0.0
    }

    /**
     * 频率 → 原始亮度（0–1，未夹到有效区间）。
     *
     * 夹紧范围取 [RAW_LEVEL_MIN]..[RAW_LEVEL_MAX] 而不是 0..1：真实有效范围就是 0..1，
     * 但解调瞬态期、静音段、噪声都可能给出 3000 Hz 以上的野值（对应亮度 2+），
     * 若不夹紧，一个野值经过低通后会污染好几个像素。留一点余量是为了不影响
     * 正常内容的边缘（边缘处经过低通本来就允许略微超出）。
     */
    private fun rawLevel(hz: Double): Double {
        val level = (hz - effectiveOffsetHz - HfFaxSpec.BLACK_HZ) /
            (HfFaxSpec.WHITE_HZ - HfFaxSpec.BLACK_HZ)
        return level.coerceIn(RAW_LEVEL_MIN, RAW_LEVEL_MAX)
    }

    /**
     * 把当前行处理成像素并写入图像缓冲。
     *
     * @return 是否真的写入了一行（到达行数上限后返回 false）
     */
    private fun emitLine(): Boolean {
        if (decodedLines >= maxLines) return false

        // ① 零相位像素 Nyquist 低通（就地）
        zeroPhaseLowPassInPlace(lineLevels, lineSamples, filterAlpha)

        // ② 在像素中心采样 → 灰度，顺便累积列亮度统计
        val step = lineSamples.toDouble() / width
        val base = decodedLines * width
        val isFirstStatLine = columnStatLines == 0
        for (x in 0 until width) {
            val center = (x + 0.5) * step
            val level = sampleLinear(lineLevels, lineSamples, center).coerceIn(0.0, 1.0)
            rows[base + x] = (level * 255.0).roundToInt()
            columnAverage[x] = if (isFirstStatLine) {
                // 首行直接赋值：若沿用「从 0 起步」的卷积，前 ~100 行里所有列都被同一
                // 个 0 基线压住，对齐要等统计量爬上去才能用；直接赋值让第 1 行就可对齐
                level
            } else {
                columnAverage[x] * HfFaxSpec.COLUMN_DECAY + level * (1.0 - HfFaxSpec.COLUMN_DECAY)
            }
        }
        columnStatLines++
        decodedLines++
        return true
    }

    /**
     * 最亮列的下标，即需要循环移位的列数。
     *
     * 语义核对：`snapshot()` 里 `out[x] = rows[(x + shift) % width]`，取
     * `shift = 最亮列` 时 `out[0]` 正是最亮的那一列 —— 也就是把白边中心搬到 x = 0。
     */
    private fun computeShift(): Int {
        if (columnStatLines <= 0) return 0
        var best = 0
        for (x in 1 until width) {
            if (columnAverage[x] > columnAverage[best]) best = x
        }
        return best
    }

    /**
     * 零相位（双向）指数滑动平均低通，就地覆盖 [buffer] 的前 [count] 个元素。
     *
     * 前向跑一遍、再反向跑一遍即可抵消一阶滤波器的相位滞后 —— 这正是「零相位」的含义，
     * 也是它能用来做**水平对齐前**的像素成形的原因：单向滤波会把所有边缘整体右移，
     * 让白边定位平白多出一段偏移。
     *
     * 两遍都**用各自的首个样本预热**滤波器，理由见类 KDoc 第 1 条。
     */
    private fun zeroPhaseLowPassInPlace(buffer: FloatArray, count: Int, alpha: Double) {
        if (count <= 0 || alpha >= 1.0) return
        val forward = ExponentialMovingAverage(alpha)
        for (i in 0 until count) {
            buffer[i] = forward.average(buffer[i].toDouble()).toFloat()
        }
        val backward = ExponentialMovingAverage(alpha)
        for (i in count - 1 downTo 0) {
            // 就地读写同一个元素是安全的：每个下标只被读取一次（读的是前向结果）
            buffer[i] = backward.average(buffer[i].toDouble()).toFloat()
        }
    }

    /** 在 [center]（样本坐标，可为小数）处线性插值取值。 */
    private fun sampleLinear(buffer: FloatArray, count: Int, center: Double): Double {
        if (count <= 1) return if (count == 1) buffer[0].toDouble() else 0.0
        val clamped = center.coerceIn(0.0, (count - 1).toDouble())
        val index = floor(clamped).toInt().coerceIn(0, count - 1)
        val frac = clamped - index
        val a = buffer[index].toDouble()
        val b = buffer[(index + 1).coerceAtMost(count - 1)].toDouble()
        return a + (b - a) * frac
    }

    private companion object {
        /**
         * 原始亮度的夹紧范围。
         *
         * 有效范围是 0..1；给到 −1..2 是为了让「接近黑白边界的内容」在低通后仍能保留
         * 真实的过冲量，同时挡住解调野值（例如 4000 Hz → 亮度 3.1）。
         */
        const val RAW_LEVEL_MIN = -1.0
        const val RAW_LEVEL_MAX = 2.0

        /**
         * 启动音瞬时读数的镜像抖动余量（Hz），加在 [startToneToleranceHz] 之上组成门宽。
         *
         * 取自 48 kHz 实测：300 Hz 摆幅 ±45 Hz、350 Hz 摆幅 ±34 Hz、400 Hz 摆幅 ±25 Hz
         * （见类 KDoc）。取 80 是为了给容差带边缘（例如 250 Hz，抖幅更大）留余量。
         */
        const val MIRROR_JITTER_HZ = 80.0

        /** 容差比较的浮点余量（见 [isRunMeanWithinTolerance]）。 */
        const val TONE_TOLERANCE_EPSILON = 1e-6
    }
}

/**
 * 一阶指数滑动平均（EMA）低通。
 *
 * 差分方程与 robot36 的 `ExponentialMovingAverage` 一致：
 * `value = value * (1 − alpha) + alpha * x`，等价于这里的 `value += alpha * (x − value)`。
 *
 * 刻意支持「未预热」状态：首个样本直接作为初值，而不是从 0 开始 —— 从 0 起步会让
 * 滤波器在启动阶段被拉向 0（黑），这正是每行左右两端变暗的根因。
 */
private class ExponentialMovingAverage(private val alpha: Double) {
    private var value = 0.0
    private var primed = false

    /** 送入一个样本，返回滤波后的值（首样本自动预热）。 */
    fun average(x: Double): Double {
        if (!primed) {
            value = x
            primed = true
        } else {
            value += alpha * (x - value)
        }
        return value
    }
}

/**
 * 由截止频率反解一阶 EMA 系数（robot36 `cutoff()` 的等价式）。
 *
 * 推导：一阶 EMA 的 −3 dB 截止满足 `alpha = cos ω − 1 + √(cos²ω − 4cos ω + 3)`，
 * 其中 `ω = 2π·freq/rate`。级联 `order` 级时每级取 `alpha^(1/order)` 以保持总截止不变。
 *
 * 该近似只在 `freq ≪ rate` 时准确（这里 640 Hz vs 48000 Hz，完全满足）；结果夹到
 * `[0, 1]`，避免极端参数下出现非物理的负系数。
 */
private fun emaAlpha(cutoffHz: Double, rate: Int, order: Int): Double {
    require(order >= 1) { "阶数必须 ≥ 1，实际为 $order" }
    val x = cos(2.0 * PI * cutoffHz / rate)
    val discriminant = x * (x - 4.0) + 3.0
    // 判别式理论上是 (x−1)(x−3) ≥ 0（x ≤ 1），浮点舍入可能给出极小负数
    val alpha = x - 1.0 + sqrt(discriminant.coerceAtLeast(0.0))
    return alpha.coerceIn(0.0, 1.0).pow(1.0 / order)
}
