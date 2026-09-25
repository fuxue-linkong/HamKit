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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.example.hamkit.data.satellite.SatelliteStatusTracker
import com.example.hamkit.data.satellite.SegmentStatus
import com.example.hamkit.ui.LocationUiState
import com.example.hamkit.ui.SatelliteFilter
import com.example.hamkit.ui.SatelliteUiState
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.applyFilterToItems
import com.example.hamkit.ui.isSatelliteSourceExpired
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.navigation3.Route

import com.example.hamkit.ui.theme.LocalCardAlpha
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 卫星管理页 Material3 风格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteManagementMaterial() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val locationState by mainViewModel.locationState
    val satelliteState by mainViewModel.satelliteState
    val categoryConfig by mainViewModel.satelliteCategoryConfig
    val filter by mainViewModel.satelliteFilter
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showCategoryManager by rememberSaveable { mutableStateOf(false) }
    var categoryPickerTarget by remember { mutableStateOf<Int?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.satellite_management)) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        SatelliteManagementContentMaterial(
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
            contentPadding = innerPadding,
            nestedScrollConnection = scrollBehavior.nestedScrollConnection
        )

        if (showFilterDialog) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showFilterDialog = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                SatelliteFilterDialogContent(onDismiss = { showFilterDialog = false })
            }
        }

        if (showCategoryManager) {
            SatelliteCategoryManagerDialogMaterial(
                show = showCategoryManager,
                onDismissRequest = { showCategoryManager = false }
            )
        }

        categoryPickerTarget?.let { target ->
            SatelliteCategoryPickerDialogMaterial(
                show = true,
                catalogNumber = target,
                onDismissRequest = { categoryPickerTarget = null }
            )
        }
    }
}

