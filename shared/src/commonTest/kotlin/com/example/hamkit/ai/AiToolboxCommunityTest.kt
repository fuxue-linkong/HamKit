package com.example.hamkit.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 社区类工具（FT8 / APRS / QSO）与卫星标签检索的工具层单测。
 *
 * 工具层是「内核逻辑 + 平台门面」的接缝，字段名与截断语义都要锁住：
 * iOS 侧拿到的就是这里的 JSON。
 */
class AiToolboxCommunityTest {

    private class FakeFacade(
        private val satellites: List<AiSatelliteInfo> = emptyList(),
        private val ft8: AiFt8Snapshot? = null,
        private val aprs: AiAprsSnapshot? = null,
        private val qso: List<AiQsoSummary> = emptyList(),
        private val now: Long = 1_800_000_000_000L,
        private val here: AiLocationInfo? = AiLocationInfo(39.9, 116.4, 50.0),
    ) : AiAppFacade {

        override fun satellites(): List<AiSatelliteInfo> = satellites

        override fun radios(norad: Int): List<AiRadioInfo> =
            listOf(AiRadioInfo("UP", 145_000_000, 435_000_000, "FM", "active"))

        override fun amsatStatus(norad: Int): String? = null

        override fun location(): AiLocationInfo? = here

        override fun tle(norad: Int): AiTleInfo? = null

        override fun nowMs(): Long = now

        override fun performAction(action: String, norad: Int, screen: String): String = "ok"

        override fun ft8Snapshot(): AiFt8Snapshot? = ft8

        override fun aprsSnapshot(): AiAprsSnapshot? = aprs

        override fun qsoRecords(): List<AiQsoSummary> = qso
    }

    private fun satellite(
        norad: Int,
        name: String,
        modes: String = "",
        visible: Boolean = false,
        aosMs: Long = 0,
    ) = AiSatelliteInfo(
        norad = norad,
        name = name,
        modesCsv = modes,
        visible = visible,
        hasPass = aosMs > 0,
        aosMs = aosMs,
        losMs = if (aosMs > 0) aosMs + 600_000 else 0,
        maxElevationDeg = 30.0,
        aosAzimuthDeg = 10,
        losAzimuthDeg = 20,
        isGeo = false,
        status = "",
    )

    private fun run(box: AiToolbox, name: String, args: String): AiToolResult {
        var captured: AiToolResult? = null
        box.execute(AiToolCall(id = "c1", name = name, argsJson = args)) { captured = it }
        return captured ?: error("工具没有回调结果：$name")
    }

    // ── search_satellites ──────────────────────────────────────────────────

    @Test
    fun `search by tags reports truncation and total`() {
        val facade = FakeFacade(
            satellites = (1..5).map { satellite(25544 + it, "SAT-$it", modes = "FM") },
        )
        val box = AiToolbox(facade)
        val result = run(box, "search_satellites", """{"tags":["fm"],"max":2}""")

        assertFalse(result.isError)
        assertTrue(result.modelText.contains("\"total\":5"), result.modelText)
        assertTrue(result.modelText.contains("\"returned\":2"), result.modelText)
        assertTrue(result.modelText.contains("\"truncated\":true"), result.modelText)
        assertTrue(result.summary.contains("已截断"), result.summary)
    }

    @Test
    fun `search rejects unknown tag instead of ignoring it`() {
        val box = AiToolbox(FakeFacade(satellites = listOf(satellite(25544, "ISS"))))
        val result = run(box, "search_satellites", """{"tags":["bogus"]}""")

        assertTrue(result.isError)
        assertTrue(result.modelText.contains("未知标签"), result.modelText)
    }

    @Test
    fun `search daylight without location fails clearly`() {
        val box = AiToolbox(
            FakeFacade(
                satellites = listOf(satellite(25544, "ISS", aosMs = 1_800_000_600_000L)),
                here = null,
            ),
        )
        val result = run(box, "search_satellites", """{"tags":["daylight"]}""")

        assertTrue(result.isError)
        assertTrue(result.modelText.contains("定位"), result.modelText)
    }

    // ── get_ft8_activity ───────────────────────────────────────────────────

