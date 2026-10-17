package com.example.hamkit.ui.cw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.ui.theme.LocalCardAlpha

/**
 * 教程课程列表的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版 [TutorialListScreenMiuix] 逐项对应，行为完全等价：
 *  - 同样的四门课程（Koch / 字符组 / 呼号 / 文本）、同样的标题与描述文案、
 *    同样的进度计算（`courseProgress[id] ?: 0f`，已完成课数按总课数折算）；
 *  - 列表项排版一致：标题、描述、`进度: 已完成/总数 (百分比)`、进度条；
 *  - 点击任意课程回调 [onLessonClick]（课程 id 原样透传）。
 *
 * [TutorialLesson] 沿用 Miuix 文件里的同一份定义，课程数据不重复声明，
 * 避免两套风格出现课程数量或文案分叉。
 *
 * 本页是子页面：外层路由（`CWPracticeRouteScreenMaterial`）已提供 Scaffold 与顶栏，
 * 这里只渲染内容并消费 [contentPadding]。
 */
@Composable
fun TutorialListScreenMaterial(
    onLessonClick: (Int) -> Unit,
    courseProgress: Map<Int, Float> = emptyMap(),
    contentPadding: PaddingValues
) {
    val lessons = listOf(
        TutorialLesson(
            id = 1,
            title = "Koch课程",
            description = "使用Koch方法渐进式学习摩尔斯电码",
            progress = courseProgress[1] ?: 0.0f,
            totalLessons = 26,
            completedLessons = ((courseProgress[1] ?: 0.0f) * 26).toInt()
        ),
        TutorialLesson(
            id = 2,
            title = "字符组练习",
            description = "练习特定字符组合，提高识别速度",
            progress = courseProgress[2] ?: 0.0f,
            totalLessons = 10,
            completedLessons = ((courseProgress[2] ?: 0.0f) * 10).toInt()
        ),
        TutorialLesson(
            id = 3,
            title = "呼号训练",
            description = "练习业余无线电呼号抄收",
            progress = courseProgress[3] ?: 0.0f,
            totalLessons = 10,
            completedLessons = ((courseProgress[3] ?: 0.0f) * 10).toInt()
        ),
        TutorialLesson(
            id = 4,
            title = "文本训练",
            description = "练习完整CW通联文本",
            progress = courseProgress[4] ?: 0.0f,
            totalLessons = 10,
            completedLessons = ((courseProgress[4] ?: 0.0f) * 10).toInt()
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(lessons) { lesson ->
            TutorialLessonItemMaterial(
                lesson = lesson,
                onClick = { onLessonClick(lesson.id) }
            )
        }
    }
}

/** 单个课程卡片（Material 版）：与 Miuix 版同一份字段顺序与文案。 */
@Composable
private fun TutorialLessonItemMaterial(
    lesson: TutorialLesson,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            // 卡片底色沿用 Miuix 版的 LocalCardAlpha 透明度语义，便于两套风格观感统一
            containerColor = MaterialTheme.colorScheme.surface
                .copy(alpha = LocalCardAlpha.current),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = lesson.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = lesson.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    // 文案与 Miuix 版一字不差：进度: 已完成/总数 (百分比)
                    text = "${stringResource(R.string.progress)}: " +
                        "${lesson.completedLessons}/${lesson.totalLessons} " +
                        "(${(lesson.progress * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    // courseProgress 已由 ViewModel 约束在 0..1，直接作为进度使用
                    progress = { lesson.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
