package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * 盲扫（VIS 缺失时的模式判定）单元测试。
 *
 * 盲扫是启发式的，因此这里刻意**分开验证两条判据**而不是只断言最终模式：
 *  - 失谐量估计（频率直方图）：可精确断言，是「无 VIS 时仍能对齐」的前提；
 *  - 模式判定（行同步间隔 + 试解码平整度）：断言模式，并对**行周期相同**的模式
 *    断言「候选中包含彼此」而不是二选一 —— 那是物理上不可分的，UI 也因此必须
 *    展示候选列表让用户切换。
 */
class SstvBlindScanTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    @Test
    fun `无 VIS 头时能判定常见模式`() {
        // 这几族的行周期彼此相差都超过 5%（拟合斜率闸门），因此可以唯一判定；
        // 内容用横向渐变（而非纯色）—— 纯色图像对「时序是否错位」不敏感，
        // 会让平整度判据失效，进而掩盖真正的判定错误
        val expected = listOf(
            SstvMode.ROBOT_8_BW,
            SstvMode.MARTIN_2,
            SstvMode.MARTIN_1,
            SstvMode.SCOTTIE_2,
            SstvMode.SCOTTIE_1,
            SstvMode.PD_50,
        )
        for (mode in expected) {
            val audio = encodeWindow(mode, gradient(mode), includeVis = false)
            val result = SstvBlindScan.scan(audio, sampleRate)
            assertNotNull("${mode.displayName} 应产生候选：${result.reason}", result.best)
            assertEquals(
                "${mode.displayName} 盲扫判定错误（候选：" +
                    result.candidates.take(3).joinToString { c -> "${c.mode.displayName}=${"%.2f".format(c.score)}" } +
                    "）",
                mode,
                result.best!!.mode,
            )
        }
    }

    @Test
    fun `结构判据能区分同步位置不同的同族近邻`() {
        // Martin 1（446.446 ms）与 Scottie 1（428.38 ms）只差 4.2%，落在拟合斜率
        // 闸门（0.95–1.05）之内，靠行周期分不开。二者的差别在**间隙在行内的分布**：
        // Martin 三个间隙都在同步之后，Scottie 有两个在同步之前。
        val martinAudio = encodeWindow(SstvMode.MARTIN_1, gradient(SstvMode.MARTIN_1), includeVis = false)
        val scottieAudio = encodeWindow(SstvMode.SCOTTIE_1, gradient(SstvMode.SCOTTIE_1), includeVis = false)

        val martinScore = structureScoreOf(SstvMode.MARTIN_1, martinAudio)
        val scottieOnMartin = structureScoreOf(SstvMode.SCOTTIE_1, martinAudio)
        assertTrue(
            "Martin 数据上，Martin 模型的结构分 $martinScore 应明显高于 Scottie 模型 $scottieOnMartin",
            martinScore > scottieOnMartin + 0.2,
        )

        val scottieScore = structureScoreOf(SstvMode.SCOTTIE_1, scottieAudio)
        val martinOnScottie = structureScoreOf(SstvMode.MARTIN_1, scottieAudio)
        assertTrue(
            "Scottie 数据上，Scottie 模型的结构分 $scottieScore 应明显高于 Martin 模型 $martinOnScottie",
            scottieScore > martinOnScottie + 0.2,
        )
    }

    @Test
    fun `行周期相同的黑白模式都出现在候选列表里`() {
        // Robot 12 B/W 与 Robot 24 B/W 的行周期与像素时长完全相同（都是 100 ms/行），
        // 只有图像行数不同 —— 靠音频在原理上分不开，因此必须两个都给出
        val mode = SstvMode.ROBOT_24_BW
        val audio = encodeWindow(mode, gradient(mode), includeVis = false)
        val result = SstvBlindScan.scan(audio, sampleRate)
        assertNotNull(result.best)

        val names = result.candidates.map { it.mode }
        assertTrue("候选中应包含 Robot 24 B/W", names.contains(SstvMode.ROBOT_24_BW))
        assertTrue("候选中应包含同周期的 Robot 12 B/W", names.contains(SstvMode.ROBOT_12_BW))
        // 判定出的行周期必须与真实值一致（这是可断言的硬指标）
        assertEquals(
            "拟合行周期比应符合标称",
            1.0,
            result.best!!.slantRatio,
            0.01,
        )
    }

    @Test
    fun `带 VIS 头时也能判定`() {
        val mode = SstvMode.ROBOT_8_BW
        val audio = encodeWindow(mode, gradient(mode), includeVis = true)
        val result = SstvBlindScan.scan(audio, sampleRate)
        assertNotNull("带 VIS 的音频也应能盲扫（跳过 VIS 头）", result.best)
        assertEquals(mode, result.best!!.mode)
    }

    @Test
    fun `失谐量估计可纠正 VIS 缺失时的频率偏移`() {
        // VIS 原本是失谐量的唯一来源；VIS 没了以后必须从同步脉冲反推，
        // 否则同步脉冲会落在 1200 Hz 容差之外，整帧都解不出来
        val offset = 45.0
        val mode = SstvMode.ROBOT_8_BW
        val audio = encodeWindow(mode, gradient(mode), includeVis = false, freqOffsetHz = offset)
        val result = SstvBlindScan.scan(audio, sampleRate)
        assertNotNull("带失谐的音频应能判定", result.best)
        assertEquals(mode, result.best!!.mode)
        assertTrue(
            "失谐估计 ${result.best!!.measuredShiftHz} Hz 应接近 $offset Hz",
            abs(result.best!!.measuredShiftHz - offset) < 20.0,
        )
    }

    @Test
    fun `盲扫判定后可据此正确解码`() {
        // 端到端：无 VIS → 盲扫 → 用判定出的模式解码 → 图像可辨认
        val mode = SstvMode.ROBOT_8_BW
        val image = gradient(mode)
        val audio = SstvEncoder(sampleRate).encode(image, mode, includeVis = false)

        val result = SstvBlindScan.scan(audio, sampleRate)
        assertNotNull(result.best)
        val decoded = SstvDecoder(sampleRate).decode(audio, forcedMode = result.best!!.mode)
        assertTrue(
            "按盲扫结果解码失败：${(decoded as? SstvDecoder.Outcome.Failure)?.reason}",
            decoded is SstvDecoder.Outcome.Success,
        )
        val outcome = (decoded as SstvDecoder.Outcome.Success).result
        assertTrue(
            "应解出接近整帧的行数，实际 ${outcome.decodedLines}/${mode.imageLines}",
            outcome.decodedLines >= mode.imageLines - 2,
        )
        val psnr = psnr(image, outcome)
        assertTrue("盲扫后解码 PSNR $psnr dB 过低", psnr > 30.0)
    }

    @Test
    fun `音频过短时给出明确原因`() {
        val result = SstvBlindScan.scan(FloatArray(sampleRate), sampleRate)
        assertTrue("过短音频不应给出候选", !result.usable)
        assertNotNull(result.reason)
        assertTrue(
            "原因应指明音频过短：${result.reason}",
            result.reason!!.contains("过短"),
        )
    }

    @Test
    fun `纯噪声不会串出同步脉冲串`() {
        // 均匀白噪声没有周期性，贪心串联应当失败或只串出极少数脉冲
        val noise = FloatArray(sampleRate * 8) { i ->
            val x = ((i * 2654435761L) ushr 8).toInt() % 20000
            (x / 20000.0 * 0.6 - 0.3).toFloat()
        }
        val result = SstvBlindScan.scan(noise, sampleRate)
        assertTrue(
            "噪声不应判定出可用模式（得分 ${result.best?.score}）",
            result.best == null || result.best!!.pulseCount < 20,
        )
    }

    @Test
    fun `失谐量直方图在无同步内容时返回零`() {
        val freqs = FloatArray(sampleRate) { 1800f }
        assertEquals(0.0, SstvBlindScan.estimateSyncShift(freqs, sampleRate, 0), 1e-9)
    }

    @Test
    fun `失谐量直方图能读出恒定同步频率`() {
        // 1500 次/秒的 1245 Hz 采样（对应 +45 Hz 失谐）
        val freqs = FloatArray(sampleRate) { 1245f }
        assertEquals(45.0, SstvBlindScan.estimateSyncShift(freqs, sampleRate, 0), 1e-9)
    }

    @Test
    fun `横向粗糙度对错位图像显著变大`() {
        // 时序正确 → 行内平滑；把行内容整体平移半个行周期 → 采样点跨越通道边界
        val width = 320
        val height = 8
        val smooth = IntArray(width * height) { index ->
            val x = index % width
            val v = (x * 255 / width)
            (v shl 16) or (v shl 8) or v
        }
        val rough = IntArray(width * height) { index ->
            val x = index % width
            val v = if (x % 2 == 0) 0 else 255
            (v shl 16) or (v shl 8) or v
        }
        val smoothScore = SstvBlindScan.horizontalRoughness(smooth, width, height, height)
        val roughScore = SstvBlindScan.horizontalRoughness(rough, width, height, height)
        assertTrue(
            "平滑图像的粗糙度 $smoothScore 应远小于错位图像 $roughScore",
            roughScore > smoothScore * 10,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /**
     * 只生成够盲扫用的那一小段音频。
     *
     * 盲扫只看开头 [SstvBlindScan.DEFAULT_WINDOW_SECONDS] 秒（+1 秒跳过量），
     * 因此没必要为 Martin 1（114 秒）这类慢模式编码整帧 —— 那会让单条用例多做
     * 十几倍的无用功。
     */
    private fun encodeWindow(
        mode: SstvMode,
        image: SstvImageSource,
        includeVis: Boolean,
        freqOffsetHz: Double = 0.0,
    ): FloatArray {
        val seconds = SstvBlindScan.DEFAULT_WINDOW_SECONDS + 1.0
        val lines = (seconds / mode.lineSeconds).toInt() + 2
        val chunks = SstvEncoder(sampleRate)
            .encodeChunks(image, mode, freqOffsetHz = freqOffsetHz, includeVis = includeVis)
            .take(lines + 1)
            .toList()
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /** 对给定音频跑一次同步拟合，返回某候选模式的结构一致性得分。 */
    private fun structureScoreOf(mode: SstvMode, audio: FloatArray): Double {
        val demodulator = SstvDemodulator(
            sampleRate = sampleRate,
            phaseSpan = SstvDemodulator.recommendPhaseSpan(
                SstvMode.entries.map { it.pixelSeconds * sampleRate }.sorted()[SstvMode.entries.size / 2],
            ),
        )
        val freqs = demodulator.demodulate(audio)
        val searchFrom = (SstvBlindScan.DEFAULT_SKIP_SECONDS * sampleRate).toInt()
            .coerceAtLeast(demodulator.transientSamples)
        val shift = SstvBlindScan.estimateSyncShift(freqs, sampleRate, searchFrom)
        val fit = SstvSync.findSyncPulses(freqs, mode, sampleRate, shift, searchFrom)
            ?: return 0.0
        return SstvBlindScan.gapStructureScore(
            freqs = freqs,
            mode = mode,
            fit = fit,
            sampleRate = sampleRate,
            shiftHz = fit.measuredShiftHz,
            maxLines = 40,
        )
    }

    private fun gradient(mode: SstvMode): SstvImageSource {
        val w = mode.linePixels
        val h = mode.imageLines
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = (127.5 + 127.5 * sin(2.0 * PI * x / w)).toInt().coerceIn(0, 255)
                px[y * w + x] = (v shl 16) or (v shl 8) or v
            }
        }
        return SstvPixelImageSource(w, h, px)
    }

    private fun psnr(source: SstvImageSource, result: SstvDecoder.Result): Double {
        var sum = 0.0
        var count = 0
        for (y in 2 until result.height - 2) {
            for (x in 4 until result.width - 4) {
                val a = source.rgbAt(x, y)
                val b = result.pixels[y * result.width + x]
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = (((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)).toDouble()
                    sum += d * d
                    count++
                }
            }
        }
        val mse = sum / count
        return if (mse <= 1e-9) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }
}
