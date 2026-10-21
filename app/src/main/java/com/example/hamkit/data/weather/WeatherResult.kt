package com.example.hamkit.data.weather

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/**
 * 天气数据模型。包含实时天气与 4 天预报。
 *
 * 数据来源：高德 Web 服务（天气 + 逆地理编码）
 * - 实时天气：/v3/weather/weatherInfo?extensions=base
 * - 4 天预报：/v3/weather/weatherInfo?extensions=all
 *
 * @param cityName 城市名（来自高德逆地理编码）
 * @param now 实时天气
 * @param daily 4 天预报列表
 * @param fetchTimeMillis 数据获取时间戳，用于缓存过期判断
 */
@Immutable
data class WeatherResult(
    val cityName: String,
    val now: WeatherNow,
    val daily: List<WeatherDay>,
    val fetchTimeMillis: Long = System.currentTimeMillis()
)

/**
 * 实时天气（高德 lives 字段）。
 *
 * @param temp 当前温度（摄氏度，保留一位小数由 UI 格式化）
 * @param text 天气状况文字（如"晴"、"多云"、"阴"）
 * @param windDir 风向（如"东北"）
 * @param windPower 风力（如"≤3"、"1-3"）
 * @param humidity 湿度百分比
 * @param reportTime 数据报告时间（如"2026-07-07 22:03:09"）
 */
@Immutable
data class WeatherNow(
    val temp: String,
    val text: String,
    val windDir: String = "",
    val windPower: String = "",
    val humidity: String = "",
    val reportTime: String = ""
)

/**
 * 单日天气预报（高德 casts 字段）。
 *
 * @param date 日期（yyyy-MM-dd）
 * @param week 星期几（1-7，1=周一）
 * @param dayWeather 白天天气文字
 * @param nightWeather 夜间天气文字
 * @param dayTemp 白天最高温度
 * @param nightTemp 夜间最低温度
 * @param dayWind 白天风向
 * @param nightWind 夜间风向
 * @param dayPower 白天风力
 * @param nightPower 夜间风力
 */
@Immutable
data class WeatherDay(
    val date: String,
    val week: String = "",
    val dayWeather: String = "",
    val nightWeather: String = "",
    val dayTemp: String = "",
    val nightTemp: String = "",
    val dayWind: String = "",
    val nightWind: String = "",
    val dayPower: String = "",
    val nightPower: String = ""
)

/**
 * 天气文字描述到 Material Icon 的映射。
 *
 * 天气服务返回中文或英文描述，此函数根据关键字匹配到最合适的 Material Icon：
 * - 晴 → WbSunny / NightsStay
 * - 多云 → CloudQueue
 * - 阴 → Cloud
 * - 雷 → Thunderstorm
 * - 雨 → Grain
 * - 雪 → AcUnit
 * - 雾/霾/沙/尘/风 → Air
 *
 * @param weatherText 天气状况文字（如"晴"、"雷阵雨"）
 * @param isNight 是否夜间（影响晴的图标选择）
 */
fun mapWeatherIcon(weatherText: String, isNight: Boolean = false): ImageVector {
    val text = weatherText.lowercase(Locale.getDefault())
    return when {
        text.contains("晴") || text.contains("clear") ->
            if (isNight) Icons.Default.NightsStay else Icons.Default.WbSunny
        text.contains("多云") || text.contains("cloud") -> Icons.Default.CloudQueue
        text.contains("阴") || text.contains("overcast") -> Icons.Default.Cloud
        text.contains("雷") || text.contains("thunder") -> Icons.Default.Thunderstorm
        text.contains("雨") || text.contains("rain") || text.contains("drizzle") -> Icons.Default.Grain
        text.contains("雪") || text.contains("snow") -> Icons.Default.AcUnit
        text.contains("雾") || text.contains("霾") || text.contains("fog") -> Icons.Default.Air
        text.contains("沙") || text.contains("尘") -> Icons.Default.Air
        text.contains("风") || text.contains("wind") -> Icons.Default.Air
        else -> Icons.Default.BrokenImage
    }
}
