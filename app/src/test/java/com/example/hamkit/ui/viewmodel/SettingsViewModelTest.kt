package com.example.hamkit.ui.viewmodel

import com.example.hamkit.HamKitApplication
import com.example.hamkit.data.repository.SettingsRepository
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.WideNavStyle
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fake [SettingsRepository] for unit tests.
 * Stores values in memory without touching SharedPreferences.
 */
class FakeSettingsRepository : SettingsRepository {
    override var uiMode: String = UiMode.DEFAULT_VALUE
    override var checkUpdate: Boolean = true
    override var themeMode: Int = 0
    override var miuixMonet: Boolean = false
    override var keyColor: Int = 0
    override var colorStyle: String = PaletteStyle.TonalSpot.name
    override var colorSpec: String = ColorSpec.SpecVersion.Default.name
    override var enablePredictiveBack: Boolean = false
    override var enableBlur: Boolean = true
    override var enableFloatingBottomBar: Boolean = true
    override var enableFloatingBottomBarBlur: Boolean = true
    override var wideNavStyle: String = WideNavStyle.Auto.value
    override var pageScale: Float = 1.0f
    override var customBackgroundUri: String = ""
}

/**
 * [SettingsViewModel] 单元测试。
 *
 * 使用 FakeSettingsRepository 隔离 SharedPreferences 依赖。
 * 覆盖：
 * - 初始状态反映仓库默认值
 * - 各 setter 方法同步更新 StateFlow 与仓库
 * - UI 模式切换时的主题转换逻辑
 * - Monet 开关对主题模式的影响
 * - 刷新操作重新加载仓库值
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HamKitApplication::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeSettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeSettingsRepository()
        viewModel = SettingsViewModel(fakeRepo)
        // @Before 不是 runTest 块，通过 dispatcher.scheduler 显式推进
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects repository defaults`() = runTest(testDispatcher) {
        val state = viewModel.uiState.first()
        assertEquals(UiMode.DEFAULT_VALUE, state.uiMode)
        assertTrue(state.checkUpdate)
        assertEquals(0, state.themeMode)
        assertFalse(state.miuixMonet)
        assertEquals(1.0f, state.pageScale, 0.001f)
        assertTrue(state.enableBlur)
        assertTrue(state.enableFloatingBottomBar)
        assertTrue(state.enableFloatingBottomBarBlur)
    }

    @Test
    fun `setCheckUpdate updates state and repository`() = runTest(testDispatcher) {
        viewModel.setCheckUpdate(false)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertFalse(state.checkUpdate)
        assertFalse(fakeRepo.checkUpdate)
    }

    @Test
    fun `setThemeMode in material mode stores value directly`() = runTest(testDispatcher) {
        fakeRepo.uiMode = "material"
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setThemeMode(2)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(2, state.themeMode)
        assertEquals(2, fakeRepo.themeMode)
    }

    @Test
    fun `setThemeMode in miuix mode with monet adds offset`() = runTest(testDispatcher) {
        fakeRepo.uiMode = "miuix"
        fakeRepo.miuixMonet = true
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setThemeMode(2)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        // miuix + monet: effective mode = mode + 3 = 5
        assertEquals(5, state.themeMode)
        assertEquals(5, fakeRepo.themeMode)
    }

    @Test
    fun `setThemeMode in miuix mode without monet stores value directly`() = runTest(testDispatcher) {
        fakeRepo.uiMode = "miuix"
        fakeRepo.miuixMonet = false
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setThemeMode(1)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(1, state.themeMode)
        assertEquals(1, fakeRepo.themeMode)
    }

    @Test
    fun `setMiuixMonet converts non-monet to monet mode`() = runTest(testDispatcher) {
        fakeRepo.uiMode = "miuix"
        fakeRepo.miuixMonet = false
        fakeRepo.themeMode = 2 // DARK
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setMiuixMonet(true)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        // DARK (2) -> MONET_DARK (5)
        assertEquals(5, state.themeMode)
        assertTrue(state.miuixMonet)
    }

    @Test
    fun `setMiuixMonet converts monet to non-monet mode`() = runTest(testDispatcher) {
        fakeRepo.uiMode = "miuix"
        fakeRepo.miuixMonet = true
        fakeRepo.themeMode = 5 // MONET_DARK
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setMiuixMonet(false)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        // MONET_DARK (5) -> DARK (2)
        assertEquals(2, state.themeMode)
        assertFalse(state.miuixMonet)
    }

    @Test
    fun `setUiMode from material to miuix converts theme`() = runTest(testDispatcher) {
        fakeRepo.uiMode = "material"
        fakeRepo.themeMode = 2 // DARK
        fakeRepo.miuixMonet = true
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setUiMode("miuix")
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals("miuix", state.uiMode)
        // DARK (2) -> MONET_DARK (5) because miuixMonet is true
        assertEquals(5, state.themeMode)
    }

    @Test
    fun `setUiMode from miuix to material converts monet to non-monet`() = runTest(testDispatcher) {
        fakeRepo.uiMode = "miuix"
        fakeRepo.themeMode = 5 // MONET_DARK
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.setUiMode("material")
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals("material", state.uiMode)
        // MONET_DARK (5) -> DARK (2)
        assertEquals(2, state.themeMode)
    }

    @Test
    fun `setPageScale updates state and repository`() = runTest(testDispatcher) {
        viewModel.setPageScale(1.5f)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(1.5f, state.pageScale, 0.001f)
        assertEquals(1.5f, fakeRepo.pageScale, 0.001f)
    }

    @Test
    fun `setEnableBlur updates state and repository`() = runTest(testDispatcher) {
        viewModel.setEnableBlur(false)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertFalse(state.enableBlur)
        assertFalse(fakeRepo.enableBlur)
    }

    @Test
    fun `setEnableFloatingBottomBar updates state and repository`() = runTest(testDispatcher) {
        viewModel.setEnableFloatingBottomBar(false)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertFalse(state.enableFloatingBottomBar)
        assertFalse(fakeRepo.enableFloatingBottomBar)
    }

    @Test
    fun `setEnablePredictiveBack updates state and repository`() = runTest(testDispatcher) {
        viewModel.setEnablePredictiveBack(true)
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertTrue(state.enablePredictiveBack)
        assertTrue(fakeRepo.enablePredictiveBack)
    }

    @Test
    fun `clearUpdateResult resets update state`() = runTest(testDispatcher) {
        // Simulate having an update available by directly setting state
        viewModel.checkUpdateNow(force = true)
        advanceUntilIdle()

        viewModel.clearUpdateResult()
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertFalse(state.updateAvailable)
        assertEquals(0, state.latestVersionInfo.versionCode)
    }

    @Test
    fun `refresh reloads all values from repository`() = runTest(testDispatcher) {
        fakeRepo.themeMode = 2
        fakeRepo.uiMode = "material"
        fakeRepo.pageScale = 0.8f

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals(2, state.themeMode)
        assertEquals("material", state.uiMode)
        assertEquals(0.8f, state.pageScale, 0.001f)
    }
}
