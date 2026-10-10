package com.example.hamkit.data.aprs

import android.location.Location

object AprsPacket {
    private val QRG_RE = Regex(".*?(\\d{2,3}[.,]\\d{3,4}).*?")

    /**
     * APRS-IS 只读连接使用的占位 passcode。
     *
     * APRS-IS 官方约定：`pass -1` 表示「只验证呼号、不授予注入权限」的只读会话，
     * 该连接可以接收报文，但服务器会拒绝其发送的任何报文。
     *
     * 合规背景（HK-BUG-003）：APRS-IS 官方要求
     * “Authors, YOU are responsible for issuing passcodes to amateur radio operators ONLY.
     * Do not make this available in an ondemand fashion …”。
     * 因此本应用**不实现、不内置、不显示** passcode 算法，用户必须自行持有验证码；
     * 留空时即用本常量以只读方式连接。
     */
    const val READ_ONLY_PASSCODE = "-1"

    fun formatCallSsid(callsign: String, ssid: String?): String {
        return if (!ssid.isNullOrEmpty()) "$callsign-$ssid" else callsign
    }

    private fun m2ft(meter: Double): Int = (meter * 3.2808399).toInt()

    private fun mps2kt(mps: Float): Int = (mps * 1.94384449f).toInt()

    fun formatAltitude(location: Location): String {
        return if (location.hasAltitude()) {
            "/A=%06d".format(m2ft(location.altitude))
        } else ""
    }

    fun formatCourseSpeed(location: Location): String {
        return if (location.hasSpeed() && location.hasBearing()) {
            "%03d/%03d".format(
                location.bearing.toInt(),
                mps2kt(location.speed)
            )
        } else ""
    }

    fun formatLogin(callsign: String, ssid: String?, passcode: String, version: String): String {
        return "user ${formatCallSsid(callsign, ssid)} pass $passcode vers $version"
    }

    fun formatPosition(
        latitude: Double,
        longitude: Double,
        symbolTable: Char,
        symbolCode: Char,
        comment: String,
        compressed: Boolean = false
    ): String {
        return if (compressed) {
            formatCompressedPosition(latitude, longitude, symbolTable, symbolCode, comment)
        } else {
            formatUncompressedPosition(latitude, longitude, symbolTable, symbolCode, comment)
        }
    }

    private fun formatUncompressedPosition(
        latitude: Double,
        longitude: Double,
        symbolTable: Char,
        symbolCode: Char,
        comment: String
    ): String {
        val latStr = formatLat(latitude)
        val lonStr = formatLon(longitude)
        return "!$latStr$symbolTable$lonStr$symbolCode$comment"
    }

    private fun formatCompressedPosition(
        latitude: Double,
        longitude: Double,
        symbolTable: Char,
        symbolCode: Char,
        comment: String
    ): String {
        // 标准压缩编码要求先四舍五入成整数，再按 base-91 整数分解，
        // 避免浮点小数导致最低位字符偏差 1
        val latVal = Math.round((90.0 - latitude) * 380926.0)
        val lonVal = Math.round((180.0 + longitude) * 190463.0)

        val latBytes = ByteArray(4) { i ->
            val div = Math.pow(91.0, (3 - i).toDouble()).toLong()
            ((latVal / div) % 91 + 33).toInt().toByte()
        }
        val lonBytes = ByteArray(4) { i ->
            val div = Math.pow(91.0, (3 - i).toDouble()).toLong()
            ((lonVal / div) % 91 + 33).toInt().toByte()
        }

        val compressed = String(latBytes, Charsets.US_ASCII) +
                String(lonBytes, Charsets.US_ASCII) +
                symbolCode + comment
        return "!$symbolTable$compressed"
    }

    private fun formatLat(lat: Double): String {
        val isNorth = lat >= 0
        val absLat = kotlin.math.abs(lat)
        var degrees = absLat.toInt()
        var minutes = (absLat - degrees) * 60.0
        // 浮点误差可能导致 minutes 被四舍五入成 60.00（非法），进位到度
        if (minutes >= 59.9995) {
            degrees += 1
            minutes = 0.0
        }
        return "%02d%05.2f%s".format(degrees, minutes, if (isNorth) "N" else "S")
    }

    private fun formatLon(lon: Double): String {
        val isEast = lon >= 0
        val absLon = kotlin.math.abs(lon)
        var degrees = absLon.toInt()
        var minutes = (absLon - degrees) * 60.0
        if (minutes >= 59.9995) {
            degrees += 1
            minutes = 0.0
        }
        return "%03d%05.2f%s".format(degrees, minutes, if (isEast) "E" else "W")
    }

    fun formatMessage(
        source: String,
        destination: String,
        message: String,
        msgNumber: String? = null
    ): String {
        val base = ":${destination.padEnd(9, ' ')}:$message"
        return if (msgNumber != null) {
            "$base{$msgNumber}"
        } else {
            base
        }
    }

    fun parseHostPort(hostport: String, defaultport: Int): Pair<String, Int> {
        val splits = hostport.trim().split(":")
        return try {
            Pair(splits[0], splits[1].toInt())
        } catch (_: Throwable) {
            Pair(splits[0], defaultport)
        }
    }

    fun parseQrg(comment: String): String? {
        return QRG_RE.find(comment)?.groupValues?.getOrNull(1)
    }
}
