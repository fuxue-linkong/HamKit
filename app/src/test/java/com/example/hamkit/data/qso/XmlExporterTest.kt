package com.example.hamkit.data.qso

import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * XML (.xml, ADX 兼容) 导出器测试。
 *
 * 覆盖：ADX 结构、元素名大写、QSO_DATE=YYYYMMDD（非 ISO 8601）、
 * COMMENT_INTL 承载中文、& < > 转义、空字段省略。
 */
class XmlExporterTest {

    private fun record(
        callsign: String = "BG7HIM",
        qsoTime: Long = ZonedDateTime.of(2026, 8, 27, 14, 15, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli(),
        isComplete: Boolean = false,
        comment: String? = null,
        grid: String? = "OM44",
        operator: String? = "BA1ABC",
        myGrid: String? = null,
        reportSent: String = "-99",
        reportReceived: String = "-99",
        freqHz: Long = 7_074_000L,
        band: Ft8Band = Ft8Band.BAND_40M,
    ) = Ft8QsoRecord(
        id = 1,
        callsign = callsign,
        grid = grid,
        reportSent = reportSent,
        reportReceived = reportReceived,
        band = band,
        freqHz = freqHz,
        mode = "FT8",
        qsoTime = qsoTime,
        isComplete = isComplete,
        operator = operator,
        myGrid = myGrid,
        comment = comment,
    )

    @Test
    fun adx_structure() {
        val out = XmlExporter.export(listOf(record()))
        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(out.contains("<ADX>"))
        assertTrue(out.contains("<HEADER>"))
        assertTrue(out.contains("<ADIF_VER>3.1.4</ADIF_VER>"))
        assertTrue(out.contains("<PROGRAMID>HamKit</PROGRAMID>"))
        assertTrue(out.contains("<RECORDS>"))
        assertTrue(out.contains("<RECORD>"))
        assertTrue(out.contains("</RECORD>"))
        assertTrue(out.contains("</RECORDS>"))
        assertTrue(out.trimEnd().endsWith("</ADX>"))
        // ADX 无 EOH（那是 ADI 的 Header 结束标记）
        assertFalse(out.contains("EOH"))
    }

    @Test
    fun elements_uppercase_with_plain_values() {
        val out = XmlExporter.export(listOf(record()))
        assertTrue(out.contains("<CALL>BG7HIM</CALL>"))
        assertTrue(out.contains("<GRIDSQUARE>OM44</GRIDSQUARE>"))
        assertTrue(out.contains("<BAND>40m</BAND>"))
        assertTrue(out.contains("<FREQ>7.074</FREQ>"))
        assertTrue(out.contains("<MODE>FT8</MODE>"))
        assertTrue(out.contains("<STATION_CALLSIGN>BA1ABC</STATION_CALLSIGN>"))
        assertTrue(out.contains("<OPERATOR>BA1ABC</OPERATOR>"))
    }

    @Test
    fun qso_date_is_yyyymmdd_not_iso() {
        val out = XmlExporter.export(listOf(record()))
        // ADX 与 ADI 同用 YYYYMMDD/HHMMSS，而非 ISO 8601
        assertTrue(out.contains("<QSO_DATE>20260827</QSO_DATE>"))
        assertTrue(out.contains("<TIME_ON>141500</TIME_ON>"))
        assertFalse(out.contains("2026-08-27"))
        assertFalse(out.contains("14:15:00"))
    }

    @Test
    fun comment_intl_carries_chinese() {
        val out = XmlExporter.export(listOf(record(comment = "信号很好 73")))
        assertTrue(out.contains("<COMMENT_INTL>信号很好 73</COMMENT_INTL>"))
    }

    @Test
    fun escapes_ampersand_and_angle_brackets() {
        val out = XmlExporter.export(listOf(record(comment = "A&B<C>D")))
        assertTrue(out.contains("<COMMENT_INTL>A&amp;B&lt;C&gt;D</COMMENT_INTL>"))
    }

    @Test
    fun empty_fields_omitted() {
        val out = XmlExporter.export(
            listOf(record(grid = null, operator = null, myGrid = null, comment = null))
        )
        assertFalse(out.contains("GRIDSQUARE"))
        assertFalse(out.contains("RST_SENT"))
        assertFalse(out.contains("RST_RCVD"))
        assertFalse(out.contains("OPERATOR"))
        assertFalse(out.contains("MY_GRIDSQUARE"))
        assertFalse(out.contains("COMMENT_INTL"))
        assertFalse(out.contains("QSO_COMPLETE"))
    }

    @Test
    fun qso_complete_only_when_complete() {
        val complete = XmlExporter.export(listOf(record(isComplete = true)))
        assertTrue(complete.contains("<QSO_COMPLETE>Y</QSO_COMPLETE>"))

        val incomplete = XmlExporter.export(listOf(record(isComplete = false)))
        assertFalse(incomplete.contains("QSO_COMPLETE"))
    }

    @Test
    fun multiple_records_each_wrapped() {
        val out = XmlExporter.export(listOf(record(callsign = "W1AW"), record(callsign = "K1ABC")))
        assertEquals(2, Regex("<RECORD>").findAll(out).count())
        assertTrue(out.contains("<CALL>W1AW</CALL>"))
        assertTrue(out.contains("<CALL>K1ABC</CALL>"))
    }
}
