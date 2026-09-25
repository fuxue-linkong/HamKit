package com.example.hamkit.ui.screen.satellite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hamkit.R
import com.example.hamkit.data.satellite.SatelliteCategory
import com.example.hamkit.ui.LocalMainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

// ══════════════════════════════════════════════════════════════════════════
// 分类管理 / 分类归属弹窗（双皮肤）
//
// 设计要点：
// - 分类只负责「组织 / 筛选」；过境提醒是独立的卫星级开关，
//   因此「把卫星归入分类」不会创建或删除任何提醒。
// - 内置分类（我的关注）不可删除，避免迁移数据失去归属。
// - 弹窗内容量小，统一使用 Column + verticalScroll，避免 LazyColumn 在
//   Dialog 的无限高度约束下抛异常。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 分类管理弹窗（Miuix 风格）：新建 / 重命名 / 删除分类，并可点击行按分类筛选。
 */
@Composable
fun SatelliteCategoryManagerDialogMiuix(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    val mainViewModel = LocalMainViewModel.current
    val config by mainViewModel.satelliteCategoryConfig
    val filter by mainViewModel.satelliteFilter

    // 预计算每个分类的卫星数：satelliteCountOf 是 O(归属条目)，
    // 直接放在每行组合里会在每次重组时对每个分类重复扫描。
    val categoryCounts = remember(config) {
        config.categories.associate { it.id to config.satelliteCountOf(it.id) }
    }

    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SatelliteCategory?>(null) }

    // 不覆盖 insideMargin：Miuix 默认 24dp 会同时作用于标题与内容，
    // 置 0 会让「管理分类」标题贴住弹窗顶边（官方默认即 24dp）
    OverlayDialog(
        show = show,
        title = stringResource(R.string.satellite_category_manage),
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // 水平留白由 insideMargin 统一提供，这里只补内容纵向间距
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CategoryCreationRow(
                    value = newName,
                    onValueChange = { newName = it },
                    onSubmit = {
                        mainViewModel.addSatelliteCategory(newName)
                        newName = ""
                    }
                )

                if (config.categories.isEmpty()) {
                    MiuixText(
                        text = stringResource(R.string.satellite_category_empty),
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                config.sortedCategories().forEach { category ->
                    val count = categoryCounts[category.id] ?: 0
                    if (editingId == category.id) {
                        CategoryRenameRow(
                            value = editingName,
                            onValueChange = { editingName = it },
                            onSubmit = {
                                mainViewModel.renameSatelliteCategory(category.id, editingName)
                                editingId = null
                            }
                        )
                    } else {
                        MiuixCategoryRow(
                            category = category,
                            count = count,
                            selectedForFilter = category.id in filter.categoryIds,
                            onToggleFilter = {
                                val ids = if (category.id in filter.categoryIds) {
                                    filter.categoryIds - category.id
                                } else {
                                    filter.categoryIds + category.id
                                }
                                mainViewModel.updateSatelliteFilter(filter.copy(categoryIds = ids))
                            },
                            onRename = {
                                editingId = category.id
                                editingName = category.name
                            },
                            onDelete = { pendingDelete = category }
                        )
                    }
                }
            }
        }
    )

    pendingDelete?.let { target ->
        val count = categoryCounts[target.id] ?: 0
        // 必须用 Miuix 弹窗：Miuix 皮肤下没有 MaterialTheme provider，
        // 直接用 material3.AlertDialog 会脱离当前主题（配色/字体回落到 Material 默认）
        MiuixConfirmDialog(
            show = true,
            title = stringResource(R.string.satellite_category_delete),
            message = stringResource(R.string.satellite_category_delete_confirm, target.name, count),
            confirmText = stringResource(R.string.satellite_category_delete),
            dismissText = stringResource(R.string.cancel),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                mainViewModel.removeSatelliteCategory(target.id)
                // 分类删除后同步清理筛选条件中的悬空 id
                val ids = filter.categoryIds - target.id
                if (ids != filter.categoryIds) {
                    mainViewModel.updateSatelliteFilter(filter.copy(categoryIds = ids))
                }
                pendingDelete = null
            }
        )
    }
}

