package com.example.hamkit.data.ai.tool.tools

import com.example.hamkit.data.ai.tool.AiCard
import com.example.hamkit.data.ai.tool.AiNavTarget
import com.example.hamkit.data.ai.tool.AiTool
import com.example.hamkit.data.ai.tool.AiToolContext
import com.example.hamkit.data.ai.tool.AiToolSpec
import com.example.hamkit.data.ai.tool.ToolCategory
import com.example.hamkit.data.ai.tool.ToolOutcome
import com.example.hamkit.data.ai.tool.ToolRisk
import com.example.hamkit.data.ai.tool.errorOutcome
import com.example.hamkit.data.ai.tool.optStringOrNullArg
import com.example.hamkit.data.ai.tool.stringProperty
import com.example.hamkit.data.ai.tool.toolSchema
import com.example.hamkit.data.sstv.SstvMode
import org.json.JSONObject
import java.time.Instant

private val SCREEN_TARGETS: Map<String, AiNavTarget> = mapOf(
    "sstv_receive" to AiNavTarget.SSTV_RECEIVE,
    "sstv_gallery" to AiNavTarget.SSTV_GALLERY,
    "sstv_tx" to AiNavTarget.SSTV_TX,
    "cw" to AiNavTarget.CW,
    "ft8" to AiNavTarget.FT8,
    "aprs" to AiNavTarget.APRS,
    "qso_log" to AiNavTarget.QSO_LOG,
    "satellite_list" to AiNavTarget.SATELLITE_LIST,
    "reminder_list" to AiNavTarget.REMINDER_LIST,
    "ai_settings" to AiNavTarget.AI_SETTINGS,
)

/** 打开 App 内页面（只跳转，不执行动作）。 */
object OpenScreenTool : AiTool {
    override val spec = AiToolSpec(
        name = "open_screen",
        title = "打开页面",
        description = "打开 App 内页面：sstv_receive / sstv_gallery / sstv_tx / cw / ft8 / aprs / " +
            "qso_log / satellite_list / reminder_list / ai_settings。",
        parameters = toolSchema(
            JSONObject().put(
                "screen",
                JSONObject().put("type", "string")
                    .put("enum", org.json.JSONArray(SCREEN_TARGETS.keys.toList()))
                    .put("description", "目标页面"),
            ),
            required = listOf("screen"),
        ),
        category = ToolCategory.NAVIGATION,
        risk = ToolRisk.NAVIGATION,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val screen = args.optStringOrNullArg("screen")?.lowercase()
            ?: return errorOutcome("缺少 screen 参数")
        val target = SCREEN_TARGETS[screen]
            ?: return errorOutcome("未知页面：$screen")
        ctx.facade.navigate(target)
        return ToolOutcome(
            modelText = JSONObject().put("opened", screen).toString(),
            summary = "已打开 $screen",
        )
    }
}

/** 打开 SSTV 接收页并自动开始接收（跨页面长任务）。 */
object StartSstvReceiveTool : AiTool {
    override val spec = AiToolSpec(
        name = "start_sstv_receive",
        title = "开始 SSTV 接收",
        description = "打开 SSTV 接收页并立即开始接收；可指定模式（如 PD_120、Robot36），不指定则 VIS 自动识别。",
        parameters = toolSchema(
            JSONObject().put("mode", stringProperty("SSTV 模式名，如 PD_120、Robot36；可省略")),
        ),
        category = ToolCategory.SSTV,
        risk = ToolRisk.NAVIGATION,
        longRunning = true,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val raw = args.optStringOrNullArg("mode")
        val normalized = raw?.uppercase()?.replace('-', '_')?.replace(" ", "_")
        val mode = normalized?.let { name ->
            SstvMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        if (normalized != null && mode == null) {
            return errorOutcome("未知 SSTV 模式：$raw（可用 PD_120、Robot36、Martin1 等）")
        }
        ctx.facade.startSstvReceive(mode?.name)
        val json = JSONObject().apply {
            put("started", true)
            put("mode", mode?.name ?: "VIS_AUTO")
        }
        return ToolOutcome(
            modelText = json.toString(),
            card = AiCard.Sstv(mode?.name),
            summary = "开始接收（${mode?.name ?: "自动识别"}）",
        )
    }
}

/** 创建过境提醒（写入系统闹钟）。 */
object AddPassReminderTool : AiTool {
    override val spec = AiToolSpec(
        name = "add_pass_reminder",
        title = "创建过境提醒",
        description = "为指定卫星创建下一次过境的系统提醒。",
        parameters = toolSchema(
            JSONObject().put("satellite", stringProperty("卫星名称或 NORAD 编号")),
            required = listOf("satellite"),
        ),
        category = ToolCategory.UTILITY,
        risk = ToolRisk.SIDE_EFFECT,
    )

    override suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome {
        val query = args.optStringOrNullArg("satellite")
            ?: return errorOutcome("缺少 satellite 参数")
        val ref = ctx.facade.resolveSatellite(query)
            ?: return errorOutcome("没有找到这颗卫星，请给出名称或 NORAD 编号")

        val now = Instant.now()
        val nextPass = ctx.facade.satellites()
            .filter { it.catalogNumber == ref.catalogNumber && it.losTime.isAfter(now) }
            .minByOrNull { it.aosTime }

        ctx.reportProgress(null, "写入过境提醒…")
        ctx.facade.setReminder(ref.catalogNumber, true)

        val json = JSONObject().apply {
            put("scheduled", true)
            put("satellite", ref.name)
            nextPass?.let { put("next_aos", it.aosTime.toString()) }
        }
        return ToolOutcome(
            modelText = json.toString(),
            card = AiCard.Reminder(ref.name, nextPass?.aosTime),
            summary = nextPass?.let { "已提醒 · 下次 AOS ${it.aosTime}" } ?: "已开启提醒",
        )
    }
}
