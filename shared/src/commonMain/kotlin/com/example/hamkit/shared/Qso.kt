package com.example.hamkit.shared

/**
 * QSO 日志模型与导出 —— 从 Android `Ft8QsoRecord` + `QsoExporters.kt` 移植。
 *
 * 纯逻辑（无 java.time）：UTC 日期/时间用 civil-from-days 算法自行换算，
 * 导出格式与 Android 的 ADIF 3.1.4 / ADX XML 保持一致。
 */
data class QsoRecord(
    val callsign: String,
    val grid: String?,
    val reportSent: String = "-99",
    val reportReceived: String = "-99",
    val bandName: String = "40m",
    val freqHz: Long = 7_074_000L,
    val mode: String = "FT8",
    val qsoTime: Long = 0L,
    val isComplete: Boolean = false,
    val operator: String? = null,
    val myGrid: String? = null,
    val comment: String? = null,
)

object QsoExporter {

    const val FORMAT_ADIF = "adi"
    const val FORMAT_XML = "xml"

    private const val ADIF_VERSION = "3.1.4"

    /** 按格式导出文本。 */
    fun export(records: List<QsoRecord>, format: String): String =
        if (format == FORMAT_XML) exportXml(records) else exportAdif(records)

    /** 建议文件名：HamKit_QSO_yyyyMMdd_HHmmss.adi/.xml */
    fun suggestFilename(format: String, nowMs: Long): String {
        val (y, mo, d) = utcDateParts(nowMs)
        val (h, mi, s) = utcTimeParts(nowMs)
        val stamp = pad(y, 4) + pad(mo, 2) + pad(d, 2) + "_" + pad(h, 2) + pad(mi, 2) + pad(s, 2)
        val ext = if (format == FORMAT_XML) "xml" else "adi"
        return "HamKit_QSO_${stamp}.$ext"
    }

    // MARK: - ADIF

    fun exportAdif(records: List<QsoRecord>): String {
        val sb = StringBuilder()
        // Header 首字符必须非 '<'，否则解析方会把首行当作第一条记录
        sb.append("HamKit QSO log export (ADIF ").append(ADIF_VERSION).append(")\n")
        sb.append("<adif_ver:5>").append(ADIF_VERSION).append('\n')
        sb.append("<programid:6>HamKit\n")
        sb.append("<EOH>\n")
        for (record in records) {
            if (record.callsign.isBlank()) continue
            sb.append(adifRecord(record))
        }
        return sb.toString()
    }

    private fun adifRecord(record: QsoRecord): String {
        val sb = StringBuilder()
        field(sb, "call", sanitize(record.callsign))
        record.grid?.takeIf { it.isNotBlank() }?.let { field(sb, "gridsquare", sanitize(it)) }
        if (record.reportSent.isNotBlank() && record.reportSent != "-99") {
            field(sb, "rst_sent", sanitize(record.reportSent))
        }
        if (record.reportReceived.isNotBlank() && record.reportReceived != "-99") {
            field(sb, "rst_rcvd", sanitize(record.reportReceived))
        }
        field(sb, "band", record.bandName)
        field(sb, "freq", formatMhz(record.freqHz))
        field(sb, "mode", record.mode.ifBlank { "FT8" })
        val (y, mo, d) = utcDateParts(record.qsoTime)
        val (h, mi, s) = utcTimeParts(record.qsoTime)
        field(sb, "qso_date", pad(y, 4) + pad(mo, 2) + pad(d, 2))
        field(sb, "time_on", pad(h, 2) + pad(mi, 2) + pad(s, 2))
        if (record.isComplete) field(sb, "qso_complete", "Y")
        record.operator?.takeIf { it.isNotBlank() }?.let { op ->
            field(sb, "operator", sanitize(op))
            field(sb, "station_callsign", sanitize(op))
        }
        record.myGrid?.takeIf { it.isNotBlank() }?.let { field(sb, "my_gridsquare", sanitize(it)) }
        record.comment?.let { c ->
            val ascii = sanitize(c)
            if (ascii.isNotBlank()) field(sb, "comment", ascii)
        }
        sb.append("<EOR>\n")
        return sb.toString()
    }

