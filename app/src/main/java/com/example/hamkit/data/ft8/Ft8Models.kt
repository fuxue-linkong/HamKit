package com.example.hamkit.data.ft8

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Ft8Config(
    val callsign: String = "",
    val grid: String = "", // Maidenhead 网格如 "OM44"
    val band: Ft8Band = Ft8Band.BAND_40M,
    val txEnabled: Boolean = false,
    val rxEnabled: Boolean = true,
    val autoReplyEnabled: Boolean = false,
    val monitoredBands: Set<Ft8Band> = setOf(Ft8Band.BAND_40M),
) : Parcelable

enum class Ft8Band(
    val displayName: String,
    val freqHz: Long, // 拨号频率 Hz
    val audioOffsetHz: Int = 200, // 基带音频偏移
) {
    BAND_160M("160m", 1_840_000L),
    BAND_80M("80m", 3_573_000L),
    BAND_60M("60m", 5_357_000L),
    BAND_40M("40m", 7_074_000L),
    BAND_30M("30m", 10_136_000L),
    BAND_20M("20m", 14_074_000L),
    BAND_17M("17m", 18_100_000L),
    BAND_15M("15m", 21_074_000L),
    BAND_12M("12m", 24_915_000L),
    BAND_10M("10m", 28_074_000L),
    BAND_6M("6m", 50_313_000L),
    BAND_2M("2m", 144_174_000L),
}

@Parcelize
data class Ft8DecodedMessage(
    val utcTime: Long,
    val snr: Float, // 信噪比 dB
    val dt: Float, // 时间偏移 秒
    val freqHz: Float, // 音频频率
    val raw77: Long, // 77-bit 消息原文
    val callsign: String,
    val grid: String?,
    val report: String?,
    val hash: String?,
    val messageText: String,
) : Parcelable

@Parcelize
data class Ft8QsoRecord(
    val id: Long = 0,
    val callsign: String,
    val grid: String?,
    val reportSent: String = "-99",
    val reportReceived: String = "-99",
    val band: Ft8Band = Ft8Band.BAND_40M,
    val freqHz: Long = 7_074_000L,
    val mode: String = "FT8",
    val qsoTime: Long = System.currentTimeMillis(),
    val isComplete: Boolean = false,
    val operator: String? = null, // 本台呼号（操作员）
    val myGrid: String? = null, // 本台网格
    val comment: String? = null, // 备注（导出 ADIF 时仅保留 ASCII）
) : Parcelable
