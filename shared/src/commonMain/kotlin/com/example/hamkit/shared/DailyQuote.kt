package com.example.hamkit.shared

import com.example.hamkit.shared.net.HttpTransport

/**
 * 一条每日一言（hitokoto），字段与 Android `HitokotoQuote` 一一对应。
 *
 * @param text 正文
 * @param from 来源（作品名），可能为空
 * @param error 非空表示本次拉取失败（[text] 为空）
 */
data class DailyQuoteSnapshot(
    val text: String,
    val from: String,
    val error: String?,
) {
    /** 与 Android `HitokotoQuote.toDisplayText()` 一致：有来源就追加「 —— 来源」。 */
    val displayText: String
        get() = if (from.isBlank()) text else "$text —— $from"
}

/**
 * 一言 API（https://v1.hitokoto.cn/），与 Android `HitokotoApiService` 同源同解析。
 *
 * 返回随机一条一言，失败时把原因放进 [DailyQuoteSnapshot.error]，
 * 由调用方（iOS `DailyQuoteStore`）回退到本地文案池，保证界面永远有内容。
 */
object Hitokoto {

    const val URL = "https://v1.hitokoto.cn/"

    /** 与 Android 一致：连接/读取各 10s。 */
    private const val TIMEOUT_MS = 10_000

    fun fetch(transport: HttpTransport, callback: (DailyQuoteSnapshot) -> Unit) {
        transport.get(URL, TIMEOUT_MS) { response ->
            if (!response.isSuccess) {
                callback(
                    DailyQuoteSnapshot(
                        text = "",
                        from = "",
                        error = response.error ?: "一言请求失败：HTTP ${response.status}",
                    )
                )
                return@get
            }
            val body = response.body
            if (body.isNullOrBlank()) {
                callback(DailyQuoteSnapshot("", "", "一言响应为空"))
                return@get
            }
            val parsed = try {
                MiniJson.parseObject(body)
            } catch (e: Exception) {
                callback(DailyQuoteSnapshot("", "", "一言解析失败：${e.message}"))
                return@get
            }
            // 与 Android 相同：只认 "hitokoto" / "from" 两个字段，正文为空视为失败。
            val content = (parsed["hitokoto"] as? String)?.trim().orEmpty()
            if (content.isEmpty()) {
                callback(DailyQuoteSnapshot("", "", "一言正文为空"))
                return@get
            }
            val from = (parsed["from"] as? String)?.trim().orEmpty()
            callback(DailyQuoteSnapshot(content, from, null))
        }
    }
}
