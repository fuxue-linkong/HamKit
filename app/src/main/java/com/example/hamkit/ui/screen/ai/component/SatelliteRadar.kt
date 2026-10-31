package com.example.hamkit.ui.screen.ai.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 卫星极坐标雷达（方位 0° 向上、顺时针）。
 *
 * 只做视觉表达：同心圈表示仰角 30°/60°，虚线环与十字为刻度，扫描扇区跟随当前方位，
 * 圆点位置 = 当前方位 + (1 - 仰角/90) 的半径。颜色由调用方传入，Miuix/Material 共用。
 */
@Composable
fun SatelliteRadar(
    azimuthDeg: Double,
    elevationDeg: Double,
    primary: Color,
    outline: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 132.dp,
) {
    Canvas(modifier.size(diameter)) {
        val centerPoint = center
        val radius = size.minDimension / 2f - 4.dp.toPx()
        val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

        drawCircle(outline, radius, centerPoint, style = Stroke(1.dp.toPx()))
        drawCircle(
            outline.copy(alpha = 0.55f),
            radius * 2f / 3f,
            centerPoint,
            style = Stroke(1.dp.toPx(), pathEffect = dashed),
        )
        drawCircle(
            outline.copy(alpha = 0.55f),
            radius / 3f,
            centerPoint,
            style = Stroke(1.dp.toPx(), pathEffect = dashed),
        )
        drawLine(
            outline.copy(alpha = 0.3f),
            Offset(centerPoint.x - radius, centerPoint.y),
            Offset(centerPoint.x + radius, centerPoint.y),
            1.dp.toPx(),
        )
        drawLine(
            outline.copy(alpha = 0.3f),
            Offset(centerPoint.x, centerPoint.y - radius),
            Offset(centerPoint.x, centerPoint.y + radius),
            1.dp.toPx(),
        )

        // 扫描扇区（±30°）
        drawArc(
            color = primary.copy(alpha = 0.12f),
            startAngle = (azimuthDeg - 120.0).toFloat(),
            sweepAngle = 60f,
            useCenter = true,
            topLeft = Offset(centerPoint.x - radius, centerPoint.y - radius),
            size = Size(radius * 2f, radius * 2f),
        )

        val elevation = elevationDeg.coerceIn(0.0, 90.0)
        val distance = radius * (1f - (elevation / 90f).toFloat())
        val radians = Math.toRadians(azimuthDeg)
        val point = Offset(
            centerPoint.x + distance * sin(radians).toFloat(),
            centerPoint.y - distance * cos(radians).toFloat(),
        )
        drawLine(primary, centerPoint, point, 1.5.dp.toPx())
        drawCircle(primary.copy(alpha = 0.25f), 8.dp.toPx(), point)
        drawCircle(primary, 4.dp.toPx(), point)
    }
}
