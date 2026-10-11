package com.example.hamkit.data.sstv

import com.example.hamkit.data.satellite.SatelliteCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二期 T8：卫星 ↔ SSTV 联动规则的单元测试。
 *
 * 这组规则决定「过境提醒要不要给 SSTV 入口」与「预置哪个模式」。判错的代价是用户在
 * 几分钟的过境窗口里多点几次，或者解出一张**模式错误但看着合理**的图，因此两个判定
 * 分别覆盖，并额外守护「预置模式名必须真的存在于模式表」—— 枚举一旦改名，字符串形式的
 * 预置会静默失效，而这类 bug 只在真机上点通知时才暴露。
 */
class SstvSatelliteLinkTest {

    @Test
    fun `转发器标注 SSTV 时判定为支持`() {
        assertTrue(SstvSatelliteLink.isSstvSatellite(listOf("FM", "SSTV")))
    }

    @Test
    fun `模式标签大小写不敏感`() {
        assertTrue(SstvSatelliteLink.isSstvSatellite(listOf("fm", "sstv")))
        assertTrue(SstvSatelliteLink.isSstvSatellite(listOf("Sstv")))
    }

    @Test
    fun `模式标签可以带后缀`() {
        // 转发器数据里常见 "SSTV/PD120"、"SSTV-PD120" 这类复合标注
        assertTrue(SstvSatelliteLink.isSstvSatellite(listOf("SSTV/PD120")))
        assertTrue(SstvSatelliteLink.isSstvSatellite(listOf("SSTV-PD120")))
    }

    @Test
    fun `modes 缺失时回退到静态目录表`() {
        // ISS 在目录表里明确标了 SSTV，提醒项却可能拿不到 modes
        assertTrue(SstvSatelliteLink.isSstvSatellite(emptyList(), SstvSatelliteLink.ISS_NORAD))
    }

    @Test
    fun `两处都没有 SSTV 信息时判定为不支持`() {
        // AO-91 只有 FM
        assertFalse(SstvSatelliteLink.isSstvSatellite(listOf("FM"), 43017))
        // 目录表未收录的卫星
        assertFalse(SstvSatelliteLink.isSstvSatellite(emptyList(), 99999))
        // 连 NORAD 编号都没有
        assertFalse(SstvSatelliteLink.isSstvSatellite(emptyList(), null))
    }

    @Test
    fun `ISS 预置 PD-120`() {
        assertEquals("PD_120", SstvSatelliteLink.presetModeNameFor(SstvSatelliteLink.ISS_NORAD))
        assertEquals(SstvMode.PD_120, SstvSatelliteLink.presetModeFor(SstvSatelliteLink.ISS_NORAD))
    }

    @Test
    fun `非 ISS 的 SSTV 卫星不预置模式`() {
        // LilacSat-2 偶发使用 SSTV 且模式不固定，猜错会解出一张「看着合理」的错图，
        // 因此宁可交给 VIS 自动识别
        val lilacSat2 = 40908
        assertTrue(SstvSatelliteLink.isSstvSatellite(emptyList(), lilacSat2))
        assertNull(SstvSatelliteLink.presetModeNameFor(lilacSat2))
        assertNull(SstvSatelliteLink.presetModeFor(lilacSat2))
    }

    @Test
    fun `预置模式名必须真的存在于模式表`() {
        val mode = SstvMode.entries.firstOrNull { it.name == SstvSatelliteLink.ISS_PRESET_MODE_NAME }
        assertNotNull(
            "ISS 预置模式名 ${SstvSatelliteLink.ISS_PRESET_MODE_NAME} 不在 SstvMode 中",
            mode,
        )
        assertTrue("预置模式必须可解码", mode!!.decodable)
    }

    @Test
    fun `目录表里所有标了 SSTV 的卫星都能被判定出来`() {
        val sstvInCatalog = SatelliteCatalog.MODES_BY_CATALOG_NUMBER
            .filterValues { modes -> modes.any { it.contains("SSTV", ignoreCase = true) } }
            .keys
        assertTrue("目录表应至少收录 ISS", sstvInCatalog.contains(SstvSatelliteLink.ISS_NORAD))
        for (norad in sstvInCatalog) {
            assertTrue("NORAD $norad 应从目录表回退判定为 SSTV 卫星", SstvSatelliteLink.isSstvSatellite(emptyList(), norad))
        }
    }
}
