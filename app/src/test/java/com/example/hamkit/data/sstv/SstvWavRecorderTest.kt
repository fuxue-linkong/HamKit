package com.example.hamkit.data.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * 流式 WAV 录音器（[SstvWavRecorder]）单元测试。
 *
 * 核心验收是**闭环**：把一帧 SSTV 音频按接收时的分块方式流式录下来，再喂回
 * [SstvWavReader] + [SstvDecoder] —— 只有这条路径成立，「录下过境 → 事后重解」
 * 才不是空话。其余用例覆盖头部回填、重复关闭、越界 count 等边界。
 */
class SstvWavRecorderTest {

    private val sampleRate = SstvVisDetector.DEFAULT_SAMPLE_RATE

    @Test
    fun `流式录音可被读取器原样读回`() {
        val tone = FloatArray(sampleRate / 2) { sin(2.0 * PI * 1500.0 * it / sampleRate).toFloat() }
        val pcm = ShortArray(tone.size) { SstvWavWriter.toPcm16(tone[it]) }
        val file = tempFile("recorder_roundtrip")

        try {
            val recorder = SstvWavRecorder(file, sampleRate)
            assertEquals(null, recorder.open())
            // 刻意用「不整除」的分块，复现实时接收里 500 ms 一块、最后一块不满的情形
            var offset = 0
            val chunk = 7_000
            while (offset < pcm.size) {
                val count = minOf(chunk, pcm.size - offset)
                recorder.append(pcm, offset, count)
                offset += count
            }
            val samples = recorder.close()

            assertEquals(tone.size.toLong(), samples)
            assertEquals(
                "文件长度应为头部 + 样本字节数",
                SstvWavWriter.HEADER_BYTES + pcm.size * 2L,
                file.length(),
            )

            val outcome = SstvWavReader.read(file)
            assertTrue(
                "录音应能被读回：${(outcome as? SstvWavReader.Outcome.Failure)?.reason}",
                outcome is SstvWavReader.Outcome.Success,
            )
            val read = (outcome as SstvWavReader.Outcome.Success).samples
            assertEquals(pcm.size, read.size)
            for (i in tone.indices step 331) {
                assertTrue(
                    "第 $i 个样本偏差 ${abs(tone[i] - read[i])} 超出 16 位量化范围",
                    abs(tone[i] - read[i]) < 2.0 / 32768,
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `录制一帧音频后可以重新解码出原图`() {
        // 「录下过境 → 事后重解」的端到端验收。用 Robot 8 B/W（8 秒）以控制测试耗时
        val mode = SstvMode.ROBOT_8_BW
        val image = SstvPixelImageSource(
            mode.linePixels,
            mode.imageLines,
            IntArray(mode.linePixels * mode.imageLines) { index ->
                val x = index % mode.linePixels
                val v = (x * 255 / (mode.linePixels - 1))
                (v shl 16) or (v shl 8) or v
            },
        )
        val audio = SstvEncoder(sampleRate).encode(image, mode)
        val pcm = ShortArray(audio.size) { SstvWavWriter.toPcm16(audio[it]) }
        val file = tempFile("recorder_redecode")

        try {
            val recorder = SstvWavRecorder(file, sampleRate)
            recorder.open()
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(sampleRate / 2, pcm.size - offset)
                recorder.append(pcm, offset, count)
                offset += count
            }
            recorder.close()

            val outcome = SstvWavReader.read(file)
            assertTrue(outcome is SstvWavReader.Outcome.Success)
            val decoded = SstvDecoder(sampleRate)
                .decode((outcome as SstvWavReader.Outcome.Success).samples)
            assertTrue(
                "录制后重解失败：${(decoded as? SstvDecoder.Outcome.Failure)?.reason}",
                decoded is SstvDecoder.Outcome.Success,
            )
            val result = (decoded as SstvDecoder.Outcome.Success).result
            assertEquals(mode, result.mode)
            assertEquals(mode.imageLines, result.decodedLines)

            val psnr = psnr(image, result)
            assertTrue("录制后重解的 PSNR $psnr dB 过低", psnr > 30.0)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `重复 close 是空操作`() {
        val file = tempFile("recorder_double_close")
        try {
            val recorder = SstvWavRecorder(file, sampleRate)
            recorder.open()
            recorder.append(ShortArray(1000) { 100 })
            val first = recorder.close()
            val second = recorder.close()
            assertEquals(1000L, first)
            assertEquals("第二次 close 不应改变结果", first, second)
            // 1000 个样本只有 0.02 秒，读取器会以「过短」拒绝——但文件本身必须结构完整，
            // 也就是说失败原因必须是「过短」而不是「缺少 data 块 / 头损坏」
            val reopened = SstvWavReader.read(file)
            assertTrue(
                "第二次 close 后文件仍应是结构完整的 WAV，实际 $reopened",
                reopened is SstvWavReader.Outcome.Success ||
                    (reopened as? SstvWavReader.Outcome.Failure)?.reason?.contains("过短") == true,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `未打开时 append 不抛异常`() {
        val file = tempFile("recorder_not_open")
        try {
            val recorder = SstvWavRecorder(file, sampleRate)
            recorder.append(ShortArray(100) { 5 })
            assertEquals(0L, recorder.sampleCount)
            assertTrue(!recorder.isOpen)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `append 的数量参数被夹紧到数组范围内`() {
        val file = tempFile("recorder_clamp")
        try {
            val recorder = SstvWavRecorder(file, sampleRate)
            recorder.open()
            val pcm = ShortArray(64) { 7 }
            // 传入远超数组长度的 count：只应写入数组内实际存在的部分
            recorder.append(pcm, 0, 9999)
            assertEquals(64L, recorder.sampleCount)
            recorder.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `时长按样本数与采样率换算`() {
        val file = tempFile("recorder_seconds")
        try {
            val recorder = SstvWavRecorder(file, 8000)
            recorder.open()
            recorder.append(ShortArray(16_000) { 1 })
            assertEquals(2.0, recorder.seconds, 1e-9)
            recorder.close()
        } finally {
            file.delete()
        }
    }

    private fun tempFile(name: String): File =
        File.createTempFile("sstv_$name", ".wav").also { it.delete() }

    private fun psnr(source: SstvImageSource, result: SstvDecoder.Result): Double {
        var sum = 0.0
        var count = 0
        for (y in 4 until result.height - 4) {
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
