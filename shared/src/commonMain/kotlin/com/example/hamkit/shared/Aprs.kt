package com.example.hamkit.shared

/**
 * APRS 包格式化与解析 —— 从 Android `AprsPacket.kt` + `AprsPacketParser.kt` 移植。
 *
 * 边界约定：Swift 侧不传 Kotlin Char，符号表/符号码统一用 String（取首字符）。
 * 网络连接（APRS-IS TCP）由平台实现：Android Socket / iOS Network.framework。
 */
data class AprsStationInfo(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val symbolTable: String,
    val symbolCode: String,
    val comment: String,
    val altitudeMeters: Double,
    val course: Int,
    val speedKnots: Int,
)

/** 解析结果；[type] 为 "message" / "position" / "unsupported"。 */
data class AprsParsedPacket(
    val type: String,
    val source: String,
    val destination: String,
    val body: String,
    val msgNumber: String,
    val isAck: Boolean,
    val isRej: Boolean,
    val latitude: Double,
    val longitude: Double,
    val symbolTable: String,
    val symbolCode: String,
    val comment: String,
    val altitudeMeters: Double,
    val course: Int,
    val speedKnots: Int,
    val hasPosition: Boolean,
)

object Aprs {

    /** APRS-IS 只读占位 passcode（未填写验证码时使用）。 */
    const val READ_ONLY_PASSCODE = "-1"

    private val QRG_REGEX = Regex(".*?(\\d{2,3}[.,]\\d{3,4}).*?")

    fun formatCallSsid(callsign: String, ssid: String): String =
        if (ssid.isNotEmpty()) "$callsign-$ssid" else callsign

    /**
     * 登录行。
     *
     * APRS-IS 的 `vers` 必须是**两个 token**：`vers <软件名> <版本号>`。
     * 只给一个 token 时，严格的入口站点（aprsc）会直接判
     * `# Invalid login: software name and version are not separated by a space`
     * 并关闭连接 —— iOS 端一直连不上就是这个原因。
     * 这里兜一层：调用方只传了软件名时补一个默认版本号。
     */
    fun formatLogin(callsign: String, ssid: String, passcode: String, version: String): String {
        val trimmed = version.trim()
        val vers = when {
            trimmed.isEmpty() -> "HamKit 1.0"
            trimmed.any { it.isWhitespace() } -> trimmed
            else -> "$trimmed 1.0"
        }
        return "user ${formatCallSsid(callsign, ssid)} pass $passcode vers $vers"
    }

    /**
     * 位置包。compressed=true 用压缩格式（base-91），否则用未压缩格式。
     * 与 Android `formatPosition` 相同。
     */
    fun formatPosition(
        latitude: Double,
        longitude: Double,
        symbolTable: String,
        symbolCode: String,
        comment: String,
        compressed: Boolean,
    ): String =
        if (compressed) {
            formatCompressedPosition(latitude, longitude, symbolTable, symbolCode, comment)
        } else {
            formatUncompressedPosition(latitude, longitude, symbolTable, symbolCode, comment)
        }

    private fun formatUncompressedPosition(
        latitude: Double,
        longitude: Double,
        symbolTable: String,
        symbolCode: String,
        comment: String,
    ): String = "!${formatLat(latitude)}$symbolTable${formatLon(longitude)}$symbolCode$comment"

    private fun formatCompressedPosition(
        latitude: Double,
        longitude: Double,
        symbolTable: String,
        symbolCode: String,
        comment: String,
    ): String {
        // 标准压缩编码：先四舍五入成整数，再按 base-91 分解
        val latVal = kotlin.math.round((90.0 - latitude) * 380926.0).toLong()
        val lonVal = kotlin.math.round((180.0 + longitude) * 190463.0).toLong()

        val latBytes = ByteArray(4) { i ->
            val div = pow91(3 - i)
            ((latVal / div) % 91 + 33).toByte()
        }
        val lonBytes = ByteArray(4) { i ->
            val div = pow91(3 - i)
            ((lonVal / div) % 91 + 33).toByte()
        }
        val latStr = latBytes.map { it.toInt().toChar() }.joinToString("")
        val lonStr = lonBytes.map { it.toInt().toChar() }.joinToString("")
        return "!$symbolTable$latStr$lonStr$symbolCode$comment"
    }

    private fun pow91(exp: Int): Long {
        var v = 1L
        repeat(exp) { v *= 91 }
        return v
    }

    private fun formatLat(lat: Double): String {
        val isNorth = lat >= 0
        val absLat = kotlin.math.abs(lat)
        var degrees = absLat.toInt()
        var minutes = (absLat - degrees) * 60.0
        if (minutes >= 59.9995) {
            degrees += 1
            minutes = 0.0
        }
        return degrees.toString().padStart(2, '0') +
            formatFixed2(minutes) +
            if (isNorth) "N" else "S"
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
        return degrees.toString().padStart(3, '0') +
            formatFixed2(minutes) +
            if (isEast) "E" else "W"
    }

