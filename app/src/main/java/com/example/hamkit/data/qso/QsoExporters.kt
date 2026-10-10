package com.example.hamkit.data.qso

import com.example.hamkit.data.ft8.Ft8QsoRecord
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** QSO 日志导出格式：ADIF (.adi) 或 ADX 兼容 XML (.xml) */
enum class QsoExportFormat(
    val fileExtension: String,
    val mimeType: String,
) {
    ADIF("adi", "text/plain"),
    XML("xml", "text/xml"),
}

/**
 * QSO 记录导出：ADIF (.adi, 3.1.4) 与 XML (.xml, ADX 兼容)。
 *
 * 纯函数、纯 JVM（仅依赖 java.time），可直接单元测试。
 *
 * 字段映射（Ft8QsoRecord → ADIF/ADX）：
 *   callsign→CALL  grid→GRIDSQUARE  reportSent→RST_SENT  reportReceived→RST_RCVD
 *   band→BAND  freqHz→FREQ(MHz)  mode→MODE  qsoTime(UTC)→QSO_DATE+TIME_ON
 *   isComplete→QSO_COMPLETE  operator→OPERATOR/STATION_CALLSIGN  myGrid→MY_GRIDSQUARE
 *   comment→COMMENT(仅 ASCII)/COMMENT_INTL(UTF-8)
 */
object AdifExporter {

    private const val ADIF_VERSION = "3.1.4"

    fun export(records: List<Ft8QsoRecord>): String {
        val sb = StringBuilder()
        // Header 首字符必须非 '<'，否则解析方会把首行当作第一条记录（视为无 Header）
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

    private fun adifRecord(record: Ft8QsoRecord): String {
        val sb = StringBuilder()
        field(sb, "call", sanitize(record.callsign))
        record.grid?.takeIf { it.isNotBlank() }?.let { field(sb, "gridsquare", sanitize(it)) }
        if (record.reportSent.isNotBlank() && record.reportSent != "-99") {
            field(sb, "rst_sent", sanitize(record.reportSent))
        }
        if (record.reportReceived.isNotBlank() && record.reportReceived != "-99") {
            field(sb, "rst_rcvd", sanitize(record.reportReceived))
        }
        field(sb, "band", record.band.displayName)
        field(sb, "freq", formatMhz(record.freqHz))
        field(sb, "mode", record.mode.ifBlank { "FT8" })
        field(sb, "qso_date", utcDate(record.qsoTime))
        field(sb, "time_on", utcTime(record.qsoTime))
        // qso_complete 枚举为 Y/N/NIL/?，仅导出 Y
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

    /** `<name:len>value`：长度为字符数（值已保证 ASCII，字符数=字节数） */
    private fun field(sb: StringBuilder, name: String, value: String) {
        sb.append('<').append(name).append(':').append(value.length).append('>')
            .append(value).append('\n')
    }

    /** ADIF 字段仅允许 ASCII 32–126；其余字符（含中文/换行）直接剥离 */
    private fun sanitize(value: String): String =
        value.filter { it.code in 32..126 }.trim()

    /** Hz → MHz 数字字符串，尾零裁剪（7_074_000 → "7.074"） */
    internal fun formatMhz(freqHz: Long): String {
        val text = String.format(Locale.US, "%.6f", freqHz / 1_000_000.0)
        return text.trimEnd('0').trimEnd('.')
    }

    internal fun utcDate(qsoTime: Long): String =
        Instant.ofEpochMilli(qsoTime).atOffset(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
        )

    internal fun utcTime(qsoTime: Long): String =
        Instant.ofEpochMilli(qsoTime).atOffset(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern("HHmmss", Locale.US)
        )
}

/** XML 导出：ADX 结构（元素名大写、无数据类型指示符、无 EOH），支持中文 COMMENT_INTL */
object XmlExporter {

    private const val ADIF_VERSION = "3.1.4"

    fun export(records: List<Ft8QsoRecord>): String {
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
            element(sb, "BAND", record.band.displayName)
            element(sb, "FREQ", AdifExporter.formatMhz(record.freqHz))
            element(sb, "MODE", record.mode.ifBlank { "FT8" })
            element(sb, "QSO_DATE", AdifExporter.utcDate(record.qsoTime))
            element(sb, "TIME_ON", AdifExporter.utcTime(record.qsoTime))
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

    /** 手写 XML 转义：& < >（元素内容场景） */
    internal fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

/** 统一入口：按格式导出并生成建议文件名 */
object QsoExporter {

    fun export(records: List<Ft8QsoRecord>, format: QsoExportFormat): String = when (format) {
        QsoExportFormat.ADIF -> AdifExporter.export(records)
        QsoExportFormat.XML -> XmlExporter.export(records)
    }

    fun suggestFilename(
        format: QsoExportFormat,
        now: Long = System.currentTimeMillis(),
    ): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(now))
        return "HamKit_QSO_${stamp}.${format.fileExtension}"
    }
}
