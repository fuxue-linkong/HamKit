package com.example.hamkit.data.satellite

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * CelesTrak active 分组 CSV（gp.php?GROUP=active&FORMAT=csv）解析器。
 *
 * 把 CSV 行转换为自研 TLE 解析器（[com.example.hamkit.data.satellite.predict.TleElements]）
 * 所需的标准三行格式（名称行 + line1 + line2）。
 *
 * ## CSV 列（CelesTrak gp.php 文档）与 TLE 字段的对应关系
 * | CSV 列 | TLE 字段 |
 * |---|---|
 * | OBJECT_NAME | 名称行（截断到 24 字符） |
 * | OBJECT_ID | line1 国际标识符（YYYY-NNNX → YYNNNX） |
 * | EPOCH | line1 历元（YYDDD.DDDDDDDD） |
 * | MEAN_MOTION | line2 平均运动 |
 * | ECCENTRICITY | line2 偏心率（×1e7 整数格式） |
 * | INCLINATION | line2 倾角 |
 * | RA_OF_ASC_NODE | line2 升交点赤经 |
 * | ARG_OF_PERICENTER | line2 近地点幅角 |
 * | MEAN_ANOMALY | line2 平近点角 |
 * | EPHEMERIS_TYPE | line1 星历类型 |
 * | CLASSIFICATION_TYPE | line1 分类（U/C/S） |
 * | NORAD_CAT_ID | line1/line2 NORAD 编号 |
 * | ELEMENT_SET_NO | line1 星历编号 |
 * | REV_AT_EPOCH | line2 历元圈数 |
 * | BSTAR | line1 大气阻力系数（指数格式） |
 * | MEAN_MOTION_DOT | line1 平均运动一阶导数（"drag" 字段，隐含小数点格式） |
 * | MEAN_MOTION_DDOT | line1 平均运动二阶导数（指数格式） |
 *
 * ## 与自研 TLE 解析器的兼容性
 * [com.example.hamkit.data.satellite.predict.TleElements] 按固定列偏移
 * 解析 line1/line2，不校验校验和，只要求各数字字段能被 toDouble / toInt 解析。因此：
 * - "drag" 字段必须输出为可直接解析的普通小数（如 "-.00002182"），
 *   不能使用隐含小数点格式（如 " 00000-0"）；
 * - "BSTAR"/"MEAN_MOTION_DDOT" 使用标准指数格式 "±NNNNN±N"，
 *   按 m×1e-5 / 10^exp 解读（与其解析逻辑自洽）；
 * - 校验和仍按标准算法计算并写入（保证数据完整性与可互操作性）。
 *
 * CSV 中的空值（NULL / 空串）：
 * - EPOCH / NORAD_CAT_ID / MEAN_MOTION / INCLINATION 等必填字段缺失时整行跳过；
 * - BSTAR / MEAN_MOTION_DOT / MEAN_MOTION_DDOT / ELEMENT_SET_NO / REV_AT_EPOCH
 *   缺失时按 0 / 默认值处理。
 */
object TleParser {

    private const val LINE_LENGTH = 69

    private val EPOCH_PATTERN = Regex(
        """(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?"""
    )

    /**
     * 解析 CelesTrak active CSV 全文，返回 (名称, line1, line2) 三元组列表。
     *
     * 第一行视为表头（按列名定位，不依赖固定列序）；后续每行一条卫星，
     * 解析失败（必填字段缺失/格式非法）的行走过。支持带引号字段与转义引号。
     */
    fun parseActiveCsv(csv: String): List<Triple<String, String, String>> {
        val lines = csv.lines()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val headers = parseCsvLine(lines.first()).map { it.trim() }
        val indexByName = headers.withIndex().associate { (i, h) -> h to i }

        val required = listOf(
            "EPOCH", "NORAD_CAT_ID", "MEAN_MOTION", "INCLINATION",
            "RA_OF_ASC_NODE", "ARG_OF_PERICENTER", "MEAN_ANOMALY",
        )
        if (required.any { it !in indexByName }) return emptyList()

        val result = mutableListOf<Triple<String, String, String>>()
        for (line in lines.drop(1)) {
            val fields = parseCsvLine(line)
            if (fields.size < headers.size) continue
            val row = headers.withIndex().associate { (i, h) -> h to fields[i].trim() }
            val tleTriple = buildTle(row) ?: continue
            result.add(tleTriple)
        }
        return result
    }

