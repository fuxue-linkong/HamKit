package com.example.hamkit.ui.util

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
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
 * 同时这里显式用 `BitmapFactory` 解码并关闭密度缩放：
 *   - 资源放在 `drawable-nodpi`，本就不应随屏幕密度缩放；
 *   - 显式 `inScaled = false` 可避免个别 ROM 按自身密度策略对位图做二次缩放，
 *     保证 painter 的 intrinsicSize 恒为资源原始像素（691×691），
 *     配合调用点显式声明的 `ContentScale.Fit`，得到「等比完整显示、绝不裁切」的结果。
 */
@Composable
fun rememberAppIconPainter(): Painter {
    val context = LocalContext.current
    return remember(context) {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_logo, options)
        if (bitmap != null) {
            BitmapPainter(bitmap.asImageBitmap())
        } else {
            // 资源缺失时兜底为 1×1 透明位图，避免崩溃
            BitmapPainter(ImageBitmap(1, 1))
        }
    }
}
