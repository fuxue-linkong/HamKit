package com.example.hamkit.data.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SatelliteCategoryConfig] 纯函数操作单元测试。
 *
 * 重点验证「组织」与「提醒」的分离：任何分类操作都不得改动 `reminderFlags`，
 * 反之亦然。这是「收藏并入分类」重构的核心不变式。
 */
class SatelliteCategoryTest {

    @Test
    fun `addCategory appends with increasing sortOrder`() {
        var config = SatelliteCategoryConfig()
        val (first, catA) = config.addCategory("新手 FM", id = "a")
        config = first
        val (second, catB) = config.addCategory("线性转发器", id = "b")
        config = second

        assertEquals(2, config.categories.size)
        assertEquals(0, catA.sortOrder)
        assertEquals(1, catB.sortOrder)
        assertEquals("新手 FM", config.categoryById("a")?.name)
    }

    @Test
    fun `addCategory trims name`() {
        val (config, category) = SatelliteCategoryConfig().addCategory("  空白  ", id = "a")
        assertEquals("空白", category.name)
        assertEquals("空白", config.categoryById("a")?.name)
    }

    @Test
    fun `renameCategory updates name and ignores blank`() {
        val config = SatelliteCategoryConfig()
            .addCategory("旧名", id = "a").first

        val renamed = config.renameCategory("a", "  新名 ")
        assertEquals("新名", renamed.categoryById("a")?.name)

        // 空白名称应被忽略，保持原名
        assertEquals("新名", renamed.renameCategory("a", "   ").categoryById("a")?.name)
    }

    @Test
    fun `renameCategory ignores unknown id`() {
        val config = SatelliteCategoryConfig().addCategory("A", id = "a").first
        assertEquals(config, config.renameCategory("missing", "B"))
    }

    @Test
    fun `removeCategory removes it and its assignments`() {
        val config = SatelliteCategoryConfig()
            .addCategory("A", id = "a").first
            .addCategory("B", id = "b").first
            .toggleAssignment(25544, "a")
            .toggleAssignment(25544, "b")
            .toggleAssignment(43013, "b")

        val removed = config.removeCategory("a")

        assertTrue(removed.categoryById("a") == null)
        assertEquals(setOf("b"), removed.categoryIdsOf(25544))
        assertEquals(setOf("b"), removed.categoryIdsOf(43013))
    }

    @Test
    fun `removeCategory drops satellites left with no category`() {
        val config = SatelliteCategoryConfig()
            .addCategory("A", id = "a").first
            .toggleAssignment(25544, "a")

        val removed = config.removeCategory("a")
        assertTrue(removed.membership.isEmpty())
        assertTrue(removed.categorizedCatalogNumbers.isEmpty())
    }

    @Test
    fun `removeCategory refuses builtin category`() {
        val config = SatelliteCategoryConfig()
            .migrateLegacyFavorites(setOf(25544))

        val attempted = config.removeCategory(BUILTIN_STARRED_CATEGORY_ID)

        assertTrue(
            "内置分类必须保留",
            attempted.categoryById(BUILTIN_STARRED_CATEGORY_ID) != null
        )
        assertEquals(setOf(25544), attempted.categorizedCatalogNumbers)
    }

    @Test
    fun `removeCategory leaves reminderFlags untouched`() {
        val config = SatelliteCategoryConfig()
            .addCategory("A", id = "a").first
            .toggleAssignment(25544, "a")
            .toggleReminder(25544)

        val removed = config.removeCategory("a")

        assertTrue("删除分类不得影响提醒开关", 25544 in removed.reminderFlags)
    }

    @Test
    fun `toggleAssignment adds then removes`() {
        val config = SatelliteCategoryConfig().addCategory("A", id = "a").first

        val added = config.toggleAssignment(25544, "a")
        assertEquals(setOf("a"), added.categoryIdsOf(25544))

        val removed = added.toggleAssignment(25544, "a")
        assertTrue(removed.membership.isEmpty())
    }

    @Test
    fun `toggleAssignment ignores unknown category`() {
        val config = SatelliteCategoryConfig()
        assertEquals(config, config.toggleAssignment(25544, "missing"))
    }

    @Test
    fun `toggleAssignment does not touch reminderFlags`() {
        val config = SatelliteCategoryConfig().addCategory("A", id = "a").first
        val assigned = config.toggleAssignment(25544, "a")
        assertTrue("归类不得开启提醒", assigned.reminderFlags.isEmpty())
    }

    @Test
    fun `toggleReminder does not touch assignments`() {
        val config = SatelliteCategoryConfig()
            .addCategory("A", id = "a").first
            .toggleAssignment(25544, "a")

        val withReminder = config.toggleReminder(43013)

        assertEquals(setOf("a"), withReminder.categoryIdsOf(25544))
        assertEquals(setOf(43013), withReminder.reminderFlags)

        val off = withReminder.toggleReminder(43013)
        assertTrue("再次切换应关闭提醒", off.reminderFlags.isEmpty())
        assertEquals(setOf("a"), off.categoryIdsOf(25544))
    }

    @Test
    fun `clearAssignments keeps reminderFlags`() {
        val config = SatelliteCategoryConfig()
            .addCategory("A", id = "a").first
            .toggleAssignment(25544, "a")
            .toggleReminder(25544)

        val cleared = config.clearAssignments(25544)

        assertTrue(cleared.membership.isEmpty())
        assertTrue(25544 in cleared.reminderFlags)
    }