/**
 * Miuix 风格确认弹窗（替代 material3.AlertDialog，避免在 Miuix 皮肤下脱离主题）。
 */
@Composable
private fun MiuixConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                MiuixText(
                    text = message,
                    fontSize = 14.sp,
                    color = colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            MiuixText(
                                text = dismissText,
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = onConfirm,
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            MiuixText(confirmText)
                        }
                    }
                }
            }
        }
    )
}

/**
 * 卫星分类归属弹窗（Miuix 风格）：多选归类 + 卫星级过境提醒开关。
 */
@Composable
fun SatelliteCategoryPickerDialogMiuix(
    show: Boolean,
    catalogNumber: Int,
    onDismissRequest: () -> Unit,
) {
    val mainViewModel = LocalMainViewModel.current
    val config by mainViewModel.satelliteCategoryConfig
    val satelliteName = mainViewModel.satelliteNameOf(catalogNumber)
    val assigned = config.categoryIdsOf(catalogNumber)
    val reminderEnabled = catalogNumber in config.reminderFlags

    var newName by remember { mutableStateOf("") }

    OverlayDialog(
        show = show,
        title = stringResource(R.string.satellite_category_picker_title, satelliteName),
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MiuixSwitchRow(
                    label = stringResource(R.string.satellite_reminder_toggle),
                    description = stringResource(
                        if (reminderEnabled) R.string.satellite_reminder_on
                        else R.string.satellite_reminder_off
                    ),
                    checked = reminderEnabled,
                    onClick = { mainViewModel.toggleSatelliteReminder(catalogNumber) }
                )
                Spacer(Modifier.height(8.dp))

                if (config.categories.isEmpty()) {
                    MiuixText(
                        text = stringResource(R.string.satellite_category_empty),
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                config.sortedCategories().forEach { category ->
                    MiuixCheckRow(
                        label = category.name,
                        checked = category.id in assigned,
                        onClick = {
                            mainViewModel.toggleSatelliteCategory(catalogNumber, category.id)
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
                CategoryCreationRow(
                    value = newName,
                    onValueChange = { newName = it },
                    onSubmit = {
                        mainViewModel.addSatelliteCategory(newName)
                        newName = ""
                    }
                )
            }
        }
    )
}

// ---- Miuix 复用小组件 ----

@Composable
private fun CategoryCreationRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.satellite_category_name_hint),
                singleLine = true
            )
        }
        Button(
            onClick = onSubmit,
            enabled = value.isNotBlank(),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            MiuixText(stringResource(R.string.satellite_category_new))
        }
    }
}

@Composable
private fun CategoryRenameRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        Button(
            onClick = onSubmit,
            enabled = value.isNotBlank(),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            MiuixText(stringResource(R.string.confirm))
        }
    }
}

