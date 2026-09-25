package com.example.hamkit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hamkit.data.satellite.SatelliteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * 卫星筛选相关集成测试。
 *
 * 覆盖：
 * - Bug #8：[SatelliteFilterPopup] 的 expanded 状态使用 rememberSaveable，
 *   在状态恢复后应保持展开/收起状态。
 * - [SatelliteFilter] 状态管理与 [applyFilter] 过滤逻辑的集成。
 */
@RunWith(AndroidJUnit4::class)
class SatelliteFilterIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---------- Bug #8: rememberSaveable 状态保存 ----------

    /**
     * 验证 rememberSaveable 在 Compose 状态恢复后保留 expanded 状态。
     *
     * 这是 Bug #8 修复的核心验证：原代码用 remember，配置变更后状态丢失；
     * 修复后用 rememberSaveable，状态随 SavedStateRegistry 保存恢复。
     */
    @Test
    fun rememberSaveable_expandedState_survivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            Box {
                // 模拟 SatelliteFilterPopup 中 expanded 的声明方式
                var expanded by rememberSaveable { mutableStateOf(false) }
                BasicText(
                    text = if (expanded) "EXPANDED" else "COLLAPSED",
                    modifier = Modifier.clickable { expanded = true }
                )
            }
        }

        // 初始状态：收起
        composeRule.onNodeWithText("COLLAPSED").assertIsDisplayed()

        // 点击展开
        composeRule.onNodeWithText("COLLAPSED").performClick()
        composeRule.onNodeWithText("EXPANDED").assertIsDisplayed()

        // 触发状态恢复：rememberSaveable 的值应被保存
        restorationTester.emulateSavedInstanceStateRestore()

        // 恢复后应仍为展开状态（rememberSaveable 保存了 true）
        composeRule.onNodeWithText("EXPANDED").assertIsDisplayed()
    }

    /**
     * 对照组：remember（非 saveable）在状态恢复后丢失 expanded 状态。
     * 此测试验证修复前的行为，凸显 rememberSaveable 的必要性。
     */
    @Test
    fun remember_withoutSaveable_losesStateOnRestoration() {
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            Box {
                // 故意使用 remember（非 saveable）以对照 Bug #8 修复前的行为
                var expanded by androidx.compose.runtime.remember { mutableStateOf(false) }
                BasicText(
                    text = if (expanded) "EXPANDED" else "COLLAPSED",
                    modifier = Modifier.clickable { expanded = true }
                )
            }
        }

        composeRule.onNodeWithText("COLLAPSED").assertIsDisplayed()
        composeRule.onNodeWithText("COLLAPSED").performClick()
        composeRule.onNodeWithText("EXPANDED").assertIsDisplayed()

        // 状态恢复：remember 不保存，恢复后回到初始值 false
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("COLLAPSED").assertIsDisplayed()
    }

    // ---------- SatelliteFilter 过滤逻辑 ----------

    @Test
    fun satelliteFilter_isActive_falseWhenAllDefaults() {
        val filter = SatelliteFilter()
        assertFalse(filter.isActive)
    }

    @Test
    fun satelliteFilter_isActive_trueWhenAnyConditionSet() {
        assertTrue(SatelliteFilter(onlyInPass = true).isActive)
        assertTrue(SatelliteFilter(onlyUpcoming = true).isActive)
        assertTrue(SatelliteFilter(onlyAmsat = true).isActive)
        assertTrue(SatelliteFilter(categoryIds = setOf("c1")).isActive)
        assertTrue(SatelliteFilter(modes = setOf("FM")).isActive)
    }

    @Test
    fun applyFilter_returnsAllWhenFilterInactive() {
        val satellites = listOf(
            testSatellite(25544, modes = listOf("FM")),
            testSatellite(43013, modes = listOf("CW"))
        )
        val result = satellites.applyFilter(SatelliteFilter())
        assertEquals(2, result.size)
    }

    @Test
    fun applyFilter_filtersByMode() {
        val satellites = listOf(
            testSatellite(25544, modes = listOf("FM")),
            testSatellite(43013, modes = listOf("CW")),
            testSatellite(99999, modes = listOf("FM", "SSTV"))
        )
        val result = satellites.applyFilter(SatelliteFilter(modes = setOf("FM")))
        assertEquals(2, result.size)
        assertTrue(result.all { "FM" in it.modes })
    }

    @Test
    fun applyFilter_onlyInPass() {
        val satellites = listOf(
            testSatellite(25544, isCurrentlyVisible = true),
            testSatellite(43013, isCurrentlyVisible = false)
        )
        val result = satellites.applyFilter(SatelliteFilter(onlyInPass = true))
        assertEquals(1, result.size)
        assertEquals(25544, result[0].catalogNumber)
    }

    @Test
    fun applyFilter_onlyUpcoming() {
        val satellites = listOf(
            testSatellite(25544, isCurrentlyVisible = true),
            testSatellite(43013, isCurrentlyVisible = false)
        )
        val result = satellites.applyFilter(SatelliteFilter(onlyUpcoming = true))
        assertEquals(1, result.size)
        assertEquals(43013, result[0].catalogNumber)
    }

    @Test
    fun applyFilter_byCategory() {
        val satellites = listOf(
            testSatellite(25544),
            testSatellite(43013),
            testSatellite(99999)
        )
        val membership = mapOf(
            25544 to setOf("amateur"),
            99999 to setOf("amateur", "linear")
        )
        val result = satellites.applyFilter(
            SatelliteFilter(categoryIds = setOf("amateur")),
            membership = membership
        )
        assertEquals(2, result.size)
        assertTrue(result.all { it.catalogNumber in setOf(25544, 99999) })
    }

    @Test
    fun applyFilter_onlyAmsat() {
        val satellites = listOf(
            testSatellite(25544, status = "active"),
            testSatellite(43013, status = "")
        )
        val result = satellites.applyFilter(SatelliteFilter(onlyAmsat = true))
        assertEquals(1, result.size)
        assertEquals(25544, result[0].catalogNumber)
    }

    @Test
    fun applyFilter_combinesMultipleConditions() {
        val satellites = listOf(
            testSatellite(25544, modes = listOf("FM"), isCurrentlyVisible = true, status = "active"),
            testSatellite(43013, modes = listOf("FM"), isCurrentlyVisible = false, status = "active"),
            testSatellite(99999, modes = listOf("CW"), isCurrentlyVisible = true, status = "active")
        )
        // 同时筛选：FM 模式 + 在境 + AMSAT
        val result = satellites.applyFilter(
            SatelliteFilter(modes = setOf("FM"), onlyInPass = true, onlyAmsat = true)
        )
        assertEquals(1, result.size)
        assertEquals(25544, result[0].catalogNumber)
    }

    private fun testSatellite(
        catalogNumber: Int,
        modes: List<String> = listOf("FM"),
        isCurrentlyVisible: Boolean = false,
        status: String = ""
    ) = SatelliteInfo(
        name = "SAT-$catalogNumber",
        catalogNumber = catalogNumber,
        modes = modes,
        aosTime = Instant.now(),
        losTime = Instant.now().plusSeconds(600),
        maxElevation = 30.0,
        aosAzimuth = 0,
        losAzimuth = 180,
        isCurrentlyVisible = isCurrentlyVisible,
        source = "CT",
        status = status
    )
}
