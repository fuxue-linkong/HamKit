package com.example.hamkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.data.reminder.ReminderItem
import com.example.hamkit.ui.component.material.ExpressiveSwitch
import com.example.hamkit.ui.theme.LocalCardAlpha
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 过境提醒列表的内容区（Material 3 风格）。
 *
 * 与 Miuix 版 [ReminderListScreen] 参数与行为完全一致，只替换组件与配色：
 * 空态（卫星图标 + 提示）、每颗卫星一张卡片（名称 / NORAD / 启用开关 / AOS / LOS /
 * 仰角 / 方位 / 模式 / 删除）。时间格式与字段顺序刻意保持一致，避免两种风格下
 * 同一份数据看起来像两个功能。
 */
@Composable
fun ReminderListScreenMaterial(
    items: List<ReminderItem>,
    onToggleEnabled: (Int, Boolean) -> Unit,
    onDelete: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (items.isEmpty()) {
            ReminderEmptyStateMaterial()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.catalogNumber }) { item ->
                    ReminderItemCardMaterial(
                        item = item,
                        onToggleEnabled = { onToggleEnabled(item.catalogNumber, it) },
                        onDelete = { onDelete(item.catalogNumber) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderEmptyStateMaterial() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.SatelliteAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = stringResource(R.string.reminder_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReminderItemCardMaterial(
    item: ReminderItem,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val localFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
    val utcFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.of("UTC"))

    val aosLocal = localFormatter.format(Instant.ofEpochMilli(item.aosTimeMillis))
    val losLocal = localFormatter.format(Instant.ofEpochMilli(item.losTimeMillis))
    val aosUtc = utcFormatter.format(Instant.ofEpochMilli(item.aosTimeMillis))

    val modeText = if (item.modes.isEmpty()) {
        stringResource(R.string.mode_unknown)
    } else {
        item.modes.joinToString("/")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        // 停用的提醒整卡降透明度：与 Miuix 版同一套「灰掉」语义，只是换成 Material 的色板
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = "NORAD: ${item.catalogNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ExpressiveSwitch(
                    checked = item.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }
            Text(
                text = "AOS: $aosLocal (UTC $aosUtc)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "LOS: $losLocal | ${stringResource(R.string.elevation)}: " +
                    "${item.maxElevation.toInt()}° | ${stringResource(R.string.aos_azimuth)}: " +
                    "${item.aosAzimuth}°",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${stringResource(R.string.mode)}: $modeText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
