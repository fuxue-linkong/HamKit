package com.example.hamkit.ai

import com.example.hamkit.data.satellite.SatelliteNameMatcher
import com.example.hamkit.data.satellite.predict.SatellitePropagator
import com.example.hamkit.data.satellite.predict.TleElements
import com.example.hamkit.data.zone.ZoneResolver
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 平台数据门面：iOS / Android 各自实现，把「当前卫星列表、转发器、定位」交给内核。
 *
 * 只暴露值对象，不暴露平台类型——内核因此可以在两端复用同一套工具实现，
 * 模型看到的字段名与 Android 端一致。
 */
interface AiAppFacade {

    /** 卫星目录（含过境信息；无过境时 [AiSatelliteInfo.hasPass] = false）。 */
    fun satellites(): List<AiSatelliteInfo>

    /** 某颗卫星的转发器。 */
    fun radios(norad: Int): List<AiRadioInfo>

    /** AMSAT 状态文案；无则 null。 */
    fun amsatStatus(norad: Int): String?

    /** 当前地面站坐标；无定位时 null。 */
    fun location(): AiLocationInfo?

    /** 卫星 TLE（实时位置需要）；无则 null。 */
    fun tle(norad: Int): AiTleInfo?

    /** 当前 UTC 毫秒。 */
    fun nowMs(): Long

    /**
     * 执行动作（跳页 / 建提醒）。返回给模型的一句话结果。
     *
     * @param action `open_screen` 或 `add_pass_reminder`
     * @param norad 卫星编号（`open_screen` 时为 0）
     * @param screen 页面 id（`add_pass_reminder` 时为空）
     */
    fun performAction(action: String, norad: Int, screen: String): String

    /** FT8 当前会话快照（解码 + QSO）；模块不可用时返回 null。 */
    fun ft8Snapshot(): AiFt8Snapshot?

    /** APRS 当前会话已接收的台站（已脱敏，不含经纬度）；模块不可用时返回 null。 */
    fun aprsSnapshot(): AiAprsSnapshot?

    /** QSO 日志全量摘要（FT8 与手动记录同一库）。 */
    fun qsoRecords(): List<AiQsoSummary>

    /**
     * 强制重新获取定位（忽略缓存）；失败返回 null。默认实现退化为普通取值。
     * 平台实现应触发一次真实的 GPS 刷新。
     */
    fun refreshLocation(): AiLocationInfo? = location()

    /** 写入一条 QSO 日志，返回正数行 id；失败返回 -1。 */
    fun addQso(draft: AiQsoDraft): Long = -1L
}

/** 待写入的 QSO 记录（字段与 Android `Ft8QsoRecord` / 内核 `QsoRecord` 对齐）。 */
data class AiQsoDraft(
    val callsign: String,
    val grid: String? = null,
    val reportSent: String = "-99",
    val reportReceived: String = "-99",
    val band: String = "40m",
    val freqHz: Long = 7_074_000L,
    val mode: String = "FT8",
    val qsoTimeMs: Long = 0L,
    val isComplete: Boolean = true,
    val myGrid: String? = null,
    val comment: String? = null,
)

/** 一条 FT8 解码（工具只读快照）。 */
data class AiFt8Decode(
    val timeMs: Long,
    val message: String,
    val snrDb: Double,
    val freqHz: Double,
)

/** QSO 记录摘要。 */
data class AiQsoSummary(
    val callsign: String,
    val grid: String,
    val band: String,
    val mode: String,
    val qsoTimeMs: Long,
    val isComplete: Boolean,
)

/** FT8 模块快照。 */
data class AiFt8Snapshot(
    val decoding: Boolean,
    val decoded: List<AiFt8Decode>,
    val qso: List<AiQsoSummary>,
)

/**
 * APRS 已接收台站摘要。
 *
 * 只带经纬度供内核换算「距离/方位」，工具输出**不含**坐标 —— 模型看到的始终是
 * 距离与方位，与 Android 的脱敏口径一致。换算放在内核是为了两端同一份公式，
 * 也避免 Kotlin `Double?` 跨语言装箱给 Swift 侧增加构造负担。
 */
data class AiAprsStationSummary(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val comment: String,
)

/** APRS 模块快照（只读当前会话已接收的站点）。 */
data class AiAprsSnapshot(
    val connected: Boolean,
    val stations: List<AiAprsStationSummary>,
)

data class AiSatelliteInfo(
    val norad: Int,
    val name: String,
    /** 有效模式，逗号分隔（活跃转发器模式 ∪ 目录模式）。 */
    val modesCsv: String,
    val visible: Boolean,
    val hasPass: Boolean,
    val aosMs: Long,
    val losMs: Long,
    val maxElevationDeg: Double,
    val aosAzimuthDeg: Int,
    val losAzimuthDeg: Int,
    val isGeo: Boolean,
    val status: String,
)

data class AiRadioInfo(
    val name: String,
    val uplinkHz: Long,
    val downlinkHz: Long,
    val mode: String,
    val status: String,
) {
    val isActive: Boolean get() = status == "active"
}

data class AiLocationInfo(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
)

data class AiTleInfo(
    val name: String,
    val line1: String,
    val line2: String,
)

/**
 * 内置工具箱：与 Android `AiToolRegistry.ALL` 对应的 iOS 可用子集。
 *
 * 与 Android 的差异（已在 docs/UPSTREAM_SYNC_2026-10-31.md 记录）：
 * - 没有 `get_weather`（iOS 天气由 WeatherStore 单独负责，暂不接进对话）；
 * - 没有 `cw_encode` / `start_sstv_receive`（涉及音频会话，留给后续阶段）；
 * - `get_satellite_live` 不含「是否在地影」与遥测相位（需要额外的天体计算）。
 */
class AiToolbox(private val facade: AiAppFacade) : AiToolHost {

    private val tools: List<AiTool> = listOf(
        ZoneInfoTool,
        RefreshLocationTool,
        SatellitePassesTool,
        SatelliteLiveTool,
        SatelliteTransmittersTool,
        SearchSatellitesTool,
        AmsatStatusTool,
        Ft8ActivityTool,
        AprsStationsTool,
        QsoLogTool,
        AddQsoLogTool,
        UtcTimeTool,
        OpenScreenTool,
        PassReminderTool,
    )

