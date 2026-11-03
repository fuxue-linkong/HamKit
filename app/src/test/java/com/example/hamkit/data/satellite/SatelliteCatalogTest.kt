package com.example.hamkit.data.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SatelliteCatalog] 完整性单元测试。
 *
 * 这些测试充当目录数据的“不变量”：任何新增卫星必须同时维护
 * 模式表与 AMSAT 状态名映射，避免运行时出现空指针或缺失状态。
 */
class SatelliteCatalogTest {

    @Test
    fun `modes map is not empty`() {
        assertTrue(SatelliteCatalog.MODES_BY_CATALOG_NUMBER.isNotEmpty())
    }

    @Test
    fun `every catalog number has at least one mode`() {
        SatelliteCatalog.MODES_BY_CATALOG_NUMBER.forEach { (catNum, modes) ->
            assertTrue("卫星 $catNum 的模式列表为空", modes.isNotEmpty())
        }
    }

    @Test
    fun `modes do not contain blank entries`() {
        SatelliteCatalog.MODES_BY_CATALOG_NUMBER.forEach { (catNum, modes) ->
            modes.forEach { mode ->
                assertTrue("卫星 $catNum 存在空白模式条目", mode.isNotBlank())
            }
        }
    }

    @Test
    fun `catalog numbers set matches modes map keys`() {
        assertEquals(
            SatelliteCatalog.MODES_BY_CATALOG_NUMBER.keys,
            SatelliteCatalog.catalogNumbers
        )
    }

    @Test
    fun `amsat status name map keys are subset of catalog numbers`() {
        // AMSAT 状态映射只覆盖 AMSAT API 实际支持的卫星（参见 SatelliteDataSource：
        // amsatName 为 null 时 status 回退为空串）。但任何出现在该映射中的键
        // 必须也在模式表中存在，否则会出现"有状态名却无模式"的不一致。
        val catalogNumbers = SatelliteCatalog.catalogNumbers
        SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER.keys.forEach { catNum ->
            assertTrue(
                "AMSAT 状态映射中的卫星 $catNum 未在模式表中登记",
                catalogNumbers.contains(catNum)
            )
        }
    }

    @Test
    fun `amsat status names follow naming convention`() {
        // AMSAT API 名称形如 "AO-91_[FM]" 或 "ISS_[FM]"，必须包含方括号
        SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER.forEach { (catNum, name) ->
            assertTrue("卫星 $catNum 的 AMSAT 名称 '$name' 缺少 [Mode] 后缀", name.contains('['))
            assertTrue("卫星 $catNum 的 AMSAT 名称 '$name' 缺少 ] 后缀", name.contains(']'))
            assertFalse("卫星 $catNum 的 AMSAT 名称 '$name' 不能为空", name.isBlank())
        }
    }

    @Test
    fun `iss catalog number is present`() {
        // ISS 是核心展示对象，必须存在
        assertNotNull(SatelliteCatalog.MODES_BY_CATALOG_NUMBER[25544])
        assertNotNull(SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[25544])
        assertEquals("ISS_[FM]", SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[25544])
    }

    @Test
    fun `no duplicate catalog numbers across maps`() {
        // Map 自动去重，这里通过比较 size 与 distinct size 验证键的唯一性
        val modesKeys = SatelliteCatalog.MODES_BY_CATALOG_NUMBER.keys
        val amsatKeys = SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER.keys
        assertEquals(modesKeys.size, modesKeys.distinct().size)
        assertEquals(amsatKeys.size, amsatKeys.distinct().size)
    }

    @Test
    fun `effectiveModes merges active radios with catalog and ignores inactive`() {
        val radios = listOf(
            RadioInfo(noradCatId = 25544, name = "FM", mode = "FM", status = RadioInfo.STATUS_ACTIVE),
            RadioInfo(noradCatId = 25544, name = "SSTV", mode = "SSTV", status = RadioInfo.STATUS_ACTIVE),
            RadioInfo(noradCatId = 25544, name = "USB", mode = "USB", status = RadioInfo.STATUS_INACTIVE),
            RadioInfo(noradCatId = 25544, name = "blank", mode = "", status = RadioInfo.STATUS_ACTIVE),
        )

        assertEquals(
            listOf("FM", "SSTV"),
            SatelliteCatalog.effectiveModes(25544, radios.activeModes()),
        )
    }

    @Test
    fun `effectiveModes uses active radios for satellites outside catalog`() {
        val radios = listOf(
            RadioInfo(noradCatId = 58334, name = "UHF", mode = "FM", status = RadioInfo.STATUS_ACTIVE),
        )

        assertEquals(
            listOf("FM"),
            SatelliteCatalog.effectiveModes(58334, radios.activeModes()),
        )
    }

    @Test
    fun `effectiveModes falls back to catalog when radios are empty`() {
        assertEquals(listOf("FM", "SSTV"), SatelliteCatalog.effectiveModes(25544, emptyList<String>()))
    }

    /** 与生产代码同一投影：活跃转发器的模式（内核不依赖 Android 的 RadioInfo）。 */
    private fun List<RadioInfo>.activeModes(): List<String> =
        filter { it.isActive }.map { it.mode }
}
