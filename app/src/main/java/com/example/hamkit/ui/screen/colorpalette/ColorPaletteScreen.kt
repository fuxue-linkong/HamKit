package com.example.hamkit.ui.screen.colorpalette

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.example.hamkit.R
import com.example.hamkit.HamKitApplication
import com.example.hamkit.data.LandscapeImageStore
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.theme.ColorMode
import com.example.hamkit.ui.viewmodel.SettingsViewModel

private const val IMPORT_LOG_TAG = "BgImport"

@Composable
fun ColorPaletteScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val viewModel = appViewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imageStore = remember { LandscapeImageStore(context.applicationContext) }
    val mainViewModel = LocalMainViewModel.current
    val currentPaletteStyle = try {
        PaletteStyle.valueOf(uiState.colorStyle)
    } catch (_: Exception) {
        PaletteStyle.TonalSpot
    }
    val currentColorSpec = try {
        ColorSpec.SpecVersion.valueOf(uiState.colorSpec)
    } catch (_: Exception) {
        ColorSpec.SpecVersion.Default
    }
    // 主题模式在 SettingsRepository 中以 Int 存储，需转回 ColorMode 枚举
    val currentColorMode = ColorMode.fromValue(uiState.themeMode)

    // 导入成功/失败后的统一收尾：更新状态并刷新首页时间卡背景
    val onSaved: () -> Unit = {
        // 存储实际文件路径而非源 URI（源 URI 进程重启后可能失效）
        viewModel.setCustomBackgroundUri(imageStore.customImageFile.absolutePath)
        mainViewModel.refreshLandscapeImage()
    }

    val onFailed: (String) -> Unit = { reason ->
        Log.e(IMPORT_LOG_TAG, "背景导入失败：$reason")
        Toast.makeText(
            context,
            context.getString(R.string.timecard_background_save_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    val actions = ColorPaletteScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSetThemeMode = viewModel::setThemeMode,
        onSetMiuixMonet = viewModel::setMiuixMonet,
        onSetKeyColor = viewModel::setKeyColor,
        onSetColorMode = viewModel::setColorMode,
        onSetColorStyle = viewModel::setColorStyle,
        onSetColorSpec = viewModel::setColorSpec,
        onSetEnableBlur = viewModel::setEnableBlur,
        onSetEnableFloatingBottomBar = viewModel::setEnableFloatingBottomBar,
        onSetEnableFloatingBottomBarBlur = viewModel::setEnableFloatingBottomBarBlur,
        onSetEnablePredictiveBack = {
            viewModel.setEnablePredictiveBack(it)
            HamKitApplication.setEnableOnBackInvokedCallback(context.applicationInfo, it)
            activity?.recreate()
        },
        onSetPageScale = viewModel::setPageScale,
        // 首选路径：直接用裁剪结果位图保存，绕开 MediaStore 临时授权失效问题
        onSetCustomBackgroundBitmap = { bitmap: Bitmap ->
            if (imageStore.saveCustomImage(bitmap)) {
                onSaved()
            } else {
                onFailed("裁剪结果位图保存失败")
            }
            bitmap.recycle()
        },
        // 兼容路径：裁剪页只回传 uri 时（如 Activity 重建）仍按 uri 读取并保存
        onSetCustomBackground = { uri: Uri ->
            Log.i(IMPORT_LOG_TAG, "背景导入走 uri 路径：${uri.scheme}:$uri")
            if (imageStore.saveCustomImage(uri, context)) {
                onSaved()
            } else {
                onFailed("uri 读取保存失败：${uri.scheme}:$uri")
            }
        },
        onClearCustomBackground = {
            imageStore.clearCustomImage()
            viewModel.clearCustomBackgroundUri()
            mainViewModel.refreshLandscapeImage()
        },
    )

    // 组装 UI 状态（重构时漏掉了这行，导致两个子屏幕收到未定义的 state）
    val state = ColorPaletteUiState(
        uiState = uiState,
        currentColorMode = currentColorMode,
        currentPaletteStyle = currentPaletteStyle,
        currentColorSpec = currentColorSpec,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ColorPaletteScreenMiuix(state, actions)
        UiMode.Material -> ColorPaletteScreenMaterial(state, actions)
    }
}
