package com.example.hamkit.shared

import com.example.hamkit.shared.net.HttpTransport

/**
 * SatNOGS DB 转发器（transmitter）数据模型。
 *
 * 从 Android `data/satellite/TransmitterDataSource.kt` + `RadioInfo.kt` 移植：
 * 字段语义、缺省处理（频率 0 视为缺失、name/description 为字面量 "null" 视为缺失、
 * 状态缺省 unknown）保持一致。
 */
data class Transmitter(
    val norad: Int,
    val name: String,
    val mode: String,
    /** 上行频率（Hz）；0 表示无 */
    val uplinkHz: Long,
    /** 下行频率（Hz）；0 表示无 */
    val downlinkHz: Long,
    val inverted: Boolean,
    /** active / inactive / future / unknown */
    val status: String,
) {
    val isActive: Boolean get() = status == STATUS_ACTIVE

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_INACTIVE = "inactive"
        const val STATUS_FUTURE = "future"
        const val STATUS_UNKNOWN = "unknown"
    }
}

/** 一次转发器拉取结果；[error] 非空表示失败（列表可能为空）。 */
data class TransmitterSnapshot(
    val transmitters: List<Transmitter>,
    val error: String?,
)

/**
 * SatNOGS DB 转发器数据源（KMP 版）。
 *
 * 依赖注入的 [HttpTransport]，与 TLE 共用平台网络栈；JSON 解析复用本文件的
 * 轻量解析器，避免为 iOS 引入 org.json / kotlinx-serialization 依赖。
 */
object SatnogsTransmitters {

    /**
     * 兜底源：SatNOGS DB 官方。
     *
     * 国内经常超时 / 被 DNS 污染，所以排在自建 CDN 之后（与 TLE 同样的主次关系：
     * 主 = `tle.hamkit.click`，次 = 上游官方源）。
     */
    const val URL = "https://db.satnogs.org/api/transmitters/?format=json"

    /**
     * **主源**：自建 CDN 上的静态快照（同 [URL] 的 JSON 数组）。
     *
     * 放在 `tle/` 前缀下，和 `satnogs.3le` / `amateur.3le` 等文件同一目录
     * （由 `website/deploy/hamkit-tle-sync.sh` 每 24 小时同步），国内直连、
     * 无 DNS 污染、有 CDN 缓存，与 TLE 的主次策略保持一致。
     */
    const val MIRROR_URL = "https://tle.hamkit.click/tle/transmitters.json"

    /** 主 = 自建 CDN；次 = SatNOGS 官方（与 TLE 一致的主次顺序）。 */
    private val SOURCES: List<String> = listOf(MIRROR_URL, URL)

    /** 拉取全部转发器；全部源都失败时回调 [TransmitterSnapshot.error]。 */
    fun fetch(transport: HttpTransport, callback: (TransmitterSnapshot) -> Unit) {
        fetchFrom(transport, 0, emptyList(), callback)
    }

    private fun fetchFrom(
        transport: HttpTransport,
        index: Int,
        failures: List<String>,
        callback: (TransmitterSnapshot) -> Unit,
    ) {
        if (index >= SOURCES.size) {
            callback(
                TransmitterSnapshot(
                    transmitters = emptyList(),
                    error = failures.joinToString("；").ifEmpty { "没有可用的转发器数据源" },
                )
            )
            return
        }
        val url = SOURCES[index]
        // 全量转发器 JSON 有几 MB，30s 在弱网下经常读不完（Android 端同样给了 30s，
        // 但它有 Room 缓存兜底）；iOS 放宽到 60s，并配合 UI 的一次自动重试。
        transport.get(url, 60_000) { response ->
            if (!response.isSuccess) {
                val reason = response.error ?: "HTTP ${response.status}"
                fetchFrom(transport, index + 1, failures + "$url → $reason", callback)
                return@get
            }
            val body = response.body
            if (body.isNullOrBlank()) {
                fetchFrom(transport, index + 1, failures + "$url → 响应为空", callback)
                return@get
            }
            val parsed = try {
                parse(body)
            } catch (e: Exception) {
                fetchFrom(transport, index + 1, failures + "$url → 解析失败：${e.message}", callback)
                return@get
            }
            if (parsed.isEmpty()) {
                fetchFrom(transport, index + 1, failures + "$url → 解析结果为空", callback)
                return@get
            }
            callback(TransmitterSnapshot(parsed, null))
        }
    }

    /** 解析 SatNOGS transmitters API 的 JSON 数组。解析失败抛异常。 */
    fun parse(json: String): List<Transmitter> {
        val items = MiniJson.parseArrayRoot(json)
        return items.mapNotNull { parseItem(it) }
    }

    private fun parseItem(raw: Any?): Transmitter? {
        val item = raw as? Map<*, *> ?: return null
        // 字段读取一律走「宽松取值」：Android 用的是 org.json 的 optInt / optLong /
        // optBoolean，数字/数字字符串、布尔/"true" 字符串都接受；MiniJson 只有
        // Double/String/Boolean 三种标量，这里对齐同样的宽容度，避免 CDN 镜像
        // 与官方接口在类型上略有差异就整条丢弃。
        val norad = optDouble(item["norad_cat_id"])?.toInt() ?: return null
        if (norad <= 0) return null

        val uplink = optDouble(item["uplink_low"])?.toLong() ?: 0L
        val downlink = optDouble(item["downlink_low"])?.toLong() ?: 0L
        if (uplink <= 0 && downlink <= 0) return null

        val mode = cleanField(item["uplink_mode"] as? String)
            ?: cleanField(item["downlink_mode"] as? String)
            ?: cleanField(item["mode"] as? String)
            ?: ""

        val rawName = cleanField(item["name"] as? String)
        val description = cleanField(item["description"] as? String)
        val name = rawName ?: description ?: run {
            val freqHz = if (downlink > 0) downlink else uplink
            val freqMhz = formatMhz(freqHz)
            if (mode.isNotBlank()) "$mode $freqMhz" else freqMhz
        }

        val status = (item["status"] as? String)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: Transmitter.STATUS_UNKNOWN

        // 上游有的版本把极性字段叫 invert，有的叫 inverted，两个都认。
        val inverted = optBoolean(item["inverted"]) ?: optBoolean(item["invert"]) ?: false

        return Transmitter(
            norad = norad,
            name = name,
            mode = mode.trim(),
            uplinkHz = uplink,
            downlinkHz = downlink,
            inverted = inverted,
            status = status,
        )
    }

    /** 等价于 org.json 的 `optDouble`：数字或数字字符串；非法值当 0（返回 null 由调用方兜底）。 */
    private fun optDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        else -> null
    }

    /** 等价于 org.json 的 `optBoolean`：布尔或 "true"/"false" 字符串。 */
    private fun optBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    /** 空串/空白/字面量 "null" 视为缺失；剔除中括号（"[Beacon]" → "Beacon"）。 */
    private fun cleanField(value: String?): String? =
        value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.filterNot { it == '[' || it == ']' }
            ?.trim()
            ?.ifEmpty { null }

    private fun formatMhz(hz: Long): String {
        val mhz = hz / 1_000_000.0
        val whole = mhz.toLong()
        val frac = ((mhz - whole) * 1000).toLong()
        return "$whole.${frac.toString().padStart(3, '0')} MHz"
    }
}
