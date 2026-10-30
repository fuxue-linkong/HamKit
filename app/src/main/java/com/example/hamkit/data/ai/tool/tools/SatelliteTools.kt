package com.example.hamkit.data.ai.tool.tools

import com.example.hamkit.data.ai.tool.AiCard
import com.example.hamkit.data.ai.tool.AiSatelliteRef
import com.example.hamkit.data.ai.tool.AiTool
import com.example.hamkit.data.ai.tool.AiToolContext
import com.example.hamkit.data.ai.tool.AiToolSpec
import com.example.hamkit.data.ai.tool.LiveSnapshot
import com.example.hamkit.data.ai.tool.PassSnapshot
import com.example.hamkit.data.ai.tool.RadioSnapshot
import com.example.hamkit.data.ai.tool.SatelliteCardData
import com.example.hamkit.data.ai.tool.ToolCategory
import com.example.hamkit.data.ai.tool.ToolOutcome
import com.example.hamkit.data.ai.tool.errorOutcome
import com.example.hamkit.data.ai.tool.integerProperty
import com.example.hamkit.data.ai.tool.optIntOrNullArg
import com.example.hamkit.data.ai.tool.optStringOrNullArg
import com.example.hamkit.data.ai.tool.stringProperty
import com.example.hamkit.data.ai.tool.toolSchema
import com.example.hamkit.data.satellite.SatelliteLiveInfo
import com.example.hamkit.data.satellite.LiveSatelliteTracker
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** 按名称/编号找卫星；找不到时返回统一错误。 */
private suspend fun resolveOrNull(
    ctx: AiToolContext,
    args: JSONObject,
): AiSatelliteRef? = ctx.facade.resolveSatellite(
    args.optStringOrNullArg("satellite")
        ?: args.optStringOrNullArg("query")
        ?: return null,
)

private fun SatelliteInfo.toPass(): PassSnapshot = PassSnapshot(
    aos = aosTime,
    los = losTime,
    maxElevation = maxElevation,
    aosAzimuth = aosAzimuth,
    losAzimuth = losAzimuth,
    isDaylight = isDaylightPass,
)

private fun SatelliteLiveInfo.toSnapshot(): LiveSnapshot = LiveSnapshot(
    azimuthDeg = azimuthDeg,
    elevationDeg = elevationDeg,
    rangeKm = rangeKm,
    altitudeKm = altitudeKm,
    subpointLatDeg = subpointLatDeg,
    subpointLonDeg = subpointLonDeg,
    phaseDeg = phaseDeg,
    isEclipsed = isEclipsed,
    dopplerDownlinkHz = dopplerDownlinkHz,
    dopplerUplinkHz = dopplerUplinkHz,
)

private fun RadioInfo.toSnapshot(): RadioSnapshot = RadioSnapshot(
    name = displayName,
    uplinkHz = uplinkHz,
    downlinkHz = downlinkHz,
    mode = displayMode,
    status = status,
)

/** 搜索卫星（名称/模式/是否在境）。 */
object SearchSatellitesTool : AiTool {
    override val spec = AiToolSpec(
        name = "search_satellites",
        title = "搜索卫星",
        description = "按名称关键词、工作模式或是否在境筛选当前卫星列表，返回名称、NORAD、模式与 AMSAT 状态。",
        parameters = toolSchema(
            JSONObject()
                .put("query", stringProperty("名称关键词，如 ISS、AO-91；可省略"))
                .put("mode", stringProperty("工作模式，如 FM、SSB、SSTV；可省略"))
                .put("only_above", JSONObject().put("type", "boolean").put("description", "只返回当前在境卫星"))
                .put("limit", integerProperty("最多返回条数", 1, 20)),
        ),
        category = ToolCategory.SATELLITE,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        ctx.reportProgress(null, "读取卫星列表…")
        val all = ctx.facade.ensureSatellites()
        if (all.isEmpty()) return errorOutcome("卫星列表为空，请先在首页刷新卫星数据")

        val query = args.optStringOrNullArg("query")?.lowercase()
        val mode = args.optStringOrNullArg("mode")?.lowercase()
        val onlyAbove = args.has("only_above") && !args.isNull("only_above") && args.optBoolean("only_above")
        val limit = (args.optIntOrNullArg("limit") ?: 10).coerceIn(1, 20)

        val filtered = all
            .filter { query == null || it.name.lowercase().contains(query) || it.catalogNumber.toString() == query }
            .filter { mode == null || it.modes.any { m -> m.lowercase() == mode } }
            .filter { !onlyAbove || it.isCurrentlyVisible }
            .sortedByDescending { it.isCurrentlyVisible }
            .take(limit)

        val array = JSONArray()
        filtered.forEach { sat ->
            array.put(
                JSONObject().apply {
                    put("name", sat.name)
                    put("norad", sat.catalogNumber)
                    put("modes", JSONArray(sat.modes))
                    put("visible", sat.isCurrentlyVisible)
                    if (sat.status.isNotBlank()) put("amsat_status", sat.status)
                    put("next_aos", sat.aosTime.toString())
                    put("max_elevation", sat.maxElevation)
                }
            )
        }
        return ToolOutcome(
            modelText = JSONObject().put("count", filtered.size).put("satellites", array).toString(),
            summary = "找到 ${filtered.size} 颗卫星",
        )
    }
}

