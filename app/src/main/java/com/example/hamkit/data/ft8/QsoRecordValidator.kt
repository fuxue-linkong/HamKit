package com.example.hamkit.data.ft8

/**
 * QSO 通联记录校验错误文案常量：UI 层可直接展示，也供测试断言使用。
 */
object ValidationMessages {
    const val CALLSIGN_EMPTY = "请输入呼号"
    const val CALLSIGN_TOO_LONG = "呼号不能超过 12 个字符"
    const val CALLSIGN_INVALID_CHARS = "呼号仅允许字母和数字"
    const val FREQUENCY_INVALID = "请输入有效频率（1-10000 MHz）"
}

/**
 * QSO 通联记录校验器：编辑对话框保存前与 ViewModel 落库前共用同一套规则。
 *
 * 提供两层 API：
 * - 结构化结果层（任务 3b 的 UI/ViewModel 集成契约）：
 *   [validateCallsignResult] / [validateFrequency] / [validateRecord]，
 *   错误携带 fieldName，便于 UI 按字段绑定错误提示。
 * - 兼容层（当前编辑对话框已在使用，签名保持稳定）：
 *   [validateCallsign]（返回 null/错误文案）、[validateFreqMhz]（返回 MHz 值/错误文案对）、
 *   [isFreqFarFromBand]（频段偏差软提示）。UI 迁移到结构化层后可移除兼容层。
 *
 * 规则口径（与对话框输入过滤一致）：
 * - 呼号：非空、1-12 字符、仅字母 + 数字（大小写均可，UI 输入框负责归一大写）；
 * - 频率：1-10000 MHz（1_000_000 - 10_000_000_000 Hz，覆盖 160m 至 2m 及更高 amateur 波段）；
 * - 可选软校验：频率与所选频段拨号频率偏差超过 ±20% 时不拦截，仅提示。
 */
object QsoRecordValidator {

    /** 呼号最大长度（与编辑框 take(12)、ADIF 常见上限一致） */
    const val CALLSIGN_MAX_LENGTH = 12

    /** 合法频率下限（MHz） */
    const val FREQ_MIN_MHZ = 1.0

    /** 合法频率上限（MHz），覆盖 2m 波段及更高 amateur 频段 */
    const val FREQ_MAX_MHZ = 10_000.0

    /** 合法频率下限（Hz），即 1 MHz */
    const val FREQ_MIN_HZ = 1_000_000L

    /** 合法频率上限（Hz），即 10000 MHz */
    const val FREQ_MAX_HZ = 10_000_000_000L

    /** 频段偏差软提示阈值：|输入 - 频段中心| / 频段中心 超过 20% 时提示 */
    const val BAND_DEVIATION_THRESHOLD = 0.20

    private const val FIELD_CALLSIGN = "callsign"
    private const val FIELD_FREQUENCY = "frequency"

    /** 校验结果：Valid = 通过；Invalid = 失败，携带字段名与面向用户的错误文案 */
    sealed class ValidationResult {
        data object Valid : ValidationResult()

        /** 校验失败；fieldName 用于 UI 将错误定位到具体字段（"callsign" / "frequency"） */
        data class Invalid(val fieldName: String, val message: String) : ValidationResult()
    }

    /**
     * 校验呼号（结构化结果版）：非空、[CALLSIGN_MAX_LENGTH] 字符内、仅字母 + 数字（大小写均可）。
     * 内部会 trim；失败时 fieldName 固定为 "callsign"。
     */
    fun validateCallsignResult(callsign: String): ValidationResult {
        val value = callsign.trim()
        if (value.isEmpty()) {
            return ValidationResult.Invalid(FIELD_CALLSIGN, ValidationMessages.CALLSIGN_EMPTY)
        }
        if (value.length > CALLSIGN_MAX_LENGTH) {
            return ValidationResult.Invalid(FIELD_CALLSIGN, ValidationMessages.CALLSIGN_TOO_LONG)
        }
        if (value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }) {
            return ValidationResult.Invalid(FIELD_CALLSIGN, ValidationMessages.CALLSIGN_INVALID_CHARS)
        }
        return ValidationResult.Valid
    }

    /**
     * 校验频率（Hz，结构化结果版）：范围 [FREQ_MIN_HZ]-[FREQ_MAX_HZ]，即 1-10000 MHz。
     * freqHz <= 0 一律无效；失败时 fieldName 固定为 "frequency"。
     */
    fun validateFrequency(freqHz: Long): ValidationResult {
        if (freqHz < FREQ_MIN_HZ || freqHz > FREQ_MAX_HZ) {
            return ValidationResult.Invalid(FIELD_FREQUENCY, ValidationMessages.FREQUENCY_INVALID)
        }
        return ValidationResult.Valid
    }

    /**
     * 完整记录校验（供 ViewModel 落库守卫）：呼号 + 频率两项硬校验，
     * 返回第一个失败的校验结果（优先级：呼号 > 频率）。
     */
    fun validateRecord(record: Ft8QsoRecord): ValidationResult {
        val callsignResult = validateCallsignResult(record.callsign)
        if (callsignResult is ValidationResult.Invalid) return callsignResult
        return validateFrequency(record.freqHz)
    }

    // ---- 兼容层：当前编辑对话框使用，UI 迁移到结构化层后可移除 ----

    /**
     * 校验呼号：非空、[CALLSIGN_MAX_LENGTH] 字符内、仅字母 + 数字（大小写均可）。
     * 返回 null 表示通过，否则为错误文案。
     */
    fun validateCallsign(callsign: String): String? {
        val value = callsign.trim()
        if (value.isEmpty()) return ValidationMessages.CALLSIGN_EMPTY
        if (value.length > CALLSIGN_MAX_LENGTH) return ValidationMessages.CALLSIGN_TOO_LONG
        if (value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }) {
            return ValidationMessages.CALLSIGN_INVALID_CHARS
        }
        return null
    }

    /**
     * 校验频率文本：非空、可解析为数字、范围 [FREQ_MIN_MHZ]-[FREQ_MAX_MHZ] MHz。
     * 返回解析成功的 MHz 值；失败返回 null 并由 second 给出错误文案。
     */
    fun validateFreqMhz(freqText: String): Pair<Double?, String?> {
        val text = freqText.trim()
        if (text.isEmpty()) return null to ValidationMessages.FREQUENCY_INVALID
        val value = text.toDoubleOrNull()
            ?: return null to ValidationMessages.FREQUENCY_INVALID
        if (value < FREQ_MIN_MHZ || value > FREQ_MAX_MHZ) {
            return null to ValidationMessages.FREQUENCY_INVALID
        }
        return value to null
    }

    /**
     * 软校验：频率与所选频段拨号频率的偏差是否超出 ±[BAND_DEVIATION_THRESHOLD]。
     * 返回 true 表示偏差过大（仅提示，不拦截）。
     */
    fun isFreqFarFromBand(freqMhz: Double, band: Ft8Band): Boolean {
        val bandMhz = band.freqHz / 1_000_000.0
        if (bandMhz <= 0.0) return false
        return kotlin.math.abs(freqMhz - bandMhz) / bandMhz > BAND_DEVIATION_THRESHOLD
    }
}
