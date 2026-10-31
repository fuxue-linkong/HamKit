package com.example.hamkit.data.satellite

import androidx.compose.runtime.Immutable

/**
 * 卫星列表项：合并"TLE 基本信息 + 过境预测（可空）+ 转发器列表"。
 *
 * 由 [MainViewModel] 在预测完成后构建：以缓存 TLE 全量卫星为底（解决"卫星太少"，
 * 不再只显示有过境的卫星），预测结果（[pass]）仅在卫星 48h 内有过境时非空。
 *
 * @param tle TLE 元素集（名称、NORAD 编号来源）
 * @param pass 过境预测结果；卫星在当前预测窗口内无过境时为 null
 * @param radios 该卫星的转发器列表（含 active/inactive，可为空）
 */
@Immutable
data class SatelliteListItem(
    val tle: SourcedTLE,
    val pass: SatelliteInfo? = null,
    val radios: List<RadioInfo> = emptyList(),
) {
    /** NORAD 编号 */
    val catalogNumber: Int
        get() = tle.tle.catnum

    /** 卫星名称（TLE 名称，过境结果名称兜底） */
    val name: String
        get() = tle.tle.name.trim().ifEmpty { pass?.name ?: catalogNumber.toString() }

    /** 是否正在境内（有过境且 in-pass） */
    val isCurrentlyVisible: Boolean
        get() = pass?.isCurrentlyVisible ?: false

    /** 是否在地平线以上（由实时位置推断，用于排序） */
    val isAboveHorizon: Boolean
        get() = isCurrentlyVisible

    /** 是否至少有一个活跃转发器 */
    val hasActiveTransmitter: Boolean
        get() = radios.any { it.isActive }

    /**
     * 有效工作模式：活跃转发器的模式 ∪ 硬编码 catalog 模式（去重）。
     * 筛选与展示均基于此列表。
     */
    val effectiveModes: List<String>
        get() = SatelliteCatalog.effectiveModes(
            catalogNumber = catalogNumber,
            // 内核不认识 Android 的 RadioInfo，这里先把活跃转发器的模式投影成字符串
            activeRadioModes = radios.filter { it.isActive }.map { it.mode },
        )

    /** AMSAT 状态（来自过境结果，无则空） */
    val status: String
        get() = pass?.status ?: ""
}
