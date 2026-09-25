package com.example.hamkit.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.permission.PermissionManager
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.navigation3.Navigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.HomeViewModel
import com.example.hamkit.ui.viewmodel.SettingsViewModel

@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true
) {
    val homeViewModel = appViewModel<HomeViewModel>()
    val settingsViewModel = appViewModel<SettingsViewModel>()
    val mainViewModel = LocalMainViewModel.current
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionManager = remember(context) { PermissionManager(context) }
    val permissionState by permissionManager.state.collectAsStateWithLifecycle()

    // MainViewModel 的状态是 Compose State<T>（非 StateFlow），直接用 by 委托即可
    val locationState by mainViewModel.locationState
    val satelliteState by mainViewModel.satelliteState
    val weather by mainViewModel.weather
    val weatherLoading by mainViewModel.weatherLoading
    val weatherError by mainViewModel.weatherError
    val dailyQuote by mainViewModel.dailyQuote
    val timeCardBackgroundFile by mainViewModel.timeCardBackgroundFile
    val timeCardMaskColor by mainViewModel.timeCardMaskColor

    var hasActivated by remember { mutableStateOf(false) }
    if (isCurrentPage) hasActivated = true

    // 首次进入主页时初始化业务 ViewModel（加载 TLE 缓存、启动天气自动刷新等）
    LaunchedEffect(hasActivated) {
        if (hasActivated) {
            mainViewModel.initializeIfNeeded()
            mainViewModel.startWeatherAutoRefresh()
            mainViewModel.refreshDailyQuote()
            mainViewModel.refreshLandscapeImage()
            mainViewModel.refreshLocation()
        }
    }

    if (hasActivated) {
        LaunchedEffect(Unit) {
            homeViewModel.refresh()
            // 启动时自动检查更新（节流：6 小时内只查一次）；
            // 检查结果存入 SettingsViewModel.uiState，由 MainScreen 顶层 UpdateDialogs 渲染
            if (uiState.checkUpdateEnabled) {
                settingsViewModel.checkUpdateNow(force = false)
            }
        }
    }
    LifecycleResumeEffect(permissionManager) {
        permissionManager.refresh()
        mainViewModel.refreshLandscapeImage()
        onPauseOrDispose { }
    }

    val actions = HomeActions(
        onPermissionsClick = { navigator.push(Route.Permissions) },
        onOpenUrl = uriHandler::openUri,
        onRefreshLocation = { mainViewModel.refreshLocation() },
        onRefreshWeather = { mainViewModel.refreshWeather(force = true) },
        onSatelliteManagementClick = { navigator.push(Route.SatelliteManagement) },
        onCWPracticeClick = { navigator.push(Route.CWPractice) },
        onLocationDetailClick = { navigator.push(Route.LocationDetail) },
        onAprsClick = { navigator.push(Route.AprsMain) },
        onFt8Click = { navigator.push(Route.Ft8Main) },
    )

    val businessState = HomeBusinessState(
        location = locationState,
        satellites = satelliteState,
        weather = weather,
        weatherLoading = weatherLoading,
        weatherError = weatherError,
        dailyQuote = dailyQuote,
        timeCardBackgroundFile = timeCardBackgroundFile,
        timeCardMaskColor = timeCardMaskColor,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> HomePagerMiuix(
            state = uiState,
            businessState = businessState,
            permissionState = permissionState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> HomePagerMaterial(
            state = uiState,
            businessState = businessState,
            permissionState = permissionState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}
