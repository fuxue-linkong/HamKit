package com.example.hamkit.data.satellite

/**
 * 业余卫星目录，维护 NORAD 编号与工作模式的映射。
 * 供数据源和预测器共享使用。
 */
object SatelliteCatalog {

    /**
     * 业余卫星常见工作模式，按 NORAD 编号维护。
     */
    val MODES_BY_CATALOG_NUMBER: Map<Int, List<String>> = mapOf(
        // FM
        25544 to listOf("FM", "SSTV"),      // ISS
        43017 to listOf("FM"),              // AO-91 (Fox-1B)
        43137 to listOf("FM"),              // AO-92 (Fox-1D)
        61781 to listOf("FM"),              // AO-123 (ASRTU-1)
        27607 to listOf("FM"),              // SO-50
        22825 to listOf("FM"),              // AO-27
        43678 to listOf("FM"),              // PO-101 (Diwata-2)
        40908 to listOf("FM", "SSTV"),      // LilacSat-2
        41909 to listOf("FM"),              // BY70-1
        42684 to listOf("FM"),              // CAS-3H
        // 线性转发器（CW / USB / LSB）
        7530 to listOf("CW", "USB", "LSB"), // AO-7
        24278 to listOf("CW", "USB", "LSB"),// FO-29
        39417 to listOf("CW", "USB", "LSB"),// AO-73 (FUNcube-1)
        42017 to listOf("CW", "USB", "LSB"),// EO-88
        43854 to listOf("CW", "USB", "LSB"),// JO-97
        42761 to listOf("CW", "USB", "LSB"),// CAS-4A
        42759 to listOf("CW", "USB", "LSB"),// CAS-4B
        40903 to listOf("CW", "USB", "LSB"),// XW-2A
        40911 to listOf("CW", "USB", "LSB"),// XW-2B
        40906 to listOf("CW", "USB", "LSB"),// XW-2C
        40907 to listOf("CW", "USB", "LSB"),// XW-2D
        40910 to listOf("CW", "USB", "LSB"),// XW-2F
        // D-Star
        43879 to listOf("DSTAR")            // D-Star ONE
    )

    /** 我们关心的所有 NORAD 编号集合 */
    val catalogNumbers: Set<Int> get() = MODES_BY_CATALOG_NUMBER.keys

    /**
     * 有效工作模式：活跃转发器的模式 ∪ 硬编码目录模式，按首次出现顺序去重。
     *
     * 单一事实来源：卫星列表项、过境预测、AI 工具都调用这里，避免“列表页有模式、
     * 其它地方没模式”的两套聚合逻辑。
     *
     * **参数为什么是模式字符串而不是转发器对象**：内核（commonMain）不能依赖各平台的
     * 转发器模型（Android 是 `RadioInfo`、iOS 是 `Transmitter`），所以由调用方先把
     * 「活跃转发器的模式」投影成字符串再传进来。语义与 Android 侧
     * `radios.filter { it.isActive }.map { it.mode }` 完全一致。
     */
    fun effectiveModes(catalogNumber: Int, activeRadioModes: List<String>): List<String> =
        (
            activeRadioModes.filter { it.isNotBlank() } +
                MODES_BY_CATALOG_NUMBER[catalogNumber].orEmpty()
            ).distinct()

    /**
     * TLE 名称到 AMSAT 状态 API 名称的映射。
     * AMSAT API 使用带模式的名称（如 "AO-91_[FM]"），而 TLE 名称可能不同。
     */
    val AMSAT_STATUS_NAME_BY_CATALOG_NUMBER: Map<Int, String> = mapOf(
        25544 to "ISS_[FM]",
        43017 to "AO-91_[FM]",
        43137 to "AO-92_[FM]",
        61781 to "AO-123_[FM]",
        27607 to "SO-50_[FM]",
        22825 to "AO-27_[FM]",
        43678 to "PO-101_[FM]",
        40908 to "LilacSat-2_[FM]",
        7530 to "AO-7_[U/v]",
        24278 to "FO-29_[V/u]",
        39417 to "AO-73_[U/v]",
        42017 to "EO-88_[U/v]",
        43854 to "JO-97_[U/v]",
        42761 to "CAS-4A_[U/v]",
        42759 to "CAS-4B_[U/v]",
        40903 to "XW-2A_[U/v]",
        40911 to "XW-2B_[U/v]",
        40906 to "XW-2C_[U/v]",
        40907 to "XW-2D_[U/v]",
        40910 to "XW-2F_[U/v]",
        43879 to "D-Star_ONE_[DStar]"
    )
}
