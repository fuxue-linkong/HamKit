package com.example.hamkit.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 窗口尺寸断点（大屏适配）。
 *
 * 断点沿用 Material 常见分档，并考虑折叠屏展开（宽 ≥600dp 即给 Rail）：
 * - <600dp：手机竖屏，底部导航栏；
 * - ≥600dp 且（≥840dp 或 高宽比 <1.2）：横屏手机/折叠屏，折叠 Rail；
 * - ≥1200dp：平板/大屏，Rail 展开显示文字标签。
 *
 * 优先用 [LocalWindowInfo.containerSize]（API 33+ 最准确）；
 * 取不到有效值时回退 [LocalConfiguration.screenWidthDp]。
 */
@Composable
fun rememberWindowWidthDp(): Dp {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val widthPx = windowInfo.containerSize.width
    return if (widthPx > 0) {
        with(density) { widthPx.toDp() }
    } else {
        (LocalConfiguration.current.screenWidthDp ?: 0).dp
    }
}

@Composable
fun rememberWindowHeightDp(): Dp {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val heightPx = windowInfo.containerSize.height
    return if (heightPx > 0) {
        with(density) { heightPx.toDp() }
    } else {
        (LocalConfiguration.current.screenHeightDp ?: 0).dp
    }
}

/** 是否应使用侧边导航 Rail（替代底部栏）。 */
@Composable
fun shouldShowNavigationRail(): Boolean {
    val width = rememberWindowWidthDp()
    val height = rememberWindowHeightDp()
    if (width < 600.dp) return false
    // 折叠屏竖持（宽 ≥600 但细长）仍可保留底部栏
    return width >= 840.dp || height / width < 1.2f
}

/** Rail 是否展开显示文字标签（平板/大屏横屏）。 */
@Composable
fun shouldExpandNavigationRail(): Boolean = rememberWindowWidthDp() >= 1200.dp

/**
 * 大屏内容限宽：避免列表/对话在平板上横向铺满。
 * 返回 [Dp.Unspecified] 表示不限宽。
 */
@Composable
fun contentMaxWidth(): Dp = if (rememberWindowWidthDp() >= 840.dp) 720.dp else Dp.Unspecified
