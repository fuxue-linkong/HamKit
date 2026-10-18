package com.example.hamkit.ui.cw

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator

/**
 * CW 练习路由入口的 Material（Material 3）风格实现。
 *
 * 状态机、返回逻辑与标题映射与 Miuix 版逐行对应，差别只在：
 *  - 顶栏换成 material3 的 `Scaffold` + `TopAppBar`（Miuix 版接了背景模糊，Material 3
 *    的顶栏自带滚动容器色，叠模糊层会与 Material 的层级语义冲突）；
 *  - 子页面调用各自的 Material 实现，摩斯编解码页注入 `MaterialTheme` 的颜色。
 *
 * 两套实现共享同一个 [CWPracticeViewModel]，切换风格不会丢练习进度。
 */
@Composable
fun CWPracticeRouteScreenMaterial() {
    val navigator = LocalNavigator.current
    val cwViewModel = applicationScopedViewModel<CWPracticeViewModel>()
    val settings by cwViewModel.settings.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(CWPage.Main) }

    // 子页面返回逻辑：练习页回设置页/列表页，其余回主菜单；主菜单再退回上一级路由
    BackHandler(enabled = page != CWPage.Main) {
        when (page) {
            CWPage.FreePractice -> {
                cwViewModel.stopPractice()
                page = CWPage.FreeSettings
            }
            CWPage.TutorialPractice -> {
                cwViewModel.stopPractice()
                page = CWPage.Tutorial
            }
            else -> page = CWPage.Main
        }
    }

    val title = when (page) {
        CWPage.Main -> stringResource(R.string.cw_practice)
        CWPage.FreeSettings -> stringResource(R.string.free_practice)
        CWPage.FreePractice -> stringResource(R.string.free_practice)
        CWPage.Tutorial -> stringResource(R.string.tutorial_practice)
        CWPage.TutorialPractice -> stringResource(R.string.tutorial_practice)
        CWPage.MorseCode -> stringResource(R.string.morsecode_codec)
    }

    val onBack: () -> Unit = when (page) {
        CWPage.Main -> dropUnlessResumed { navigator.pop() }
        CWPage.FreePractice -> { { cwViewModel.stopPractice(); page = CWPage.FreeSettings } }
        CWPage.TutorialPractice -> { { cwViewModel.stopPractice(); page = CWPage.Tutorial } }
        else -> { { page = CWPage.Main } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        when (page) {
            CWPage.Main -> CWPracticeScreenMaterial(
                onBackClick = onBack,
                onFreePracticeClick = { page = CWPage.FreeSettings },
                onTutorialClick = { page = CWPage.Tutorial },
                onMorseCodeClick = { page = CWPage.MorseCode },
                contentPadding = innerPadding,
            )

            CWPage.FreeSettings -> FreePracticeSettingsScreenMaterial(
                settings = settings,
                onSettingsChange = cwViewModel::updateSettings,
                onStartPractice = {
                    cwViewModel.generatePracticeText(settings.characterSet, settings.practiceLength)
                    cwViewModel.startPractice()
                    page = CWPage.FreePractice
                },
                contentPadding = innerPadding,
            )

            CWPage.FreePractice -> {
                val currentText by cwViewModel.currentText.collectAsStateWithLifecycle()
                val morseCode by cwViewModel.morseCode.collectAsStateWithLifecycle()
                val userInput by cwViewModel.userInput.collectAsStateWithLifecycle()
                val isPlaying by cwViewModel.isPlaying.collectAsStateWithLifecycle()
                val isPaused by cwViewModel.isPaused.collectAsStateWithLifecycle()
                val accuracy by cwViewModel.accuracy.collectAsStateWithLifecycle()
                PracticeScreenMaterial(
                    currentText = currentText,
                    morseCode = morseCode,
                    userInput = userInput,
                    isPlaying = isPlaying,
                    isPaused = isPaused,
                    accuracy = accuracy,
                    isTutorialMode = false,
                    onUserInputChange = cwViewModel::updateUserInput,
                    onGenerateText = {
                        cwViewModel.generatePracticeText(settings.characterSet, settings.practiceLength)
                    },
                    onStartPractice = cwViewModel::startPractice,
                    onPausePractice = cwViewModel::pausePractice,
                    onResumePractice = cwViewModel::resumePractice,
                    onStopPractice = cwViewModel::stopPractice,
                    onCheckResults = cwViewModel::checkResults,
                    contentPadding = innerPadding,
                )
            }

            CWPage.Tutorial -> {
                val courseProgress by cwViewModel.courseProgress.collectAsStateWithLifecycle()
                TutorialListScreenMaterial(
                    onLessonClick = { courseId ->
                        // 生成对应课程的教程内容并进入教程练习页
                        cwViewModel.generateTutorialText(courseId)
                        page = CWPage.TutorialPractice
                    },
                    courseProgress = courseProgress,
                    contentPadding = innerPadding,
                )
            }

            CWPage.TutorialPractice -> {
                val currentText by cwViewModel.currentText.collectAsStateWithLifecycle()
                val morseCode by cwViewModel.morseCode.collectAsStateWithLifecycle()
                val userInput by cwViewModel.userInput.collectAsStateWithLifecycle()
                val isPlaying by cwViewModel.isPlaying.collectAsStateWithLifecycle()
                val isPaused by cwViewModel.isPaused.collectAsStateWithLifecycle()
                val accuracy by cwViewModel.accuracy.collectAsStateWithLifecycle()
                val courseTitle by cwViewModel.currentCourseTitle.collectAsStateWithLifecycle()
                val lessonInfo by cwViewModel.currentLessonInfo.collectAsStateWithLifecycle()
                PracticeScreenMaterial(
                    currentText = currentText,
                    morseCode = morseCode,
                    userInput = userInput,
                    isPlaying = isPlaying,
                    isPaused = isPaused,
                    accuracy = accuracy,
                    courseTitle = courseTitle,
                    lessonInfo = lessonInfo,
                    isTutorialMode = true,
                    onUserInputChange = cwViewModel::updateUserInput,
                    onGenerateText = { /* 教程内容由课程决定，不支持手动生成 */ },
                    onStartPractice = cwViewModel::startPractice,
                    onPausePractice = cwViewModel::pausePractice,
                    onResumePractice = cwViewModel::resumePractice,
                    onStopPractice = cwViewModel::stopPractice,
                    onCheckResults = cwViewModel::checkResults,
                    onNextLesson = cwViewModel::advanceCourseProgress,
                    contentPadding = innerPadding,
                )
            }

            CWPage.MorseCode -> MorseCodeScreenMaterial(
                // Material 版注入 Material 主题色；摩斯页本身不感知风格
                bgPage = MaterialTheme.colorScheme.surface,
                bgCard = MaterialTheme.colorScheme.surfaceContainerHigh,
                primaryColor = MaterialTheme.colorScheme.primary,
                textPrimary = MaterialTheme.colorScheme.onSurface,
                textSecondary = MaterialTheme.colorScheme.onSurfaceVariant,
                contentPadding = innerPadding,
            )
        }
    }
}
