package com.example.hamkit.data.ai.tool.tools

import com.example.hamkit.data.LocationResult
import com.example.hamkit.data.ai.tool.AiCard
import com.example.hamkit.data.ai.tool.AiTool
import com.example.hamkit.data.ai.tool.AiToolContext
import com.example.hamkit.data.ai.tool.AiToolSpec
import com.example.hamkit.data.ai.tool.ToolCategory
import com.example.hamkit.data.ai.tool.ToolOutcome
import com.example.hamkit.data.ai.tool.ZoneCardData
import com.example.hamkit.data.ai.tool.errorOutcome
import com.example.hamkit.data.ai.tool.optDoubleOrNullArg
import com.example.hamkit.data.ai.tool.optStringOrNullArg
import com.example.hamkit.data.ai.tool.stringProperty
import com.example.hamkit.data.ai.tool.toolSchema
import com.example.hamkit.data.cw.MorseCodeGenerator
import com.example.hamkit.data.zone.ZoneResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 双区定位：优先用当前定位，也允许模型显式传坐标（例如用户问"东京在几区"）。 */
object GetZoneInfoTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_zone_info",
        title = "双区定位",
        description = "查询当前位置或指定坐标的 CQ 分区、ITU 分区与 6 位 Maidenhead 网格。",
        parameters = toolSchema(
            JSONObject()
                .put("lat", JSONObject().put("type", "number").put("description", "纬度；缺省使用当前定位"))
                .put("lon", JSONObject().put("type", "number").put("description", "经度；缺省使用当前定位")),
        ),
        category = ToolCategory.LOCATION,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val explicitLat = args.optDoubleOrNullArg("lat")
        val explicitLon = args.optDoubleOrNullArg("lon")

        val location: LocationResult = if (explicitLat != null && explicitLon != null) {
            ctx.reportProgress(null, "计算分区与网格…")
            val zone = withContext(Dispatchers.Default) {
                ZoneResolver.resolve(explicitLat, explicitLon)
            }
            LocationResult(
                latitude = explicitLat,
                longitude = explicitLon,
                cqZone = zone.cqZone,
                ituZone = zone.ituZone,
                maidenhead = zone.maidenhead,
            )
        } else {
            ctx.reportProgress(null, "等待定位…")
            ctx.facade.awaitLocation() ?: return errorOutcome("没有拿到定位结果，请检查定位权限或稍后重试")
        }

        val card = ZoneCardData(
            latitude = location.latitude,
            longitude = location.longitude,
            cqZone = location.cqZone,
            ituZone = location.ituZone,
            maidenhead = location.maidenhead,
            address = location.address,
        )
        val json = JSONObject().apply {
            put("lat", location.latitude)
            put("lon", location.longitude)
            location.cqZone?.let { put("cq_zone", it) }
            location.ituZone?.let { put("itu_zone", it) }
            put("grid", location.maidenhead)
            if (location.address.isNotBlank()) put("address", location.address)
        }
        return ToolOutcome(
            modelText = json.toString(),
            card = AiCard.Zone(card),
            summary = "CQ ${location.cqZone ?: "-"} · ITU ${location.ituZone ?: "-"} · ${location.maidenhead}",
        )
    }
}

/** UTC / 本地时间。 */
object GetUtcTimeTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_utc_time",
        title = "UTC 时间",
        description = "返回当前 UTC 时间、本地时间与星期，用于通联时间换算。",
        parameters = toolSchema(JSONObject()),
        category = ToolCategory.UTILITY,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val now = Instant.now()
        val utc = DateTimeFormatter.ISO_INSTANT.format(now)
        val local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(now)
        val json = JSONObject().apply {
            put("utc", utc)
            put("local", local)
            put("epoch_ms", now.toEpochMilli())
        }
        return ToolOutcome(modelText = json.toString(), summary = "UTC $utc")
    }
}

/** 首页天气（高德）。 */
object GetWeatherTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_weather",
        title = "天气",
        description = "返回当前位置的实时天气（温度、天气状况、风向风力、湿度）。",
        parameters = toolSchema(JSONObject()),
        category = ToolCategory.UTILITY,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        ctx.reportProgress(null, "读取天气…")
        val weather = ctx.facade.weather() ?: return errorOutcome("暂无天气数据（可能需要先定位或稍后重试）")
        val json = JSONObject().apply {
            put("city", weather.cityName)
            put("temp", weather.now.temp)
            put("text", weather.now.text)
            put("wind", "${weather.now.windDir} ${weather.now.windPower}")
            put("humidity", weather.now.humidity)
        }
        return ToolOutcome(
            modelText = json.toString(),
            summary = "${weather.cityName} ${weather.now.temp}°C ${weather.now.text}",
        )
    }
}

/** 文本 → 摩斯电码（纯本地，无需网络）。 */
object CwEncodeTool : AiTool {
    override val spec = AiToolSpec(
        name = "cw_encode",
        title = "摩斯编码",
        description = "把英文/数字文本编码成摩斯电码（点划用 . 和 - 表示，字符间用空格）。",
        parameters = toolSchema(
            JSONObject().put("text", stringProperty("需要编码的文本")),
            required = listOf("text"),
        ),
        category = ToolCategory.UTILITY,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val text = args.optStringOrNullArg("text") ?: return errorOutcome("缺少 text 参数")
        if (text.length > 200) return errorOutcome("文本过长（最多 200 字符）")
        val morse = withContext(Dispatchers.Default) { MorseCodeGenerator().toMorseCode(text) }
        val json = JSONObject().put("morse", morse)
        return ToolOutcome(modelText = json.toString(), summary = morse.take(80))
    }
}
