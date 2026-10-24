package com.example.hamkit.data.aprs

import android.util.Log

object AprsPacketParser {
    private val TAG = "AprsPacketParser"

    sealed class ParsedPacket {
        abstract val source: String
    }

    data class ParsedMessage(
        override val source: String,
        val destination: String,
        val body: String,
        val msgNumber: String? = null,
        val isAck: Boolean = false,
        val isRej: Boolean = false
    ) : ParsedPacket()

    data class ParsedPosition(
        override val source: String,
        val latitude: Double,
        val longitude: Double,
        val symbolTable: Char = '/',
        val symbolCode: Char = '>',
        val comment: String = "",
        val altitude: Double? = null,
        val course: Int? = null,
        val speed: Int? = null
    ) : ParsedPacket()

    fun parse(packet: String): ParsedPacket {
        val sourceDest = packet.substringBefore(":")
        val source = sourceDest.substringBefore(">")
        val payload = packet.substringAfter(":", "")

        if (payload.isEmpty()) {
            throw IllegalArgumentException("Empty payload")
        }

        val dataType = payload[0]

        return when (dataType) {
            ':' -> parseMessage(source, payload)
            '!', '=', '/', '@' -> parsePosition(source, payload)
            ';' -> parseObject(source, payload)
            ')' -> parseItem(source, payload)
            else -> throw IllegalArgumentException("Unknown data type: $dataType")
        }
    }

    private fun parseMessage(source: String, payload: String): ParsedMessage {
        // APRS message format: :DESTINATION:message{NNN
        // payload starts with ':', then 9-char destination, then ':', then message
        val body = payload.substring(1) // skip first ':'
        // 目标字段固定 9 字符（空格补齐），畸形报文不足时直接丢弃而非越界
        if (body.length < 10) {
            throw IllegalArgumentException("Message payload too short: $payload")
        }
        val dest = body.substring(0, 9).trim()
        val msgContent = body.substring(9).removePrefix(":")
        // APRS 消息编号为 1-5 位字母数字组合（如 {A1}、{ABC12}），非纯数字
        val msgNumberMatch = Regex("\\{([A-Za-z0-9]{1,5})$").find(msgContent)

        val msgNumber = msgNumberMatch?.groupValues?.getOrNull(1)
        val cleanBody = if (msgNumber != null) {
            msgContent.substring(0, msgContent.length - msgNumberMatch.value.length)
        } else {
            msgContent
        }

        // P2-5：startsWith("ack") 过宽（如正文 "acknowledge" 会被误判为 ACK）。
        // APRS ACK/REJ 正文格式为 ack/rej + 1~5 位字母数字编号，改为全串精确匹配。
        val isAck = Regex("^ack[A-Za-z0-9]{1,5}$").matches(cleanBody)
        val isRej = Regex("^rej[A-Za-z0-9]{1,5}$").matches(cleanBody)

        return ParsedMessage(
            source = source,
            destination = dest,
            body = cleanBody,
            msgNumber = msgNumber,
            isAck = isAck,
            isRej = isRej
        )
    }

    private fun parsePosition(source: String, payload: String): ParsedPosition {
        return parseUncompressedPosition(source, payload)
    }

    private fun parseUncompressedPosition(source: String, payload: String): ParsedPosition {
        val posRegex = Regex("""(!|=|/|@)(\d{2})(\d{2}\.\d{2})([NS])(.)(\d{3})(\d{2}\.\d{2})([EW])(.)(.*)""")
        val match = posRegex.find(payload) ?: throw IllegalArgumentException("Invalid position format")

        val groups = match.groupValues
        val latDeg = groups[2].toDouble()
        val latMin = groups[3].toDouble()
        val latHem = groups[4]
        val symTable = groups[5][0]
        val lonDeg = groups[6].toDouble()
        val lonMin = groups[7].toDouble()
        val lonHem = groups[8]
        val symCode = groups[9][0]
        val rest = groups[10]

        val latitude = latDeg + latMin / 60.0
        val lat = if (latHem == "N") latitude else -latitude

        val longitude = lonDeg + lonMin / 60.0
        val lon = if (lonHem == "E") longitude else -longitude

        var comment = rest
        var altitude: Double? = null
        var course: Int? = null
        var speed: Int? = null

        val altMatch = Regex("/A=(\\d{6})").find(comment)
        if (altMatch != null) {
            altitude = altMatch.groupValues[1].toInt().toDouble() / 3.2808399
            comment = comment.replace(altMatch.value, "")
        }

        val csMatch = Regex("(\\d{3})/(\\d{3})").find(comment)
        if (csMatch != null) {
            course = csMatch.groupValues[1].toInt()
            speed = csMatch.groupValues[2].toInt()
        }

        return ParsedPosition(
            source = source,
            latitude = lat,
            longitude = lon,
            symbolTable = symTable,
            symbolCode = symCode,
            comment = comment.trim(),
            altitude = altitude,
            course = course,
            speed = speed
        )
    }

    private fun parseObject(source: String, payload: String): ParsedPosition {
        val objRegex = Regex(""";(.{9})\*([\d/!@])(\d{2})(\d{2}\.\d{2})([NS])(.)(\d{3})(\d{2}\.\d{2})([EW])(.)(.*)""")
        val match = objRegex.find(payload) ?: throw IllegalArgumentException("Invalid object format")

        val groups = match.groupValues
        val latDeg = groups[3].toDouble()
        val latMin = groups[4].toDouble()
        val latHem = groups[5]
        val symTable = groups[6][0]
        val lonDeg = groups[7].toDouble()
        val lonMin = groups[8].toDouble()
        val lonHem = groups[9]
        val symCode = groups[10][0]
        val rest = groups[11]

        val latitude = latDeg + latMin / 60.0
        val lat = if (latHem == "N") latitude else -latitude

        val longitude = lonDeg + lonMin / 60.0
        val lon = if (lonHem == "E") longitude else -longitude

        return ParsedPosition(
            source = source,
            latitude = lat,
            longitude = lon,
            symbolTable = symTable,
            symbolCode = symCode,
            comment = groups[1].trim()
        )
    }

    private fun parseItem(source: String, payload: String): ParsedPosition {
        val itemRegex = Regex("""\)(.+?)([!_])(\d{2})(\d{2}\.\d{2})([NS])(.)(\d{3})(\d{2}\.\d{2})([EW])(.)(.*)""")
        val match = itemRegex.find(payload) ?: throw IllegalArgumentException("Invalid item format")

        val groups = match.groupValues
        val latDeg = groups[3].toDouble()
        val latMin = groups[4].toDouble()
        val latHem = groups[5]
        val symTable = groups[6][0]
        val lonDeg = groups[7].toDouble()
        val lonMin = groups[8].toDouble()
        val lonHem = groups[9]
        val symCode = groups[10][0]
        val rest = groups[11]

        val latitude = latDeg + latMin / 60.0
        val lat = if (latHem == "N") latitude else -latitude

        val longitude = lonDeg + lonMin / 60.0
        val lon = if (lonHem == "E") longitude else -longitude

        return ParsedPosition(
            source = source,
            latitude = lat,
            longitude = lon,
            symbolTable = symTable,
            symbolCode = symCode,
            comment = groups[1].trim()
        )
    }
}
