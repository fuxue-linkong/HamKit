package com.example.hamkit.data.wearable

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 手机 → 手环 的卫星过境提醒协议（v1）。
 *
 * **字段名必须与手环端 `wearable/src/common/utils/alert.js` 完全一致**，
 * 两端是独立工程、没有共享的 schema 文件，改字段时务必同步改两边。
 *
 * 时间统一用 UTC 毫秒时间戳（与 [com.example.hamkit.data.reminder.ReminderItem] 一致）。
 *
 * 序列化用 `org.json`（Android 内置）：仓库里没有 kotlinx-serialization-json，
 * 而 ReminderStore / SatelliteCacheStore 等同类代码也都用 org.json，保持一致、不引新依赖。
 */
data class WearableAlert(
    /** 提醒唯一 id：`<NORAD>-<AOS 毫秒>`，手环端用它去重与回执 */
    val id: String,
    /** 卫星名（手环上直接展示） */
    val sat: String,
    /** NORAD 编号 */
    val norad: Int,
    /** AOS 时间（UTC 毫秒） */
    val aos: Long,
    /** LOS 时间（UTC 毫秒） */
    val los: Long,
    /** 最大仰角（度） */
    val el: Int = 0,
    /** AOS 方位角（度） */
    val az: Int = 0,
    /** 工作模式，如 FM / SSTV */
    val modes: List<String> = emptyList(),
    /** 发送时刻（UTC 毫秒）：手环用它校正自己的时钟偏差 */
    val now: Long = System.currentTimeMillis(),
    /** 消息类型，固定 "pass" */
    val t: String = TYPE_PASS,
    /** 协议版本 */
    val v: Int = PROTOCOL_VERSION
) {
    /** 手工序列化，字段顺序固定，便于日志排查与协议比对 */
    fun toJson(): String {
        val modesArray = JSONArray()
        modes.forEach { modesArray.put(it) }

        return JSONObject().apply {
            put("t", t)
            put("v", v)
            put("id", id)
            put("sat", sat)
            put("norad", norad)
            put("aos", aos)
            put("los", los)
            put("el", el)
            put("az", az)
            put("modes", modesArray)
            put("now", now)
        }.toString()
    }

    companion object {
        const val TYPE_PASS = "pass"
        const val PROTOCOL_VERSION = 1

        /** 提醒 id 生成规则，两端保持一致即可（手环端只当作不透明字符串） */
        fun makeId(norad: Int, aosMillis: Long): String = "$norad-$aosMillis"
    }
}

/**
 * 手环 → 手机 的回执消息。
 *
 * 手环端目前会发两种：
 * - `hello`：握手，请求补发最近一条未过期提醒（连接建立或应用重新打开时）
 * - `ack`：`shown`（已展示）/ `dismiss`（用户上滑关闭）
 */
data class WearableInbound(
    val t: String,
    val v: Int = 1,
    val id: String? = null,
    val act: String? = null,
    val at: Long? = null
) {
    companion object {
        const val TYPE_HELLO = "hello"
        const val TYPE_ACK = "ack"

        /**
         * 从字节流解析，任何异常都退化成 null（协议不合法即忽略该消息）。
         *
         * interconnect 的报文是 UTF-8 的 JSON；但手环端 `connect.send({data: obj})`
         * 在不同 toolkit 版本下可能先把对象序列化成字符串再被框架序列化一次，
         * 从而出现 `"{\"t\":\"ack\"}"` 这种双层编码，这里解一层再试一次。
         */
        fun decodeBytes(bytes: ByteArray): WearableInbound? {
            val raw = bytes.toString(Charsets.UTF_8).trim()
            if (raw.isEmpty()) return null

            val text = unwrapJsonString(raw)

            return runCatching {
                val obj = JSONObject(text)
                val type = obj.optString("t", "")
                // 缺 t 视为无效消息：手环端不会发这种包，多半是协议错位
                if (type.isEmpty()) {
                    null
                } else {
                    WearableInbound(
                        t = type,
                        v = obj.optInt("v", 1),
                        id = obj.optStringOrNull("id"),
                        act = obj.optStringOrNull("act"),
                        at = if (obj.has("at") && !obj.isNull("at")) obj.optLong("at") else null
                    )
                }
            }.getOrNull()
        }

        /** 若整段内容是一个 JSON 字符串字面量，取出其中的原始文本；否则原样返回 */
        private fun unwrapJsonString(raw: String): String {
            if (!raw.startsWith("\"")) return raw
            return runCatching {
                (JSONTokener(raw).nextValue() as? String) ?: raw
            }.getOrDefault(raw)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key)
    }
}
