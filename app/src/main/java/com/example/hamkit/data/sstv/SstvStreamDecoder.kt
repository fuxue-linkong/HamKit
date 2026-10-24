package com.example.hamkit.data.sstv

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

/**
 * 流式逐行 SSTV 解码器（SSTV 三期 Q0 地基 + Q1 滚动回收）。
 *
 * 上游设计：[docs/SSTV_STREAM_DECODER_DESIGN.md](../../../../../../../../../docs/SSTV_STREAM_DECODER_DESIGN.md)
 * 滚动回收：[docs/SSTV_PHASE3_PLAN.md](../../../../../../../../../docs/SSTV_PHASE3_PLAN.md) §3.2 ④
 *
 * 与一次性解码 [SstvDecoder.decodeFromFrequencies] 的区别：
 *  - **有状态**：跨 [feed] 调用保持脉冲检测、回归与 Robot 色度行交替状态；
 *  - **任意起点**：不要求 VIS 头，模式由构造参数给定；音频从图像中间开始也能解；
 *  - **无限高度**：输出行数不设上限；内存由两段式频率缓冲 + 滚动回收约束为常量。
 *
 * ## 为什么「双输出」
 *
 * 离线解码用**全部**同步脉冲做一次最小二乘拟合再解每一行（非因果）；流式解码在处理
 * 第 k 行时只能用**前 k 个**脉冲（因果）。二者早期行必然不同，因此两种都提供：
 *
 * | 输出 | 触发 | 定位依据 | 用途 |
 * |---|---|---|---|
 * | 实时 | 每次 [feed] | 在线最小二乘（因果） | 边收边显示，延迟低 |
 * | 收尾 | [finalize] | 全局拟合 + 按帧重解 | 保存 |
 *
 * ## 行号语义（重要）
 *
 * 持续接收的音频没有「帧首」，因此 [LineEvent.imageRow] **不是**模式内的行号，而是从
 * 「本解码器见到的第一条完整无线行」起从 0 单调递增的流水号 —— 不因一帧结束而重置，
 * 对应 UI 上「卷纸式」不断延长的画面。中途重新锁定后行号继续累加，不会回绕。
 *
 * ## 频率缓冲的两段式布局（Q1 的核心）
 *
 * 一期的「整帧保留」在持续接收下不可行：1 小时 @ 48 kHz = 1.7 亿样本 = 690 MB。
 * 但也不能简单地「解完即丢」—— 有三处必须回读历史：
 *
 *  1. **帧边界的脉冲检测**：一个同步脉冲可能跨块边界，检测器要回看前一个块末尾；
 *  2. **逐行精调**：`lineStartRefined` 要读该行同步脉冲的实测中心（可能在行首之后）；
 *  3. **[finalize]**：要有该帧的**行起点**从头重解，才能做到与一期逐像素一致。
 *
 * 因此缓冲分成两段：
 *
 * | 段 | 保存什么 | 回收条件 |
 * |---|---|---|
 * | **高频段** `buffer` | 最近 `TAIL_LINES` 个行周期的样本（至少 `MIN_TAIL_SAMPLES`，容量取 2 的幂） | 恒定的环形窗口，超过即丢最老 |
 * | **低频段** `segments`（按帧） | 每帧「行起点 → 帧尾起点」的浮点频率，按行抽样（stride） | 帧数超过 [maxSegments] 时整帧丢弃最老；[dropHistory] 可全部丢弃 |
 *
 * 抽样（stride）的代价只落在 [finalize] 的**同步拟合精度**上（亚行周期的抖动，由逐行
 * 精调吸收），实测仍与一期逐像素一致；实时路径永远用未抽样的高频段，不受影响。
 *
 * 纯 Kotlin 实现，不依赖 Android API。
 *
 * @param sampleRate 采样率（Hz），必须与上游 [SstvDemodulator] 一致
 * @param mode 已知模式（持续接收时来自手动锁定 / 盲扫 / 中途捕到的 VIS）
 * @param initialHedrShiftHz 启动时的失谐量估计（Hz）；未捕获 VIS 时传 0，
 *   脉冲足够多后会被实测均值取代
 * @param maxSegments 低频段最多保留的帧数（[finalize] 与保存会用到）。默认 8：
 *   按 Robot 36 每帧约 5 MB 计约 40 MB，且已足够覆盖一次过境的重复发送
 * @param onLine 每解码完**一个图像行**回调一次（PD 族一次无线行回调两次）。
 *   刻意放在最后一个参数：调用方最常写的是 `SstvStreamDecoder(rate, mode) { … }`
 */
