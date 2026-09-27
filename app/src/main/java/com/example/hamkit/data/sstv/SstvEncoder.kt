package com.example.hamkit.data.sstv

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 编码器的图像输入抽象。
 *
 * 刻意不依赖 Android 的 `Bitmap`，使编码器与解码器一样可在纯 JVM 单元测试中
 * 驱动 —— 这是「编解码自环」验证（PSNR 金标准）的前提。
 */
interface SstvImageSource {
    /** 图像宽度（像素）。 */
    val width: Int

    /** 图像高度（像素）。 */
    val height: Int

    /** 返回 (x, y) 处的打包 RGB（0xRRGGBB），坐标越界行为由实现决定。 */
    fun rgbAt(x: Int, y: Int): Int
}

/**
 * SSTV 编码器：把图像调制为 FM 音频（含 VIS 头）。
 *
 * **一期定位为测试基础设施**：编解码自环（编码 → 解码 → 与原图比对 PSNR）是
 * 唯一能脱离真实电波验证解码器正确性的手段（见 docs/REQUIREMENT_SSTV.md §5.3）。
 * 实际发射（AudioTrack 播放）复用同一份输出。
 *
 * 与解码器共用 [SstvMode.channelTasks] 的同一套通道时序，因此模式参数表是
 * 单点真相：解码端与编码端不可能出现时序漂移。
 *
 * 纯 Kotlin 实现，不依赖 Android API。
 *
 * @param sampleRate 输出采样率（Hz）
 */
