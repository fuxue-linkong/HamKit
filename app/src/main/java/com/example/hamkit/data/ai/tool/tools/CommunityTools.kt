package com.example.hamkit.data.ai.tool.tools

import com.example.hamkit.data.ai.tool.AiTool
import com.example.hamkit.data.ai.tool.AiToolContext
import com.example.hamkit.data.ai.tool.AiToolSpec
import com.example.hamkit.data.ai.tool.ToolCategory
import com.example.hamkit.data.ai.tool.ToolOutcome
import com.example.hamkit.data.ai.tool.errorOutcome
import com.example.hamkit.data.ai.tool.integerProperty
import com.example.hamkit.data.ai.tool.optIntOrNullArg
import com.example.hamkit.data.ai.tool.optStringOrNullArg
import com.example.hamkit.data.ai.tool.ToolRisk
import com.example.hamkit.data.ai.tool.integerProperty
import com.example.hamkit.data.ai.tool.stringProperty
import com.example.hamkit.data.ai.tool.toolSchema
import org.json.JSONArray
import org.json.JSONObject
import com.example.hamkit.data.ai.tool.optLongOrNullArg
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord

/** FT8 活动：本次会话最近的解码与 QSO。 */
object GetFt8ActivityTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_ft8_activity",
        title = "FT8 活动",
        description = "读取本次会话最近的 FT8 解码（消息、SNR、频偏）与 QSO 记录。" +
            "解码列表在内存中，重启 App 后为空；需要历史通联请用 search_qso_log。",
        parameters = toolSchema(
            JSONObject()
                .put("max", integerProperty("最多返回条数", 1, 50))
                .put("callsign", stringProperty("按呼号过滤，可省略")),
        ),
        category = ToolCategory.UTILITY,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val snapshot = ctx.facade.ft8Snapshot() ?: return errorOutcome("FT8 模块不可用")
        val max = (args.optIntOrNullArg("max") ?: 10).coerceIn(1, 50)
        val callsign = args.optStringOrNullArg("callsign")?.trim()?.takeIf { it.isNotEmpty() }

        val decoded = snapshot.decoded
            .filter { callsign == null || it.message.contains(callsign, ignoreCase = true) }
            .sortedByDescending { it.time }
            .take(max)
        val qso = snapshot.qso
            .filter { callsign == null || it.callsign.contains(callsign, ignoreCase = true) }
            .sortedByDescending { it.qsoTime }
            .take(max)

        val json = JSONObject().apply {
            put("decoding", snapshot.decoding)
            put("decoded", JSONArray().apply {
                decoded.forEach { entry ->
                    put(JSONObject().apply {
                        put("time", entry.time)
                        put("message", entry.message)
                        put("snr", entry.snr)
                        put("freq_hz", entry.freqHz)
                    })
                }
            })
            put("qso", JSONArray().apply {
                qso.forEach { record ->
                    put(JSONObject().apply {
                        put("callsign", record.callsign)
                        put("grid", record.grid ?: "")
                        put("band", record.band)
                        put("mode", record.mode)
                        put("time", record.qsoTime)
                        put("complete", record.isComplete)
                    })
                }
            })
            if (decoded.isEmpty() && qso.isEmpty()) {
                put("note", "本次会话还没有 FT8 解码或 QSO 记录")
            }
        }
        return ToolOutcome(
            modelText = json.toString(),
            summary = "FT8 解码 ${decoded.size} 条，QSO ${qso.size} 条",
        )
    }
}

