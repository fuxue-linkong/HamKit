package com.example.hamkit.data.ai.tool

import com.example.hamkit.data.ai.tool.tools.AddPassReminderTool
import com.example.hamkit.data.ai.tool.tools.CwEncodeTool
import com.example.hamkit.data.ai.tool.tools.GetAmsatStatusTool
import com.example.hamkit.data.ai.tool.tools.GetSatelliteLiveTool
import com.example.hamkit.data.ai.tool.tools.GetSatellitePassesTool
import com.example.hamkit.data.ai.tool.tools.GetSatelliteTransmittersTool
import com.example.hamkit.data.ai.tool.tools.GetUtcTimeTool
import com.example.hamkit.data.ai.tool.tools.GetWeatherTool
import com.example.hamkit.data.ai.tool.tools.GetZoneInfoTool
import com.example.hamkit.data.ai.tool.tools.OpenScreenTool
import com.example.hamkit.data.ai.tool.tools.SearchSatellitesTool
import com.example.hamkit.data.ai.tool.tools.StartSstvReceiveTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * 工具注册表：名称 → 工具实例。
 *
 * 新增工具只需在 [ALL] 里追加；协议层会自动把 [specs] 序列化进请求。
 */
class AiToolRegistry {

    private val tools: Map<String, AiTool> = ALL.associateBy { it.spec.name }

    fun specs(disabled: Set<String> = emptySet()): List<AiToolSpec> =
        tools.values.filter { it.spec.name !in disabled }.map { it.spec }

    fun find(name: String): AiTool? = tools[name]

    companion object {
        /** 顺序影响模型看到工具的顺序（定位/卫星在前，动作在后）。 */
        val ALL: List<AiTool> = listOf(
            GetZoneInfoTool,
            GetSatellitePassesTool,
            GetSatelliteLiveTool,
            GetSatelliteTransmittersTool,
            SearchSatellitesTool,
            GetAmsatStatusTool,
            GetUtcTimeTool,
            GetWeatherTool,
            CwEncodeTool,
            OpenScreenTool,
            StartSstvReceiveTool,
            AddPassReminderTool,
        )
    }
}

/**
 * 工具执行器：解析参数、超时、异常兜底。
 *
 * 进度通过 [AiToolContext.reportProgress] 上报，由调用方（ViewModel）驱动工具卡。
 */
class AiToolExecutor(private val registry: AiToolRegistry) {

    suspend fun execute(call: AiToolCall, ctx: AiToolContext): ToolOutcome {
        val tool = registry.find(call.name)
            ?: return errorOutcome("未知工具：${call.name}")

        val args = runCatching {
            JSONObject(call.argsJson.ifBlank { "{}" })
        }.getOrElse {
            return errorOutcome("工具参数不是合法 JSON：${call.argsJson.take(120)}")
        }

        val timeoutMs = if (tool.spec.longRunning) LONG_TIMEOUT_MS else TIMEOUT_MS
        return try {
            withTimeout(timeoutMs) { tool.execute(ctx, args) }
        } catch (timeout: TimeoutCancellationException) {
            errorOutcome("「${tool.spec.title}」执行超时")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            errorOutcome("「${tool.spec.title}」失败：${t.message ?: t::class.java.simpleName}")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val LONG_TIMEOUT_MS = 60_000L
    }
}
