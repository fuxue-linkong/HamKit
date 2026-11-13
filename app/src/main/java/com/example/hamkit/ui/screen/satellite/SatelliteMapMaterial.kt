package com.example.hamkit.ui.screen.satellite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 卫星轨道地图页（Material3 风格）。
 * 与 [SatelliteMapMiuix] 功能一致：AMap 轨道地图 + 实时信息面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteMapMaterial(catalogNumber: Int, onBack: () -> Unit = {}) {
    val mainViewModel = LocalMainViewModel.current
    val session = rememberSatelliteLiveSession(catalogNumber)
    val tracker = rememberSatelliteLiveTracker(catalogNumber)
    val liveInfo by if (tracker != null) {
        tracker.liveInfo.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<SatelliteLiveInfo?>(null) }
    }
    val nowMillis = liveInfo?.timestampMs ?: System.currentTimeMillis()
    val position = rememberStationPosition()

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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // 底部实时信息面板（B5 整改：顶部 16dp 圆角，倒计时提为第一行，与 Miuix 版一致）
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // B5 整改：倒计时（关键信息）提为第一行主视觉
                    Text(
                        text = formatPassCountdown(session.aosTime, session.losTime, nowMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MapInfoTextMaterial(
                            label = "方位",
                            value = liveInfo?.azimuthDeg?.let { "%.1f°".format(it) } ?: "--"
                        )
                        MapInfoTextMaterial(
                            label = "仰角",
                            value = liveInfo?.elevationDeg?.let { "%.1f°".format(it) } ?: "--"
                        )
                        MapInfoTextMaterial(
                            label = "距离",
                            value = liveInfo?.rangeKm?.let { "%.0f km".format(it) } ?: "--"
                        )
                        MapInfoTextMaterial(
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
private fun MapInfoTextMaterial(label: String, value: String) {
    StatCell(
        label = label,
        value = value,
        emphasis = StatEmphasis.Inline,
        alignment = Alignment.CenterHorizontally
    )
}
