package com.example.hamkit.ui.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.hamkit.HamKitApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MainActivityViewModel] 单元测试。
 *
 * 通过 [Config.application] 让 Robolectric 创建并初始化 [HamKitApplication]，
 * 避免手动 new 导致 attachBaseContext 缺失。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HamKitApplication::class)
class MainActivityViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: MainActivityViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Robolectric 已创建并初始化 HamKitApplication，radioApp 全局变量已就绪
        viewModel = MainActivityViewModel(SavedStateHandle())
        // @Before 不是 runTest 块，通过 dispatcher.scheduler 显式推进
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState has valid app settings`() = runTest {
        val state = viewModel.uiState.first()
        assertNotNull(state.appSettings)
        assertEquals(1.0f, state.pageScale, 0.001f)
    }

    @Test
    fun `selectedMainPage starts at 0`() = runTest {
        val page = viewModel.selectedMainPage.first()
        assertEquals(0, page)
    }

    @Test
    fun `setSelectedMainPage updates selected page`() = runTest {
        viewModel.setSelectedMainPage(1)
        advanceUntilIdle()

        val page = viewModel.selectedMainPage.first()
        assertEquals(1, page)
    }

    @Test
    fun `setSelectedMainPage clamps to valid range`() = runTest {
        viewModel.setSelectedMainPage(5)
        advanceUntilIdle()

        val page = viewModel.selectedMainPage.first()
        assertEquals(2, page) // Clamped to LAST_PAGE_INDEX（新增 AI 页后为 2）

        viewModel.setSelectedMainPage(-1)
        advanceUntilIdle()

        val page2 = viewModel.selectedMainPage.first()
        assertEquals(0, page2) // Clamped to 0
    }

    @Test
    fun `legacy saved page 1 migrates to settings page 2`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("main_page_ai_migrated", false)
            .commit()

        val handle = SavedStateHandle(mapOf("selected_main_page" to 1))
        val migrated = MainActivityViewModel(handle)
        advanceUntilIdle()

        // 旧版本的 1 表示"设置"；新增 AI 页后设置页是 2
        assertEquals(2, migrated.selectedMainPage.first())
        assertEquals(2, handle.get<Int>("selected_main_page"))
    }

    @Test
    fun `migration runs only once`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("main_page_ai_migrated", false)
            .commit()

        val first = MainActivityViewModel(SavedStateHandle(mapOf("selected_main_page" to 1)))
        advanceUntilIdle()
        assertEquals(2, first.selectedMainPage.first())

        // 迁移标志已写入：再传 1 时应直接按新索引处理（1 = AI 页）
        val second = MainActivityViewModel(SavedStateHandle(mapOf("selected_main_page" to 1)))
        advanceUntilIdle()
        assertEquals(1, second.selectedMainPage.first())
    }

    @Test
    fun `uiState reflects preference changes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("page_scale", 1.5f)
            .commit()
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(1.5f, state.pageScale, 0.001f)
    }
}
