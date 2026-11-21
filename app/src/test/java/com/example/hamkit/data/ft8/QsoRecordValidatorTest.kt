package com.example.hamkit.data.ft8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QSO 通联记录校验器测试。
 *
 * 覆盖：呼号（空 / 超长 / 非法字符 / 大小写 / trim）、频率（空 / 非数字 / 越界 / 合法）、
 * 完整记录校验（validateRecord，呼号优先）、频段偏差软校验（isFreqFarFromBand）。
 */
class QsoRecordValidatorTest {

    // ── 呼号：结构化结果版 ──

    @Test
    fun callsignResult_empty_isInvalid() {
        val result = QsoRecordValidator.validateCallsignResult("")
        assertTrue(result is QsoRecordValidator.ValidationResult.Invalid)
        assertEquals(ValidationMessages.CALLSIGN_EMPTY, (result as QsoRecordValidator.ValidationResult.Invalid).message)
    }

    @Test
    fun callsignResult_blankWithSpaces_isTrimmedToEmpty() {
        val result = QsoRecordValidator.validateCallsignResult("   ")
        assertTrue(result is QsoRecordValidator.ValidationResult.Invalid)
    }

    @Test
    fun callsignResult_validMixedCase_passes() {
        assertEquals(
            QsoRecordValidator.ValidationResult.Valid,
            QsoRecordValidator.validateCallsignResult("BG7Him"),
        )
    }

    @Test
    fun callsignResult_leadingTrailingSpaces_trimmed() {
        assertEquals(
            QsoRecordValidator.ValidationResult.Valid,
            QsoRecordValidator.validateCallsignResult("  BG7HIM  "),
        )
    }

    @Test
    fun callsignResult_exactlyTwelveChars_passes() {
        assertEquals(
            QsoRecordValidator.ValidationResult.Valid,
            QsoRecordValidator.validateCallsignResult("AB12CD34EF5G"),
        )
    }

    @Test
    fun callsignResult_thirteenChars_isTooLong() {
        val result = QsoRecordValidator.validateCallsignResult("AB12CD34EF5GH")
        assertTrue(result is QsoRecordValidator.ValidationResult.Invalid)
        assertEquals(ValidationMessages.CALLSIGN_TOO_LONG, (result as QsoRecordValidator.ValidationResult.Invalid).message)
    }

    @Test
    fun callsignResult_withHyphen_isInvalidChars() {
        val result = QsoRecordValidator.validateCallsignResult("BG7-HIM")
        assertTrue(result is QsoRecordValidator.ValidationResult.Invalid)
        assertEquals(
            ValidationMessages.CALLSIGN_INVALID_CHARS,
            (result as QsoRecordValidator.ValidationResult.Invalid).message,
        )
    }

    @Test
    fun callsignResult_fieldNameIsCallsign() {
        val result = QsoRecordValidator.validateCallsignResult("") as QsoRecordValidator.ValidationResult.Invalid
        assertEquals("callsign", result.fieldName)
    }

    // ── 呼号：兼容层 ──

    @Test
    fun callsignCompat_empty_returnsMessage() {
        assertEquals(ValidationMessages.CALLSIGN_EMPTY, QsoRecordValidator.validateCallsign(""))
    }

    @Test
    fun callsignCompat_valid_returnsNull() {
        assertEquals(null, QsoRecordValidator.validateCallsign("BG7HIM"))
    }

    @Test
    fun callsignCompat_invalidChars_returnsMessage() {
        assertEquals(
            ValidationMessages.CALLSIGN_INVALID_CHARS,
            QsoRecordValidator.validateCallsign("BG7HIM/1"),
        )
    }

    // ── 频率（MHz 文本，兼容层）──

    @Test
    fun freqMhz_emptyText_isInvalid() {
        val (value, error) = QsoRecordValidator.validateFreqMhz("")
        assertEquals(null, value)
        assertEquals(ValidationMessages.FREQUENCY_INVALID, error)
    }

    @Test
    fun freqMhz_blankText_isInvalid() {
        val (_, error) = QsoRecordValidator.validateFreqMhz("   ")
        assertEquals(ValidationMessages.FREQUENCY_INVALID, error)
    }

    @Test
    fun freqMhz_notANumber_isInvalid() {
        val (value, error) = QsoRecordValidator.validateFreqMhz("7.0.74")
        assertEquals(null, value)
        assertEquals(ValidationMessages.FREQUENCY_INVALID, error)
    }

    @Test
    fun freqMhz_belowOneMHz_isInvalid() {
        val (value, error) = QsoRecordValidator.validateFreqMhz("0.99")
        assertEquals(null, value)
        assertEquals(ValidationMessages.FREQUENCY_INVALID, error)
    }

    @Test
    fun freqMhz_zero_isInvalid() {
        val (_, error) = QsoRecordValidator.validateFreqMhz("0")
        assertEquals(ValidationMessages.FREQUENCY_INVALID, error)
    }

    @Test
    fun freqMhz_atLowerBound_oneMHz_isValid() {
        val (value, error) = QsoRecordValidator.validateFreqMhz("1")
        assertEquals(1.0, value)
        assertEquals(null, error)
    }

    @Test
    fun freqMhz_atUpperBound_10000MHz_isValid() {
        val (value, error) = QsoRecordValidator.validateFreqMhz("10000")
        assertEquals(10_000.0, value)
        assertEquals(null, error)
    }

