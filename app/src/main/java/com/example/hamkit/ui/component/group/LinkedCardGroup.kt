package com.example.hamkit.ui.component.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hamkit.ui.theme.Radii

/**
 * 连体卡片组：
 *
 * - 组内条目间隙仅 [gap]（默认 3dp，约为页边距 1/6），相邻条目相接的角只有外角的 1/4；
 * - 整组外角取 [Radii.CardGroup]，读作一个软材料整体；组内每行仍是独立触控区；
 * - 分组标题是 [titleStyle] 的小字强调色。
 *
 * 用法：
 * ```
 * LinkedCardGroup(title = "网络") {
 *     SettingRow(...)   // 条目之间不要自己加 Spacer
 *     SettingRow(...)
 * }
 * ```
 */
@Composable
fun LinkedCardGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    gap: Dp = 3.dp,
    outerShape: Shape = RectangleShape,
    groupColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let {
            androidx.compose.material3.Text(
                text = it,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(outerShape)
                .background(groupColor)
                .padding(gap / 2),
            content = content,
        )
    }
}

/** 连体组内单个条目容器：条目自己带圆角，与相邻条目形成细缝。 */
@Composable
fun LinkedCardGroupItem(
    modifier: Modifier = Modifier,
    shape: Shape,
    color: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color),
        content = content,
    )
}

/** 连体组条目圆角：外角 = 组外角的 1/4（3dp 细缝时视觉最均衡）。 */
object LinkedItemShape {
    fun first(radius: Dp = Radii.Control): Shape =
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = radius, topEnd = radius,
            bottomStart = radius / 4, bottomEnd = radius / 4,
        )

    fun middle(radius: Dp = Radii.Control): Shape =
        androidx.compose.foundation.shape.RoundedCornerShape(radius / 4)

    fun last(radius: Dp = Radii.Control): Shape =
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = radius / 4, topEnd = radius / 4,
            bottomStart = radius, bottomEnd = radius,
        )

    fun single(radius: Dp = Radii.Control): Shape =
        androidx.compose.foundation.shape.RoundedCornerShape(radius)
}
