package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.AprsViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale

@Composable
fun AprsMainScreen(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = appViewModel<AprsViewModel>()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingLicenseConfirm by viewModel.pendingLicenseConfirm.collectAsStateWithLifecycle()

    val isConnected = connectionState == AprsViewModel.ConnectionState.CONNECTED ||
        connectionState == AprsViewModel.ConnectionState.CONNECTING

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "APRS",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    Text(
                        text = "设置",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.AprsSettings) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // 连接状态卡片
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "●",
                            style = MiuixTheme.textStyles.title1,
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
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            // 首次连接先弹「持照与责任声明」，确认后才登录（HK-REQ-004）
                            onClick = { viewModel.requestConnect() },
                            modifier = Modifier.weight(1f),
                            enabled = !isConnected
                        ) { Text("连接") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.disconnect() },
                            modifier = Modifier.weight(1f),
                            enabled = isConnected
                        ) { Text("断开") }
                    }

                    // 只读模式提示（HK-REQ-003）：未填写 passcode 时仅接收不发送
                    if (settings.isReadOnly) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.aprs_readonly_banner),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.onSurfaceSecondary
                        )
                    }

                    lastError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "错误: $error",
                            style = MiuixTheme.textStyles.body2,
                            color = colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 站点列表入口
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(Route.AprsStations) }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "站点列表",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${stations.distinctBy { it.callsign.substringBefore("-") }.size} 个站点",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceSecondary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 地图入口
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(Route.AprsMap) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "站点地图",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "在地图上查看站点位置 →",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceSecondary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "最近站点",
                style = MiuixTheme.textStyles.body1,
                color = colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(stations.take(5)) { station ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = station.callsign,
                                style = MiuixTheme.textStyles.body1,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.4f, %.4f",
                                    station.latitude,
                                    station.longitude
                                ),
                                style = MiuixTheme.textStyles.body2,
                                color = colorScheme.onSurfaceSecondary
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
        AprsLicenseConfirmDialog(
            callsign = settings.fullCallsign,
            onConfirm = { viewModel.confirmLicenseAndConnect() },
            onDismiss = { viewModel.dismissLicenseConfirm() },
        )
    }
}

/**
 * 持照与责任声明确认对话框（HK-REQ-004）。
 *
 * 明示「你呼号下的所有流量由你本人负责」，并说明本应用不计算 / 不发放 passcode；
 * 未确认不可登录 APRS-IS。
 */
@Composable
private fun AprsLicenseConfirmDialog(
    callsign: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.aprs_license_title),
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = stringResource(R.string.aprs_license_message),
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onSurface,
                )
                if (callsign.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "本次连接呼号：$callsign",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceSecondary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(
                        text = stringResource(R.string.aprs_license_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.aprs_license_confirm),
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}
