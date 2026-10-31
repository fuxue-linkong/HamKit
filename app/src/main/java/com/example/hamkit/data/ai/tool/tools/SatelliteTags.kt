package com.example.hamkit.data.ai.tool.tools

import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteCatalog
import com.example.hamkit.data.satellite.SatelliteInfo
import com.example.hamkit.data.satellite.SatelliteNameMatcher
import java.time.Instant
import java.util.Locale

/**
 * 卫星检索标签。
 *
 * 标签之间是 AND 关系：`tags = ["amateur", "visible"]` 表示"业余卫星且当前在境"。
 * 模型通过 `search_satellites` 的 `tags` 参数传入，值用 [wire] 的小写形式。
 */
enum class SatelliteTag(val wire: String, val label: String) {
    AMATEUR("amateur", "业余卫星"),
    VISIBLE("visible", "当前在境"),
    UPCOMING("upcoming", "有未来过境"),
    FM("fm", "FM"),
    SSB("ssb", "SSB"),
    CW("cw", "CW"),
    SSTV("sstv", "SSTV"),
    APRS("aprs", "APRS"),
    FT8("ft8", "FT8"),
    DSTAR("dstar", "D-STAR"),
    GEO("geo", "静止/深空"),
    DAYLIGHT("daylight", "白天过境"),
    HEARD("heard", "AMSAT 听到"),
    REPEATER("repeater", "有活跃转发器"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String): SatelliteTag? = byWire[value.trim().lowercase(Locale.US)]

        /** 给工具 schema 与提示词共用的说明文本。 */
        const val DOC: String =
            "可用 tag（AND 组合）：amateur(业余卫星)、visible(当前在境)、upcoming(有未来过境)、" +
                "fm/ssb/cw/sstv/aprs/ft8/dstar(模式)、geo(静止/深空)、daylight(白天过境)、" +
                "heard(AMSAT 听到)、repeater(有活跃转发器)"
    }
}

/** tag 解析结果。 */
sealed interface TagParseResult {
    data class Success(val tags: Set<SatelliteTag>) : TagParseResult

    data class Failure(val message: String) : TagParseResult
}

/**
 * 解析 tag（纯 Kotlin 参数，便于 JVM 单测）。
 *
 * [rawTags] 可以是数组元素或逗号分隔字符串展开后的列表；[mode] 是旧参数
 * （等价追加对应模式 tag），[onlyAbove] 等价追加 [SatelliteTag.VISIBLE]。
 * 未知 tag 不静默忽略，而是返回可读错误，避免模型误以为过滤生效。
 */
fun parseSatelliteTags(
    rawTags: List<String>?,
    mode: String?,
    onlyAbove: Boolean,
): TagParseResult {
    val tags = linkedSetOf<SatelliteTag>()

    fun add(raw: String): TagParseResult.Failure? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val tag = SatelliteTag.fromWire(value)
            ?: return TagParseResult.Failure(
                "未知标签「$value」。${SatelliteTag.DOC}",
            )
        tags += tag
        return null
    }

    rawTags?.forEach { part ->
        add(part)?.let { return it }
    }
    mode?.takeIf { it.isNotBlank() }?.let { add(it)?.let { failure -> return failure } }
    if (onlyAbove) tags += SatelliteTag.VISIBLE
    return TagParseResult.Success(tags)
}

/** 单个 tag 的匹配规则。 */
fun SatelliteTag.matches(
    sat: SatelliteInfo,
    now: Instant,
    radios: List<RadioInfo>,
): Boolean = when (this) {
    SatelliteTag.AMATEUR ->
        sat.catalogNumber in SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER

    SatelliteTag.VISIBLE -> sat.isCurrentlyVisible
    SatelliteTag.UPCOMING -> sat.aosTime.isAfter(now)
    SatelliteTag.FM -> sat.modes.any { it.equals("FM", true) }
    SatelliteTag.SSB -> sat.modes.any {
        it.equals("USB", true) || it.equals("LSB", true) || it.equals("SSB", true)
    }

    SatelliteTag.CW -> sat.modes.any { it.equals("CW", true) }
    SatelliteTag.SSTV -> sat.modes.any { it.equals("SSTV", true) }
    SatelliteTag.APRS -> sat.modes.any { it.equals("APRS", true) }
    SatelliteTag.FT8 -> sat.modes.any { it.equals("FT8", true) }
    SatelliteTag.DSTAR -> sat.modes.any { it.equals("DSTAR", true) }
    SatelliteTag.GEO -> sat.isGeo
    SatelliteTag.DAYLIGHT -> sat.isDaylightPass
    SatelliteTag.HEARD ->
        sat.status.contains("Heard", true) || sat.status.contains("Crew", true)

    SatelliteTag.REPEATER -> radios.any { it.isActive }
}

/**
 * 过滤 + 排序（纯函数，便于单测）。
 *
 * - 有 `query`：沿用名称相关度分层排序（精确/前缀优先）；
 * - 无 `query`：业余卫星优先 → 在境优先 → AOS 时间。
 */
fun filterSatellites(
    all: List<SatelliteInfo>,
    query: String?,
    tags: Set<SatelliteTag>,
    now: Instant,
    radiosOf: (Int) -> List<RadioInfo>,
): List<SatelliteInfo> {
    val queryNorad = query?.toIntOrNull()
    val matched = all.filter { sat ->
        val nameOk = query == null ||
            (queryNorad != null && sat.catalogNumber == queryNorad) ||
            SatelliteNameMatcher.nameTier(query, sat.name) != null
        nameOk && tags.all { tag -> tag.matches(sat, now, radiosOf(sat.catalogNumber)) }
    }

    return if (query != null) {
        matched.sortedWith(
            compareBy<SatelliteInfo>(
                { SatelliteNameMatcher.nameTier(query, it.name) ?: Int.MAX_VALUE },
                { if (it.isCurrentlyVisible) 0 else 1 },
                { it.aosTime },
            ),
        )
    } else {
        matched.sortedWith(
            compareBy<SatelliteInfo>(
                {
                    if (it.catalogNumber in SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER) {
                        0
                    } else {
                        1
                    }
                },
                { if (it.isCurrentlyVisible) 0 else 1 },
                { it.aosTime },
            ),
        )
    }
}