    /** `<name:len>value`：长度为字符数（值已保证 ASCII） */
    private fun field(sb: StringBuilder, name: String, value: String) {
        sb.append('<').append(name).append(':').append(value.length).append('>')
            .append(value).append('\n')
    }

    /** ADIF 字段仅允许 ASCII 32–126；其余字符（含中文/换行）剥离 */
    private fun sanitize(value: String): String =
        value.filter { it.code in 32..126 }.trim()

    /** Hz → MHz 字符串，尾零裁剪（7_074_000 → "7.074"） */
    fun formatMhz(freqHz: Long): String {
        val scaled = kotlin.math.round(freqHz / 1_000_000.0 * 1_000_000).toLong()
        val whole = scaled / 1_000_000
        val frac = scaled % 1_000_000
        if (frac == 0L) return whole.toString()
        val fracText = frac.toString().padStart(6, '0').trimEnd('0')
        return "$whole.$fracText"
    }

    // MARK: - ADX XML

    fun exportXml(records: List<QsoRecord>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<ADX>\n")
        sb.append("  <HEADER>\n")
        sb.append("    <ADIF_VER>").append(ADIF_VERSION).append("</ADIF_VER>\n")
        sb.append("    <PROGRAMID>HamKit</PROGRAMID>\n")
        sb.append("  </HEADER>\n")
        sb.append("  <RECORDS>\n")
        for (record in records) {
            if (record.callsign.isBlank()) continue
            sb.append("    <RECORD>\n")
            element(sb, "CALL", record.callsign)
            record.grid?.takeIf { it.isNotBlank() }?.let { element(sb, "GRIDSQUARE", it) }
            element(sb, "BAND", record.bandName)
            element(sb, "FREQ", formatMhz(record.freqHz))
            element(sb, "MODE", record.mode.ifBlank { "FT8" })
            val (y, mo, d) = utcDateParts(record.qsoTime)
            val (h, mi, s) = utcTimeParts(record.qsoTime)
            element(sb, "QSO_DATE", pad(y, 4) + pad(mo, 2) + pad(d, 2))
            element(sb, "TIME_ON", pad(h, 2) + pad(mi, 2) + pad(s, 2))
            record.operator?.takeIf { it.isNotBlank() }?.let { op ->
                element(sb, "STATION_CALLSIGN", op)
                element(sb, "OPERATOR", op)
            }
            record.myGrid?.takeIf { it.isNotBlank() }?.let { element(sb, "MY_GRIDSQUARE", it) }
            if (record.reportSent.isNotBlank() && record.reportSent != "-99") {
                element(sb, "RST_SENT", record.reportSent)
            }
            if (record.reportReceived.isNotBlank() && record.reportReceived != "-99") {
                element(sb, "RST_RCVD", record.reportReceived)
            }
            record.comment?.takeIf { it.isNotBlank() }?.let { element(sb, "COMMENT_INTL", it) }
            if (record.isComplete) element(sb, "QSO_COMPLETE", "Y")
            sb.append("    </RECORD>\n")
        }
        sb.append("  </RECORDS>\n")
        sb.append("</ADX>\n")
        return sb.toString()
    }

    private fun element(sb: StringBuilder, name: String, value: String) {
        sb.append("      <").append(name).append('>')
            .append(escape(value))
            .append("</").append(name).append(">\n")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // MARK: - UTC 时间换算（无 java.time）

    private fun utcDateParts(ms: Long): Triple<Int, Int, Int> {
        val days = floorDiv(ms, 86_400_000L)
        return civilFromDays(days)
    }

    private fun utcTimeParts(ms: Long): Triple<Int, Int, Int> {
        val dayMs = floorMod(ms, 86_400_000L)
        val totalSeconds = dayMs / 1000
        return Triple(
            (totalSeconds / 3600).toInt(),
            ((totalSeconds % 3600) / 60).toInt(),
            (totalSeconds % 60).toInt(),
        )
    }

    /** Howard Hinnant 的 civil_from_days（1970-01-01 起的天数 → 年月日）。 */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        val z = daysSinceEpoch + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year.toInt(), m.toInt(), d.toInt())
    }

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if ((a % b != 0L) && ((a < 0) != (b < 0))) q -= 1
        return q
    }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