    /** "%05.2f"：至少 5 字符、2 位小数（如 07.25、12.50）。 */
    private fun formatFixed2(value: Double): String {
        val scaled = kotlin.math.round(value * 100.0).toLong()
        val whole = scaled / 100
        val frac = scaled % 100
        return whole.toString().padStart(2, '0') + "." + frac.toString().padStart(2, '0')
    }

    fun formatMessage(
        destination: String,
        message: String,
        msgNumber: String,
    ): String {
        val base = ":${destination.padEnd(9, ' ')}:$message"
        return if (msgNumber.isNotEmpty()) "$base{$msgNumber}" else base
    }

    fun parseHostPort(hostport: String, defaultPort: Int): Pair<String, Int> {
        val splits = hostport.trim().split(":")
        return try {
            if (splits.size >= 2) splits[0] to splits[1].toInt() else splits[0] to defaultPort
        } catch (_: Throwable) {
            (splits.firstOrNull() ?: hostport) to defaultPort
        }
    }

    fun parseQrg(comment: String): String? =
        QRG_REGEX.find(comment)?.groupValues?.getOrNull(1)

    /**
     * 解析 APRS-IS 报文（`SRC>DEST,path:payload`）。
     * 不支持的包类型返回 type="unsupported"，不抛异常（网络线程安全事故）。
     */
    fun parsePacket(packet: String): AprsParsedPacket {
        val sourceDest = packet.substringBefore(":")
        val source = sourceDest.substringBefore(">")
        val payload = packet.substringAfter(":", "")

        if (payload.isEmpty()) {
            return unsupported(source)
        }

        return when (payload[0]) {
            ':' -> parseMessage(source, payload)
            '!', '=', '/', '@' -> parsePosition(source, payload)
            else -> unsupported(source)
        }
    }

    private fun unsupported(source: String) = AprsParsedPacket(
        type = "unsupported",
        source = source,
        destination = "",
        body = "",
        msgNumber = "",
        isAck = false,
        isRej = false,
        latitude = 0.0,
        longitude = 0.0,
        symbolTable = "/",
        symbolCode = ">",
        comment = "",
        altitudeMeters = 0.0,
        course = 0,
        speedKnots = 0,
        hasPosition = false,
    )

    private fun parseMessage(source: String, payload: String): AprsParsedPacket {
        val body = payload.substring(1)
        if (body.length < 10) return unsupported(source)
        val dest = body.substring(0, 9).trim()
        val msgContent = body.substring(9).removePrefix(":")
        val match = Regex("\\{([A-Za-z0-9]{1,5})$").find(msgContent)
        val msgNumber = match?.groupValues?.getOrNull(1) ?: ""
        val cleanBody = if (match != null) {
            msgContent.substring(0, msgContent.length - match.value.length)
        } else {
            msgContent
        }
        return unsupported(source).copy(
            type = "message",
            destination = dest,
            body = cleanBody,
            msgNumber = msgNumber,
            isAck = cleanBody.startsWith("ack"),
            isRej = cleanBody.startsWith("rej"),
        )
    }

    private fun parsePosition(source: String, payload: String): AprsParsedPacket {
        val regex = Regex("""(!|=|/|@)(\d{2})(\d{2}\.\d{2})([NS])(.)(\d{3})(\d{2}\.\d{2})([EW])(.)(.*)""")
        val match = regex.find(payload) ?: return unsupported(source)
        val g = match.groupValues
        val lat = g[2].toDouble() + g[3].toDouble() / 60.0
        val lon = g[6].toDouble() + g[7].toDouble() / 60.0
        val latitude = if (g[4] == "N") lat else -lat
        val longitude = if (g[8] == "E") lon else -lon
        val symbolTable = g[5]
        val symbolCode = g[9]
        var comment = g[10]
        var altitude = 0.0
        var course = 0
        var speed = 0

        Regex("/A=(\\d{6})").find(comment)?.let { alt ->
            altitude = alt.groupValues[1].toInt().toDouble() / 3.2808399
            comment = comment.replace(alt.value, "")
        }
        Regex("(\\d{3})/(\\d{3})").find(comment)?.let { cs ->
            course = cs.groupValues[1].toInt()
            speed = cs.groupValues[2].toInt()
        }

        return unsupported(source).copy(
            type = "position",
            latitude = latitude,
            longitude = longitude,
            symbolTable = symbolTable,
            symbolCode = symbolCode,
            comment = comment.trim(),
            altitudeMeters = altitude,
            course = course,
            speedKnots = speed,
            hasPosition = true,
        )
    }
}
