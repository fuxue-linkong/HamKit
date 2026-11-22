package com.example.hamkit.ui.screen.lab

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedListItem
import com.example.hamkit.ui.component.material.SegmentedSwitchItem
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.screen.settings.wearableStatusText

/**
 * 实验性功能的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版功能等价：说明卡 + 手环提醒（开关/测试）+ 小米超级岛开关 +
 * 测试过境通知入口。与 Miuix 版差异同提醒列表页：保持标准 Material 顶栏，
 * 不做模糊背景（避免与 Material 层级语义冲突）。
 */
@Composable
fun LabRouteScreenMaterial() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val settings by mainViewModel.reminderSettings
    val wearableStatus by mainViewModel.wearableStatus

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lab_title)) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = innerPadding,
        ) {
            item {
                Text(
                    text = stringResource(R.string.lab_desc),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
            // 手环提醒：整体从设置页迁移而来
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    content = buildList {
                        add {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.Watch,
                                title = stringResource(id = R.string.wearable_enable),
                                summary = if (settings.wearableEnabled) {
                                    wearableStatusText(wearableStatus)
                                } else {
                                    stringResource(id = R.string.wearable_enable_desc)
                                },
                                checked = settings.wearableEnabled,
                                onCheckedChange = { enabled ->
                                    mainViewModel.updateReminderSettings(
                                        settings.copy(wearableEnabled = enabled)
                                    )
                                }
                            )
                        }
                        add {
                            SegmentedListItem(
                                onClick = mainViewModel::sendTestWearableAlert,
                                headlineContent = { Text(stringResource(id = R.string.wearable_test)) },
                                supportingContent = { Text(stringResource(id = R.string.wearable_test_desc)) },
                                leadingContent = { Icon(Icons.Filled.Watch, null) },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                            )
                        }
                    }
                )
            }
            // 通知测试：验证普通通知与超级岛形态
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    content = listOf {
                        SegmentedListItem(
                            onClick = mainViewModel::sendTestPassNotification,
                            headlineContent = { Text(stringResource(id = R.string.lab_test_pass_notification)) },
                            supportingContent = { Text(stringResource(id = R.string.lab_test_pass_notification_desc)) },
                            leadingContent = { Icon(Icons.Filled.Notifications, null) },
                            trailingContent = {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        )
                    }
                )
            }
        }
    }
}
