package com.example.hamkit.data.ft8

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * FT8 麦克风录音器：AudioRecord 连续采集，按 15 秒 UTC 时隙对齐解码。
 *
 * 相比旧实现（固定 12000 Hz + 每 1.5s 解码 16s 窗口）的改进：
 *  - 采样率优先 12000 Hz，若设备不支持则回退 48000/44100 并重采样到 12000；
 *  - 解码窗口对齐 15 秒时隙（FT8 标准周期），每次只解码一个完整时隙；
 *  - 解码回调提供 SNR / DT / 频率等完整信息。
 */
class Ft8Recorder(
    private val context: Context,
    private val onDecoded: (Ft8Decoder.DecodeResult) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val recordLock = Any()
    private var audioRecord: AudioRecord? = null

    val isRecording: Boolean
        get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        if (!hasPermission()) {
            onStatus("缺少录音权限")
            return
        }
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        // 释放录音设备，让阻塞在 read() 的读线程尽快退出
        releaseAudioRecord()
    }

    /** 幂等释放录音设备（stop() 与 runLoop 清理路径都可能调用，仅释放一次） */
    private fun releaseAudioRecord() {
        synchronized(recordLock) {
            val rec = audioRecord
            if (rec != null) {
                runCatching { rec.stop() }
                runCatching { rec.release() }
                audioRecord = null
            }
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private suspend fun CoroutineScope.runLoop() {
        try {
            // 依次尝试：12000（标准）→ 48000 → 44100，取第一个可用的
            val candidates = intArrayOf(12000, 48000, 44100)
            var sampleRate = 0
            var record: AudioRecord? = null
            for (rate in candidates) {
                val minBuffer = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) continue
                val candidate = try {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(minBuffer, rate / 10) * 2)
                        .build()
                } catch (e: Exception) {
                    null
                }
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    sampleRate = rate
                    record = candidate
                    break
                } else {
                    runCatching { candidate?.release() }
                }
            }

            if (record == null || sampleRate == 0) {
                onStatus("录音初始化失败（无可用采样率）")
                return
            }
            audioRecord = record

            record.startRecording()
            onStatus("正在解码… (${sampleRate} Hz)")

            // 录音线程：持续读入环形缓冲
            val readThread = Thread {
                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val readBuf = ShortArray(sampleRate / 10) // 0.1s 块
                try {
                    while (true) {
                        val read = record.read(readBuf, 0, readBuf.size, AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            synchronized(ringLock) {
                                // 重采样到 12000
                                val decimated = resampleTo12000(readBuf, read, sampleRate)
                                for (s in decimated) {
                                    ringBuffer[ringWrite] = s
                                    ringWrite = (ringWrite + 1) % ringBuffer.size
                                    if (ringWrite == ringRead) {
                                        ringRead = (ringRead + 1) % ringBuffer.size
                                    }
                                }
                            }
                        } else if (read < 0) {
                            // 任何错误码（含 release 后的 ERROR_DEAD_OBJECT）都退出
                            break
                        }
                    }
                } catch (_: Exception) {
                    // release 后 read() 可能抛 IllegalStateException，正常退出
                }
            }
            readThread.start()

            // 解码线程：每 15 秒时隙解码一次。
            // 精确对齐 UTC 15 秒槽边界（而非固定 delay 轮询），避免调度抖动导致
            // 解码窗口跨槽切割。
            var lastSlot = -1L
            while (isActive) {
                val now = System.currentTimeMillis()
                val slotIndex = now / DECODE_INTERVAL_MS // 当前所处 15 秒槽序号
                // 距下一槽边界的剩余毫秒数；负值说明本槽已过（罕见，如挂起恢复），立即处理
                val delayMs = (slotIndex + 1) * DECODE_INTERVAL_MS - now
                if (delayMs > 0) {
                    delay(delayMs)
                }
                val nowSlot = (System.currentTimeMillis() / 1000) / 15
                if (nowSlot != lastSlot) {
                    lastSlot = nowSlot
                    val window = readLatestWindow()
                    if (window.size >= Ft8Encoder.SAMPLE_RATE * 12) {
                        val result = withContextSafe(window)
                        onDecoded(result)
                    }
                }
            }

            readThread.join(1000)
            releaseAudioRecord()
        } catch (e: Exception) {
            onStatus("录音错误: ${e.message}")
            releaseAudioRecord()
        }
    }

    /** 把任意采样率音频重采样到 12000 Hz（线性插值） */
    private fun resampleTo12000(src: ShortArray, srcLen: Int, srcRate: Int): ShortArray {
        if (srcRate == Ft8Encoder.SAMPLE_RATE) {
            return src.copyOfRange(0, srcLen)
        }
        val outLen = (srcLen.toLong() * Ft8Encoder.SAMPLE_RATE / srcRate).toInt()
        val out = ShortArray(outLen)
        val ratio = srcRate.toDouble() / Ft8Encoder.SAMPLE_RATE
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, srcLen - 1)
            val i1 = (i0 + 1).coerceIn(0, srcLen - 1)
            val frac = (pos - i0).toFloat()
            out[i] = (src[i0] * (1 - frac) + src[i1] * frac).toInt().toShort()
        }
        return out
    }

    private val ringLock = Any()
    // 环形缓冲：16 秒的 12000 Hz 数据
    private val ringBuffer = ShortArray(Ft8Encoder.SAMPLE_RATE * 16)
    private var ringRead = 0
    private var ringWrite = 0

    private fun readLatestWindow(): ShortArray {
        val windowSamples = Ft8Encoder.SAMPLE_RATE * 15
        val out = ShortArray(windowSamples)
        synchronized(ringLock) {
            val available = if (ringWrite >= ringRead) {
                ringWrite - ringRead
            } else {
                ringBuffer.size - ringRead + ringWrite
            }
            val take = min(available, windowSamples)
            val start = (ringWrite - take + ringBuffer.size) % ringBuffer.size
            for (i in 0 until take) {
                out[i] = ringBuffer[(start + i) % ringBuffer.size]
            }
        }
        return out
    }

    private suspend fun withContextSafe(window: ShortArray): Ft8Decoder.DecodeResult =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            Ft8Decoder.decode(window)
        }

    companion object {
        /** FT8 时隙长度（毫秒），解码循环按此对齐 UTC 15 秒槽边界 */
        private const val DECODE_INTERVAL_MS = 15000L
    }
}
