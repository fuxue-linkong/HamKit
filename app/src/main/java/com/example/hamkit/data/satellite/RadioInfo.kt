package com.example.hamkit.data.satellite

import java.util.Locale

/**
 * 卫星转发器（transmitter）频率信息，来自 SatNOGS DB
 * （App 读取的是自建 CDN 上的 SatNOGS 转发器快照，
 * 见 [TransmitterDataSource.TRANSMITTERS_URL]）。
 *
 * 对应 API 字段：uuid, norad_cat_id, name, uplink_low/uplink_high/uplink_mode,
 * downlink_low/downlink_high/downlink_mode, inverted, status, description。
 * 频率取各频段的下边带值（low），即静止基频。
 *
 * @param noradCatId 卫星 NORAD 编号
 * @param name 转发器名称（API "name" 字段）
 * @param uplinkHz 上行基频（Hz），无上行（仅信标/下行）时为 null
 * @param downlinkHz 下行基频（Hz），无下行时为 null
 * @param mode 工作模式（如 "FM"、"USB"、"LSB"，优先取上行模式）
 * @param inverted 是否为倒置转发器（上行/下行频带反向，线性转发器常见）
 * @param status 状态（API "status" 字段）：active / inactive / future / unknown
 * @param description 转发器描述（API "description" 字段，如 "FM Voice"）
 */
data class RadioInfo(
    val noradCatId: Int,
    val name: String,
    val uplinkHz: Long? = null,
    val downlinkHz: Long? = null,
    val mode: String = "",
    val inverted: Boolean = false,
    val status: String = STATUS_ACTIVE,
    val description: String = "",
) {
    /** 是否处于活跃状态 */
    val isActive: Boolean
        get() = status == STATUS_ACTIVE

    /**
     * 展示名称，参照 Look4Sat 处理流程（其以 API "description" 字段作为转发器标题）：
     * 优先 [description]，其次 [name]；视为"无名称"的情况：空串、空白、
     * 字面量 "null"（大小写不敏感，JSON null 经 optString 得到）。顺带剔除中括号 []。
     * 描述与名称均无效时，按"模式 + 频率"生成名称（如 "FM 145.800 MHz"），
     * 保证展示出的转发器始终有名字、不出现「未命名转发器」。
     */
    val displayName: String
        get() = (description
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: name
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: "")
            .filterNot { it == '[' || it == ']' }
            .trim()
            .ifBlank { buildFrequencyName() }

    /** 展示用模式：剔除空串/空白/字面量 "null"，无有效值时返回空串 */
    val displayMode: String
        get() = mode
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: ""

    /** 无描述/名称时按"模式 + 频率"生成展示名称（Look4Sat 风格，保证总有名字） */
    private fun buildFrequencyName(): String = buildString {
        val displayMode = displayMode
        if (displayMode.isNotBlank()) append(displayMode).append(" ")
        val freqHz = downlinkHz ?: uplinkHz
        if (freqHz != null) {
            append(String.format(Locale.US, "%.3f MHz", freqHz / 1_000_000.0))
        }
    }.trim()

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_INACTIVE = "inactive"
        const val STATUS_FUTURE = "future"
        const val STATUS_UNKNOWN = "unknown"
    }
}
