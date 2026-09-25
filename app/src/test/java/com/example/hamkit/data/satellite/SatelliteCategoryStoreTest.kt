package com.example.hamkit.data.satellite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SatelliteCategoryStore] 持久化单元测试。
 *
 * 覆盖：完整配置往返、空存储、损坏数据降级（不崩溃）、提醒开关独立读写、
 * 迁移标记与清空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SatelliteCategoryStoreTest {

    private lateinit var context: Context
    private lateinit var store: SatelliteCategoryStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SatelliteCategoryStore(context)
        store.clear()
    }

    @Test
    fun `loadConfig returns empty config when nothing stored`() {
        val config = store.loadConfig()
        assertTrue(config.categories.isEmpty())
        assertTrue(config.membership.isEmpty())
        assertTrue(config.reminderFlags.isEmpty())
    }

    @Test
    fun `saveConfig and loadConfig round-trip`() {
        val config = SatelliteCategoryConfig()
            .addCategory("新手 FM", id = "c1").first
            .addCategory("线性转发器", id = "c2").first
            .toggleAssignment(25544, "c1")
            .toggleAssignment(25544, "c2")
            .toggleAssignment(43017, "c1")
            .toggleReminder(25544)

        store.saveConfig(config)
        val loaded = SatelliteCategoryStore(context).loadConfig()

        assertEquals(config.sortedCategories(), loaded.sortedCategories())
        assertEquals(config.membership, loaded.membership)
        assertEquals(config.reminderFlags, loaded.reminderFlags)
    }

    @Test
    fun `categories persist across store instances`() {
        store.saveCategories(listOf(SatelliteCategory("c1", "分类一", sortOrder = 3)))
        val loaded = SatelliteCategoryStore(context).loadCategories()
        assertEquals(listOf(SatelliteCategory("c1", "分类一", sortOrder = 3)), loaded)
    }

    @Test
    fun `membership persists independently from categories`() {
        store.saveMembership(mapOf(25544 to setOf("c1"), 43017 to setOf("c1", "c2")))
        val loaded = SatelliteCategoryStore(context).loadMembership()
        assertEquals(setOf("c1"), loaded[25544])
        assertEquals(setOf("c1", "c2"), loaded[43017])
    }

    @Test
    fun `reminder flags are written and read independently of categories`() {
        store.saveReminderFlags(setOf(25544, 43017))
        // 分类为空，提醒开关仍应保留 —— 证明二者互不依赖
        assertTrue(store.loadCategories().isEmpty())
        assertEquals(setOf(25544, 43017), SatelliteCategoryStore(context).loadReminderFlags())
    }

    @Test
    fun `saving config does not clobber unrelated prefs`() {
        store.saveConfig(
            SatelliteCategoryConfig().addCategory("A", id = "c1").first.toggleReminder(25544)
        )
        val loaded = store.loadConfig()
        assertEquals(listOf("c1"), loaded.categories.map { it.id })
        assertEquals(setOf(25544), loaded.reminderFlags)
    }

    @Test
    fun `loadConfigWithLegacyMigration migrates once and persists`() {
        val migrated = store.loadConfigWithLegacyMigration(setOf(25544, 43013))

        assertEquals(setOf(25544, 43013), migrated.categorizedCatalogNumbers)
        assertEquals(setOf(25544, 43013), migrated.reminderFlags)
        assertTrue(store.isLegacyMigrationDone())

        // 已迁移：再次调用不得重复迁移，也不应把用户后续取消的归属迁回
        val edited = migrated.toggleAssignment(25544, BUILTIN_STARRED_CATEGORY_ID)
        store.saveConfig(edited)

        val again = store.loadConfigWithLegacyMigration(setOf(25544, 43013))
        assertEquals(edited, again)
        assertTrue("已取消的归属不应被重新迁入", 25544 !in again.categorizedCatalogNumbers)
    }

    @Test
    fun `loadConfigWithLegacyMigration is a no-op without legacy data`() {
        val config = store.loadConfigWithLegacyMigration(emptySet())
        assertTrue(config.categories.isEmpty())
        assertTrue(store.isLegacyMigrationDone())
    }

    @Test
    fun `loadConfig drops membership pointing at unknown categories`() {
        store.saveCategories(listOf(SatelliteCategory("real", "真实分类")))
        store.saveMembership(mapOf(25544 to setOf("ghost"), 43013 to setOf("real", "ghost")))

        val config = store.loadConfig()

        assertTrue(
            "悬空归属必须清理，否则卫星会被算作已分类却显示不出分类",
            25544 !in config.categorizedCatalogNumbers
        )
        assertEquals(setOf("real"), config.categoryIdsOf(43013))
    }

    @Test
    fun `loadConfig keeps membership when categories are unreadable`() {
        // 分类键被写成非 String（模拟异常备份恢复 / 其他写入者）
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit().putInt("categories", 42).commit()
        store.saveMembership(mapOf(25544 to setOf("c1")))

        val config = store.loadConfig()

        assertTrue(config.categories.isEmpty())
        assertEquals(
            "分类不可读时不应连带清空归属数据",
            setOf(25544),
            config.categorizedCatalogNumbers
        )
    }

    @Test
    fun `wrong-typed preference values degrade to empty instead of crashing`() {
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit()
            .putInt("categories", 1)
            .putInt("membership", 2)
            .putInt("reminder_flags", 3)
            .putInt("legacy_favorites_migrated_v1", 4)
            .commit()

        val config = store.loadConfig()

        assertTrue(config.categories.isEmpty())
        assertTrue(config.membership.isEmpty())
        assertTrue(config.reminderFlags.isEmpty())
        assertFalse(store.isLegacyMigrationDone())
    }

    @Test
    fun `explicit json null id is skipped`() {
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit()
            .putString("categories", """[{"id":null,"name":"坏"},{"id":"good","name":"好"}]""")
            .commit()

        assertEquals(listOf("good"), store.loadCategories().map { it.id })
    }

    @Test
    fun `non-positive catalog numbers are rejected on load`() {
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit()
            .putString("membership", """{"0":["c1"],"-5":["c1"],"25544":["c1"]}""")
            .putString("reminder_flags", """[0,-5,25544]""")
            .commit()
        store.saveCategories(listOf(SatelliteCategory("c1", "分类")))

        val config = store.loadConfig()

        assertEquals(setOf(25544), config.categorizedCatalogNumbers)
        assertEquals(setOf(25544), config.reminderFlags)
    }

    @Test
    fun `legacy migration marker defaults to false and can be set`() {
        assertFalse(store.isLegacyMigrationDone())
        store.markLegacyMigrationDone()
        assertTrue(SatelliteCategoryStore(context).isLegacyMigrationDone())
    }

    @Test
    fun `clear removes everything including migration marker`() {
        store.saveConfig(
            SatelliteCategoryConfig().addCategory("A", id = "c1").first.toggleReminder(25544)
        )
        store.markLegacyMigrationDone()

        store.clear()

        val reloaded = SatelliteCategoryStore(context).loadConfig()
        assertTrue(reloaded.categories.isEmpty())
        assertTrue(reloaded.membership.isEmpty())
        assertTrue(reloaded.reminderFlags.isEmpty())
        assertFalse(store.isLegacyMigrationDone())
    }

    @Test
    fun `corrupted category json degrades to empty instead of crashing`() {
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit()
            .putString("categories", "{ this is not valid json")
            .commit()

        assertTrue(store.loadCategories().isEmpty())
    }

    @Test
    fun `corrupted membership json degrades to empty instead of crashing`() {
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit()
            .putString("membership", "[not an object]")
            .commit()

        assertTrue(store.loadMembership().isEmpty())
    }

    @Test
    fun `corrupted reminder json degrades to empty instead of crashing`() {
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit()
            .putString("reminder_flags", "{not an array}")
            .commit()

        assertTrue(store.loadReminderFlags().isEmpty())
    }

    @Test
    fun `categories with blank id are skipped on load`() {
        context.getSharedPreferences("radio_area_satellite_categories", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "categories",
                """[{"id":"","name":"坏"},{"id":"good","name":"好","sortOrder":1}]"""
            )
            .commit()

        assertEquals(listOf("good"), store.loadCategories().map { it.id })
    }
}
