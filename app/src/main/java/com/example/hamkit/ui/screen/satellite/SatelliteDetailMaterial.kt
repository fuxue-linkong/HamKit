package com.example.hamkit.ui.screen.satellite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteLiveInfo
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.theme.LocalCardAlpha

/**
 * 卫星详情页（Material3 风格）。
 * 与 [SatelliteDetailMiuix] 功能一致：实时位置面板 + 倒计时 + 多普勒 + 蚀状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteDetailMaterial(catalogNumber: Int, onBack: () -> Unit = {}) {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val locationState by mainViewModel.locationState
    val radioMap by mainViewModel.radioMap
    val session = rememberSatelliteLiveSession(catalogNumber)
    val tracker = rememberSatelliteLiveTracker(catalogNumber)
    val liveInfo by if (tracker != null) {
        tracker.liveInfo.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<SatelliteLiveInfo?>(null) }
    }
    val nowMillis = liveInfo?.timestampMs ?: System.currentTimeMillis()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.satelliteName) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        )
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (session.tle == null || locationState.result == null) {
                SatelliteDetailPlaceholderMaterial {
                    Text(
                        text = stringResource(R.string.satellite_need_location),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            // 实时大数字面板
            SatelliteLivePanelMaterial(liveInfo)

            // AOS→LOS 倒计时
            DetailCardMaterial {
                DetailRowMaterial(
                    label = "过境倒计时",
                    value = formatPassCountdown(session.aosTime, session.losTime, nowMillis)
                )
                if (session.maxElevation > 0.0) {
                    Spacer(Modifier.height(6.dp))
                    DetailRowMaterial(label = "最大仰角", value = "${session.maxElevation.toInt()}°")
                }
                if (session.isGeo) {
                    Spacer(Modifier.height(6.dp))
                    DetailRowMaterial(label = "轨道类型", value = "GEO / 深空（恒定可见）")
                }
            }

            // 多普勒频率
            DetailCardMaterial {
                val downlink = liveInfo?.dopplerDownlinkHz
                val uplink = liveInfo?.dopplerUplinkHz
                DetailRowMaterial(
                    label = "下行频率",
                    value = downlink?.let { formatFrequencyHz(it) } ?: "数据不可用"
                )
                Spacer(Modifier.height(6.dp))
                DetailRowMaterial(
                    label = "上行频率",
                    value = uplink?.let { formatFrequencyHz(it) } ?: "数据不可用"
                )
            }

            // 转发器列表（多收发器：名称 + 频率 + 模式 + 状态）
            val radios = radioMap[catalogNumber].orEmpty()
            if (radios.isNotEmpty()) {
                DetailCardMaterial {
                    DetailRowMaterial(
                        label = "转发器（${radios.count { it.isActive }}/${radios.size} 活跃）",
                        value = ""
                    )
                    Spacer(Modifier.height(6.dp))
                    radios.forEach { radio ->
                        TransceiverDetailRowMaterial(radio)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // 蚀状态 + 星下点 + 相位
            DetailCardMaterial {
                val eclipsed = liveInfo?.isEclipsed ?: false
                val eclipseColor = if (eclipsed) Color(0xFFF44336) else Color(0xFF4CAF50)
                DetailRowMaterial(
                    label = "日照状态",
                    value = if (eclipsed) "蚀中（地影）" else "日照中",
                    valueColor = eclipseColor
                )
                liveInfo?.let { info ->
                    Spacer(Modifier.height(6.dp))
                    DetailRowMaterial(
                        label = "星下点",
                        value = "%.2f°, %.2f°".format(info.subpointLatDeg, info.subpointLonDeg)
                    )
                    Spacer(Modifier.height(6.dp))
                    DetailRowMaterial(label = "轨道相位", value = "%.0f°".format(info.phaseDeg))
                }
            }

            // SSTV 卫星：一键接收入口（ARISS 的 ISS 活动使用 PD-120）
            if (radios.any { it.mode.contains("SSTV", ignoreCase = true) }) {
                DetailCardMaterial {
                    DetailRowMaterial(label = "SSTV 图像传输", value = "该卫星支持")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (catalogNumber == 25544) {
                            "ARISS 活动通常在 145.800 MHz 使用 PD-120，单帧约 126 秒。"
                        } else {
                            "该卫星的转发器标注了 SSTV 模式，可进入解码器接收图像。"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = dropUnlessResumed {
                            navigator.push(
                                Route.SstvMain(
                                    presetModeName = if (catalogNumber == 25544) "PD_120" else null
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("接收 SSTV 图像")
                    }
                }
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = dropUnlessResumed {
                        navigator.push(Route.SatelliteRadar(catalogNumber))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Radar,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("打开雷达图")
                }
                Button(
                    onClick = dropUnlessResumed {
                        navigator.push(Route.SatelliteMap(catalogNumber))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Map,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("打开地图")
                }
            }
        }
    }
}

// ---- 实时大数字面板 ----

@Composable
private fun SatelliteLivePanelMaterial(liveInfo: SatelliteLiveInfo?) {
    DetailCardMaterial {
        if (liveInfo == null) {
            Text(
                text = "正在计算实时位置…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@DetailCardMaterial
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveBigNumberMaterial(
                label = "方位角",
                value = "%.1f°".format(liveInfo.azimuthDeg),
                modifier = Modifier.weight(1f)
            )
            LiveBigNumberMaterial(
                label = "仰角",
                value = "%.1f°".format(liveInfo.elevationDeg),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveSmallNumberMaterial(
                label = "距离",
                value = "%.0f km".format(liveInfo.rangeKm),
                modifier = Modifier.weight(1f)
            )
            LiveSmallNumberMaterial(
                label = "速度",
                value = "%+.1f km/s".format(liveInfo.rangeRateMps / 1000.0),
                modifier = Modifier.weight(1f)
            )
            LiveSmallNumberMaterial(
                label = "高度",
                value = "%.0f km".format(liveInfo.altitudeKm),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (liveInfo.isAboveHorizon) "● 在地平线以上" else "○ 在地平线以下",
            style = MaterialTheme.typography.bodySmall,
            color = if (liveInfo.isAboveHorizon) Color(0xFF4CAF50)
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LiveBigNumberMaterial(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
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

@Composable
private fun LiveSmallNumberMaterial(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- 通用卡片 ----

@Composable
private fun DetailCardMaterial(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun DetailRowMaterial(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun SatelliteDetailPlaceholderMaterial(content: @Composable () -> Unit) {
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

/**
 * 详情页单条转发器行（Material 版）。
 */
@Composable
private fun TransceiverDetailRowMaterial(radio: RadioInfo) {
    val title = if (radio.inverted) "INV: ${radio.displayName}" else radio.displayName
    val (statusText, statusColor) = when (radio.status) {
        RadioInfo.STATUS_ACTIVE -> "活跃" to Color(0xFF4CAF50)
        RadioInfo.STATUS_FUTURE -> "未启用" to Color(0xFF42A5F5)
        else -> "停用" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.ifBlank { stringResource(R.string.transceiver_unnamed) },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = statusColor
            )
        }
        val freqText = listOfNotNull(
            radio.downlinkHz?.let { "RX ${formatMhzTextMaterial(it)}" },
            radio.uplinkHz?.let { "TX ${formatMhzTextMaterial(it)}" },
            radio.displayMode.takeIf { it.isNotBlank() }
        ).joinToString("  ")
        if (freqText.isNotEmpty()) {
            Text(
                text = freqText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 频率（Hz）→ MHz 文本 */
private fun formatMhzTextMaterial(hz: Long): String =
    "%.3f MHz".format(java.util.Locale.US, hz / 1_000_000.0)
