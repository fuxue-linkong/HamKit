package com.example.hamkit.ui.screen.ai.component

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hamkit.ui.screen.satellite.RadarView
import com.example.hamkit.ui.theme.SafeColors

/**
 * AI 卡片内的卫星极坐标雷达（D9 整改）。
 *
 * 原先是独立绘制的简化版 Canvas（±30° 固定扇区、startAngle 语义还与扫描逻辑不符），
 * 用户在 AI 卡片与雷达页看到两种「雷达」。现直接复用卫星模块的 [RadarView]
 * 紧凑模式：细描边、隐藏仰角标签、关闭扫描扇形，颜色走主题/令牌，
 * 与雷达页保持同一套视觉语言。
 *
 * @param primary 位置光点颜色（沿用调用方主题色，保持与卡片内其他元素一致）
 * @param isEclipsed 蚀状态：光点变红（与雷达页同语义）
 */
@Composable
fun SatelliteRadar(
    azimuthDeg: Double,
    elevationDeg: Double,
    primary: Color,
    outline: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 132.dp,
    isEclipsed: Boolean = false,
) {
    RadarView(
        azimuthDeg = azimuthDeg,
        elevationDeg = elevationDeg,
        isEclipsed = isEclipsed,
        shouldShowSweep = false,
        compact = true,
        sweepColor = primary,
        eclipsedDotColor = SafeColors.statusColors(SafeColors.StatusTone.ERROR).foreground,
        radarColor = outline,
        modifier = modifier.size(diameter),
    )
}