    override fun specs(): List<AiToolSpec> = tools.map { it.spec }

    override fun execute(call: AiToolCall, callback: (AiToolResult) -> Unit) {
        val tool = tools.firstOrNull { it.spec.name == call.name }
        if (tool == null) {
            callback(errorResult("未知工具：${call.name}"))
            return
        }
        val args = Json.parse(call.argsJson.ifBlank { "{}" })?.objOrNull()
        if (args == null) {
            callback(errorResult("工具参数不是合法 JSON：${call.argsJson.take(120)}"))
            return
        }
        callback(runCatching { tool.execute(facade, args) }.getOrElse {
            errorResult("「${tool.spec.title}」失败：${it.message ?: "未知错误"}")
        })
    }

    private fun errorResult(message: String): AiToolResult =
        AiToolResult(modelText = message, summary = message, isError = true)
}

// ── 工具基类与参数助手 ──────────────────────────────────────────────────────

internal abstract class AiTool {
    abstract val spec: AiToolSpec

    abstract fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult
}

private fun Json.Obj.str(key: String): String? =
    this[key]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun Json.Obj.int(key: String): Int? = this[key]?.intOrNull()

private fun Json.Obj.bool(key: String): Boolean? = this[key]?.boolOrNull()

/** 极简 JSON Schema 构造：只用到 object/string/integer/boolean 三种类型。 */
private fun schema(vararg props: Triple<String, String, String>): String {
    val properties = Json.obj()
    props.forEach { (name, type, description) ->
        properties.put(name, Json.obj().put("type", type).put("description", description))
    }
    return Json.obj().put("type", "object").put("properties", properties).stringify()
}

/** 名称/编号 → 唯一卫星；歧义时自动取排序第一的候选（附带候选列表供纠正）。 */
private sealed interface Lookup {
    data class Found(
        val satellite: AiSatelliteInfo,
        val autoMatched: Boolean = false,
        val candidates: List<AiSatelliteInfo> = emptyList(),
    ) : Lookup

    data class Failed(val message: String) : Lookup
}

private fun resolveCandidates(facade: AiAppFacade, query: String): List<AiSatelliteInfo> {
    val all = facade.satellites()
    if (all.isEmpty()) return emptyList()
    val candidates = all.map { SatelliteNameMatcher.Candidate(it.norad, it.name) }
    return when (val resolution = SatelliteNameMatcher.resolve(query, candidates)) {
        is SatelliteNameMatcher.Resolution.Unique -> {
            val norad = resolution.candidate.catalogNumber
            all.filter { it.norad == norad }
        }

        // 名称歧义时用排序第一的候选，避免模型反复重试；结果里附带候选，可再用 norad_id 纠正
        is SatelliteNameMatcher.Resolution.Ambiguous -> {
            val norads = resolution.candidates.take(8).map { it.catalogNumber }.toSet()
            all.filter { it.norad in norads }
                .sortedBy { sat -> resolution.candidates.indexOfFirst { it.catalogNumber == sat.norad } }
        }

        SatelliteNameMatcher.Resolution.NotFound -> emptyList()
    }
}

/**
 * 从工具参数里解析卫星引用：norad_id 优先于名称（模型常把 NORAD 放进 norad_id，
 * 避免 "ISS" 这类歧义）；两者都缺时返回统一错误。
 */
private fun satelliteQueryFromArgs(args: Json.Obj): String? {
    val norad = args.int("norad_id")
    if (norad != null && norad > 0) return norad.toString()
    return args.str("satellite") ?: args.str("query")
}

private fun AiAppFacade.lookupFromArgs(args: Json.Obj): Lookup {
    val query = satelliteQueryFromArgs(args)
        ?: return Lookup.Failed("缺少卫星名称或 NORAD 编号（可传 satellite 或 norad_id）")
    val candidates = resolveCandidates(this, query)
    return when {
        candidates.isEmpty() -> Lookup.Failed("没有找到卫星 \"$query\"，请给出名称或 NORAD 编号")
        candidates.size > 1 -> Lookup.Found(
            satellite = candidates.first(),
            autoMatched = true,
            candidates = candidates.take(8),
        )

        else -> Lookup.Found(candidates.first())
    }
}

private fun msToIso(ms: Long): String = isoUtc(ms)

/** UTC 毫秒 → `yyyy-MM-ddTHH:mm:ssZ`（内核不依赖 kotlinx-datetime，手算即可）。 */
internal fun isoUtc(ms: Long): String {
    var seconds = ms / 1000
    val days = floorDiv(seconds, 86400)
    seconds -= days * 86400
    val hour = seconds / 3600
    val minute = (seconds % 3600) / 60
    val second = seconds % 60
    val (year, month, day) = civilFromDays(days)
    fun pad(value: Long, width: Int = 2) = value.toString().padStart(width, '0')
    return "${pad(year, 4)}-${pad(month)}-${pad(day)}T" +
        "${pad(hour)}:${pad(minute)}:${pad(second)}Z"
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
}

/** 两点球面距离（km），haversine 公式。 */
internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadiusKm * 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

/** 初始方位角（度，0 = 正北，顺时针）。 */
internal fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    val deg = atan2(y, x) * 180.0 / PI
    return if (deg < 0) deg + 360.0 else deg
}

/** 儒略日 → 公历年月日（Howard Hinnant 的 civil_from_days）。 */
private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Long, Long> {
    var z = daysSinceEpoch + 719468
    val era = floorDiv(z, 146097)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    z = if (m <= 2) y + 1 else y
    return Triple(z, m, d)
}

// ── 工具实现 ────────────────────────────────────────────────────────────────

private object UtcTimeTool : AiTool() {
    override val spec = AiToolSpec(
        name = "get_utc_time",
        title = "当前 UTC 时间",
        description = "返回当前 UTC 时间与本地时区偏移，用于换算 AOS/LOS 或回答“现在几点”。",
        parametersJson = schema(),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val now = facade.nowMs()
        val text = Json.obj()
            .put("utc", isoUtc(now))
            .put("epoch_ms", now)
            .stringify()
        return AiToolResult(modelText = text, summary = "UTC ${isoUtc(now)}")
    }
}