    /**
     * 由单行 CSV 字段（已按表头名索引）构建三行 TLE。
     *
     * @return (名称, line1, line2)；必填字段缺失或非法时返回 null
     */
    fun buildTle(row: Map<String, String>): Triple<String, String, String>? {
        val norad = row["NORAD_CAT_ID"]?.toIntOrNull() ?: return null
        if (norad <= 0) return null

        val epoch = row["EPOCH"].orEmpty()
        val parsedEpoch = parseEpoch(epoch) ?: return null

        val meanMotion = row["MEAN_MOTION"]?.toDoubleOrNull() ?: return null
        val inclination = row["INCLINATION"]?.toDoubleOrNull() ?: return null
        val raan = row["RA_OF_ASC_NODE"]?.toDoubleOrNull() ?: return null
        val eccentricity = row["ECCENTRICITY"]?.toDoubleOrNull() ?: 0.0
        val argPerigee = row["ARG_OF_PERICENTER"]?.toDoubleOrNull() ?: return null
        val meanAnomaly = row["MEAN_ANOMALY"]?.toDoubleOrNull() ?: return null

        val bstar = row["BSTAR"]?.toDoubleOrNull() ?: 0.0
        val meanMotionDot = row["MEAN_MOTION_DOT"]?.toDoubleOrNull() ?: 0.0
        val meanMotionDdot = row["MEAN_MOTION_DDOT"]?.toDoubleOrNull() ?: 0.0
        val elementSetNo = row["ELEMENT_SET_NO"]?.toIntOrNull() ?: 999
        val revAtEpoch = row["REV_AT_EPOCH"]?.toIntOrNull() ?: 9995
        val classification = row["CLASSIFICATION_TYPE"]?.take(1)?.takeIf { it.isNotBlank() } ?: "U"
        val ephemerisType = row["EPHEMERIS_TYPE"]?.take(1)?.takeIf { it.isNotBlank() } ?: "0"
        val objectId = row["OBJECT_ID"].orEmpty()
        val name = row["OBJECT_NAME"]?.takeIf { it.isNotBlank() } ?: norad.toString()

        val (year, refEpoch) = parsedEpoch
        val line1 = buildLine1(
            noradCatId = norad,
            classification = classification,
            objectId = objectId,
            year = year,
            refEpoch = refEpoch,
            meanMotionDot = meanMotionDot,
            meanMotionDdot = meanMotionDdot,
            bstar = bstar,
            ephemerisType = ephemerisType,
            elementSetNo = elementSetNo,
        )
        val line2 = buildLine2(
            noradCatId = norad,
            inclination = inclination,
            raan = raan,
            eccentricity = eccentricity,
            argPerigee = argPerigee,
            meanAnomaly = meanAnomaly,
            meanMotion = meanMotion,
            revAtEpoch = revAtEpoch,
        )
        return Triple(name.take(24), line1, line2)
    }

    // ── line 构造 ──

    /**
     * 构造 TLE line1（69 字符，含校验和）。字段布局：
     * 1  NNNNNU YYNNNX   YYDDD.DDDDDDDD  D.DDDDDDDD  MMMMSSSS  BBBBB±E  0 NNNNN*
     */
    fun buildLine1(
        noradCatId: Int,
        classification: String = "U",
        objectId: String = "",
        year: Int,
        refEpoch: Double,
        meanMotionDot: Double,
        meanMotionDdot: Double = 0.0,
        bstar: Double = 0.0,
        ephemerisType: String = "0",
        elementSetNo: Int = 999,
    ): String {
        val sb = StringBuilder(LINE_LENGTH)
        sb.append("1 ")
        sb.append(padStart(noradCatId.toString(), 5, '0'))
        sb.append(classification.take(1).ifEmpty { " " })
        sb.append(' ')
        sb.append(formatIntlDesignator(objectId))
        sb.append(' ')
        sb.append(padStart(year.toString(), 2, '0'))
        sb.append(String.format(Locale.US, "%012.8f", refEpoch))
        sb.append(' ')
        sb.append(formatDragField(meanMotionDot))
        sb.append(' ')
        sb.append(formatExponentialField(meanMotionDdot))
        sb.append(' ')
        sb.append(formatExponentialField(bstar))
        sb.append(' ')
        sb.append(ephemerisType.take(1).ifEmpty { " " })
        sb.append(padStart(elementSetNo.toString(), 5, ' '))
        val checksum = checksum(sb.toString())
        sb.append(checksum)
        return sb.toString()
    }

    /**
     * 构造 TLE line2（69 字符，含校验和）。字段布局：
     * 2  NNNNN  DD.DDDDD  DDD.DDDDD  DDDDDDD  DDD.DDDDD  DDD.DDDDD  D.DDDDDDDD  NNNNN*
     */
    fun buildLine2(
        noradCatId: Int,
        inclination: Double,
        raan: Double,
        eccentricity: Double,
        argPerigee: Double,
        meanAnomaly: Double,
        meanMotion: Double,
        revAtEpoch: Int = 9995,
    ): String {
        val sb = StringBuilder(LINE_LENGTH)
        sb.append("2 ")
        sb.append(padStart(noradCatId.toString(), 5, '0'))
        sb.append(' ')
        sb.append(String.format(Locale.US, "%8.4f", inclination))
        sb.append(' ')
        sb.append(String.format(Locale.US, "%8.4f", raan))
        sb.append(' ')
        sb.append(
            padStart(
                (eccentricity * 1e7).roundToInt().coerceIn(0, 99_999_999).toString(),
                7,
                '0'
            )
        )
        sb.append(' ')
        sb.append(String.format(Locale.US, "%8.4f", argPerigee))
        sb.append(' ')
        sb.append(String.format(Locale.US, "%8.4f", meanAnomaly))
        sb.append(' ')
        sb.append(String.format(Locale.US, "%11.8f", meanMotion))
        sb.append(padStart(revAtEpoch.toString(), 5, '0'))
        sb.append(checksum(sb.toString()))
        return sb.toString()
    }

