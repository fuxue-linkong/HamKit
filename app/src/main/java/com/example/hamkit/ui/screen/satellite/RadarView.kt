package com.example.hamkit.ui.screen.satellite

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StampedPathEffectStyle
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.example.hamkit.R
import com.example.hamkit.ui.theme.SafeColors
import com.example.hamkit.ui.theme.isInDarkTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val CIRCLES = 3
private const val STROKE_WIDTH = 6f
private const val SWEEP_DURATION_MS = 8_000
private val PI_2 = PI / 2.0

// 绘制色默认值统一走 SafeColors 令牌（深浅色模式各自取变体），
// 调用方可通过参数覆盖（如 AI 卡片复用时传入主题色）。
private val RADAR_COLOR_LIGHT = Color(0xFF546E7A)
private val RADAR_COLOR_DARK = Color(0xFF90A4AE)

@Composable
fun RadarView(
    azimuthDeg: Double?,
    elevationDeg: Double?,
    isEclipsed: Boolean = false,
    trackPositions: List<Pair<Double, Double>>? = null,
    sunAzimuthDeg: Double? = null,
    sunElevationDeg: Double? = null,
    moonAzimuthDeg: Double? = null,
    moonElevationDeg: Double? = null,
    phoneAzimuthDeg: Float? = null,
    shouldShowSweep: Boolean = true,
    shouldUseCompass: Boolean = false,
    shouldFlipRadar: Boolean = false,
    sweepColor: Color = SafeColors.successIcon,
    trackColor: Color = SafeColors.infoIcon,
    eclipsedDotColor: Color = SafeColors.statusColors(SafeColors.StatusTone.ERROR).foreground,
    radarColor: Color = if (isInDarkTheme()) RADAR_COLOR_DARK else RADAR_COLOR_LIGHT,
    aimColor: Color = SafeColors.statusColors(SafeColors.StatusTone.INFO).foreground.copy(alpha = 0.6f),
    modifier: Modifier = Modifier,
) {
    val pulseTransition = rememberInfiniteTransition(label = "pulseScale")
    val pulseRadius by pulseTransition.animateFloat(
        initialValue = 16f,
        targetValue = 64f,
        animationSpec = infiniteRepeatable(tween(1000)),
        label = "pulseRadius"
    )
    val sweepTransition = rememberInfiniteTransition(label = "sweep")
    val sweepDegrees by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SWEEP_DURATION_MS, easing = LinearEasing)),
        label = "sweepDegrees"
    )
    val measurer = rememberTextMeasurer()
    val sunPainter = painterResource(R.drawable.ic_sun)
    val moonPainter = painterResource(R.drawable.ic_moon)

    // Track path & sweep brush cache
    var cachedRadius by remember { mutableFloatStateOf(0f) }
    var cachedTrackRef by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    var cachedSweepColor by remember { mutableStateOf(Color.Unspecified) }
    var trackPath by remember { mutableStateOf(Path()) }
    var trackEffect by remember { mutableStateOf(PathEffect.cornerPathEffect(0f)) }
    var cachedSweepBrush by remember { mutableStateOf<ShaderBrush?>(null) }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val radius = size.minDimension / 2f * 0.95f

        if (radius != cachedRadius || trackPositions !== cachedTrackRef) {
            val positions = trackPositions
            if (positions != null && positions.size >= 2) {
                trackPath = createTrackPath(positions, radius)
                trackEffect = createTrackEffect(trackPath)
            } else {
                trackPath = Path()
                trackEffect = PathEffect.cornerPathEffect(0f)
            }
            cachedSweepBrush = makeSweepBrush(center, sweepColor)
            cachedRadius = radius
            cachedTrackRef = trackPositions
            cachedSweepColor = sweepColor
        } else if (sweepColor != cachedSweepColor) {
            cachedSweepBrush = makeSweepBrush(center, sweepColor)
            cachedSweepColor = sweepColor
        }

        val baseRotation = if (shouldUseCompass) -(phoneAzimuthDeg ?: 0f) else 0f
        val rotation = if (shouldFlipRadar) baseRotation + 180f else baseRotation

        rotate(rotation) {
            if (shouldShowSweep) cachedSweepBrush?.let { drawSweep(center, sweepDegrees, radius, it) }
            drawRadar(radius, radarColor)
            drawElevationLabels(radius, sweepColor, measurer)
            translate(center.x, center.y) {
                drawTrack(trackPath, trackEffect, trackColor, sweepColor)
                if (azimuthDeg != null && elevationDeg != null && elevationDeg > 0.0) {
                    drawPosition(azimuthDeg, elevationDeg, radius, isEclipsed, pulseRadius, sweepColor, eclipsedDotColor)
                }
                if (sunAzimuthDeg != null && sunElevationDeg != null && sunElevationDeg > 0.0) {
                    drawBodyIcon(sunAzimuthDeg, sunElevationDeg, radius, sweepColor, sunPainter, 52f)
                }
                if (moonAzimuthDeg != null && moonElevationDeg != null && moonElevationDeg > 0.0) {
                    drawBodyIcon(moonAzimuthDeg, moonElevationDeg, radius, radarColor, moonPainter, 52f)
                }
                if (shouldUseCompass && phoneAzimuthDeg != null) {
                    drawAim(phoneAzimuthDeg.toDouble(), radius, aimColor)
                }
            }
        }
    }
}