    @Test
    fun freqMhz_aboveUpperBound_isInvalid() {
        val (value, error) = QsoRecordValidator.validateFreqMhz("10000.1")
        assertEquals(null, value)
        assertEquals(ValidationMessages.FREQUENCY_INVALID, error)
    }

    @Test
    fun freqMhz_typicalFt8Frequency_isValid() {
        val (value, error) = QsoRecordValidator.validateFreqMhz("14.074")
        assertEquals(14.074, value!!, 1e-9)
        assertEquals(null, error)
    }

    // ── 频率（Hz，结构化结果版）──

    @Test
    fun freqHz_atLowerBound_isValid() {
        assertEquals(
            QsoRecordValidator.ValidationResult.Valid,
            QsoRecordValidator.validateFrequency(QsoRecordValidator.FREQ_MIN_HZ),
        )
    }

    @Test
    fun freqHz_belowLowerBound_isInvalid() {
        val result = QsoRecordValidator.validateFrequency(QsoRecordValidator.FREQ_MIN_HZ - 1)
        assertTrue(result is QsoRecordValidator.ValidationResult.Invalid)
        assertEquals(
            ValidationMessages.FREQUENCY_INVALID,
            (result as QsoRecordValidator.ValidationResult.Invalid).message,
        )
    }

    @Test
    fun freqHz_zero_isInvalid() {
        assertTrue(QsoRecordValidator.validateFrequency(0) is QsoRecordValidator.ValidationResult.Invalid)
    }

    @Test
    fun freqHz_negative_isInvalid() {
        assertTrue(QsoRecordValidator.validateFrequency(-1) is QsoRecordValidator.ValidationResult.Invalid)
    }

    @Test
    fun freqHz_atUpperBound_isValid() {
        assertEquals(
            QsoRecordValidator.ValidationResult.Valid,
            QsoRecordValidator.validateFrequency(QsoRecordValidator.FREQ_MAX_HZ),
        )
    }

    @Test
    fun freqHz_aboveUpperBound_isInvalid() {
        assertTrue(QsoRecordValidator.validateFrequency(QsoRecordValidator.FREQ_MAX_HZ + 1) is QsoRecordValidator.ValidationResult.Invalid)
    }

    @Test
    fun freqHz_fieldNameIsFrequency() {
        val result = QsoRecordValidator.validateFrequency(0) as QsoRecordValidator.ValidationResult.Invalid
        assertEquals("frequency", result.fieldName)
    }

    // ── 完整记录校验（validateRecord）──

    /** 呼号 / 频率合法的基线记录 */
    private fun record(
        callsign: String = "BG7HIM",
        freqHz: Long = 7_074_000L,
    ) = Ft8QsoRecord(id = 0, callsign = callsign, grid = null, freqHz = freqHz)

    @Test
    fun record_valid_passes() {
        assertEquals(
            QsoRecordValidator.ValidationResult.Valid,
            QsoRecordValidator.validateRecord(record()),
        )
    }

    @Test
    fun record_emptyCallsign_reportsCallsignFirst() {
        val result = QsoRecordValidator.validateRecord(record(callsign = ""))
        assertTrue(result is QsoRecordValidator.ValidationResult.Invalid)
        assertEquals("callsign", (result as QsoRecordValidator.ValidationResult.Invalid).fieldName)
    }

    @Test
    fun record_emptyCallsign_withBadFreq_reportsCallsignFirst() {
        // 呼号优先于频率
        val result = QsoRecordValidator.validateRecord(record(callsign = "", freqHz = 0))
        assertEquals(
            "callsign",
            (result as QsoRecordValidator.ValidationResult.Invalid).fieldName,
        )
    }

    @Test
    fun record_invalidFreq_reportsFrequency() {
        val result = QsoRecordValidator.validateRecord(record(freqHz = 0))
        assertEquals(
            "frequency",
            (result as QsoRecordValidator.ValidationResult.Invalid).fieldName,
        )
    }

    // ── 频段偏差软校验（isFreqFarFromBand）──

    @Test
    fun freqFarFromBand_atBandCenter_isFalse() {
        assertFalse(QsoRecordValidator.isFreqFarFromBand(7.074, Ft8Band.BAND_40M))
    }

    @Test
    fun freqFarFromBand_withinTwentyPercent_isFalse() {
        // 40m 中心 7.074 MHz，±20% ≈ 5.66 - 8.49 MHz
        assertFalse(QsoRecordValidator.isFreqFarFromBand(8.0, Ft8Band.BAND_40M))
        assertFalse(QsoRecordValidator.isFreqFarFromBand(6.0, Ft8Band.BAND_40M))
    }

    @Test
    fun freqFarFromBand_beyondTwentyPercent_isTrue() {
        assertTrue(QsoRecordValidator.isFreqFarFromBand(14.074, Ft8Band.BAND_40M))
    }

    @Test
    fun freqFarFromBand_exactlyAtThreshold_isFalse() {
        // 恰好 ±20% 边界（> 阈值才算偏差）
        val bandMhz = Ft8Band.BAND_40M.freqHz / 1_000_000.0
        assertFalse(QsoRecordValidator.isFreqFarFromBand(bandMhz * 1.20, Ft8Band.BAND_40M))
    }
}
