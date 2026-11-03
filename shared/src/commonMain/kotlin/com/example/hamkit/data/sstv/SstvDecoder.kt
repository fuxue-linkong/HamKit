package com.example.hamkit.data.sstv

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * SSTV 解码器：音频 → 图像。
 *
 * 完整链路（见 docs/REQUIREMENT_SSTV.md §4.1）：
 * ```
 * ① VIS 检测      → 模式 + 失谐补偿量 + 图像数据起点
 * ② 正交鉴频      → 逐样本瞬时频率流
 * ③ 行同步 + 拟合 → slant 校正后的每行行首位置
 * ④ 通道采样      → 按模式时序取每像素的电平（0–255）
 * ⑤ 色彩组装      → PD / Robot / Martin 各自的色彩空间与行配对规则
 * ```
 *
 * 解码器与编码器共用 [SstvMode.channelTasks] 的通道时序，因此模式参数表是单点
 * 真相，不存在编解码时序漂移的可能 —— 这也是编解码自环测试能作为金标准的基础。
 *
 * 纯 Kotlin 实现，不依赖 Android API。
 *
 * @param sampleRate 采样率（Hz）
 * @param groupDelaySamples 解调滤波器群延迟补偿（样本），默认 **0**。
 *
 *   理论上 IIR 低通会引入群延迟，但实测（自环扫描 -20…+60 样本）表明 **0 最优**：
 *   行同步是在同一条「已被延迟」的频率流上检测的，同步位置与像素采样因此共享同一
 *   时间轴，额外补偿反而会引入偏移（PSNR 由 29.9 dB 掉到 14.9 dB）。该参数保留用于
 *   更换滤波器配置时的微调。
 * @param pixelNyquistFilter 是否对每个通道做**零相位像素 Nyquist 低通**（默认开启）。
 *
 *   技术参照 robot36（xdsopl/robot36，0BSD）：截止频率取「一个像素能表达的最高横向
 *   频率」，再用前向 + 反向二阶 EMA 实现零相位（不产生水平位移）。开启后弱信号下的
 *   椒盐噪点明显减少，而强信号下的细节损失可忽略（截止正好落在可还原范围的边界上）。
 *   置 false 退回一期的「像素中点窗平均」行为，便于 A/B 对比与回归。
 */