/** APRS 已接收台站（只读当前会话，不发起新查询）。 */
object GetAprsStationsTool : AiTool {
    override val spec = AiToolSpec(
        name = "get_aprs_stations",
        title = "APRS 台站",
        description = "列出当前 APRS 会话已接收的台站（呼号、距离、方位、最后报告）。" +
            "只读内存数据：不发起新查询、不修改 filter、不自动连接；" +
            "接收范围是固定 filter，不代表以你为中心的完整列表。",
        parameters = toolSchema(
            JSONObject()
                .put("radius_km", JSONObject().put("type", "number")
                    .put("description", "在已接收台站里按距离过滤，默认 100")
                    .put("minimum", 1).put("maximum", 1000))
                .put("max", integerProperty("最多返回条数", 1, 50)),
        ),
        category = ToolCategory.UTILITY,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val snapshot = ctx.facade.aprsSnapshot() ?: return errorOutcome("APRS 模块不可用")
        if (!snapshot.connected) {
            return errorOutcome("APRS 未连接，工具不会自动连接；请先在 APRS 页连接")
        }
        val radiusKm = if (args.has("radius_km") && !args.isNull("radius_km")) {
            args.optDouble("radius_km", 100.0).coerceIn(1.0, 1000.0)
        } else {
            100.0
        }
        val max = (args.optIntOrNullArg("max") ?: 10).coerceIn(1, 50)

        val filtered = snapshot.stations
            .filter { station ->
                val distance = station.distanceKm
                distance == null || distance <= radiusKm
            }
            .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            .take(max)

        val json = JSONObject().apply {
            put("connected", true)
            put("scope", "session")
            put("filter_note", "当前为固定范围接收（非以你为中心），仅含本次会话已收到的台站")
            put("total", snapshot.stations.size)
            put("returned", filtered.size)
            put("stations", JSONArray().apply {
                filtered.forEach { station ->
                    put(JSONObject().apply {
                        put("callsign", station.callsign)
                        station.distanceKm?.let { put("distance_km", it) }
                        station.bearing?.let { put("bearing", it) }
                        put("last_report", station.timestamp)
                        if (station.comment.isNotBlank()) put("comment", station.comment)
                    })
                }
            })
            if (filtered.isEmpty()) put("note", "本次会话还没有收到符合范围的 APRS 台站")
        }
        return ToolOutcome(
            modelText = json.toString(),
            summary = "APRS 台站 ${filtered.size} 个",
        )
    }
}

/** QSO 日志检索。 */
object SearchQsoLogTool : AiTool {
    override val spec = AiToolSpec(
        name = "search_qso_log",
        title = "QSO 日志检索",
        description = "按呼号与时间范围检索本机 QSO 日志（FT8 与手动记录都在同一库）。",
        parameters = toolSchema(
            JSONObject()
                .put("callsign", stringProperty("按呼号过滤，可省略"))
                .put("days", integerProperty("最近多少天", 1, 365))
                .put("max", integerProperty("最多返回条数", 1, 100)),
        ),
        category = ToolCategory.UTILITY,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val records = ctx.facade.qsoRecords()
        if (records.isEmpty()) return errorOutcome("QSO 日志为空")

        val callsign = args.optStringOrNullArg("callsign")?.trim()?.takeIf { it.isNotEmpty() }
        val days = (args.optIntOrNullArg("days") ?: 30).coerceIn(1, 365)
        val max = (args.optIntOrNullArg("max") ?: 20).coerceIn(1, 100)
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L

        val filtered = records
            .filter { record ->
                (callsign == null || record.callsign.contains(callsign, ignoreCase = true)) &&
                    record.qsoTime >= cutoff
            }
            .sortedByDescending { it.qsoTime }
        val page = filtered.take(max)

        val json = JSONObject().apply {
            put("total", filtered.size)
            put("returned", page.size)
            put("days", days)
            put("truncated", filtered.size > page.size)
            put("records", JSONArray().apply {
                page.forEach { record ->
                    put(JSONObject().apply {
                        put("callsign", record.callsign)
                        put("grid", record.grid ?: "")
                        put("band", record.band)
                        put("mode", record.mode)
                        put("time", record.qsoTime)
                        put("complete", record.isComplete)
                    })
                }
            })
        }
        return ToolOutcome(
            modelText = json.toString(),
            summary = "QSO ${page.size} 条（共 ${filtered.size}）",
        )
    }
}

/** 呼号规范化：去空格、大写，并校验常见格式（可选 /P、/M 等后缀）。 */
internal fun normalizeCallsign(raw: String): String? {
    val value = raw.trim().uppercase()
    return value.takeIf { Regex("^[A-Z0-9]{3,12}(/[A-Z0-9]{1,3})?$").matches(it) }
}

