package com.example.hamkit.data.qso

import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * ADIF (.adi, 3.1.4) 导出器测试。
 *
 * 覆盖：Header 规则（首字符非 '<'、版本字段、EOH）、字段长度前缀=字符数、
 * UTC 日期时间（跨时区固定 epoch 验证）、MHz 格式化、空字段省略、
 * qso_complete 仅导 Y、注释剥离非 ASCII。
 */
class AdifExporterTest {

    /** 固定 UTC 时间 2026-08-27 14:15:00 的记录 */
    private fun record(
        callsign: String = "BG7HIM",
        qsoTime: Long = utcEpoch(2026, 8, 27, 14, 15, 0),
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

    private fun utcEpoch(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC)
        .toInstant().toEpochMilli()

    @Test
    fun header_first_char_not_field_and_contains_version_and_eoh() {
        val out = AdifExporter.export(listOf(record()))
        assertFalse("header must not start with '<' (would be treated as no-header)", out.startsWith("<"))
        assertTrue(out.startsWith("HamKit QSO log export"))
        assertTrue(out.contains("<adif_ver:5>3.1.4"))
        assertTrue(out.contains("<programid:6>HamKit"))
        assertTrue(out.contains("<EOH>"))
        assertTrue(out.contains("<EOR>"))
        // Header 必须在第一条记录之前
        assertTrue("EOH must precede first record", out.indexOf("<EOH>") < out.indexOf("<call:"))
    }

    @Test
    fun field_length_prefix_equals_char_count() {
        val out = AdifExporter.export(listOf(record(callsign = "BG7HIM")))
        assertTrue(out.contains("<call:6>BG7HIM"))
        assertTrue(out.contains("<gridsquare:4>OM44"))
        assertTrue(out.contains("<band:3>40m"))
        assertTrue(out.contains("<mode:3>FT8"))
    }

    @Test
    fun utc_datetime_ignores_local_timezone() {
        val original = TimeZone.getDefault()
        // 本地时区设为 UTC+8：若实现误用本地时间，日期/时间都会偏移 8 小时
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val epoch = utcEpoch(2026, 8, 27, 14, 15, 0)
            val out = AdifExporter.export(listOf(record(qsoTime = epoch)))
            assertTrue(out.contains("<qso_date:8>20260827"))
            assertTrue(out.contains("<time_on:6>141500"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun freq_mhz_trailing_zeros_trimmed() {
        assertEquals("7.074", AdifExporter.formatMhz(7_074_000L))
        assertEquals("14.074", AdifExporter.formatMhz(14_074_000L))
        assertEquals("1.84", AdifExporter.formatMhz(1_840_000L))
        assertEquals("50.313", AdifExporter.formatMhz(50_313_000L))
        val out = AdifExporter.export(listOf(record(freqHz = 7_074_000L)))
        assertTrue(out.contains("<freq:5>7.074"))
    }

    @Test
    fun empty_fields_omitted() {
        val out = AdifExporter.export(
            listOf(
                record(grid = null, operator = null, myGrid = null, comment = null)
            )
        )
        assertFalse(out.contains("gridsquare"))
        assertFalse(out.contains("rst_sent"))
        assertFalse(out.contains("rst_rcvd"))
        assertFalse(out.contains("operator"))
        assertFalse(out.contains("station_callsign"))
        assertFalse(out.contains("my_gridsquare"))
        assertFalse(out.contains("comment"))
        assertFalse(out.contains("qso_complete"))
    }

    @Test
    fun rst_exported_when_not_default_minus99() {
        val out = AdifExporter.export(
            listOf(record(reportSent = "-10", reportReceived = "+05"))
        )
        assertTrue(out.contains("<rst_sent:3>-10"))
        assertTrue(out.contains("<rst_rcvd:3>+05"))
    }

    @Test
    fun qso_complete_only_when_complete() {
        val complete = AdifExporter.export(listOf(record(isComplete = true)))
        assertTrue(complete.contains("<qso_complete:1>Y"))

        val incomplete = AdifExporter.export(listOf(record(isComplete = false)))
        assertFalse(incomplete.contains("qso_complete"))
    }

    @Test
    fun comment_strips_non_ascii() {
        val out = AdifExporter.export(listOf(record(comment = "Thanks 73 你好\nQRZ")))
        // 中文与换行被剥离，剩余 ASCII 拼接（长度=字符数）
        assertTrue(out.contains("<comment:13>Thanks 73 QRZ"))
        assertFalse(out.contains("你好"))
    }

    @Test
    fun records_without_callsign_skipped() {
        val out = AdifExporter.export(
            listOf(record(callsign = " "), record(callsign = "W1AW"))
        )
        assertEquals(1, out.lineSequence().count { it == "<EOR>" })
        assertTrue(out.contains("W1AW"))
    }
}
