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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.ui.theme.LocalCardAlpha
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * CW 练习页的 Miuix（MIUI）风格实现。
 *
 * 本函数由 [CWPracticeRouteScreen] 按界面风格分发调用：Material 模式下走同包的
 * PracticeScreenMaterial（参数列表与本函数完全一致，行为等价）。
 */
@Composable
fun PracticeScreenMiuix(
    currentText: String,
    morseCode: String,
    userInput: String,
    isPlaying: Boolean,
    isPaused: Boolean,
    accuracy: Float,
    courseTitle: String = "",
    lessonInfo: String = "",
    isTutorialMode: Boolean = false,
    onUserInputChange: (String) -> Unit,
    onGenerateText: () -> Unit,
    onStartPractice: () -> Unit,
    onPausePractice: () -> Unit,
    onResumePractice: () -> Unit,
    onStopPractice: () -> Unit,
    onCheckResults: () -> Unit,
    onNextLesson: () -> Unit = {},
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
                text = if (courseTitle.isNotEmpty()) courseTitle else stringResource(R.string.cw_practice),
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold
            )
        }

        if (lessonInfo.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = LocalCardAlpha.current)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = lessonInfo,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "${stringResource(R.string.original_text)}:",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Text(
                        text = currentText.ifEmpty { if (isTutorialMode) "加载中..." else "点击生成文本开始练习" },
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "${stringResource(R.string.morse_code)}:",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Text(
                        text = morseCode.ifEmpty { if (isTutorialMode) "加载中..." else "生成文本后显示" },
                        style = MiuixTheme.textStyles.body2,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (!isTutorialMode) {
            item {
                Button(
                    onClick = onGenerateText,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.generate_text))
                }
            }
        }

        item {
            TextField(
                value = userInput,
                onValueChange = onUserInputChange,
                label = stringResource(R.string.input_what_you_hear),
                enabled = !isPlaying,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isPlaying) {
                    Button(
                        onClick = onStartPractice,
                        modifier = Modifier.weight(1f),
                        enabled = currentText.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.start_practice))
                    }
                } else {
                    if (isPaused) {
                        Button(
                            onClick = onResumePractice,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.resume_practice))
                        }
                    } else {
                        Button(
                            onClick = onPausePractice,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.pause_practice))
                        }
                    }
                    Button(
                        onClick = onStopPractice,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.stop_practice))
                    }
                }
            }
        }

        item {
            Button(
                onClick = onCheckResults,
                modifier = Modifier.fillMaxWidth(),
                enabled = userInput.isNotEmpty() && !isPlaying
            ) {
                Text(stringResource(R.string.check_results))
            }
        }

        item {
            if (userInput.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.accuracy),
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "${accuracy.toInt()}%",
                            style = MiuixTheme.textStyles.title1,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = accuracy / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                foregroundColor = MiuixTheme.colorScheme.primary,
                                backgroundColor = MiuixTheme.colorScheme.surfaceVariant
                            )
                        )
                        // 教程模式达标后显示"下一课"按钮，由用户主动推进
                        if (isTutorialMode && accuracy >= 80f) {
                            Button(
                                onClick = onNextLesson,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Text(stringResource(R.string.next_lesson))
                            }
                        }
                    }
                }
            }
        }
    }
}
