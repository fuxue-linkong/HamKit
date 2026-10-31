package com.example.hamkit.data.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 工具注册表与 schema 的静态检查（不执行真实工具，避免依赖设备状态）。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AiToolRegistryTest {

    private val registry = AiToolRegistry()

    @Test
    fun `all tools have unique snake case names`() {
        val names = registry.specs().map { it.name }
        assertEquals("工具名称重复", names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue("工具名必须是 snake_case：$name", name.matches(Regex("^[a-z][a-z0-9_]*$")))
        }
    }

    @Test
    fun `expected p1 tools are registered`() {
        val expected = setOf(
            "get_zone_info",
            "get_satellite_passes",
            "get_satellite_live",
            "get_satellite_transmitters",
            "search_satellites",
            "get_amsat_status",
            "get_ft8_activity",
            "get_aprs_stations",
            "search_qso_log",
            "get_utc_time",
            "get_weather",
            "cw_encode",
            "open_screen",
            "start_sstv_receive",
            "add_pass_reminder",
        )
        assertEquals(expected, registry.specs().map { it.name }.toSet())
    }

    @Test
    fun `every spec has object schema and description`() {
        registry.specs().forEach { spec ->
            assertTrue("${spec.name} 缺少标题", spec.title.isNotBlank())
            assertTrue("${spec.name} 缺少描述", spec.description.isNotBlank())
            assertEquals("${spec.name} schema type", "object", spec.parameters.optString("type"))
            assertTrue("${spec.name} schema properties", spec.parameters.has("properties"))
        }
    }

    @Test
    fun `disabled tools are excluded`() {
        val specs = registry.specs(setOf("get_weather", "get_utc_time"))
        assertTrue(specs.none { it.name == "get_weather" })
        assertTrue(specs.none { it.name == "get_utc_time" })
        assertEquals(13, specs.size)
    }

    @Test
    fun `find returns tool by name`() {
        assertNotNull(registry.find("get_zone_info"))
        assertEquals("get_zone_info", registry.find("get_zone_info")?.spec?.name)
        assertEquals(null, registry.find("not_a_tool"))
    }
}
