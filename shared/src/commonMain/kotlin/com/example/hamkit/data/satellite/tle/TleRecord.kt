package com.example.hamkit.data.satellite.tle

import com.example.hamkit.data.satellite.predict.TleElements

/**
 * 一条卫星 TLE（名称 + 两行数据），跨平台可自由传递。
 *
 * 与 [TleElements] 的区别：TleRecord 保留**原始文本**，便于缓存到磁盘、
 * 再传给 `HamKitApi.satellitePosition(tle = record.lines, ...)`；
 * [TleElements] 是解析后的轨道元素，解析失败会抛异常。
 */
data class TleRecord(
    val name: String,
    val norad: Int,
    val line1: String,
    val line2: String,
) {
    /** 三行格式（名称 + line1 + line2），可直接喂给 SGP4 接口。 */
    val lines: List<String>
        get() = listOf(name, line1, line2)

    /** 解析成轨道元素；非法 TLE 返回 null。 */
    fun elementsOrNull(): TleElements? = try {
        TleElements.fromThreeLines(arrayOf(name, line1, line2))
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * CelesTrak 的 gp.php 分组名。
 *
 * 只收录 HamKit 用得到的几个；新增分组时记得同时确认 iOS / Android 两侧的
 * 下拉选项（目前 iOS 只用 AMATEUR）。
 */
enum class TleGroup(val wireName: String) {
    AMATEUR("amateur"),
    SATNOGS("satnogs"),
    ACTIVE("active"),
    WEATHER("weather"),
    ;

    companion object {
        /** 从字符串还原分组（大小写不敏感），未知值回落到 [AMATEUR]。 */
        fun fromWireName(value: String): TleGroup =
            entries.firstOrNull { it.wireName == value.trim().lowercase() } ?: AMATEUR
    }
}

/** 拉取结果：成功时 records 非空且 error 为 null。 */
data class TleFetchOutcome(
    val records: List<TleRecord>,
    val error: String?,
)

/** 仓库返回给 UI 的快照（可能来自缓存）。 */
data class TleSnapshot(
    val records: List<TleRecord>,
    /** 这份数据的获取时间（UTC 毫秒）；0 表示没有成功拉取过 */
    val fetchedAtMs: Long,
    /** true 表示本次直接命中缓存，没有发网络请求 */
    val fromCache: Boolean,
    /** 非空表示本次拉取失败（此时 records 可能是上一次的缓存） */
    val error: String?,
)

/**
 * CelesTrak 三行 TLE 文本解析器。
 *
 * gp.php 在 `FORMAT=tle` 下返回的标准格式是：
 * ```
 * ISS (ZARYA)
 * 1 25544U 98067A   ...
 * 2 25544  51.6323  ...
 *
 * ESH'HAIL-2
 * 1 43700U 18090A    ...
 * 2 43700   0.0213  ...
 * ```
 * 以「line1 以 `1 ` 开头、下一行以 `2 ` 开头」为一条记录的分界，名称取上一行；
 * 没有名称时用 NORAD 编号兜底。解析失败的记录跳过，不中断整批。
 */
object TleTextParser {

    fun parse(text: String): List<TleRecord> {
        val lines = text.split('\n').map { it.trimEnd('\r').trimEnd() }
        val result = LinkedHashMap<Int, TleRecord>()
        var i = 0
        while (i < lines.size - 1) {
            val line1 = lines[i]
            val line2 = lines[i + 1]
            if (line1.startsWith("1 ") && line2.startsWith("2 ")) {
                val name = previousName(lines, i) ?: fallbackName(line1)
                val record = buildRecord(name, line1, line2)
                if (record != null) {
                    // 同一 NORAD 出现多次时保留最后一次（CelesTrak 偶尔会重复分组）
                    result[record.norad] = record
                }
                i += 2
            } else {
                i += 1
            }
        }
        return result.values.toList()
    }

    private fun previousName(lines: List<String>, line1Index: Int): String? {
        if (line1Index <= 0) return null
        val candidate = lines[line1Index - 1].trim()
        if (candidate.isEmpty()) return null
        if (candidate.startsWith("1 ") || candidate.startsWith("2 ")) return null
        return candidate
    }

    private fun fallbackName(line1: String): String {
        val norad = line1.takeIf { it.length >= 7 }?.substring(2, 7)?.trim().orEmpty()
        return if (norad.isEmpty()) "UNKNOWN" else "NORAD $norad"
    }

    private fun buildRecord(name: String, line1: String, line2: String): TleRecord? {
        if (line1.length < 69 || line2.length < 69) return null
        val norad = line1.substring(2, 7).trim().toIntOrNull() ?: return null
        if (norad <= 0) return null
        // 用真正的解析器做一次校验：列位/数值不合法的行不要进入缓存
        val parsed = try {
            TleElements.fromThreeLines(arrayOf(name, line1, line2))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return TleRecord(
            name = name.ifBlank { "NORAD $norad" },
            norad = parsed.catalogNumber,
            line1 = line1,
            line2 = line2,
        )
    }
}
