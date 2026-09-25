package com.example.hamkit.ui.screen.satellite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteCatalog
import com.example.hamkit.data.satellite.SatelliteCategoryConfig
import com.example.hamkit.data.satellite.SatelliteInfo
import com.example.hamkit.data.satellite.SatelliteListItem
import com.example.hamkit.data.satellite.SatelliteStatusSegmenter
import com.example.hamkit.data.satellite.SegmentStatus
import com.example.hamkit.data.satellite.SatelliteStatusTracker
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.applyFilterToItems
import com.example.hamkit.ui.isSatelliteSourceExpired
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.theme.LocalCardAlpha
import com.example.hamkit.ui.theme.LocalEnableBlur
import com.example.hamkit.ui.theme.SafeColors
import com.example.hamkit.ui.util.BlurredBar
import com.example.hamkit.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 卫星管理页入口：按当前界面风格分发到 Miuix / Material 实现。
 */
@Composable
fun SatelliteManagementScreen() {
    when (com.example.hamkit.ui.LocalUiMode.current) {
        com.example.hamkit.ui.UiMode.Miuix -> SatelliteManagementMiuix()
        com.example.hamkit.ui.UiMode.Material -> SatelliteManagementMaterial()
    }
}

/**
 * 卫星管理页 Miuix 风格：展示附近过境卫星，支持筛选、分类、实时 AMSAT 状态、
 * BJT 分段时间线、在境倒计时等。
 */
@Composable
fun SatelliteManagementMiuix() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val locationState by mainViewModel.locationState
    val satelliteState by mainViewModel.satelliteState
    val categoryConfig by mainViewModel.satelliteCategoryConfig
    val filter by mainViewModel.satelliteFilter
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showCategoryManager by rememberSaveable { mutableStateOf(false) }
    var categoryPickerTarget by remember { mutableStateOf<Int?>(null) }

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                top.yukonga.miuix.kmp.basic.TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.satellite_management),
                    navigationIcon = {
                        Box(modifier = Modifier.padding(start = 12.dp)) {
                            IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        SatelliteManagementContent(
            locationState = locationState,
            satelliteState = satelliteState,
            satelliteItems = mainViewModel.satelliteItems,
            filter = filter,
            categoryConfig = categoryConfig,
            statusTracker = mainViewModel.statusTracker,
            onSatelliteClick = { catalogNumber ->
                navigator.push(Route.SatelliteDetail(catalogNumber))
            },
            onNameQueryChange = { query ->
                mainViewModel.updateSatelliteFilter(filter.copy(nameQuery = query))
            },
            onShowFilterDialog = { showFilterDialog = true },
            onShowCategoryManager = { showCategoryManager = true },
            onShowCategoryPicker = { catalogNumber -> categoryPickerTarget = catalogNumber },
            onToggleReminder = mainViewModel::toggleSatelliteReminder,
            onGetLocation = mainViewModel::refreshLocationOnly,
            onUpdateSource = mainViewModel::refreshSatelliteSourceOnly,
            contentPadding = innerPadding
        )

        if (showFilterDialog) {
            SatelliteFilterDialogMiuix(
                show = showFilterDialog,
                onDismissRequest = { showFilterDialog = false }
            )
        }

        if (showCategoryManager) {
            SatelliteCategoryManagerDialogMiuix(
                show = showCategoryManager,
                onDismissRequest = { showCategoryManager = false }
            )
        }

        categoryPickerTarget?.let { target ->
            SatelliteCategoryPickerDialogMiuix(
                show = true,
                catalogNumber = target,
                onDismissRequest = { categoryPickerTarget = null }
            )
        }
    }
}

