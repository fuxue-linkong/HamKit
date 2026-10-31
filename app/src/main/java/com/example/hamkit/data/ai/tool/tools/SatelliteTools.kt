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
import com.example.hamkit.data.satellite.LiveSatelliteTracker
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteCatalog
import com.example.hamkit.data.satellite.SatelliteInfo
import com.example.hamkit.data.satellite.SatelliteLiveInfo
import com.example.hamkit.data.satellite.SatelliteNameMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** 卫星解析结果：成功拿到唯一引用，或失败并带统一错误输出。 */
private sealed interface SatelliteLookup {
    data class Found(val ref: AiSatelliteRef) : SatelliteLookup
    data class Failed(val outcome: ToolOutcome) : SatelliteLookup
}

/**
 * 按名称/编号解析卫星，并统一处理“缺参 / 找不到 / 有歧义”。
 *
 * 歧义时列出候选（名称 + NORAD），要求调用方改用 NORAD 编号：
 * 例如 `ISS` 同时匹配 `ISS (ZARYA)` 与 `ISS (NAUKA)`，
 * 不能再像旧实现那样静默取列表第一条（会命中 `OUTPOST MISSION2`）。
 */
private fun resolveOrError(ctx: AiToolContext, args: JSONObject): SatelliteLookup {
    val query = args.optStringOrNullArg("satellite")
        ?: args.optStringOrNullArg("query")
        ?: return SatelliteLookup.Failed(errorOutcome("缺少卫星名称或 NORAD 编号"))
    val candidates = ctx.facade.satelliteCandidates(query)
    return when {
        candidates.isEmpty() -> SatelliteLookup.Failed(
            errorOutcome("没有找到卫星 \"$query\"，请给出名称或 NORAD 编号")
        )
        candidates.size > 1 -> SatelliteLookup.Failed(
            errorOutcome(
                "卫星名称 \"$query\" 有歧义，请用 NORAD 编号指定：" +
                    candidates.take(5).joinToString("、") {
                        "${it.name} (#${it.catalogNumber})"
                    }
            )
        )
        else -> SatelliteLookup.Found(candidates.first())
    }
}

private fun SatelliteInfo.toPass(): PassSnapshot = PassSnapshot(
    aos = aosTime,
    los = losTime,
    maxElevation = maxElevation,
    aosAzimuth = aosAzimuth,
    losAzimuth = losAzimuth,
    isDaylight = isDaylightPass,
    isInPass = isCurrentlyVisible,
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

/**
 * 单颗卫星的模型可读 JSON。
 *
 * `next_aos` 只在 AOS 位于未来时输出；在境卫星的 AOS 已经过去，
 * 改用 `in_pass_since` 表达，避免模型把过去时刻误读成“下一次过境”。
 */
private fun SatelliteInfo.toModelJson(now: Instant): JSONObject = JSONObject().apply {
    put("name", name)
    put("norad", catalogNumber)
    put("modes", JSONArray(modes))
    put("visible", isCurrentlyVisible)
    put("is_geo", isGeo)
    put("aos", aosTime.toString())
    put("los", losTime.toString())
    put("max_elevation", maxElevation)
    if (status.isNotBlank()) put("amsat_status", status)
    if (aosTime.isAfter(now)) {
        put("next_aos", aosTime.toString())
    } else if (isCurrentlyVisible) {
        put("in_pass_since", aosTime.toString())
    }
}

/** 搜索卫星（名称/模式/是否在境）。 */
object SearchSatellitesTool : AiTool {
    override val spec = AiToolSpec(
        name = "search_satellites",
        title = "搜索卫星",
        description = "按名称关键词、标签（tag）或是否在境检索当前卫星列表，返回名称、NORAD、模式与 AMSAT 状态。" +
            SatelliteTag.DOC +
            "。max 控制返回条数，默认 20、最大 100；被截断时返回 truncated=true 与 total。" +
            "在境卫星的 AOS 可能已过去，用 visible 与 los 判断；next_aos 仅在未来有值时返回。",
        parameters = toolSchema(
            JSONObject()
                .put("query", stringProperty("名称关键词或 NORAD 编号，如 ISS、25544；可省略"))
                .put(
                    "tags",
                    JSONObject()
                        .put("type", "array")
                        .put("description", SatelliteTag.DOC)
                        .put("items", JSONObject().put("type", "string")),
                )
                .put("max", integerProperty("返回上限，默认 20", 1, 100))
                .put("mode", stringProperty("（兼容旧参数）工作模式，等价 tags 里的模式"))
                .put(
                    "only_above",
                    JSONObject().put("type", "boolean")
                        .put("description", "（兼容旧参数）只返回当前在境卫星"),
                ),
        ),
        category = ToolCategory.SATELLITE,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        ctx.reportProgress(null, "读取卫星列表…")
        val all = ctx.facade.ensureSatellites()
        if (all.isEmpty()) return errorOutcome("卫星列表为空，请先在首页刷新卫星数据")

        val query = args.optStringOrNullArg("query")?.trim()?.takeIf { it.isNotEmpty() }
        val rawTags = when (val raw = args.opt("tags")) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> raw.split(',')
            null, JSONObject.NULL -> emptyList()
            else -> return errorOutcome("tags 必须是字符串数组。${SatelliteTag.DOC}")
        }
        val tags = when (
            val result = parseSatelliteTags(
                rawTags = rawTags,
                mode = args.optStringOrNullArg("mode"),
                onlyAbove = args.optBoolean("only_above", false),
            )
        ) {
            is TagParseResult.Success -> result.tags
            is TagParseResult.Failure -> return errorOutcome(result.message)
        }
        // 默认 20 颗；兼容旧参数 limit
        val max = (args.optIntOrNullArg("max") ?: args.optIntOrNullArg("limit") ?: 20)
            .coerceIn(1, 100)
        val now = Instant.now()

        val sorted = filterSatellites(all, query, tags, now) { catalog ->
            ctx.facade.radios(catalog)
        }
        val filtered = sorted.take(max)
        val truncated = sorted.size > filtered.size

        val array = JSONArray()
        filtered.forEach { array.put(it.toModelJson(now)) }
        val json = JSONObject().apply {
            put("total", sorted.size)
            put("returned", filtered.size)
            put("truncated", truncated)
            put("tags", JSONArray(tags.map { it.wire }))
            put("in_pass_count", filtered.count { it.isCurrentlyVisible })
            put("satellites", array)
            if (tags.contains(SatelliteTag.REPEATER) && filtered.isEmpty()) {
                put("note", "转发器库为空或没有匹配的活跃转发器，可先到卫星页刷新转发器数据")
            }
        }
        val summary = if (truncated) {
            "找到 ${filtered.size} 颗（共 ${sorted.size}，已截断）"
        } else {
            "找到 ${filtered.size} 颗"
        }
        return ToolOutcome(modelText = json.toString(), summary = summary)
    }
}

