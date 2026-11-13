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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.hamkit.data.satellite.SatelliteLiveInfo
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.component.StatCell
import com.example.hamkit.ui.component.StatEmphasis
import com.example.hamkit.ui.theme.SafeColors

/**
 * 卫星雷达图页（Material3 风格）。
 * 与 [SatelliteRadarMiuix] 功能一致，页面存活期间保持屏幕常亮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteRadarMaterial(catalogNumber: Int, onBack: () -> Unit = {}) {
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

    val phoneAzimuth by rememberPhoneCompassAzimuth(
        latitudeDeg = position?.first,
        longitudeDeg = position?.second,
        altitudeM = position?.third ?: 0.0
    )

    rememberKeepScreenOn(active = true)

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
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                // B4 整改：限宽 460dp 与雷达图/数据卡同轴（与 Miuix 版一致）
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatPassCountdown(session.aosTime, session.losTime, nowMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                val eclipsed = liveInfo?.isEclipsed ?: false
                Text(
                    text = if (eclipsed) "● 蚀中" else "● 日照中",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (eclipsed) {
                        SafeColors.statusColors(SafeColors.StatusTone.ERROR).foreground
                    } else {
                        SafeColors.statusColors(SafeColors.StatusTone.SUCCESS).foreground
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            if (session.tle == null || locationState.result == null) {
                RadarPlaceholderMaterial {
                    Text(
                        text = stringResource(R.string.satellite_need_location),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

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

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .padding(top = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    RadarStatMaterial("方位", liveInfo?.azimuthDeg?.let { "%.0f°".format(it) } ?: "--")
                    RadarStatMaterial("仰角", liveInfo?.elevationDeg?.let { "%.0f°".format(it) } ?: "--")
                    RadarStatMaterial("距离", liveInfo?.rangeKm?.let { "%.0f km".format(it) } ?: "--")
                    RadarStatMaterial("高度", liveInfo?.altitudeKm?.let { "%.0f km".format(it) } ?: "--")
                }
            }
        }
    }
}

@Composable
private fun RadarStatMaterial(label: String, value: String) {
    StatCell(
        label = label,
        value = value,
        emphasis = StatEmphasis.Inline,
        alignment = Alignment.CenterHorizontally
    )
}

@Composable
private fun RadarPlaceholderMaterial(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
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
