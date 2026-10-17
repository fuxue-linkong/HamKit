package com.example.hamkit.ui.cw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedListItem

/**
 * CW 练习主菜单的 Material（Material 3）风格实现。
 *
 * 三个入口（自由练习 / 教程练习 / 摩斯电码编解码）与 Miuix 版一一对应，同样使用
 * 三张可点击卡片；这里改用 Material 的分段列表承载 —— 它是 Material 3 表达
 * 「一组并列入口」的标准形态，且自带触感反馈与点击涟漪。
 */
@Composable
fun CWPracticeScreenMaterial(
    onBackClick: () -> Unit,
    onFreePracticeClick: () -> Unit,
    onTutorialClick: () -> Unit,
    onMorseCodeClick: () -> Unit,
    contentPadding: PaddingValues,
) {
    SegmentedColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = listOf(
            {
                CwEntryItem(
                    title = stringResource(R.string.free_practice),
                    description = stringResource(R.string.free_practice_desc),
                    onClick = onFreePracticeClick,
                )
            },
            {
                CwEntryItem(
                    title = stringResource(R.string.tutorial_practice),
                    description = stringResource(R.string.tutorial_practice_desc),
                    onClick = onTutorialClick,
                )
            },
            {
                CwEntryItem(
                    title = stringResource(R.string.morsecode_codec),
                    description = stringResource(R.string.morsecode_codec_desc),
                    onClick = onMorseCodeClick,
                )
            },
        ),
    )
}

/** 一个入口项：粗体标题 + 描述。 */
@Composable
private fun CwEntryItem(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
