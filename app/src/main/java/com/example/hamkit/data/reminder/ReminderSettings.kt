package com.example.hamkit.data.reminder

import androidx.compose.runtime.Immutable

/**
 * 日程提醒设置项。
 *
 * @param enabled 总开关，false 时所有提醒均不触发
 * @param leadMinutes 提前提醒分钟数：5/10/15/30。默认 10。
 * @param repeatMode 重复策略：[RepeatMode.ALWAYS] 每次过境提醒；[RepeatMode.DAYTIME_ONLY] 仅白天（6:00-22:00）提醒
 * @param soundEnabled 通知声音开关
 * @param vibrationEnabled 通知振动开关
 */
@Immutable
data class ReminderSettings(
    val enabled: Boolean = true,
    val leadMinutes: Int = 10,
    val repeatMode: RepeatMode = RepeatMode.ALWAYS,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
)

/**
 * 提醒重复策略。
 */
enum class RepeatMode {
    /** 每次过境都提醒 */
    ALWAYS,

    /** 仅白天过境提醒（6:00-22:00 本地时间） */
    DAYTIME_ONLY;

    companion object {
        fun fromName(name: String?): RepeatMode =
            entries.firstOrNull { it.name == name } ?: ALWAYS
    }
}

/**
 * 单条提醒项。每颗已开启过境提醒的卫星对应一条提醒，记录下一次过境信息。
 *
 * 由卫星级提醒开关（`SatelliteCategoryConfig.reminderFlags`）驱动生成/删除，
 * 与卫星的分类归属无关，无需用户手动创建。
 */
@Immutable
data class ReminderItem(
    /** NORAD 编号，作为唯一标识 */
    val catalogNumber: Int,
    /** 卫星名称（用于通知展示） */
    val name: String,
    /** 下次 AOS 时间（UTC 毫秒） */
    val aosTimeMillis: Long,
    /** 下次 LOS 时间（UTC 毫秒） */
    val losTimeMillis: Long,
    /** 最大仰角（度） */
    val maxElevation: Double,
    /** AOS 方位角（度） */
    val aosAzimuth: Int,
    /** LOS 方位角（度） */
    val losAzimuth: Int,
    /** 工作模式列表（FM/SSTV 等），空列表表示未知 */
    val modes: List<String>,
    /** 当前是否启用提醒。用户可在提醒列表页单独关闭。 */
    val enabled: Boolean = true
)
