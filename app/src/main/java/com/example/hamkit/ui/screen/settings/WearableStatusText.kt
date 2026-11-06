package com.example.hamkit.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.hamkit.R
import com.example.hamkit.data.wearable.WearableStatus

/**
 * 手环链路状态 → 展示文案。
 *
 * 放在两套主题之间共用，避免 Material / Miuix 两份设置页各写一套 when，
 * 改文案时漏改一处。
 */
@Composable
fun wearableStatusText(status: WearableStatus): String = when (status) {
    WearableStatus.Disabled -> stringResource(id = R.string.wearable_enable_desc)
    WearableStatus.Connecting -> stringResource(id = R.string.wearable_status_connecting)
    WearableStatus.Connected -> stringResource(id = R.string.wearable_status_connected)
    WearableStatus.AppNotInstalled -> stringResource(id = R.string.wearable_status_app_missing)
    WearableStatus.NoDevice -> stringResource(id = R.string.wearable_status_no_device)
    is WearableStatus.Failed -> stringResource(id = R.string.wearable_status_failed, status.reason)
}
