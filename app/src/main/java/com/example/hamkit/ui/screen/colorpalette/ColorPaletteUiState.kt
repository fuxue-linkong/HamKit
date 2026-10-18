package com.example.hamkit.ui.screen.colorpalette

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.example.hamkit.ui.screen.settings.SettingsUiState
import com.example.hamkit.ui.theme.ColorMode

@Immutable
data class ColorPaletteUiState(
    val uiState: SettingsUiState,
    val currentColorMode: ColorMode,
    val currentPaletteStyle: PaletteStyle,
    val currentColorSpec: ColorSpec.SpecVersion,
)

@Immutable
data class ColorPaletteScreenActions(
    val onBack: () -> Unit,
    val onSetThemeMode: (Int) -> Unit,
    val onSetMiuixMonet: (Boolean) -> Unit,
    val onSetKeyColor: (Int) -> Unit,
    val onSetColorMode: (ColorMode) -> Unit,
    val onSetColorStyle: (String) -> Unit,
    val onSetColorSpec: (String) -> Unit,
    val onSetEnableBlur: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBar: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBarBlur: (Boolean) -> Unit,
    val onSetEnablePredictiveBack: (Boolean) -> Unit,
    val onSetPageScale: (Float) -> Unit,
    val onSetCustomBackground: (android.net.Uri) -> Unit = {},
    // 裁剪结果位图直存路径：不依赖裁剪页回传的 uri 授权
    val onSetCustomBackgroundBitmap: (android.graphics.Bitmap) -> Unit = {},
    // 打开系统图库选图（调用方在此登记 in-flight 以吞掉图库期间的返回事件）
    val onOpenImagePicker: () -> Unit = {},
    val onClearCustomBackground: () -> Unit = {},
)
