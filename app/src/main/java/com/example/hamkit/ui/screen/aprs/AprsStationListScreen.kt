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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.aprs.AprsStation
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.AprsViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APRS 站点列表页分发入口：按当前 UI 风格选择 Miuix / Material 实现。
 *
 * 两者共用同一个 application 级 [AprsViewModel] 实例，切换风格不会丢状态。
 */
@Composable
fun AprsStationListScreen(
    onNavigateToStation: (AprsStation) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> AprsStationListScreenMiuix(
            onNavigateToStation = onNavigateToStation,
            onNavigateBack = onNavigateBack
        )

        UiMode.Material -> AprsStationListScreenMaterial(
            onNavigateToStation = onNavigateToStation,
            onNavigateBack = onNavigateBack
        )
    }
}

@Composable
fun AprsStationListScreenMiuix(
    onNavigateToStation: (AprsStation) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<AprsViewModel>()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "APRS 站点",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground
                        )
                    }
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
            Spacer(Modifier.height(8.dp))

            Text(
                text = "连接状态: ${connectionState.name} | 站点数: ${stations.distinctBy { it.callsign.substringBefore("-") }.size}",
                style = MiuixTheme.textStyles.body2,
                color = colorScheme.onSurfaceSecondary
            )

            Spacer(Modifier.height(16.dp))

            if (stations.isEmpty()) {
                Text(
                    text = "暂无站点数据。请确保已连接 APRS-IS 服务器。",
                    style = MiuixTheme.textStyles.body1,
                    color = colorScheme.onSurfaceSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical()
                        .scrollEndHaptic()
                ) {
                    items(stations) { station ->
                        AprsStationItem(
                            station = station,
                            onClick = { onNavigateToStation(station) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AprsStationItem(
    station: AprsStation,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = station.callsign,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = dateFormat.format(Date(station.timestamp)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = String.format(
                    Locale.US,
                    "%.4f, %.4f",
                    station.latitude,
                    station.longitude
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )

            if (station.comment.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = station.comment,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }

            station.altitude?.let { alt ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = String.format(Locale.US, "高度: %.0fm", alt),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
        }
    }
}