@Composable
private fun MiuixCategoryRow(
    category: SatelliteCategory,
    count: Int,
    selectedForFilter: Boolean,
    onToggleFilter: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selectedForFilter) {
                    colorScheme.primaryContainer.copy(alpha = 0.6f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onToggleFilter)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 名称与数量之间保留 2dp，否则两行文字会贴在一起
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MiuixText(text = category.name, fontSize = 15.sp, color = colorScheme.onSurface)
            MiuixText(
                text = stringResource(R.string.satellite_category_satellite_count, count),
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MiuixIconButton(onClick = onRename) {
                MiuixIcon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.satellite_category_rename),
                    tint = colorScheme.onSurfaceVariantSummary
                )
            }
            if (!category.isBuiltin) {
                MiuixIconButton(onClick = onDelete) {
                    MiuixIcon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.satellite_category_delete),
                        tint = colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixCheckRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn + toggleable：保证 48dp 最小触控区，并向无障碍服务暴露勾选语义
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
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
                .background(
                    if (checked) colorScheme.primary
                    else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                MiuixText(text = "✓", fontSize = 13.sp, color = colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun MiuixSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onClick() })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MiuixText(text = label, fontSize = 14.sp, color = colorScheme.onSurface)
            MiuixText(text = description, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
        }
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (checked) colorScheme.primary
                    else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    // 用主题色而非硬编码白色：浅色主题下白色滑块在浅灰轨道上几乎不可见
                    .background(colorScheme.surface)
                    .then(if (checked) Modifier.offset(x = 18.dp) else Modifier)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Material3 皮肤
// ══════════════════════════════════════════════════════════════════════════

/**
 * 分类管理弹窗（Material3 风格）。
 */
@Composable
fun SatelliteCategoryManagerDialogMaterial(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    if (!show) return
    val mainViewModel = LocalMainViewModel.current
    val config by mainViewModel.satelliteCategoryConfig
    val filter by mainViewModel.satelliteFilter

    // 预计算每个分类的卫星数，避免每次重组对每个分类重复扫描归属表
    val categoryCounts = remember(config) {
        config.categories.associate { it.id to config.satelliteCountOf(it.id) }
    }

    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SatelliteCategory?>(null) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.satellite_category_manage)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.satellite_category_name_hint)) },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            mainViewModel.addSatelliteCategory(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank()
                    ) { Text(stringResource(R.string.satellite_category_new)) }
                }

                if (config.categories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.satellite_category_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                config.sortedCategories().forEach { category ->
                    val selectedForFilter = category.id in filter.categoryIds
                    val count = categoryCounts[category.id] ?: 0
                    if (editingId == category.id) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = editingName,
                                onValueChange = { editingName = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            TextButton(
                                onClick = {
                                    mainViewModel.renameSatelliteCategory(category.id, editingName)
                                    editingId = null
                                },
                                enabled = editingName.isNotBlank()
                            ) { Text(stringResource(R.string.confirm)) }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val ids = if (selectedForFilter) {
                                        filter.categoryIds - category.id
                                    } else {
                                        filter.categoryIds + category.id
                                    }
                                    mainViewModel.updateSatelliteFilter(filter.copy(categoryIds = ids))
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selectedForFilter) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = stringResource(R.string.satellite_category_satellite_count, count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    editingId = category.id
                                    editingName = category.name
                                }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = stringResource(R.string.satellite_category_rename)
                                    )
                                }
                                if (!category.isBuiltin) {
                                    IconButton(onClick = { pendingDelete = category }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = stringResource(R.string.satellite_category_delete)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.confirm)) }
        }
    )

    pendingDelete?.let { target ->
        val count = categoryCounts[target.id] ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.satellite_category_delete)) },
            text = { Text(stringResource(R.string.satellite_category_delete_confirm, target.name, count)) },
            confirmButton = {
                TextButton(onClick = {
                    mainViewModel.removeSatelliteCategory(target.id)
                    val ids = filter.categoryIds - target.id
                    if (ids != filter.categoryIds) {
                        mainViewModel.updateSatelliteFilter(filter.copy(categoryIds = ids))
                    }
                    pendingDelete = null
                }) { Text(stringResource(R.string.satellite_category_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/**
 * 卫星分类归属弹窗（Material3 风格）。
 */
@Composable
fun SatelliteCategoryPickerDialogMaterial(
    show: Boolean,
    catalogNumber: Int,
    onDismissRequest: () -> Unit,
) {
    if (!show) return
    val mainViewModel = LocalMainViewModel.current
    val config by mainViewModel.satelliteCategoryConfig
    val satelliteName = mainViewModel.satelliteNameOf(catalogNumber)
    val assigned = config.categoryIdsOf(catalogNumber)
    val reminderEnabled = catalogNumber in config.reminderFlags

    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.satellite_category_picker_title, satelliteName)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { mainViewModel.toggleSatelliteReminder(catalogNumber) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.satellite_reminder_toggle),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(
                                if (reminderEnabled) R.string.satellite_reminder_on
                                else R.string.satellite_reminder_off
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { mainViewModel.toggleSatelliteReminder(catalogNumber) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                if (config.categories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.satellite_category_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                config.sortedCategories().forEach { category ->
                    val checked = category.id in assigned
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                mainViewModel.toggleSatelliteCategory(catalogNumber, category.id)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = category.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                mainViewModel.toggleSatelliteCategory(catalogNumber, category.id)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.satellite_category_name_hint)) },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            mainViewModel.addSatelliteCategory(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank()
                    ) { Text(stringResource(R.string.satellite_category_new)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.confirm)) }
        }
    )
}
