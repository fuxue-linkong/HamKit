package com.example.hamkit.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.hamkit.R
import com.example.hamkit.permission.PermissionState
import com.example.hamkit.ui.WeatherCardMaterial
import com.example.hamkit.ui.theme.LocalCardAlpha
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun HomePagerMaterial(
    state: HomeUiState,
    businessState: HomeBusinessState,
    permissionState: PermissionState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = { TopBar(scrollBehavior = scrollBehavior) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        // 大屏适配：以「内容区实际宽度」（已扣除侧栏）判断列数，
        // 窄屏保持原有单列形态，避免手机端回归。
        BoxWithConstraints(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .fillMaxSize()
        ) {
            val columns = when {
                maxWidth >= HomeLayout.ThreeColumnMinWidth -> 3
                maxWidth >= HomeLayout.TwoColumnMinWidth -> 2
                else -> 1
            }
            if (columns == 1) {
                HomeCompactContentMaterial(permissionState, businessState, actions, bottomInnerPadding)
            } else {
                HomeWideContentMaterial(permissionState, businessState, actions, columns, bottomInnerPadding)
            }
        }
    }
}

/**
 * 窄屏（手机竖屏 / 窄分屏）主页：单列卡片流，保持既有形态。
 */
@Composable
private fun HomeCompactContentMaterial(
    permissionState: PermissionState,
    businessState: HomeBusinessState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 业务卡片全部是 material3 实现（含天气卡的 Material 变体），无需再包 MiuixTheme
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 条件卡：定位未授权 → 仅权限引导卡；定位已授权 → 时间排布 + 各功能入口
            if (permissionState.requiredGranted) {
                // 时间排布：时间卡（含每日言滚动）+ 紧贴的天气卡
                HomeHeaderMaterial(businessState, actions.onRefreshWeather)
                // 卫星列表卡：附近过境卫星
                SatelliteListCard(businessState, actions)
                // 定位详情入口
                LocationEntryCard(actions.onLocationDetailClick)
                CwEntryCard(actions.onCWPracticeClick)
                AprsEntryCard(actions.onAprsClick)
                Ft8EntryCard(actions.onFt8Click)
                // SSTV 慢扫描电视入口
                SstvEntryCard(actions.onSstvClick)
                // QSO 日志入口
                QsoLogEntryCard(actions.onQsoLogClick)
            } else {
                // 权限卡片：引导用户授权定位（未授权时隐藏其余卡片）
                PermissionCard(permissionState, actions.onPermissionsClick)
            }
        }
        Spacer(Modifier.height(bottomInnerPadding))
    }
}

/**
 * 宽屏（窗口 ≥600dp）主页：通栏时间卡 + 主列 / 侧列 + 底部入口行。
 *
 * 布局对齐平板设计稿：
 * - 时间天气卡通栏，承担页面主视觉；
 * - 卫星卡占主列（1.55 份宽），右侧按 2×2 摆放入口卡（三栏时侧列占两列）；
 * - 底部 SSTV / QSO 按主列比例收尾。
 */
@Composable
private fun HomeWideContentMaterial(
    permissionState: PermissionState,
    businessState: HomeBusinessState,
    actions: HomeActions,
    columns: Int,
    bottomInnerPadding: Dp,
) {
    val gap = HomeLayout.Gap
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = HomeLayout.MaxContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HomeLayout.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (!permissionState.requiredGranted) {
                // 未授权时只有引导卡，宽屏下铺满会显得空旷，限宽处理
                Box(
                    modifier = Modifier
                        .widthIn(max = HomeLayout.PermissionCardMaxWidth)
                        .fillMaxWidth()
                ) {
                    PermissionCard(permissionState, actions.onPermissionsClick)
                }
            } else {
                HomeHeaderMaterial(businessState, actions.onRefreshWeather)

                // 主列：卫星卡；右侧：功能入口 2×2，三栏时侧列占两列
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(modifier = Modifier.weight(1.55f)) {
                        SatelliteListCard(businessState, actions)
                    }
                    Column(
                        modifier = Modifier.weight(if (columns >= 3) 2f else 1f),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            Box(Modifier.weight(1f)) { LocationEntryCard(actions.onLocationDetailClick) }
                            Box(Modifier.weight(1f)) { CwEntryCard(actions.onCWPracticeClick) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            Box(Modifier.weight(1f)) { AprsEntryCard(actions.onAprsClick) }
                            Box(Modifier.weight(1f)) { Ft8EntryCard(actions.onFt8Click) }
                        }
                    }
                }

                // 底部入口行：SSTV 宽、QSO 窄，与上方主列宽度呼应
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(if (columns >= 3) 2f else 1.55f)) { SstvEntryCard(actions.onSstvClick) }
                    Box(Modifier.weight(1f)) { QsoLogEntryCard(actions.onQsoLogClick) }
                }
            }
            Spacer(Modifier.height(bottomInnerPadding))
        }
    }
}

/**
 * 主页时间排布（来自 main 分支 HomeHeader，适配 Material 主题）。
 *
 * 顶部为时间卡：本地时间（大字号）+ 右侧日期（星期 / 年 月 日，"日"放大）+ UTC 时间 +
 * 每日一言水平滚动；时间卡正下方紧贴天气卡。背景色与定位状态色联动。
 * 颜色使用 MaterialTheme.colorScheme，与天气卡的 Material 变体保持视觉一致。
 */