class SstvEncoder(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
) {

    /** 当前振荡器相位，保证跨音调段连续（避免频谱泄漏）。 */
    private var phase = 0.0

    /**
     * 理论时间轴累计（秒）与已写入样本数。
     *
     * **必须用时间累加器而不是逐段 `roundToInt`**：PD-120 的单像素时长是
     * 190 µs（@48 kHz = 9.12 样本），若每个像素各自四舍五入为 9 样本，640 像素
     * 就会短掉 76.8 样本，累计到一行末端是 6.4 ms（约 1.3% 的行长）。接收端按标称
     * 时序采样时，图像会出现整行的水平错位（实测 PSNR 因此从 40+ dB 掉到 11.8 dB）。
     * 用累加器把舍入误差限制在 ±0.5 样本内、且不随像素数累积。
     */
    private var plannedSeconds = 0.0
    private var writtenSamples = 0

    /**
     * 编码一帧：VIS 头 + 全部无线行。
     *
     * @param source 图像源；尺寸与模式不一致时按最近邻缩放
     * @param mode 目标模式（仅支持 [SstvSyncPosition.LINE_START] 模式）
     * @param freqOffsetHz 整体频率偏移，用于模拟电台失谐 / 多普勒
     * @param includeVis 是否输出 VIS 头
     */
    fun encode(
        source: SstvImageSource,
        mode: SstvMode,
        freqOffsetHz: Double = 0.0,
        includeVis: Boolean = true,
    ): FloatArray {
        require(mode.syncPosition == SstvSyncPosition.LINE_START) {
            "暂不支持行中同步模式（Scottie）的编码：${mode.displayName}"
        }
        phase = 0.0
        plannedSeconds = 0.0
        writtenSamples = 0

        val writer = FloatWriter(estimateSampleCount(mode, includeVis))
        if (includeVis) writeVis(writer, mode.visCode, freqOffsetHz)

        val scaled = ScaledSource(source, mode)
        val tasks = mode.channelTasks()
        val blackHz = SstvMode.BLACK_HZ + freqOffsetHz
        val syncHz = SstvMode.VIS_BREAK_HZ + freqOffsetHz

        // 图像数据的理论起点（VIS 之后），用于按行号精确定位每行的时间终点
        val imageStartSeconds = plannedSeconds

        for (radioLine in 0 until mode.radioLines) {
            // 行首同步 + porch（porch 用黑电平，与主流实现一致）
            writeTone(writer, syncHz, mode.syncSeconds)
            writeTone(writer, blackHz, mode.porchSeconds)

            for ((index, task) in tasks.withIndex()) {
                for (x in 0 until task.pixels) {
                    val level = channelLevel(task.role, radioLine, x, scaled)
                    val freq = SstvMode.BLACK_HZ +
                        level / 255.0 * (SstvMode.WHITE_HZ - SstvMode.BLACK_HZ) +
                        freqOffsetHz
                    writeTone(writer, freq, task.pixelSeconds)
                }
                if (index != tasks.lastIndex) {
                    writeTone(writer, blackHz, mode.separatorSeconds)
                }
            }

            // 补齐到该行的理论终点：部分模式（如 Martin）在最后一个通道之后仍有
            // 分隔脉冲，而 channelTasks 只描述通道起点，不描述尾部。用行号推算目标
            // 时间可同时消除累计舍入误差。
            val lineEndSeconds = imageStartSeconds + (radioLine + 1) * mode.lineSeconds
            val remaining = lineEndSeconds - plannedSeconds
            if (remaining > 1e-9) {
                writeTone(writer, blackHz, remaining)
            }
        }
        return writer.toArray()
    }

    /** 仅生成 VIS 头（约 910 ms），供 VIS 检测的单元测试使用。 */
    fun encodeVis(visCode: Int, freqOffsetHz: Double = 0.0): FloatArray {
        phase = 0.0
        plannedSeconds = 0.0
        writtenSamples = 0
        val writer = FloatWriter((VIS_TOTAL_SECONDS * sampleRate).toInt() + 8)
        writeVis(writer, visCode, freqOffsetHz)
        return writer.toArray()
    }

    /** 估算编码一帧所需的样本数（用于预分配缓冲）。 */
    fun estimateSampleCount(mode: SstvMode, includeVis: Boolean = true): Int {
        val visSeconds = if (includeVis) VIS_TOTAL_SECONDS else 0.0
        return ((visSeconds + mode.frameSeconds + 0.05) * sampleRate).toInt() + 64
    }

    // ══════════════════════════════════════════════════════════════════
    // 内部实现
    // ══════════════════════════════════════════════════════════════════

    /**
     * 按通道角色取出该像素的电平值（0–255）。
     *
     * 各族的差异集中在这里：
     *  - PD：两个图像行共享 Cr/Cb（水平全分辨率、垂直 2:1）
     *  - Robot 24/36：按行奇偶只发送 Cr 或 Cb
     *  - Martin：直接取 G/B/R，不做色彩空间转换
     */
    private fun channelLevel(
        role: SstvChannelRole,
        radioLine: Int,
        x: Int,
        source: ScaledSource,
    ): Int = when (role) {
        SstvChannelRole.Y_ODD -> {
            val rgb = source.rgbAt(x, radioLine * 2)
            SstvColor.rgbToY(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        }

        SstvChannelRole.Y_EVEN -> {
            val rgb = source.rgbAt(x, radioLine * 2 + 1)
            SstvColor.rgbToY(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        }

        // PD 的 Cr/Cb 由相邻两行平均后共享（解码端两行也用同一组色度）
        SstvChannelRole.CR -> {
            val top = source.rgbAt(x, radioLine * 2)
            val bottom = source.rgbAt(x, radioLine * 2 + 1)
            (SstvColor.rgbToCr(SstvColor.redOf(top), SstvColor.greenOf(top), SstvColor.blueOf(top)) +
                SstvColor.rgbToCr(
                    SstvColor.redOf(bottom),
                    SstvColor.greenOf(bottom),
                    SstvColor.blueOf(bottom),
                )) / 2
        }

        SstvChannelRole.CB -> {
            val top = source.rgbAt(x, radioLine * 2)
            val bottom = source.rgbAt(x, radioLine * 2 + 1)
            (SstvColor.rgbToCb(SstvColor.redOf(top), SstvColor.greenOf(top), SstvColor.blueOf(top)) +
                SstvColor.rgbToCb(
                    SstvColor.redOf(bottom),
                    SstvColor.greenOf(bottom),
                    SstvColor.blueOf(bottom),
                )) / 2
        }

        SstvChannelRole.Y -> {
            val rgb = source.rgbAt(x, radioLine)
            SstvColor.rgbToY(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        }

        // Robot 24/36：偶行发 Cr、奇行发 Cb（解码端另一分量复制自上一行）
        SstvChannelRole.CHROMA -> {
            val rgb = source.rgbAt(x, radioLine)
            val r = SstvColor.redOf(rgb)
            val g = SstvColor.greenOf(rgb)
            val b = SstvColor.blueOf(rgb)
            if (radioLine % 2 == 0) SstvColor.rgbToCr(r, g, b) else SstvColor.rgbToCb(r, g, b)
        }

        SstvChannelRole.U -> {
            val rgb = source.rgbAt(x, radioLine)
            SstvColor.rgbToCr(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        }

        SstvChannelRole.V -> {
            val rgb = source.rgbAt(x, radioLine)
            SstvColor.rgbToCb(SstvColor.redOf(rgb), SstvColor.greenOf(rgb), SstvColor.blueOf(rgb))
        }

        SstvChannelRole.GREEN -> SstvColor.greenOf(source.rgbAt(x, radioLine))
        SstvChannelRole.BLUE -> SstvColor.blueOf(source.rgbAt(x, radioLine))
        SstvChannelRole.RED -> SstvColor.redOf(source.rgbAt(x, radioLine))
    }

    /** 写入 VIS 头：leader / break / leader + 起始位 + 7 数据位 + 偶校验 + 停止位。 */
    private fun writeVis(writer: FloatWriter, visCode: Int, freqOffsetHz: Double) {
        val leader = SstvMode.VIS_LEADER_HZ + freqOffsetHz
        val separator = SstvMode.VIS_BREAK_HZ + freqOffsetHz
        val bitOne = SstvMode.VIS_BIT_ONE_HZ + freqOffsetHz
        val bitZero = SstvMode.VIS_BIT_ZERO_HZ + freqOffsetHz

        writeTone(writer, leader, 0.300)
        writeTone(writer, separator, 0.010)
        writeTone(writer, leader, 0.300)
        writeTone(writer, separator, 0.030) // 起始位

        var parity = 0
        for (b in 0 until 7) {
            val bit = (visCode shr b) and 1 // LSB 先传
            parity = parity xor bit
            writeTone(writer, if (bit == 1) bitOne else bitZero, 0.030)
        }
        // Robot 12 B/W 使用奇校验（与解码端保持一致）
        val parityBit = if (visCode == R12BW_VIS_CODE) parity xor 1 else parity
        writeTone(writer, if (parityBit == 1) bitOne else bitZero, 0.030)

        writeTone(writer, separator, 0.030) // 停止位
    }

    /** 以恒定频率写入 [seconds] 时长的正弦（相位跨段连续）。 */
    private fun writeTone(writer: FloatWriter, freqHz: Double, seconds: Double) {
        if (seconds <= 0.0) return
        plannedSeconds += seconds
        val targetSamples = (plannedSeconds * sampleRate).roundToInt()
        val count = targetSamples - writtenSamples
        if (count <= 0) return

        val step = TWO_PI * freqHz / sampleRate
        writer.ensure(count)
        var p = phase
        repeat(count) {
            writer.writeUnchecked(sin(p).toFloat())
            p += step
        }
        phase = p
        writtenSamples = targetSamples
    }

    /**
     * 把任意尺寸的图像源映射到模式的分辨率（最近邻）。
     *
     * 用最近邻而非双线性，是为了让自环测试的误差只来自 FM 链路本身，
     * 不混入插值算法的额外失真。
     */
    private class ScaledSource(private val source: SstvImageSource, mode: SstvMode) {
        private val sourceWidth = source.width
        private val sourceHeight = source.height
        private val scaleX = sourceWidth.toDouble() / mode.linePixels
        private val scaleY = sourceHeight.toDouble() / mode.imageLines

        fun rgbAt(x: Int, y: Int): Int {
            val px = (x * scaleX).toInt().coerceIn(0, sourceWidth - 1)
            val py = (y * scaleY).toInt().coerceIn(0, sourceHeight - 1)
            return source.rgbAt(px, py)
        }
    }

    /** 可增长的 Float 缓冲，避免 `MutableList<Float>` 的装箱开销。 */
    private class FloatWriter(initialCapacity: Int) {
        private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
        private var size = 0

        fun ensure(count: Int) {
            if (size + count <= data.size) return
            var newSize = data.size
            while (newSize < size + count) newSize = newSize shl 1
            data = data.copyOf(newSize)
        }

        fun writeUnchecked(value: Float) {
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val TWO_PI = 2.0 * PI

        /** VIS 头总时长：300 + 10 + 300 + 30 + 8×30 + 30 ms。 */
        const val VIS_TOTAL_SECONDS = 0.910

        /** Robot 12 B/W 的 VIS 码（奇校验）。 */
        private const val R12BW_VIS_CODE = 0x06
    }
}

/**
 * 把打包 RGB 像素数组适配为 [SstvImageSource]（便于测试与 Android 侧复用）。
 *
 * @param pixels 行优先的打包 RGB（0xRRGGBB）数组，长度须为 `width * height`
 */
class SstvPixelImageSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : SstvImageSource {

    init {
        require(pixels.size >= width * height) {
            "像素数组长度 ${pixels.size} 小于 $width × $height"
        }
    }

    override fun rgbAt(x: Int, y: Int): Int = pixels[y * width + x]
}
