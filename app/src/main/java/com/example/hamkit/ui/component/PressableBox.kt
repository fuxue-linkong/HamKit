package com.example.hamkit.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.example.hamkit.ui.theme.Motions

/**
 * 按压反馈（借鉴 ReveriePaint 思路 6）：按压缩放 + 触点径向高光跟随指尖。
 * 替代系统 ripple：深色低饱和界面上 ripple 灰圈显脏，高光更贴"材料受力"。
 */
@Composable
fun PressableBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.ui.graphics.RectangleShape,
    /** 高光颜色；传 [Color.Transparent] 关闭触点高光，只保留缩放。 */
    highlightColor: Color = Color.White.copy(alpha = 0.10f),
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motions.press(),
        label = "pressScale",
    )
    var size by remember { mutableStateOf(IntSize.Zero) }
    var touch by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .scale(scale)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .pointerInput(enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touch = down.position
                    waitForUpOrCancellation()
                }
            },
    ) {
        content()
        if (pressed && highlightColor != Color.Transparent && size != IntSize.Zero) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(highlightColor, Color.Transparent),
                        center = touch,
                        radius = size.width.coerceAtLeast(size.height).toFloat() * 0.9f,
                    ),
                )
            }
        }
    }
}
