package com.example.hamkit.ai

import com.example.hamkit.data.satellite.SatelliteCatalog
import com.example.hamkit.data.satellite.SatelliteNameMatcher
import com.example.hamkit.data.satellite.SunPosition

/**
 * 卫星检索标签（对齐 Android `SatelliteTag`）。
 *
 * 标签之间是 AND 关系：`tags = ["amateur", "visible"]` 表示「业余卫星且当前在境」。
 * 模型通过 `search_satellites` 的 `tags` 参数传入，值用 [wire] 的小写形式。
 */
enum class AiSatelliteTag(val wire: String, val label: String) {
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

        fun fromWire(value: String): AiSatelliteTag? = byWire[value.trim().lowercase()]

        /** 给工具 schema 与提示词共用的说明文本。 */
        const val DOC: String =
            "可用 tag（AND 组合）：amateur(业余卫星)、visible(当前在境)、upcoming(有未来过境)、" +
                "fm/ssb/cw/sstv/aprs/ft8/dstar(模式)、geo(静止/深空)、daylight(白天过境)、" +
                "heard(AMSAT 听到)、repeater(有活跃转发器)"
    }
}

/** tag 解析结果。 */
sealed interface AiTagParseResult {
    data class Success(val tags: Set<AiSatelliteTag>) : AiTagParseResult

    data class Failure(val message: String) : AiTagParseResult
}

/**
 * 解析 tag（纯 Kotlin 参数，便于单测）。
 *
 * [rawTags] 可以是数组元素或逗号分隔字符串展开后的列表；[mode] 是旧参数
 * （等价追加对应模式 tag），[onlyAbove] 等价追加 [AiSatelliteTag.VISIBLE]。
 * 未知 tag 不静默忽略，而是返回可读错误，避免模型误以为过滤生效。
 */
fun parseAiSatelliteTags(
    rawTags: List<String>?,
    mode: String?,
    onlyAbove: Boolean,
): AiTagParseResult {
    val tags = LinkedHashSet<AiSatelliteTag>()

    fun add(raw: String): AiTagParseResult.Failure? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val tag = AiSatelliteTag.fromWire(value)
            ?: return AiTagParseResult.Failure("未知标签「$value」。${AiSatelliteTag.DOC}")
        tags += tag
        return null
    }

    rawTags?.forEach { part ->
        // 数组元素里也可能出现 "amateur,visible" 这种写法
        part.split(',').forEach { piece ->
            add(piece)?.let { return it }
        }
    }
    mode?.takeIf { it.isNotBlank() }?.let { add(it)?.let { failure -> return failure } }
    if (onlyAbove) tags += AiSatelliteTag.VISIBLE
    return AiTagParseResult.Success(tags)
}

/**
 * 单个 tag 的匹配规则。
 *
 * [radios] 是惰性取值：只有 `repeater` 标签会用到，其它标签不会触发
 * 「每颗卫星都跨语言取一次转发器」的开销（iOS 侧全量目录 1.6 万颗）。
 */
internal fun AiSatelliteTag.matches(
    sat: AiSatelliteInfo,
    nowMs: Long,
    location: AiLocationInfo?,
    radios: () -> List<AiRadioInfo>,
): Boolean = when (this) {
    AiSatelliteTag.AMATEUR -> sat.norad in SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER
    AiSatelliteTag.VISIBLE -> sat.visible
    AiSatelliteTag.UPCOMING -> sat.hasPass && sat.aosMs > nowMs
    AiSatelliteTag.FM -> sat.hasMode("FM")
    AiSatelliteTag.SSB -> sat.hasMode("USB") || sat.hasMode("LSB") || sat.hasMode("SSB")
    AiSatelliteTag.CW -> sat.hasMode("CW")
    AiSatelliteTag.SSTV -> sat.hasMode("SSTV")
    AiSatelliteTag.APRS -> sat.hasMode("APRS")
    AiSatelliteTag.FT8 -> sat.hasMode("FT8")
    AiSatelliteTag.DSTAR -> sat.hasMode("DSTAR")
    AiSatelliteTag.GEO -> sat.isGeo
    // 白天过境：取 AOS 时刻地面站的太阳仰角（与 Android CelestialComputer 同义）
    AiSatelliteTag.DAYLIGHT -> sat.hasPass && location != null &&
        SunPosition.isDaylightAt(location.latitude, location.longitude, sat.aosMs)

    AiSatelliteTag.HEARD ->
        sat.status.contains("Heard", ignoreCase = true) ||
            sat.status.contains("Crew", ignoreCase = true)

    AiSatelliteTag.REPEATER -> radios().any { it.isActive }
}

private fun AiSatelliteInfo.hasMode(mode: String): Boolean =
    modesCsv.split(',').any { it.trim().equals(mode, ignoreCase = true) }

/**
 * 过滤 + 排序（纯函数，便于单测）。
 *
 * - 有 `query`：沿用名称相关度分层排序（精确/前缀优先）；
 * - 无 `query`：业余卫星优先 → 在境优先 → AOS 时间。
 */
fun filterAiSatellites(
    all: List<AiSatelliteInfo>,
    query: String?,
    tags: Set<AiSatelliteTag>,
    nowMs: Long,
    location: AiLocationInfo?,
    radiosOf: (Int) -> List<AiRadioInfo>,
): List<AiSatelliteInfo> {
    val queryNorad = query?.toIntOrNull()
    val needsRadios = AiSatelliteTag.REPEATER in tags
    val matched = all.filter { sat ->
        val nameOk = query == null ||
            (queryNorad != null && sat.norad == queryNorad) ||
            SatelliteNameMatcher.nameTier(query, sat.name) != null
        if (!nameOk) return@filter false
        tags.all { tag ->
            tag.matches(sat, nowMs, location) {
                if (needsRadios) radiosOf(sat.norad) else emptyList()
            }
        }
    }

    return if (query != null) {
        matched.sortedWith(
            compareBy<AiSatelliteInfo>(
                { SatelliteNameMatcher.nameTier(query, it.name) ?: Int.MAX_VALUE },
                { if (it.visible) 0 else 1 },
                { if (it.hasPass) it.aosMs else Long.MAX_VALUE },
            ),
        )
    } else {
        matched.sortedWith(
            compareBy<AiSatelliteInfo>(
                { if (it.norad in SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER) 0 else 1 },
                { if (it.visible) 0 else 1 },
                { if (it.hasPass) it.aosMs else Long.MAX_VALUE },
            ),
        )
    }
}
