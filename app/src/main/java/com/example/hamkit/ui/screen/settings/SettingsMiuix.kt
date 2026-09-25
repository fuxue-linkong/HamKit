package com.example.hamkit.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.data.reminder.RepeatMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.component.dialog.rememberLoadingDialog
import com.example.hamkit.ui.component.miuix.SendLogDialog
import com.example.hamkit.ui.theme.LocalEnableBlur
import com.example.hamkit.ui.util.BlurredBar
import com.example.hamkit.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    businessState: SettingsBusinessState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val loadingDialog = rememberLoadingDialog()
    val showSendLogDialog = rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.settings),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_check_update),
                            summary = stringResource(id = R.string.settings_check_update_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Update,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_check_update),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_check_update_now),
                            summary = stringResource(id = R.string.settings_check_update_now_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Download,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_check_update_now),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onCheckUpdateNow
                        )
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        OverlayDropdownPreference(
                            title = stringResource(id = R.string.settings_ui_mode),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            startAction = {
                                Icon(
                                    Icons.Rounded.Dashboard,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_ui_mode),
                                    tint = colorScheme.onBackground
                                )
                            },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onSelectedIndexChange = actions.onSetUiModeIndex
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_theme),
                            summary = stringResource(id = R.string.settings_theme_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_theme),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenTheme
                        )
                    }

                    // 业务设置：日程提醒
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.reminder_master_switch),
                            summary = stringResource(id = R.string.reminder_section_desc),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Notifications,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.reminder_master_switch),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = businessState.reminderSettings.enabled,
                            onCheckedChange = { enabled ->
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(enabled = enabled)
                                )
                            }
                        )
                        OverlayDropdownPreference(
                            title = stringResource(id = R.string.reminder_lead_time),
                            items = listOf("5", "10", "15", "30"),
                            selectedIndex = when (businessState.reminderSettings.leadMinutes) {
                                5 -> 0
                                10 -> 1
                                15 -> 2
                                30 -> 3
                                else -> 1
                            },
                            onSelectedIndexChange = { index ->
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
                        OverlayDropdownPreference(
                            title = stringResource(id = R.string.reminder_repeat_mode),
                            items = listOf(
                                stringResource(id = R.string.reminder_repeat_always),
                                stringResource(id = R.string.reminder_repeat_daytime)
                            ),
                            selectedIndex = if (businessState.reminderSettings.repeatMode == RepeatMode.DAYTIME_ONLY) 1 else 0,
                            onSelectedIndexChange = { index ->
                                val mode = if (index == 1) RepeatMode.DAYTIME_ONLY else RepeatMode.ALWAYS
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(repeatMode = mode)
                                )
                            }
                        )
                        SwitchPreference(
                            title = stringResource(id = R.string.reminder_sound),
                            checked = businessState.reminderSettings.soundEnabled,
                            onCheckedChange = { enabled ->
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(soundEnabled = enabled)
                                )
                            }
                        )
                        SwitchPreference(
                            title = stringResource(id = R.string.reminder_vibration),
                            checked = businessState.reminderSettings.vibrationEnabled,
                            onCheckedChange = { enabled ->
                                actions.onUpdateReminderSettings(
                                    businessState.reminderSettings.copy(vibrationEnabled = enabled)
                                )
                            }
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.reminder_view_list),
                            summary = if (businessState.reminderItems.isEmpty()) {
                                stringResource(id = R.string.reminder_list_empty)
                            } else {
                                stringResource(id = R.string.reminder_count) + ": ${businessState.reminderItems.size}"
                            },
                            onClick = actions.onOpenReminderList
                        )
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_export_categories),
                            summary = stringResource(id = R.string.settings_export_categories_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Upload,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_export_categories),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onExportSatelliteCategories
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_import_categories),
                            summary = stringResource(id = R.string.settings_import_categories_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Download,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_import_categories),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onImportSatelliteCategories
                        )
                    }

                    Card(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.send_log),
                            startAction = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.send_log),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showSendLogDialog.value = true },
                        )
                        SendLogDialog(
                            show = showSendLogDialog.value,
                            onDismissRequest = { showSendLogDialog.value = false },
                            loadingDialog = loadingDialog
                        )
                        val about = stringResource(id = R.string.about)
                        ArrowPreference(
                            title = about,
                            startAction = {
                                Icon(
                                    Icons.Rounded.ContactPage,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = about,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenAbout,
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
        // UpdateDialogs 已提升到 MainScreen 顶层渲染（启动自动检查与设置页共用）
    }
}
