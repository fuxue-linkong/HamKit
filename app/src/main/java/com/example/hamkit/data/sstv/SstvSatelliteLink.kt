package com.example.hamkit.data.sstv

import com.example.hamkit.data.satellite.SatelliteCatalog

/**
 * 卫星 ↔ SSTV 解码器的联动规则（二期 T8）。
 *
 * 过境提醒链路（`AlarmManager` → [com.example.hamkit.data.reminder.ReminderReceiver] →
 * 通知）与 SSTV 接收链路原本互不相干，它让「提醒响起」与「开始收图」之间仍隔着若干次点击。
 * 这里集中两条判定规则，供通知侧（决定是否给出「接收 SSTV」入口、预置哪个模式）与
 * Activity 侧共用：
 *
 * 1. [isSstvSatellite]：该卫星值不值得给出 SSTV 入口；
 * 2. [presetModeNameFor]：给出入口时锁定哪个模式。
 *
 * 纯 Kotlin、不依赖 Android API，因此可直接在 JVM 单元测试中验证。
 */
object SstvSatelliteLink {

    /** ISS 的 NORAD 编号。ARISS 的 SSTV 活动几乎都在 ISS 上进行。 */
    const val ISS_NORAD = 25544

    /** ISS 的 ARISS 活动统一使用 PD-120（145.800 MHz，单帧约 126 秒）。 */
    const val ISS_PRESET_MODE_NAME = "PD_120"

    /** 转发器标注里代表 SSTV 的模式标签。 */
    const val SSTV_MODE_TAG = "SSTV"

    /**
     * 判定该卫星是否支持 SSTV。
     *
     * @param modes 转发器标注的工作模式（[com.example.hamkit.data.reminder.ReminderItem.modes]）
     * @param catalogNumber NORAD 编号，用于在 [modes] 缺失时回退查静态目录表
     *
     * 顺序上先用 [modes]：它来自实时下载的转发器数据，比仓库里的静态表新。
     * 但提醒项有可能拿不到 modes（例如数据源未覆盖该卫星），此时若目录表里有记录，
     * 仍应给出入口 —— 否则 ISS 这种「目录表里明确标了 SSTV」的卫星反而漏掉。
     */
    fun isSstvSatellite(modes: List<String>, catalogNumber: Int? = null): Boolean {
        if (modes.any { it.contains(SSTV_MODE_TAG, ignoreCase = true) }) return true
        val fromCatalog = catalogNumber
            ?.let { SatelliteCatalog.MODES_BY_CATALOG_NUMBER[it] }
            ?: return false
        return fromCatalog.any { it.contains(SSTV_MODE_TAG, ignoreCase = true) }
    }

    /**
     * 返回该卫星的 SSTV 预置模式名；无已知预置时返回 null（交由 VIS 自动识别）。
     *
     * 目前只有 ISS 有确定答案。其余 SSTV 卫星（如 LilacSat-2）偶发使用，模式不固定，
     * 与其猜一个不如让 VIS 去认 —— 猜错会解出一张「看着合理」的错图。
     */
    fun presetModeNameFor(catalogNumber: Int): String? =
        if (catalogNumber == ISS_NORAD) ISS_PRESET_MODE_NAME else null

    /** 同 [presetModeNameFor]，但直接返回模式枚举（名字对不上时返回 null）。 */
    fun presetModeFor(catalogNumber: Int): SstvMode? =
        presetModeNameFor(catalogNumber)?.let { name ->
            SstvMode.entries.firstOrNull { it.name == name }
        }
}
