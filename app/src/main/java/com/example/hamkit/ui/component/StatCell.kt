package com.example.hamkit.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme as miuixColorScheme

/**
 * 「label + value 数字对」统一组件（设计审查 A7/D6 整改）。
 *
 * 五处各自为政的实现（LiveBigNumber / ManagementStat / RadarStat / MapInfoText / Metric）
 * 收敛为一份规格：
 * - [StatEmphasis.Hero]    30sp Bold   —— 详情页实时主数字
 * - [StatEmphasis.Primary] 22sp Bold   —— 管理页概览统计
 * - [StatEmphasis.Inline]  15sp SemiBold —— 雷达页/地图页/AI 卡片行内指标
 *
 * label 一律 11-12sp（满足无障碍最小字号，修复原 MetricMiuix 9.5sp 问题）。
 * 颜色从当前皮肤的主题读取，双皮肤共用同一份布局与字号规格。
 */
enum class StatEmphasis { Hero, Primary, Inline }

@Composable
fun StatCell(
    label: String,
    value: String,
    emphasis: StatEmphasis,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    val valueFontSize = when (emphasis) {
        StatEmphasis.Hero -> 30.sp
        StatEmphasis.Primary -> 22.sp
        StatEmphasis.Inline -> 15.sp
    }
    val labelFontSize = when (emphasis) {
        StatEmphasis.Inline -> 11.sp
        else -> 12.sp
    }
    val valueFontWeight = if (emphasis == StatEmphasis.Inline) FontWeight.SemiBold else FontWeight.Bold

    when (LocalUiMode.current) {
        UiMode.Miuix -> StatCellContent(
            value = value,
            label = label,
            valueColor = miuixColorScheme.primary.takeIf { emphasis != StatEmphasis.Inline }
                ?: miuixColorScheme.onSurface,
            labelColor = miuixColorScheme.onSurfaceVariantSummary,
            valueFontSize = valueFontSize,
            valueFontWeight = valueFontWeight,
            labelFontSize = labelFontSize,
            alignment = alignment,
            modifier = modifier,
            textFactory = { text, fontSize, fontWeight, color ->
                MiuixText(text = text, fontSize = fontSize, fontWeight = fontWeight, color = color)
            },
        )

        UiMode.Material -> StatCellContent(
            value = value,
            label = label,
            valueColor = MaterialTheme.colorScheme.primary.takeIf { emphasis != StatEmphasis.Inline }
                ?: MaterialTheme.colorScheme.onSurface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            valueFontSize = valueFontSize,
            valueFontWeight = valueFontWeight,
            labelFontSize = labelFontSize,
            alignment = alignment,
            modifier = modifier,
            textFactory = { text, fontSize, fontWeight, color ->
                MaterialText(text = text, fontSize = fontSize, fontWeight = fontWeight, color = color)
            },
        )
    }
}

@Composable
private fun StatCellContent(
    value: String,
    label: String,
    valueColor: Color,
    labelColor: Color,
    valueFontSize: androidx.compose.ui.unit.TextUnit,
    valueFontWeight: FontWeight,
    labelFontSize: androidx.compose.ui.unit.TextUnit,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
    textFactory: @Composable (String, androidx.compose.ui.unit.TextUnit, FontWeight, Color) -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = alignment) {
        textFactory(value, valueFontSize, valueFontWeight, valueColor)
        textFactory(label, labelFontSize, FontWeight.Normal, labelColor)
    }
}
