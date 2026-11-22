package com.example.hamkit.ui.screen.lab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.screen.settings.wearableStatusText
import com.example.hamkit.ui.theme.LocalEnableBlur
import com.example.hamkit.ui.util.BlurredBar
import com.example.hamkit.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 实验性功能路由入口：按当前界面风格分发到 Miuix / Material 实现。
 *
 * 集中放置尚未完善或仅部分设备可见的功能（手环提醒、小米超级岛）
 * 以及各类测试入口（测试过境通知等），与设置页稳定功能隔离。
 */
@Composable
fun LabRouteScreen() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> LabRouteScreenMiuix()
        UiMode.Material -> LabRouteScreenMaterial()
    }
}

/**
 * Miuix（MIUI）风格实现：分组 Card + SwitchPreference/ArrowPreference，
 * 结构与设置页手环分组一致；顶栏复用 [BlurredBar] 模糊效果。
 */
@Composable
fun LabRouteScreenMiuix() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val settings by mainViewModel.reminderSettings
    val wearableStatus by mainViewModel.wearableStatus

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    MiuixTheme {
        Scaffold(
            topBar = {
                BlurredBar(backdrop) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.lab_title),
                        navigationIcon = {
                            Box(modifier = Modifier.padding(start = 12.dp)) {
                                IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                                    androidx.compose.material3.Icon(
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentPadding = innerPadding
            ) {
                // 说明卡：声明实验性质，避免用户误当作稳定功能
                item {
                    Card(
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.lab_desc),
                            modifier = Modifier.padding(12.dp),
                            color = colorScheme.onBackground
                        )
                    }
                }
                // 手环提醒：整体从设置页迁移而来
                item {
                    Card(
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.wearable_enable),
                            summary = if (settings.wearableEnabled) {
                                wearableStatusText(wearableStatus)
                            } else {
                                stringResource(id = R.string.wearable_enable_desc)
                            },
                            startAction = {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.Watch,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.wearable_enable),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = settings.wearableEnabled,
                            onCheckedChange = { enabled ->
                                mainViewModel.updateReminderSettings(
                                    settings.copy(wearableEnabled = enabled)
                                )
                            }
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.wearable_test),
                            summary = stringResource(id = R.string.wearable_test_desc),
                            startAction = {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.Watch,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.wearable_test),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = mainViewModel::sendTestWearableAlert
                        )
                    }
                }
                // 小米超级岛：整体从设置页迁移而来
                item {
                    Card(
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.island_enable),
                            summary = stringResource(id = R.string.island_enable_desc),
                            startAction = {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.Lightbulb,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.island_enable),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = settings.islandEnabled,
                            onCheckedChange = { enabled ->
                                mainViewModel.updateReminderSettings(
                                    settings.copy(islandEnabled = enabled)
                                )
                            }
                        )
                    }
                }
                // 通知测试：验证普通通知与超级岛形态
                item {
                    Card(
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.lab_test_pass_notification),
                            summary = stringResource(id = R.string.lab_test_pass_notification_desc),
                            startAction = {
                                androidx.compose.material3.Icon(
                                    Icons.Rounded.Notifications,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.lab_test_pass_notification),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = mainViewModel::sendTestPassNotification
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}
