package com.example.hamkit.ui.screen.satellite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.data.satellite.SatelliteLiveInfo
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.component.StatCell
import com.example.hamkit.ui.component.StatEmphasis
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 卫星轨道地图页入口：按当前界面风格分发到 Miuix / Material 实现。
 */
@Composable
fun SatelliteMapScreen(catalogNumber: Int, onBack: () -> Unit = {}) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SatelliteMapMiuix(catalogNumber, onBack)
        UiMode.Material -> SatelliteMapMaterial(catalogNumber, onBack)
    }
}

/**
 * 卫星轨道地图页（Miuix 风格）：
 * AMap 地图 + 地面站/星下点/日月 Marker + 地面轨迹 + 覆盖圆 + 底部实时信息面板。
 */
@Composable
fun SatelliteMapMiuix(catalogNumber: Int, onBack: () -> Unit = {}) {
    val mainViewModel = LocalMainViewModel.current
    val locationState by mainViewModel.locationState
    val session = rememberSatelliteLiveSession(catalogNumber)
    val tracker = rememberSatelliteLiveTracker(catalogNumber)
    val liveInfo by if (tracker != null) {
        tracker.liveInfo.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<SatelliteLiveInfo?>(null) }
    }
    val nowMillis = liveInfo?.timestampMs ?: System.currentTimeMillis()
    val position = rememberStationPosition()

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.TopAppBar(
                color = colorScheme.surface,
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
        ) {
            if (session.tle == null || position == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.satellite_need_location),
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary
                    )
                }
                return@Column
            }

            SatelliteOrbitMap(
                tle = session.tle,
                stationLatDeg = position.first,
                stationLonDeg = position.second,
                liveInfo = liveInfo,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // 底部实时信息面板（B5 整改：顶部 16dp 圆角，面板感更明确）
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                cornerRadius = 16.dp,
                colors = CardDefaults.defaultColors(
                    color = colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // B5 整改：倒计时（关键信息）提为第一行主视觉，指标行放第二行
                    Text(
                        text = formatPassCountdown(session.aosTime, session.losTime, nowMillis),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MapInfoTextMiuix(
                            label = "方位",
                            value = liveInfo?.azimuthDeg?.let { "%.1f°".format(it) } ?: "--"
                        )
                        MapInfoTextMiuix(
                            label = "仰角",
                            value = liveInfo?.elevationDeg?.let { "%.1f°".format(it) } ?: "--"
                        )
                        MapInfoTextMiuix(
                            label = "距离",
                            value = liveInfo?.rangeKm?.let { "%.0f km".format(it) } ?: "--"
                        )
                        MapInfoTextMiuix(
                            label = "高度",
                            value = liveInfo?.altitudeKm?.let { "%.0f km".format(it) } ?: "--"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapInfoTextMiuix(label: String, value: String) {
    StatCell(
        label = label,
        value = value,
        emphasis = StatEmphasis.Inline,
        alignment = Alignment.CenterHorizontally
    )
}
