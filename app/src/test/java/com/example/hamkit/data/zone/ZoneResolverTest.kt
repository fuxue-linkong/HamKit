package com.example.hamkit.data.zone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZoneResolver] 单元测试。
 *
 * 覆盖：
 * - 主要人口密集区的 CQ/ITU 分区正确性。
 * - 海洋/偏远地区的兜底公式。
 * - 返回的 maidenhead 与 [MaidenheadCalculator] 一致。
 */
class ZoneResolverTest {

    @Test
    fun `beijing resolves to east asia zones`() {
        val info = ZoneResolver.resolve(39.9042, 116.4074)
        assertEquals(24, info.cqZone)
        assertEquals(44, info.ituZone)
        assertEquals("OM89ev", info.maidenhead)
    }

    @Test
    fun `tokyo resolves to japan zones`() {
        val info = ZoneResolver.resolve(35.6762, 139.6503)
        assertEquals(25, info.cqZone)
        assertEquals(45, info.ituZone)
        assertEquals("PM95tq", info.maidenhead)
    }

    @Test
    fun `new york resolves to north america zones`() {
        val info = ZoneResolver.resolve(40.7128, -74.0060)
        // P1-3 修复后美国本土按西经 85° 拆成 CQ4（中西部）/CQ5（东部）：
        // 纽约（-74°）属东部 → CQ Zone 5（CQ WAZ 权威表）。旧断言 CQ4
        // 建立在「全美一条 CQ4」的错误表上，随修复一并更正。
        assertEquals(5, info.cqZone)
        assertEquals(8, info.ituZone)
        assertEquals("FN20xr", info.maidenhead)
    }

    @Test
    fun `london resolves to western europe zones`() {
        val info = ZoneResolver.resolve(51.5074, -0.1278)
        assertEquals(14, info.cqZone)
        assertEquals(27, info.ituZone)
        assertEquals("IO91wm", info.maidenhead)
    }

    @Test
    fun `sydney resolves to australia zones`() {
        val info = ZoneResolver.resolve(-33.8688, 151.2093)
        assertEquals(30, info.cqZone)
        assertEquals(55, info.ituZone)
        assertEquals("QF56od", info.maidenhead)
    }

    @Test
    fun `antarctic ocean uses fallback formula`() {
        // 南极海域无精确覆盖，应使用基于经度的兜底公式
        val info = ZoneResolver.resolve(-60.0, -30.0)
        // fallbackCqZone(-30) = floor(150/9) + 1 = 17
        assertEquals(17, info.cqZone)
        // fallbackItuZone(-30) = floor(150/4) + 1 = 38
        assertEquals(38, info.ituZone)
        assertNotNull(info.maidenhead)
        assertTrue(info.maidenhead.length == 6)
    }

    @Test
    fun `fallback cq zone clamps to valid range`() {
        // 经度 -180 应得到 cqZone=1，经度 180 应得到 cqZone=40 但被 clamp
        val west = ZoneResolver.resolve(-85.0, -180.0)
        assertEquals(1, west.cqZone)
        val east = ZoneResolver.resolve(-85.0, 180.0)
        // fallbackCqZone(180) = floor(360/9) + 1 = 41, clamp 至 40
        assertEquals(40, east.cqZone)
    }

    @Test
    fun `fallback itu zone clamps to valid range`() {
        val east = ZoneResolver.resolve(-85.0, 180.0)
        // fallbackItuZone(180) = floor(360/4) + 1 = 91, clamp 至 90
        assertEquals(90, east.ituZone)
    }

    @Test
    fun `resolver always returns maidenhead consistent with calculator`() {
        // 抽样若干坐标点，确认 maidenhead 字段来自 MaidenheadCalculator
        val samples = listOf(
            39.9042 to 116.4074,
            -33.8688 to 151.2093,
            0.0 to 0.0,
            -60.0 to -30.0,
            55.0 to 60.0
        )
        for ((lat, lon) in samples) {
            val expected = MaidenheadCalculator.calculate(lat, lon)
            val actual = ZoneResolver.resolve(lat, lon).maidenhead
            assertEquals("mismatch at ($lat, $lon)", expected, actual)
        }
    }

