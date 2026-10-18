package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.AprsViewModel
import java.util.Locale

/**
 * APRS 主页（Material 3 风格）。
 *
 * 与 [AprsMainScreenMiuix] 逐项对应：
 * - 连接状态卡片：状态色点 + 「状态: XXX」+ 连接 / 断开按钮（按是否已连接互相禁用）；
 * - 只读模式横幅（HK-REQ-003）与最近一次错误提示；
 * - 站点列表入口、站点地图入口；
 * - 「最近站点」列表（最多 5 条，字段与 Miuix 版一致）；
 * - 首次连接前的「持照与责任声明」确认弹窗（HK-REQ-004）。
 *
 * 两套实现共享同一个 [AprsViewModel]，因此连接状态、站点数据与弹窗状态天然同步。
 */
@Composable
fun AprsMainScreenMaterial(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<AprsViewModel>()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingLicenseConfirm by viewModel.pendingLicenseConfirm.collectAsStateWithLifecycle()

    // 连接中与已连接都视作「已连上」：连接中禁用「连接」、允许「断开」，与 Miuix 版同一判定
    val isConnected = connectionState == AprsViewModel.ConnectionState.CONNECTED ||
        connectionState == AprsViewModel.ConnectionState.CONNECTING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APRS") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onNavigate(Route.AprsSettings) }) {
                        Text("设置")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 连接状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 状态色点：绿=已连接 / 黄=连接中 / 红=出错 / 灰=未连接（与 Miuix 版同色值）
                        Text(
                            text = "●",
                            style = MaterialTheme.typography.headlineSmall,
                            color = when (connectionState) {
                                AprsViewModel.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                AprsViewModel.ConnectionState.CONNECTING -> Color(0xFFFFC107)
                                AprsViewModel.ConnectionState.ERROR -> Color(0xFFF44336)
                                else -> Color(0xFF9E9E9E)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "状态: ${connectionState.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            // 首次连接先弹「持照与责任声明」，确认后才登录（HK-REQ-004）
                            onClick = { viewModel.requestConnect() },
                            modifier = Modifier.weight(1f),
                            enabled = !isConnected
                        ) {
                            Text("连接")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.disconnect() },
                            modifier = Modifier.weight(1f),
                            enabled = isConnected
                        ) {
                            Text("断开")
                        }
                    }

                    // 只读模式提示（HK-REQ-003）：未填写 passcode 时仅接收不发送
                    if (settings.isReadOnly) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.aprs_readonly_banner),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    lastError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "错误: $error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 站点列表入口
            Card(
                onClick = { onNavigate(Route.AprsStations) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "站点列表",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 站点数按去掉 SSID 后的呼号去重，与 Miuix 版同一算法
                        text = "${stations.distinctBy { it.callsign.substringBefore("-") }.size} 个站点",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 地图入口
            Card(
                onClick = { onNavigate(Route.AprsMap) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "站点地图",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "在地图上查看站点位置 →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "最近站点",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(
                    items = stations.take(5),
                    // 呼号是 aprs_stations 表主键，可安全用作 key
                    key = { station -> station.callsign }
                ) { station ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = station.callsign,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            // 坐标固定 Locale.US：小数点必须是 '.'，避免地区设置影响可读性
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.4f, %.4f",
                                    station.latitude,
                                    station.longitude
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // 首次连接前的「持照与责任声明」确认（HK-REQ-004）
    if (pendingLicenseConfirm) {
        AprsLicenseConfirmDialogMaterial(
            callsign = settings.fullCallsign,
            onConfirm = { viewModel.confirmLicenseAndConnect() },
            onDismiss = { viewModel.dismissLicenseConfirm() },
        )
    }
}

/**
 * 持照与责任声明确认对话框（Material 3 风格，HK-REQ-004）。
 *
 * 与 Miuix 版 [AprsLicenseConfirmDialog] 同文案、同回调：
 * 明示「你呼号下的所有流量由你本人负责」，并说明本应用不计算 / 不发放 passcode；
 * 未确认不可登录 APRS-IS（取消 = 关闭弹窗，不建立连接）。
 */
@Composable
private fun AprsLicenseConfirmDialogMaterial(
    callsign: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aprs_license_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.aprs_license_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 呼号为空时（尚未填写身份）不展示这一行，与 Miuix 版一致
                if (callsign.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "本次连接呼号：$callsign",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        // 确认在右、取消在左，与 Miuix 版按钮语义顺序一致（确认 = 主操作）
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.aprs_license_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.aprs_license_cancel))
            }
        },
    )
}
