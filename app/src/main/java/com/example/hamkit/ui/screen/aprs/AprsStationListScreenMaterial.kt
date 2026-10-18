package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.aprs.AprsStation
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.AprsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APRS 站点列表页（Material 3 风格）。
 *
 * 与 [AprsStationListScreenMiuix] 行为完全一致：顶部一行「连接状态 | 站点数」汇总，
 * 无数据时给出去连接服务器的提示，否则按 ViewModel 给出的顺序（已按时间倒序）
 * 逐条展示呼号 / 时间 / 坐标 / 评论 / 高度。
 */
@Composable
fun AprsStationListScreenMaterial(
    onNavigateToStation: (AprsStation) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<AprsViewModel>()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APRS 站点") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
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
            Spacer(Modifier.height(8.dp))

            Text(
                text = "连接状态: ${connectionState.name} | 站点数: " +
                        "${stations.distinctBy { it.callsign.substringBefore("-") }.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            if (stations.isEmpty()) {
                Text(
                    text = "暂无站点数据。请确保已连接 APRS-IS 服务器。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = stations,
                        // 呼号是站点的唯一标识；带 key 可让新报文到达时只重绘受影响的条目
                        key = { station -> station.callsign }
                    ) { station ->
                        AprsStationCardMaterial(
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

/** 单个站点卡片：与 Miuix 版 [AprsStationItem] 字段逐项对应 */
@Composable
private fun AprsStationCardMaterial(
    station: AprsStation,
    onClick: () -> Unit
) {
    // SimpleDateFormat 非线程安全且构造成本高，与 Miuix 版一样按条目 remember 复用
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = station.callsign,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = dateFormat.format(Date(station.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            // 坐标固定用 Locale.US：小数点必须是 '.'，避免地区设置改成逗号后坐标无法复制使用
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

            if (station.comment.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = station.comment,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            station.altitude?.let { alt ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = String.format(Locale.US, "高度: %.0fm", alt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
