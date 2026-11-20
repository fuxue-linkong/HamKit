package com.example.hamkit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hamkit.R
import com.example.hamkit.data.weather.WeatherResult
import com.example.hamkit.data.weather.mapWeatherIcon
import com.example.hamkit.ui.theme.LocalCardAlpha
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.example.hamkit.ui.theme.Radii

@Composable
fun WeatherCard(
    weather: WeatherResult?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    // 主题色由调用方注入：Miuix 调用方走默认值，Material 调用方传 MaterialTheme.colorScheme 对应字段
    stateColor: Color = if (weather != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
    secondaryTextColor: Color = MiuixTheme.colorScheme.onSurfaceSecondary,
    // 是否绘制自带圆角背景；合并进其他卡片时传 false 仅渲染内容
    applyBackground: Boolean = true,
    // 温度数字字号（sp）；时间卡片为与 UTC 时间字号调换，会传入较小值
    temperatureFontSize: Float = 26f,
) {
    val finalModifier = if (applyBackground) {
        modifier
            .clip(RoundedCornerShape(Radii.Card))
            .background(stateColor.copy(alpha = 0.12f * LocalCardAlpha.current))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    } else {
        modifier
    }
    Column(modifier = finalModifier) {
        when {
            isLoading && weather == null -> LoadingState(stateColor = stateColor)
            error != null && weather == null -> ErrorState(
                error = error,
                stateColor = stateColor,
                secondaryTextColor = secondaryTextColor,
                onRetry = onRefresh
            )
            weather != null -> WeatherContent(
                weather = weather,
                stateColor = stateColor,
                secondaryTextColor = secondaryTextColor,
                temperatureFontSize = temperatureFontSize,
            )
            else -> InitialState(
                stateColor = stateColor,
                onRefresh = onRefresh
            )
        }
    }
}

@Composable
private fun WeatherContent(
    weather: WeatherResult,
    stateColor: Color,
    secondaryTextColor: Color,
    temperatureFontSize: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = mapWeatherIcon(weather.now.text),
            contentDescription = weather.now.text,
            tint = stateColor,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = formatTemperature(weather.now.temp),
            style = TextStyle(fontSize = temperatureFontSize.sp, fontWeight = FontWeight.Medium),
            color = stateColor
        )
        Text(
            text = weather.now.text,
            style = TextStyle(fontSize = 14.sp),
            color = secondaryTextColor
        )
    }
}

@Composable
private fun LoadingState(stateColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InfiniteProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = stateColor,
        )
        Text(
            text = stringResource(R.string.weather_loading),
            style = TextStyle(fontSize = 14.sp),
            color = stateColor
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(10.dp)
            .clip(RoundedCornerShape(Radii.Badge))
            .background(stateColor.copy(alpha = 0.1f))
    )
}

@Composable
private fun ErrorState(
    error: String,
    stateColor: Color,
    secondaryTextColor: Color,
    onRetry: () -> Unit
) {
    Text(
        text = stringResource(R.string.weather_load_failed),
        style = TextStyle(fontSize = 13.sp),
        color = stateColor
    )
    Text(
        text = error,
        style = TextStyle(fontSize = 11.sp),
        color = secondaryTextColor,
        modifier = Modifier.padding(top = 2.dp)
    )
    TextButton(
        text = stringResource(R.string.weather_retry),
        onClick = onRetry,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun InitialState(
    stateColor: Color,
    onRefresh: () -> Unit
) {
    TextButton(
        text = stringResource(R.string.weather_tap_to_load),
        onClick = onRefresh,
    )
}

private fun formatTemperature(temp: String): String {
    val value = temp.toDoubleOrNull() ?: return "--"
    return String.format(java.util.Locale.US, "%.1f°C", value)
}