@Composable
private fun SatelliteManagementContent(
    locationState: com.example.hamkit.ui.LocationUiState,
    satelliteState: com.example.hamkit.ui.SatelliteUiState,
    satelliteItems: List<SatelliteListItem>,
    filter: com.example.hamkit.ui.SatelliteFilter,
    categoryConfig: SatelliteCategoryConfig,
    statusTracker: SatelliteStatusTracker,
    onSatelliteClick: (Int) -> Unit,
    onNameQueryChange: (String) -> Unit,
    onShowFilterDialog: () -> Unit,
    onShowCategoryManager: () -> Unit,
    onShowCategoryPicker: (Int) -> Unit,
    onToggleReminder: (Int) -> Unit,
    onGetLocation: () -> Unit,
    onUpdateSource: () -> Unit,
    contentPadding: PaddingValues
) {
    // 订阅 AMSAT 状态字典，状态变化时触发重组以更新 effectiveStatus / isInherited
    @Suppress("UnusedVariable")
    val statusEntries = statusTracker.statusMap.value

    // 分类 id → 名称，用于列表项展示已归入的分类
    val categoryNames = remember(categoryConfig) {
        categoryConfig.categories.associate { it.id to it.name }
    }

    // 应用筛选（基于转发器模式 + TLE 全量 + 分类归属）
    val filteredSatellites = remember(satelliteItems, filter, categoryConfig) {
        satelliteItems.applyFilterToItems(filter, categoryConfig.membership)
    }
    val totalCount = satelliteItems.size
    val categorizedNumbers = categoryConfig.categorizedCatalogNumbers
    // 预计算已分类数量：列表规模可达 1.6 万，且本页存在 5 秒一次的倒计时重组
    val categorizedCount = remember(satelliteItems, categorizedNumbers) {
        satelliteItems.count { it.catalogNumber in categorizedNumbers }
    }

    // 统一倒计时时钟：仅当有在境卫星时每 5 秒更新，避免每颗卫星各自 LaunchedEffect
    var inPassNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasInPassSatellites = filteredSatellites.any { it.isCurrentlyVisible }
    LaunchedEffect(hasInPassSatellites) {
        if (hasInPassSatellites) {
            while (true) {
                inPassNowMillis = System.currentTimeMillis()
                delay(5000)
            }
        }
    }

    // 预计算每颗卫星的有效状态（含延续标记），避免 items 块内逐项查询触发重组
    val statusCache = remember(statusEntries, filteredSatellites) {
        filteredSatellites.associate { sat ->
            val amsatName = SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[sat.catalogNumber]
            val statusQuery = if (amsatName != null) statusTracker.queryStatus(amsatName) else null
            val effectiveStatus = statusQuery?.status?.takeIf { it.isNotBlank() } ?: sat.status
            val isInherited = statusQuery?.isInherited ?: false
            sat.catalogNumber to (effectiveStatus to isInherited)
        }
    }

    // 排序：已分类优先 → 在境优先 → 有活跃转发器 → AOS 升序（无过境的排最后）
    val sortedSatellites = remember(filteredSatellites, categorizedNumbers) {
        filteredSatellites.sortedWith(
            compareByDescending<SatelliteListItem> { it.catalogNumber in categorizedNumbers }
                .thenByDescending { it.isCurrentlyVisible }
                .thenByDescending { it.hasActiveTransmitter }
                .thenBy { it.pass?.aosTime ?: Instant.MAX }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        overscrollEffect = null,
    ) {
        // 数据源刷新 + 统计 + 筛选入口合并为一张卡（减少卡片间距，列表起点上移）
        item {
            SatelliteOverviewCard(
                isLoading = locationState.isLoading,
                isSatelliteLoading = satelliteState.isSatelliteLoading,
                lastLocationTime = locationState.lastLocationUpdateTime,
                lastLocationCity = locationState.lastLocationCity,
                lastSatelliteTime = satelliteState.lastSatelliteUpdateTime,
                totalCount = totalCount,
                filteredCount = filteredSatellites.size,
                categorizedCount = categorizedCount,
                categoryCount = categoryConfig.categories.size,
                filter = filter,
                onNameQueryChange = onNameQueryChange,
                onShowFilterDialog = onShowFilterDialog,
                onShowCategoryManager = onShowCategoryManager,
                onGetLocation = onGetLocation,
                onUpdateSource = onUpdateSource
            )
        }

        // 占位态 / 列表
        when {
            satelliteState.isSatelliteLoading && filteredSatellites.isEmpty() -> {
                item { SatellitePlaceholderCard { Text(stringResource(R.string.processing)) } }
            }
            satelliteState.satelliteError != null -> {
                item {
                    SatellitePlaceholderCard {
                        Text(
                            text = stringResource(R.string.satellite_load_failed, satelliteState.satelliteError),
                            color = colorScheme.onError
                        )
                    }
                }
            }
            locationState.result == null -> {
                item { SatellitePlaceholderCard { Text(stringResource(R.string.satellite_need_location)) } }
            }
            filteredSatellites.isEmpty() -> {
                item {
                    SatellitePlaceholderCard {
                        Text(
                            stringResource(
                                if (filter.isActive) R.string.no_satellites_filtered
                                else R.string.no_satellites
                            )
                        )
                    }
                }
            }
            else -> {
                items(items = sortedSatellites, key = { it.catalogNumber }) { sat ->
                    val (effectiveStatus, isInherited) = statusCache[sat.catalogNumber]
                        ?: (sat.status to false)
                    SatelliteManagementItem(
                        satellite = sat,
                        effectiveStatus = effectiveStatus,
                        assignedCategoryNames = categoryConfig
                            .categoryIdsOf(sat.catalogNumber)
                            .mapNotNull { categoryNames[it] }
                            .sorted(),
                        reminderEnabled = sat.catalogNumber in categoryConfig.reminderFlags,
                        isStatusInherited = isInherited,
                        nowMillis = if (sat.isCurrentlyVisible) inPassNowMillis else 0L,
                        statusSegments = satelliteState.segmentStatuses[sat.catalogNumber],
                        onOpenCategoryPicker = { onShowCategoryPicker(sat.catalogNumber) },
                        onToggleReminder = { onToggleReminder(sat.catalogNumber) },
                        onSatelliteClick = { onSatelliteClick(sat.catalogNumber) }
                    )
                }
            }
        }
    }
}

// ---- 概览卡（数据源刷新 + 统计 + 筛选入口合并）----

@Composable
private fun SatelliteOverviewCard(
    isLoading: Boolean,
    isSatelliteLoading: Boolean,
    lastLocationTime: Instant?,
    lastLocationCity: String,
    lastSatelliteTime: Instant?,
    totalCount: Int,
    filteredCount: Int,
    categorizedCount: Int,
    categoryCount: Int,
    filter: com.example.hamkit.ui.SatelliteFilter,
    onNameQueryChange: (String) -> Unit,
    onShowFilterDialog: () -> Unit,
    onShowCategoryManager: () -> Unit,
    onGetLocation: () -> Unit,
    onUpdateSource: () -> Unit
) {
    val dateTimeFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
    val zoneId = remember { ZoneId.systemDefault() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 名称搜索框（移自筛选页，置于顶部最易触达位置）
            top.yukonga.miuix.kmp.basic.TextField(
                value = filter.nameQuery,
                onValueChange = onNameQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.filter_search_hint),
                singleLine = true
            )

            // 描述 + 统计
            Text(
                text = stringResource(R.string.satellite_management_desc),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ManagementStat(
                    label = stringResource(R.string.satellite_count, totalCount),
                    value = totalCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                ManagementStat(
                    label = stringResource(R.string.satellite_categorized_count),
                    value = categorizedCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                ManagementStat(
                    label = stringResource(R.string.satellite_category_count),
                    value = categoryCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            // 筛选 / 分类入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 筛选按钮：始终固定在左侧
                SatelliteFilterButton(
                    filter = filter,
                    onClick = onShowFilterDialog
                )

                // 分类入口：紧邻筛选按钮，视觉语言一致
                SatelliteCategoryButton(
                    active = filter.categoryIds.isNotEmpty(),
                    onClick = onShowCategoryManager
                )

                // 计数文字：激活时从按钮右侧淡入，不改变按钮位置
                androidx.compose.animation.AnimatedVisibility(
                    modifier = Modifier.weight(1f, fill = false),
                    visible = filter.isActive && totalCount > 0,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally()
                ) {
                    Text(
                        text = stringResource(R.string.satellite_count_filtered, filteredCount, totalCount),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            // 数据源刷新操作
            ActionRow(
                buttonText = stringResource(R.string.sat_action_get_location),
                isLoading = isLoading,
                enabled = !isLoading,
                onClick = onGetLocation,
                primaryText = if (lastLocationTime != null) {
                    lastLocationTime.atZone(zoneId).format(dateTimeFormatter)
                } else {
                    stringResource(R.string.sat_no_location_time)
                },
                secondaryText = lastLocationCity.ifBlank { stringResource(R.string.sat_no_city) }
            )
            ActionRow(
                buttonText = stringResource(R.string.sat_action_update_source),
                isLoading = isSatelliteLoading,
                enabled = !isSatelliteLoading,
                onClick = onUpdateSource,
                primaryText = if (lastSatelliteTime != null) {
                    lastSatelliteTime.atZone(zoneId).format(dateTimeFormatter)
                } else {
                    stringResource(R.string.sat_no_source_time)
                },
                secondaryText = if (isSatelliteSourceExpired(lastSatelliteTime)) {
                    stringResource(R.string.sat_source_expired)
                } else {
                    stringResource(R.string.sat_source_fresh)
                },
                secondaryColor = if (isSatelliteSourceExpired(lastSatelliteTime)) {
                    colorScheme.onError
                } else {
                    colorScheme.onSurfaceVariantSummary
                }
            )
        }
    }
}

@Composable
private fun ActionRow(
    buttonText: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    primaryText: String,
    secondaryText: String,
    secondaryColor: Color = colorScheme.onSurfaceVariantSummary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(buttonText)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primaryText,
                fontSize = 14.sp,
                color = colorScheme.onSurface
            )
            Text(
                text = secondaryText,
                fontSize = 12.sp,
                color = secondaryColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ManagementStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.primary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary
        )
    }
}

// ---- 筛选入口 ----

@Composable
private fun SatelliteFilterButton(
    filter: com.example.hamkit.ui.SatelliteFilter,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.FilterList,
            contentDescription = null,
            tint = if (filter.isActive) colorScheme.primary else colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = stringResource(R.string.filter_title),
            fontSize = 13.sp,
            color = if (filter.isActive) colorScheme.primary else colorScheme.onSurfaceVariantSummary
        )
        if (filter.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary)
            )
        }
    }
}

