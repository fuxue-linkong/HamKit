package com.example.hamkit.ui.screen.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.example.hamkit.data.weather.WeatherResult
import com.example.hamkit.ui.LocationUiState
import com.example.hamkit.ui.SatelliteUiState
import com.example.hamkit.ui.util.LatestVersionInfo
import java.io.File

@Immutable
data class HomeUiState(
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentAppVersionCode: Long,
)

/**
 * 业务状态聚合：从 [com.example.hamkit.ui.MainViewModel] 收集定位、卫星、天气等状态。
 * 与 [HomeUiState]（系统信息）分离，便于在 Miuix/Material 双主题间共享。
 */
@Immutable
data class HomeBusinessState(
    val location: LocationUiState,
    val satellites: SatelliteUiState,
    val weather: WeatherResult?,
    val weatherLoading: Boolean,
    val weatherError: String?,
    val dailyQuote: String,
    val timeCardBackgroundFile: File? = null,
    val timeCardMaskColor: Color = Color(0xFF3A5F7F.toInt()),
)

@Immutable
data class HomeActions(
    val onPermissionsClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onRefreshLocation: () -> Unit = {},
    val onRefreshWeather: () -> Unit = {},
    val onSatelliteManagementClick: () -> Unit = {},
    val onCWPracticeClick: () -> Unit = {},
    val onLocationDetailClick: () -> Unit = {},
    val onAprsClick: () -> Unit = {},
    val onFt8Click: () -> Unit = {},
)