    @Test
    fun `ft8 activity filters by callsign and sorts by time`() {
        val snapshot = AiFt8Snapshot(
            decoding = false,
            decoded = listOf(
                AiFt8Decode(1000, "CQ BH4XYZ OM44", -12.0, 1200.0),
                AiFt8Decode(2000, "JA1ABC BH4XYZ -05", -8.0, 900.0),
                AiFt8Decode(3000, "CQ K1ABC FN42", -3.0, 700.0),
            ),
            qso = listOf(
                AiQsoSummary("JA1ABC", "PM95", "20m", "FT8", 2000, true),
                AiQsoSummary("K1ABC", "FN42", "20m", "FT8", 3000, false),
            ),
        )
        val box = AiToolbox(FakeFacade(ft8 = snapshot))
        val result = run(box, "get_ft8_activity", """{"callsign":"BH4XYZ"}""")

        assertFalse(result.isError)
        // 两条解码都含 BH4XYZ，按时间倒序
        val firstIndex = result.modelText.indexOf("JA1ABC BH4XYZ")
        val secondIndex = result.modelText.indexOf("CQ BH4XYZ OM44")
        assertTrue(firstIndex in 0 until secondIndex, result.modelText)
        assertTrue(result.modelText.contains("\"decoding\":false"))
        // QSO 列表按呼号过滤后为空（两条 QSO 都不含 BH4XYZ）
        assertTrue(result.modelText.contains("\"qso\":[]"), result.modelText)
    }

    @Test
    fun `ft8 tool reports unavailable module`() {
        val box = AiToolbox(FakeFacade(ft8 = null))
        val result = run(box, "get_ft8_activity", "{}")
        assertTrue(result.isError)
        assertTrue(result.modelText.contains("不可用"))
    }

    // ── get_aprs_stations ──────────────────────────────────────────────────

    @Test
    fun `aprs stations filter by radius and sort by distance`() {
        val snapshot = AiAprsSnapshot(
            connected = true,
            stations = listOf(
                // 纬度每 1° ≈ 111 km：2.7° 约 300 km、0.55° 约 61 km、0.1° 约 11 km
                AiAprsStationSummary("FAR", 42.6, 116.4, 1000, ""),
                AiAprsStationSummary("NEAR", 40.0, 116.4, 2000, "mobile"),
                AiAprsStationSummary("MID", 40.45, 116.4, 3000, ""),
            ),
        )
        val box = AiToolbox(FakeFacade(aprs = snapshot))
        val result = run(box, "get_aprs_stations", """{"radius_km":100}""")

        assertFalse(result.isError)
        assertTrue(result.modelText.contains("\"total\":3"), result.modelText)
        assertTrue(result.modelText.contains("\"returned\":2"), result.modelText)
        val nearIndex = result.modelText.indexOf("NEAR")
        val midIndex = result.modelText.indexOf("MID")
        assertTrue(nearIndex < midIndex, "应按距离升序：${result.modelText}")
        assertFalse(result.modelText.contains("FAR"), "超半径的台站应被过滤")
        assertTrue(result.modelText.contains("\"scope\":\"session\""))
    }

    @Test
    fun `aprs disconnected is an error and never auto-connects`() {
        val box = AiToolbox(FakeFacade(aprs = AiAprsSnapshot(connected = false, stations = emptyList())))
        val result = run(box, "get_aprs_stations", "{}")

        assertTrue(result.isError)
        assertTrue(result.modelText.contains("不会自动连接"), result.modelText)
    }

    // ── search_qso_log ─────────────────────────────────────────────────────

    @Test
    fun `qso log filters by days and truncates`() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        val records = listOf(
            AiQsoSummary("OLD", "", "20m", "FT8", now - 40 * day, true),
            AiQsoSummary("NEW1", "", "40m", "FT8", now - 1 * day, true),
            AiQsoSummary("NEW2", "", "40m", "SSB", now - 2 * day, true),
        )
        val box = AiToolbox(FakeFacade(qso = records, now = now))
        val result = run(box, "search_qso_log", """{"days":7,"max":1}""")

        assertFalse(result.isError)
        assertTrue(result.modelText.contains("\"total\":2"), result.modelText)
        assertTrue(result.modelText.contains("\"returned\":1"), result.modelText)
        assertTrue(result.modelText.contains("\"truncated\":true"), result.modelText)
        assertFalse(result.modelText.contains("OLD"))
    }

    @Test
    fun `empty qso log is reported`() {
        val box = AiToolbox(FakeFacade(qso = emptyList()))
        val result = run(box, "search_qso_log", "{}")
        assertTrue(result.isError)
        assertTrue(result.modelText.contains("为空"))
    }

    // ── 截断 ────────────────────────────────────────────────────────────────

    @Test
    fun `truncate keeps short text and marks long text`() {
        assertEquals("abc", truncateToolResult("abc", max = 10))
        val long = "x".repeat(50)
        val cut = truncateToolResult(long, max = 10)
        assertEquals(10, cut.substringBefore("\n").length)
        assertTrue(cut.endsWith("（结果已截断）"))
    }
}