// ---- 分类入口 ----

/**
 * 分类入口按钮：视觉语言与 [SatelliteFilterButton] 完全一致
 * （图标 + 文字 + 激活小圆点），保证两个入口并排时不突兀。
 *
 * @param active 当前是否按分类筛选（有选中分类时高亮）
 */
@Composable
private fun SatelliteCategoryButton(
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Category,
            contentDescription = null,
            tint = if (active) colorScheme.primary else colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = stringResource(R.string.satellite_category_title),
            fontSize = 13.sp,
            color = if (active) colorScheme.primary else colorScheme.onSurfaceVariantSummary
        )
        if (active) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary)
            )
        }
    }
}

// ---- 卫星列表项 ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SatelliteManagementItem(
    satellite: SatelliteListItem,
    effectiveStatus: String,
    assignedCategoryNames: List<String>,
    reminderEnabled: Boolean,
    isStatusInherited: Boolean,
    nowMillis: Long,
    statusSegments: List<SegmentStatus>?,
    onOpenCategoryPicker: () -> Unit,
    onToggleReminder: () -> Unit,
    onSatelliteClick: () -> Unit
) {
    // 分段时间线默认折叠，点击展开按钮展开
    var expanded by rememberSaveable(satellite.catalogNumber) { mutableStateOf(false) }

    // 过境信息（无过境的卫星不显示时间徽章）
    val pass = satellite.pass

    val timeInfo = remember(pass?.aosTime, pass?.losTime, pass?.isCurrentlyVisible, nowMillis) {
        val formatter = satelliteTimeFormatter
        val zone = ZoneId.systemDefault()
        when {
            pass == null -> null
            pass.isCurrentlyVisible -> {
                val losTime = pass.losTime.atZone(zone).format(formatter)
                val now = if (nowMillis > 0) Instant.ofEpochMilli(nowMillis) else Instant.now()
                val remainingSeconds = Duration.between(now, pass.losTime).seconds
                SatelliteTimeInfo.InPass(losTime, formatRemainingTime(remainingSeconds))
            }
            else -> SatelliteTimeInfo.Upcoming(pass.aosTime.atZone(zone).format(formatter))
        }
    }

    // 强调色：已归入分类用 tertiaryContainer 背景；在境仅加 primary 边框，背景保持 surface
    val hasCategory = assignedCategoryNames.isNotEmpty()
    val cardContainerColor = when {
        hasCategory -> colorScheme.tertiaryContainer
        else -> colorScheme.surface
    }
    val cardContentColor = when {
        hasCategory -> colorScheme.onTertiaryContainer
        else -> colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (pass?.isCurrentlyVisible == true) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onSatelliteClick),
        colors = CardDefaults.defaultColors(
            color = cardContainerColor.copy(alpha = LocalCardAlpha.current),
            contentColor = cardContentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 第一行：名 + 仰角 + 分类 + 提醒 + 展开按钮（点击卡片进入详情页）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = satellite.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = cardContentColor,
                        maxLines = 1
                    )
                    Text(
                        text = "#%05d".format(Locale.US, satellite.catalogNumber),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (pass != null) {
                        Text(
                            text = "${pass.maxElevation.toInt()}°",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cardContentColor
                        )
                    }
                    IconButton(onClick = onOpenCategoryPicker) {
                        Icon(
                            imageVector = Icons.Rounded.Category,
                            contentDescription = stringResource(R.string.satellite_category_title),
                            tint = if (hasCategory) colorScheme.onTertiaryContainer else colorScheme.onSurfaceVariantSummary
                        )
                    }
                    // 过境提醒开关：可直接切换，避免「开关只读、无法发现」的问题
                    IconButton(onClick = onToggleReminder) {
                        Icon(
                            imageVector = if (reminderEnabled) {
                                Icons.Rounded.Notifications
                            } else {
                                Icons.Rounded.NotificationsNone
                            },
                            contentDescription = stringResource(
                                if (reminderEnabled) {
                                    R.string.satellite_reminder_on
                                } else {
                                    R.string.satellite_reminder_off
                                }
                            ),
                            tint = if (reminderEnabled) {
                                colorScheme.primary
                            } else {
                                colorScheme.onSurfaceVariantSummary
                            }
                        )
                    }
                    // 转发器/时间线展开按钮（卡片点击已改为进入详情页）
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Chips 流：Source / Status / Mode
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (effectiveStatus.isNotEmpty()) {
                    StatusChip(status = effectiveStatus, isStatusInherited = isStatusInherited)
                }
                // 已归入的分类：最多显示 3 个，其余用 +N 表示
                if (assignedCategoryNames.isNotEmpty()) {
                    assignedCategoryNames.take(3).forEach { name -> CategoryChip(name = name) }
                    if (assignedCategoryNames.size > 3) {
                        CategoryChip(name = "+${assignedCategoryNames.size - 3}")
                    }
                }
                val modes = satellite.effectiveModes
                if (modes.isEmpty()) {
                    ModeChip(mode = stringResource(R.string.mode_unknown))
                } else {
                    // 最多显示 4 个模式 chip，其余用 +N 表示
                    modes.take(4).forEach { mode -> ModeChip(mode = mode) }
                    if (modes.size > 4) {
                        ModeChip(mode = "+${modes.size - 4}")
                    }
                }
                // 转发器数量徽章（多收发器提示）
                if (satellite.radios.isNotEmpty()) {
                    TransceiverCountChip(
                        active = satellite.radios.count { it.isActive },
                        total = satellite.radios.size
                    )
                }
            }

            // 展开区：转发器列表 + BJT 分段状态时间线
            if (expanded) {
                if (satellite.radios.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    TransceiverList(satellite.radios)
                }
                if (statusSegments != null) {
                    Spacer(Modifier.height(10.dp))
                    SatelliteStatusSegments(statusSegments)
                }
            }

            Spacer(Modifier.height(10.dp))

            // 时间徽章 + 在境进度条（无过境的卫星显示占位）
            when (timeInfo) {
                is SatelliteTimeInfo.InPass -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TimeBadge(
                            label = stringResource(R.string.los_time),
                            value = timeInfo.losTime,
                            isActive = true
                        )
                        TimeBadge(
                            label = stringResource(R.string.time_remaining),
                            value = timeInfo.remainingText,
                            isActive = true
                        )
                    }
                }
                is SatelliteTimeInfo.Upcoming -> {
                    TimeBadge(
                        label = stringResource(R.string.aos_time),
                        value = timeInfo.aosTime,
                        isActive = false
                    )
                }
                null -> {
                    TimeBadge(
                        label = stringResource(R.string.no_pass_window),
                        value = stringResource(R.string.no_pass_hint),
                        isActive = false
                    )
                }
            }
        }
    }
}