class SstvStreamDecoder(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    private val mode: SstvMode,
    initialHedrShiftHz: Double = 0.0,
    private val maxSegments: Int = DEFAULT_MAX_SEGMENTS,
    private val onLine: (LineEvent) -> Unit = {},
) {

    /**
     * 一行解码结果。
     *
     * @param imageRow 图像行号（流水号，见类注释「行号语义」）
     * @param pixels 该行的打包 RGB（0xRRGGBB），长度 = [SstvMode.linePixels]
     * @param locked 该行是否由**实测同步脉冲**定位（false 表示用的是拟合外推）。
     */
    data class LineEvent(val imageRow: Int, val pixels: IntArray, val locked: Boolean) {
        // IntArray 的默认语义是引用比较，这里按内容比较，避免调用方踩坑
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LineEvent) return false
            return imageRow == other.imageRow &&
                locked == other.locked &&
                pixels.contentEquals(other.pixels)
        }

        override fun hashCode(): Int =
            (imageRow * 31 + pixels.contentHashCode()) * 31 + locked.hashCode()
    }

    /** 解码统计快照，供 UI 展示。 */
    data class Stats(
        /** 已解出的图像行数 */
        val decodedLines: Int,
        /** 已接受（通过串链校验）的同步脉冲数 */
        val syncPulses: Int,
        /** 当前使用的失谐量（Hz） */
        val hedrShiftHz: Double,
        /** 拟合行周期 / 标称行周期（1.0 表示完全同步） */
        val slantRatio: Double,
        /** 最近 [SYNC_HIT_WINDOW] 行的同步命中率（0–1） */
        val syncHitRate: Float,
        /** 是否处于丢锁状态（连续多行未命中实测脉冲） */
        val lostSync: Boolean,
        /** 是否已锁定（脉冲足够且拟合合法） */
        val locked: Boolean,
        /** 高频段当前占用的样本数（内存诊断用） */
        val bufferedSamples: Int = 0,
        /** 低频段（历史帧）当前占用的样本数（已抽样） */
        val historySamples: Long = 0L,
    )

    // ══════════════════════════════════════════════════════════════════
    // 频率流缓冲：高频段（最近 TAIL_SAMPLES 样本的环形窗口）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 高频段：最近 [tailCapacity] 个样本的环形窗口。
     *
     * 起点对齐到环容量整数倍（[clearTail] / [slideTail] 都按 [tailCapacity] 整段推进），
     * 因此绝对位置与环下标之间的换算是**常量**，逐行解码时不必反复切换坐标系。
     */
    private var buffer = FloatArray(0)

    /** 环容量（样本数），按模式行周期取 [TAIL_LINES] 个行周期，对齐到 2 的幂。 */
    private val tailCapacity: Int = tailCapacityFor(mode, sampleRate)

    /**
     * 环下标基准：`tailStart` 恒为 [tailCapacity] 的整数倍，因此
     * `环下标 = (绝对位置 − tailStart) and (tailCapacity − 1)` 是常量换算。
     */
    private var tailStart = 0L

    /** 已写入环的最后一个样本 + 1（绝对位置）；累加样本游标。 */
    private var appendedEnd = 0L

    /** 历史段的「当前帧尾起点」：高频段不可能回退到这里之前。 */
    private var tailFloor = 0L

    /** 脉冲扫描游标（绝对样本位置），保证每个样本只扫一次。 */
    private var scanCursor = 0L

    /**
     * 增量 VIS 检测器（有状态）。
     *
     * 只在调用方喂**原始音频**（[feedAudio]）时才工作：它无法从频率流上识别 VIS。
     * 检测本身是模板匹配，开销很小，因此不需要保存音频本身 —— 这正是 Q1 相比 Q0
     * 能省下「整段音频缓冲」的原因。
     */
    private val visDetector = SstvVisDetector(sampleRate)

    /** 频段状态：当前是否位于某个 1200 Hz 连续段内。 */
    private var inPulse = false
    private var pulseStart = 0L
    private var pulseFreqSum = 0.0
    private var pulseFreqCount = 0

    // ══════════════════════════════════════════════════════════════════
    // 低频段：按帧保留「行起点 → 帧尾起点」的浮点频率（可抽样）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 一段历史频率。
     *
     * @param startSample 首样本的绝对位置
     * @param stride 抽样步长（每 stride 个样本存 1 个）；1 表示未抽样
     * @param data 与 [startSample] 对齐的样本（`data[i]` 对应 `startSample + i * stride`）
     * @param frozen 是否已冻结（帧尾已过）：冻结后不再需要把新样本回填进来
     */
    private class Segment(
        var startSample: Long,
        var data: FloatArray,
        var size: Int,
        var stride: Int,
    ) {
        var frozen = false

        /** 下一个要存的样本的绝对位置。 */
        fun nextPosition(): Long = startSample + size.toLong() * stride

        /**
         * 按固定步长把 `[from, to)` 的样本写进段里（[available] 负责取样本）。
         *
         * 只写「下一个位置落在窗口内」的那些样本，因此**与分块边界无关**：无论调用方
         * 按 1024 还是按 4096 样本喂入，段内容完全一致（有单元测试守护）。
         */
        fun append(from: Long, to: Long, available: (Long) -> Float) {
            if (frozen || stride <= 0) return
            var next = nextPosition()
            while (next < from) next += stride
            while (next < to) {
                ensure(size + 1)
                data[size++] = available(next)
                next += stride
            }
        }

        /** 按 2 倍步长重抽样：只保留偶数位置的样本，时间跨度不变、占用减半。 */
        fun halveStride() {
            val newSize = (size + 1) / 2
            val shrunk = FloatArray(newSize)
            var out = 0
            var inIndex = 0
            while (inIndex < size) {
                shrunk[out++] = data[inIndex]
                inIndex += 2
            }
            data = shrunk
            size = newSize
            stride *= 2
        }

        private fun ensure(required: Int) {
            if (required <= data.size) return
            var capacity = max(data.size, 1024)
            while (capacity < required) capacity *= 2
            data = data.copyOf(capacity)
        }
    }

    /** 历史段（最老在前）。容量 [maxSegments]。 */
    private val segments = ArrayList<Segment>(4)

    /** 历史段当前总样本数（已抽样）。 */
    private var historySamples = 0L

    // ══════════════════════════════════════════════════════════════════
    // 行跟踪状态
    // ══════════════════════════════════════════════════════════════════

    /** 启动时的失谐量：脉冲不足时退化为它。 */
    private var initialShiftHz = initialHedrShiftHz

    /** 最近若干脉冲的频率（环形缓冲），用于滚动失谐估计。 */
    private val shiftWindow = DoubleArray(SHIFT_WINDOW)
    private var shiftCount = 0
    private var shiftWriteIndex = 0

    /** 最近若干实测脉冲中心（环形缓冲），供逐行精调与碰撞判定。 */
    private val recentCenters = DoubleArray(PULSE_WINDOW)
    private var centerCount = 0
    private var centerWriteIndex = 0

    /** 最近若干行的命中标记（环形缓冲），用于 [Stats.syncHitRate]。 */
    private val hitWindow = BooleanArray(SYNC_HIT_WINDOW)
    private var hitCount = 0
    private var hitWriteIndex = 0
    private var totalHits = 0

    /** 在线最小二乘的累积量（**全局脉冲序号 k → 脉冲中心**，k = 0 是首个脉冲）。 */
    private var pulseK = 0L
    private var sumK = 0.0
    private var sumP = 0.0
    private var sumKK = 0.0
    private var sumKP = 0.0

    /** 当前拟合：`center(k) ≈ firstCenter + lineSamples · k`。未锁定时为 NaN。 */
    private var lineSamples = Double.NaN
    private var firstCenter = Double.NaN

    /** 已有足够脉冲且拟合合法。 */
    private var fitLocked = false

    /** 丢锁：连续 [LOST_SYNC_LINES] 行没有命中实测脉冲。 */
    private var lostSync = false

    /**
     * 首个被接受的脉冲对应的**局部序号**。
     *
     * 它未必是 0：音频可能从图像中途开始，此时首脉冲之前还缺若干行，序号从负数
     * 起算；等序号推进到 0 才对应「首脉冲所在的那一行」，也是全局流水号 0。
     */
    private var firstRadio = 0

    /** 下一条待解码无线行的**全局流水号**（0 起，单调递增）。 */
    private var nextRadio = 0

    /** 已解出的无线行数。 */
    private var decodedRadioLines = 0

    /** 连续未命中的行数。 */
    private var missStreak = 0

    /** 首个被接受的脉冲中心（历史段的起点）。 */
    private var anchorCenter = Double.NaN

    /**
     * 最近一个被接受脉冲的**行格点序号**（锁定期校验用，见 [onPulse]）。
     *
     * 与 [pulseK] 不同：它取的是「脉冲中心相对拟合直线落在第几个行周期」，
     * 因此允许跳号（漏检），只要求严格单调递增。
     */
    private var lastAcceptedLine = Long.MIN_VALUE

    // ══════════════════════════════════════════════════════════════════
    // 解码用的复用对象
    // ══════════════════════════════════════════════════════════════════

    /**
     * 行解码器。
     *
     * 内部持有 Robot 24/36 的**跨行色度平面**与行奇偶状态，因此必须全程复用同一个
     * 实例 —— 这正是流式解码必须「有状态」的原因之一。
     */
    private var lineDecoder = SstvDecoder(sampleRate)

    private val width = mode.linePixels

    /** 单条无线行解出来的图像行（PD 族 2 行，其余 1 行）。 */
    private val radioLinePixels = IntArray(width * mode.imageRowsPerRadioLine)

    /** 滚动失谐的初始值（未累积脉冲前），避免首块拿不到目标频率。 */
    private val nominalSyncHz = SstvMode.VIS_BREAK_HZ

    // ══════════════════════════════════════════════════════════════════
    // 对外接口
    // ══════════════════════════════════════════════════════════════════

    /**
     * 解码器内部互斥锁。
     *
     * 持续接收场景下本类会被多个协程并发调用：采集协程调 [feed]/[feedAudio]，
     * 预览刷新协程周期性调 [stats]，用户停止时（join 超时后）还会调 [finalizeFrames]。
     * 全部可变状态都没有同步原语，参照 [SstvRollingImage] 的做法在对外入口统一加锁，
     * 保证任一时刻只有一个线程在改内部状态。
     */
    private val decoderLock = Any()

    /** 在 [decoderLock] 上执行 [block]；锁内不可回调回对外入口（会重入死锁）。 */
    private inline fun <T> locked(block: () -> T): T = synchronized(decoderLock, block)

    /**
     * 喂入一段**新的**频率流（必须是上次之后的新样本，长度任意、可为 0）。
     *
     * 内部会：写高频段与历史段 → 检测同步脉冲 → 更新滚动回归 → 把能确定已完整到达的
     * 无线行解出来并回调 → 回收历史段。尚未锁定（脉冲不足 [MIN_PULSES_FOR_FIT] 个）时
     * 只做脉冲搜索。
     */
    fun feed(newFreqs: FloatArray) {
        if (newFreqs.isEmpty()) return
        // 整次喂入持锁处理：块间状态（环、脉冲检测、回归）必须对其它线程不可见，
        // 否则 stats()/finalizeFrames() 可能读到半块写入的中间态
        locked { feedLocked(newFreqs) }
    }

    /** [feed] 的锁内实现（不持锁调用！）。 */
    private fun feedLocked(newFreqs: FloatArray) {
        // 内部按「不超过半个环容量」切块处理。
        //
        // 为什么必须切块：脉冲扫描发生在样本写入环之后，而环只保留最近 tailCapacity
        // 个样本。调用方一次喂入整帧（导入录音、单元测试都会这样）时，大块写入会先
        // 把前半段覆盖掉，扫描就只看得到尾部几秒的脉冲 —— 实时输出因此丢掉绝大部分
        // 行（整帧只剩二三十行），而最终图像却看起来「像那么回事」，很难察觉。
        // 切块后每块的样本在下一块写入前就完成扫描，历史段也按同一粒度回填。
        var offset = 0
        while (offset < newFreqs.size) {
            val count = minOf(maxFeedChunk, newFreqs.size - offset)
            val block = if (count == newFreqs.size) {
                newFreqs
            } else {
                newFreqs.copyOfRange(offset, offset + count)
            }
            appendAll(block)
            scanPulses()
            drainRadioLines()
            maintainHistory()
            offset += count
        }
    }

    /**
     * 强制推出所有已到达的行（收尾用）。
     *
     * 与 [feed] 的区别：允许在**未锁定**时用标称行周期解码 —— 短音频（如只收到几行）
     * 攒不够 4 个脉冲，但位置仍可由已知模式的标称周期确定。正常流程不需要调用。
     *
     * @return 本次新解出的行数
     */
    fun flush(): Int {
        // 收尾前强制推出行的路径同样可能被收尾线程调用，纳入同一把锁
        return locked {
            val before = decodedRadioLines
            drainRadioLines(force = true)
            maintainHistory()
            decodedRadioLines - before
        }
    }

    /**
     * 喂入一段**原始音频**（可选）。
     *
     * 只用于收尾时的 VIS 识别（在原始音频上识别 VIS 很便宜，模板匹配，不需要保存音频）。
     * 持续接收的常态（无 VIS）可以完全不调用它。
     *
     * 频次与 [feed] 一致即可；音频起点必须与频率流起点对齐。
     */
    fun feedAudio(pcm: FloatArray) {
        if (pcm.isEmpty()) return
        // 与 feed 竞争同一份可变状态（visDetector 与 initialShiftHz），纳入同一把锁
        locked {
            // 增量喂给 VIS 检测器：它内部自己维护窗口与丢弃策略，这里不保留音频
            val result = visDetector.process(pcm)
            // 中途捕到 VIS：失谐量可信度远高于「未锁定时的 0」，立刻用于脉冲搜索。
            // 模式仍不切换 —— 切换会让流水号断裂、已收内容与新模式的行周期不匹配
            // 写运行时镜像 [initialShiftHz]（构造参数初始化的可变副本），
            // 而不是构造参数本身 —— 参数在 Kotlin 里是只读的
            if (result != null && !fitLocked) {
                initialShiftHz = result.hedrShiftHz
            }
        }
    }

    /** 当前统计快照。 */
    fun stats(): Stats {
        // 预览刷新协程每 120ms 调一次，必须与 feed/feedAudio/finalizeFrames 互斥，
        // 否则可能读到环写入到一半的中间态（脏统计甚至越界）
        return locked {
            val slant = if (fitLocked && lineSamples.isFinite()) {
                lineSamples / nominalLineSamples
            } else {
                1.0
            }
            Stats(
                decodedLines = decodedRadioLines * mode.imageRowsPerRadioLine,
                syncPulses = pulseK.toInt(),
                hedrShiftHz = currentShiftHz(),
                slantRatio = slant,
                syncHitRate = hitRate(),
                lostSync = lostSync,
                locked = fitLocked,
                bufferedSamples = bufferedSamples(),
                historySamples = historySamples,
            )
        }
    }

    /**
     * 已消费的样本数：调用方可安全丢弃 `feed` 累计前 [consumedSamples] 个样本。
     *
     * Q1 之后**内部已经自行回收**，这个数字主要用于诊断与测试：它表示「当前帧尾之前、
     * 未来不可能再被实时路径读取的样本数」。超过它之前的数据只可能以抽样形式存在于
     * 历史段中（供 [finalize] 使用）。
     */
    fun consumedSamples(): Long {
        // 只读多份内部状态，同样可能与 feed 并发，纳入同一把锁（P2-1）
        return locked {
            // 回收点 = 下一行行首 − 一个行周期的余量
            //
            // 为什么不是「已解完那一行的行尾」：
            //  - 下一行的**同步脉冲**必须先被检测到（它位于下一行行首附近），
            //    否则流式解码会永远等不到该行的行锚点；
            //  - 逐行精调还会回读该行同步脉冲的中心，正常情况下落在行首之后，
            //    但 Scottie 族的同步在行中、脉冲中心最晚可比行首晚近一个行周期。
            //
            // 保守地留整整一个行周期：丢样本的代价（整行错乱）远高于多留一段内存，
            // 而一个行周期相对整段缓冲是小量（Robot 36 约 0.15 s）。
            if (decodedRadioLines == 0) return@locked 0L
            val samples = if (fitLocked) lineSamples else nominalLineSamples
            val start = radioStart(firstRadio + nextRadio, samples)
            if (!start.isFinite()) return@locked 0L
            (start - samples).toLong().coerceIn(0L, tailFloor)
        }
    }

    /**
     * 收尾：用历史段 + 高频段拼出每一帧的完整频率，做全局拟合后重解。
     *
     * 与实时输出的分工见类注释；重解的原因是非因果拟合能修正早期行的定位偏差（D2）。
     * 与一期的一致性：若这段音频里**存在 VIS 头**（例如从帧首开始接收），走与一期
     * 完全相同的路径（沿用 VIS 给出的起点与失谐量）；否则用全局脉冲拟合。
     *
     * 注意：VIS 给出的起点是「音频内的绝对样本位置」，而历史段只从**首个被接受脉冲**
     * 之前开始 —— 若 VIS 落在历史段之前，就退化为全局拟合（此时历史段也不完整，
     * 结果本来就不是一期意义上的「整帧」）。
     *
     * @return 各帧的最终图像（最老在前）；没有可用数据时返回空列表
     */
    fun finalizeFrames(): List<SstvDecoder.Result> {
        // 停止收尾可能在 join 超时后与仍存活的采集协程并发执行（P2-1），
        // 与 feed/feedAudio 互斥后再做全局拟合与重解
        return locked {
            finalizeFramesLocked()
        }
    }

    /** [finalizeFrames] 的锁内实现（不持锁调用！）。 */
    private fun finalizeFramesLocked(): List<SstvDecoder.Result> {
        val out = ArrayList<SstvDecoder.Result>(segments.size + 1)
        if (segments.isEmpty()) {
            // 还没跨过帧边界：当前帧仍在高频段里，只有它一整段
            val whole = copyTail()
            if (whole.isNotEmpty()) {
                decodeWholeSegment(whole, tailStart, 1)?.let { out.add(it) }
            }
            return out
        }

        for (index in segments.indices) {
            val segment = segments[index]
            // 帧尾：下一段的起点；最后一段延到当前写入游标（含高频段）
            val end = if (index + 1 < segments.size) {
                segments[index + 1].startSample
            } else {
                appendedEnd
            }
            val freqs = materialize(segment, end)
            if (freqs.isEmpty()) continue
            decodeWholeSegment(freqs, segment.startSample, segment.stride)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 收尾：返回**最近一帧**的最终图像（与一期逐像素一致的定稿）。
     *
     * 持续接收里通常只关心当前正在收的这一帧；历史帧见 [finalizeFrames]。
     *
     * @return 最终图像；数据不足以定位时返回 null
     */
    fun finalize(): SstvDecoder.Result? = finalizeFrames().lastOrNull()

    /** 是否还有可 [finalize] 的历史数据。 */
    fun hasHistory(): Boolean = locked { segments.isNotEmpty() || appendedEnd > tailStart }

    /** 清空全部状态（重新开始一次持续接收）。幂等。 */
    fun reset() = locked {
        clearTail()
        segments.clear()
        historySamples = 0L
        visDetector.reset()
        inPulse = false
        pulseStart = 0L
        pulseFreqSum = 0.0
        pulseFreqCount = 0
        shiftCount = 0
        shiftWriteIndex = 0
        centerCount = 0
        centerWriteIndex = 0
        candidateCount = 0
        recentCenters.fill(Double.NaN)
        hitWindow.fill(false)
        hitCount = 0
        hitWriteIndex = 0
        totalHits = 0
        pulseK = 0
        sumK = 0.0
        sumP = 0.0
        sumKK = 0.0
        sumKP = 0.0
        lineSamples = Double.NaN
        firstCenter = Double.NaN
        fitLocked = false
        lostSync = false
        nextRadio = 0
        firstRadio = 0
        decodedRadioLines = 0
        missStreak = 0
        anchorCenter = Double.NaN
        lastAcceptedLine = Long.MIN_VALUE
        // 行解码器带着 Robot 色度平面与奇偶状态，一并换新
        resetLineDecoder()
    }

    /**
     * 丢弃历史段（释放低频段内存）。
     *
     * 用户保存完图像、或明确不再需要「事后重解」时调用：之后 [finalize] 只能看到当前
     * 帧的末段数据（通常不足以出完整帧，会返回 null 或不完整的图）。持续接收可以据此
     * 把内存压到最小 —— 这是调研风险项 R5「持续接收产生海量图库/内存」的缓解手段。
     */
    fun dropHistory() {
        // segments 与 historySamples 会被 feed 并发改写，纳入同一把锁（P2-1）
        locked {
            segments.clear()
            historySamples = 0L
        }
        // 当前帧的起点随之丢失：实时路径不受影响，但 finalize 不再有完整帧
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 两段式缓冲写入
    // ══════════════════════════════════════════════════════════════════

    private fun appendAll(newFreqs: FloatArray) {
        ensureTailAllocated()
        val from = appendedEnd
        val to = from + newFreqs.size

        // ① 高频段：写入环形窗口（每段按「到环尾」的长度切分，避免跨环的两次拷贝）
        var written = 0
        while (written < newFreqs.size) {
            val cursor = from + written
            val index = ringIndex(cursor)
            val run = minOf(newFreqs.size - written, tailCapacity - index)
            System.arraycopy(newFreqs, written, buffer, index, run)
            written += run
        }

        // ② 低频段：续写未冻结的段（正常只有最后一段）。必须覆盖**整块**而不只是新样本 ——
        //    段内空洞会让 finalize 的全局拟合偏差被放大
        //
        //    首段必须在**本块回填之前**建立：段一旦存在，下面的循环就会把整块样本写进
        //    段里。若等到锁定后（maintainHistory）再建，这批样本早已只存在于环里（甚至
        //    已被环覆盖），段从空开始 —— 单次大块喂入时 finalize 只能看到尾部几秒。
        if (segments.isEmpty()) {
            segments.add(Segment(from, FloatArray(0), 0, 1))
        }
        for (segment in segments) {
            if (segment.frozen) continue
            segment.clip(from, to)
            segment.append(from, to) { position -> sampleAt(position, from, newFreqs) }
        }

        // ③ 推进游标；若推进超过环容量，整体平移环（新样本覆盖最老的）
        appendedEnd = to
        slideTailIfNeeded()
    }

    /** 读取绝对位置 [position] 的样本：优先当前块，其次高频段环。 */
    private fun sampleAt(position: Long, blockStart: Long, block: FloatArray): Float {
        if (position >= blockStart && position - blockStart < block.size) {
            return block[(position - blockStart).toInt()]
        }
        if (position < tailStart || position >= appendedEnd) return Float.NaN
        return buffer[ringIndex(position)]
    }

    /** 绝对位置 → 环下标。前提：`position ∈ [tailStart, tailStart + tailCapacity)`。 */
    private fun ringIndex(position: Long): Int =
        ((position - tailStart).toInt()) and (tailCapacity - 1)

    /** 环容量不足时，把环整体推进整数个容量（起点仍对齐），丢弃最老样本。 */
    private fun slideTailIfNeeded() {
        val needed = appendedEnd - tailStart
        if (needed <= tailCapacity) return
        // 每次平移整数个容量：起点保持对齐，环形映射不变。
        // （块大小远小于容量，因此正常只会平移一次；while 是为了极端大块也不出错）
        while (appendedEnd - tailStart > tailCapacity) {
            tailStart += tailCapacity.toLong()
        }
    }

    private fun ensureTailAllocated() {
        if (buffer.isNotEmpty()) return
        buffer = FloatArray(tailCapacity)
        // 起点对齐到容量整数倍
        tailStart = 0L
    }

    private fun bufferedSamples(): Int = (appendedEnd - tailStart).toInt().coerceIn(0, tailCapacity)

    private fun clearTail() {
        buffer = FloatArray(0)
        tailStart = appendedEnd
        scanCursor = appendedEnd
        tailFloor = appendedEnd
    }

    /** 当前高频段里的全部样本（从 [tailStart] 起）。 */
    private fun copyTail(): FloatArray = copyRange(tailStart, appendedEnd)

    /**
     * 把绝对位置区间 `[from, to)` 从环形缓冲复制成线性数组。
     *
     * 调用方必须保证区间落在环内（`from >= tailStart`、`to <= appendedEnd`）。
     */
    private fun copyRange(from: Long, to: Long): FloatArray {
        val count = (to - from).toInt().coerceAtLeast(0)
        if (count == 0) return FloatArray(0)
        val out = FloatArray(count)
        var written = 0
        while (written < count) {
            val index = ringIndex(from + written)
            val run = minOf(count - written, tailCapacity - index)
            System.arraycopy(buffer, index, out, written, run)
            written += run
        }
        return out
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 历史段维护（按帧保留 + 抽样）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 在每次 [feed] 末尾维护历史段：切帧、抽样、帧数上限。
     *
     * 帧边界的判定：**连续太久没有同步脉冲**（超过一个完整帧时长 + 1 秒）就认为上一帧
     * 已经发完。这只影响「历史段如何切分」（截图、finalize 的输入），不影响实时解码。
     */
    private fun maintainHistory() {
        if (!fitLocked || appendedEnd <= 0) return
        // ① 首段由 [appendAll] 在首个样本写入时建立（从流起点开始）。这里不再建段：
        //    等到锁定后再建，那批样本已经只存在于环里、甚至被覆盖，段会是空的。
        if (segments.isEmpty()) return

        // ② 帧尾切分：最后一个脉冲之后超过 frameGapSamples 仍无新脉冲 → 上一段冻结
        //
        //    ⚠ 必须用「最近实际接受的脉冲」：若用拟合外推，丢锁后会一直外推、
        //    永远判不出帧尾，历史段就永远不会切分（finalize 也就永远只有一段素材）。
        val last = segments.last()
        val lastCenter = lastAcceptedCenter()
        if (!last.frozen && lastCenter.isFinite()) {
            val gap = appendedEnd - lastCenter
            if (gap > frameGapSamples) {
                last.frozen = true
                // 新段起点：最后一个脉冲之后一行。开头几行若落在无声区，脉冲检测会
                // 在拿到 4 个脉冲后才锁定，多留的行不影响实时路径
                val nextStart = (lastCenter + lineSamplesOrDefault()).toLong()
                    .coerceAtMost(appendedEnd)
                segments.add(Segment(nextStart, FloatArray(0), 0, 1))
            }
        }

        // ③ 预算：超出后把**最老的**段步长翻倍（时间跨度不变、精度下降）
        while (segments.sumOf { it.size.toLong() * it.stride } > maxHistorySamples) {
            val target = segments.firstOrNull { it.stride < MAX_STRIDE && it.size > 256 } ?: break
            target.halveStride()
        }

        // ④ 帧数上限：整帧丢弃最老的。
        //    但**最后一段不能丢** —— 它是「当前正在收的这一段」，所有后续样本都按它
        //    记账；若把段列表清空，下一次 feed 会重新建立一个起点落在当前尾部的段，
        //    早期数据就悄悄消失在历史之外（finalize 就少一段素材）
        while (segments.size > maxSegments && segments.size > 1) {
            segments.removeAt(0)
        }
        historySamples = segments.sumOf { it.size.toLong() * it.stride }
    }

    private fun lineSamplesOrDefault(): Double =
        if (lineSamples.isFinite() && lineSamples > 0) lineSamples else nominalLineSamples

    /**
     * 把 [segment] 的存储内容截取到 `[windowStart, windowEnd)` 窗口内（段起点随之移动）。
     *
     * 每块 feed 调用一次、一步截取：窗口起点之前的样本已不可能被回读，直接削掉。
     */
    private fun Segment.clip(windowStart: Long, windowEnd: Long) {
        if (size == 0) return
        // 窗口起点之前的样本已经不可能再被回读到：从存储里削掉
        val firstIndex = ceil((windowStart - startSample).toDouble() / stride).toInt()
            .coerceAtLeast(0)
        if (firstIndex < size) {
            val keep = size - firstIndex
            val shrunk = FloatArray(keep)
            System.arraycopy(data, firstIndex, shrunk, 0, keep)
            data = shrunk
            size = keep
            startSample += firstIndex.toLong() * stride
        }
        // 窗口终点之后的样本（只可能出现在「回退重喂」这种异常路径）同样削掉
        val endIndex = ceil((windowEnd - startSample).toDouble() / stride).toInt()
            .coerceIn(0, size)
        if (endIndex < size) {
            data = data.copyOf(endIndex)
            size = endIndex
        }
    }

    /**
     * 把 [segment] 从起点到 [end] 还原成逐样本频率流（stride > 1 时线性插值）。
     *
     * 段尾之后若已进入高频段，用高频段的精确样本覆盖（最后一段的尾部最精确）。
     */
    private fun materialize(segment: Segment, end: Long): FloatArray {
        val length = end - segment.startSample
        if (length <= 0) return FloatArray(0)
        val out = FloatArray(length.toInt().coerceAtLeast(0))
        if (out.isEmpty()) return out

        if (segment.size == 0) {
            // 什么都没存下（极短段）：整体交给高频段
            overlapTail(segment.startSample, out)
            return out
        }

        val lastValue = segment.data[segment.size - 1]
        for (i in out.indices) {
            val position = segment.startSample + i
            val index = ((position - segment.startSample) / segment.stride).toInt()
            if (index >= segment.size) {
                // 超出段尾：用最后一个样本填充（正常路径不会发生）
                out[i] = lastValue
                continue
            }
            if (segment.stride == 1) {
                out[i] = segment.data[index]
                continue
            }
            // 线性插值还原到逐样本密度
            val base = segment.startSample + index.toLong() * segment.stride
            val fraction = (position - base).toDouble() / segment.stride
            val a = segment.data[index]
            val b = segment.data[minOf(index + 1, segment.size - 1)]
            out[i] = (a + (b - a) * fraction).toFloat()
        }

        // 段尾之后进入高频段的部分：用精确样本覆盖
        overlapTail(segment.startSample, out)
        return out
    }

    /**
     * 用高频段的精确样本覆盖 [out] 中与之重叠的部分（[out] 从 [segmentStart] 起算）。
     *
     * 二者可能以两种方式错位，必须分别换算：
     *  - 段起点**早于**环起点（常见）：`out` 内的高频段起点 = tailStart − segmentStart，
     *    高频段从下标 0 开始；
     *  - 段起点**晚于**环起点（段刚建立、环已先滑动）：`out` 从下标 0 开始，
     *    高频段要从 offset = segmentStart − tailStart 处开始拷。
     *
     * 早期实现只处理了前一种，后者会让 `from` 为负、整段样本都不拷贝 —— 于是
     * finalize 拿到一段全零的频率流，同步拟合直接失败（返回 null）。
     */
    private fun overlapTail(segmentStart: Long, out: FloatArray) {
        val tail = copyTail()
        if (tail.isEmpty() || out.isEmpty()) return
        val tailOffset = (segmentStart - tailStart).coerceAtLeast(0L).toInt()
        val outOffset = (tailStart - segmentStart).coerceAtLeast(0L).toInt()
        if (tailOffset >= tail.size || outOffset >= out.size) return
        val count = minOf(tail.size - tailOffset, out.size - outOffset)
        if (count > 0) System.arraycopy(tail, tailOffset, out, outOffset, count)
    }

    /** 把整段频率解码为一帧（与一期路径一致）。 */
    private fun decodeWholeSegment(freqs: FloatArray, startSample: Long, stride: Int): SstvDecoder.Result? {
        if (freqs.size < sampleRate / 4) return null
        val decoder = SstvDecoder(sampleRate = sampleRate)

        // ① 有 VIS 且模式一致：走与一期完全相同的路径（此时结果逐像素一致）
        val vis = visDetector.result
        if (vis != null && vis.mode == mode) {
            // VIS 坐标是「喂给检测器的音频」坐标系；音频与频率流同时起算，
            // 因此它与段起点之差就是段内下标（抽样时按 stride 折算）。
            //
            // VIS 必须落在**本段范围内**：持续接收时 VIS 只出现在流首，后续帧的段
            // 起点都在它之后；此时强行把它夹到 0 会在错误位置搜同步，把本来能解的
            // 帧解死。落在段外的 VIS 直接忽略，交给全局拟合。
            val local = ((vis.stopEndSample - startSample) / stride).toInt()
            if (local >= 0 && local < freqs.size) {
                val viaVis = decoder.decodeFromFrequencies(
                    freqs = freqs,
                    mode = mode,
                    hedrShiftHz = vis.hedrShiftHz,
                    searchFromSample = local,
                    // 与一期 [SstvDecoder] 一致：解调器的瞬态样本不可采样。
                    // 历史段抽样后采样率等价于 rate/stride，瞬态长度需按 stride 折算
                    validFromSample = SstvDemodulator.TRANSIENT_SAMPLES / stride.coerceAtLeast(1),
                )
                if (viaVis is SstvDecoder.Outcome.Success) return viaVis.result
            }
        }

        // ② 无 VIS 时的全局拟合回退（保留首脉冲，见 SstvSync.findSyncPulses）
        return decodeWithGlobalFit(freqs, decoder)
    }

    /** 无 VIS 时的全局拟合回退。 */
    private fun decodeWithGlobalFit(freqs: FloatArray, decoder: SstvDecoder): SstvDecoder.Result? {
        val fit = SstvSync.findSyncPulses(
            freqs = freqs,
            mode = mode,
            sampleRate = sampleRate,
            hedrShiftHz = initialShiftHz,
            startIndex = 0,
            dropFirstPulse = false,
        ) ?: return null
        val shift = if (fit.pulseCount >= MIN_PULSES_FOR_SHIFT_REFINE) {
            fit.measuredShiftHz
        } else {
            initialShiftHz
        }
        val effectiveRate = fit.effectiveRate(sampleRate)
        val pixels = IntArray(width * mode.imageLines)
        // 与一期一致：每次独立解码都要重置行奇偶状态
        decoder.resetFrameState()
        var decoded = 0
        for (radio in 0 until mode.radioLines) {
            if (!decoder.decodeRadioLine(
                    freqs = freqs,
                    fit = fit,
                    mode = mode,
                    line = radio,
                    hedrShiftHz = shift,
                    pixels = pixels,
                    validFrom = 0,
                    effectiveRate = effectiveRate,
                )
            ) {
                break
            }
            decoded += mode.imageRowsPerRadioLine
        }
        if (decoded == 0) return null
        return SstvDecoder.Result(
            mode = mode,
            width = width,
            height = mode.imageLines,
            pixels = pixels,
            hedrShiftHz = shift,
            slantRatio = fit.slantRatio,
            syncPulseCount = fit.pulseCount,
            decodedLines = decoded.coerceAtMost(mode.imageLines),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 流式脉冲检测（跨块延续的段状态机）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 逐样本扫描 1200 Hz 连续段（从 [scanCursor] 到当前写入游标之前）。
     *
     * 与 [SstvSync.findSyncPulses] 的「一次扫出全部脉冲」不同，这里是**跨块延续**的
     * 段状态机：输入块可能在脉冲中间断开，`inPulse` / `pulseStart` 等字段必须跨调用
     * 保持，否则会把一个脉冲切成两个、或产生半个脉冲的误判。
     *
     * 1200 Hz 位于亮度映射区间（1500–2300 Hz）之外，因此图像内容不会误触发；
     * 再加长度门限 [minPulseSamples] 挡住过渡带毛刺。
     */
    private fun scanPulses() {
        if (buffer.isEmpty()) return
        // 只扫到「环里仍然存在」的范围：更老的样本已经不可能再参与
        val scanFrom = maxOf(scanCursor, tailStart)
        if (scanFrom >= appendedEnd) {
            scanCursor = maxOf(scanCursor, appendedEnd)
            return
        }
        val targetHz = nominalSyncHz + currentShiftHz()
        var position = scanFrom
        while (position < appendedEnd) {
            val index = ringIndex(position)
            val run = minOf((appendedEnd - position).toInt(), tailCapacity - index)
            val end = position + run
            var i = index
            while (i < index + run) {
                val value = buffer[i]
                if (abs(value - targetHz) <= SYNC_TOLERANCE_HZ) {
                    if (!inPulse) {
                        inPulse = true
                        pulseStart = position + (i - index)
                        pulseFreqSum = 0.0
                        pulseFreqCount = 0
                    }
                    pulseFreqSum += value
                    pulseFreqCount++
                } else if (inPulse) {
                    inPulse = false
                    val segEnd = position + (i - index)
                    val length = segEnd - pulseStart
                    // 上限门限：过长的段是「合并段」，它的中心不能当行锚点（见 onPulse）
                    if (length >= minPulseSamples && length <= maxPulseSamples && pulseFreqCount > 0) {
                        onPulse(pulseStart.toDouble(), segEnd.toDouble(), pulseFreqSum / pulseFreqCount)
                    }
                }
                i++
            }
            position = end
        }
        scanCursor = appendedEnd
    }

    /**
     * 接受一个同步脉冲。
     *
     * 防线（设计文档 D4「最小二乘对离群脉冲敏感」的缓解）：
     *  1. 长度门限 —— 已在 [scanPulses] 上游过滤；
     *  2. 节拍门限 —— 真实同步脉冲的间距恒为 1 个行周期；未锁定时按「连续差一个
     *     行周期」攒候选链，攒够 4 个才锁定（见 [attachCandidate]）；已锁定时用拟合
     *     直线外推校验，偏差超过半行周期即丢弃。这一条同时挡住图像内容里偶然的
     *     1200 Hz 波动，以及一个同步脉冲被噪声切成两段产生的碎片。
     */
    private fun onPulse(start: Double, end: Double, meanHz: Double) {
        val center = (start + end) / 2.0
        if (!center.isFinite()) return

        if (fitLocked && lineSamples.isFinite()) {
            // 允许漏检：先把脉冲对齐到最近的「行格点」，再检查它离该格点是否足够近。
            //
            // 不能像早期实现那样预测「正好第 pulseK 个」：真实信号偶尔会漏一个脉冲
            // （脉冲恰好跨环边界被截断、深衰落、瞬时干扰），预测值随即永久落后整整
            // 一行，此后每个真实脉冲都被判为野值 —— 跟踪器在第一次漏检后彻底失效，
            // 命中率跌到 0，后续行全靠外推（实测 MARTIN 1 在第 159 个脉冲处死锁）。
            //
            // 行号必须单调：同一脉冲重复上报、或迟到的旧脉冲都直接丢弃，
            // 否则最小二乘会被重复点带偏。
            val line = round((center - firstCenter) / lineSamples).toLong()
            if (line <= lastAcceptedLine) return
            val predicted = firstCenter + lineSamples * line.toDouble()
            if (abs(center - predicted) > lineSamples * CHAIN_TOLERANCE) return
            lastAcceptedLine = line
            // 把最小二乘的序号也推进到这一行。
            //
            // 回归的自变量必须是**真实行号**而不是「第几个收到的脉冲」：漏检时二者
            // 会错开，若仍用顺序号，等于把 11 个行周期的间隔记成 1 个，斜率被拉高
            // 4% 以上，越过 slant 门限后反复「解锁 → 重建链 → 再解锁」，丢锁恢复
            // 就再也收不到行（实测 `丢锁后重新锁定` 只剩零星几行）。
            pulseK = line
        }
        attachCandidate(center, meanHz)
    }

    /**
     * 锁定前的候选链：连续差一个行周期的脉冲中心。
     *
     * 只用最近 [MIN_PULSES_FOR_FIT] 个（够了就能直接拟合），因此容量固定，O(1) 追加。
     */
    private val candidateChain = DoubleArray(MIN_PULSES_FOR_FIT)
    private var candidateCount = 0

    /**
     * 把候选脉冲并入链，必要时锁定。
     *
     * 链的维护规则：
     *  - 链为空 → 直接收；
     *  - 与链尾相差约一个行周期 → 追加；
     *  - 否则 → 链重置为只含本候选（说明前面那串不是真实行同步）。
     *
     * 一旦链满 [MIN_PULSES_FOR_FIT] 个，就以它们为起点建立拟合（k 由此起算），
     * 之后的脉冲全部按「已锁定」的规则校验。
     */
    private fun attachCandidate(center: Double, meanHz: Double) {
        if (fitLocked) {
            appendPulse(center, meanHz)
            if (pulseK >= MIN_PULSES_FOR_FIT) refit()
            return
        }

        val toleranceMin = nominalLineSamples * CHAIN_MIN_TOLERANCE
        val toleranceMax = nominalLineSamples * CHAIN_MAX_TOLERANCE
        val last = if (candidateCount > 0) candidateChain[candidateCount - 1] else Double.NaN
        if (last.isFinite()) {
            val distance = abs(center - last)
            if (distance < toleranceMin || distance > toleranceMax) {
                // 节拍断了：以本候选开启新链
                candidateCount = 0
            }
        }
        candidateChain[candidateCount++] = center
        if (candidateCount < MIN_PULSES_FOR_FIT) return

        // 链满：这就是真实行同步，一次性入账并拟合
        candidateCount = 0
        for (index in 0 until MIN_PULSES_FOR_FIT) {
            appendPulse(candidateChain[index], meanHz)
        }
        refit()
    }

    /** 入账一个脉冲：滚动失谐、在线最小二乘累积量、环形缓冲。 */
    private fun appendPulse(center: Double, meanHz: Double) {
        shiftWindow[shiftWriteIndex] = meanHz
        shiftWriteIndex = (shiftWriteIndex + 1) % SHIFT_WINDOW
        if (shiftCount < SHIFT_WINDOW) shiftCount++

        val k = pulseK.toDouble()
        sumK += k
        sumP += center
        sumKK += k * k
        sumKP += k * center
        pulseK++

        if (!anchorCenter.isFinite()) anchorCenter = center

        recentCenters[centerWriteIndex % PULSE_WINDOW] = center
        centerWriteIndex++
        if (centerCount < PULSE_WINDOW) centerCount++
    }

    /**
     * 最近一个**实际接受**的脉冲中心。
     *
     * 与「按拟合直线外推到最近一行」不同：丢锁时尾部会有很长一段没有实测脉冲，
     * 用外推值判断「帧结束」只会让新帧的起点跟着漂。这里只看真实观测。
     */
    private fun lastAcceptedCenter(): Double {
        if (centerCount == 0) return Double.NaN
        val index = (centerWriteIndex - 1 + PULSE_WINDOW) % PULSE_WINDOW
        return recentCenters[index]
    }

    /** 用累积量重算直线 `center(k) ≈ firstCenter + lineSamples · k`。 */
    private fun refit() {
        val n = pulseK.toDouble()
        val denominator = n * sumKK - sumK * sumK
        if (denominator <= 0.0) return
        val slope = (n * sumKP - sumK * sumP) / denominator
        val intercept = (sumP - slope * sumK) / n

        // 行周期必须接近标称值，否则说明串链错误（与一期同样的守卫）
        if (slope < nominalLineSamples * MIN_SLANT_RATIO ||
            slope > nominalLineSamples * MAX_SLANT_RATIO
        ) {
            resetRegression()
            return
        }

        lineSamples = slope
        firstCenter = intercept
        if (!fitLocked) {
            fitLocked = true
            lostSync = false
            // 刚锁定时，最后一个已接受的脉冲是第 (pulseK−1) 行；此后只接受
            // 行号更大的脉冲（见 onPulse 的单调校验）
            lastAcceptedLine = pulseK - 1L
            // 首个接受脉冲之前还有多少行？
            //
            // 音频可能从图像中途开始，此时首脉冲所在行的行首为负（行已残缺），
            // 该行及其之前的所有行都不能解，因此 firstRadio 取负把它们跳过；
            // 与 radioStart 的换算是互逆关系。
            if (slope > 0.0) {
                val syncSamples = mode.syncSeconds * sampleRate
                val anchorStart = intercept - syncSamples / 2.0 -
                    mode.syncOffsetSeconds * sampleRate
                // 行首为负时缺 ceil(-行首 / 行周期) 行；行首为正则为 0（不能是正数，
                // 那会让流水号从一个不存在的行开始）
                val missing = ceil(-anchorStart / slope).toInt()
                firstRadio = -missing.coerceAtLeast(0)
            }
        }
    }

    /** 清空回归累积量并回到搜索态（脉冲序号保留，行号语义因此保持连续）。 */
    private fun resetRegression() {
        sumK = 0.0
        sumP = 0.0
        sumKK = 0.0
        sumKP = 0.0
        lineSamples = Double.NaN
        firstCenter = Double.NaN
        fitLocked = false
        lostSync = false
        missStreak = 0
    }

    /** 重建行解码器（清掉 Robot 跨行色度与奇偶状态）。 */
    private fun resetLineDecoder() {
        lineDecoder = SstvDecoder(sampleRate)
    }

    // ══════════════════════════════════════════════════════════════════
    // ④ 逐行解码
    // ══════════════════════════════════════════════════════════════════

    /**
     * 推出所有「已完整到达」的无线行。
     *
     * 行边界的判定与一期**逐条一致**，这是 `finalize` 能逐像素对齐的前提：
     * 一期对每个通道检查
     *   ① `channelStart < freqs.size - 1`（起点未越界）
     *   ② `channelStart + pixels·pixelSamples >= validFrom`（未全落在瞬态区）
     * 在 `validFrom = 0` 时等价于「该通道终点已到达」；再加上
     *   ③ `lineStartRefined` 命中的脉冲中心 `< freqs.size - 1`（精调读数不越界）
     * 三条都满足才解，否则 `decodeRadioLine` 会解到不完整的数据。
     *
     * @param force 未锁定时也用标称行周期解码（[flush] 用）
     */
    private fun drainRadioLines(force: Boolean = false) {
        if (buffer.isEmpty()) return
        if (!fitLocked && !force) return
        if (!fitLocked && pulseK == 0L) return

        // 高频段回收点不能越过逐行精调可能回读的最老脉冲（否则整幅错位）
        if (fitLocked) {
            val start = radioStart(firstRadio + nextRadio, lineSamplesOrDefault())
            if (start.isFinite()) {
                val floor = (start - tailCapacity).toLong()
                if (floor > tailFloor) tailFloor = floor
            }
        }

        val samples = if (fitLocked) lineSamples else nominalLineSamples
        if (!samples.isFinite() || samples <= 0.0) return
        val effectiveRate = sampleRate * (samples / nominalLineSamples)

        // 一行内最晚结束的通道终点（相对行首的秒数）→ 该行被完整解出所需的最小样本数
        val tasks = mode.channelTasks()
        val lastChannelEnd = tasks.maxOf { it.startSeconds + it.pixels * it.pixelSeconds }

        while (true) {
            val start = radioStart(firstRadio + nextRadio, samples)
            if (!start.isFinite() || !samples.isFinite()) return

            // 起点在音频之前（音频从行中间开始）→ 这一行不完整，跳过
            if (start < 0.0) {
                if (start + samples >= 0.0) {
                    nextRadio++
                    continue
                }
                // 整行都在起点之前只可能是坐标异常；停止以免死循环
                break
            }

            // 这一行（含行首同步脉冲与逐行精调要找的脉冲）已经全部滚出高频段：
            // 再等也补不回来（长时间丢锁后重新锁定的常态）。直接跳过并推进行号，
            // 而不是用环里的新数据冒充旧行 —— 那样只会画出一条错位的行。
            if (start + samples < tailStart) {
                nextRadio++
                if (tailStart - (start + samples) > tailCapacity) break
                continue
            }

            // 条件②：最晚通道的终点必须已到达
            val lastChannelEndSample = start + lastChannelEnd * effectiveRate
            if (lastChannelEndSample > appendedEnd - 1) break

            // 条件③：**精调实际会命中的那个脉冲中心**必须已到达（否则读数越界）
            val fit = fitForRadioLine(start, samples, effectiveRate)
            val syncSamples = mode.syncSeconds * effectiveRate
            val predictedCenter = start + mode.syncOffsetSeconds * effectiveRate + syncSamples / 2.0
            val matched = nearestPulse(predictedCenter, samples * SstvSync.LINE_REFINE_TOLERANCE)
            if (!matched.isNaN() && matched > appendedEnd - 1) break

            val locked = fit.pulseCenters.isNotEmpty()
            radioLinePixels.fill(0)

            // 把这一行需要读的样本从**环形缓冲**展开成线性数组，并把 fit 的坐标整体
            // 平移到窗口起点。
            //
            // 为什么必须转换：decodeRadioLine 用 `firstLineStart + row·lineSamples`
            // 之类的**绝对样本位置**直接索引 freqs。Q0 的频率缓冲是线性数组，绝对位置
            // 恰好等于下标；Q1 改成环形缓冲后，绝对位置与环下标只差一个常量
            // `tailStart`，而 decodeRadioLine 里的越界守卫
            // `channelStart >= freqs.size - 1` 会用绝对位置去比环容量 —— 环一滑动
            // （约 5 秒后）每一行都判为「越界」，实时输出直接归零。
            //
            // 窗口取「行首前一个行周期 ~ 最晚通道终点后半个行周期」：前侧覆盖逐行精调
            // 要回读的行首同步脉冲（Scottie 族的同步在行中，也不会超出），后侧覆盖
            // 最后一个通道的尾部与群延迟。
            val windowStart = (start - samples).toLong().coerceAtLeast(tailStart)
            val windowEnd = (lastChannelEndSample + samples * 0.5).toLong()
                .coerceAtMost(appendedEnd)
            val window = copyRange(windowStart, windowEnd)
            // line = 0：时序在行内是相对的，绝对位置由 fit.firstLineStart 承载
            val localFit = fit.copy(
                firstLineStart = fit.firstLineStart - windowStart,
                pulseCenters = fit.pulseCenters.map { it - windowStart },
            )
            val ok = lineDecoder.decodeRadioLine(
                freqs = window,
                fit = localFit,
                mode = mode,
                line = 0,
                hedrShiftHz = currentShiftHz(),
                pixels = radioLinePixels,
                validFrom = 0,
                effectiveRate = effectiveRate,
            )
            if (ok) emitRadioLine(locked)
            // 解码失败也要前进，否则会卡在同一行
            nextRadio++
        }
    }

    /**
     * 全局流水号为 [radio] 的无线行的**行首**样本位置。
     *
     * 几何推导（与 [SstvSync.SyncFit.lineStartRefined] 严格互逆）：
     * 某行的同步脉冲中心
     * ```
     * center(k) = lineStart(k) + syncOffset + syncSamples/2
     * ```
     * 由拟合直线 `center(k) = firstCenter + k·L` 反解
     * ```
     * lineStart(k) = firstCenter + k·L − syncSamples/2 − syncOffset
     * ```
     * 流水号 0 就是首个脉冲所在的那一行（k = 0）。
     *
     * 未锁定时退回标称周期与最早那个已接受脉冲的中心。
     */
    private fun radioStart(radio: Int, samples: Double): Double {
        if (!samples.isFinite() || samples <= 0.0) return Double.NaN
        val syncSamples = mode.syncSeconds * sampleRate
        val syncOffset = mode.syncOffsetSeconds * sampleRate

        // 永用「最早的那个**实际接受的**脉冲」，不要用拟合截距 firstCenter：
        // 截距是直线外推到 k=0 的结果，而 k=0 未必是首个接受的脉冲（图像内容误检、
        // 或音频起点落在图像中途时都不同）。二者混用会整体差一个行周期。
        val anchor = firstAcceptedCenter()
        if (!anchor.isFinite()) return Double.NaN

        // 行首 = 脉冲中心 − 半个同步脉冲 − Scottie 偏移
        //  - 半个同步脉冲：锚点是脉冲**中心**，行首量的是脉冲**起点**；
        //  - Scottie 偏移：该族同步在行中，行首在同步之前 2·sep + 2·chanLen。
        return anchor + radio.toDouble() * samples - syncSamples / 2.0 - syncOffset
    }

    /**
     * **行几何的锚点**：首个被接受的脉冲中心。
     *
     * ⚠ 不能从 [recentCenters] 环形缓冲里取：它只保留最近 [PULSE_WINDOW] 个脉冲，
     * 接收一会儿就会被覆盖。这里固定累加器 [anchorCenter]（只在首脉冲时写入）。
     */
    private fun firstAcceptedCenter(): Double = anchorCenter

    /**
     * 为一行的**局部**索引构造 [SstvSync.SyncFit]。
     *
     * 让 `firstLineStart` 正好落在该行行首、`lineSamples` 取拟合周期，则
     * `lineStart(0)` 就是这一行 —— 于是可以直接复用一期的 [SstvDecoder.decodeRadioLine]。
     * `pulseCenters` 只放附近（±1.5 行周期）的实测脉冲，供 `lineStartRefined` 精调；
     * 精调命中与否，就是 [LineEvent.locked]。
     */
    private fun fitForRadioLine(
        start: Double,
        samples: Double,
        effectiveRate: Double,
    ): SstvSync.SyncFit {
        val syncSamples = mode.syncSeconds * effectiveRate
        val syncOffset = mode.syncOffsetSeconds * effectiveRate
        val predictedCenter = start + syncOffset + syncSamples / 2.0
        val tolerance = samples * 1.5
        val nearby = ArrayList<Double>(4)
        for (index in 0 until centerCount) {
            val center = recentCenters[index]
            if (center.isFinite() && abs(center - predictedCenter) <= tolerance) nearby.add(center)
        }
        return SstvSync.SyncFit(
            pulseCount = nearby.size,
            firstLineStart = start,
            lineSamples = samples,
            nominalLineSamples = nominalLineSamples,
            syncFrequencyHz = nominalSyncHz + currentShiftHz(),
            pulseCenters = nearby,
            syncOffsetSamples = syncOffset,
        )
    }

    /**
     * 在实测脉冲环里找离 [target] 最近的一个。
     *
     * 判定口径与 [SstvSync.SyncFit.lineStartRefined] 完全一致（容差都是
     * `lineSamples × LINE_REFINE_TOLERANCE`，取最近者），因此「是否命中」
     * 与「命中哪一个」在两边得到同一答案。未命中返回 `NaN`。
     */
    private fun nearestPulse(target: Double, tolerance: Double): Double {
        var best = Double.NaN
        var bestDistance = Double.MAX_VALUE
        for (index in 0 until centerCount) {
            val center = recentCenters[index]
            if (!center.isFinite()) continue
            val distance = abs(center - target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = center
            }
        }
        return if (bestDistance <= tolerance) best else Double.NaN
    }

    /** 回调一行图像并更新命中统计与丢锁状态。 */
    private fun emitRadioLine(locked: Boolean) {
        val rows = mode.imageRowsPerRadioLine
        val baseRow = decodedRadioLines * rows
        for (r in 0 until rows) {
            // 复制一份：radioLinePixels 会被下一行覆盖
            onLine(
                LineEvent(
                    imageRow = baseRow + r,
                    pixels = radioLinePixels.copyOfRange(r * width, (r + 1) * width),
                    locked = locked,
                ),
            )
        }
        decodedRadioLines++

        // 命中统计（环形窗口）
        hitWindow[hitWriteIndex] = locked
        hitWriteIndex = (hitWriteIndex + 1) % SYNC_HIT_WINDOW
        if (hitCount < SYNC_HIT_WINDOW) hitCount++
        if (locked) totalHits++

        // 丢锁判定：连续多行没命中实测脉冲 → 标记；重新命中即恢复（行号不重置）
        if (locked) {
            missStreak = 0
            lostSync = false
        } else {
            missStreak++
            if (missStreak >= LOST_SYNC_LINES) lostSync = true
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 失谐与统计
    // ══════════════════════════════════════════════════════════════════

    /** 当前失谐量估计：脉冲足够时用滚动实测均值，否则用初始值。 */
    private fun currentShiftHz(): Double {
        if (shiftCount < MIN_PULSES_FOR_SHIFT_REFINE) return initialShiftHz
        var sum = 0.0
        for (i in 0 until shiftCount) sum += shiftWindow[i]
        return sum / shiftCount - SstvMode.VIS_BREAK_HZ
    }

    private fun hitRate(): Float {
        if (hitCount == 0) return 0f
        var hits = 0
        for (i in 0 until hitCount) if (hitWindow[i]) hits++
        return hits.toFloat() / hitCount
    }

    /** 标称行周期（样本）。 */
    private val nominalLineSamples: Double get() = mode.lineSeconds * sampleRate

    /** 脉冲最短持续样本数（与一期 0.45 的比例一致）。 */
    private val minPulseSamples: Int
        get() = (mode.syncSeconds * sampleRate * MIN_PULSE_RATIO).toInt().coerceAtLeast(2)

    /**
     * 脉冲最长持续样本数：超过它判为**合并段**而非单个同步脉冲。
     *
     * 最典型的合并段是「VIS 停止位 + 第 0 行同步」——二者都是 1200 Hz 且首尾相接。
     * 实测 Robot 36 下正常脉冲约 134 样本，而该合并段长达 622 样本；若把它当普通
     * 脉冲，其中心会比真实同步中心早约 244 样本，整幅图随之错位一行。
     */
    private val maxPulseSamples: Int
        get() = (mode.syncSeconds * sampleRate * MAX_PULSE_RATIO).toInt().coerceAtLeast(4)

    /** 帧间隔（样本）：超过它就认为上一帧已发完。一个完整帧 + 1 秒。 */
    private val frameGapSamples: Long
        get() = (mode.imageLines * mode.lineSeconds * sampleRate).toLong() +
            sampleRate.toLong()

    /**
     * [`feed`] 内部单块的最大样本数：半个环容量。
     *
     * 取一半而不是整个容量，是为了保证「上一块的样本在被扫描之前不会被下一块覆盖」：
     * 每块写入后 `appendedEnd - tailStart <= capacity`，扫描游标始终落在环内。
     */
    private val maxFeedChunk: Int
        get() = maxOf(1, tailCapacity / 2)

    /** 历史段总预算（样本）：超过后把最老的段抽样减半。 */
    private val maxHistorySamples: Long
        get() = minOf(DEFAULT_MAX_HISTORY_SAMPLES, maxSegments.toLong() * sampleRate * 600 / 2)

    companion object {
        /** 同步频率容差（Hz），与 [SstvSync] 一致。 */
        private const val SYNC_TOLERANCE_HZ = 60.0

        /** 脉冲最短持续比例（相对标称同步时长），与一期一致。 */
        private const val MIN_PULSE_RATIO = 0.45

        /**
         * 脉冲最长持续比例（相对标称同步时长）。
         *
         * 正常同步脉冲长度≈1.0 倍标称（过渡带会让它略长）；取 1.6 留出噪声余量，
         * 同时能稳稳挡住「VIS 停止位 + 首脉冲」这类 3 倍以上的合并段。
         */
        private const val MAX_PULSE_RATIO = 1.6

        /** 锁定所需的最少脉冲数，与一期 `SstvSync.MIN_PULSES_FOR_FIT` 一致。 */
        private const val MIN_PULSES_FOR_FIT = 4

        /** 采用实测失谐均值所需的最少脉冲数（与一期一致）。 */
        private const val MIN_PULSES_FOR_SHIFT_REFINE = 8

        /** 已锁定时，与拟合直线外推位置的最大偏差（行周期的倍数）。 */
        private const val CHAIN_TOLERANCE = 0.5

        /**
         * 节拍门限（行周期的倍数）。
         *
         * 下限 / 上限界定「与上一个已接受脉冲相差约一个行周期」：真实同步脉冲严格落在
         * 这个区间内；图像内容的误检间隔通常远小于下限，或（跳行的）远大于上限。
         */
        private const val CHAIN_MIN_TOLERANCE = 0.85
        private const val CHAIN_MAX_TOLERANCE = 1.15

        /** 连续多少行未命中实测脉冲就判为丢锁。 */
        private const val LOST_SYNC_LINES = 3

        /** 滚动失谐窗口（脉冲数）。 */
        private const val SHIFT_WINDOW = 32

        /** 实测脉冲中心环形缓冲（行数）。 */
        private const val PULSE_WINDOW = 64

        /** 命中率窗口（行数）。 */
        private const val SYNC_HIT_WINDOW = 64

        /** slant 合法范围。 */
        private const val MIN_SLANT_RATIO = 0.95
        private const val MAX_SLANT_RATIO = 1.05

        /** 高频段至少保留的样本数（一次性至少覆盖这么多，避免短模式环太小）。 */
        private const val MIN_TAIL_SAMPLES = 4 shl 16

        /** 高频段保留的行数：覆盖「一行解码进度 + 半个行周期的精调回读」并留足余量。 */
        private const val TAIL_LINES = 8

        /** 历史段默认最多保留的帧数。 */
        const val DEFAULT_MAX_SEGMENTS = 8

        /** 历史段总样本预算（默认）：48 kHz 下相当于 15 分钟完整密度，超出后抽样。 */
        const val DEFAULT_MAX_HISTORY_SAMPLES = 48_000L * 900

        /** 历史段最大抽样步长：再大就还原不出同步脉冲。 */
        private const val MAX_STRIDE = 8

        /** 按模式行周期计算高频段环容量（2 的幂，便于 O(1) 取模）。 */
        internal fun tailCapacityFor(mode: SstvMode, sampleRate: Int): Int {
            val needed = (mode.lineSeconds * sampleRate * TAIL_LINES).toInt().coerceAtLeast(MIN_TAIL_SAMPLES)
            var capacity = 1
            while (capacity < needed) capacity = capacity shl 1
            return capacity
        }
    }
}
