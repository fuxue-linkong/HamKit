package com.example.hamkit.ui.screen.satellite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hamkit.R
import com.example.hamkit.data.satellite.SatelliteModes
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.SatelliteFilter
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.theme.LocalCardAlpha
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 卫星筛选内容组件（供弹窗或子页复用）。
 * 修改即时写回 [com.example.hamkit.ui.MainViewModel]。
 *
 * @param horizontalPadding 左右留白。Miuix 弹窗内已有 OverlayDialog 的
 *   insideMargin(24dp)，传 0 避免叠加；独立子页没有这层内边距，沿用 12dp。
 */
@Composable
fun SatelliteFilterDialogContent(
    onDismiss: () -> Unit,
    horizontalPadding: Dp = 12.dp,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SatelliteFilterContentMiuix(
            onDismiss = onDismiss,
            horizontalPadding = horizontalPadding
        )

        UiMode.Material -> SatelliteFilterContentMaterial(
            onDismiss = onDismiss,
            horizontalPadding = horizontalPadding
        )
    }
}

/**
 * 卫星筛选子页面入口（保留独立页面能力）。
 * 由卫星管理页的筛选按钮导航进入，修改即时写回 [com.example.hamkit.ui.MainViewModel]，
 * 返回（pop）后管理页列表自动反映新筛选条件。
 */
@Composable
fun SatelliteFilterScreen() {
    val navigator = LocalNavigator.current
    SatelliteFilterDialogContent(onDismiss = { navigator.pop() })
}

// ---------------- Miuix 风格 ----------------