    // ── 字段格式化 ──

    /**
     * 平均运动一阶导数（drag）字段（10 字符）。
     * 必须输出为 Double.parseDouble 可直接解析的普通小数（predict4java 兼容）：
     * 符号位 + "." + 8 位小数，绝对值 < 1。
     */
    private fun formatDragField(value: Double): String {
        if (value == 0.0) return " .00000000"
        val sign = if (value < 0) "-" else " "
        val abs = Math.abs(value).coerceAtMost(0.99999999)
        val digits = String.format(Locale.US, "%.8f", abs).substring(1) // 去掉前导 "0"
        return sign + digits
    }

    /**
     * 指数格式字段（BSTAR / MEAN_MOTION_DDOT），标准 TLE 布局 "±NNNNN±N"：
     * 符号位 + 5 位尾数 + 指数符号 + 指数数字。
     *
     * predict4java 按 m×1e-5/10^e 解读（其 [44,50)/[53,59) 尾数与 [51,52)/[60,61)
     * 指数位解析），本函数选择尾数 e 使 round-trip 误差最小。
     */
    private fun formatExponentialField(value: Double): String {
        if (value == 0.0) return " 00000+0"
        var exponent = 0
        var scaled = value * 1e5
        // 在 5 位尾数内保留最大有效数字（|m| ≥ 10000），超过则退位
        while (abs(scaled) < 10_000.0 && exponent < 9) {
            scaled *= 10.0
            exponent++
        }
        while (abs(scaled) >= 100_000.0 && exponent > 0) {
            scaled /= 10.0
            exponent--
        }
        val mantissa = scaled.roundToInt().coerceIn(-99_999, 99_999)
        val sign = if (mantissa < 0) "-" else " "
        val digits = padStart(abs(mantissa).toString(), 5, '0')
        val expSign = if (exponent >= 0) "+" else "-"
        val expDigit = abs(exponent).toString()
        return "$sign$digits$expSign$expDigit"
    }

    /**
     * 国际标识符字段（8 字符）。CSV 格式 "YYYY-NNNX"（如 "1998-067A"），
     * TLE 格式 "YYNNNX"（如 "98067A"），右对齐补空格。
     */
    private fun formatIntlDesignator(objectId: String): String {
        val m = Regex("""\d{4}-(\d{3})([A-Z0-9]?)""").find(objectId.trim()) ?: return "        "
        val year2 = objectId.trim().substring(2, 4)
        val piece = m.groupValues[2]
        return padStart(year2 + m.groupValues[1] + piece, 8, ' ')
    }

    /** TLE 校验和：各位数字之和（"-" 计 1），mod 10。 */
    fun checksum(line: String): Int {
        var sum = 0
        for (c in line) {
            when {
                c.isDigit() -> sum += c - '0'
                c == '-' -> sum += 1
            }
        }
        return sum % 10
    }

    // ── CSV 解析 ──

    /**
     * 解析单行 CSV，支持双引号字段与转义引号（""）。
     */
    fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            sb.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        sb.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    /**
     * 解析 CelesTrak EPOCH 字段（"2025-08-09 12:34:56" 或带小数秒），
     * 返回 (两位年份, YYDDD.DDDDDDDD 的 day-of-year+小数日)。
     * 解析失败返回 null。
     */
    fun parseEpoch(epoch: String): Pair<Int, Double>? {
        val m = EPOCH_PATTERN.matchEntire(epoch.trim()) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].toInt()
        val fraction = m.groupValues[7].takeIf { it.isNotEmpty() }
            ?.let { "0.$it".toDouble() } ?: 0.0
        return try {
            val date = LocalDate.of(year, month, day)
            val dayOfYear = date.getDayOfYear().toDouble()
            val fracDay = (hour * 3600 + minute * 60 + second) / 86_400.0 + fraction / 86_400.0
            (year % 100) to (dayOfYear + fracDay)
        } catch (_: Exception) {
            null
        }
    }

    private fun padStart(s: String, length: Int, padChar: Char): String {
        if (s.length >= length) return s
        return s.padStart(length, padChar)
    }
}