/** 过境预测（复用首页已算好的 48h 结果）。 */
object GetSatellitePassesTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_satellite_passes",
        title = "卫星过境预测",
        description = "查询指定卫星未来若干小时的过境：AOS/LOS、最大仰角、方位与是否白天过境。" +
            "卫星当前在境时返回本次过境（AOS 可能已过去），此时用 in_pass 与 los 判断剩余时间。",
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
        val ref = when (val lookup = resolveOrError(ctx, args)) {
            is SatelliteLookup.Found -> lookup.ref
            is SatelliteLookup.Failed -> return lookup.outcome
        }
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
        val info = all.firstOrNull { it.catalogNumber == ref.catalogNumber }

        ctx.reportProgress(0.9f, "整理转发器…")
        val radios = ctx.facade.radios(ref.catalogNumber).map { it.toSnapshot() }
        val card = SatelliteCardData(
            name = ref.name,
            catalogNumber = ref.catalogNumber,
            modes = info?.modes.orEmpty(),
            amsatStatus = info?.status?.ifBlank { null },
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
                    put("in_pass", pass.isCurrentlyVisible)
                    put("is_geo", pass.isGeo)
                    put("max_elevation", pass.maxElevation)
                    put("aos_azimuth", pass.aosAzimuth)
                    put("los_azimuth", pass.losAzimuth)
                    put("daylight", pass.isDaylightPass)
                }
            )
        }
        val inPass = passes.any { it.isCurrentlyVisible }
        val json = JSONObject().apply {
            put("satellite", ref.name)
            put("passes", array)
            if (inPass) {
                put(
                    "note",
                    "当前正在过境：aos 是本次过境开始时间（可能已过去），请用 los 判断剩余时间；" +
                        "下一次过境需在本次结束后重新查询"
                )
            }
        }
        return ToolOutcome(
            modelText = json.toString(),
            card = AiCard.Satellite(card),
            summary = if (inPass) {
                "正在过境 · 最佳仰角 ${passes.maxOf { it.maxElevation }.toInt()}°"
            } else {
                "${passes.size} 次过境 · 最佳仰角 ${passes.maxOf { it.maxElevation }.toInt()}°"
            },
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
        val ref = when (val lookup = resolveOrError(ctx, args)) {
            is SatelliteLookup.Found -> lookup.ref
            is SatelliteLookup.Failed -> return lookup.outcome
        }
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
        val all = ctx.facade.ensureSatellites()
        val info = all.firstOrNull { it.catalogNumber == ref.catalogNumber }
        val now = Instant.now()
        val passes = all
            .filter { it.catalogNumber == ref.catalogNumber && it.losTime.isAfter(now) }
            .sortedBy { it.aosTime }
            .take(3)
            .map { it.toPass() }
        val radios = ctx.facade.radios(ref.catalogNumber).map { it.toSnapshot() }

        val card = SatelliteCardData(
            name = ref.name,
            catalogNumber = ref.catalogNumber,
            modes = info?.modes.orEmpty(),
            amsatStatus = info?.status?.ifBlank { null },
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
        val ref = when (val lookup = resolveOrError(ctx, args)) {
            is SatelliteLookup.Found -> lookup.ref
            is SatelliteLookup.Failed -> return lookup.outcome
        }
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
            modes = SatelliteCatalog.effectiveModes(ref.catalogNumber, radios),
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
        val ref = when (val lookup = resolveOrError(ctx, args)) {
            is SatelliteLookup.Found -> lookup.ref
            is SatelliteLookup.Failed -> return lookup.outcome
        }
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