/** 过境预测（复用首页已算好的 48h 结果）。 */
object GetSatellitePassesTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_satellite_passes",
        title = "卫星过境预测",
        description = "查询指定卫星未来若干小时的过境：AOS/LOS、最大仰角、方位与是否白天过境。",
        parameters = toolSchema(
            JSONObject()
                .put("satellite", stringProperty("卫星名称或 NORAD 编号，如 ISS、25544"))
                .put("hours", integerProperty("预测窗口（小时）", 1, 72))
                .put("limit", integerProperty("最多返回过境次数", 1, 6)),
            required = listOf("satellite"),
        ),
        category = ToolCategory.SATELLITE,
        longRunning = true,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val ref = resolveOrNull(ctx, args) ?: return errorOutcome("没有找到这颗卫星，请给出名称或 NORAD 编号")
        ctx.reportProgress(0.2f, "加载卫星数据…")
        val all = ctx.facade.ensureSatellites()
        if (all.isEmpty()) return errorOutcome("卫星数据不可用，请稍后重试")

        ctx.reportProgress(0.6f, "筛选过境…")
        val hours = (args.optIntOrNullArg("hours") ?: 48).coerceIn(1, 72)
        val limit = (args.optIntOrNullArg("limit") ?: 4).coerceIn(1, 6)
        val now = Instant.now()
        val deadline = now.plus(Duration.ofHours(hours.toLong()))
        val passes = all
            .filter { it.catalogNumber == ref.catalogNumber }
            .filter { it.losTime.isAfter(now) && it.aosTime.isBefore(deadline) }
            .sortedBy { it.aosTime }
            .take(limit)

        ctx.reportProgress(0.9f, "整理转发器…")
        val radios = ctx.facade.radios(ref.catalogNumber).map { it.toSnapshot() }
        val card = SatelliteCardData(
            name = ref.name,
            catalogNumber = ref.catalogNumber,
            modes = all.firstOrNull { it.catalogNumber == ref.catalogNumber }?.modes.orEmpty(),
            amsatStatus = all.firstOrNull { it.catalogNumber == ref.catalogNumber }?.status?.ifBlank { null },
            passes = passes.map { it.toPass() },
            radios = radios,
        )

        if (passes.isEmpty()) {
            return ToolOutcome(
                modelText = JSONObject().put("passes", JSONArray()).put("note", "未来 $hours 小时没有过境").toString(),
                card = AiCard.Satellite(card),
                summary = "未来 $hours 小时无过境",
            )
        }

        val array = JSONArray()
        passes.forEach { pass ->
            array.put(
                JSONObject().apply {
                    put("aos", pass.aosTime.toString())
                    put("los", pass.losTime.toString())
                    put("max_elevation", pass.maxElevation)
                    put("aos_azimuth", pass.aosAzimuth)
                    put("los_azimuth", pass.losAzimuth)
                    put("daylight", pass.isDaylightPass)
                }
            )
        }
        return ToolOutcome(
            modelText = JSONObject().put("satellite", ref.name).put("passes", array).toString(),
            card = AiCard.Satellite(card),
            summary = "${passes.size} 次过境 · 最佳仰角 ${passes.maxOf { it.maxElevation }.toInt()}°",
        )
    }
}

