package com.example.hamkit.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.data.reminder.RepeatMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedDropdownItem
import com.example.hamkit.ui.component.material.SegmentedListItem
import com.example.hamkit.ui.component.material.SegmentedSwitchItem
import com.example.hamkit.ui.component.material.SendLogBottomSheet
import com.example.hamkit.ui.component.material.SnackBarHost

/**
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMaterial(
    uiState: SettingsUiState,
    businessState: SettingsBusinessState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackBarHost = remember { SnackbarHostState() }
    var showBottomSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(scrollBehavior = scrollBehavior)
        },
        snackbarHost = { SnackBarHost(hostState = snackBarHost, modifier = Modifier.padding(bottom = bottomInnerPadding)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                content = buildList {
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.Update,
                            title = stringResource(id = R.string.settings_check_update),
                            summary = stringResource(id = R.string.settings_check_update_summary),
                            checked = uiState.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = actions.onCheckUpdateNow,
                            headlineContent = { Text(stringResource(id = R.string.settings_check_update_now)) },
                            supportingContent = { Text(stringResource(id = R.string.settings_check_update_now_summary)) },
                            leadingContent = { Icon(Icons.Filled.Download, stringResource(id = R.string.settings_check_update_now)) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                }
            )

            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                content = buildList {
                    add {
                        SegmentedDropdownItem(
                            icon = Icons.Rounded.Dashboard,
                            title = stringResource(id = R.string.settings_ui_mode),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onItemSelected = actions.onSetUiModeIndex
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = actions.onOpenTheme,
                            headlineContent = { Text(stringResource(id = R.string.settings_theme)) },
                            supportingContent = { Text(stringResource(id = R.string.settings_theme_summary)) },
                            leadingContent = { Icon(Icons.Filled.Palette, stringResource(id = R.string.settings_theme)) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                }
            )

            // 业务设置：日程提醒
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                content = buildList {
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Filled.Notifications,
                            title = stringResource(id = R.string.reminder_master_switch),
                            summary = stringResource(id = R.string.reminder_section_desc),
                            checked = businessState.reminderSettings.enabled,
                            onCheckedChange = { enabled ->
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(enabled = enabled)
                                )
                            }
                        )
                    }
                    add {
                        SegmentedDropdownItem(
                            title = stringResource(id = R.string.reminder_lead_time),
                            items = listOf("5", "10", "15", "30"),
                            selectedIndex = when (businessState.reminderSettings.leadMinutes) {
                                5 -> 0
                                10 -> 1
                                15 -> 2
                                30 -> 3
                                else -> 1
                            },
                            onItemSelected = { index ->
                                val minutes = when (index) {
                                    0 -> 5
                                    1 -> 10
                                    2 -> 15
                                    3 -> 30
                                    else -> 10
                                }
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(leadMinutes = minutes)
                                )
                            }
                        )
                    }
                    add {
                        SegmentedDropdownItem(
                            title = stringResource(id = R.string.reminder_repeat_mode),
                            items = listOf(
                                stringResource(id = R.string.reminder_repeat_always),
                                stringResource(id = R.string.reminder_repeat_daytime)
                            ),
                            selectedIndex = if (businessState.reminderSettings.repeatMode == RepeatMode.DAYTIME_ONLY) 1 else 0,
                            onItemSelected = { index ->
                                val mode = if (index == 1) RepeatMode.DAYTIME_ONLY else RepeatMode.ALWAYS
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(repeatMode = mode)
                                )
                            }
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            title = stringResource(id = R.string.reminder_sound),
                            checked = businessState.reminderSettings.soundEnabled,
                            onCheckedChange = { enabled ->
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(soundEnabled = enabled)
                                )
                            }
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            title = stringResource(id = R.string.reminder_vibration),
                            checked = businessState.reminderSettings.vibrationEnabled,
                            onCheckedChange = { enabled ->
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(vibrationEnabled = enabled)
                                )
                            }
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = actions.onOpenReminderList,
                            headlineContent = { Text(stringResource(id = R.string.reminder_view_list)) },
                            supportingContent = {
                                Text(
                                    if (businessState.reminderItems.isEmpty()) {
                                        stringResource(id = R.string.reminder_list_empty)
                                    } else {
                                        stringResource(id = R.string.reminder_count) + ": ${businessState.reminderItems.size}"
                                    }
                                )
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        )
                    }
                }
            )

            // 卫星分类配置导入 / 导出
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = actions.onExportSatelliteCategories,
                            headlineContent = { Text(stringResource(id = R.string.settings_export_categories)) },
                            supportingContent = {
                                Text(stringResource(id = R.string.settings_export_categories_summary))
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Upload, null)
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = actions.onImportSatelliteCategories,
                            headlineContent = { Text(stringResource(id = R.string.settings_import_categories)) },
                            supportingContent = {
                                Text(stringResource(id = R.string.settings_import_categories_summary))
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Download, null)
                            },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                        )
                    }
                )
            )

            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = { showBottomSheet = true },
                            headlineContent = { Text(stringResource(id = R.string.send_log)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.BugReport,
                                    stringResource(id = R.string.send_log)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = actions.onOpenAbout,
                            headlineContent = { Text(stringResource(id = R.string.about)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.ContactPage,
                                    stringResource(id = R.string.about)
                                )
                            },
                        )
                    }
                )
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (showBottomSheet) {
                SendLogBottomSheet(
                    onDismiss = { showBottomSheet = false },
                    snackbarHostState = snackBarHost,
                )
            }
            // UpdateDialogs 已提升到 MainScreen 顶层渲染（启动自动检查与设置页共用）
            Spacer(modifier = Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
