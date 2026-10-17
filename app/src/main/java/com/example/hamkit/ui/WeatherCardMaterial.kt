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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * 天气内容的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版 [WeatherCard] **签名完全一致**（含颜色注入参数、`applyBackground`、
 * `temperatureFontSize`），因此 Material 页面可以直接替换调用而不改其它代码。
 *
 * 与 Miuix 版的差别只在组件：图标与文本换成 material3，加载指示器换成
 * `CircularProgressIndicator`，重试/加载按钮换成 material3 `TextButton`。
 * 字号沿用原实现的显式 `TextStyle`，保证两种风格下天气信息的信息层级不变。
 *
 * @param stateColor 主状态色（图标、温度、加载文字）；由调用方注入对应风格的主题色
 * @param secondaryTextColor 次级文字色
 * @param applyBackground 是否绘制自带圆角背景；合并进其他卡片时传 false 仅渲染内容
 * @param temperatureFontSize 温度数字字号（sp）
 */
@Composable
fun WeatherCardMaterial(
    weather: WeatherResult?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    stateColor: Color,
    secondaryTextColor: Color,
    applyBackground: Boolean = true,
    temperatureFontSize: Float = 26f,
) {
    val finalModifier = if (applyBackground) {
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(stateColor.copy(alpha = 0.12f * LocalCardAlpha.current))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    } else {
        modifier
    }
    Column(modifier = finalModifier) {
        when {
            isLoading && weather == null -> WeatherLoadingStateMaterial(stateColor = stateColor)
            error != null && weather == null -> WeatherErrorStateMaterial(
                error = error,
                stateColor = stateColor,
                secondaryTextColor = secondaryTextColor,
                onRetry = onRefresh,
            )
            weather != null -> WeatherContentMaterial(
                weather = weather,
                stateColor = stateColor,
                secondaryTextColor = secondaryTextColor,
                temperatureFontSize = temperatureFontSize,
            )
            else -> WeatherInitialStateMaterial(
                stateColor = stateColor,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun WeatherContentMaterial(
    weather: WeatherResult,
    stateColor: Color,
    secondaryTextColor: Color,
    temperatureFontSize: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = mapWeatherIcon(weather.now.text),
            contentDescription = weather.now.text,
            tint = stateColor,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = formatTemperatureMaterial(weather.now.temp),
            style = TextStyle(fontSize = temperatureFontSize.sp, fontWeight = FontWeight.Medium),
            color = stateColor,
        )
        Text(
            text = weather.now.text,
            style = TextStyle(fontSize = 14.sp),
            color = secondaryTextColor,
        )
    }
}

@Composable
private fun WeatherLoadingStateMaterial(stateColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = stateColor,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.weather_loading),
            style = TextStyle(fontSize = 14.sp),
            color = stateColor,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(stateColor.copy(alpha = 0.1f)),
    )
}

@Composable
private fun WeatherErrorStateMaterial(
    error: String,
    stateColor: Color,
    secondaryTextColor: Color,
    onRetry: () -> Unit,
) {
    Text(
        text = stringResource(R.string.weather_load_failed),
        style = TextStyle(fontSize = 13.sp),
        color = stateColor,
    )
    Text(
        text = error,
        style = TextStyle(fontSize = 11.sp),
        color = secondaryTextColor,
        modifier = Modifier.padding(top = 2.dp),
    )
    TextButton(
        onClick = onRetry,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Text(stringResource(R.string.weather_retry))
    }
}

@Composable
private fun WeatherInitialStateMaterial(
    stateColor: Color,
    onRefresh: () -> Unit,
) {
    TextButton(onClick = onRefresh) {
        Text(
            text = stringResource(R.string.weather_tap_to_load),
            color = stateColor,
        )
    }
}

/** 温度格式化：与 Miuix 版同一实现（保留一位小数，非法值给 `--`）。 */
private fun formatTemperatureMaterial(temp: String): String {
    val value = temp.toDoubleOrNull() ?: return "--"
    return String.format(java.util.Locale.US, "%.1f°C", value)
}
