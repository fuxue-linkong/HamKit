package com.example.hamkit.ui.screen.satellite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Radar
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteLiveInfo
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.theme.LocalCardAlpha
import com.example.hamkit.ui.theme.LocalEnableBlur
import com.example.hamkit.ui.util.BlurredBar
import com.example.hamkit.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.util.Locale

/** ISS 的 NORAD 编号：其 ARISS SSTV 活动统一使用 PD-120 模式。 */
private const val ISS_NORAD = 25544

/**
 * 卫星详情页入口：按当前界面风格分发到 Miuix / Material 实现。
 */
@Composable
fun SatelliteDetailScreen(catalogNumber: Int, onBack: () -> Unit = {}) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SatelliteDetailMiuix(catalogNumber, onBack)
        UiMode.Material -> SatelliteDetailMaterial(catalogNumber, onBack)
    }
}

/**
 * 卫星详情页（Miuix 风格）：
 * 实时方位/仰角/距离/速度/高度大数字面板 + AOS→LOS 倒计时 +
 * 多普勒频率 + 蚀状态 + 雷达图/地图入口。
 */
@Composable
fun SatelliteDetailMiuix(catalogNumber: Int, onBack: () -> Unit = {}) {
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
    // 倒计时时钟：以追踪器时间戳驱动（1 秒一跳）；无追踪器时静态显示
    val nowMillis = liveInfo?.timestampMs ?: System.currentTimeMillis()

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                top.yukonga.miuix.kmp.basic.TopAppBar(
                    color = barColor,
                    title = session.satelliteName,
                    navigationIcon = {
                        Box(modifier = Modifier.padding(start = 12.dp)) {
                            IconButton(onClick = dropUnlessResumed { onBack() }) {
                                Icon(
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (session.tle == null || locationState.result == null) {
                SatelliteDetailPlaceholderCard {
                    Text(
                        text = stringResource(R.string.satellite_need_location),
                        color = colorScheme.onSurfaceVariantSummary
                    )
                }
                return@Column
            }

            // 实时大数字面板
            SatelliteLivePanelMiuix(liveInfo)

            // AOS→LOS 倒计时
            DetailCardMiuix {
                DetailRowMiuix(
                    label = "过境倒计时",
                    value = formatPassCountdown(session.aosTime, session.losTime, nowMillis)
                )
                if (session.maxElevation > 0.0) {
                    Spacer(Modifier.height(6.dp))
                    DetailRowMiuix(label = "最大仰角", value = "${session.maxElevation.toInt()}°")
                }
                if (session.isGeo) {
                    Spacer(Modifier.height(6.dp))
                    DetailRowMiuix(label = "轨道类型", value = "GEO / 深空（恒定可见）")
                }
            }

            // 多普勒频率
            DetailCardMiuix {
                val downlink = liveInfo?.dopplerDownlinkHz
                val uplink = liveInfo?.dopplerUplinkHz
                DetailRowMiuix(
                    label = "下行频率",
                    value = downlink?.let { formatFrequencyHz(it) } ?: "数据不可用"
                )
                Spacer(Modifier.height(6.dp))
                DetailRowMiuix(
                    label = "上行频率",
                    value = uplink?.let { formatFrequencyHz(it) } ?: "数据不可用"
                )
            }

            // 转发器列表（多收发器：名称 + 频率 + 模式 + 状态）
            val radios = radioMap[catalogNumber].orEmpty()
            if (radios.isNotEmpty()) {
                DetailCardMiuix {
                    DetailRowMiuix(
                        label = "转发器（${radios.count { it.isActive }}/${radios.size} 活跃）",
                        value = ""
                    )
                    Spacer(Modifier.height(6.dp))
                    radios.forEach { radio ->
                        TransceiverDetailRowMiuix(radio)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // 蚀状态 + 星下点 + 相位
            DetailCardMiuix {
                val eclipsed = liveInfo?.isEclipsed ?: false
                val eclipseColor = if (eclipsed) Color(0xFFF44336) else Color(0xFF4CAF50)
                DetailRowMiuix(
                    label = "日照状态",
                    value = if (eclipsed) "蚀中（地影）" else "日照中",
                    valueColor = eclipseColor
                )
                liveInfo?.let { info ->
                    Spacer(Modifier.height(6.dp))
                    DetailRowMiuix(
                        label = "星下点",
                        value = "%.2f°, %.2f°".format(info.subpointLatDeg, info.subpointLonDeg)
                    )
                    Spacer(Modifier.height(6.dp))
                    DetailRowMiuix(label = "轨道相位", value = "%.0f°".format(info.phaseDeg))
                }
            }

            // SSTV 卫星：一键接收入口（ARISS 的 ISS 活动使用 PD-120）
            if (radios.any { it.mode.contains("SSTV", ignoreCase = true) }) {
                DetailCardMiuix {
                    DetailRowMiuix(label = "SSTV 图像传输", value = "该卫星支持")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (catalogNumber == ISS_NORAD) {
                            "ARISS 活动通常在 145.800 MHz 使用 PD-120，单帧约 126 秒。"
                        } else {
                            "该卫星的转发器标注了 SSTV 模式，可进入解码器接收图像。"
                        },
                        color = colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = dropUnlessResumed {
                            navigator.push(
                                Route.SstvMain(
                                    // ISS 的 ARISS 活动统一使用 PD-120，直接锁定可省去 VIS 识别的失败风险
                                    presetModeName = if (catalogNumber == ISS_NORAD) "PD_120" else null
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()
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
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = colorScheme.onPrimary
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("打开雷达图")
                }
                Button(
                    onClick = dropUnlessResumed {
                        navigator.push(Route.SatelliteMap(catalogNumber))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Map,
                        contentDescription = null,
                        tint = colorScheme.onPrimary
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("打开地图")
                }
            }
        }
    }
}

// ---- 实时大数字面板 ----

@Composable
private fun SatelliteLivePanelMiuix(liveInfo: SatelliteLiveInfo?) {
    DetailCardMiuix {
        if (liveInfo == null) {
            Text(
                text = "正在计算实时位置…",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            return@DetailCardMiuix
        }
        // 方位 / 仰角大数字
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveBigNumberMiuix(
                label = "方位角",
                value = "%.1f°".format(liveInfo.azimuthDeg),
                modifier = Modifier.weight(1f)
            )
            LiveBigNumberMiuix(
                label = "仰角",
                value = "%.1f°".format(liveInfo.elevationDeg),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        // 距离 / 速度 / 高度
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveSmallNumberMiuix(
                label = "距离",
                value = "%.0f km".format(liveInfo.rangeKm),
                modifier = Modifier.weight(1f)
            )
            LiveSmallNumberMiuix(
                label = "速度",
                value = "%+.1f km/s".format(liveInfo.rangeRateMps / 1000.0),
                modifier = Modifier.weight(1f)
            )
            LiveSmallNumberMiuix(
                label = "高度",
                value = "%.0f km".format(liveInfo.altitudeKm),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (liveInfo.isAboveHorizon) {
                "● 在地平线以上"
            } else {
                "○ 在地平线以下"
            },
            fontSize = 13.sp,
            color = if (liveInfo.isAboveHorizon) Color(0xFF4CAF50) else colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun LiveBigNumberMiuix(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.primary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun LiveSmallNumberMiuix(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary
        )
    }
}

// ---- 通用卡片 ----

@Composable
private fun DetailCardMiuix(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
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
private fun DetailRowMiuix(
    label: String,
    value: String,
    valueColor: Color = colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun SatelliteDetailPlaceholderCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
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
 * 详情页单条转发器行：名称（含倒置标记）+ 上下行频率 + 模式 + 状态。
 */
@Composable
private fun TransceiverDetailRowMiuix(radio: RadioInfo) {
    val title = if (radio.inverted) "INV: ${radio.displayName}" else radio.displayName
    val (statusText, statusColor) = when (radio.status) {
        RadioInfo.STATUS_ACTIVE -> "活跃" to Color(0xFF4CAF50)
        RadioInfo.STATUS_FUTURE -> "未启用" to Color(0xFF42A5F5)
        else -> "停用" to colorScheme.onSurfaceVariantSummary
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.ifBlank { stringResource(R.string.transceiver_unnamed) },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Text(
                text = statusText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = statusColor
            )
        }
        val freqText = listOfNotNull(
            radio.downlinkHz?.let { "RX ${formatMhzText(it)}" },
            radio.uplinkHz?.let { "TX ${formatMhzText(it)}" },
            radio.displayMode.takeIf { it.isNotBlank() }
        ).joinToString("  ")
        if (freqText.isNotEmpty()) {
            Text(
                text = freqText,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/** 频率（Hz）→ MHz 文本 */
private fun formatMhzText(hz: Long): String = "%.3f MHz".format(Locale.US, hz / 1_000_000.0)
