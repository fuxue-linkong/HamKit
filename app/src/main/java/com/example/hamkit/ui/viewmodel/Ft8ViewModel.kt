package com.example.hamkit.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ft8.Ft8AudioPlayer
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8Config
import com.example.hamkit.data.ft8.Ft8Decoder
import com.example.hamkit.data.ft8.Ft8Encoder
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.ft8.Ft8Recorder
import com.example.hamkit.data.ft8.Ft8SettingsStore
import com.example.hamkit.data.qso.QsoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Ft8DecodedEntry(
    val time: Long = System.currentTimeMillis(),
    val result: Ft8Decoder.DecodeResult,
)
data class Ft8UiState(
    val config: Ft8Config = Ft8Config(),
    val isEncoding: Boolean = false,
    val generatedPcm: ShortArray? = null,
    val lastMessageText: String = "",
    val isPlaying: Boolean = false,
    val isDecoding: Boolean = false,
    val decodeStatus: String = "",
    val decodedEntries: List<Ft8DecodedEntry> = emptyList(),
    val qsoRecords: List<Ft8QsoRecord> = emptyList(),
    val lastError: String? = null,
)

class Ft8ViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = Ft8SettingsStore(application)
    private val audioPlayer = Ft8AudioPlayer()
    private lateinit var recorder: Ft8Recorder
    private val qsoStore = QsoStore(application)

    private val _uiState = MutableStateFlow(
        Ft8UiState(config = settingsStore.toConfig())
    )
    val uiState: StateFlow<Ft8UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(config = settingsStore.toConfig()) }
        }
        // QSO 日志由 Room Flow 驱动，跨页面自动同步
        viewModelScope.launch {
            qsoStore.records.collect { records ->
                _uiState.update { it.copy(qsoRecords = records) }
            }
        }
    }

    fun updateSettings(transform: (Ft8Config) -> Ft8Config) {
        val newConfig = transform(_uiState.value.config)
        settingsStore.fromConfig(newConfig)
        _uiState.update { it.copy(config = newConfig) }
    }

    fun setBand(band: Ft8Band) {
        updateSettings { it.copy(band = band) }
    }

    fun encodeMessage(messageText: String) {
        val config = _uiState.value.config
        if (config.callsign.isBlank()) {
            _uiState.update { it.copy(lastError = "请先设置呼号") }
            return
        }

        _uiState.update { it.copy(isEncoding = true, lastError = null) }

        viewModelScope.launch {
            try {
                val pcm = withContext(Dispatchers.Default) {
                    Ft8Encoder.encodeToPcm(
                        messageText = messageText,
                        callsign = config.callsign,
                        grid = config.grid
                    )
                }
                _uiState.update {
                    it.copy(
                        isEncoding = false,
                        generatedPcm = pcm,
                        lastMessageText = messageText,
                    )
                }
                playPcm(pcm)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isEncoding = false,
                        lastError = "编码失败: ${e.message}",
                    )
                }
            }
        }
    }

    fun playPcm(pcm: ShortArray?) {
        val data = pcm ?: _uiState.value.generatedPcm ?: return
        if (_uiState.value.isPlaying) return
        _uiState.update { it.copy(isPlaying = true, lastError = null) }
        audioPlayer.play(data) {
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun stopPlayback() {
        audioPlayer.stop()
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }

    fun notifyPermissionDenied() {
        _uiState.update { it.copy(decodeStatus = "未授予录音权限，无法解码", lastError = "需要麦克风权限才能解码 FT8 信号") }
    }

    fun startDecoding() {
        if (_uiState.value.isDecoding) return
        stopPlayback()
        recorder = Ft8Recorder(
            context = getApplication(),
            onDecoded = { result ->
                _uiState.update {
                    val entries = it.decodedEntries + Ft8DecodedEntry(result = result)
                    it.copy(
                        decodedEntries = entries.takeLast(MAX_DECODED_ENTRIES),
                        decodeStatus = if (result.success) {
                            "解码成功: ${result.messageText} (SNR ${result.snr} dB)"
                        } else {
                            "未检测到信号: ${result.messageText}"
                        },
                    )
                }
                recordQsoFromDecoded(result)
            },
            onStatus = { status ->
                _uiState.update { it.copy(decodeStatus = status) }
            },
        )
        _uiState.update { it.copy(isDecoding = true, decodeStatus = "启动录音…", lastError = null) }
        recorder.start()
    }

    fun stopDecoding() {
        if (!_uiState.value.isDecoding) return
        recorder.stop()
        _uiState.update { it.copy(isDecoding = false, decodeStatus = "已停止解码") }
    }

    fun clearDecoded() {
        _uiState.update { it.copy(decodedEntries = emptyList()) }
    }

    override fun onCleared() {
        audioPlayer.stop()
        if (::recorder.isInitialized) {
            runCatching { recorder.stop() }
        }
        super.onCleared()
    }

    // ── QSO 自动记录 ────────────────────────────────────────────────────────

    /**
     * 从解码消息自动维护 QSO 记录。
     *
     * 消息格式为 `<TO> <FROM> <EXTRA>`（FT8 标准）：
     *  - `CQ [修饰] PEER [GRID]`：仅呼叫，不记录
     *  - `我 PEER 网格/报告/RRR/RR73/73`（对方发给我）：更新 grid / reportReceived / 完成标记
     *  - `PEER 我 报告`（麦克风拾到自家扬声器）：更新 reportSent
     *  - 含 `<H22:…>`/`<?>`/DE/QRZ、呼号未设置、解码失败：跳过
     */
    private fun recordQsoFromDecoded(result: Ft8Decoder.DecodeResult) {
        if (!result.success) return
        val messageText = result.messageText.trim()
        if (messageText.isEmpty()) return
        if (messageText.contains("<H") || messageText.contains("<?>")) return

        val tokens = messageText.split(Regex("\\s+"))
        if (tokens.size < 3) return // 无有效交换信息（仅地址）
        if (tokens[0] == "CQ") return
        if (tokens.any { it == "DE" || it == "QRZ" }) return

        val config = _uiState.value.config
        val myCall = config.callsign.trim().uppercase()
        if (myCall.isEmpty()) return
        val call0 = tokens[0].normalizeCall()
        val call1 = tokens[1].normalizeCall()
        val extra = tokens.drop(2).joinToString(" ") // 兼容 "R OM44"（R + 网格）

        val record = when {
            call0 == myCall && call1 != myCall -> {
                // 对方发给我：网格 / 对方对我信号的报告 / RRR / RR73、73
                applyPeerExtra(
                    callsign = tokens[1].uppercase(),
                    extra = extra,
                    config = config,
                    reportSent = null,
                )
            }
            call1 == myCall && call0 != myCall -> {
                // 我发给对方（麦克风拾到自家扬声器）：仅我的发射报告
                reportOf(extra)?.let { reportSent ->
                    applyPeerExtra(
                        callsign = tokens[0].uppercase(),
                        extra = extra,
                        config = config,
                        reportSent = reportSent,
                    )
                }
            }
            else -> null
        } ?: return

        viewModelScope.launch {
            upsertAutoRecord(record)
        }
    }

    /**
     * 组装一条自动记录（仅包含本次消息带来的增量字段，其余保持默认）。
     * [reportSent] 非 null 时记录发射报告；null 时按 extra 解析接收侧字段。
     */
    private fun applyPeerExtra(
        callsign: String,
        extra: String,
        config: Ft8Config,
        reportSent: String?,
    ): Ft8QsoRecord? {
        val base = Ft8QsoRecord(
            callsign = callsign,
            grid = null,
            band = config.band,
            freqHz = config.band.freqHz,
            mode = "FT8",
            qsoTime = System.currentTimeMillis(),
            isComplete = false,
            operator = config.callsign.trim().uppercase().ifEmpty { null },
            myGrid = config.grid.trim().uppercase().ifEmpty { null },
        )
        if (reportSent != null) return base.copy(reportSent = reportSent)
        return when {
            extra == "RR73" || extra == "73" -> base.copy(isComplete = true)
            extra == "RRR" -> base
            else -> {
                val grid = gridOf(extra)?.let { base.copy(grid = it) }
                val report = reportOf(extra)?.let { base.copy(reportReceived = it) }
                grid ?: report
            }
        }
    }

    /** 同呼号+频段、未完成且 30 分钟内 → 增量合并；否则新建 */
    private suspend fun upsertAutoRecord(record: Ft8QsoRecord) {
        val existing = qsoStore.findRecentIncomplete(
            record.callsign,
            record.band,
            System.currentTimeMillis() - QSO_DEDUP_WINDOW_MS,
        )
        if (existing == null) {
            qsoStore.insert(record)
        } else {
            qsoStore.update(
                existing.copy(
                    grid = record.grid ?: existing.grid,
                    reportSent = record.reportSent.takeUnless { it == "-99" } ?: existing.reportSent,
                    reportReceived = record.reportReceived.takeUnless { it == "-99" }
                        ?: existing.reportReceived,
                    isComplete = record.isComplete || existing.isComplete,
                )
            )
        }
    }

    /** 识别网格：`OM44` 或 `R OM44`（R 前缀表示确认 + 网格），非法返回 null */
    private fun gridOf(extra: String): String? {
        val candidate = extra.removePrefix("R ").trim()
        return candidate.takeIf { it.matches(GRID_REGEX) }
    }

    /** 识别信号报告：`-10` / `+05` / `R-10`（R 前缀表示确认 + 报告），非法返回 null */
    private fun reportOf(extra: String): String? =
        extra.takeIf { it.matches(REPORT_REGEX) }

    private fun String.normalizeCall(): String =
        uppercase().removeSuffix("/P").removeSuffix("/R")

    companion object {
        private const val MAX_DECODED_ENTRIES = 50

        /** 自动记录去重窗口：同呼号+频段未完成记录 30 分钟内合并 */
        private const val QSO_DEDUP_WINDOW_MS = 30L * 60 * 1000

        private val GRID_REGEX = Regex("[A-R]{2}[0-9]{2}")
        private val REPORT_REGEX = Regex("R?[+-][0-9]{1,2}")
    }
}
