package com.example.hamkit.data.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * [SatelliteCategoryExchange] 导入 / 导出单元测试。
 *
 * 覆盖：导出 → 解析往返一致、非法文件被拒绝、合并策略（同 id 更新 / 新 id 追加 /
 * 归属与提醒取并集 / 丢弃悬空引用）以及幂等性。
 *
 * org.json 在 JVM 单测中需 Robolectric 提供实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SatelliteCategoryExchangeTest {

    private fun sampleConfig(): SatelliteCategoryConfig = SatelliteCategoryConfig()
        .addCategory("新手 FM", id = "c1").first
        .addCategory("线性转发器", id = "c2").first
        .toggleAssignment(25544, "c1")
        .toggleAssignment(43017, "c1")
        .toggleAssignment(7530, "c2")
        .toggleReminder(25544)

    @Test
    fun `export and parse round-trip preserves everything`() {
        val original = sampleConfig()
        val json = SatelliteCategoryExchange.export(
            config = original,
            satelliteNames = mapOf(25544 to "ISS (ZARYA)"),
            appVersion = "3.0.0",
            exportedAt = Instant.parse("2026-08-20T12:00:00Z"),
        )

        val parsed = SatelliteCategoryExchange.parse(json).getOrThrow()

        assertEquals(original.sortedCategories(), parsed.sortedCategories())
        assertEquals(original.membership, parsed.membership)
        assertEquals(original.reminderFlags, parsed.reminderFlags)
    }

    @Test
    fun `export writes schema version and readable satellite names`() {
        val json = SatelliteCategoryExchange.export(
            config = sampleConfig(),
            satelliteNames = mapOf(25544 to "ISS (ZARYA)", 99999 to "不在分类中"),
        )

        assertTrue(json.contains("\"schema\": \"${SatelliteCategoryExchange.SCHEMA}\""))
        assertTrue(json.contains("\"version\": ${SatelliteCategoryExchange.VERSION}"))
        assertTrue(json.contains("ISS (ZARYA)"))
        // 仅导出确实出现在 membership 中的卫星名称
        assertFalse(json.contains("不在分类中"))
    }

    @Test
    fun `parse rejects foreign schema`() {
        val result = SatelliteCategoryExchange.parse("""{"schema":"something.else","version":1}""")
        assertTrue(result.isFailure)
    }

    @Test
    fun `parse rejects missing schema`() {
        assertTrue(SatelliteCategoryExchange.parse("""{"version":1}""").isFailure)
    }

    @Test
    fun `parse rejects unsupported future version`() {
        val json = """{"schema":"${SatelliteCategoryExchange.SCHEMA}","version":99}"""
        assertTrue(SatelliteCategoryExchange.parse(json).isFailure)
    }

    @Test
    fun `parse rejects invalid json`() {
        assertTrue(SatelliteCategoryExchange.parse("not json at all").isFailure)
    }

    @Test
    fun `parse caps the number of categories`() {
        val entries = (0 until SatelliteCategoryExchange.MAX_CATEGORIES + 50)
            .joinToString(",") { """{"id":"c$it","name":"分类$it"}""" }
        val json = """{"schema":"${SatelliteCategoryExchange.SCHEMA}","version":1,"categories":[$entries]}"""

        val parsed = SatelliteCategoryExchange.parse(json).getOrThrow()

        assertEquals(SatelliteCategoryExchange.MAX_CATEGORIES, parsed.categories.size)
    }

    @Test
    fun `parse rejects oversized input`() {
        val huge = "x".repeat(SatelliteCategoryExchange.MAX_JSON_CHARS + 1)
        val result = SatelliteCategoryExchange.parse(huge)
        assertTrue(result.isFailure)
    }

    @Test
    fun `parse accepts empty category set`() {
        val json = """
            {"schema":"${SatelliteCategoryExchange.SCHEMA}","version":1,
             "categories":[],"membership":{},"reminderFlags":[]}
        """.trimIndent()
        val parsed = SatelliteCategoryExchange.parse(json).getOrThrow()
        assertTrue(parsed.categories.isEmpty())
        assertTrue(parsed.membership.isEmpty())
        assertTrue(parsed.reminderFlags.isEmpty())
    }

    @Test
    fun `parse skips categories with blank id`() {
        val json = """
            {"schema":"${SatelliteCategoryExchange.SCHEMA}","version":1,
             "categories":[{"id":"","name":"坏数据"},{"id":"ok","name":"好数据"}]}
        """.trimIndent()
        val parsed = SatelliteCategoryExchange.parse(json).getOrThrow()
        assertEquals(listOf("ok"), parsed.categories.map { it.id })
    }

    // ── 合并策略 ──

    @Test
    fun `merge appends new categories and counts them`() {
        val current = SatelliteCategoryConfig().addCategory("已有", id = "c1").first
        val incoming = SatelliteCategoryConfig().addCategory("新分类", id = "c2").first

        val result = SatelliteCategoryExchange.merge(current, incoming)

        assertEquals(1, result.addedCategories)
        assertEquals(0, result.updatedCategories)
        assertTrue(result.config.categoryById("c2") != null)
        assertTrue(result.hasChanges)
    }

    @Test
    fun `merge updates name for same id and preserves sortOrder`() {
        val current = SatelliteCategoryConfig().addCategory("旧名", id = "c1").first
        val incoming = SatelliteCategoryConfig(
            categories = listOf(SatelliteCategory("c1", "新名", sortOrder = 42))
        )

        val result = SatelliteCategoryExchange.merge(current, incoming)

        assertEquals("新名", result.config.categoryById("c1")?.name)
        assertEquals("导入应保留本地排序", 0, result.config.categoryById("c1")?.sortOrder)
        assertEquals(1, result.updatedCategories)
        assertEquals(0, result.addedCategories)
    }

    @Test
    fun `merge does not count unchanged category as updated`() {
        val current = SatelliteCategoryConfig().addCategory("同名", id = "c1").first
        val result = SatelliteCategoryExchange.merge(current, current)
        assertEquals(0, result.updatedCategories)
        assertEquals(0, result.addedCategories)
        assertFalse(result.hasChanges)
    }

    @Test
    fun `merge unions membership without dropping existing`() {
        val current = SatelliteCategoryConfig()
            .addCategory("A", id = "c1").first
            .addCategory("B", id = "c2").first
            .toggleAssignment(25544, "c1")
        val incoming = SatelliteCategoryConfig()
            .addCategory("A", id = "c1").first
            .addCategory("B", id = "c2").first
            .toggleAssignment(43017, "c1")

        val result = SatelliteCategoryExchange.merge(current, incoming)

        assertEquals(setOf(25544, 43017), result.config.categorizedCatalogNumbers)
        assertEquals(1, result.addedAssignments)
    }

    @Test
    fun `merge unions reminder flags`() {
        val current = SatelliteCategoryConfig().toggleReminder(25544)
        val incoming = SatelliteCategoryConfig().toggleReminder(43017)

        val result = SatelliteCategoryExchange.merge(current, incoming)

        assertEquals(setOf(25544, 43017), result.config.reminderFlags)
        assertEquals(1, result.addedReminderFlags)
    }

    @Test
    fun `merge drops assignments pointing at unknown categories`() {
        val current = SatelliteCategoryConfig().addCategory("保留", id = "c1").first
        val incoming = SatelliteCategoryConfig(
            categories = emptyList(),
            membership = mapOf(25544 to setOf("does-not-exist", "c1")),
        )

        val result = SatelliteCategoryExchange.merge(current, incoming)

        assertEquals(setOf("c1"), result.config.categoryIdsOf(25544))
        assertEquals(1, result.addedAssignments)
    }

    @Test
    fun `merge never plants a builtin category from an imported file`() {
        val incoming = SatelliteCategoryConfig(
            categories = listOf(
                SatelliteCategory(BUILTIN_STARRED_CATEGORY_ID, "我的关注", sortOrder = -1)
            ),
            membership = mapOf(25544 to setOf(BUILTIN_STARRED_CATEGORY_ID)),
        )

        val result = SatelliteCategoryExchange.merge(SatelliteCategoryConfig(), incoming)

        assertTrue(
            "导入不得在一台没有内置分类的设备上凭空种下不可删除的分类",
            result.config.categories.isEmpty()
        )
        assertTrue(result.config.membership.isEmpty())
        assertEquals(0, result.addedCategories)
        assertFalse(result.hasChanges)
    }

    @Test
    fun `merge ignores crafted builtin-like ids`() {
        val incoming = SatelliteCategoryConfig(
            categories = listOf(SatelliteCategory("builtin:evil", "恶意分类")),
            membership = mapOf(25544 to setOf("builtin:evil")),
        )

        val result = SatelliteCategoryExchange.merge(SatelliteCategoryConfig(), incoming)

        assertTrue(result.config.categories.isEmpty())
        assertTrue(result.config.membership.isEmpty())
    }

    @Test
    fun `merge does not rename a local builtin category`() {
        val local = SatelliteCategoryConfig().migrateLegacyFavorites(setOf(25544))
        val incoming = SatelliteCategoryConfig(
            categories = listOf(
                SatelliteCategory(BUILTIN_STARRED_CATEGORY_ID, "被改名的内置分类", sortOrder = -1)
            ),
            membership = mapOf(43013 to setOf(BUILTIN_STARRED_CATEGORY_ID)),
        )

        val result = SatelliteCategoryExchange.merge(local, incoming)

        assertEquals(
            "内置分类名称由本地迁移决定，导入不得覆盖",
            BUILTIN_STARRED_CATEGORY_NAME,
            result.config.categoryById(BUILTIN_STARRED_CATEGORY_ID)?.name
        )
        assertEquals(0, result.updatedCategories)
        assertEquals(setOf(25544, 43013), result.config.categorizedCatalogNumbers)
    }

    @Test
    fun `parse rejects file without categories array`() {
        val json = """{"schema":"${SatelliteCategoryExchange.SCHEMA}","version":1,"membership":{}}"""
        assertTrue(SatelliteCategoryExchange.parse(json).isFailure)
    }

    @Test
    fun `parse rejects non-integer version`() {
        val json = """{"schema":"${SatelliteCategoryExchange.SCHEMA}","version":"1","categories":[]}"""
        assertTrue(SatelliteCategoryExchange.parse(json).isFailure)
    }

    @Test
    fun `parse drops membership referencing absent categories on merge`() {
        val json = """
            {"schema":"${SatelliteCategoryExchange.SCHEMA}","version":1,
             "categories":[{"id":"kept","name":"保留"}],
             "membership":{"25544":["ghost","kept"],"43013":["ghost"]}}
        """.trimIndent()
        val incoming = SatelliteCategoryExchange.parse(json).getOrThrow()
        val result = SatelliteCategoryExchange.merge(SatelliteCategoryConfig(), incoming)

        assertEquals(setOf("kept"), result.config.categoryIdsOf(25544))
        assertTrue("全部悬空的归属应被丢弃", 43013 !in result.config.categorizedCatalogNumbers)
    }

    @Test
    fun `merge is idempotent`() {
        val current = SatelliteCategoryConfig().addCategory("A", id = "c1").first
        val incoming = sampleConfig()

        val once = SatelliteCategoryExchange.merge(current, incoming)
        val twice = SatelliteCategoryExchange.merge(once.config, incoming)

        assertEquals(once.config, twice.config)
        assertFalse("重复导入同一份文件不应再产生变化", twice.hasChanges)
    }

    @Test
    fun `merge keeps incoming category id so future imports stay stable`() {
        val incoming = SatelliteCategoryConfig().addCategory("分享来的分类", id = "shared-uuid").first
        val result = SatelliteCategoryExchange.merge(SatelliteCategoryConfig(), incoming)
        assertTrue(result.config.categoryById("shared-uuid") != null)
    }

    @Test
    fun `defaultFileName matches expected pattern`() {
        val name = SatelliteCategoryExchange.defaultFileName(Instant.parse("2026-08-20T12:00:00Z"))
        assertTrue(
            "实际文件名：$name",
            Regex("""HamKit_satellite_categories_\d{4}-\d{2}-\d{2}\.json""").matches(name)
        )
    }
}
