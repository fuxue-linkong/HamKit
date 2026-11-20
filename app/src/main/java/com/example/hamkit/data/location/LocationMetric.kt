package com.example.hamkit.data.location

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次定位请求的结构化性能指标。
 *
 * 是 LocationHelper 现有文本日志（定位结果 / 定位耗时分解 / 定位慢）的结构化版本，
 * 字段语义与日志一一对应，便于后端按 traceId 聚合分析。
 * 未来接入埋点上报时，由 [LocationMetricRecorder] 的实现负责序列化与发送。
 *
 * JSON 序列化：项目未引入独立 JSON 库依赖，与 WearableAlert 等模块一致，
 * 使用 Android 平台自带的 org.json 实现（[toJson]），不新增 gradle 依赖。
 *
 * @property traceId 链路 ID，与 LocationHelper 日志的 [链路:xxx] 前缀一致
 * @property result 定位结果：SUCCESS / TIMEOUT / FAIL
 * @property resultReason 具体原因：CACHE、NETWORK、GPS、PASSIVE、OTHER、
 *             NO_PERMISSION、LOCATION_DISABLED、NO_RESPONSE_10S、
 *             NO_PROVIDER_ENABLED、SECURITY_EXCEPTION、UNKNOWN_ERROR
 * @property durationMs 定位总耗时（毫秒）
 * @property provider 最终使用的 provider：network / passive / gps / cache / timeout / unknown
 * @property accuracy 精度（米），无精度信息时为 -1f
 * @property cacheCheckMs 缓存查询（getLastKnownLocation 批量查询）耗时（毫秒）
 * @property providerRegisterMs 各 provider 的注册耗时（毫秒），key 为 provider 小写名
 * @property providerFirstResponseMs 各 provider 首次回调耗时（毫秒），
 *             仅含注册成功且有回调的 provider；key 为 provider 小写名
 * @property isSlow 是否为慢定位（超过对应路径的慢阈值）
 * @property slowReason 慢的原因，如 NETWORK_PROVIDER_SLOW、GPS_COLD_START、
 *             CACHE_QUERY_SLOW、TOTAL_SLOW_TIMEOUT 等；非慢定位为 null
 * @property timestamp 请求时间戳（毫秒，System.currentTimeMillis）
 * @property deviceInfo 设备信息，格式如 "Android 33, Pixel 6"；未知时为 null
 */
data class LocationMetric(
    val traceId: String,
    val result: String,
    val resultReason: String,
    val durationMs: Long,
    val provider: String,
    val accuracy: Float,
    val cacheCheckMs: Long,
    val providerRegisterMs: Map<String, Long>,
    val providerFirstResponseMs: Map<String, Long>,
    val isSlow: Boolean,
    val slowReason: String? = null,
    val timestamp: Long,
    val deviceInfo: String? = null
) {
    /**
     * 序列化为 JSON 字符串，字段名与验收 schema 一致。
     * 嵌套耗时 map 序列化为对象；null 字段（slowReason/deviceInfo）省略不输出。
     */
    fun toJson(): String {
        fun mapToJson(map: Map<String, Long>): JSONObject = JSONObject().apply {
            map.forEach { (key, value) -> put(key, value) }
        }
        return JSONObject().apply {
            put("traceId", traceId)
            put("result", result)
            put("resultReason", resultReason)
            put("durationMs", durationMs)
            put("provider", provider)
            put("accuracy", accuracy.toDouble())
            put("cacheCheckMs", cacheCheckMs)
            put("providerRegisterMs", mapToJson(providerRegisterMs))
            put(
                "providerFirstResponseMs",
                mapToJson(providerFirstResponseMs.filterValues { it != null })
            )
            put("isSlow", isSlow)
            slowReason?.let { put("slowReason", it) }
            put("timestamp", timestamp)
            deviceInfo?.let { put("deviceInfo", it) }
        }.toString()
    }

    companion object {
        /** 从 JSON 字符串反序列化（供测试与未来上报链路核对字段） */
        fun fromJson(json: String): LocationMetric {
            val obj = JSONObject(json)
            fun mapFromJson(key: String): MutableMap<String, Long> {
                val mapObj = obj.optJSONObject(key) ?: JSONObject()
                val map = mutableMapOf<String, Long>()
                mapObj.keys().forEach { k -> map[k] = mapObj.getLong(k) }
                return map
            }
            val registerMs = mapFromJson("providerRegisterMs")
            val firstResponse = mapFromJson("providerFirstResponseMs")
            return LocationMetric(
                traceId = obj.getString("traceId"),
                result = obj.getString("result"),
                resultReason = obj.getString("resultReason"),
                durationMs = obj.getLong("durationMs"),
                provider = obj.getString("provider"),
                accuracy = obj.getDouble("accuracy").toFloat(),
                cacheCheckMs = obj.getLong("cacheCheckMs"),
                providerRegisterMs = registerMs,
                providerFirstResponseMs = firstResponse,
                isSlow = obj.getBoolean("isSlow"),
                slowReason = obj.optString("slowReason").takeIf { it.isNotEmpty() },
                timestamp = obj.getLong("timestamp"),
                deviceInfo = obj.optString("deviceInfo").takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * 把 provider 注册耗时 map 的「起点时间戳」换算为「耗时（毫秒）」。
 *
 * LocationHelper 内部各 provider 注册时记录的是起点时间戳（providerRegisterStartMs），
 * 上报前统一用 [nowMs] 减去各起点得到耗时。
 */
internal fun Map<String, Long>.toRegisterCostMap(nowMs: Long): Map<String, Long> =
    mapValues { (_, startMs) -> (nowMs - startMs).coerceAtLeast(0L) }

/**
 * 拼接设备信息字符串，格式如 "Android 33, Pixel 6"。
 * 供 LocationHelper 在构建 [LocationMetric] 时填充 deviceInfo 使用。
 */
internal fun deviceInfoString(): String =
    "Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}"
