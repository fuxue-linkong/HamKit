package com.example.hamkit.ai

import com.example.hamkit.data.satellite.SatelliteNameMatcher
import com.example.hamkit.data.satellite.predict.SatellitePropagator
import com.example.hamkit.data.satellite.predict.TleElements
import com.example.hamkit.data.zone.ZoneResolver
import kotlin.math.PI
import kotlin.math.roundToInt

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
}

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
        description = "按名称关键词、工作模式或是否在境筛选卫星列表，返回名称、NORAD、模式与状态。" +
            "在境卫星的 AOS 可能已过去，用 visible 与 los 判断；next_aos 仅在未来有值时返回。",
        parametersJson = schema(
            Triple("query", "string", "名称关键词，如 ISS、AO-91；可省略"),
            Triple("mode", "string", "工作模式，如 FM、SSTV；可省略"),
            Triple("only_above", "boolean", "只返回当前在境的卫星"),
            Triple("limit", "integer", "返回条数，默认 10，最大 20"),
        ),
    )

    override fun execute(facade: AiAppFacade, args: Json.Obj): AiToolResult {
        val all = facade.satellites()
        if (all.isEmpty()) {
            return AiToolResult("卫星列表为空，请先在首页刷新卫星数据", "列表为空", isError = true)
        }
        val query = args.str("query")
        val mode = args.str("mode")?.uppercase()
        val queryNorad = query?.toIntOrNull()
        val onlyAbove = args.bool("only_above") == true
        val limit = (args.int("limit") ?: 10).coerceIn(1, 20)
        val now = facade.nowMs()

        val matched = all.filter { sat ->
            val nameOk = query == null ||
                (queryNorad != null && sat.norad == queryNorad) ||
                SatelliteNameMatcher.nameTier(query, sat.name) != null
            val modeOk = mode == null ||
                sat.modesCsv.split(',').any { it.trim().uppercase() == mode }
            (!onlyAbove || sat.visible) && nameOk && modeOk
        }

        // 有查询词时先按匹配层次排序，避免「子串假阳性」卫星排到精确命中前面
        val sorted = if (query != null) {
            matched.sortedWith(
                compareBy(
                    { SatelliteNameMatcher.nameTier(query, it.name) ?: Int.MAX_VALUE },
                    { if (it.visible) 0 else 1 },
                    { if (it.hasPass) it.aosMs else Long.MAX_VALUE },
                )
            )
        } else {
            matched.sortedWith(
                compareBy(
                    { if (it.visible) 0 else 1 },
                    { if (it.hasPass) it.aosMs else Long.MAX_VALUE },
                )
            )
        }
        val picked = sorted.take(limit)

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
            .put("count", picked.size)
            .put("in_pass_count", picked.count { it.visible })
            .put("satellites", array)
            .stringify()
        return AiToolResult(
            modelText = text,
            summary = "找到 ${picked.size} 颗卫星",
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