/** 实时位置（AZ/EL/距离/多普勒/蚀）。 */
object GetSatelliteLiveTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_satellite_live",
        title = "卫星实时追踪",
        description = "计算指定卫星当前相对你位置的方位、仰角、距离、星下点、多普勒与蚀状态。",
        parameters = toolSchema(
            JSONObject().put("satellite", stringProperty("卫星名称或 NORAD 编号")),
            required = listOf("satellite"),
        ),
        category = ToolCategory.SATELLITE,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val ref = resolveOrNull(ctx, args) ?: return errorOutcome("没有找到这颗卫星，请给出名称或 NORAD 编号")
        ctx.reportProgress(null, "等待定位…")
        val location = ctx.facade.awaitLocation() ?: return errorOutcome("没有定位结果，无法计算实时位置")
        val tle = ctx.facade.tleFor(ref.catalogNumber) ?: return errorOutcome("缺少 ${ref.name} 的 TLE 数据")

        ctx.reportProgress(0.5f, "计算实时位置…")
        val live = withContext(Dispatchers.Default) {
            runCatching {
                LiveSatelliteTracker(tle.tle, location.latitude, location.longitude).computeOnce()
            }.getOrNull()
        } ?: return errorOutcome("轨道计算失败（TLE 可能已过期）")

        ctx.reportProgress(0.85f, "读取过境与转发器…")
        val passes = ctx.facade.satellites()
            .filter { it.catalogNumber == ref.catalogNumber && it.losTime.isAfter(Instant.now()) }
            .sortedBy { it.aosTime }
            .take(3)
            .map { it.toPass() }
        val radios = ctx.facade.radios(ref.catalogNumber).map { it.toSnapshot() }

        val card = SatelliteCardData(
            name = ref.name,
            catalogNumber = ref.catalogNumber,
            modes = ctx.facade.satellites()
                .firstOrNull { it.catalogNumber == ref.catalogNumber }?.modes.orEmpty(),
            amsatStatus = ctx.facade.satellites()
                .firstOrNull { it.catalogNumber == ref.catalogNumber }?.status?.ifBlank { null },
            live = live.toSnapshot(),
            passes = passes,
            radios = radios,
        )
        val json = JSONObject().apply {
            put("satellite", ref.name)
            put("azimuth", live.azimuthDeg)
            put("elevation", live.elevationDeg)
            put("range_km", live.rangeKm)
            put("altitude_km", live.altitudeKm)
            put("subpoint_lat", live.subpointLatDeg)
            put("subpoint_lon", live.subpointLonDeg)
            put("eclipsed", live.isEclipsed)
            live.dopplerDownlinkHz?.let { put("doppler_downlink_hz", it) }
            live.dopplerUplinkHz?.let { put("doppler_uplink_hz", it) }
            put("above_horizon", live.isAboveHorizon)
        }
        return ToolOutcome(
            modelText = json.toString(),
            card = AiCard.Satellite(card),
            summary = "AZ ${live.azimuthDeg.toInt()}° EL ${live.elevationDeg.toInt()}°",
        )
    }
}

/** 转发器频率。 */
object GetSatelliteTransmittersTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_satellite_transmitters",
        title = "转发器频率",
        description = "返回指定卫星的转发器列表：上行/下行频率、模式、是否倒置、活跃状态。",
        parameters = toolSchema(
            JSONObject().put("satellite", stringProperty("卫星名称或 NORAD 编号")),
            required = listOf("satellite"),
        ),
        category = ToolCategory.SATELLITE,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val ref = resolveOrNull(ctx, args) ?: return errorOutcome("没有找到这颗卫星，请给出名称或 NORAD 编号")
        ctx.reportProgress(null, "读取转发器库…")
        val radios = ctx.facade.radios(ref.catalogNumber)
        if (radios.isEmpty()) return errorOutcome("${ref.name} 暂无转发器数据")

        val array = JSONArray()
        radios.forEach { radio ->
            array.put(
                JSONObject().apply {
                    put("name", radio.displayName)
                    radio.uplinkHz?.let { put("uplink_hz", it) }
                    radio.downlinkHz?.let { put("downlink_hz", it) }
                    put("mode", radio.displayMode)
                    put("inverted", radio.inverted)
                    put("status", radio.status)
                }
            )
        }
        val card = SatelliteCardData(
            name = ref.name,
            catalogNumber = ref.catalogNumber,
            radios = radios.map { it.toSnapshot() },
        )
        return ToolOutcome(
            modelText = JSONObject().put("satellite", ref.name).put("transmitters", array).toString(),
            card = AiCard.Satellite(card),
            summary = "${radios.size} 个转发器（${radios.count { it.isActive }} 个活跃）",
        )
    }
}

/** AMSAT 状态。 */
object GetAmsatStatusTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_amsat_status",
        title = "AMSAT 状态",
        description = "查询卫星最近的 AMSAT 状态报告（Heard / Telemetry Only / Not Heard / Crew Active）。",
        parameters = toolSchema(
            JSONObject().put("satellite", stringProperty("卫星名称或 NORAD 编号")),
            required = listOf("satellite"),
        ),
        category = ToolCategory.SATELLITE,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val ref = resolveOrNull(ctx, args) ?: return errorOutcome("没有找到这颗卫星，请给出名称或 NORAD 编号")
        val amsatName = ctx.facade.amsatStatusName(ref.catalogNumber)
            ?: return ToolOutcome(
                modelText = JSONObject().put("satellite", ref.name).put("status", JSONObject.NULL)
                    .put("note", "该卫星不在 AMSAT 状态列表").toString(),
                summary = "无 AMSAT 数据",
            )
        val status = ctx.facade.amsatStatus(amsatName)
            ?: return ToolOutcome(
                modelText = JSONObject().put("satellite", ref.name).put("status", JSONObject.NULL)
                    .put("note", "最近没有 AMSAT 状态报告").toString(),
                summary = "暂无报告",
            )
        val json = JSONObject().apply {
            put("satellite", ref.name)
            put("status", status.status)
            put("reported_at", status.reportTime.toString())
            put("inherited", status.isInherited)
        }
        return ToolOutcome(modelText = json.toString(), summary = status.status)
    }
}