    @Test
    fun `satelliteCountOf counts members`() {
        val config = SatelliteCategoryConfig()
            .addCategory("A", id = "a").first
            .addCategory("B", id = "b").first
            .addCategory("空分类", id = "c").first
            .toggleAssignment(1, "a")
            .toggleAssignment(2, "a")
            .toggleAssignment(3, "b")

        assertEquals(2, config.satelliteCountOf("a"))
        assertEquals(1, config.satelliteCountOf("b"))
        assertEquals("未被归入任何卫星的分类应为 0", 0, config.satelliteCountOf("c"))
        // 一颗卫星可属于多个分类，应分别计数
        val shared = config.toggleAssignment(1, "b")
        assertEquals(2, shared.satelliteCountOf("a"))
        assertEquals(2, shared.satelliteCountOf("b"))
    }

    @Test
    fun `addCategory rejects blank name`() {
        val (config, category) = SatelliteCategoryConfig().addCategory("   ", id = "a")
        assertTrue("空白名称不应新增分类", config.categories.isEmpty())
        assertEquals("a", category.id)
    }

    @Test
    fun `addCategory with duplicate id does not create a second entry`() {
        val config = SatelliteCategoryConfig().addCategory("A", id = "a").first
        val (again, returned) = config.addCategory("另一个名字", id = "a")

        assertEquals("重复 id 不应新增分类", 1, again.categories.size)
        assertEquals("A", returned.name)
        assertEquals(config, again)
    }

    @Test
    fun `sortedCategories is deterministic when sortOrder ties`() {
        val config = SatelliteCategoryConfig(
            categories = listOf(
                SatelliteCategory("x", "X", sortOrder = 5),
                SatelliteCategory("y", "Y", sortOrder = 5),
            )
        )
        assertEquals(listOf("x", "y"), config.sortedCategories().map { it.id })
    }

    @Test
    fun `sortedCategories orders by sortOrder`() {
        val config = SatelliteCategoryConfig(
            categories = listOf(
                SatelliteCategory("c", "C", sortOrder = 2),
                SatelliteCategory("a", "A", sortOrder = 0),
                SatelliteCategory("b", "B", sortOrder = 1),
            )
        )
        assertEquals(listOf("a", "b", "c"), config.sortedCategories().map { it.id })
    }

    // ── 历史收藏迁移 ──

    @Test
    fun `migrateLegacyFavorites creates builtin category with assignments and reminders`() {
        val migrated = SatelliteCategoryConfig()
            .migrateLegacyFavorites(setOf(25544, 43013))

        val starred = migrated.categoryById(BUILTIN_STARRED_CATEGORY_ID)
        assertEquals(BUILTIN_STARRED_CATEGORY_NAME, starred?.name)
        assertTrue(starred?.isBuiltin == true)
        assertEquals(setOf(25544, 43013), migrated.categorizedCatalogNumbers)
        assertEquals(
            "历史收藏必须保持提醒开启（行为不回退）",
            setOf(25544, 43013),
            migrated.reminderFlags
        )
    }

    @Test
    fun `migrateLegacyFavorites is a no-op when there is nothing to migrate`() {
        val config = SatelliteCategoryConfig().addCategory("已有", id = "a").first
        assertEquals(config, config.migrateLegacyFavorites(emptySet()))
        assertTrue(
            "全新安装不应凭空创建内置分类",
            config.migrateLegacyFavorites(emptySet()).categoryById(BUILTIN_STARRED_CATEGORY_ID) == null
        )
    }

    @Test
    fun `migrateLegacyFavorites reuses existing builtin category and merges`() {
        val once = SatelliteCategoryConfig().migrateLegacyFavorites(setOf(25544))
        val twice = once.migrateLegacyFavorites(setOf(43013))

        assertEquals(
            "不应重复创建内置分类",
            1,
            twice.categories.count { it.id == BUILTIN_STARRED_CATEGORY_ID }
        )
        assertEquals(setOf(25544, 43013), twice.categorizedCatalogNumbers)
        assertEquals(setOf(25544, 43013), twice.reminderFlags)
    }

    @Test
    fun `migrateLegacyFavorites preserves pre-existing categories and assignments`() {
        val existing = SatelliteCategoryConfig()
            .addCategory("用户分类", id = "u1").first
            .toggleAssignment(99999, "u1")

        val migrated = existing.migrateLegacyFavorites(setOf(25544))

        assertEquals(setOf("u1"), migrated.categoryIdsOf(99999))
        assertEquals(setOf(BUILTIN_STARRED_CATEGORY_ID), migrated.categoryIdsOf(25544))
    }

    @Test
    fun `builtin category is placed first`() {
        val existing = SatelliteCategoryConfig().addCategory("用户分类", id = "u1").first
        val migrated = existing.migrateLegacyFavorites(setOf(25544))
        assertEquals(BUILTIN_STARRED_CATEGORY_ID, migrated.sortedCategories().first().id)
    }

    @Test
    fun `isBuiltin is derived from id prefix`() {
        assertTrue(SatelliteCategory(BUILTIN_STARRED_CATEGORY_ID, "我的关注").isBuiltin)
        assertFalse(SatelliteCategory("2f1c-uuid", "普通分类").isBuiltin)
    }
}
