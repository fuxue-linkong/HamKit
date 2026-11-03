package com.example.hamkit.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 卫星标签解析与过滤单测（对齐 Android `SatelliteTagsTest`）。
 *
 * 这些规则决定模型能否一次问对卫星，回归成本高、又无法在 iOS 上调试，
 * 所以全部锁在 JVM 单测里。
 */
class AiSatelliteTagsTest {

    private fun sat(
        norad: Int,
        name: String,
        modes: String = "",
        visible: Boolean = false,
        aosMs: Long = 0,
        status: String = "",
        isGeo: Boolean = false,
    ) = AiSatelliteInfo(
        norad = norad,
        name = name,
        modesCsv = modes,
        visible = visible,
        hasPass = aosMs > 0,
        aosMs = aosMs,
        losMs = if (aosMs > 0) aosMs + 600_000 else 0,
        maxElevationDeg = 45.0,
        aosAzimuthDeg = 10,
        losAzimuthDeg = 20,
        isGeo = isGeo,
        status = status,
    )

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Test
    fun `parse accepts lower and mixed case tags`() {
        val result = parseAiSatelliteTags(listOf("amateur", "FM"), mode = null, onlyAbove = false)
        val success = assertIs<AiTagParseResult.Success>(result)
        assertEquals(setOf(AiSatelliteTag.AMATEUR, AiSatelliteTag.FM), success.tags)
    }

    @Test
    fun `parse splits comma separated entries`() {
        val result = parseAiSatelliteTags(
            listOf("amateur,visible"),
            mode = null,
            onlyAbove = false,
        )
        val success = assertIs<AiTagParseResult.Success>(result)
        assertEquals(
            setOf(AiSatelliteTag.AMATEUR, AiSatelliteTag.VISIBLE),
            success.tags,
        )
    }

    @Test
    fun `legacy mode and only_above map to tags`() {
        val result = parseAiSatelliteTags(emptyList(), mode = "sstv", onlyAbove = true)
        val success = assertIs<AiTagParseResult.Success>(result)
        assertEquals(setOf(AiSatelliteTag.SSTV, AiSatelliteTag.VISIBLE), success.tags)
    }

    @Test
    fun `unknown tag fails with readable message`() {
        val result = parseAiSatelliteTags(listOf("amateur", "nope"), null, false)
        val failure = assertIs<AiTagParseResult.Failure>(result)
        assertTrue(failure.message.contains("未知标签"), failure.message)
        assertTrue(failure.message.contains("amateur"), "错误里应带可用标签说明")
    }

    // ── 过滤与排序 ──────────────────────────────────────────────────────────

    @Test
    fun `amateur tag keeps only catalogued amateur satellites`() {
        val all = listOf(
            sat(25544, "ISS (ZARYA)", modes = "FM", visible = true),
            sat(99999, "SOME WEATHER SAT", modes = "HRPT"),
        )
        val filtered = filterAiSatellites(
            all = all,
            query = null,
            tags = setOf(AiSatelliteTag.AMATEUR),
            nowMs = 1_000,
            location = null,
            radiosOf = { emptyList() },
        )
        assertEquals(listOf(25544), filtered.map { it.norad })
    }

    @Test
    fun `visible and upcoming are exclusive`() {
        val now = 10_000L
        val all = listOf(
            sat(1, "NOW", visible = true, aosMs = 5_000),
            sat(2, "LATER", visible = false, aosMs = 20_000),
        )
        val visible = filterAiSatellites(all, null, setOf(AiSatelliteTag.VISIBLE), now, null) {
            emptyList()
        }
        val upcoming = filterAiSatellites(all, null, setOf(AiSatelliteTag.UPCOMING), now, null) {
            emptyList()
        }
        assertEquals(listOf(1), visible.map { it.norad })
        assertEquals(listOf(2), upcoming.map { it.norad })
    }

    @Test
    fun `ssb tag also matches usb and lsb`() {
        val all = listOf(
            sat(1, "A", modes = "USB,CW"),
            sat(2, "B", modes = "LSB"),
            sat(3, "C", modes = "FM"),
        )
        val filtered = filterAiSatellites(all, null, setOf(AiSatelliteTag.SSB), 0, null) {
            emptyList()
        }
        assertEquals(listOf(1, 2), filtered.map { it.norad })
    }

    @Test
    fun `repeater tag only queries radios when needed`() {
        var calls = 0
        val all = listOf(sat(1, "A", modes = "FM"), sat(2, "B", modes = "FM"))
        val radiosOf: (Int) -> List<AiRadioInfo> = { norad ->
            calls += 1
            if (norad == 2) {
                listOf(AiRadioInfo("uplink", 145_000_000, 435_000_000, "FM", "active"))
            } else {
                listOf(AiRadioInfo("uplink", 145_000_000, 435_000_000, "FM", "inactive"))
            }
        }

        // 不带 repeater：完全不碰转发器（iOS 侧是一次跨语言调用，不能省）
        filterAiSatellites(all, null, setOf(AiSatelliteTag.FM), 0, null, radiosOf)
        assertEquals(0, calls)

        val filtered = filterAiSatellites(all, null, setOf(AiSatelliteTag.REPEATER), 0, null, radiosOf)
        assertEquals(listOf(2), filtered.map { it.norad })
        assertTrue(calls >= 2)
    }

    @Test
    fun `daylight tag needs location and uses aos sun elevation`() {
        val noonUtc = 1_779_940_800_000L // 2026-05-28T04:00:00Z = 北京当地正午
        val all = listOf(sat(1, "DAY", aosMs = noonUtc))
        val beijing = AiLocationInfo(39.9, 116.4, 50.0)
        val daylight = filterAiSatellites(
            all,
            null,
            setOf(AiSatelliteTag.DAYLIGHT),
            noonUtc,
            beijing,
        ) { emptyList() }
        assertEquals(listOf(1), daylight.map { it.norad }, "北京当地正午应判为白天")

        // 没有定位时无法判定 → 不匹配（工具层会先报错提示）
        val noLocation = filterAiSatellites(
            all,
            null,
            setOf(AiSatelliteTag.DAYLIGHT),
            noonUtc,
            null,
        ) { emptyList() }
        assertTrue(noLocation.isEmpty())
    }

    @Test
    fun `query sorts exact name before substring false positive`() {
        val all = listOf(
            sat(100, "OUTPOST MISSION2 ISS RELAY", visible = true),
            sat(25544, "ISS (ZARYA)", visible = false),
        )
        val filtered = filterAiSatellites(
            all,
            query = "ISS",
            tags = emptySet(),
            nowMs = 0,
            location = null,
            radiosOf = { emptyList() },
        )
        assertEquals(25544, filtered.first().norad)
    }

    @Test
    fun `without query amateur satellites come first`() {
        val all = listOf(
            sat(100, "PLAIN", visible = false),
            sat(27607, "SO-50", visible = false),
        )
        val filtered = filterAiSatellites(all, null, emptySet(), 0, null) { emptyList() }
        assertEquals(27607, filtered.first().norad)
    }
}