/**
 * 转发器数量徽章：active 数量 / 总数（如 "📻 1/3"）。
 */
@Composable
private fun TransceiverCountChip(active: Int, total: Int) {
    val (bgColor, contentColor) = if (active > 0) {
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer
    } else {
        colorScheme.surfaceVariant to colorScheme.onSurfaceVariantSummary
    }
    Chip(text = stringResource(R.string.transceiver_count, active, total), bgColor = bgColor, contentColor = contentColor)
}

/**
 * 展开区：该卫星的全部转发器（每个转发器一行：名称 + 频率 + 模式 + 状态）。
 */
@Composable
private fun TransceiverList(radios: List<RadioInfo>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        radios.forEach { radio ->
            TransceiverRow(radio)
        }
    }
}

/**
 * 单条转发器行：名称（含倒置标记）+ 上下行频率 + 模式 + 状态徽章。
 */
@Composable
private fun TransceiverRow(radio: RadioInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val title = if (radio.inverted) "INV: ${radio.displayName}" else radio.displayName
            Text(
                text = title.ifBlank { stringResource(R.string.transceiver_unnamed) },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
                maxLines = 1
            )
            RadioStatusBadge(radio)
        }
        // 频率 + 模式行
        val freqText = buildString {
            if (radio.downlinkHz != null) {
                append("RX ${formatMHz(radio.downlinkHz)}")
            }
            if (radio.uplinkHz != null) {
                if (isNotEmpty()) append("  ")
                append("TX ${formatMHz(radio.uplinkHz)}")
            }
            if (isNotEmpty() && radio.displayMode.isNotBlank()) append("  ")
            if (radio.displayMode.isNotBlank()) append(radio.displayMode)
        }
        if (freqText.isNotEmpty()) {
            Text(
                text = freqText,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 转发器状态徽章：活跃（绿）/ 停用（灰）/ 未来（蓝） */
@Composable
private fun RadioStatusBadge(radio: RadioInfo) {
    val (label, bgColor, contentColor) = when (radio.status) {
        RadioInfo.STATUS_ACTIVE ->
            Triple(
                stringResource(R.string.transceiver_active),
                colorScheme.primaryContainer,
                colorScheme.onPrimaryContainer
            )
        RadioInfo.STATUS_FUTURE ->
            Triple(
                stringResource(R.string.transceiver_future),
                colorScheme.secondaryContainer,
                colorScheme.onSecondaryContainer
            )
        else ->
            Triple(
                stringResource(R.string.transceiver_inactive),
                colorScheme.surfaceVariant,
                colorScheme.onSurfaceVariantSummary
            )
    }
    Chip(text = label, bgColor = bgColor, contentColor = contentColor)
}

/** 频率（Hz）→ 可读文本（MHz，3 位小数） */
private fun formatMHz(hz: Long): String = "%.3f".format(Locale.US, hz / 1_000_000.0)

// ---- 时间相关辅助 ----

private val satelliteTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private sealed class SatelliteTimeInfo {
    data class InPass(val losTime: String, val remainingText: String) : SatelliteTimeInfo()
    data class Upcoming(val aosTime: String) : SatelliteTimeInfo()
}

private fun formatRemainingTime(seconds: Long): String {
    if (seconds <= 0) return "0秒"
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) "${minutes}分${remainingSeconds}秒" else "${remainingSeconds}秒"
}

@Composable
private fun TimeBadge(label: String, value: String, isActive: Boolean) {
    val containerColor = if (isActive) {
        colorScheme.primary.copy(alpha = 0.15f)
    } else {
        colorScheme.surfaceVariant
    }
    val contentColor = if (isActive) colorScheme.primary else colorScheme.onSurfaceVariantSummary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$label：",
            fontSize = 11.sp,
            color = contentColor
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

// ---- Chips ----

@Composable
private fun StatusChip(status: String, isStatusInherited: Boolean = false) {
    val baseText = when (status) {
        "Heard" -> stringResource(R.string.status_heard)
        "Telemetry Only" -> stringResource(R.string.status_telemetry_only)
        "Not Heard" -> stringResource(R.string.status_not_heard)
        "Crew Active" -> stringResource(R.string.status_crew_active)
        else -> status
    }
    val displayText = if (isStatusInherited) "$baseText *" else baseText
    val (bgColor, contentColor) = when (status) {
        "Heard" -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        "Telemetry Only" -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
        "Not Heard" -> SafeColors.errorContainer to SafeColors.errorIcon
        "Crew Active" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        else -> colorScheme.surfaceVariant to colorScheme.onSurfaceVariantSummary
    }
    val finalBg = if (isStatusInherited) bgColor.copy(alpha = 0.85f) else bgColor
    val finalContent = if (isStatusInherited) contentColor.copy(alpha = 0.85f) else contentColor
    Chip(text = displayText, bgColor = finalBg, contentColor = finalContent)
}

@Composable
private fun ModeChip(mode: String) {
    val (bgColor, contentColor) = when (mode.uppercase()) {
        "FM" -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        "SSTV" -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
        "DSTAR" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        "CW" -> SafeColors.errorContainer to SafeColors.errorIcon
        else -> colorScheme.surfaceVariant to colorScheme.onSurfaceVariantSummary
    }
    Chip(text = mode, bgColor = bgColor, contentColor = contentColor)
}

/** 分类名称徽章：与模式徽章区分，使用 tertiary 色系。 */
@Composable
private fun CategoryChip(name: String) {
    Chip(
        // 已分类卡片的容器本身就是 tertiaryContainer，徽章必须换色才能看出边界
        text = name,
        bgColor = colorScheme.surfaceVariant,
        contentColor = colorScheme.onSurfaceVariantSummary
    )
}

@Composable
private fun Chip(text: String, bgColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---- BJT 分段状态时间线 ----

@Composable
private fun SatelliteStatusSegments(segments: List<SegmentStatus>?) {
    if (segments.isNullOrEmpty()) return
    val today = SatelliteStatusSegmenter.dateOf(Instant.now())
    val daySegments = remember(segments, today) {
        SatelliteStatusSegmenter.segmentsForDate(segments, today)
            .ifEmpty { segments.takeLast(4) }
    }
    if (daySegments.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.status_segment_title),
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            daySegments.forEach { seg ->
                SegmentCell(segment = seg, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SegmentCell(segment: SegmentStatus, modifier: Modifier = Modifier) {
    val displayText = when (segment.status) {
        "Heard" -> stringResource(R.string.status_heard)
        "Telemetry Only" -> stringResource(R.string.status_telemetry_only)
        "Not Heard" -> stringResource(R.string.status_not_heard)
        "Crew Active" -> stringResource(R.string.status_crew_active)
        null -> stringResource(R.string.status_no_data)
        else -> segment.status
    }
    val bgColor = when (segment.status) {
        "Heard" -> colorScheme.primaryContainer
        "Telemetry Only" -> colorScheme.secondaryContainer
        "Not Heard" -> SafeColors.errorContainer
        "Crew Active" -> colorScheme.tertiaryContainer
        else -> colorScheme.surfaceVariant
    }
    val contentColor = when (segment.status) {
        "Heard" -> colorScheme.onPrimaryContainer
        "Telemetry Only" -> colorScheme.onSecondaryContainer
        "Not Heard" -> SafeColors.errorIcon
        "Crew Active" -> colorScheme.onTertiaryContainer
        else -> colorScheme.onSurfaceVariantSummary
    }
    val rangeLabel = remember(segment.segment) {
        "${segment.segment.startHour.toString().padStart(2, '0')}" +
            "-${segment.segment.endHour.toString().padStart(2, '0')}"
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = rangeLabel,
            fontSize = 11.sp,
            color = contentColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = displayText,
            fontSize = 11.sp,
            color = contentColor
        )
    }
}

// ---- 占位卡 ----

@Composable
private fun SatellitePlaceholderCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}
