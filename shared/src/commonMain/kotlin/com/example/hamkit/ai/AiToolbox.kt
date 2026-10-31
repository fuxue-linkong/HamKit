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
}

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
        SatellitePassesTool,
        SatelliteLiveTool,
        SatelliteTransmittersTool,
        SearchSatellitesTool,
        AmsatStatusTool,
        Ft8ActivityTool,
        AprsStationsTool,
        QsoLogTool,
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

/** 名称/编号 → 唯一卫星；有歧义或找不到时返回统一的错误结果。 */
private sealed interface Lookup {
    data class Found(val satellite: AiSatelliteInfo) : Lookup
    data class Failed(val message: String) : Lookup
}

private fun AiAppFacade.lookup(query: String): Lookup {
    val all = satellites()
    if (all.isEmpty()) return Lookup.Failed("卫星列表为空，请先刷新卫星数据")
    val candidates = all.map { SatelliteNameMatcher.Candidate(it.norad, it.name) }
    return when (val resolution = SatelliteNameMatcher.resolve(query, candidates)) {
        is SatelliteNameMatcher.Resolution.Unique -> {
            val norad = resolution.candidate.catalogNumber
            all.firstOrNull { it.norad == norad }
                ?.let { Lookup.Found(it) }
                ?: Lookup.Failed("没有找到卫星 \"$query\"")
        }

        is SatelliteNameMatcher.Resolution.Ambiguous -> Lookup.Failed(
            "卫星名称 \"$query\" 有歧义，请用 NORAD 编号指定：" +
                resolution.candidates.take(5).joinToString("、") {
                    "${it.name} (#${it.catalogNumber})"
                }
        )

        SatelliteNameMatcher.Resolution.NotFound ->
            Lookup.Failed("没有找到卫星 \"$query\"，请给出名称或 NORAD 编号")
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
            "卫星当前在境时返回本次过境（AOS 可能已过去），用 in_pass 与 los 判断剩余时间。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号，如 ISS、25544"),
            Triple("hours", "integer", "预测时长（小时），默认 24，最大 72"),
            Triple("limit", "integer", "最多返回几次过境，默认 5"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val query = args.str("satellite")
            ?: return AiToolResult("缺少卫星名称或 NORAD 编号", "缺少参数", isError = true)
        val satellite = when (val lookup = facade.lookup(query)) {
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
        description = "按当前地面站坐标计算卫星实时方位/仰角/距离/星下点，以及下行多普勒。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号，如 ISS、25544"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val query = args.str("satellite")
            ?: return AiToolResult("缺少卫星名称或 NORAD 编号", "缺少参数", isError = true)
        val satellite = when (val lookup = facade.lookup(query)) {
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
        description = "返回卫星转发器的上行/下行频率（Hz）、模式与状态。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号，如 ISS、25544"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val query = args.str("satellite")
            ?: return AiToolResult("缺少卫星名称或 NORAD 编号", "缺少参数", isError = true)
        val satellite = when (val lookup = facade.lookup(query)) {
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
        description = "返回卫星最新的 AMSAT 状态报告（如“可操作”“遥测”）。",
        parametersJson = schema(
            Triple("satellite", "string", "卫星名称或 NORAD 编号"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val query = args.str("satellite")
            ?: return AiToolResult("缺少卫星名称或 NORAD 编号", "缺少参数", isError = true)
        val satellite = when (val lookup = facade.lookup(query)) {
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
        val query = args.str("satellite")
            ?: return AiToolResult("缺少 satellite 参数", "缺少参数", isError = true)
        val satellite = when (val lookup = facade.lookup(query)) {
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
        description = "按呼号与时间范围检索本机 QSO 日志（FT8 与手动记录都在同一库）。",
        parametersJson = schema(
            Triple("callsign", "string", "按呼号过滤，可省略"),
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

        val filtered = records
            .filter { record ->
                (callsign == null || record.callsign.contains(callsign, ignoreCase = true)) &&
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
            .put("truncated", filtered.size > page.size)
            .put("records", array)
            .stringify()
        return AiToolResult(
            modelText = text,
            summary = "QSO ${page.size} 条（共 ${filtered.size}）",
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
