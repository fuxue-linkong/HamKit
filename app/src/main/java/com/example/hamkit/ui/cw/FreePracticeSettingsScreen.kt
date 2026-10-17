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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.data.cw.CharacterSet
import com.example.hamkit.data.cw.CWSettings
import com.example.hamkit.data.cw.PlayMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 自由练习设置页的 Miuix（MIUI）风格实现。
 *
 * 本函数由 [CWPracticeRouteScreen] 按界面风格分发调用：Material 模式下走同包的
 * FreePracticeSettingsScreenMaterial（参数列表与本函数完全一致，行为等价）。
 */
@Composable
fun FreePracticeSettingsScreenMiuix(
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
        item {
            Text(
                text = stringResource(R.string.wpm),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = settings.wpm.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(wpm = it.roundToInt())) },
                valueRange = 5f..50f,
                steps = 44
            )
            Text(text = "${settings.wpm} WPM")
        }

        item {
            Text(
                text = stringResource(R.string.frequency),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = settings.frequency.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(frequency = it.roundToInt())) },
                valueRange = 400f..800f,
                steps = 399
            )
            Text(text = "${settings.frequency} Hz")
        }

        item {
            Text(
                text = stringResource(R.string.character_set),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold
            )
            Text(text = settings.characterSet.name)
        }

        item {
            Text(
                text = stringResource(R.string.practice_length),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = settings.practiceLength.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(practiceLength = it.roundToInt())) },
                valueRange = 10f..500f,
                steps = 48
            )
            Text(text = "${settings.practiceLength} ${stringResource(R.string.char_count)}")
        }

        item {
            Text(
                text = stringResource(R.string.practice_duration),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = settings.practiceDuration.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(practiceDuration = it.roundToInt())) },
                valueRange = 1f..30f,
                steps = 28
            )
            Text(text = "${settings.practiceDuration} ${stringResource(R.string.minutes)}")
        }

        item {
            Text(
                text = stringResource(R.string.play_mode),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (settings.playMode == PlayMode.CONTINUOUS) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(stringResource(R.string.continuous))
                    }
                } else {
                    Button(
                        onClick = { onSettingsChange(settings.copy(playMode = PlayMode.CONTINUOUS)) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Transparent,
                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    ) {
                        Text(stringResource(R.string.continuous))
                    }
                }
                if (settings.playMode == PlayMode.INTERVAL) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(stringResource(R.string.interval))
                    }
                } else {
                    Button(
                        onClick = { onSettingsChange(settings.copy(playMode = PlayMode.INTERVAL)) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Transparent,
                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    ) {
                        Text(stringResource(R.string.interval))
                    }
                }
            }
        }

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