private object ZoneInfoTool : AiTool() {

    override val spec = AiToolSpec(
        name = "get_zone_info",
        title = "分区定位",
        description = "计算 CQ / ITU 分区与 Maidenhead 网格。不传坐标时用当前地面站位置。",
        parametersJson = schema(
            Triple("latitude", "number", "纬度（度），可省略"),
            Triple("longitude", "number", "经度（度），可省略"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val latitude = args["latitude"]?.let { it as? Json.Num }?.value
        val longitude = args["longitude"]?.let { it as? Json.Num }?.value
        val location = if (latitude != null && longitude != null) {
            AiLocationInfo(latitude, longitude, 0.0)
        } else {
            facade.location() ?: return AiToolResult(
                modelText = "没有定位结果，请先授权定位或直接给出经纬度",
                summary = "缺少定位",
                isError = true,
            )
        }
        val zone = ZoneResolver.resolve(location.latitude, location.longitude)
        val text = Json.obj()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("cq_zone", zone.cqZone ?: 0)
            .put("itu_zone", zone.ituZone ?: 0)
            .put("maidenhead", zone.maidenhead)
            .stringify()
        val summary = "CQ ${zone.cqZone ?: "?"} / ITU ${zone.ituZone ?: "?"} · ${zone.maidenhead}"
        return AiToolResult(modelText = text, summary = summary)
    }
}

private object SearchSatellitesTool : AiTool() {

    override val spec = AiToolSpec(
        name = "search_satellites",
        title = "搜索卫星",
        description = "按名称关键词、标签（tag）或是否在境检索当前卫星列表，返回名称、NORAD、模式与状态。" +
            AiSatelliteTag.DOC +
            "。max 控制返回条数，默认 20、最大 100；被截断时返回 truncated=true 与 total。" +
            "在境卫星的 AOS 可能已过去，用 visible 与 los 判断；next_aos 仅在未来有值时返回。",
        parametersJson = Json.obj()
            .put("type", "object")
            .put(
                "properties",
                Json.obj()
                    .put(
                        "query",
                        Json.obj().put("type", "string")
                            .put("description", "名称关键词或 NORAD 编号，如 ISS、25544；可省略"),
                    )
                    .put(
                        "tags",
                        Json.obj()
                            .put("type", "array")
                            .put("description", AiSatelliteTag.DOC)
                            .put("items", Json.obj().put("type", "string")),
                    )
                    .put(
                        "max",
                        Json.obj().put("type", "integer")
                            .put("description", "返回上限，默认 20")
                            .put("minimum", 1).put("maximum", 100),
                    )
                    .put(
                        "mode",
                        Json.obj().put("type", "string")
                            .put("description", "（兼容旧参数）工作模式，等价 tags 里的模式"),
                    )
                    .put(
                        "only_above",
                        Json.obj().put("type", "boolean")
                            .put("description", "（兼容旧参数）只返回当前在境卫星"),
                    ),
            )
            .stringify(),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val all = facade.satellites()
        if (all.isEmpty()) {
            return AiToolResult("卫星列表为空，请先在首页刷新卫星数据", "列表为空", isError = true)
        }
        val query = args.str("query")
        val rawTags = when (val raw = args["tags"]) {
            is Json.Arr -> raw.items.mapNotNull { it.stringOrNull() }
            is Json.Str -> raw.value.split(',')
            else -> emptyList()
        }
        val tags = when (
            val parsed = parseAiSatelliteTags(
                rawTags = rawTags,
                mode = args.str("mode"),
                onlyAbove = args.bool("only_above") == true,
            )
        ) {
            is AiTagParseResult.Success -> parsed.tags
            is AiTagParseResult.Failure ->
                return AiToolResult(parsed.message, "标签无效", isError = true)
        }
        val location = facade.location()
        if (AiSatelliteTag.DAYLIGHT in tags && location == null) {
            return AiToolResult(
                modelText = "没有定位结果，无法判断白天过境；请先授权定位",
                summary = "缺少定位",
                isError = true,
            )
        }
        // 默认 20 颗；兼容旧参数 limit
        val max = (args.int("max") ?: args.int("limit") ?: 20).coerceIn(1, 100)
        val now = facade.nowMs()

        val sorted = filterAiSatellites(all, query, tags, now, location) { norad ->
            facade.radios(norad)
        }
        val picked = sorted.take(max)
        val truncated = sorted.size > picked.size

        val array = Json.arr()
        picked.forEach { sat ->
            val obj = Json.obj()
                .put("name", sat.name)
                .put("norad", sat.norad)
                .put("modes", Json.Arr().also { arr ->
                    sat.modesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { arr.add(it) }
                })
                .put("visible", sat.visible)
                .put("is_geo", sat.isGeo)
            if (sat.status.isNotBlank()) obj.put("amsat_status", sat.status)
            if (sat.hasPass) {
                obj.put("los", isoUtc(sat.losMs))
                obj.put("max_elevation", sat.maxElevationDeg)
                if (sat.aosMs > now) {
                    obj.put("next_aos", isoUtc(sat.aosMs))
                } else if (sat.visible) {
                    obj.put("in_pass_since", isoUtc(sat.aosMs))
                }
            }
            array.add(obj)
        }

        val text = Json.obj()
            .put("total", sorted.size)
            .put("returned", picked.size)
            .put("truncated", truncated)
            .put("tags", Json.Arr().also { arr -> tags.forEach { arr.add(it.wire) } })
            .put("in_pass_count", picked.count { it.visible })
            .put("satellites", array)
            .also {
                if (AiSatelliteTag.REPEATER in tags && picked.isEmpty()) {
                    it.put("note", "转发器库为空或没有匹配的活跃转发器，可先到卫星页刷新转发器数据")
                }
            }
            .stringify()
        val summary = if (truncated) {
            "找到 ${picked.size} 颗（共 ${sorted.size}，已截断）"
        } else {
            "找到 ${picked.size} 颗"
        }
        return AiToolResult(
            modelText = text,
            summary = summary,
            cardJson = satelliteCardJson(picked.firstOrNull(), facade),
        )
    }
}

private object SatellitePassesTool : AiTool() {

    override val spec = AiToolSpec(
        name = "get_satellite_passes",
        title = "卫星过境预测",
        description = "查询指定卫星未来若干小时的过境：AOS/LOS、最大仰角、方位。" +
            "卫星当前在境时返回本次过境（AOS 可能已过去），用 in_pass 与 los 判断剩余时间。" +
            "名称有歧义时优先传 norad_id。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号，如 ISS、25544"),
            Triple("norad_id", "integer", "NORAD 编号，优先于名称"),
            Triple("hours", "integer", "预测时长（小时），默认 24，最大 72"),
            Triple("limit", "integer", "最多返回几次过境，默认 5"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val lookup = facade.lookupFromArgs(args)
        val satellite = when (lookup) {
            is Lookup.Found -> lookup.satellite
            is Lookup.Failed -> return AiToolResult(lookup.message, lookup.message, isError = true)
        }
        if (!satellite.hasPass) {
            return AiToolResult(
                modelText = "${satellite.name} 在预测窗口内没有过境",
                summary = "${satellite.name} 无过境",
            )
        }
        val inPass = satellite.visible
        val text = Json.obj()
            .put("satellite", satellite.name)
            .also { root ->
                if (lookup is Lookup.Found && lookup.autoMatched) {
                    root.put("auto_matched", true)
                    root.put(
                        "candidates",
                        Json.Arr().also { arr ->
                            lookup.candidates.forEach { candidate ->
                                arr.add(
                                    Json.obj()
                                        .put("name", candidate.name)
                                        .put("norad", candidate.norad),
                                )
                            }
                        },
                    )
                    root.put("hint", "名称有歧义，已按排序第一处理；需要其它卫星请传 norad_id")
                }
            }
            .put(
                "passes",
                Json.arr().add(
                    Json.obj()
                        .put("aos", isoUtc(satellite.aosMs))
                        .put("los", isoUtc(satellite.losMs))
                        .put("in_pass", inPass)
                        .put("is_geo", satellite.isGeo)
                        .put("max_elevation", satellite.maxElevationDeg)
                        .put("aos_azimuth", satellite.aosAzimuthDeg)
                        .put("los_azimuth", satellite.losAzimuthDeg)
                ),
            )
            .also {
                if (inPass) {
                    it.put(
                        "note",
                        "当前正在过境：aos 是本次过境开始时间（可能已过去），" +
                            "请用 los 判断剩余时间；下一次过境需在本次结束后重新查询",
                    )
                }
            }
            .stringify()
        val summary = if (inPass) {
            "正在过境 · 最大仰角 ${satellite.maxElevationDeg.roundToInt()}°"
        } else {
            "下次过境 ${isoUtc(satellite.aosMs)} · 最大仰角 ${satellite.maxElevationDeg.roundToInt()}°"
        }
        return AiToolResult(
            modelText = text,
            summary = summary,
            cardJson = satelliteCardJson(satellite, facade),
        )
    }
}

private object SatelliteLiveTool : AiTool() {

    override val spec = AiToolSpec(
        name = "get_satellite_live",
        title = "卫星实时位置",
        description = "按当前地面站坐标计算卫星实时方位/仰角/距离/星下点，以及下行多普勒。" +
            "名称有歧义时优先传 norad_id。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号，如 ISS、25544"),
            Triple("norad_id", "integer", "NORAD 编号，优先于名称"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val satellite = when (val lookup = facade.lookupFromArgs(args)) {
            is Lookup.Found -> lookup.satellite
            is Lookup.Failed -> return AiToolResult(lookup.message, lookup.message, isError = true)
        }
        val location = facade.location()
            ?: return AiToolResult("没有定位结果，无法计算实时位置", "缺少定位", isError = true)
        val tle = facade.tle(satellite.norad)
            ?: return AiToolResult("缺少 ${satellite.name} 的 TLE 数据", "缺少 TLE", isError = true)

        val elements = runCatching { TleElements.parse(tle.name, tle.line1, tle.line2) }
            .getOrNull()
            ?: return AiToolResult("TLE 解析失败（可能格式不符）", "TLE 解析失败", isError = true)
        val propagator = SatellitePropagator(
            tle = elements,
            latitudeDeg = location.latitude,
            longitudeDeg = location.longitude,
            altitudeM = location.altitudeM,
        )
        val now = facade.nowMs()
        val position = propagator.getPosition(now)
            ?: return AiToolResult("轨道计算失败（TLE 可能已过期）", "轨道计算失败", isError = true)

        val azimuth = position.azimuthRad * 180 / PI
        val elevation = position.elevationRad * 180 / PI
        val subLat = position.latitudeRad * 180 / PI
        val subLon = position.longitudeRad * 180 / PI
        // 多普勒：拿到下行频率时按径向速度换算（rangeRate 为 km/s，转 m/s）
        val downlinkHz = facade.radios(satellite.norad)
            .firstOrNull { it.isActive && it.downlinkHz > 0 }?.downlinkHz
        val dopplerHz = downlinkHz?.let {
            it * (-position.rangeRateKmPerSec * 1000.0 / 299_792_458.0)
        }

        val text = Json.obj()
            .put("satellite", satellite.name)
            .put("utc", isoUtc(now))
            .put("azimuth", azimuth)
            .put("elevation", elevation)
            .put("range_km", position.rangeKm)
            .put("altitude_km", position.altitudeKm)
            .put("sub_lat", subLat)
            .put("sub_lon", subLon)
            .put("above_horizon", position.isAboveHorizon)
            .also { if (dopplerHz != null) it.put("doppler_downlink_hz", dopplerHz) }
            .stringify()

        return AiToolResult(
            modelText = text,
            summary = "方位 ${azimuth.roundToInt()}° 仰角 ${elevation.roundToInt()}°",
            cardJson = satelliteCardJson(satellite, facade),
        )
    }
}

private object SatelliteTransmittersTool : AiTool() {

    override val spec = AiToolSpec(
        name = "get_satellite_transmitters",
        title = "转发器频率",
        description = "返回卫星转发器的上行/下行频率（Hz）、模式与状态。名称有歧义时优先传 norad_id。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号，如 ISS、25544"),
            Triple("norad_id", "integer", "NORAD 编号，优先于名称"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val satellite = when (val lookup = facade.lookupFromArgs(args)) {
            is Lookup.Found -> lookup.satellite
            is Lookup.Failed -> return AiToolResult(lookup.message, lookup.message, isError = true)
        }
        val radios = facade.radios(satellite.norad)
        if (radios.isEmpty()) {
            return AiToolResult("${satellite.name} 暂无转发器数据", "无转发器数据", isError = true)
        }
        val array = Json.arr()
        radios.forEach { radio ->
            array.add(
                Json.obj()
                    .put("name", radio.name)
                    .put("uplink_hz", radio.uplinkHz)
                    .put("downlink_hz", radio.downlinkHz)
                    .put("mode", radio.mode)
                    .put("status", radio.status)
            )
        }
        val text = Json.obj()
            .put("satellite", satellite.name)
            .put("transmitters", array)
            .stringify()
        return AiToolResult(
            modelText = text,
            summary = "${radios.size} 个转发器（${radios.count { it.isActive }} 个活跃）",
            cardJson = satelliteCardJson(satellite, facade),
        )
    }
}

private object AmsatStatusTool : AiTool() {

    override val spec = AiToolSpec(
        name = "get_amsat_status",
        title = "AMSAT 状态",
        description = "返回卫星最新的 AMSAT 状态报告（如“可操作”“遥测”）。名称有歧义时优先传 norad_id。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号"),
            Triple("norad_id", "integer", "NORAD 编号，优先于名称"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val satellite = when (val lookup = facade.lookupFromArgs(args)) {
            is Lookup.Found -> lookup.satellite
            is Lookup.Failed -> return AiToolResult(lookup.message, lookup.message, isError = true)
        }
        val status = facade.amsatStatus(satellite.norad)
            ?: return AiToolResult(
                modelText = Json.obj().put("satellite", satellite.name).putNull("status").stringify(),
                summary = "${satellite.name} 没有状态报告",
            )
        val text = Json.obj().put("satellite", satellite.name).put("status", status).stringify()
        return AiToolResult(modelText = text, summary = status)
    }
}

private object OpenScreenTool : AiTool() {

    override val spec = AiToolSpec(
        name = "open_screen",
        title = "打开页面",
        description = "在 App 内跳转到指定页面。screen 取值：" +
            "sstv_receive / sstv_gallery / cw / ft8 / aprs / qso_log / satellite_list / reminder_list / ai_settings",
        parametersJson = schema(
            Triple("screen", "string", "页面 id"),
        ),
        risk = AiToolRisk.NAVIGATION,
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val screen = args.str("screen")
            ?: return AiToolResult("缺少 screen 参数", "缺少参数", isError = true)
        val result = facade.performAction("open_screen", 0, screen)
        return AiToolResult(modelText = result, summary = result)
    }
}

private object PassReminderTool : AiTool() {

    override val spec = AiToolSpec(
        name = "add_pass_reminder",
        title = "设置过境提醒",
        description = "为指定卫星的下一次过境创建本地提醒。只取 AOS 在未来的过境，" +
            "正在过境的卫星会跳过本次。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号"),
        ),
        risk = AiToolRisk.SIDE_EFFECT,
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val satellite = when (val lookup = facade.lookupFromArgs(args)) {
            is Lookup.Found -> lookup.satellite
            is Lookup.Failed -> return AiToolResult(lookup.message, lookup.message, isError = true)
        }
        val now = facade.nowMs()
        if (!satellite.hasPass || satellite.aosMs <= now) {
            return AiToolResult(
                modelText = "${satellite.name} 没有 AOS 在未来的过境，无法设置提醒",
                summary = "没有可提醒的过境",
                isError = true,
            )
        }
        val result = facade.performAction("add_pass_reminder", satellite.norad, "")
        return AiToolResult(modelText = result, summary = result)
    }
}

