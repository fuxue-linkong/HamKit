package com.example.hamkit.data.sstv

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
 */
class SstvDecoder(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    private val groupDelaySamples: Double = 0.0,
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
        val mode = forcedMode ?: visOutcome?.mode
        ?: return Outcome.Failure("未识别到 VIS 头，请手动选择模式")
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
     * 解码一个无线行并写入 [pixels]。
     *
     * @return 该行是否完整落在音频范围内；false 表示同步已丢失，调用方应停止。
     */
    private fun decodeRadioLine(
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

            val arr = IntArray(task.pixels)
            for (x in 0 until task.pixels) {
                val center = channelStart + (x + 0.5) * pixelSamples
                // 末尾越界由 sampleLevel 内部钳制，避免因最后几个样本缺失而丢掉整行
                arr[x] = sampleLevel(freqs, center, pixelSamples, hedrShiftHz)
            }
            values.add(arr)
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
                    // Robot 24/36：每行只发一个色度分量，另一分量沿用上一行
                    val chroma = values[1]
                    if (row % 2 == 0) {
                        System.arraycopy(chroma, 0, chromaCr, 0, width)
                    } else {
                        System.arraycopy(chroma, 0, chromaCb, 0, width)
                    }
                    for (x in 0 until width) {
                        pixels[row * width + x] =
                            SstvColor.ycbcrToRgb(y[x], chromaCr[x], chromaCb[x])
                    }
                }
            }

            SstvChannelLayout.RGB_SEQUENTIAL -> {
                // Martin：发送顺序为 G → B → R
                val green = values[0]
                val blue = values[1]
                val red = values[2]
                val row = line
                for (x in 0 until width) {
                    pixels[row * width + x] = SstvColor.pack(red[x], green[x], blue[x])
                }
            }
        }
        return true
    }

    /**
     * 在 [centerSample] 附近取一个小窗求平均频率，并映射为电平（0–255）。
     *
     * 取窗平均而非单点采样是抗噪的关键；窗宽取像素时长的 60%，避免跨越相邻像素。
     * 频率映射前先减去 `hedrShiftHz`，否则整幅图像会偏亮或偏暗。
     */
    private fun sampleLevel(
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

    companion object {
        /** 像素采样窗占像素时长的比例（取中部，避开边界过渡）。 */
        private const val SAMPLE_WINDOW_RATIO = 0.3

        /**
         * 采用「同步脉冲实测频率」修正失谐量所需的最少脉冲数。
         *
         * 脉冲足够多时其平均频率的统计误差可忽略，远比 VIS leader 的单次估计可靠。
         */
        private const val MIN_PULSES_FOR_SHIFT_REFINE = 8
    }
}