/** 波段解析：支持 40m / BAND_40M / 40M；空值默认 40m。 */
internal fun parseBand(raw: String?): Ft8Band? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return Ft8Band.BAND_40M
    return Ft8Band.entries.firstOrNull {
        it.displayName.equals(value, ignoreCase = true) ||
            it.name.equals(value, ignoreCase = true) ||
            it.name.removePrefix("BAND_").equals(value, ignoreCase = true)
    }
}

/** QSO 时间：非法/0 回退当前时间。 */
internal fun parseQsoTime(epochMs: Long?): Long =
    epochMs?.takeIf { it > 0 } ?: System.currentTimeMillis()

/** 写入一条 QSO 日志（写操作，执行前走二次确认）。 */
object AddQsoLogTool : AiTool {
    override val spec = AiToolSpec(
        name = "add_qso_log",
        title = "记录 QSO",
        description = "写入一条 QSO 日志（写操作，执行前会弹确认）。缺少呼号或波段时先问用户；" +
            "不要编造信号报告。默认波段 40m、模式 FT8。",
        parameters = toolSchema(
            JSONObject()
                .put("callsign", stringProperty("对方呼号，如 XX9XX"))
                .put("band", stringProperty("波段，如 40m/20m/2m，默认 40m"))
                .put("mode", stringProperty("模式，默认 FT8"))
                .put("freq_hz", integerProperty("频率 Hz，可省略", 1, 1_300_000_000))
                .put("report_sent", stringProperty("发出的信号报告，如 -12"))
                .put("report_received", stringProperty("收到的信号报告，如 -08"))
                .put("grid", stringProperty("对方网格"))
                .put("my_grid", stringProperty("本台网格"))
                .put("comment", stringProperty("备注"))
                .put(
                    "qso_time_epoch_ms",
                    JSONObject().put("type", "integer")
                        .put("description", "通联时间（epoch 毫秒），可省略"),
                ),
            required = listOf("callsign"),
        ),
        category = ToolCategory.UTILITY,
        risk = ToolRisk.SIDE_EFFECT,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val rawCallsign = args.optStringOrNullArg("callsign")
            ?: return errorOutcome("缺少 callsign 参数")
        val callsign = normalizeCallsign(rawCallsign)
            ?: return errorOutcome("呼号格式不正确：$rawCallsign")
        val band = parseBand(args.optStringOrNullArg("band"))
            ?: return errorOutcome(
                "无法识别波段，可用：" +
                    Ft8Band.entries.joinToString { it.displayName },
            )
        val mode = args.optStringOrNullArg("mode")?.trim()?.takeIf { it.isNotEmpty() } ?: "FT8"
        val qsoTime = parseQsoTime(args.optLongOrNullArg("qso_time_epoch_ms"))
        val record = Ft8QsoRecord(
            callsign = callsign,
            grid = args.optStringOrNullArg("grid"),
            reportSent = args.optStringOrNullArg("report_sent") ?: "-99",
            reportReceived = args.optStringOrNullArg("report_received") ?: "-99",
            band = band,
            freqHz = args.optLongOrNullArg("freq_hz") ?: band.freqHz,
            mode = mode,
            qsoTime = qsoTime,
            isComplete = true,
            myGrid = args.optStringOrNullArg("my_grid"),
            comment = args.optStringOrNullArg("comment"),
        )

        ctx.reportProgress(null, "写入 QSO 日志…")
        val id = ctx.facade.addQso(record)
        if (id <= 0) return errorOutcome("写入 QSO 日志失败")

        val json = JSONObject().apply {
            put("saved", true)
            put("id", id)
            put("callsign", callsign)
            put("band", band.displayName)
            put("mode", mode)
            put("qso_time", qsoTime)
        }
        return ToolOutcome(
            modelText = json.toString(),
            summary = "已记录 QSO 与 $callsign（${band.displayName} $mode）",
        )
    }
}
