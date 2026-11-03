package com.example.hamkit.data.satellite

import android.util.Log
import com.example.hamkit.data.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SatNOGS 转发器数据源。
 *
 * **数据地址固定为自建 CDN 上的快照** `https://tle.hamkit.click/tle/transmitters.json`
 * （由 `website/deploy/hamkit-tle-sync.sh` 每 24 小时从 SatNOGS DB 同步并裁剪）。
 * 不再直连 `db.satnogs.org`：官方接口在国内容易被 DNS 污染 / 超时，且返回十几 MB
 * 全量字段，首拉经常读不完；CDN 快照按 [parseTransmitterItem] 的规则裁剪过，
 * 国内直连、有 CDN 缓存，体积约为官方的 1/3。
 *
 * 快照里仍是 JSON 数组，字段名保持 SatNOGS 原名（norad_cat_id / uplink_low /
 * downlink_low / uplink_mode / downlink_mode / mode / name / description /
 * inverted / status），解析结果与直连官方时逐条一致。
 *
 * JSON 解析使用 org.json（Android 自带，与 [AmsatStatusApiService] 风格一致），
 * 不做强类型校验：字段缺失/为 null 时按默认值处理，单条解析失败跳过该条。
 */
class TransmitterDataSource {

    // 基于共享单例派生：共享连接池/线程池，仅覆盖本服务的超时配置
    private val client = HttpClientProvider.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取全部转发器（含非活跃）。网络失败时抛 [IOException]（由 Repository 层静默降级）。
     */
    suspend fun fetchTransmitters(): List<RadioInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(TRANSMITTERS_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("转发器快照请求失败：${response.code}（$TRANSMITTERS_URL）")
            }
            val body = response.body?.string() ?: throw IOException("转发器快照响应为空")
            parseTransmitters(body)
        }
    }

    /**
     * 解析 SatNOGS transmitters API 的 JSON 数组（org.json）。
     *
     * 字段：uuid, norad_cat_id, name, uplink_low/uplink_high/uplink_mode,
     * downlink_low/downlink_high/downlink_mode, inverted, status, description。
     * 频率字段可能为 null（该转发器只有上行或只有下行），使用 optLong 的
     * 0 哨兵过滤；uuid 作为主键，重复记录按最后一个生效。
     */
    internal fun parseTransmitters(json: String): List<RadioInfo> {
        val arr = try {
            JSONArray(json)
        } catch (e: JSONException) {
            throw IOException("SatNOGS transmitters 响应不是有效 JSON: ${e.message}")
        }

        val result = mutableListOf<RadioInfo>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val radio = parseTransmitterItem(item) ?: continue
            result.add(radio)
        }
        if (result.isEmpty()) {
            Log.w(TAG, "SatNOGS transmitters 解析结果为空（共 ${arr.length()} 条记录）")
        }
        return result
    }

    /**
     * 解析单条转发器 JSON 对象。缺失上下行频率或 norad_cat_id 非法时返回 null。
     */
    internal fun parseTransmitterItem(item: JSONObject): RadioInfo? {
        val noradCatId = item.optInt("norad_cat_id", -1)
        if (noradCatId <= 0) return null

        // 频率字段可能为 null（JSONObject.NULL → optLong 返回默认值 0）
        val uplinkHz = item.optLong("uplink_low", 0L).takeIf { it > 0 }
        val downlinkHz = item.optLong("downlink_low", 0L).takeIf { it > 0 }
        if (uplinkHz == null && downlinkHz == null) return null

        // 模式优先取上行模式，其次下行模式，再其次旧版 "mode" 字段
        val mode = item.optString("uplink_mode", "").cleanTransmitterField()
            ?: item.optString("downlink_mode", "").cleanTransmitterField()
            ?: item.optString("mode", "").cleanTransmitterField()
            ?: ""

        // 名称：优先 API name，其次 description，最后按模式+频率拼接。
        // name/description 为 JSON null 时 optString 返回字面量 "null"，视为缺失；
        // 中括号 []（如 "[Beacon]"）一并剔除。
        val rawName = item.optString("name", "").cleanTransmitterField()
        val description = item.optString("description", "").cleanTransmitterField()
        val name = when {
            rawName != null -> rawName
            description != null -> description
            else -> {
                val freqHz = downlinkHz ?: uplinkHz
                if (freqHz != null) {
                    val freqMhz = String.format("%.3f MHz", freqHz / 1_000_000.0)
                    if (mode.isNotBlank()) "$mode $freqMhz" else freqMhz
                } else {
                    mode.takeIf { it.isNotBlank() } ?: ""
                }
            }
        }

        // 状态：active / inactive / future / unknown（缺省按 unknown）
        val status = item.optString("status", "").trim().lowercase()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: RadioInfo.STATUS_UNKNOWN

        return RadioInfo(
            noradCatId = noradCatId,
            name = name,
            uplinkHz = uplinkHz,
            downlinkHz = downlinkHz,
            mode = mode.trim(),
            inverted = item.optBoolean("inverted", false),
            status = status,
            description = description.orEmpty(),
        )
    }

    companion object {
        private const val TAG = "TransmitterDataSource"

        /**
         * 自建 CDN 上的 SatNOGS 转发器快照（含全部状态，状态区分在 UI 层）。
         *
         * 地址写死为 CDN：国内可直连、无 DNS 污染、由 CDN 缓存；内容由
         * `website/deploy/hamkit-tle-sync.sh` 定时同步并裁剪，字段与 SatNOGS
         * 官方 `transmitters` 接口一致，解析逻辑无需区分来源。
         */
        const val TRANSMITTERS_URL =
            "https://tle.hamkit.click/tle/transmitters.json"
    }
}

/**
 * 清理单条转发器字段值：
 * - 空串/空白/字面量 "null"（大小写不敏感，JSON null 经 optString 得到）视为缺失，返回 null
 * - 剔除中括号 []（如 "[Beacon]" → "Beacon"）
 */
private fun String.cleanTransmitterField(): String? =
    trim()
        .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        ?.filterNot { it == '[' || it == ']' }
        ?.trim()
        ?.ifEmpty { null }
