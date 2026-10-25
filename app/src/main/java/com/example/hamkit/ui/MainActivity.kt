package com.example.hamkit.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.hamkit.R
import com.example.hamkit.HamKitApplication
import com.example.hamkit.data.privacy.AmapPrivacyInitializer
import com.example.hamkit.data.privacy.PrivacyConsentStore
import com.example.hamkit.data.sstv.SstvLaunchIntent
import com.example.hamkit.data.sstv.SstvLaunchRequest
import com.example.hamkit.ui.component.bottombar.BottomBar
import com.example.hamkit.ui.component.bottombar.MainPagerState
import com.example.hamkit.ui.component.bottombar.SideRail
import com.example.hamkit.ui.component.bottombar.rememberMainPagerState
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.navigation3.Navigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.navigation3.rememberNavigator
import com.example.hamkit.ui.cw.CWPracticeRouteScreen
import com.example.hamkit.ui.screen.about.AboutScreen
import com.example.hamkit.ui.screen.colorpalette.ColorPaletteScreen
import com.example.hamkit.ui.screen.home.HomePager
import com.example.hamkit.ui.screen.location.LocationDetailScreen
import com.example.hamkit.ui.screen.permission.PermissionScreen
import com.example.hamkit.ui.screen.privacy.PrivacyConsentScreen
import com.example.hamkit.ui.screen.privacy.PrivacyPolicyScreen
import com.example.hamkit.ui.screen.reminder.ReminderListRouteScreen
import com.example.hamkit.ui.screen.satellite.SatelliteFilterScreen
import com.example.hamkit.ui.screen.satellite.SatelliteManagementScreen
import com.example.hamkit.ui.screen.satellite.SatelliteDetailScreen
import com.example.hamkit.ui.screen.satellite.SatelliteRadarScreen
import com.example.hamkit.ui.screen.satellite.SatelliteMapScreen
import com.example.hamkit.ui.screen.settings.SettingPager
import com.example.hamkit.ui.screen.aprs.AprsMainScreen
import com.example.hamkit.ui.screen.aprs.AprsSettingsScreen
import com.example.hamkit.ui.screen.aprs.AprsStationListScreen
import com.example.hamkit.ui.screen.aprs.AprsMapScreen
import com.example.hamkit.ui.screen.aprs.AprsMessageScreen
import com.example.hamkit.ui.screen.aprs.AprsSymbolPickerScreen
import com.example.hamkit.ui.screen.ft8.Ft8MainScreen
import com.example.hamkit.ui.screen.ft8.Ft8SettingsScreen
import com.example.hamkit.ui.screen.ft8.QsoLogScreen
import com.example.hamkit.ui.screen.sstv.HfFaxScreen
import com.example.hamkit.ui.screen.sstv.SstvGalleryScreen
import com.example.hamkit.ui.screen.sstv.SstvMainScreen
import com.example.hamkit.ui.screen.sstv.SstvSettingsScreen
import com.example.hamkit.ui.screen.sstv.SstvTxScreen
import com.example.hamkit.ui.screen.settings.SettingsScreenActions
import com.example.hamkit.ui.screen.settings.UpdateDialogs
import com.example.hamkit.ui.theme.HamKitTheme
import com.example.hamkit.ui.theme.LocalColorMode
import com.example.hamkit.ui.theme.LocalEnableBlur
import com.example.hamkit.ui.theme.LocalEnableFloatingBottomBar
import com.example.hamkit.ui.theme.LocalEnableFloatingBottomBarBlur
import com.example.hamkit.ui.theme.ThemeController
import com.example.hamkit.ui.util.rememberBlurBackdrop
import com.example.hamkit.ui.viewmodel.MainActivityViewModel
import com.example.hamkit.ui.viewmodel.MainPagerConfig
import com.example.hamkit.ui.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    /**
     * 待消费的「直达 SSTV 接收态」请求（二期 T8）。
     *
     * 由卫星过境提醒的通知点击带入（[SstvLaunchIntent]）。Compose 侧消费一次即清空，
     * 否则重组、配置变更或进程恢复都会重复把 SSTV 页压进返回栈。
     */
    private val pendingSstvLaunch = MutableStateFlow<SstvLaunchRequest?>(null)

    /**
     * 通知权限运行时请求（Android 13+）。
     *
     * POST_NOTIFICATIONS 在 Android 13+ 必须运行时申请，否则 notificationManager.notify
     * 会被系统静默丢弃。在 onCreate 阶段注册 launcher，进入主界面后立即触发一次请求。
     * 用户拒绝后不再自动重复打扰，可在"权限"页面手动重试。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 冷启动时由通知点击带入的「直达 SSTV 接收态」请求（二期 T8）
        consumeSstvLaunchIntent(intent)

        setContent {
            val context = LocalContext.current
            var consentGranted by remember {
                mutableStateOf(PrivacyConsentStore.isAccepted(context))
            }

            // 首启隐私闸门：同意前不创建业务 ViewModel、不请求权限、不初始化高德 SDK。
            if (!consentGranted) {
                // 闸门内也要有主题：隐私页按 UiMode 分发（默认 Miuix），
                // 且配色应跟随已有的颜色/暗色设置，不能退回 Material 默认紫。
                // 这里只读本地 UI 偏好（不含个人信息，不触发网络与 SDK），
                // 首启 prefs 为空 → 走默认 Miuix，与进入主界面后的观感一致。
                val bootUiMode = remember { ThemeController.getUiMode(context) }
                val bootAppSettings = remember { ThemeController.getAppSettings(context) }
                CompositionLocalProvider(LocalUiMode provides bootUiMode) {
                    HamKitTheme(appSettings = bootAppSettings, uiMode = bootUiMode) {
                        PrivacyConsentScreen(
                            onAgree = { aprsPublic ->
                                PrivacyConsentStore.accept(
                                    context = context,
                                    location = true,
                                    aprsPublic = aprsPublic,
                                )
                                AmapPrivacyInitializer.initialize(context)
                                consentGranted = true
                            },
                            onDecline = { (context as? Activity)?.finishAffinity() },
                        )
                    }
                }
                return@setContent
            }

            // 只有通过隐私闸门后才请求通知权限；定位/麦克风由各功能页在需要时请求。
            LaunchedEffect(Unit) { requestNotificationPermissionIfNeeded() }

            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val uiMode = uiState.uiMode
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }
            // Activity 级 MainViewModel：所有页面共享同一实例，退出子页面不丢失状态
            val mainViewModel = appViewModel<MainViewModel>()

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalUiMode provides uiMode,
                LocalMainViewModel provides mainViewModel,
            ) {
                HamKitTheme(appSettings = appSettings, uiMode = uiMode) {
                    // 全局返回键总兜底（HK-BUG-002）。
                    //
                    // 根因：应用自身没有任何 enabled 的返回处理器兜底 —— 主页时
                    // MainScreenBackHandler 的启用条件（栈深 1 且 selectedPage != 0）恒为 false，
                    // 子页返回又依赖 miuix 的 NavDisplay / navigationevent 回调链，
                    // 于是虚拟导航栏/手势返回在部分 ROM 上完全失效。
                    //
                    // 这里用 androidx.activity 的 BackHandler（注册到 Activity 的
                    // OnBackPressedDispatcher，完全不经过 navigationevent）作为最外层兜底：
                    // 它在手势/预测性返回与三键导航下都会被派发，且因为注册时机最早、
                    // 处于回调栈最外层，只有当更内层（NavDisplay / 子页面）都不消费时才触发。
                    //
                    // 优先级：子页出栈 → 主页面回第 0 页 → 再按返回才退出应用（防误触双击退出）。
                    RootBackHandler(navigator = navigator)

                    // 卫星过境提醒直达 SSTV 接收态（二期 T8）：把待消费请求转成一次导航。
                    // 先清空再 push —— 否则这次状态变化引发的重组会重复入栈。
                    val pendingSstv by pendingSstvLaunch.collectAsStateWithLifecycle()
                    LaunchedEffect(pendingSstv) {
                        val launch = pendingSstv ?: return@LaunchedEffect
                        pendingSstvLaunch.value = null
                        navigator.push(
                            Route.SstvMain(
                                presetModeName = launch.presetModeName,
                                autoStart = launch.autoStart,
                            ),
                        )
                    }

                    val mainScreenEntry = @Composable {
                        MainScreen(
                            initialPage = selectedMainPage,
                            onPageChanged = viewModel::setSelectedMainPage,
                        )
                    }

                    val navDisplay = @Composable {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            onBack = {
                                navigator.pop()
                            },
                            entryProvider = entryProvider {
                                entry<Route.Main> { WithApplicationViewModelStoreOwner { mainScreenEntry() } }
                                entry<Route.About> { WithApplicationViewModelStoreOwner { AboutScreen() } }
                                entry<Route.PrivacyPolicy> {
                                    WithApplicationViewModelStoreOwner {
                                        PrivacyPolicyScreen(onBack = { navigator.pop() })
                                    }
                                }
                                entry<Route.ColorPalette> { WithApplicationViewModelStoreOwner { ColorPaletteScreen() } }
                                entry<Route.Permissions> { WithApplicationViewModelStoreOwner { PermissionScreen() } }
                                entry<Route.Home> { WithApplicationViewModelStoreOwner { mainScreenEntry() } }
                                entry<Route.Settings> { WithApplicationViewModelStoreOwner { mainScreenEntry() } }
                                entry<Route.CWPractice> { WithApplicationViewModelStoreOwner { CWPracticeRouteScreen() } }
                                entry<Route.SatelliteManagement> { WithApplicationViewModelStoreOwner { SatelliteManagementScreen() } }
                                entry<Route.SatelliteFilter> { WithApplicationViewModelStoreOwner { SatelliteFilterScreen() } }
                                entry<Route.SatelliteDetail> { route ->
                                    WithApplicationViewModelStoreOwner {
                                        SatelliteDetailScreen(catalogNumber = route.catalogNumber, onBack = { navigator.pop() })
                                    }
                                }
                                entry<Route.SatelliteRadar> { route ->
                                    WithApplicationViewModelStoreOwner {
                                        SatelliteRadarScreen(catalogNumber = route.catalogNumber, onBack = { navigator.pop() })
                                    }
                                }
                                entry<Route.SatelliteMap> { route ->
                                    WithApplicationViewModelStoreOwner {
                                        SatelliteMapScreen(catalogNumber = route.catalogNumber, onBack = { navigator.pop() })
                                    }
                                }
                                entry<Route.ReminderList> { WithApplicationViewModelStoreOwner { ReminderListRouteScreen() } }
                                entry<Route.LocationDetail> { WithApplicationViewModelStoreOwner { LocationDetailScreen() } }
                                entry<Route.AprsMain> { WithApplicationViewModelStoreOwner { AprsMainScreen(onNavigate = { navigator.push(it) }, onNavigateBack = { navigator.pop() }) } }
                                entry<Route.AprsSettings> { WithApplicationViewModelStoreOwner { AprsSettingsScreen(onNavigateBack = { navigator.pop() }, onNavigate = { navigator.push(it) }) } }
                                entry<Route.AprsStations> { WithApplicationViewModelStoreOwner { AprsStationListScreen(onNavigateBack = { navigator.pop() }) } }
                                entry<Route.AprsMessages> { WithApplicationViewModelStoreOwner { AprsMessageScreen(onNavigateBack = { navigator.pop() }) } }
                                entry<Route.AprsMap> { WithApplicationViewModelStoreOwner { AprsMapScreen(onNavigateBack = { navigator.pop() }) } }
                                entry<Route.AprsSymbolPicker> { WithApplicationViewModelStoreOwner { AprsSymbolPickerScreen(onNavigateBack = { navigator.pop() }) } }
                                entry<Route.Ft8Main> { WithApplicationViewModelStoreOwner { Ft8MainScreen(onNavigate = { navigator.push(it) }, onNavigateBack = { navigator.pop() }) } }
                                entry<Route.Ft8Settings> { WithApplicationViewModelStoreOwner { Ft8SettingsScreen(onNavigateBack = { navigator.pop() }) } }
                                entry<Route.SstvMain> { route -> WithApplicationViewModelStoreOwner { SstvMainScreen(presetModeName = route.presetModeName, autoStart = route.autoStart, onNavigate = { navigator.push(it) }, onNavigateBack = { navigator.pop() }) } }
                                entry<Route.SstvSettings> { WithApplicationViewModelStoreOwner { SstvSettingsScreen(onNavigate = { navigator.push(it) }, onNavigateBack = { navigator.pop() }) } }
                                entry<Route.SstvGallery> { route -> WithApplicationViewModelStoreOwner { SstvGalleryScreen(pickForTx = route.pickForTx, onPicked = { navigator.pop() }, onNavigateBack = { navigator.pop() }) } }
                                entry<Route.SstvTx> { WithApplicationViewModelStoreOwner { SstvTxScreen(onNavigate = { navigator.push(it) }, onNavigateBack = { navigator.pop() }) } }
                                entry<Route.HfFax> { WithApplicationViewModelStoreOwner { HfFaxScreen(onNavigateBack = { navigator.pop() }) } }
                                entry<Route.QsoLogList> { WithApplicationViewModelStoreOwner { QsoLogScreen(onNavigateBack = { navigator.pop() }) } }
                            }
                        )
                    }

                    when (uiMode) {
                        UiMode.Material -> androidx.compose.material3.Scaffold { navDisplay() }
                        UiMode.Miuix -> Scaffold { navDisplay() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 应用已在运行时点通知：解析新的直达请求，由 Compose 侧的 LaunchedEffect 消费
        consumeSstvLaunchIntent(intent)
        // 消费后再 setIntent：此时 extra 已被剥离，重建后 getIntent() 不会再重复解析
        setIntent(intent)
    }

    /**
     * 解析「过境提醒 → SSTV 接收态」请求。
     *
     * 只认 [SstvLaunchIntent.isAutoStart] 为真的 Intent：普通启动（桌面图标、
     * 其他功能跳转）不带该 extra，因此不会误入 SSTV 页。
     */
    private fun consumeSstvLaunchIntent(intent: Intent?) {
        if (!SstvLaunchIntent.isAutoStart(intent)) return
        pendingSstvLaunch.value = SstvLaunchRequest(
            presetModeName = SstvLaunchIntent.presetModeName(intent),
            autoStart = true,
        )
        // 用后即剥离 extra（P1-6）：否则 Activity 重建时 onCreate 会重新解析同一
        // Intent，再次置位 pendingSstvLaunch 导致 SSTV 页重复入栈。
        intent?.removeExtra(SstvLaunchIntent.EXTRA_AUTO_START)
        intent?.removeExtra(SstvLaunchIntent.EXTRA_PRESET_MODE)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * 主页面分页状态。
 *
 * 由 [MainScreen] 提供，供底栏 / 侧边栏导航使用，因此保持**非空**合同
 * （这些组件在 [MainScreen] 内部组合，必然能取到值）。
 *
 * 注意：[RootBackHandler] 位于 [MainScreen] 之外，在「主页尚未渲染」的时序下访问本
 * CompositionLocal 会触发此处的 `error(...)`。**不要**直接读它，也不要用 `runCatching`
 * 包（AVD 实测该异常仍会逃逸并导致启动崩溃）；应先用
 * [androidx.compose.runtime.currentCompositionLocalContext] 判断是否存在，
 * 见 [rememberMainPagerStateOrNull]。
 */
val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

/**
 * Activity 级共享的 [MainViewModel]。
 *
 * Navigation3 的 NavDisplay 每个 entry 是独立的 ViewModelStoreOwner，若各页面分别
 * `appViewModel<MainViewModel>()` 会得到不同实例，退出页面时实例销毁导致内存态丢失。
 * 此 CompositionLocal 在 Activity 级创建唯一实例，所有页面共享同一状态。
 */
val LocalMainViewModel = staticCompositionLocalOf<MainViewModel> { error("LocalMainViewModel not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    // 更新对话框提升到此渲染：启动自动检查与设置页手动检查共用同一弹窗
    val settingsViewModel = appViewModel<SettingsViewModel>()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(pagerState)
    var userScrollEnabled by remember { mutableStateOf(true) }
    val uiMode = LocalUiMode.current
    val surfaceColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surface // Blur is not used in Material, this is just a placeholder
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navController)

    // 大屏适配：按「窗口宽度」而非「屏幕方向」决定导航形态。
    // 600dp 与 Material 大屏规范的 Medium 断点一致：平板竖屏、折叠屏展开、
    // 桌面/分屏窗口变宽时都进侧栏；分屏变窄（<600dp）时自动回到底部导航。
    // 宽屏下用户可显式选择侧栏 / 底部导航（见 WideNavStyle），避免开关隐式决定。
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val useNavigationRail = windowWidthDp >= WIDE_WINDOW_WIDTH_DP &&
        when (WideNavStyle.fromValue(settingsUiState.wideNavStyle)) {
            WideNavStyle.Rail -> true
            WideNavStyle.BottomBar -> false
            WideNavStyle.Auto -> !(uiMode == UiMode.Miuix && enableFloatingBottomBar)
        }

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = 1,
                    userScrollEnabled = userScrollEnabled,
                ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> HomePager(navController, bottomInnerPadding, isCurrentPage)
                        1 -> SettingPager(navController, bottomInnerPadding)
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold {
                    Row {
                        SideRail(
                            blurBackdrop = blurBackdrop,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }

                UiMode.Miuix -> Scaffold { _ ->
                    Row {
                        SideRail(
                            blurBackdrop = blurBackdrop,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }

                UiMode.Miuix -> Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
            }
        }

        // 更新对话框：覆盖主页与设置页，启动自动检查 / 设置页手动检查共用
        UpdateDialogs(
            uiState = settingsUiState,
            actions = SettingsScreenActions(
                onSetCheckUpdate = settingsViewModel::setCheckUpdate,
                onOpenTheme = { },
                onSetUiModeIndex = { },
                onOpenAbout = { },
                onCheckUpdateNow = { settingsViewModel.checkUpdateNow(force = true) },
                onDownloadAndInstall = settingsViewModel::downloadAndInstall,
                onClearUpdateResult = settingsViewModel::clearUpdateResult,
            ),
        )
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    // 只负责「主页面从第 N 页回到第 0 页」。主页再按一次返回（即已在第 0 页）由
    // RootBackHandler 兜底退出应用，避免这里出现「注册了 handler 但全部 disabled」的空窗。
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main &&
                navController.backStackSize() == 1 &&
                mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}

/**
 * 全局返回键总兜底（HK-BUG-002）。
 *
 * 见 [MainActivity.onCreate] 中的调用点注释：使用 `androidx.activity` 的 [BackHandler]，
 * 注册到 Activity 的 `OnBackPressedDispatcher`，不依赖 navigationevent 回调链，
 * 因此不会出现「有 handler 注册但全部 disabled」导致的返回键完全失效。
 *
 * **职责边界（AVD 实测教训）**：本函数只处理「返回栈出栈」与「退出应用」，
 * 刻意不访问 [LocalMainPagerState] —— 它由 `MainScreen` 提供，而本函数位于
 * `MainScreen` 之外；在「主页尚未渲染」的时序下访问会抛
 * `IllegalStateException: LocalMainPagerState not provided`（且 `runCatching` 也挡不住），
 * 曾导致启动即崩溃。
 *
 * 「主页面回第 0 页」由 `MainScreen` 内部的 [MainScreenBackHandler] 负责：
 * - 它在 `MainScreen` 内部组合，必然能取到分页状态；
 * - 它是 [BackHandler] 的**子节点**，注册时机晚于本函数，因此在
 *   `OnBackPressedDispatcher` 回调栈中位于更内层，启用时优先于本函数收到返回事件。
 *
 * 触发顺序：子页出栈（本函数）→ 主页面回第 0 页（MainScreenBackHandler）
 * → 双击返回退出应用（本函数）。
 */
@Composable
private fun RootBackHandler(navigator: Navigator) {
    val activity = LocalContext.current as? Activity
    val context = LocalContext.current

    // 上次「准备退出」的时间戳，仅用于双击返回退出的时间窗判断
    var lastExitBackAt by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = true) {
        if (navigator.backStackSize() > 1) {
            navigator.pop()
            return@BackHandler
        }

        val now = System.currentTimeMillis()
        if (now - lastExitBackAt <= EXIT_BACK_INTERVAL_MS) {
            activity?.finish()
        } else {
            lastExitBackAt = now
            Toast.makeText(
                context,
                context.getString(R.string.back_press_again_to_exit),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/** 双击返回退出的时间窗 */
private const val EXIT_BACK_INTERVAL_MS = 2000L

/** 大屏断点：窗口宽度 ≥ 该值（dp）时使用左侧常驻导航栏 */
private const val WIDE_WINDOW_WIDTH_DP = 600

/**
 * 包装 [ViewModelStoreOwner]，在其 [defaultViewModelCreationExtras] 中注入 [ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]。
 *
 * 根因：Navigation3 NavDisplay entry 的 ViewModelStoreOwner 默认不实现
 * [HasDefaultViewModelProviderFactory]，导致其 `defaultViewModelCreationExtras` 为空
 * （缺少 `APPLICATION_KEY`）。此时调用 `viewModel<AndroidViewModel 子类>()` 会抛
 * `IllegalArgumentException: CreationExtras must have an application by 'APPLICATION_KEY'`。
 *
 * 此 wrapper 作为防御层，确保任何 entry 内的 `viewModel()` 调用都能在 CreationExtras 中拿到
 * [Application]，从而彻底消除该崩溃（对普通 [androidx.lifecycle.ViewModel] 无副作用）。
 *
 * @param delegate 被包装的原始 ViewModelStoreOwner（通常是 NavDisplay entry 自带的 owner）
 * @param application 当前应用 Application 实例
 */
private class ApplicationKeyViewModelStoreOwner(
    private val delegate: ViewModelStoreOwner,
    private val application: Application,
) : ViewModelStoreOwner by delegate, HasDefaultViewModelProviderFactory {

    @Suppress("DEPRECATION") // AndroidViewModelFactory(application) 构造虽 deprecated 但仍可用，且能同时创建普通 ViewModel 与 AndroidViewModel
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (delegate as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
            ?: ViewModelProvider.AndroidViewModelFactory(application)

    override val defaultViewModelCreationExtras: CreationExtras
        get() {
            val base = (delegate as? HasDefaultViewModelProviderFactory)
                ?.defaultViewModelCreationExtras
                ?: CreationExtras.Empty
            return MutableCreationExtras(base).apply {
                set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
            }
        }
}

/**
 * 在当前组合作用域内提供一个注入了 `APPLICATION_KEY` 的 [ViewModelStoreOwner]。
 *
 * 用法：在 NavDisplay 的每个 `entry<Route.X> { ... }` 内容外层调用本函数，
 * 即可让该 entry 内所有 `viewModel<T>()` 调用拿到 Application。
 */
@Composable
private fun WithApplicationViewModelStoreOwner(content: @Composable () -> Unit) {
    val baseOwner = LocalViewModelStoreOwner.current
    val application = LocalContext.current.applicationContext as Application
    val owner = remember(baseOwner, application) {
        if (baseOwner == null) null
        else ApplicationKeyViewModelStoreOwner(baseOwner, application)
    }
    if (owner != null) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            content()
        }
    } else {
        content()
    }
}

/**
 * 创建带 [Application] 的 [ViewModelProvider.Factory]，确保 [viewModel] 调用时
 * [AndroidViewModelFactory] 能拿到 Application（即使 CreationExtras 为空）。
 *
 * 根因：Navigation3 NavDisplay entry 的 ViewModelStoreOwner 默认不实现
 * [HasDefaultViewModelProviderFactory]，`viewModel<T>()` 回退到
 * `AndroidViewModelFactory()`（无 Application），在 lifecycle 2.10 中
 * 即使 T 是普通 [ViewModel] 也会抛 `APPLICATION_KEY` 异常。
 *
 * 本函数通过传入显式 factory 绕过该问题，不依赖 [LocalViewModelStoreOwner]。
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
): VM {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val factory = remember { ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application) }
    return viewModel(key = key, factory = factory)
}

/**
 * 创建 Application 作用域的 [ViewModel]，跨页面/导航实例共享。
 *
 * 常规 [appViewModel] 的 ViewModel 作用域取决于当前 [LocalViewModelStoreOwner]，
 * Navigation3 每个 entry 有独立 owner，导致不同页面创建不同 ViewModel 实例。
 * 本函数使用 [HamKitApplication]（实现 [ViewModelStoreOwner]）作为 owner，
 * ViewModel 生命周期与 Application 一致，适合 APRS 等需跨页面持久运行的连接。
 */
@Composable
inline fun <reified VM : ViewModel> applicationScopedViewModel(
    key: String? = null,
): VM {
    val context = LocalContext.current
    val application = context.applicationContext as HamKitApplication
    @Suppress("DEPRECATION")
    val factory = remember { ViewModelProvider.AndroidViewModelFactory(application) }
    return viewModel(viewModelStoreOwner = application, key = key, factory = factory)
}
