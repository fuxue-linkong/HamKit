package com.example.hamkit.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.hamkit.data.repository.SettingsRepository
import com.example.hamkit.data.repository.SettingsRepositoryImpl
import com.example.hamkit.radioApp
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.theme.ThemeController

class MainActivityViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val prefs = radioApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val settingRepo: SettingsRepository = SettingsRepositoryImpl()
    private val mainPageState = MainPageState(savedStateHandle, prefs)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in observedKeys) {
            _uiState.value = readUiState()
        }
    }

    private val _uiState = MutableStateFlow(readUiState())
    val uiState: StateFlow<MainActivityUiState> = _uiState.asStateFlow()
    val selectedMainPage: StateFlow<Int> = mainPageState.selectedPage

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onCleared()
    }

    fun setSelectedMainPage(page: Int) {
        mainPageState.updateSelectedPage(page)
    }

    private fun readUiState(): MainActivityUiState {
        return MainActivityUiState(
            appSettings = ThemeController.getAppSettings(radioApp),
            pageScale = settingRepo.pageScale,
            enableBlur = settingRepo.enableBlur,
            enableFloatingBottomBar = settingRepo.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = settingRepo.enableFloatingBottomBarBlur,
            uiMode = UiMode.fromValue(settingRepo.uiMode),
        )
    }

    private companion object {
        val observedKeys = setOf(
            "color_mode",
            "key_color",
            "color_style",
            "color_spec",
            "page_scale",
            "enable_blur",
            "enable_floating_bottom_bar",
            "enable_floating_bottom_bar_blur",
            "ui_mode",
        )
    }
}

private const val SELECTED_MAIN_PAGE_KEY = "selected_main_page"

private class MainPageState(
    private val savedStateHandle: SavedStateHandle,
    prefs: SharedPreferences,
) {
    init {
        val savedPage = savedStateHandle.get<Int>(SELECTED_MAIN_PAGE_KEY) ?: 0
        // 旧版本只有 2 页（0=首页，1=设置）；新增 AI 页后设置页移到 2。
        savedStateHandle[SELECTED_MAIN_PAGE_KEY] = MainPagerConfig.migrateLegacyPage(savedPage, prefs)
    }

    val selectedPage: StateFlow<Int> = savedStateHandle.getStateFlow(SELECTED_MAIN_PAGE_KEY, 0)

    fun updateSelectedPage(page: Int) {
        savedStateHandle[SELECTED_MAIN_PAGE_KEY] = MainPagerConfig.coercePage(page)
    }
}

object MainPagerConfig {
    const val PAGE_COUNT = 3
    const val LAST_PAGE_INDEX = PAGE_COUNT - 1

    /** 新增 AI 页后的设置页下标（旧版本为 1）。 */
    const val SETTINGS_PAGE_INDEX = 2

    private const val MIGRATION_KEY = "main_page_ai_migrated"

    /**
     * 旧存档迁移：`selected_main_page = 1` 在旧版本表示"设置"，在新版本表示"AI"。
     * 只在升级后的首次调用执行一次，之后沿用正常索引。
     */
    fun migrateLegacyPage(savedPage: Int, prefs: SharedPreferences): Int {
        if (prefs.getBoolean(MIGRATION_KEY, false)) return coercePage(savedPage)
        val migrated = when (savedPage) {
            1 -> SETTINGS_PAGE_INDEX
            else -> savedPage
        }
        prefs.edit().putBoolean(MIGRATION_KEY, true).apply()
        return coercePage(migrated)
    }

    fun coercePage(page: Int): Int = page.coerceIn(0, LAST_PAGE_INDEX)
}
