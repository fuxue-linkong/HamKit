package com.example.hamkit.data.sstv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SSTV 发射播放器：把 [SstvEncoder.encodeChunks] 产出的 PCM 边生成边播放。
 *
 * **为什么必须流式**：最长模式单帧十几 M 样本，整段生成再播放会直接把内存顶到
 * 百 MB（见 [SstvEncoder.encodeChunks] 的说明）。这里用 `AudioTrack` 的
 * `MODE_STREAM`：写一块、播一块，内存里始终只有一块。
 *
 * **使用者把手机当成信号源**：手机扬声器对着电台话筒播放，或（更推荐）用音频线
 * 从耳机口接到电台的 LINE IN / 数据口。后者绕开了声学链路失真，是本 App 能提供的
 * 最佳发射路径，因此发射页同时提供「导出 WAV」。
 *
 * **合规提示由 UI 负责**：本类只做播放，不含任何「是否允许发射」的判断；
 * 实际是否上电发射由用户与其执照决定（中国境内须遵守《无线电管理条例》）。
 *
 * @param sampleRate 采样率（Hz），必须与编码器一致
 * @param onProgress 播放进度回调（0–1），在 IO 线程触发
 * @param onFinished 正常播完回调；被 [stop] 打断时不触发
 * @param onError 播放失败回调（给出可展示的原因）
 */
class SstvAudioPlayer(
    private val sampleRate: Int = SstvVisDetector.DEFAULT_SAMPLE_RATE,
    private val onProgress: (Float) -> Unit = {},
    private val onFinished: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var track: AudioTrack? = null

    /** 是否正在播放。 */
    val isPlaying: Boolean
        get() = job?.isActive == true

    /**
     * 播放一串 PCM 块。
     *
     * @param chunks 采样块序列（幅度 ±1.0）
     * @param totalSamples 预估总样本数，仅用于进度显示；为 null 时进度恒为 0
     */
    fun play(chunks: Sequence<FloatArray>, totalSamples: Int? = null) {
        if (isPlaying) return
        job = scope.launch {
            val audioTrack = openTrack()
            if (audioTrack == null) {
                onError("无法初始化音频输出（采样率 $sampleRate 不受支持）")
                return@launch
            }
            track = audioTrack
            try {
                audioTrack.play()
                var written = 0L
                var buffer = ShortArray(0)
                for (chunk in chunks) {
                    if (!isActive) return@launch
                    if (buffer.size < chunk.size) buffer = ShortArray(chunk.size)
                    for (i in chunk.indices) buffer[i] = SstvWavWriter.toPcm16(chunk[i])
                    var offset = 0
                    while (offset < chunk.size && isActive) {
                        // WRITE_BLOCKING：写满 AudioTrack 内部缓冲才返回，天然形成背压，
                        // 因此编码速度不会超过播放速度，内存也不会堆积
                        val count = audioTrack.write(
                            buffer,
                            offset,
                            chunk.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (count <= 0) break
                        offset += count
                    }
                    written += chunk.size
                    if (totalSamples != null && totalSamples > 0) {
                        onProgress((written.toDouble() / totalSamples).toFloat().coerceIn(0f, 1f))
                    }
                }
                if (!isActive) return@launch
                // 等待硬件把剩余样本播完，否则 stop() 会截掉尾巴
                audioTrack.stop()
                onProgress(1f)
                onFinished()
            } catch (e: Exception) {
                if (isActive) onError("播放中断：${e.message ?: "未知错误"}")
            } finally {
                releaseTrack()
            }
        }
    }

    /** 停止播放并释放 [AudioTrack]（可重复调用）。 */
    fun stop() {
        job?.cancel()
        job = null
        releaseTrack()
    }

    private fun openTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val candidate = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                // 4 倍最小缓冲：给「编码偶尔慢一拍」留出余量，避免欠载爆音
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null

        if (candidate.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { candidate.release() }
            return null
        }
        // 发射电平拉满：SSTV 是模拟模式，音量直接决定对方收到的对比度
        runCatching { candidate.setVolume(AudioTrack.getMaxVolume()) }
        return candidate
    }

    private fun releaseTrack() {
        val current = track
        track = null
        if (current != null) {
            runCatching { if (current.playState != AudioTrack.PLAYSTATE_STOPPED) current.pause() }
            runCatching { current.flush() }
            runCatching { current.release() }
        }
    }

    /** 供 UI 显示的常量：播放通道数。 */
    companion object {
        /** 音频用途，便于调试时核对路由。 */
        const val STREAM_TYPE = AudioManager.STREAM_MUSIC
    }
}
