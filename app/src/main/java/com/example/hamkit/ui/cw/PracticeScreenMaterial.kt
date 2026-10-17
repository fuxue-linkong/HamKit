package com.example.hamkit.ui.cw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.ui.theme.LocalCardAlpha

/**
 * CW 练习页的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版 [PracticeScreenMiuix] 逐项对应：课程标题、课程信息卡片、原文、
 * 摩斯电码、生成文本按钮、听抄输入框、开始/暂停/继续/停止控制、检查结果、
 * 准确率卡片与教程模式的「下一课」按钮。
 *
 * 全部状态都来自调用方（[CWPracticeRouteScreen] 收集 ViewModel 的 StateFlow 后传入），
 * 本函数自身不持有任何状态，回调也原样透传 —— 因此两种风格下同一份数据、同一套判定，
 * 不会因为切风格而丢失正在进行的练习。
 *
 * 本页是子页面：外层 [CWPracticeRouteScreen] 已提供顶栏与 Scaffold，
 * 这里只负责内容区，[contentPadding] 直接消费（与原 Miuix 版一致）。
 */
@Composable
fun PracticeScreenMaterial(
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
        // ── 标题：教程模式显示课程标题，自由练习显示「CW练习」──
        item {
            Text(
                text = if (courseTitle.isNotEmpty()) courseTitle else stringResource(R.string.cw_practice),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // ── 教程模式的课程信息：lessonInfo 为空（自由练习）时整块不渲染 ──
        if (lessonInfo.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                            .copy(alpha = LocalCardAlpha.current)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = lessonInfo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── 原文 ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "${stringResource(R.string.original_text)}:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 空文本的占位文案按模式区分，与 Miuix 版一字不差
                    Text(
                        text = currentText.ifEmpty { if (isTutorialMode) "加载中..." else "点击生成文本开始练习" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ── 摩斯电码（等宽字体，便于与原文逐字对齐）──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "${stringResource(R.string.morse_code)}:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = morseCode.ifEmpty { if (isTutorialMode) "加载中..." else "生成文本后显示" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ── 生成文本：教程模式的内容由课程决定，不提供手动生成 ──
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

        // ── 听抄输入框：播放中禁用输入（与 Miuix 版同一判定）──
        item {
            OutlinedTextField(
                value = userInput,
                onValueChange = onUserInputChange,
                label = { Text(stringResource(R.string.input_what_you_hear)) },
                enabled = !isPlaying,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── 播放控制：未播放时只有「开始练习」（无文本时禁用）；
        //    播放中则为「继续/暂停」+「停止」并排 ──
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

        // ── 检查结果：必须有输入且未在播放 ──
        item {
            Button(
                onClick = onCheckResults,
                modifier = Modifier.fillMaxWidth(),
                enabled = userInput.isNotEmpty() && !isPlaying
            ) {
                Text(stringResource(R.string.check_results))
            }
        }

        // ── 准确率卡片：仅在有输入时显示 ──
        item {
            if (userInput.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.accuracy),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "${accuracy.toInt()}%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            // accuracy 为 0~100 的百分数，进度条需要 0~1
                            progress = { accuracy / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        // 教程模式达标后显示「下一课」按钮，由用户主动推进（阈值与 Miuix 版一致）
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
