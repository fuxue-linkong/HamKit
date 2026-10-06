package com.example.hamkit.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.example.hamkit.R

/**
 * 关于页展示的应用 Logo。
 *
 * 这里刻意不再走 `PackageManager.getApplicationIcon()`，原因是该路径在不同 ROM 上不可控：
 *  1. 同一份 adaptive icon 会被部分 ROM（实测 HyperOS）二次遮罩裁放，返回的位图只剩画布
 *     中心的一小块（人物面部特写），而启动器上的图标却是正常的；
 *  2. 当系统返回的是 `BitmapDrawable` 时，`Drawable.toBitmap(width, height)`
 *     会直接返回原始位图并忽略请求尺寸，展示效果完全取决于系统，无法保证。
 *
 * 因此改用项目内固定的高分辨率 Logo 资源 `drawable-nodpi/ic_app_logo.png`
 * （不随屏幕密度缩放，与 legacy 图标同构图），保证各设备展示一致且清晰。
 */
@Composable
fun rememberAppIconPainter(): Painter = painterResource(R.drawable.ic_app_logo)
