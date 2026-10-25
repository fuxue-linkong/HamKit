package com.example.hamkit.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.hamkit.BuildConfig
import com.example.hamkit.radioApp
import com.example.hamkit.data.repository.SettingsRepository
import com.example.hamkit.data.repository.SettingsRepositoryImpl
import com.example.hamkit.ui.screen.settings.SettingsUiState
import com.example.hamkit.ui.WideNavStyle
import com.example.hamkit.ui.theme.ColorMode
import com.example.hamkit.ui.util.ApkVerifier
import com.example.hamkit.ui.util.LatestVersionInfo
import com.example.hamkit.ui.util.checkNewVersion
import com.example.hamkit.ui.util.downloadApk
import com.example.hamkit.ui.util.isAllowedUpdateUrl
import com.example.hamkit.ui.util.isNewerVersion
import java.io.File

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val checkUpdate = repo.checkUpdate
            val themeMode = repo.themeMode
            val miuixMonet = repo.miuixMonet
            val keyColor = repo.keyColor
            val enablePredictiveBack = repo.enablePredictiveBack
            val enableBlur = repo.enableBlur
            val enableFloatingBottomBar = repo.enableFloatingBottomBar
            val enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur
            val wideNavStyle = repo.wideNavStyle
            val pageScale = repo.pageScale
            val colorStyle = repo.colorStyle
            val colorSpec = repo.colorSpec
            val uiMode = repo.uiMode
            val customBackgroundUri = repo.customBackgroundUri

            _uiState.update {
                it.copy(
                    uiMode = uiMode,
                    checkUpdate = checkUpdate,
                    themeMode = themeMode,
                    miuixMonet = miuixMonet,
                    keyColor = keyColor,
                    enablePredictiveBack = enablePredictiveBack,
                    enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    wideNavStyle = wideNavStyle,
                    pageScale = pageScale,
                    colorStyle = colorStyle,
                    colorSpec = colorSpec,
                    customBackgroundUri = customBackgroundUri,
                )
            }
        }
    }

    fun setCheckUpdate(enabled: Boolean) {
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    fun setUiMode(mode: String) {
        val oldMode = repo.uiMode
        val currentThemeMode = repo.themeMode

        val newThemeMode = when (oldMode) {
            "material" if mode == "miuix" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                val baseMode = if (colorMode == ColorMode.DARK_AMOLED) 2 else currentThemeMode
                if (repo.miuixMonet && !colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toMonetMode()
                } else if (!repo.miuixMonet && colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toNonMonetMode()
                } else baseMode
            }

            "miuix" if mode == "material" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                if (colorMode.isMonet) {
                    colorMode.toNonMonetMode()
                } else currentThemeMode
            }

            else -> currentThemeMode
        }

        repo.uiMode = mode
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(uiMode = mode, themeMode = newThemeMode) }
    }

    fun setThemeMode(mode: Int) {
        val currentUiMode = repo.uiMode
        val effectiveMode = if (currentUiMode == "miuix" && _uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableBlur(enabled: Boolean) {
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    /** 设置大屏导航形态；索引越界时回退为「自动」 */
    fun setWideNavStyleIndex(index: Int) {
        val style = WideNavStyle.entries.getOrNull(index) ?: WideNavStyle.Auto
        repo.wideNavStyle = style.value
        _uiState.update { it.copy(wideNavStyle = style.value) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }

    fun setCustomBackgroundUri(uri: String) {
        repo.customBackgroundUri = uri
        _uiState.update { it.copy(customBackgroundUri = uri) }
    }

    fun clearCustomBackgroundUri() {
        repo.customBackgroundUri = ""
        _uiState.update { it.copy(customBackgroundUri = "") }
    }

    // ── 更新检查与下载安装 ──────────────────────────────────────────

    /**
     * 自动检查节流间隔：6 小时。
     *
     * 未认证 GitHub API 限额 60 次/小时/IP，避免用户频繁进出应用消耗额度。
     * 仅对自动检查路径（force = false）生效；设置页"立即检查"按钮强制检查。
     */
    private val updateThrottleMs = 6L * 60 * 60 * 1000

    private val updatePrefs by lazy {
        radioApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /**
     * 检查更新。
     *
     * - [force] = true（默认）：跳过节流，立即请求。设置页"立即检查"按钮使用。
     * - [force] = false：节流生效，距上次检查不足 [updateThrottleMs] 则跳过。自动检查路径使用。
     *
     * 检查中设置 [SettingsUiState.updateChecking]，完成后填充
     * [SettingsUiState.latestVersionInfo] 和 [SettingsUiState.updateAvailable]。
     */
    fun checkUpdateNow(force: Boolean = true) {
        if (_uiState.value.updateChecking) return
        if (!force) {
            val last = updatePrefs.getLong("last_update_check_time", 0L)
            if (last > 0 && System.currentTimeMillis() - last < updateThrottleMs) return
        }
        updatePrefs.edit { putLong("last_update_check_time", System.currentTimeMillis()) }
        _uiState.update { it.copy(updateChecking = true, updateError = false, updateErrorMessage = "") }
        viewModelScope.launch {
            val info = withContext(kotlinx.coroutines.Dispatchers.IO) { checkNewVersion() }
            val hasUpdate = if (info.versionCode > 0) {
                info.versionCode > BuildConfig.VERSION_CODE
            } else {
                // versionCode 提取失败（手工发布的 release 可能不含 Version 行）
                // 回退到语义化版本名比较，避免误报"已是最新版本"
                isNewerVersion(info.versionName, BuildConfig.VERSION_NAME)
            } && info.downloadUrl.isNotEmpty()
            _uiState.update {
                it.copy(
                    updateChecking = false,
                    latestVersionInfo = info,
                    updateAvailable = hasUpdate,
                    updateError = false,
                    updateErrorMessage = "",
                    downloadProgress = -1,
                )
            }
        }
    }

    /**
     * 下载最新版 APK 并触发安装。
     *
     * 下载进度通过 [SettingsUiState.downloadProgress] 暴露（0-100，-1 表示未下载）。
     * 下载完成后通过 FileProvider + ACTION_VIEW 触发系统安装器。
     */
    fun downloadAndInstall() {
        val info = _uiState.value.latestVersionInfo
        if (info.downloadUrl.isEmpty()) return
        if (_uiState.value.downloadProgress >= 0) return // 正在下载中
        if (!isAllowedUpdateUrl(info.downloadUrl)) {
            _uiState.update {
                it.copy(
                    downloadProgress = -1,
                    updateError = true,
                    updateErrorMessage = "更新地址不在允许的域名内，已拒绝下载",
                )
            }
            return
        }
        _uiState.update {
            it.copy(downloadProgress = 0, updateError = false, updateErrorMessage = "")
        }
        viewModelScope.launch {
            val destFile = File(radioApp.cacheDir, "update_${info.versionCode}.apk")
            val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                downloadApk(info.downloadUrl, destFile) { progress ->
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
            }
            if (!success) {
                _uiState.update {
                    it.copy(
                        downloadProgress = -1,
                        updateError = true,
                        updateErrorMessage = "更新包下载失败或地址不可信",
                    )
                }
                return@launch
            }

            val verification = withContext(kotlinx.coroutines.Dispatchers.IO) {
                ApkVerifier.verify(radioApp, destFile, info.sha256)
            }
            if (!verification.ok) {
                destFile.delete()
                _uiState.update {
                    it.copy(
                        downloadProgress = -1,
                        updateError = true,
                        updateErrorMessage = verification.message.ifBlank { "更新包校验失败" },
                    )
                }
                return@launch
            }

            installApk(destFile)
            _uiState.update { it.copy(downloadProgress = 100) }
        }
    }

    /** 清除更新检查结果（关闭对话框时调用）。 */
    fun clearUpdateResult() {
        _uiState.update {
            it.copy(
                updateAvailable = false,
                updateErrorMessage = "",
                latestVersionInfo = LatestVersionInfo(),
            )
        }
    }

    /**
     * 通过 FileProvider 共享 APK 文件，启动系统安装器。
     * APK 存放在 cacheDir，已在 filepaths.xml 中配置 cache-path。
     */
    private fun installApk(apkFile: File) {
        val context = radioApp
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

}
