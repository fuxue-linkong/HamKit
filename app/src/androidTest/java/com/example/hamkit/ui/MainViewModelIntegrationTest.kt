package com.example.hamkit.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hamkit.HamKitApplication
import com.example.hamkit.data.satellite.BUILTIN_STARRED_CATEGORY_ID
import com.example.hamkit.data.satellite.FavoriteSatellitesStore
import com.example.hamkit.data.satellite.SatelliteCategoryStore
import com.example.hamkit.radioApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MainViewModel] 与分类 / 提醒持久化层的集成测试。
 *
 * 覆盖点：
 * 1. 分类归属与卫星级提醒开关在 ViewModel 重建后能从
 *    [SatelliteCategoryStore] 正确恢复；
 * 2. 归类操作**不会**触碰提醒（组织与提醒彻底分离）；
 * 3. 历史「收藏」数据一次性迁移进内置分类，且提醒行为不回退。
 *
 * 注意：本测试不触发网络/定位请求，仅验证状态恢复路径。
 */
@RunWith(AndroidJUnit4::class)
class MainViewModelIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun clearPrefs() {
        // MainViewModel 现通过全局 radioApp 访问上下文，需先初始化
        radioApp = context as HamKitApplication
        listOf(
            "radio_area_settings",
            "radio_area_favorites",
            "radio_area_satellite_categories",
            "radio_area_reminders",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun categoryAssignment_restoredFromStore_onViewModelRecreation() {
        val first = MainViewModel()
        first.addSatelliteCategory("新手 FM")
        val categoryId = first.satelliteCategoryConfig.value.categories.first().id
        first.toggleSatelliteCategory(25544, categoryId)
        assertEquals(setOf(25544), first.satelliteCategoryConfig.value.categorizedCatalogNumbers)

        // 模拟进程重启：新建 ViewModel 应从 SatelliteCategoryStore 恢复
        val restored = MainViewModel()
        val config = restored.satelliteCategoryConfig.value
        assertTrue(restored.satelliteCategoryConfig.value.categories.any { it.id == categoryId })
        assertEquals(setOf(25544), config.categorizedCatalogNumbers)
    }

    @Test
    fun categoryToggleOff_removesAssignment() {
        val first = MainViewModel()
        first.addSatelliteCategory("测试分类")
        val categoryId = first.satelliteCategoryConfig.value.categories.first().id
        first.toggleSatelliteCategory(25544, categoryId)
        first.toggleSatelliteCategory(25544, categoryId)

        val restored = MainViewModel()
        assertTrue(
            "取消归类后应恢复为空",
            restored.satelliteCategoryConfig.value.categorizedCatalogNumbers.isEmpty()
        )
    }

    @Test
    fun reminderFlags_restoredFromStore_onViewModelRecreation() {
        val first = MainViewModel()
        first.setSatelliteReminderEnabled(25544, enabled = true)
        assertTrue(25544 in first.satelliteCategoryConfig.value.reminderFlags)

        val restored = MainViewModel()
        assertTrue(25544 in restored.satelliteCategoryConfig.value.reminderFlags)
    }

    /**
     * 核心回归断言：把卫星归入分类**不得**产生或删除任何提醒。
     */
    @Test
    fun togglingCategory_doesNotTouchReminders() {
        val vm = MainViewModel()
        vm.addSatelliteCategory("只用于组织")
        val categoryId = vm.satelliteCategoryConfig.value.categories.first().id

        vm.toggleSatelliteCategory(25544, categoryId)
        vm.toggleSatelliteCategory(43013, categoryId)

        assertTrue("归类不应创建提醒项", vm.reminderItems.value.isEmpty())
        assertTrue("归类不应开启提醒开关", vm.satelliteCategoryConfig.value.reminderFlags.isEmpty())
    }

    /**
     * 迁移回归：升级前被收藏（因而会提醒）的卫星，升级后
     * 1）进入内置「我的关注」分类；2）提醒开关仍为开启。
     */
    @Test
    fun legacyFavorites_migratedIntoBuiltinCategory_andReminderBehaviorPreserved() {
        // 模拟旧版本遗留数据
        FavoriteSatellitesStore(context).save(setOf(25544, 43013))

        val vm = MainViewModel()
        val config = vm.satelliteCategoryConfig.value

        assertTrue(
            "应创建内置「我的关注」分类",
            config.categories.any { it.id == BUILTIN_STARRED_CATEGORY_ID }
        )
        assertEquals(setOf(25544, 43013), config.categorizedCatalogNumbers)
        assertTrue(
            "历史收藏对应的卫星必须保持提醒开启（行为不回退）",
            config.reminderFlags.containsAll(setOf(25544, 43013))
        )
    }

    @Test
    fun legacyMigration_isIdempotent() {
        FavoriteSatellitesStore(context).save(setOf(25544))

        MainViewModel() // 首次迁移
        val second = MainViewModel()

        val starred = second.satelliteCategoryConfig.value.categories
            .filter { it.id == BUILTIN_STARRED_CATEGORY_ID }
        assertEquals("迁移必须幂等，不应重复创建内置分类", 1, starred.size)
        assertEquals(setOf(25544), second.satelliteCategoryConfig.value.categorizedCatalogNumbers)
    }

    @Test
    fun migration_doesNotRunTwice_andKeepsUserEdits() {
        FavoriteSatellitesStore(context).save(setOf(25544))
        MainViewModel() // 首次迁移并写入标记

        // 用户手动取消归类
        val vm = MainViewModel()
        val categoryId = vm.satelliteCategoryConfig.value.categories
            .first { it.id == BUILTIN_STARRED_CATEGORY_ID }.id
        vm.toggleSatelliteCategory(25544, categoryId)

        // 再次重建不应重新迁回
        val again = MainViewModel()
        assertFalse(
            "迁移标记生效后不应再次迁回已被用户取消的归属",
            25544 in again.satelliteCategoryConfig.value.categorizedCatalogNumbers
        )
    }

    @Test
    fun builtinCategory_cannotBeDeleted() {
        FavoriteSatellitesStore(context).save(setOf(25544))
        val vm = MainViewModel()

        vm.removeSatelliteCategory(BUILTIN_STARRED_CATEGORY_ID)

        assertTrue(
            "内置「我的关注」分类不可删除",
            vm.satelliteCategoryConfig.value.categories.any { it.id == BUILTIN_STARRED_CATEGORY_ID }
        )
    }
}
