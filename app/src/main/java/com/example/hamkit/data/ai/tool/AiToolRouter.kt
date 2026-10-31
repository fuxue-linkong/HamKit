package com.example.hamkit.data.ai.tool

/**
 * 按用户意图裁剪工具集。
 *
 * 目的：减少下发给模型的工具 schema 数量与误调用；命中不到任何分类时返回空集，
 * 调用方据此下发全部工具（保守回退），不限制模型能力。
 */
object AiToolRouter {

    private data class Rule(val pattern: Regex, val tools: Set<String>)

    private val rules: List<Rule> = listOf(
        Rule(
            Regex("定位|分区|网格|maidenhead|\\bcq\\b|\\bitu\\b|坐标|经纬|我在哪", RegexOption.IGNORE_CASE),
            setOf("get_zone_info"),
        ),
        Rule(
            Regex(
                "卫星|过境|\\biss\\b|\\baos\\b|\\blos\\b|转发器|频率|amsat|tle|仰角|多普勒|可打",
                RegexOption.IGNORE_CASE,
            ),
            setOf(
                "get_satellite_passes",
                "get_satellite_live",
                "get_satellite_transmitters",
                "search_satellites",
                "get_amsat_status",
            ),
        ),
        Rule(Regex("\\bft8\\b|解码|js8|wspr", RegexOption.IGNORE_CASE), setOf("get_ft8_activity")),
        Rule(Regex("\\baprs\\b|igate|digipeater", RegexOption.IGNORE_CASE), setOf("get_aprs_stations")),
        Rule(Regex("\\bqso\\b|日志|通联|联络记录|通联记录", RegexOption.IGNORE_CASE), setOf("search_qso_log")),
        Rule(Regex("天气|气温|风力|湿度|下雨", RegexOption.IGNORE_CASE), setOf("get_weather")),
        Rule(Regex("时间|\\butc\\b|几点|星期", RegexOption.IGNORE_CASE), setOf("get_utc_time")),
        Rule(Regex("摩斯|\\bcw\\b|电码", RegexOption.IGNORE_CASE), setOf("cw_encode")),
        Rule(Regex("\\bsstv\\b|慢扫描", RegexOption.IGNORE_CASE), setOf("start_sstv_receive")),
        Rule(Regex("提醒|闹钟|通知", RegexOption.IGNORE_CASE), setOf("add_pass_reminder")),
        Rule(Regex("打开|跳转|进入|页面|界面", RegexOption.IGNORE_CASE), setOf("open_screen")),
    )

    /** 返回命中的工具名集合；空集表示未命中，调用方应下发全部工具。 */
    fun route(userText: String): Set<String> {
        val text = userText.trim()
        if (text.isEmpty()) return emptySet()
        return rules
            .filter { (pattern, _) -> pattern.containsMatchIn(text) }
            .flatMap { it.tools }
            .toSet()
    }
}
