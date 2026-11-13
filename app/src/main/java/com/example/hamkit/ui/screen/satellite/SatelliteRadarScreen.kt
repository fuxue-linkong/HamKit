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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.hamkit.data.satellite.SatelliteLiveInfo
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.component.StatCell
import com.example.hamkit.ui.component.StatEmphasis
import com.example.hamkit.ui.theme.SafeColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 卫星雷达图页入口：按当前界面风格分发到 Miuix / Material 实现。
 */
@Composable
fun SatelliteRadarScreen(catalogNumber: Int, onBack: () -> Unit = {}) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SatelliteRadarMiuix(catalogNumber, onBack)
        UiMode.Material -> SatelliteRadarMaterial(catalogNumber, onBack)
    }
}

/**
 * 卫星雷达图页（Miuix 风格）：
 * 实时雷达图（仰角环 + 方位刻度 + 卫星光点 + 日月 + 罗盘十字线），
 * 页面存活期间保持屏幕常亮。
 */
@Composable
fun SatelliteRadarMiuix(catalogNumber: Int, onBack: () -> Unit = {}) {
    val mainViewModel = LocalMainViewModel.current
    val locationState by mainViewModel.locationState
    val session = rememberSatelliteLiveSession(catalogNumber)
    val tracker = rememberSatelliteLiveTracker(catalogNumber)
    val liveInfo by if (tracker != null) {
        tracker.liveInfo.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<SatelliteLiveInfo?>(null) }
    }
    val trackHistory by if (tracker != null) {
        tracker.trackHistory.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList<Pair<Double, Double>>()) }
    }
    val nowMillis = liveInfo?.timestampMs ?: System.currentTimeMillis()
    val position = rememberStationPosition()

    // 罗盘（设备朝向）数据源；无旋转矢量传感器时自动为 null
    val phoneAzimuth by rememberPhoneCompassAzimuth(
        latitudeDeg = position?.first,
        longitudeDeg = position?.second,
        altitudeM = position?.third ?: 0.0
    )

    // 雷达图使用期间保持屏幕常亮
    rememberKeepScreenOn(active = true)

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
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部信息行：倒计时 + 蚀状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatPassCountdown(session.aosTime, session.losTime, nowMillis),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.primary
                )
                val eclipsed = liveInfo?.isEclipsed ?: false
                Text(
                    text = if (eclipsed) "● 蚀中" else "● 日照中",
                    fontSize = 13.sp,
                    color = if (eclipsed) {
                        SafeColors.statusColors(SafeColors.StatusTone.ERROR).foreground
                    } else {
                        SafeColors.statusColors(SafeColors.StatusTone.SUCCESS).foreground
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            if (session.tle == null || locationState.result == null) {
                RadarPlaceholderCard {
                    Text(
                        text = stringResource(R.string.satellite_need_location),
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary
                    )
                }
                return@Column
            }

            // 雷达图
            RadarView(
                azimuthDeg = liveInfo?.azimuthDeg,
                elevationDeg = liveInfo?.elevationDeg,
                isEclipsed = liveInfo?.isEclipsed ?: false,
                trackPositions = trackHistory.ifEmpty { null },
                sunAzimuthDeg = liveInfo?.sunAzimuthDeg,
                sunElevationDeg = liveInfo?.sunElevationDeg,
                moonAzimuthDeg = liveInfo?.moonAzimuthDeg,
                moonElevationDeg = liveInfo?.moonElevationDeg,
                phoneAzimuthDeg = phoneAzimuth,
                shouldShowSweep = true,
                shouldUseCompass = phoneAzimuth != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
            )

            // 底部实时数据行
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .padding(top = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = colorScheme.surface.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    RadarStatMiuix("方位", liveInfo?.azimuthDeg?.let { "%.0f°".format(it) } ?: "--")
                    RadarStatMiuix("仰角", liveInfo?.elevationDeg?.let { "%.0f°".format(it) } ?: "--")
                    RadarStatMiuix("距离", liveInfo?.rangeKm?.let { "%.0f km".format(it) } ?: "--")
                    RadarStatMiuix("高度", liveInfo?.altitudeKm?.let { "%.0f km".format(it) } ?: "--")
                }
            }
        }
    }
}

@Composable
private fun RadarStatMiuix(label: String, value: String) {
    StatCell(
        label = label,
        value = value,
        emphasis = StatEmphasis.Inline,
        alignment = Alignment.CenterHorizontally
    )
}

@Composable
private fun RadarPlaceholderCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = 0.6f)
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