// ── 社区类工具：FT8 / APRS / QSO 日志 ───────────────────────────────────────
//
// 三个工具都**只读**：不发起网络连接、不改 filter、不写日志。APRS 尤其如此——
// 模型只能看到「本次会话已经收到的台站」，避免它把结果说成「你附近全部台站」。

private object Ft8ActivityTool : AiTool() {

    override val spec = AiToolSpec(
        name = "get_ft8_activity",
        title = "FT8 活动",
        description = "读取本次会话最近的 FT8 解码（消息、SNR、频偏）与 QSO 记录。" +
            "解码列表在内存中，重启 App 后为空；需要历史通联请用 search_qso_log。",
        parametersJson = schema(
            Triple("max", "integer", "最多返回条数，默认 10，最大 50"),
            Triple("callsign", "string", "按呼号过滤，可省略"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val snapshot = facade.ft8Snapshot()
            ?: return AiToolResult("FT8 模块不可用", "FT8 不可用", isError = true)
        val max = (args.int("max") ?: 10).coerceIn(1, 50)
        val callsign = args.str("callsign")

        val decoded = snapshot.decoded
            .filter { callsign == null || it.message.contains(callsign, ignoreCase = true) }
            .sortedByDescending { it.timeMs }
            .take(max)
        val qso = snapshot.qso
            .filter { callsign == null || it.callsign.contains(callsign, ignoreCase = true) }
            .sortedByDescending { it.qsoTimeMs }
            .take(max)

        val decodedArray = Json.arr()
        decoded.forEach { entry ->
            decodedArray.add(
                Json.obj()
                    .put("time", entry.timeMs)
                    .put("message", entry.message)
                    .put("snr", entry.snrDb)
                    .put("freq_hz", entry.freqHz),
            )
        }
        val qsoArray = Json.arr()
        qso.forEach { record -> qsoArray.add(record.toJson()) }

        val text = Json.obj()
            .put("decoding", snapshot.decoding)
            .put("decoded", decodedArray)
            .put("qso", qsoArray)
            .also {
                if (decoded.isEmpty() && qso.isEmpty()) {
                    it.put("note", "本次会话还没有 FT8 解码或 QSO 记录")
                }
            }
            .stringify()
        return AiToolResult(
            modelText = text,
            summary = "FT8 解码 ${decoded.size} 条，QSO ${qso.size} 条",
        )
    }
}

private object AprsStationsTool : AiTool() {

    override val spec = AiToolSpec(
        name = "get_aprs_stations",
        title = "APRS 台站",
        description = "列出当前 APRS 会话已接收的台站（呼号、距离、方位、最后报告）。" +
            "只读内存数据：不发起新查询、不修改 filter、不自动连接；" +
            "接收范围是固定 filter，不代表以你为中心的完整列表。",
        parametersJson = Json.obj()
            .put("type", "object")
            .put(
                "properties",
                Json.obj()
                    .put(
                        "radius_km",
                        Json.obj().put("type", "number")
                            .put("description", "在已接收台站里按距离过滤，默认 100")
                            .put("minimum", 1).put("maximum", 1000),
                    )
                    .put(
                        "max",
                        Json.obj().put("type", "integer")
                            .put("description", "最多返回条数，默认 10，最大 50")
                            .put("minimum", 1).put("maximum", 50),
                    ),
            )
            .stringify(),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val snapshot = facade.aprsSnapshot()
            ?: return AiToolResult("APRS 模块不可用", "APRS 不可用", isError = true)
        if (!snapshot.connected) {
            return AiToolResult(
                modelText = "APRS 未连接，工具不会自动连接；请先在 APRS 页连接",
                summary = "APRS 未连接",
                isError = true,
            )
        }
        val radiusKm = (args["radius_km"] as? Json.Num)?.value?.coerceIn(1.0, 1000.0) ?: 100.0
        val max = (args.int("max") ?: 10).coerceIn(1, 50)
        val here = facade.location()

        // 距离/方位由内核换算：模型只看距离与方位，拿不到台站坐标
        val withDistance = snapshot.stations.map { station ->
            val distance = here?.let {
                haversineKm(it.latitude, it.longitude, station.latitude, station.longitude)
            }
            val bearing = here?.let {
                bearingDeg(it.latitude, it.longitude, station.latitude, station.longitude)
            }
            Triple(station, distance, bearing)
        }
        val filtered = withDistance
            .filter { (_, distance, _) -> distance?.let { it <= radiusKm } ?: true }
            .sortedBy { (_, distance, _) -> distance ?: Double.MAX_VALUE }
            .take(max)

        val array = Json.arr()
        filtered.forEach { (station, distance, bearing) ->
            val obj = Json.obj()
                .put("callsign", station.callsign)
                .put("last_report", station.timestampMs)
            distance?.let { obj.put("distance_km", it) }
            bearing?.let { obj.put("bearing", it) }
            if (station.comment.isNotBlank()) obj.put("comment", station.comment)
            array.add(obj)
        }

        val text = Json.obj()
            .put("connected", true)
            .put("scope", "session")
            .put("filter_note", "当前为固定范围接收（非以你为中心），仅含本次会话已收到的台站")
            .put("total", snapshot.stations.size)
            .put("returned", filtered.size)
            .put("stations", array)
            .also {
                if (filtered.isEmpty()) {
                    it.put("note", "本次会话还没有收到符合范围的 APRS 台站")
                }
            }
            .stringify()
        return AiToolResult(modelText = text, summary = "APRS 台站 ${filtered.size} 个")
    }
}

private object QsoLogTool : AiTool() {

    override val spec = AiToolSpec(
        name = "search_qso_log",
        title = "QSO 日志检索",
        description = "按呼号与时间范围检索本机 QSO 日志（FT8 与手动记录都在同一库）。" +
            "band/mode 可选过滤；组合频段（如 2m/70cm）只填主波段。",
        parametersJson = schema(
            Triple("callsign", "string", "按呼号过滤，可省略"),
            Triple("band", "string", "按波段过滤，如 2m（可省略）"),
            Triple("mode", "string", "按模式过滤，如 FM/FT8（可省略）"),
            Triple("days", "integer", "最近多少天，默认 30，最大 365"),
            Triple("max", "integer", "最多返回条数，默认 20，最大 100"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val records = facade.qsoRecords()
        if (records.isEmpty()) {
            return AiToolResult("QSO 日志为空", "日志为空", isError = true)
        }
        val callsign = args.str("callsign")
        val days = (args.int("days") ?: 30).coerceIn(1, 365)
        val max = (args.int("max") ?: 20).coerceIn(1, 100)
        val cutoff = facade.nowMs() - days * 24L * 60L * 60L * 1000L
        val bandArg = args.str("band")
        val bandFilter = if (bandArg != null) {
            parseBand(bandArg) ?: return AiToolResult(
                "无法识别波段：$bandArg。可用：${AiBand.entries.joinToString { it.display }}；" +
                    "组合频段请只填主波段（如 2m），另一段写进备注",
                "波段无效",
                isError = true,
            )
        } else {
            null
        }
        val modeFilter = args.str("mode")?.uppercase()?.takeIf { it.isNotEmpty() }

        val filtered = records
            .filter { record ->
                (callsign == null || record.callsign.contains(callsign, ignoreCase = true)) &&
                    (bandFilter == null || record.band == bandFilter.display) &&
                    (modeFilter == null || record.mode.equals(modeFilter, ignoreCase = true)) &&
                    record.qsoTimeMs >= cutoff
            }
            .sortedByDescending { it.qsoTimeMs }
        val page = filtered.take(max)

        val array = Json.arr()
        page.forEach { array.add(it.toJson()) }
        val text = Json.obj()
            .put("total", filtered.size)
            .put("returned", page.size)
            .put("days", days)
            .also { root ->
                bandFilter?.let { root.put("band", it.display) }
                modeFilter?.let { root.put("mode", it) }
            }
            .put("truncated", filtered.size > page.size)
            .put("records", array)
            .stringify()
        return AiToolResult(
            modelText = text,
            summary = "QSO ${page.size} 条（共 ${filtered.size}）",
        )
    }
}

// ── QSO 写入辅助（对齐 Android CommunityTools 的内部实现） ────────────────────

/**
 * 内核侧波段目录：与 Android `Ft8Band` 同名同频（displayName → 默认 FT8 拨号频率）。
 * 不含 70cm——Ft8Band 没有 70cm；ISS/IO-86 的 FM 常用 2m 上行，组合频段映射到 2m。
 */
internal enum class AiBand(val display: String, val freqHz: Long) {
    BAND_160M("160m", 1_840_000L),
    BAND_80M("80m", 3_573_000L),
    BAND_60M("60m", 5_357_000L),
    BAND_40M("40m", 7_074_000L),
    BAND_30M("30m", 10_136_000L),
    BAND_20M("20m", 14_074_000L),
    BAND_17M("17m", 18_100_000L),
    BAND_15M("15m", 21_074_000L),
    BAND_12M("12m", 24_915_000L),
    BAND_10M("10m", 28_074_000L),
    BAND_6M("6m", 50_313_000L),
    BAND_2M("2m", 144_174_000L),
}

/**
 * 波段解析：支持 40m / BAND_40M / 40M / 2m/70cm；空值默认 40m；
 * 70cm/430/435/440 映射到 2m（与 Android parseBand 一致）。
 */
internal fun parseBand(raw: String?): AiBand? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return AiBand.BAND_40M
    val compact = value.replace(" ", "")
    fun match(token: String): AiBand? = AiBand.entries.firstOrNull { band ->
        band.display.equals(token, ignoreCase = true) ||
            band.name.equals(token, ignoreCase = true) ||
            band.name.removePrefix("BAND_").equals(token, ignoreCase = true)
    }
    // 先整体匹配（含 "2 m"）
    match(compact)?.let { return it }
    // 组合频段（2m/70cm）取第一个能识别的
    compact.split('/', '、', ',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { token -> match(token)?.let { return it } }
    if (
        compact.contains("70cm", ignoreCase = true) ||
        compact.contains("430", ignoreCase = true) ||
        compact.contains("435", ignoreCase = true) ||
        compact.contains("440", ignoreCase = true)
    ) {
        return AiBand.BAND_2M
    }
    return null
}

/** 呼号规范化：去空格、大写，并校验常见格式（可选 /P、/M 等后缀）。 */
internal fun normalizeCallsign(raw: String): String? {
    val value = raw.trim().uppercase()
    return value.takeIf { Regex("^[A-Z0-9]{3,12}(/[A-Z0-9]{1,3})?$").matches(it) }
}

/** ISO 8601（UTC，`yyyy-MM-ddTHH:mm:ssZ`）→ epoch 毫秒；解析失败返回 null。 */
internal fun parseIsoUtc(iso: String): Long? {
    val regex = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:[Zz]|[+-]\d{2}:?\d{2})?$""",
    )
    val match = regex.find(iso.trim()) ?: return null
    val (year, month, day, hour, minute, second) = match.destructured
    val seconds = runCatching {
        daysFromCivil(year.toInt(), month.toInt(), day.toInt()) * 86_400L +
            hour.toInt() * 3_600L + minute.toInt() * 60L + (second.toIntOrNull() ?: 0)
    }.getOrNull() ?: return null
    return seconds * 1000L
}

/** 公历年月日 → 自 epoch 起的天数（civil_from_days 的逆运算，Howard Hinnant）。 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = floorDiv(y.toLong(), 400)
    val yoe = y - era * 400
    val m = month
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/** QSO 时间：优先 epoch 毫秒，其次 ISO 8601，最后当前时间。 */
internal fun parseQsoTime(iso: String?, epochMs: Long?, nowMs: Long): Long =
    epochMs?.takeIf { it > 0 }
        ?: iso?.trim()?.takeIf { it.isNotEmpty() }?.let { parseIsoUtc(it) }
        ?: nowMs

/** 单条 QSO 参数 → 草稿；失败返回 null 并给出原因。 */
internal fun buildQsoDraft(args: Json.Obj, nowMs: Long): Pair<AiQsoDraft?, String?> {
    val rawCallsign = args.str("callsign") ?: return null to "缺少呼号"
    val callsign = normalizeCallsign(rawCallsign)
        ?: return null to "呼号格式不正确：$rawCallsign"
    val rawBand = args.str("band")
    val band = parseBand(rawBand)
        ?: return null to "无法识别波段，可用：" + AiBand.entries.joinToString { it.display } +
            "；组合频段请只填主波段（如 2m），另一段写进备注"
    val mode = args.str("mode") ?: "FT8"
    val qsoTime = parseQsoTime(
        args.str("qso_time"),
        (args["qso_time_epoch_ms"] as? Json.Num)?.value?.toLong(),
        nowMs,
    )
    val draft = AiQsoDraft(
        callsign = callsign,
        grid = args.str("grid"),
        reportSent = args.str("report_sent") ?: "-99",
        reportReceived = args.str("report_received") ?: "-99",
        band = band.display,
        freqHz = (args["freq_hz"] as? Json.Num)?.value?.toLong() ?: band.freqHz,
        mode = mode,
        qsoTimeMs = qsoTime,
        isComplete = true,
        myGrid = args.str("my_grid"),
        comment = buildList {
            args.str("comment")?.takeIf { it.isNotBlank() }?.let(::add)
            if (!rawBand.isNullOrBlank() && rawBand != band.display) {
                add("波段：$rawBand")
            }
        }.joinToString("；").takeIf { it.isNotBlank() },
    )
    return draft to null
}

/** 写入 QSO 日志（写操作，执行前走二次确认）；支持单条与批量。 */
private object AddQsoLogTool : AiTool() {

    private const val MAX_BATCH = 30

    override val spec = AiToolSpec(
        name = "add_qso_log",
        title = "记录 QSO",
        description = "写入 QSO 日志（写操作，执行前会弹确认）。默认波段 40m、模式 FT8；" +
            "用户一次给多条时用 records 数组一次写入。缺少呼号或波段时先问用户，不要编造信号报告。",
        parametersJson = Json.obj()
            .put("type", "object")
            .put(
                "properties",
                Json.obj()
                    .put("callsign", Json.obj().put("type", "string").put("description", "对方呼号，如 XX9XX"))
                    .put("band", Json.obj().put("type", "string").put("description", "波段，如 40m/20m/2m，默认 40m"))
                    .put("mode", Json.obj().put("type", "string").put("description", "模式，默认 FT8"))
                    .put(
                        "freq_hz",
                        Json.obj().put("type", "integer").put("description", "频率 Hz，可省略"),
                    )
                    .put("report_sent", Json.obj().put("type", "string").put("description", "发出的信号报告，如 -12"))
                    .put("report_received", Json.obj().put("type", "string").put("description", "收到的信号报告，如 -08"))
                    .put("grid", Json.obj().put("type", "string").put("description", "对方网格"))
                    .put("my_grid", Json.obj().put("type", "string").put("description", "本台网格"))
                    .put("comment", Json.obj().put("type", "string").put("description", "备注"))
                    .put(
                        "qso_time",
                        Json.obj().put("type", "string")
                            .put("description", "通联时间，ISO 8601（如 2026-10-24T02:21:00Z），可省略"),
                    )
                    .put(
                        "qso_time_epoch_ms",
                        Json.obj().put("type", "integer")
                            .put("description", "通联时间（epoch 毫秒），可省略；优先于 qso_time"),
                    )
                    .put(
                        "records",
                        Json.obj().put("type", "array")
                            .put("description", "批量写入多条（可选，最多 30 条）；每项字段与单条一致")
                            .put("items", Json.obj().put("type", "object")),
                    ),
            )
            .stringify(),
        risk = AiToolRisk.SIDE_EFFECT,
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val nowMs = facade.nowMs()
        val batch = args["records"] as? Json.Arr
        if (batch != null && batch.items.isNotEmpty()) {
            val limit = minOf(batch.items.size, MAX_BATCH)
            val drafts = mutableListOf<AiQsoDraft>()
            batch.items.take(limit).forEachIndexed { index, item ->
                val obj = item.objOrNull()
                    ?: return AiToolResult("第 ${index + 1} 条不是对象", "参数无效", isError = true)
                val pair = buildQsoDraft(obj, nowMs)
                val draft = pair.first
                    ?: return AiToolResult("第 ${index + 1} 条：${pair.second}", "参数无效", isError = true)
                drafts += draft
            }
            val ids = drafts.map { facade.addQso(it) }
            if (ids.any { it <= 0 }) {
                return AiToolResult("部分 QSO 写入失败", "写入失败", isError = true)
            }
            val json = Json.obj()
                .put("saved", true)
                .put("count", ids.size)
                .also { if (batch.items.size > MAX_BATCH) it.put("truncated", true) }
                .put(
                    "ids",
                    Json.Arr().also { arr -> ids.forEach { arr.add(Json.of(it.toDouble())) } },
                )
                .stringify()
            return AiToolResult(
                modelText = json,
                summary = "已记录 ${ids.size} 条 QSO（首条 ${drafts.first().callsign}）",
            )
        }

        val pair = buildQsoDraft(args, nowMs)
        val draft = pair.first
            ?: return AiToolResult(pair.second ?: "参数不正确", "参数无效", isError = true)
        val id = facade.addQso(draft)
        if (id <= 0) return AiToolResult("写入 QSO 日志失败", "写入失败", isError = true)

        val json = Json.obj()
            .put("saved", true)
            .put("id", id)
            .put("callsign", draft.callsign)
            .put("band", draft.band)
            .put("mode", draft.mode)
            .put("qso_time", draft.qsoTimeMs)
            .stringify()
        return AiToolResult(
            modelText = json,
            summary = "已记录 QSO 与 ${draft.callsign}（${draft.band} ${draft.mode}）",
        )
    }
}

/** 强制重新获取定位（忽略缓存），用于“定位不准/重新定位”场景。 */
private object RefreshLocationTool : AiTool() {

    override val spec = AiToolSpec(
        name = "refresh_location",
        title = "重新定位",
        description = "强制重新获取当前定位（会触发 GPS），返回 CQ/ITU 分区、网格与坐标。" +
            "仅在用户明确说“重新定位/定位不准/刷新位置”时调用；普通位置问题用 get_zone_info。",
        parametersJson = schema(),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val location = facade.refreshLocation()
            ?: return AiToolResult(
                "定位失败：请检查定位权限与 GPS 是否开启",
                "定位失败",
                isError = true,
            )
        val zone = ZoneResolver.resolve(location.latitude, location.longitude)
        val json = Json.obj()
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("cq_zone", zone.cqZone ?: 0)
            .put("itu_zone", zone.ituZone ?: 0)
            .put("grid", zone.maidenhead)
            .stringify()
        return AiToolResult(
            modelText = json,
            summary = "已重新定位：${zone.maidenhead}",
        )
    }
}

private fun AiQsoSummary.toJson(): Json.Obj = Json.obj()
    .put("callsign", callsign)
    .put("grid", grid)
    .put("band", band)
    .put("mode", mode)
    .put("time", qsoTimeMs)
    .put("complete", isComplete)

/** 卫星卡片的 JSON（iOS 侧解析后画卡片；字段与 Android `SatelliteCardData` 对齐）。 */
private fun satelliteCardJson(
    satellite: AiSatelliteInfo?,
    facade: AiAppFacade,
): String {
    if (satellite == null) return ""
    val radios = facade.radios(satellite.norad)
    val obj = Json.obj()
        .put("name", satellite.name)
        .put("norad", satellite.norad)
        .put("modes", Json.Arr().also { arr ->
            satellite.modesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { arr.add(it) }
        })
    if (satellite.status.isNotBlank()) obj.put("amsat_status", satellite.status)
    if (satellite.hasPass) {
        obj.put(
            "pass",
            Json.obj()
                .put("aos", isoUtc(satellite.aosMs))
                .put("los", isoUtc(satellite.losMs))
                .put("in_pass", satellite.visible)
                .put("max_elevation", satellite.maxElevationDeg)
                .put("aos_azimuth", satellite.aosAzimuthDeg)
                .put("los_azimuth", satellite.losAzimuthDeg),
        )
    }
    if (radios.isNotEmpty()) {
        obj.put(
            "radios",
            Json.arr().also { arr ->
                radios.take(5).forEach { radio ->
                    arr.add(
                        Json.obj()
                            .put("name", radio.name)
                            .put("downlink_hz", radio.downlinkHz)
                            .put("uplink_hz", radio.uplinkHz)
                            .put("mode", radio.mode)
                            .put("status", radio.status)
                    )
                }
            },
        )
    }
    return obj.stringify()
}
