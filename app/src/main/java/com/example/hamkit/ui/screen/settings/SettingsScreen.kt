package com.example.hamkit.ui.screen.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.data.satellite.SatelliteCategoryExchange
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Navigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val settingsViewModel = appViewModel<SettingsViewModel>()
    val mainViewModel = LocalMainViewModel.current
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    // MainViewModel 的状态是 Compose State<T>（非 StateFlow），直接用 by 委托即可
    val reminderSettings by mainViewModel.reminderSettings
    val reminderItems by mainViewModel.reminderItems

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        settingsViewModel.refresh()
        onPauseOrDispose { }
    }

    // ── 卫星分类配置导出：SAF 选择目标文件后写入 JSON ──
    val exportCategoriesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val message = runCatching {
                val json = mainViewModel.exportSatelliteCategories()
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
                context.getString(R.string.settings_export_categories_success)
            }.getOrElse { error ->
                context.getString(
                    R.string.settings_export_categories_failed,
                    error.message.orEmpty()
                )
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── 卫星分类配置导入：SAF 选择文件后解析并合并 ──
    val importCategoriesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val message = runCatching {
                val json = readBoundedText(context, uri, SatelliteCategoryExchange.MAX_JSON_CHARS)
                // 解析在 IO 线程，状态写入回主线程（避免后台线程写 Compose 状态）
                val merged = withContext(Dispatchers.Main) {
                    mainViewModel.importSatelliteCategories(json)
                }.getOrThrow()
                if (!merged.hasChanges) {
                    context.getString(R.string.settings_import_categories_no_change)
                } else {
                    context.getString(
                        R.string.settings_import_categories_success,
                        merged.addedCategories,
                        merged.updatedCategories,
                        merged.addedAssignments,
                        merged.addedReminderFlags
                    )
                }
            }.getOrElse { error ->
                context.getString(
                    R.string.settings_import_categories_failed,
                    error.message.orEmpty()
                )
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val businessState = SettingsBusinessState(
        reminderSettings = reminderSettings,
        reminderItems = reminderItems,
    )

    val actions = SettingsScreenActions(
        onSetCheckUpdate = settingsViewModel::setCheckUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onSetUiModeIndex = { index ->
            settingsViewModel.setUiMode(if (index == 0) UiMode.Miuix.value else UiMode.Material.value)
        },
        onOpenAbout = { navigator.push(Route.About) },
        onUpdateReminderSettings = mainViewModel::updateReminderSettings,
        onOpenReminderList = { navigator.push(Route.ReminderList) },
        onCheckUpdateNow = settingsViewModel::checkUpdateNow,
        onDownloadAndInstall = settingsViewModel::downloadAndInstall,
        onClearUpdateResult = settingsViewModel::clearUpdateResult,
        onExportSatelliteCategories = {
            exportCategoriesLauncher.launch(SatelliteCategoryExchange.defaultFileName())
        },
        onImportSatelliteCategories = {
            // 使用 */* 而非 application/json：部分文件管理器不会把 .json 识别为
            // application/json，会导致文件不可选。真正的格式校验交给 schema 检查。
            importCategoriesLauncher.launch(arrayOf("*/*"))
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> SettingPagerMiuix(uiState, businessState, actions, bottomInnerPadding)
        UiMode.Material -> SettingPagerMaterial(uiState, businessState, actions, bottomInnerPadding)
    }
}

/**
 * 以字节数为上限读取 SAF 文件内容。
 *
 * 不能在解析层之前先 `readBytes()` 再校验长度：那样超大文件已经被完整读进内存，
 * 仍可能 OOM 或被系统杀死（选择文件时用的是通配 MIME 类型，用户可能误选任意大文件）。
 * 这里边读边计数，超限立即失败。UTF-8 下字节数 ≥ 字符数，故以字符上限作字节上限更严格。
 */
private fun readBoundedText(context: Context, uri: Uri, maxBytes: Int): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: error("无法读取所选文件")
    return stream.use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                error("文件过大（超过 ${maxBytes / 1024 / 1024} MiB），已拒绝导入")
            }
            output.write(chunk, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }
}
