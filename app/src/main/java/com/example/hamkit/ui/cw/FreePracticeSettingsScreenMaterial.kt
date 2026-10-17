package com.example.hamkit.ui.cw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.data.cw.CWSettings
import com.example.hamkit.data.cw.PlayMode
import kotlin.math.roundToInt

/**
 * 自由练习设置页的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版 [FreePracticeSettingsScreenMiuix] 逐项对应，顺序与取值区间完全一致：
 * 速度（5~50 WPM，步进 44）、音调频率（400~800 Hz，步进 399）、字符集、
 * 练习长度（10~500 字符，步进 48）、练习时长（1~30 分钟，步进 28）、
 * 播放模式（连续 / 间隔）、开始练习。
 *
 * 设置数据由调用方（[CWPracticeRouteScreen]）从 ViewModel 收集后传入，
 * 每次改动都通过 [onSettingsChange] 回写整份 [CWSettings]（与原版同一套 copy 逻辑），
 * 因此两种风格共享同一份设置，切换风格不会丢配置。
 *
 * 本页是子页面：外层 [CWPracticeRouteScreen] 已提供顶栏与 Scaffold，
 * 这里只负责内容区，[contentPadding] 直接消费（与原 Miuix 版一致）。
 */
@Composable
fun FreePracticeSettingsScreenMaterial(
    settings: CWSettings,
    onSettingsChange: (CWSettings) -> Unit,
    onStartPractice: () -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 速度（WPM）：WPM 越低越容易听辨，5~50 是业余练习的常用区间 ──
        item {
            CwSettingsCard(title = stringResource(R.string.wpm)) {
                Slider(
                    value = settings.wpm.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(wpm = it.roundToInt())) },
                    valueRange = 5f..50f,
                    steps = 44,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${settings.wpm} WPM",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 音调频率：影响听感，同时也是 CW 滤波器的中心频率 ──
        item {
            CwSettingsCard(title = stringResource(R.string.frequency)) {
                Slider(
                    value = settings.frequency.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(frequency = it.roundToInt())) },
                    valueRange = 400f..800f,
                    steps = 399,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${settings.frequency} Hz",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 字符集：沿用 Miuix 版的展示内容（枚举名）──
        item {
            CwSettingsCard(title = stringResource(R.string.character_set)) {
                Text(
                    text = settings.characterSet.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 练习长度（字符数）──
        item {
            CwSettingsCard(title = stringResource(R.string.practice_length)) {
                Slider(
                    value = settings.practiceLength.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(practiceLength = it.roundToInt())) },
                    valueRange = 10f..500f,
                    steps = 48,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${settings.practiceLength} ${stringResource(R.string.char_count)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 练习时长（分钟）──
        item {
            CwSettingsCard(title = stringResource(R.string.practice_duration)) {
                Slider(
                    value = settings.practiceDuration.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(practiceDuration = it.roundToInt())) },
                    valueRange = 1f..30f,
                    steps = 28,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${settings.practiceDuration} ${stringResource(R.string.minutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 播放模式：选中项用填充按钮、未选中用描边按钮。
        //    点击选中项仍是空回调（对应 Miuix 版的 onClick = {}），只有未选中项才回写设置 ──
        item {
            CwSettingsCard(title = stringResource(R.string.play_mode)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (settings.playMode == PlayMode.CONTINUOUS) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.continuous))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSettingsChange(settings.copy(playMode = PlayMode.CONTINUOUS)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.continuous))
                        }
                    }
                    if (settings.playMode == PlayMode.INTERVAL) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.interval))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSettingsChange(settings.copy(playMode = PlayMode.INTERVAL)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.interval))
                        }
                    }
                }
            }
        }

        // ── 开始练习：与上方设置留出一段间距，避免误触 ──
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStartPractice,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.start_practice))
            }
        }
    }
}

/**
 * 设置分区卡片：统一的标题 + 内容排版。
 *
 * Material 版各分区共用同一底色（surfaceContainerHigh），与其它页面的 Material 实现
 * 保持同一视觉语言；Miuix 版是没有卡片的扁平排版，这里属于风格差异，不影响行为。
 */
@Composable
private fun CwSettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