// ── Look4Sat 风格绘制函数 ──

private fun DrawScope.drawRadar(radius: Float, color: Color) {
    val step = radius / CIRCLES
    for (i in 0 until CIRCLES) {
        drawCircle(color, radius - step * i, style = Stroke(STROKE_WIDTH))
    }
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), STROKE_WIDTH)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), STROKE_WIDTH)
}

private fun DrawScope.drawElevationLabels(radius: Float, color: Color, measurer: TextMeasurer) {
    val step = radius / CIRCLES
    val degStep = 90 / CIRCLES
    val style = TextStyle(color, 15.sp)
    for (i in 0 until CIRCLES) {
        val textY = (radius - step * i) - 32f
        drawText(measurer, " ${degStep * (CIRCLES - i)}°", Offset(center.x, textY), style = style)
    }
}

private fun DrawScope.drawTrack(path: Path, effect: PathEffect, color: Color, effectColor: Color) {
    if (path.isEmpty) return
    drawPath(path, color, style = Stroke(STROKE_WIDTH))
    drawPath(path, effectColor, style = Stroke(pathEffect = effect))
}

private fun DrawScope.drawPosition(
    azimuthDeg: Double, elevationDeg: Double, radius: Float,
    isEclipsed: Boolean, posRadius: Float, color: Color, eclipsedColor: Color,
) {
    val pos = sph2Cart(azimuthDeg, elevationDeg, radius)
    val dotColor = if (isEclipsed) eclipsedColor else color
    drawCircle(dotColor, 16f, pos)
    if (!isEclipsed) drawCircle(dotColor.copy(alpha = 1f - (posRadius / 64f)), posRadius, pos)
}

private fun DrawScope.drawAim(azimuthDeg: Double, radius: Float, color: Color) {
    val size = 36f
    val azimRad = Math.toRadians(azimuthDeg)
    val pos = sph2CartRad(azimRad, 0.0, radius)
    drawLine(color, Offset(pos.x - size, pos.y), Offset(pos.x + size, pos.y), STROKE_WIDTH)
    drawLine(color, Offset(pos.x, pos.y - size), Offset(pos.x, pos.y + size), STROKE_WIDTH)
    drawCircle(color, size / 2f, pos, style = Stroke(STROKE_WIDTH))
}

// ── 工具函数 ──

private fun makeSweepBrush(center: Offset, color: Color): ShaderBrush {
    val colors = listOf(Color.Transparent, color.copy(alpha = 0.5f), color)
    val colorStops = listOf(0.64f, 0.995f, 1f)
    return ShaderBrush(SweepGradientShader(center, colors, colorStops))
}

private fun DrawScope.drawSweep(center: Offset, degrees: Float, radius: Float, brush: ShaderBrush) {
    rotate(-90f + degrees, center) { drawCircle(brush, radius, style = Fill) }
}

private fun createTrackPath(track: List<Pair<Double, Double>>, radius: Float): Path {
    val path = Path()
    track.forEachIndexed { i, (az, el) ->
        val offset = sph2Cart(az, el, radius)
        if (i == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
    }
    return path
}

private fun createTrackEffect(trackPath: Path): PathEffect {
    val shapeRadius = 24f
    val angle = 120.0.toRadians()
    val shape = Path().apply {
        moveTo((shapeRadius * cos(angle)).toFloat(), (shapeRadius * sin(angle)).toFloat())
        for (i in 1 until 3) {
            lineTo(
                x = (shapeRadius * cos(angle - angle * i)).toFloat(),
                y = (shapeRadius * sin(angle - angle * i)).toFloat()
            )
        }
        close()
    }
    val trackLength = PathMeasure().apply { setPath(trackPath, false) }.length
    return PathEffect.stampedPathEffect(shape, trackLength / 2f, trackLength / 4f, StampedPathEffectStyle.Rotate)
}

private fun DrawScope.drawBodyIcon(
    azimuthDeg: Double, elevationDeg: Double, radius: Float,
    color: Color, painter: Painter, iconSize: Float,
) {
    val azimRad = Math.toRadians(azimuthDeg)
    val elevRad = Math.toRadians(elevationDeg)
    val pos = sph2CartRad(azimRad, elevRad, radius)
    val half = iconSize / 2f
    withTransform({ translate(pos.x - half, pos.y - half) }) {
        with(painter) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(color)) }
    }
}

private fun sph2Cart(azimuthDeg: Double, elevationDeg: Double, r: Float): Offset {
    return sph2CartRad(Math.toRadians(azimuthDeg), Math.toRadians(elevationDeg), r)
}

private fun sph2CartRad(azimRad: Double, elevRad: Double, r: Float): Offset {
    val radius = r * ((PI_2 - elevRad) / PI_2).toFloat()
    return Offset(
        x = (radius * sin(azimRad)).toFloat(),
        y = -(radius * cos(azimRad)).toFloat()
    )
}

private fun Double.toRadians(): Double = Math.toRadians(this)
