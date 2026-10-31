package com.example.hamkit.data.satellite

/**
 * 卫星工作模式全集（对齐 Look4Sat `Sources.satelliteModes`，60 种）。
 *
 * 用于筛选页的模式选项与转发器模式标签的规范化。
 * 转发器实际模式（SatNOGS DB）可能与此列表有交集之外的值，
 * 筛选时以"包含匹配"为原则，列表仅作 UI 选项来源。
 */
object SatelliteModes {

    /** 全部模式（Look4Sat 同源列表） */
    val ALL: List<String> = listOf(
        "4FSK", "64-QAM", "AFSK", "AFSK TUBiX10", "AHRPT", "AM", "APT", "ASK", "BPSK",
        "BPSK PMT-A3", "CERTO", "CW", "DATV", "DBPSK", "DOKA", "DPSK", "DQPSK", "DSB",
        "DSTAR", "DUV", "DVB-S2", "FFSK", "FM", "FMN", "FSK", "FSK AX.100 Mode 5",
        "FSK AX.100 Mode 6", "FSK AX.25 G3RUH", "FT8", "GENESIS FSK", "GFSK", "GFSK Pkst",
        "GFSK Rktr", "GFSK/BPSK", "GMSK", "GMSK USP", "HRPT", "LoRa", "LRPT", "LSB",
        "MFSK", "MSK", "MSK AX.100 Mode 5", "MSK AX.100 Mode 6", "OFDM", "OQPSK", "PPM",
        "PSK", "PSK31", "PSK63", "QPSK", "QPSK31", "QPSK63", "SIDLOC", "SQPSK", "SSDV",
        "SSTV", "UNKNOWN", "USB", "WSJT"
    )

    /**
     * 与 [mode] 忽略大小写匹配的模式名；无匹配时返回原值。
     * 用于把转发器模式映射为筛选选项（如 "FM" → "FM"）。
     */
    fun normalize(mode: String): String =
        ALL.firstOrNull { it.equals(mode, ignoreCase = true) } ?: mode
}