@Composable
private fun HomeHeaderMaterial(
    state: HomeBusinessState,
    onRefreshWeather: () -> Unit,
) {
    // 时钟每秒刷新，仅在此组件内部持有，避免 1s tick 触发整个主页重组
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }

    val zonedNow = now.atZone(ZoneId.systemDefault())
    val localTime = zonedNow.format(timeFormatter)
    val utcTime = now.atZone(ZoneOffset.UTC).format(timeFormatter)

    val hasBackground = state.timeCardBackgroundFile != null && state.timeCardBackgroundFile.exists()
    val whiteText = Color.White
    val whiteTextSecondary = Color.White.copy(alpha = 0.85f)
    val whiteTextDim = Color.White.copy(alpha = 0.75f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 时间 + 天气合并卡：本地时间 → UTC → 天气 → 每日一言
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        ) {
            if (hasBackground) {
                val file = state.timeCardBackgroundFile!!
                val context = androidx.compose.ui.platform.LocalContext.current
                // remember ImageRequest，避免每秒时钟重组时重建请求；key 含 lastModified+length 防缓存碰撞
                val request = remember(file, file.lastModified(), file.length()) {
                    ImageRequest.Builder(context)
                        .data(file)
                        .crossfade(true)
                        .memoryCacheKey("timecard_${file.lastModified()}_${file.length()}")
                        .diskCacheKey("timecard_${file.lastModified()}_${file.length()}")
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to state.timeCardMaskColor,
                                0.25f to state.timeCardMaskColor.copy(alpha = 0.85f),
                                0.5f to state.timeCardMaskColor.copy(alpha = 0.45f),
                                0.8f to state.timeCardMaskColor.copy(alpha = 0.3f),
                                1.0f to state.timeCardMaskColor.copy(alpha = 0.3f)
                            )
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val timeColor = if (hasBackground) {
                    whiteText
                } else {
                    if (state.weather != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                }
                val secondaryColor = if (hasBackground) whiteTextSecondary else MaterialTheme.colorScheme.onSurfaceVariant

                // 本地时间
                Text(
                    text = localTime,
                    fontSize = LOCAL_TIME_FONT_SIZE.sp,
                    color = timeColor
                )
                // UTC 时间
                Text(
                    text = "$utcTime UTC",
                    fontSize = UTC_TIME_FONT_SIZE.sp,
                    color = secondaryColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                )
                // 天气内容：禁用自带背景，复用本卡背景，注入 Material 主题色
                WeatherCardMaterial(
                    weather = state.weather,
                    isLoading = state.weatherLoading,
                    error = state.weatherError,
                    onRefresh = onRefreshWeather,
                    modifier = Modifier.fillMaxWidth(),
                    stateColor = if (hasBackground) whiteText else (if (state.weather != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }),
                    secondaryTextColor = if (hasBackground) whiteTextSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    applyBackground = false,
                    temperatureFontSize = WEATHER_TEMP_FONT_SIZE
                )
                DailyQuoteScroller(
                    quote = state.dailyQuote,
                    contentColor = if (hasBackground) whiteTextDim else secondaryColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun PermissionCard(
    state: PermissionState,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor =
                if (state.requiredGranted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            contentColor =
                if (state.requiredGranted) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permission_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            if (state.requiredGranted) {
                                stringResource(R.string.permission_ready)
                            } else {
                                stringResource(R.string.permission_missing)
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AssistChip(
                    onClick = { },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor =
                            if (state.requiredGranted) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        leadingIconContentColor =
                            if (state.requiredGranted) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                    ),
                    label = {
                        Text(
                            if (state.requiredGranted) {
                                stringResource(R.string.permission_granted)
                            } else {
                                stringResource(R.string.permission_action_required)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector =
                                if (state.requiredGranted) Icons.Default.CheckCircle
                                else Icons.Default.ErrorOutline,
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LocationEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.location_detail_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.location_detail_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SatelliteListCard(
    state: HomeBusinessState,
    actions: HomeActions,
) {
    val sats = state.satellites
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.nearby_satellites),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = actions.onSatelliteManagementClick) {
                    Text(stringResource(R.string.satellite_management))
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                sats.isSatelliteLoading && sats.satellites.isEmpty() -> Text(
                    text = stringResource(R.string.processing),
                    style = MaterialTheme.typography.bodyMedium
                )
                sats.satelliteError != null && sats.satellites.isEmpty() -> Text(
                    text = stringResource(R.string.satellite_load_failed, sats.satelliteError),
                    color = MaterialTheme.colorScheme.error
                )
                sats.satellites.isEmpty() -> Text(
                    text = stringResource(R.string.no_satellites),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> {
                    val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
                        .withZone(ZoneId.systemDefault())
                    sats.satellites.take(5).forEach { sat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sat.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${sat.modes} · ${if (sat.isCurrentlyVisible) stringResource(R.string.in_pass) else stringResource(R.string.upcoming)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.aos_time) + ": " + timeFormatter.format(sat.aosTime),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.max_elevation) + ": ${sat.maxElevation.toInt()}°",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CwEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.cw_practice),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.cw_practice_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AprsEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.aprs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.aprs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Ft8EntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ft8),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ft8_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
private fun SstvEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.sstv),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sstv_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QsoLogEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.qso_settings_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.qso_settings_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

