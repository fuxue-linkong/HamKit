package com.example.hamkit.ai

/**
 * 按用户意图裁剪下发模型的工具集（对齐 Android `AiToolRouter`）。
 *
 * 目的：减少 schema 数量与误调用。命中不到任何分类时返回空集，调用方据此下发
 * **全部**工具（保守回退），不会限制模型能力。
 *
 * 为什么不用正则：内核要同时跑在 Kotlin/JVM 与 Kotlin/Native 上，正则在两端的
 * 边界语义（`\b` 对 CJK 的处理）不完全一致；这里用「关键词 + ASCII 词边界」的
 * 显式实现，行为可单测、两端一致。
 */
object AiToolRouter {

    private class Rule(val keywords: List<String>, val tools: Set<String>)

    private val rules: List<Rule> = listOf(
        Rule(
            listOf("定位", "分区", "网格", "maidenhead", "cq", "itu", "坐标", "经纬", "我在哪"),
            setOf("get_zone_info"),
        ),
        Rule(
            listOf(
                "卫星", "过境", "iss", "aos", "los", "转发器", "频率", "amsat", "tle",
                "仰角", "多普勒", "可打",
            ),
            setOf(
                "get_satellite_passes",
                "get_satellite_live",
                "get_satellite_transmitters",
                "search_satellites",
                "get_amsat_status",
            ),
        ),
        Rule(listOf("ft8", "解码", "js8", "wspr"), setOf("get_ft8_activity")),
        Rule(listOf("aprs", "igate", "digipeater"), setOf("get_aprs_stations")),
        Rule(listOf("qso", "日志", "通联", "联络记录", "通联记录"), setOf("search_qso_log")),
        Rule(listOf("天气", "气温", "风力", "湿度", "下雨"), setOf("get_weather")),
        Rule(listOf("时间", "utc", "几点", "星期"), setOf("get_utc_time")),
        Rule(listOf("摩斯", "cw", "电码"), setOf("cw_encode")),
        Rule(listOf("sstv", "慢扫描"), setOf("start_sstv_receive")),
        Rule(listOf("提醒", "闹钟", "通知"), setOf("add_pass_reminder")),
        Rule(listOf("打开", "跳转", "进入", "页面", "界面"), setOf("open_screen")),
    )

    /** 返回命中的工具名集合；空集表示未命中，调用方应下发全部工具。 */
    fun route(userText: String): Set<String> {
        val text = userText.trim()
        if (text.isEmpty()) return emptySet()
        val hit = LinkedHashSet<String>()
        rules.forEach { rule ->
            if (rule.keywords.any { containsKeyword(text, it) }) hit += rule.tools
        }
        return hit
    }

    /** ASCII 字母/数字（用于词边界判断；CJK 不参与，避免中文关键词被误判）。 */
    private fun isAsciiWordChar(char: Char): Boolean =
        char.code < 128 && (char.isLetterOrDigit() || char == '_')

    /**
     * 关键词匹配：
     * - 纯 ASCII 关键词按「词边界」匹配，避免 `iss` 命中 `mississippi`、`cw` 命中 `kcwxx`；
     * - 含 CJK 的关键词按子串匹配（中文没有词边界）。
     */
    private fun containsKeyword(text: String, keyword: String): Boolean {
        val haystack = text.lowercase()
        val needle = keyword.lowercase()
        if (needle.isEmpty()) return false
        var from = 0
        while (from <= haystack.length - needle.length) {
            val index = haystack.indexOf(needle, from)
            if (index < 0) return false
            val before = if (index > 0) haystack[index - 1] else ' '
            val afterIndex = index + needle.length
            val after = if (afterIndex < haystack.length) haystack[afterIndex] else ' '
            val boundaryOk = !isAsciiWordChar(before) && !isAsciiWordChar(after)
            if (boundaryOk) return true
            from = index + 1
        }
        return false
    }
}