    @Test
    fun `boundary longitude 40 belongs to europe not russia`() {
        val europe = ZoneResolver.resolve(55.0, 40.0)
        assertEquals(14, europe.cqZone)
        assertEquals(27, europe.ituZone)

        val russia = ZoneResolver.resolve(55.0, 41.0)
        assertEquals(16, russia.cqZone)
        assertEquals(30, russia.ituZone)
    }

    @Test
    fun `polar boundaries are still matched`() {
        val russiaPacific = ZoneResolver.resolve(75.0, 180.0)
        assertEquals(16, russiaPacific.cqZone)

        val northAmericaPolar = ZoneResolver.resolve(35.0, -180.0)
        assertEquals(5, northAmericaPolar.cqZone)
    }

    @Test
    fun `diaoyu islands resolve to china zones`() {
        // 钓鱼岛主岛、赤尾屿都应按中国归属取 CQ24/ITU44，而不是被
        // 「日本/韩国 122–146°E」大框吞成 CQ25/ITU45。
        val main = ZoneResolver.resolve(25.75, 123.47)
        assertEquals(24, main.cqZone)
        assertEquals(44, main.ituZone)

        val chiwei = ZoneResolver.resolve(25.92, 124.33)
        assertEquals(24, chiwei.cqZone)
        assertEquals(44, chiwei.ituZone)

        // 覆盖框不得外溢到邻近的日本岛屿：石垣岛仍是 CQ25/ITU45。
        val ishigaki = ZoneResolver.resolve(24.34, 124.16)
        assertEquals(25, ishigaki.cqZone)
        assertEquals(45, ishigaki.ituZone)
    }

    @Test
    fun `scarborough shoal resolves to china zones`() {
        // 黄岩岛应按三沙市归属取 CQ24/ITU44，而不是被「东南亚」大框算成 CQ26/ITU49。
        val info = ZoneResolver.resolve(15.15, 117.75)
        assertEquals(24, info.cqZone)
        assertEquals(44, info.ituZone)

        // 框外仍是原来的东南亚兜底，确认没有扩大化。
        assertEquals(26, ZoneResolver.resolve(15.15, 118.5).cqZone)
        assertEquals(26, ZoneResolver.resolve(15.15, 117.0).cqZone)
        assertEquals(26, ZoneResolver.resolve(14.6, 117.75).cqZone)
    }

    @Test
    fun `hainan and paracel islands resolve to china zones`() {
        // 海南岛南部（三亚）此前被「东南亚」大框吞成 CQ26/ITU49，应按海南省取 CQ24/ITU44。
        val sanya = ZoneResolver.resolve(18.25, 109.5)
        assertEquals(24, sanya.cqZone)
        assertEquals(44, sanya.ituZone)

        // 西沙群岛（永兴岛）同样按三沙市归属取 CQ24/ITU44。
        val woody = ZoneResolver.resolve(16.83, 112.33)
        assertEquals(24, woody.cqZone)
        assertEquals(44, woody.ituZone)

        // 框外不误伤越南海岸、雷州半岛与北部湾。
        assertEquals(26, ZoneResolver.resolve(16.05, 108.2).cqZone)
        assertEquals(24, ZoneResolver.resolve(20.3, 110.2).cqZone)
        assertEquals(24, ZoneResolver.resolve(20.04, 110.3).cqZone)
    }

    @Test
    fun `no mans land at boundaries`() {
        val boundaryLongitudes = listOf(-180.0, -170.0, -100.0, -30.0, 0.0, 40.0, 100.0, 180.0)
        val boundaryLatitudes = listOf(-85.0, -50.0, 0.0, 30.0, 55.0, 70.0, 85.0)
        for (lon in boundaryLongitudes) {
            for (lat in boundaryLatitudes) {
                val info = ZoneResolver.resolve(lat, lon)
                assertTrue("No zone at ($lat, $lon)", info.cqZone in 1..40)
                assertTrue("No ITU zone at ($lat, $lon)", info.ituZone in 1..90)
            }
        }
    }
}