@Composable
private fun SatelliteFilterContentMiuix(
    onDismiss: () -> Unit,
    horizontalPadding: Dp,
) {
    val mainViewModel = LocalMainViewModel.current
    val filter by mainViewModel.satelliteFilter
    val onFilterChange: (SatelliteFilter) -> Unit = mainViewModel::updateSatelliteFilter

    val unknownModeLabel = stringResource(R.string.filter_mode_unknown)
    // 完整模式列表（对齐 Look4Sat 60 种）+ 未知模式选项
    val modeOptions: List<Pair<String, String>> = buildList {
        SatelliteModes.ALL.forEach { mode -> add(mode to mode) }
        add("" to unknownModeLabel)
    }

    val categoryConfig by mainViewModel.satelliteCategoryConfig

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = horizontalPadding,
            vertical = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        overscrollEffect = null,
    ) {
        // 顶部重置行：始终占位，用 AnimatedVisibility 控制显隐，
        // 避免条件 item 增减导致下方列表整体跳动。
        item {
            AnimatedVisibility(
                visible = filter.isActive,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    MiuixTextButton(
                        text = stringResource(R.string.filter_reset),
                        onClick = { onFilterChange(SatelliteFilter()) }
                    )
                }
            }
        }

        // 工作模式
        item {
            MiuixFilterSectionCard(title = stringResource(R.string.filter_mode_section)) {
                modeOptions.forEach { (value, label) ->
                    val selected = value in filter.modes
                    MiuixFilterSelectableRow(
                        label = label,
                        selected = selected,
                        onClick = {
                            val newModes = if (selected) filter.modes - value else filter.modes + value
                            onFilterChange(filter.copy(modes = newModes))
                        }
                    )
                }
            }
        }

        // 开关筛选
        item {
            MiuixFilterSectionCard(title = stringResource(R.string.filter_status_section)) {
                MiuixFilterSwitchRow(
                    label = stringResource(R.string.filter_only_in_pass),
                    checked = filter.onlyInPass,
                    onClick = {
                        onFilterChange(filter.copy(onlyInPass = !filter.onlyInPass, onlyUpcoming = false))
                    }
                )
                MiuixFilterSwitchRow(
                    label = stringResource(R.string.filter_only_upcoming),
                    checked = filter.onlyUpcoming,
                    onClick = {
                        onFilterChange(filter.copy(onlyUpcoming = !filter.onlyUpcoming, onlyInPass = false))
                    }
                )
                MiuixFilterSwitchRow(
                    label = stringResource(R.string.filter_only_amsat),
                    checked = filter.onlyAmsat,
                    onClick = { onFilterChange(filter.copy(onlyAmsat = !filter.onlyAmsat)) }
                )
            }
        }

        // 分类筛选（取代原「仅已关注」：收藏已并入分类体系）
        if (categoryConfig.categories.isNotEmpty()) {
            item {
                MiuixFilterSectionCard(title = stringResource(R.string.filter_category_section)) {
                    categoryConfig.sortedCategories().forEach { category ->
                        val selected = category.id in filter.categoryIds
                        MiuixFilterSelectableRow(
                            label = category.name,
                            selected = selected,
                            onClick = {
                                val ids = if (selected) {
                                    filter.categoryIds - category.id
                                } else {
                                    filter.categoryIds + category.id
                                }
                                onFilterChange(filter.copy(categoryIds = ids))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixFilterSectionCard(title: String, content: @Composable () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        colors = MiuixCardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            MiuixText(
                text = title,
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            // 分组标题与首行之间留 8dp，避免读起来挤在一起
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun MiuixFilterSelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiuixText(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            color = colorScheme.onSurface
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) colorScheme.primary else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                MiuixText(
                    text = "✓",
                    fontSize = 13.sp,
                    color = colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MiuixFilterSwitchRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiuixText(
            text = label,
            fontSize = 14.sp,
            color = colorScheme.onSurface
        )
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (checked) colorScheme.primary else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .then(if (checked) Modifier.offset(x = 18.dp) else Modifier)
            )
        }
    }
}

// ---------------- Material 风格 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteFilterContentMaterial(
    onDismiss: () -> Unit,
    horizontalPadding: Dp,
) {
    val mainViewModel = LocalMainViewModel.current
    val filter by mainViewModel.satelliteFilter
    val onFilterChange: (SatelliteFilter) -> Unit = mainViewModel::updateSatelliteFilter

    val unknownModeLabel = stringResource(R.string.filter_mode_unknown)
    // 完整模式列表（对齐 Look4Sat 60 种）+ 未知模式选项
    val modeOptions: List<Pair<String, String>> = buildList {
        SatelliteModes.ALL.forEach { mode -> add(mode to mode) }
        add("" to unknownModeLabel)
    }

    val categoryConfig by mainViewModel.satelliteCategoryConfig

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = horizontalPadding,
            vertical = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部重置行：有筛选条件时显示
        item {
            AnimatedVisibility(visible = filter.isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onFilterChange(SatelliteFilter()) }) {
                        Text(
                            text = stringResource(R.string.filter_reset),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 工作模式
        item {
            MaterialFilterSectionCard(title = stringResource(R.string.filter_mode_section)) {
                modeOptions.forEach { (value, label) ->
                    val selected = value in filter.modes
                    FilterCheckRowMaterial(
                        label = label,
                        checked = selected,
                        onToggle = {
                            val newModes = if (selected) filter.modes - value else filter.modes + value
                            onFilterChange(filter.copy(modes = newModes))
                        }
                    )
                }
            }
        }

        // 开关筛选
        item {
            MaterialFilterSectionCard(title = stringResource(R.string.filter_status_section)) {
                FilterSwitchRowMaterial(
                    label = stringResource(R.string.filter_only_in_pass),
                    checked = filter.onlyInPass,
                    onToggle = {
                        onFilterChange(filter.copy(onlyInPass = !filter.onlyInPass, onlyUpcoming = false))
                    }
                )
                FilterSwitchRowMaterial(
                    label = stringResource(R.string.filter_only_upcoming),
                    checked = filter.onlyUpcoming,
                    onToggle = {
                        onFilterChange(filter.copy(onlyUpcoming = !filter.onlyUpcoming, onlyInPass = false))
                    }
                )
                FilterSwitchRowMaterial(
                    label = stringResource(R.string.filter_only_amsat),
                    checked = filter.onlyAmsat,
                    onToggle = { onFilterChange(filter.copy(onlyAmsat = !filter.onlyAmsat)) }
                )
            }
        }

        // 分类筛选（取代原「仅已关注」：收藏已并入分类体系）
        if (categoryConfig.categories.isNotEmpty()) {
            item {
                MaterialFilterSectionCard(title = stringResource(R.string.filter_category_section)) {
                    categoryConfig.sortedCategories().forEach { category ->
                        val selected = category.id in filter.categoryIds
                        FilterCheckRowMaterial(
                            label = category.name,
                            checked = selected,
                            onToggle = {
                                val ids = if (selected) {
                                    filter.categoryIds - category.id
                                } else {
                                    filter.categoryIds + category.id
                                }
                                onFilterChange(filter.copy(categoryIds = ids))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialFilterSectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 与 Miuix 皮肤保持一致：标题与首行之间 8dp
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun FilterCheckRowMaterial(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun FilterSwitchRowMaterial(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