class SstvDecoder(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    private val groupDelaySamples: Double = 0.0,
    private val pixelNyquistFilter: Boolean = true,
) {

    /** 解码成功的结果。 */
    data class Result(
        /** 实际使用的模式。 */
        val mode: SstvMode,
        /** 图像宽度（像素）。 */
        val width: Int,
        /** 图像高度（像素）。 */
        val height: Int,
        /** 行优先的打包 RGB（0xRRGGBB），长度为 `width * height`。 */
        val pixels: IntArray,
        /** 实测 leader 频率与标称 1900 Hz 的偏差（Hz）。 */
        val hedrShiftHz: Double,
        /** 拟合行周期与标称值之比（1.0 表示完全同步）。 */
        val slantRatio: Double,
        /** 参与拟合的行同步脉冲数。 */
        val syncPulseCount: Int,
        /** 实际解码出的行数（同步丢失时会少于模式标称行数）。 */
        val decodedLines: Int,
    )

    /** 解码结果：成功或失败（失败带原因，便于 UI 提示与排障）。 */
    sealed interface Outcome {
        data class Success(val result: Result) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    /**
     * 解码一段包含（或紧跟）SSTV 传输的音频。
     *
     * @param audio 单声道浮点音频，幅度约 ±1.0
     * @param forcedMode 强制指定模式（跳过 VIS 自动识别）。当 VIS 弱信号识别失败
     *   时由 UI 提供；此时仍会尝试读取 VIS 以获得失谐补偿量。
     */
    fun decode(audio: FloatArray, forcedMode: SstvMode? = null): Outcome {
        if (audio.size < sampleRate / 2) {
            return Outcome.Failure("音频过短（需至少 0.5 秒）")
        }

        // ── ① VIS 检测：即使手动指定模式也尝试读取，以获得失谐补偿量 ──
        val visOutcome = SstvVisDetector(sampleRate).process(audio)
        val mode: SstvMode
        if (forcedMode != null) {
            mode = forcedMode
        } else if (visOutcome?.mode != null) {
            mode = visOutcome.mode
        } else if (visOutcome != null) {
            // VIS 头读到了，但模式表里没有这个码（私有/非标模式）：把码值报出来。
            // 与「压根没读到 VIS 头」是两种问题，提示也应不同（对齐 Android 端文案）。
            return Outcome.Failure(
                "该 VIS 码未收录，暂不支持解码（0x${visOutcome.visCode.toString(16).uppercase()}）"
            )
        } else {
            return Outcome.Failure("未识别到 VIS 头，请手动选择模式")
        }
        val hedrShiftHz = visOutcome?.hedrShiftHz ?: 0.0
        val searchFrom = visOutcome?.stopEndSample?.toInt()?.coerceAtLeast(0) ?: 0

        // ── ② 正交鉴频 ──
        val samplesPerPixel = mode.pixelSeconds * sampleRate
        val demodulator = SstvDemodulator(
            sampleRate = sampleRate,
            phaseSpan = SstvDemodulator.recommendPhaseSpan(samplesPerPixel),
        )
        val freqs = demodulator.demodulate(audio)

        return decodeFromFrequencies(
            freqs = freqs,
            mode = mode,
            hedrShiftHz = hedrShiftHz,
            searchFromSample = searchFrom,
            validFromSample = demodulator.transientSamples,
        )
    }

    /**
     * 从**已解调的频率流**解码。
     *
     * 这是把「解调」与「解码」拆开的入口：实时接收时 [SstvRecorder] 保持一个
     * 降频器实例做**增量解调**，只需对新增音频解调一次即可反复解码，避免每轮
     * 部分解码都从头解调整段音频（原先的实现是 O(n²)，一帧内累计解调约 15 倍
     * 单帧样本量）。离线与测试路径仍走 [decode]。
     *
     * @param freqs 逐样本瞬时频率（Hz），下标 0 对应图像数据的起点
     * @param mode 已确定的模式
     * @param hedrShiftHz VIS 测得的失谐量（若同步脉冲足够多会被其实测值取代）
     * @param searchFromSample 从该样本开始搜索行同步脉冲
     * @param validFromSample 该位置之前的样本处于解调瞬态，不予采样
     */
    fun decodeFromFrequencies(
        freqs: FloatArray,
        mode: SstvMode,
        hedrShiftHz: Double,
        searchFromSample: Int = 0,
        validFromSample: Int = 0,
    ): Outcome {
        if (!mode.decodable) {
            return Outcome.Failure("${mode.displayName} 当前版本不支持解码")
        }

        // ── ③ 行同步 + slant 拟合 ──
        val fit = SstvSync.findSyncPulses(
            freqs = freqs,
            mode = mode,
            sampleRate = sampleRate,
            hedrShiftHz = hedrShiftHz,
            startIndex = maxOf(searchFromSample, validFromSample),
        ) ?: return Outcome.Failure("未检测到足够的行同步脉冲，请检查音频质量")

        // ── ④⑤ 逐行采样并组装图像 ──
        // 失谐量优先取自行同步脉冲的实测频率：它由全部脉冲平均而来，比 VIS leader
        // 的 300 ms 单次估计精确得多（残差会整体抬高或压低图像亮度/饱和度）。
        val effectiveShiftHz = if (fit.pulseCount >= MIN_PULSES_FOR_SHIFT_REFINE) {
            fit.measuredShiftHz
        } else {
            hedrShiftHz
        }

        // 行内时间换算使用 slant 校正后的有效采样率，避免水平方向被拉伸
        val effectiveRate = fit.effectiveRate(sampleRate)
        val pixels = IntArray(mode.linePixels * mode.imageLines)
        var decodedLines = 0
        // 每次解码都是一段独立的音频流，行奇偶状态必须重新起算
        resetFrameState()
        for (line in 0 until mode.radioLines) {
            if (!decodeRadioLine(
                    freqs, fit, mode, line, effectiveShiftHz, pixels, validFromSample, effectiveRate,
                )
            ) {
                break
            }
            decodedLines += mode.imageRowsPerRadioLine
        }

        return Outcome.Success(
            Result(
                mode = mode,
                width = mode.linePixels,
                height = mode.imageLines,
                pixels = pixels,
                hedrShiftHz = effectiveShiftHz,
                slantRatio = fit.slantRatio,
                syncPulseCount = fit.pulseCount,
                decodedLines = decodedLines.coerceAtMost(mode.imageLines),
            ),
        )
    }

    /** 跨行保持的色度平面（Robot 24/36 的行交替色度需要上一行的分量）。 */
    private var chromaCr = IntArray(0)
    private var chromaCb = IntArray(0)

    /**
     * 上一行判定出的奇偶（Robot 24/36）。
     *
     * 标记脉冲模糊或落在两个候选之间时沿用上一行的结果 —— 行奇偶只会在帧边界变化，
     * 逐行独立猜测反而更容易在弱信号下抖动（robot36 用「toggle」达到类似效果）。
     */
    private var lastEvenParity = true

    /** 通道电平流的中间缓冲（避免每行、每通道都新建数组）。 */
    private var levelScratch = FloatArray(0)

    /**
     * 重置帧级状态（行奇偶与 Robot 跨行色度）。
     *
     * 每次解码都是一段独立的音频流，这些状态必须重新起算：否则上一段音频的判定
     * 会污染这一段 —— 虽然行奇偶标记本身会立刻纠正，但第一行仍会带错色度。
     * 三期的 [SstvStreamDecoder.finalize] 复用一个解码器实例重解时也要调用它。
     */
    public fun resetFrameState() {
        lastEvenParity = true
        chromaCr = IntArray(0)
        chromaCb = IntArray(0)
    }

    /**
     * 解码一个无线行并写入 [pixels]。
     *
     * `internal` 而非 `private`：三期的 [SstvStreamDecoder] 需要**逐行**驱动解码。
     * 行解码里含三族色彩组装、Robot 行交替色度、PD 双行配对、Scottie 行首偏移，
     * 这些全部经过既有用例验证，重写等于放弃保障；而它唯一的输入是一个 [SstvSync.SyncFit]，
     * 「离线全局拟合」与「流式滚动拟合」都能造出来 —— 这个边界正是正确的复用点。
     *
     * @return 该行是否完整落在音频范围内；false 表示同步已丢失，调用方应停止。
     */
    public fun decodeRadioLine(
        freqs: FloatArray,
        fit: SstvSync.SyncFit,
        mode: SstvMode,
        line: Int,
        hedrShiftHz: Double,
        pixels: IntArray,
        validFrom: Int,
        effectiveRate: Double,
    ): Boolean {
        val width = mode.linePixels
        if (chromaCr.size != width) {
            chromaCr = IntArray(width) { SstvColor.CHROMA_OFFSET.toInt() }
            chromaCb = IntArray(width) { SstvColor.CHROMA_OFFSET.toInt() }
        }

        // 行首 + 群延迟补偿：频率流滞后于真实时间轴，故索引需后移
        // 优先用该行实际检测到的同步脉冲做逐行精调，抑制行间定位抖动
        val lineStart = fit.lineStartRefined(line, mode.syncSeconds * effectiveRate) +
            groupDelaySamples
        val tasks = mode.channelTasks()

        // 采样每个通道：行首 + 通道偏移 + 像素中心（时间基准为 slant 校正后的速率）
        val values = ArrayList<IntArray>(tasks.size)
        for (task in tasks) {
            val channelStart = lineStart + task.startSeconds * effectiveRate
            val pixelSamples = task.pixelSeconds * effectiveRate
            // 通道起点已越界（音频结束）或完全落在解调瞬态区时，判为同步丢失
            if (channelStart >= freqs.size - 1) return false
            if (channelStart + task.pixels * pixelSamples < validFrom) return false

            values.add(sampleChannel(freqs, channelStart, task, hedrShiftHz, effectiveRate))
        }

        when (mode.layout) {
            SstvChannelLayout.PD_FRAME -> {
                // Y(奇行) / Cr / Cb / Y(偶行)：色度由两个图像行共用
                val yOdd = values[0]
                val cr = values[1]
                val cb = values[2]
                val yEven = values[3]
                val row0 = line * mode.imageRowsPerRadioLine
                val row1 = row0 + 1
                for (x in 0 until width) {
                    pixels[row0 * width + x] = SstvColor.ycbcrToRgb(yOdd[x], cr[x], cb[x])
                    pixels[row1 * width + x] = SstvColor.ycbcrToRgb(yEven[x], cr[x], cb[x])
                }
            }

            SstvChannelLayout.ROBOT_YUV -> {
                val y = values[0]
                val row = line
                if (mode == SstvMode.ROBOT_72) {
                    // Y / U / V 三通道等宽，每行色度完整
                    val u = values[1]
                    val v = values[2]
                    for (x in 0 until width) {
                        pixels[row * width + x] = SstvColor.ycbcrToRgb(y[x], u[x], v[x])
                    }
                } else {
                    // Robot 24/36：每行只发一个色度分量，另一分量沿用上一行。
                    // **奇偶来自分隔脉冲本身**，而不是行序号 —— 录音从奇数行开始时，
                    // 行序号 0 实际是奇数行，按序号推断会让整幅图红蓝互换
                    val chroma = values[1]
                    if (resolveLineParity(freqs, mode, lineStart, hedrShiftHz, effectiveRate, line)) {
                        chroma.copyInto(chromaCr, 0, 0, width)
                    } else {
                        chroma.copyInto(chromaCb, 0, 0, width)
                    }
                    for (x in 0 until width) {
                        pixels[row * width + x] =
                            SstvColor.ycbcrToRgb(y[x], chromaCr[x], chromaCb[x])
                    }
                }
            }

            SstvChannelLayout.RGB_SEQUENTIAL -> {
                // **按通道角色取值**，而不是按下标：Martin/Scottie 的发顺序是 G→B→R，
                // Wraase SC2-180 是 R→G→B，按下标取会把红蓝（乃至绿色）接错
                val order = mode.rgbOrder.roles
                val red = values[order.indexOf(SstvChannelRole.RED)]
                val green = values[order.indexOf(SstvChannelRole.GREEN)]
                val blue = values[order.indexOf(SstvChannelRole.BLUE)]
                val row = line
                for (x in 0 until width) {
                    pixels[row * width + x] = SstvColor.pack(red[x], green[x], blue[x])
                }
            }

            SstvChannelLayout.MONO -> {
                // Robot B/W：单通道即灰度，直接写回 R=G=B（不做任何色彩空间转换）
                val mono = values[0]
                val row = line
                for (x in 0 until width) {
                    val level = mono[x]
                    pixels[row * width + x] = SstvColor.pack(level, level, level)
                }
            }
        }
        return true
    }

    /**
     * 判定当前无线行的奇偶（Robot 24/36）：真 = 偶行（该行的色度通道是 Cr）。
     *
     * 判据是分隔脉冲的频率（偶行 1500 Hz / 奇行 2300 Hz）。只有当**明显更像**其中一个
     * 候选时才采信，否则沿用上一行的判定 —— 弱信号下脉冲会被噪声抹平，硬猜每行的奇偶
     * 会让红蓝通道随机翻转，画面上表现为逐行色斑。
     */
    private fun resolveLineParity(
        freqs: FloatArray,
        mode: SstvMode,
        lineStart: Double,
        hedrShiftHz: Double,
        effectiveRate: Double,
        line: Int,
    ): Boolean {
        val marker = mode.parityMarker ?: return line % 2 == 0
        val center = lineStart + (marker.startSeconds + marker.seconds / 2.0) * effectiveRate
        val half = maxOf(1.0, marker.seconds * effectiveRate * SAMPLE_WINDOW_RATIO)
        val measured = sampleMeanHz(freqs, center, half) - hedrShiftHz

        val evenError = kotlin.math.abs(measured - marker.evenHz)
        val oddError = kotlin.math.abs(measured - marker.oddHz)
        val decided = when {
            evenError + PARITY_MARGIN_HZ < oddError -> true
            oddError + PARITY_MARGIN_HZ < evenError -> false
            else -> lastEvenParity
        }
        lastEvenParity = decided
        return decided
    }

    /**
     * 把一个通道采成 0–255 的电平序列。
     *
     * 两条路径：
     *  - **像素 Nyquist 低通**（默认）：先把通道覆盖的样本转成频率流，做**零相位**
     *    （前向 + 反向）二阶 EMA，再在像素中点取值。截止频率恰为像素 Nyquist —— 即
     *    「一个像素能表达的最高横向频率」，因此只滤掉不可能还原的成分，不会牺牲真实细节。
     *    前向+反向是关键：单向滤波会把整行内容沿时间轴平移，表现为水平错位。
     *  - **关闭时**退回一期的「像素中点 30% 窗平均」，便于 A/B 对比与回归。
     *
     * 该技术参照 robot36（xdsopl/robot36，0BSD）的 `ExponentialMovingAverage` +
     * `cutoff(horizontalPixels, 2 * channelSamples, 2)` 用法实现。
     */
    private fun sampleChannel(
        freqs: FloatArray,
        channelStart: Double,
        task: SstvChannelTask,
        hedrShiftHz: Double,
        effectiveRate: Double,
    ): IntArray {
        val pixelSamples = task.pixelSeconds * effectiveRate
        val arr = IntArray(task.pixels)

        if (!pixelNyquistFilter) {
            for (x in 0 until task.pixels) {
                val center = channelStart + (x + 0.5) * pixelSamples
                arr[x] = sampleLevel(freqs, center, pixelSamples, hedrShiftHz)
            }
            return arr
        }

        val count = (task.pixels * pixelSamples).toInt().coerceAtLeast(task.pixels * 2)
        ensureLevelScratch(count)
        val from = channelStart.toInt()
        var lastFinite = SstvMode.BLACK_HZ.toFloat()
        for (i in 0 until count) {
            val index = (from + i).coerceIn(0, freqs.size - 1)
            val value = freqs[index]
            // 非有限值用前一个样本顶替，避免一个野值经 IIR 扩散到整行
            if (value.isFinite()) lastFinite = value
            levelScratch[i] = lastFinite
        }
        applyZeroPhaseLowPass(levelScratch, count, task.pixels)

        for (x in 0 until task.pixels) {
            val center = (x + 0.5) * pixelSamples
            arr[x] = sampleLevelFromStream(levelScratch, count, center, pixelSamples, hedrShiftHz)
        }
        return arr
    }

    private fun ensureLevelScratch(required: Int) {
        if (levelScratch.size < required) levelScratch = FloatArray(required)
    }

    /**
     * 零相位二阶 EMA 低通，截止频率 = **像素 Nyquist**。
     *
     * 截止频率 `f = pixels`、采样率 `rate = 2 × channelSamples` 时
     * `f/rate = pixels / (2·pixels·pixelSamples) = 0.5 / pixelSamples`，
     * 正是像素周期的 Nyquist —— 与 robot36 传参 `cutoff(horizontalPixels, 2 * channelSamples, 2)` 等价。
     *
     * `order = 2` 在 robot36 的实现里是把一阶 alpha 开平方后**跑两遍**（前向 + 反向），
     * 因此总响应约等于一阶、但相位为线性。反向那一遍刻意用最后一个样本初始化 `prev`
     * 而不是 0 —— robot36 用 0 初始化会把最右侧若干像素压暗（这是本实现的一处改进）。
     */
    private fun applyZeroPhaseLowPass(buffer: FloatArray, count: Int, pixels: Int) {
        if (count < 4 || pixels <= 0) return
        val normalized = (pixels.toDouble() / (2.0 * count)).coerceIn(0.0, 0.5)
        if (normalized <= 0.0) return
        val x = kotlin.math.cos(2.0 * PI * normalized)
        val single = x - 1.0 + kotlin.math.sqrt(x * (x - 4.0) + 3.0)
        if (single <= 0.0) return
        val alpha = kotlin.math.sqrt(single).toFloat()

        var prev = buffer[0]
        for (i in 0 until count) {
            val value = buffer[i]
            prev += alpha * (value - prev)
            buffer[i] = prev
        }
        prev = buffer[count - 1]
        for (i in count - 1 downTo 0) {
            val value = buffer[i]
            prev += alpha * (value - prev)
            buffer[i] = prev
        }
    }

    /**
     * 在 [centerSample] 附近取一个小窗求平均频率，并映射为电平（0–255）。
     *
     * 取窗平均而非单点采样是抗噪的关键；窗宽取像素时长的 60%，避免跨越相邻像素。
     * 频率映射前先减去 `hedrShiftHz`，否则整幅图像会偏亮或偏暗。
     */
    public fun sampleLevel(
        freqs: FloatArray,
        centerSample: Double,
        pixelSamples: Double,
        hedrShiftHz: Double,
    ): Int {
        val half = maxOf(1, (pixelSamples * SAMPLE_WINDOW_RATIO).toInt())
        val from = (centerSample - half).toInt().coerceIn(0, freqs.size - 1)
        val to = (centerSample + half).toInt().coerceIn(from + 1, freqs.size)

        var sum = 0.0
        for (i in from until to) sum += freqs[i]
        val meanHz = sum / (to - from)

        val nominalHz = meanHz - hedrShiftHz
        val level = (nominalHz - SstvMode.BLACK_HZ) /
            (SstvMode.WHITE_HZ - SstvMode.BLACK_HZ) * 255.0
        return level.roundToInt().coerceIn(0, 255)
    }

    /** 在**已低通**的电平流上按像素中点取值（位置相对通道起点）。 */
    private fun sampleLevelFromStream(
        buffer: FloatArray,
        count: Int,
        centerSample: Double,
        pixelSamples: Double,
        hedrShiftHz: Double,
    ): Int {
        val half = maxOf(1.0, pixelSamples * SAMPLE_WINDOW_RATIO)
        val from = (centerSample - half).toInt().coerceIn(0, count - 1)
        val to = (centerSample + half).toInt().coerceIn(from + 1, count)
        var sum = 0.0
        for (i in from until to) sum += buffer[i]
        val meanHz = sum / (to - from)
        val level = (meanHz - hedrShiftHz - SstvMode.BLACK_HZ) /
            (SstvMode.WHITE_HZ - SstvMode.BLACK_HZ) * 255.0
        return level.roundToInt().coerceIn(0, 255)
    }

    /** 在 [centerSample] 附近取窗求平均频率（Hz）。用于奇偶判定等「读频率」的场合。 */
    private fun sampleMeanHz(freqs: FloatArray, centerSample: Double, halfSamples: Double): Double {
        val half = maxOf(1.0, halfSamples)
        val from = (centerSample - half).toInt().coerceIn(0, freqs.size - 1)
        val to = (centerSample + half).toInt().coerceIn(from + 1, freqs.size)
        var sum = 0.0
        for (i in from until to) sum += freqs[i]
        return sum / (to - from)
    }

    companion object {
        /** 像素采样窗占像素时长的比例（取中部，避开边界过渡）。 */
        private const val SAMPLE_WINDOW_RATIO = 0.3

        /**
         * 采用「同步脉冲实测频率」修正失谐量所需的最少脉冲数。
         *
         * 脉冲足够多时其平均频率的统计误差可忽略，远比 VIS leader 的单次估计可靠。
         */
        private const val MIN_PULSES_FOR_SHIFT_REFINE = 8

        /**
         * 奇偶判定的最小置信余量（Hz）。
         *
         * 两个候选频率相差 800 Hz（1500 vs 2300），因此要求实测值「离中点多近」是可调项：
         * 取 200 Hz 表示实测值必须落在正确候选 ±200 Hz 内、且离错误候选 ≥ 600 Hz 才采信。
         * 这个余量在 SNR 20 dB 下实测足够（噪声约 ±24 Hz），同时能挡住「采到图像内容」
         * 这种完全跑偏的情况。
         */
        private const val PARITY_MARGIN_HZ = 200.0
    }
}
