package com.example.hamkit.data.zone

import kotlin.math.floor

object MaidenheadCalculator {

    private const val LON_RANGE = 360.0
    private const val LAT_RANGE = 180.0

    /**
     * 计算 6 位梅登兰德定位（ Maidenhead Locator ）。
     */
    fun calculate(latitude: Double, longitude: Double): String {
        require(latitude in -90.0..90.0) { "纬度必须在 -90 到 90 之间" }
        require(longitude in -180.0..180.0) { "经度必须在 -180 到 180 之间" }

        // 处理边界：±180.0 / ±90.0 应落入最后一个 subsquare，否则会被错误地映射为起始边界
        val safeLon = when {
            longitude >= 180.0 -> 180.0 - 1e-9
            longitude <= -180.0 -> -180.0 + 1e-9
            else -> longitude
        }
        val safeLat = when {
            latitude >= 90.0 -> 90.0 - 1e-9
            latitude <= -90.0 -> -90.0 + 1e-9
            else -> latitude
        }

        val adjustedLon = safeLon + 180.0
        val adjustedLat = safeLat + 90.0

        val fieldLon = floor(adjustedLon / (LON_RANGE / 18)).toInt().coerceAtMost(17)
        val fieldLat = floor(adjustedLat / (LAT_RANGE / 18)).toInt().coerceAtMost(17)

        val squareLon = floor((adjustedLon % (LON_RANGE / 18)) / (LON_RANGE / 18 / 10)).toInt().coerceIn(0, 9)
        val squareLat = floor((adjustedLat % (LAT_RANGE / 18)) / (LAT_RANGE / 18 / 10)).toInt().coerceIn(0, 9)

        val subsquareLon = floor((adjustedLon % (LON_RANGE / 18 / 10)) / (LON_RANGE / 18 / 10 / 24)).toInt().coerceIn(0, 23)
        val subsquareLat = floor((adjustedLat % (LAT_RANGE / 18 / 10)) / (LAT_RANGE / 18 / 10 / 24)).toInt().coerceIn(0, 23)

        return buildString {
            append(('A' + fieldLon))
            append(('A' + fieldLat))
            append(squareLon)
            append(squareLat)
            append(('a' + subsquareLon))
            append(('a' + subsquareLat))
        }
    }

    /**
     * 将 Maidenhead 字符串解析回中心坐标。
     */
    fun decode(locator: String): Pair<Double, Double> {
        require(locator.length >= 6) { "至少需要 6 位 Maidenhead 字符串" }
        val upper = locator.uppercase()

        val fieldLon = upper[0] - 'A'
        val fieldLat = upper[1] - 'A'
        require(fieldLon in 0..17) { "经度场字符非法: ${upper[0]}（应为 A-R）" }
        require(fieldLat in 0..17) { "纬度场字符非法: ${upper[1]}（应为 A-R）" }

        val squareLon = upper[2].digitToIntOrNull()
            ?: throw IllegalArgumentException("经度方格字符非法: ${upper[2]}（应为 0-9）")
        val squareLat = upper[3].digitToIntOrNull()
            ?: throw IllegalArgumentException("纬度方格字符非法: ${upper[3]}（应为 0-9）")
        require(squareLon in 0..9 && squareLat in 0..9) { "方格字符超出范围 0-9" }

        val subLon = upper[4].lowercaseChar() - 'a'
        val subLat = upper[5].lowercaseChar() - 'a'
        require(subLon in 0..23) { "经度子方格字符非法: ${upper[4]}（应为 a-x）" }
        require(subLat in 0..23) { "纬度子方格字符非法: ${upper[5]}（应为 a-x）" }

        val lon = -180.0 +
                fieldLon * (LON_RANGE / 18) +
                squareLon * (LON_RANGE / 18 / 10) +
                subLon * (LON_RANGE / 18 / 10 / 24) +
                (LON_RANGE / 18 / 10 / 24) / 2

        val lat = -90.0 +
                fieldLat * (LAT_RANGE / 18) +
                squareLat * (LAT_RANGE / 18 / 10) +
                subLat * (LAT_RANGE / 18 / 10 / 24) +
                (LAT_RANGE / 18 / 10 / 24) / 2

        return lat to lon
    }
}
