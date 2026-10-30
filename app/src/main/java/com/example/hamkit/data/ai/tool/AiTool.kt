package com.example.hamkit.data.ai.tool

import com.example.hamkit.data.LocationResult
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteInfo
import com.example.hamkit.data.satellite.SatelliteStatusQuery
import com.example.hamkit.data.satellite.SourcedTLE
import com.example.hamkit.data.weather.WeatherResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** 工具分类（只用于卡片图标与排序）。 */
enum class ToolCategory { LOCATION, SATELLITE, SSTV, NAVIGATION, UTILITY }

/** 工具风险等级：READ_ONLY 可直接执行；SIDE_EFFECT 需要用户确认。 */
enum class ToolRisk { READ_ONLY, NAVIGATION, SIDE_EFFECT }

/**
 * 工具规格：名称/描述给模型看，[parameters] 是 JSON Schema。
 */
data class AiToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val parameters: JSONObject,
    val category: ToolCategory,
    val risk: ToolRisk = ToolRisk.READ_ONLY,
    val longRunning: Boolean = false,
)

/** 模型返回的一次工具调用（参数已聚合成完整 JSON 字符串）。 */
data class AiToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/** 应用内可导航的目标（data 层不依赖 ui.navigation3.Route）。 */
enum class AiNavTarget {
    SSTV_RECEIVE, SSTV_GALLERY, SSTV_TX, CW, FT8, APRS, QSO_LOG,
    SATELLITE_LIST, REMINDER_LIST, AI_SETTINGS,
}

/** 卫星引用（名称 + NORAD）。 */
data class AiSatelliteRef(val catalogNumber: Int, val name: String)

/** 卫星卡片数据：一次工具调用把实时、过境、转发器一起带回，UI 只负责渲染。 */
data class SatelliteCardData(
    val name: String,
    val catalogNumber: Int,
    val modes: List<String> = emptyList(),
    val amsatStatus: String? = null,
    val live: LiveSnapshot? = null,
    val passes: List<PassSnapshot> = emptyList(),
    val radios: List<RadioSnapshot> = emptyList(),
    val tleAgeMinutes: Long? = null,
) {
    val nextPass: PassSnapshot? get() = passes.firstOrNull { it.los.isAfter(Instant.now()) }
}

data class LiveSnapshot(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val altitudeKm: Double,
    val subpointLatDeg: Double,
    val subpointLonDeg: Double,
    val phaseDeg: Double,
    val isEclipsed: Boolean,
    val dopplerDownlinkHz: Double?,
    val dopplerUplinkHz: Double?,
)

data class PassSnapshot(
    val aos: Instant,
    val los: Instant,
    val maxElevation: Double,
    val aosAzimuth: Int,
    val losAzimuth: Int,
    val isDaylight: Boolean,
)

data class RadioSnapshot(
    val name: String,
    val uplinkHz: Long?,
    val downlinkHz: Long?,
    val mode: String,
    val status: String,
)

data class ZoneCardData(
    val latitude: Double,
    val longitude: Double,
    val cqZone: Int?,
    val ituZone: Int?,
    val maidenhead: String,
    val address: String,
)

/** 工具执行产物：给模型的紧凑 JSON + 给 UI 的卡片。 */
data class ToolOutcome(
    val modelText: String,
    val card: AiCard? = null,
    val summary: String? = null,
    val error: String? = null,
)

sealed interface AiCard {
    data class Satellite(val data: SatelliteCardData) : AiCard
    data class Zone(val data: ZoneCardData) : AiCard
    data class Sstv(val mode: String?, val imagePath: String? = null) : AiCard
    data class Reminder(val satellite: String, val nextAos: Instant?) : AiCard
    data class Error(val message: String) : AiCard
}

/**
 * 工具执行上下文。
 *
 * [facade] 是 App 能力的唯一入口（定位/卫星/导航/提醒），工具不直接碰 ViewModel；
 * [reportProgress] 用于驱动工具卡进度条（fraction 为 null 表示不确定进度）。
 */
class AiToolContext(
    val facade: AiAppFacade,
    val reportProgress: (fraction: Float?, stage: String) -> Unit,
)

interface AiTool {
    val spec: AiToolSpec
    suspend fun execute(ctx: AiToolContext, args: JSONObject): ToolOutcome
}

/** App 能力门面：由 UI 层实现（持有 MainViewModel / SstvViewModel / Navigator）。 */
interface AiAppFacade {
    fun location(): LocationResult?
    suspend fun awaitLocation(timeoutMs: Long = 10_000): LocationResult?

    fun satellites(): List<SatelliteInfo>
    suspend fun ensureSatellites(timeoutMs: Long = 20_000): List<SatelliteInfo>
    fun tleFor(catalogNumber: Int): SourcedTLE?
    fun radios(catalogNumber: Int): List<RadioInfo>
    fun amsatStatusName(catalogNumber: Int): String?
    fun amsatStatus(name: String): SatelliteStatusQuery?
    fun weather(): WeatherResult?

    /** 名称或 NORAD 编号 → 卫星引用；找不到返回 null。 */
    fun resolveSatellite(query: String): AiSatelliteRef?

    fun navigate(target: AiNavTarget)
    fun startSstvReceive(presetMode: String?)
    fun setReminder(catalogNumber: Int, enabled: Boolean)
}

// ── JSON Schema 构造小工具 ──────────────────────────────────────────────────

fun toolSchema(
    properties: JSONObject,
    required: List<String> = emptyList(),
): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", properties)
    put("required", JSONArray(required))
}

fun stringProperty(description: String): JSONObject =
    JSONObject().put("type", "string").put("description", description)

fun integerProperty(description: String, minimum: Int, maximum: Int): JSONObject =
    JSONObject().put("type", "integer")
        .put("description", description)
        .put("minimum", minimum)
        .put("maximum", maximum)

fun booleanProperty(description: String): JSONObject =
    JSONObject().put("type", "boolean").put("description", description)

/** 统一把异常转成给模型看的错误 JSON。 */
fun errorOutcome(message: String): ToolOutcome = ToolOutcome(
    modelText = JSONObject().put("error", message).toString(),
    card = AiCard.Error(message),
    summary = message,
    error = message,
)

// ── 工具参数读取（Android org.json 对显式 null 返回 "null"）──────────────────

fun JSONObject.optStringOrNullArg(key: String): String? =
    if (!has(key) || isNull(key)) null
    else optString(key).takeIf { it.isNotBlank() && it != "null" }

fun JSONObject.optDoubleOrNullArg(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

fun JSONObject.optIntOrNullArg(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

fun JSONObject.optBooleanOrNullArg(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)