@Composable
private fun SatelliteManagementContentMaterial(
    locationState: LocationUiState,
    satelliteState: SatelliteUiState,
    satelliteItems: List<SatelliteListItem>,
    filter: SatelliteFilter,
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
    contentPadding: PaddingValues,
    nestedScrollConnection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection
) {
    @Suppress("UnusedVariable")
    val statusEntries = statusTracker.statusMap.value

    // 分类 id → 名称，用于列表项展示已归入的分类
    val categoryNames = remember(categoryConfig) {
        categoryConfig.categories.associate { it.id to it.name }
    }

    val filteredSatellites = remember(satelliteItems, filter, categoryConfig) {
        satelliteItems.applyFilterToItems(filter, categoryConfig.membership)
    }
    val totalCount = satelliteItems.size
    val categorizedNumbers = categoryConfig.categorizedCatalogNumbers
    // 预计算已分类数量：列表规模可达 1.6 万，且本页存在 5 秒一次的倒计时重组
    val categorizedCount = remember(satelliteItems, categorizedNumbers) {
        satelliteItems.count { it.catalogNumber in categorizedNumbers }
    }

    // 统一倒计时时钟
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

    // 预计算状态缓存
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
            .nestedScroll(nestedScrollConnection)
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 数据源刷新 + 统计 + 筛选入口合并为一张卡（减少卡片间距，列表起点上移）
        item {
            SatelliteOverviewCardMaterial(
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

        when {
            satelliteState.isSatelliteLoading && filteredSatellites.isEmpty() -> {
                item { SatellitePlaceholderCardMaterial { CircularProgressIndicator() } }
            }
            satelliteState.satelliteError != null -> {
                item {
                    SatellitePlaceholderCardMaterial {
                        Text(
                            text = stringResource(R.string.satellite_load_failed, satelliteState.satelliteError),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            locationState.result == null -> {
                item {
                    SatellitePlaceholderCardMaterial {
                        Text(stringResource(R.string.satellite_need_location))
                    }
                }
            }
            filteredSatellites.isEmpty() -> {
                item {
                    SatellitePlaceholderCardMaterial {
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
                    SatelliteItemMaterial(
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
private fun SatelliteOverviewCardMaterial(
    isLoading: Boolean,
    isSatelliteLoading: Boolean,
    lastLocationTime: Instant?,
    lastLocationCity: String,
    lastSatelliteTime: Instant?,
    totalCount: Int,
    filteredCount: Int,
    categorizedCount: Int,
    categoryCount: Int,
    filter: SatelliteFilter,
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 名称搜索框（移自筛选页，置于顶部最易触达位置）
            OutlinedTextField(
                value = filter.nameQuery,
                onValueChange = onNameQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.filter_search_hint)) }
            )

            // 描述 + 统计
            Text(
                text = stringResource(R.string.satellite_management_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ManagementStatMaterial(
                    label = stringResource(R.string.satellite_count, totalCount),
                    value = totalCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                ManagementStatMaterial(
                    label = stringResource(R.string.satellite_categorized_count),
                    value = categorizedCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                ManagementStatMaterial(
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
                SatelliteFilterButtonMaterial(
                    filter = filter,
                    onClick = onShowFilterDialog
                )

                // 分类入口：紧邻筛选按钮，视觉语言一致
                SatelliteCategoryButtonMaterial(
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            // 数据源刷新操作
            ActionRowMaterial(
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
            ActionRowMaterial(
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
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun ActionRowMaterial(
    buttonText: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    primaryText: String,
    secondaryText: String,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(buttonText)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ManagementStatMaterial(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- 筛选入口 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteFilterButtonMaterial(
    filter: SatelliteFilter,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (filter.isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.FilterList,
            contentDescription = null,
            tint = if (filter.isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.filter_title),
            style = MaterialTheme.typography.labelMedium,
            color = if (filter.isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (filter.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

// ---- 分类入口 ----

/**
 * 分类入口按钮（Material3）：与 [SatelliteFilterButtonMaterial] 视觉语言一致。
 *
 * @param active 当前是否按分类筛选（有选中分类时高亮）
 */
@Composable
private fun SatelliteCategoryButtonMaterial(
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Category,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.satellite_category_title),
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (active) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

// ---- 卫星列表项 ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SatelliteItemMaterial(
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

    val pass = satellite.pass
    val timeInfo = remember(pass?.aosTime, pass?.losTime, pass?.isCurrentlyVisible, nowMillis) {
        val formatter = satelliteTimeFormatterM
        val zone = ZoneId.systemDefault()
        when {
            pass == null -> null
            pass.isCurrentlyVisible -> {
                val losTime = pass.losTime.atZone(zone).format(formatter)
                val now = if (nowMillis > 0) Instant.ofEpochMilli(nowMillis) else Instant.now()
                val remainingSeconds = Duration.between(now, pass.losTime).seconds
                SatelliteTimeInfoM.InPass(losTime, formatRemainingTimeM(remainingSeconds))
            }
            else -> SatelliteTimeInfoM.Upcoming(pass.aosTime.atZone(zone).format(formatter))
        }
    }

    val hasCategory = assignedCategoryNames.isNotEmpty()
    val cardContainerColor = when {
        hasCategory -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val cardContentColor = when {
        hasCategory -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (pass?.isCurrentlyVisible == true) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else if (hasCategory) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onSatelliteClick),
        colors = CardDefaults.cardColors(
            containerColor = cardContainerColor.copy(alpha = LocalCardAlpha.current),
            contentColor = cardContentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 第一行：名 + 编号 + 仰角 + 分类 + 提醒 + 展开按钮（点击卡片进入详情页）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = satellite.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cardContentColor,
                        maxLines = 1
                    )
                    Text(
                        text = "#%05d".format(Locale.US, satellite.catalogNumber),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (pass != null) {
                        Text(
                            text = "${pass.maxElevation.toInt()}°",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = cardContentColor
                        )
                    }
                    IconButton(onClick = onOpenCategoryPicker, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Category,
                            contentDescription = stringResource(R.string.satellite_category_title),
                            modifier = Modifier.size(18.dp),
                            tint = if (hasCategory) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 过境提醒开关：可直接切换，避免「开关只读、无法发现」的问题
                    IconButton(onClick = onToggleReminder, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (reminderEnabled) {
                                Icons.Filled.Notifications
                            } else {
                                Icons.Filled.NotificationsNone
                            },
                            contentDescription = stringResource(
                                if (reminderEnabled) {
                                    R.string.satellite_reminder_on
                                } else {
                                    R.string.satellite_reminder_off
                                }
                            ),
                            modifier = Modifier.size(18.dp),
                            tint = if (reminderEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    // 转发器/时间线展开按钮（卡片点击已改为进入详情页）
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (effectiveStatus.isNotEmpty()) {
                    StatusChipM(status = effectiveStatus, isStatusInherited = isStatusInherited)
                }
                // 已归入的分类：最多显示 3 个，其余用 +N 表示
                if (assignedCategoryNames.isNotEmpty()) {
                    assignedCategoryNames.take(3).forEach { name -> CategoryChipM(name = name) }
                    if (assignedCategoryNames.size > 3) {
                        CategoryChipM(name = "+${assignedCategoryNames.size - 3}")
                    }
                }
                val modes = satellite.effectiveModes
                if (modes.isEmpty()) {
                    ModeChipM(mode = stringResource(R.string.mode_unknown))
                } else {
                    modes.take(4).forEach { mode -> ModeChipM(mode = mode) }
                    if (modes.size > 4) {
                        ModeChipM(mode = "+${modes.size - 4}")
                    }
                }
                if (satellite.radios.isNotEmpty()) {
                    TransceiverCountChipM(
                        active = satellite.radios.count { it.isActive },
                        total = satellite.radios.size
                    )
                }
            }

            // 展开区：转发器列表 + BJT 分段状态时间线
            if (expanded) {
                if (satellite.radios.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    TransceiverListM(satellite.radios)
                }
                if (statusSegments != null) {
                    Spacer(Modifier.height(10.dp))
                    SatelliteStatusSegmentsM(statusSegments)
                }
            }

            Spacer(Modifier.height(10.dp))

            // 时间徽章 + 在境进度条（无过境的卫星显示占位）
            when (timeInfo) {
                is SatelliteTimeInfoM.InPass -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TimeBadgeM(
                            label = stringResource(R.string.los_time),
                            value = timeInfo.losTime,
                            isActive = true
                        )
                        TimeBadgeM(
                            label = stringResource(R.string.time_remaining),
                            value = timeInfo.remainingText,
                            isActive = true
                        )
                    }
                }
                is SatelliteTimeInfoM.Upcoming -> {
                    TimeBadgeM(
                        label = stringResource(R.string.aos_time),
                        value = timeInfo.aosTime,
                        isActive = false
                    )
                }
                null -> {
                    TimeBadgeM(
                        label = stringResource(R.string.no_pass_window),
                        value = stringResource(R.string.no_pass_hint),
                        isActive = false
                    )
                }
            }
        }
    }
}

/** 转发器数量徽章（Material 版） */
@Composable
private fun TransceiverCountChipM(active: Int, total: Int) {
    val (bgColor, contentColor) = if (active > 0) {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ChipM(text = stringResource(R.string.transceiver_count, active, total), bgColor = bgColor, contentColor = contentColor)
}

/** 展开区：该卫星的全部转发器（Material 版） */
@Composable
private fun TransceiverListM(radios: List<RadioInfo>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        radios.forEach { radio ->
            TransceiverRowM(radio)
        }
    }
}

/** 单条转发器行（Material 版） */
@Composable
private fun TransceiverRowM(radio: RadioInfo) {
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
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            RadioStatusBadgeM(radio)
        }
        val freqText = buildString {
            if (radio.downlinkHz != null) {
                append("RX ${formatMHzM(radio.downlinkHz)}")
            }
            if (radio.uplinkHz != null) {
                if (isNotEmpty()) append("  ")
                append("TX ${formatMHzM(radio.uplinkHz)}")
            }
            if (isNotEmpty() && radio.displayMode.isNotBlank()) append("  ")
            if (radio.displayMode.isNotBlank()) append(radio.displayMode)
        }
        if (freqText.isNotEmpty()) {
            Text(
                text = freqText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 转发器状态徽章（Material 版） */
@Composable
private fun RadioStatusBadgeM(radio: RadioInfo) {
    val (label, bgColor, contentColor) = when (radio.status) {
        RadioInfo.STATUS_ACTIVE ->
            Triple(
                stringResource(R.string.transceiver_active),
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer
            )
        RadioInfo.STATUS_FUTURE ->
            Triple(
                stringResource(R.string.transceiver_future),
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer
            )
        else ->
            Triple(
                stringResource(R.string.transceiver_inactive),
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant
            )
    }
    ChipM(text = label, bgColor = bgColor, contentColor = contentColor)
}

/** 频率（Hz）→ 可读文本（MHz，3 位小数） */
private fun formatMHzM(hz: Long): String = "%.3f".format(Locale.US, hz / 1_000_000.0)

// ---- 时间相关辅助 ----

private val satelliteTimeFormatterM = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private sealed class SatelliteTimeInfoM {
    data class InPass(val losTime: String, val remainingText: String) : SatelliteTimeInfoM()
    data class Upcoming(val aosTime: String) : SatelliteTimeInfoM()
}

private fun formatRemainingTimeM(seconds: Long): String {
    if (seconds <= 0) return "0秒"
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) "${minutes}分${remainingSeconds}秒" else "${remainingSeconds}秒"
}

@Composable
private fun TimeBadgeM(label: String, value: String, isActive: Boolean) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isActive) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

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
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

// ---- Chips ----

@Composable
private fun StatusChipM(status: String, isStatusInherited: Boolean = false) {
    val baseText = when (status) {
        "Heard" -> stringResource(R.string.status_heard)
        "Telemetry Only" -> stringResource(R.string.status_telemetry_only)
        "Not Heard" -> stringResource(R.string.status_not_heard)
        "Crew Active" -> stringResource(R.string.status_crew_active)
        else -> status
    }
    val displayText = if (isStatusInherited) "$baseText *" else baseText
    val (bgColor, contentColor) = when (status) {
        "Heard" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "Telemetry Only" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "Not Heard" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "Crew Active" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val finalBg = if (isStatusInherited) bgColor.copy(alpha = 0.85f) else bgColor
    val finalContent = if (isStatusInherited) contentColor.copy(alpha = 0.85f) else contentColor
    ChipM(text = displayText, bgColor = finalBg, contentColor = finalContent)
}

@Composable
private fun ModeChipM(mode: String) {
    val (bgColor, contentColor) = when (mode.uppercase()) {
        "FM" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "SSTV" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "DSTAR" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "CW" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ChipM(text = mode, bgColor = bgColor, contentColor = contentColor)
}

/** 分类名称徽章（Material 版）：与模式徽章区分，使用 tertiary 色系。 */
@Composable
private fun CategoryChipM(name: String) {
    ChipM(
        // 已分类卡片的容器本身就是 tertiaryContainer，徽章必须换色才能看出边界
        text = name,
        bgColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChipM(text: String, bgColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---- BJT 分段状态时间线 ----

@Composable
private fun SatelliteStatusSegmentsM(segments: List<SegmentStatus>?) {
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
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            daySegments.forEach { seg ->
                SegmentCellM(segment = seg, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SegmentCellM(segment: SegmentStatus, modifier: Modifier = Modifier) {
    val displayText = when (segment.status) {
        "Heard" -> stringResource(R.string.status_heard)
        "Telemetry Only" -> stringResource(R.string.status_telemetry_only)
        "Not Heard" -> stringResource(R.string.status_not_heard)
        "Crew Active" -> stringResource(R.string.status_crew_active)
        null -> stringResource(R.string.status_no_data)
        else -> segment.status
    }
    val bgColor = when (segment.status) {
        "Heard" -> MaterialTheme.colorScheme.primaryContainer
        "Telemetry Only" -> MaterialTheme.colorScheme.secondaryContainer
        "Not Heard" -> MaterialTheme.colorScheme.errorContainer
        "Crew Active" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (segment.status) {
        "Heard" -> MaterialTheme.colorScheme.onPrimaryContainer
        "Telemetry Only" -> MaterialTheme.colorScheme.onSecondaryContainer
        "Not Heard" -> MaterialTheme.colorScheme.onErrorContainer
        "Crew Active" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}

// ---- 占位卡 ----

@Composable
private fun SatellitePlaceholderCardMaterial(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)
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
