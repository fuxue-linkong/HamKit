package com.example.hamkit.shared

import com.example.hamkit.shared.net.HttpTransport

/**
 * AMSAT 卫星状态（summary 聚合）—— 从 Android `AmsatStatusApiService` 移植。
 *
 * 只移植列表卡片需要的部分：每颗卫星一条代表状态（按 report_count 取最多）。
 * Android 的 15 分钟时间槽延续算法（`SatelliteStatusTracker`）依赖 java.time，
 * iOS 侧后续单独移植。
 */
data class AmsatStatusSummary(
    /** AMSAT 原始名称，如 "AO-91_[FM]" */
    val name: String,
    /** 状态值：HEARD / TELEMETRY / NOT HEARD 等 */
    val status: String,
)

data class AmsatStatusSnapshot(
    val summaries: List<AmsatStatusSummary>,
    val error: String?,
)

object AmsatStatus {

    private const val BASE_URL = "https://www.amsat.org/status/api/v1"

    /** 拉取过去 [hours] 小时的 summary；失败时回调 error。 */
    fun fetchSummary(
        transport: HttpTransport,
        hours: Int = 24,
        callback: (AmsatStatusSnapshot) -> Unit,
    ) {
        transport.get("$BASE_URL/summary.php?hours=$hours", 30_000) { response ->
            if (!response.isSuccess) {
                callback(AmsatStatusSnapshot(emptyList(), response.error ?: "AMSAT status 请求失败：HTTP ${response.status}"))
                return@get
            }
            val body = response.body ?: run {
                callback(AmsatStatusSnapshot(emptyList(), "AMSAT status 响应为空"))
                return@get
            }
            try {
                callback(AmsatStatusSnapshot(parseSummary(body), null))
            } catch (e: Exception) {
                callback(AmsatStatusSnapshot(emptyList(), "AMSAT 解析失败：${e.message}"))
            }
        }
    }

    /** 解析 summary.php：同一 name 多条记录时取 report_count 最大者。 */
    fun parseSummary(json: String): List<AmsatStatusSummary> {
        val root = MiniJson.parseObject(json)
        val data = root["data"] as? List<*> ?: return emptyList()
        val best = LinkedHashMap<String, Pair<String, Int>>()
        for (raw in data) {
            val item = raw as? Map<*, *> ?: continue
            val name = (item["name"] as? String)?.trim().orEmpty()
            val report = (item["report"] as? String)?.trim().orEmpty()
            val count = (item["report_count"] as? Double)?.toInt() ?: 0
            if (name.isEmpty() || report.isEmpty()) continue
            val current = best[name]
            if (current == null || count > current.second) {
                best[name] = report to count
            }
        }
        return best.map { (name, pair) -> AmsatStatusSummary(name, pair.first) }
    }
}
