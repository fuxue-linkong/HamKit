package com.example.hamkit.data.sstv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * 采集音频源抽象：把「从哪里拿音频」与「怎么解码」解耦。
 *
 * 参照 robot36（xdsopl/robot36，0BSD）的 `AudioSource` 家族（`MicrophoneAudioSource` /
 * `AudioPlaybackCaptureSource` / `FileAudioSource` 共用一个消费侧）。这样 [SstvRecorder]
 * 的采集、增量解调、部分解码、连续接收、录音、瀑布图等逻辑对三种音源完全复用，
 * 新增音源只需实现本接口。
 *
 * **实现约定**：`read` 是阻塞的且必须能被 [close] 从另一个线程打断（Android 的
 * `AudioRecord.read` 在 `release()` 后会返回负值，满足这一约定）。
 */
interface SstvAudioSource {

    /** 打开后实际使用的采样率（由 `open` 确定）。 */
    val sampleRate: Int

    /** 供 UI 展示的音源名称。 */
    val displayName: String

    /**
     * 打开音源。
     *
     * @return 失败原因（可直接展示给用户）；成功返回 null
     */
    fun open(): String?

    /**
     * 阻塞读取最多 `buffer.size` 个 16 位单声道样本。
     *
     * @return 实际读取的样本数；< 0 表示已结束或出错
     */
    fun read(buffer: ShortArray): Int

    /** 释放资源。可重复调用，且必须能从任意线程调用（用于打断 `read`）。 */
    fun close()
}

/**
 * 麦克风音源（默认）：48 kHz 优先，设备不支持时回退 44.1 kHz。
 *
 * 采样率必须尽量高：SSTV 最短像素 137.5 µs，低采样率无法在像素中点稳定取值
 * （见 docs/REQUIREMENT_SSTV.md §4.2）；回退到 44.1 kHz 时由解码器的 slant 拟合
 * 自动吸收比例差异。
 */
class MicrophoneAudioSource(private val context: Context) : SstvAudioSource {

    private val lock = Any()
    private var record: AudioRecord? = null

    override var sampleRate: Int = SstvRecorder.TARGET_SAMPLE_RATE
        private set

    override val displayName: String get() = "麦克风"

    override fun open(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "缺少麦克风权限"
        }
        for (rate in CANDIDATE_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, rate / 5) * 2)
                    .build()
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                synchronized(lock) { record = candidate }
                sampleRate = rate
                runCatching { candidate.startRecording() }
                return null
            }
            runCatching { candidate?.release() }
        }
        return "录音初始化失败（设备不支持 48 kHz 与 44.1 kHz）"
    }

    override fun read(buffer: ShortArray): Int {
        val current = synchronized(lock) { record } ?: return -1
        return runCatching {
            current.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        }.getOrDefault(-1)
    }

    override fun close() {
        val current = synchronized(lock) {
            val value = record
            record = null
            value
        } ?: return
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private companion object {
        val CANDIDATE_RATES = intArrayOf(SstvRecorder.TARGET_SAMPLE_RATE, 44_100)
    }
}

/**
 * 播放捕获音源（Android 10 / API 29 起）：解码**其它应用正在播放**的音频。
 *
 * 典型用法是把 SSTV 录音放在任意播放器里播、或对着 WebSDR 的网页播放，让 HamKit 直接
 * 从系统的播放混音里取音 —— 比「扬声器 → 麦克风」少一层声学失真。参照 robot36 的
 * `AudioPlaybackCaptureSource` + `AudioCaptureService`。
 *
 * **两个平台约束**（都必须在调用方满足，本类无法代为处理）：
 *  1. 必须先由用户通过 `MediaProjectionManager.createScreenCaptureIntent()` 授权，
 *     再把拿到的 [MediaProjection] 传进来；
 *  2. `targetSdk >= 34` 时，**调用 `getMediaProjection()` 之前**必须已有一个
 *     `foregroundServiceType="mediaProjection"` 的前台服务在运行，否则抛
 *     `SecurityException`。见 `SstvCaptureService` 与 `AndroidManifest.xml`。
 *
 * 另外被捕获的应用可以声明 `allowAudioPlaybackCapture=false` 拒绝被录，
 * 这种情况下本音源只会读到静音 —— 这是平台行为，不是缺陷。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackCaptureAudioSource(
    private val projection: MediaProjection,
    override var sampleRate: Int = SstvRecorder.TARGET_SAMPLE_RATE,
) : SstvAudioSource {

    private val lock = Any()
    private var record: AudioRecord? = null

    override val displayName: String get() = "播放捕获"

    override fun open(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "播放捕获需要 Android 10 及以上"
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return "当前采样率不被支持（$sampleRate Hz）"

        val configuration = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                // 只匹配「媒体」类用途：既覆盖绝大多数播放器，也避免把通话/提示音录进来
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
        }.getOrNull() ?: return "无法建立播放捕获配置（授权可能已失效）"

        val candidate = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(configuration)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 5) * 2)
                .build()
        }.getOrNull()

        if (candidate == null || candidate.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { candidate?.release() }
            return "播放捕获初始化失败（可能缺少前台服务或授权已过期）"
        }
        synchronized(lock) { record = candidate }
        runCatching { candidate.startRecording() }
        return null
    }

    override fun read(buffer: ShortArray): Int {
        val current = synchronized(lock) { record } ?: return -1
        return runCatching {
            current.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        }.getOrDefault(-1)
    }

    override fun close() {
        val current = synchronized(lock) {
            val value = record
            record = null
            value
        } ?: return
        runCatching { current.stop() }
        runCatching { current.release() }
    }
}
