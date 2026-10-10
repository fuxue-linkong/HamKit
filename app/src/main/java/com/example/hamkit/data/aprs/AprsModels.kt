package com.example.hamkit.data.aprs

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AprsStation(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val symbolTable: Char = '/',
    val symbolCode: Char = '>',
    val comment: String = "",
    val altitude: Double? = null,
    val course: Int? = null,
    val speed: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val distance: Double? = null,
    val bearing: Double? = null
) : Parcelable

@Parcelize
data class AprsMessage(
    val id: Long = 0,
    val source: String,
    val destination: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val isRead: Boolean = false,
    val msgNumber: String? = null,
    val isAcknowledged: Boolean = false,
    val status: Int = 0,
    val retryCount: Int = 0
) : Parcelable

data class AprsPosition(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val course: Int? = null,
    val speed: Int? = null,
    val symbolTable: Char = '/',
    val symbolCode: Char = '>',
    val comment: String = ""
)

data class AprsConfig(
    val callsign: String = "",
    val ssid: String = "",
    val passcode: String = "",
    val server: String = "china.aprs2.net",
    val port: Int = 14580,
    val useTls: Boolean = false,
    val symbolTable: Char = '/',
    val symbolCode: Char = '>',
    val comment: String = "HamKit",
    val transmitInterval: Int = 60,
    val useCompression: Boolean = true,
    val enableTransmit: Boolean = false,
    val enableReceive: Boolean = true,
    val filter: String = "",
    /**
     * 是否已确认「持照与责任声明」（HK-REQ-004）。
     *
     * 首次连接 APRS-IS 前必须确认；未确认不得登录。
     */
    val licenseConfirmed: Boolean = false
) {
    val fullCallsign: String
        get() = if (ssid.isNotEmpty()) "$callsign-$ssid" else callsign

    /**
     * 是否处于只读模式（HK-REQ-003）。
     *
     * 用户未自行填写 passcode 时为 true：以 [AprsPacket.READ_ONLY_PASSCODE]（pass -1）连接，
     * 可接收报文，服务器拒绝其注入的任何报文。
     *
     * 注意：本应用**不计算** passcode（HK-BUG-003 合规整改），因此不再有 computedPasscode 回退。
     */
    val isReadOnly: Boolean
        get() = passcode.isBlank()

    /**
     * 实际用于登录的 passcode 字符串：用户填写的值，或在只读模式下使用 `-1`。
     */
    val effectivePasscode: String
        get() = if (isReadOnly) AprsPacket.READ_ONLY_PASSCODE else passcode.trim()
}
