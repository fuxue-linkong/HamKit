package com.example.hamkit.ui.cw

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.theme.LocalEnableBlur
import com.example.hamkit.ui.util.BlurredBar
import com.example.hamkit.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * CW 练习路由入口：用状态机在主菜单和三个子页面之间切换，
 * 子页面（自由练习设置 / 教程列表 / 摩斯电码编解码）保留在 cw/ 子目录中，
 * 不污染 Routes.kt，符合"原 UI 处理方式"。
 *
 * 本函数是**风格分发入口**：两套实现共用同一个 [CWPracticeViewModel] 实例
 * （application 级 ViewModelStoreOwner），因此切换风格不会丢练习进度。
 */
@Composable
fun CWPracticeRouteScreen() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> CWPracticeRouteScreenMiuix()
        UiMode.Material -> CWPracticeRouteScreenMaterial()
    }
}

/** Miuix（MIUI）风格实现。 */
@Composable
fun CWPracticeRouteScreenMiuix() {
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

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

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

    // CW 子组件均使用 Miuix 组件 + LocalCardAlpha，统一在 MiuixTheme 下渲染
    MiuixTheme {
        Scaffold(
            topBar = {
                BlurredBar(backdrop) {
                    TopAppBar(
                        color = barColor,
                        title = title,
                        navigationIcon = {
                            Box(modifier = Modifier.padding(start = 12.dp)) {
                                IconButton(onClick = onBack) {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior
                    )
                }
            },
            popupHost = { },
            contentWindowInsets = WindowInsets.systemBars
                .add(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal)
        ) { innerPadding ->
            when (page) {
                CWPage.Main -> CWPracticeScreenMiuix(
                    onBackClick = onBack,
                    onFreePracticeClick = { page = CWPage.FreeSettings },
                    onTutorialClick = { page = CWPage.Tutorial },
                    onMorseCodeClick = { page = CWPage.MorseCode },
                    contentPadding = innerPadding
                )

                CWPage.FreeSettings -> FreePracticeSettingsScreenMiuix(
                    settings = settings,
                    onSettingsChange = cwViewModel::updateSettings,
                    onStartPractice = {
                        cwViewModel.generatePracticeText(settings.characterSet, settings.practiceLength)
                        cwViewModel.startPractice()
                        page = CWPage.FreePractice
                    },
                    contentPadding = innerPadding
                )

                CWPage.FreePractice -> {
                    val currentText by cwViewModel.currentText.collectAsStateWithLifecycle()
                    val morseCode by cwViewModel.morseCode.collectAsStateWithLifecycle()
                    val userInput by cwViewModel.userInput.collectAsStateWithLifecycle()
                    val isPlaying by cwViewModel.isPlaying.collectAsStateWithLifecycle()
                    val isPaused by cwViewModel.isPaused.collectAsStateWithLifecycle()
                    val accuracy by cwViewModel.accuracy.collectAsStateWithLifecycle()
                    PracticeScreenMiuix(
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
                        contentPadding = innerPadding
                    )
                }

                CWPage.Tutorial -> {
                    val courseProgress by cwViewModel.courseProgress.collectAsStateWithLifecycle()
                    TutorialListScreenMiuix(
                        onLessonClick = { courseId ->
                            // 生成对应课程的教程内容并进入教程练习页
                            cwViewModel.generateTutorialText(courseId)
                            page = CWPage.TutorialPractice
                        },
                        courseProgress = courseProgress,
                        contentPadding = innerPadding
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
                    PracticeScreenMiuix(
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
                        contentPadding = innerPadding
                    )
                }

                CWPage.MorseCode -> MorseCodeScreenMiuix(
                    bgPage = colorScheme.surface,
                    bgCard = colorScheme.surface,
                    primaryColor = colorScheme.primary,
                    textPrimary = colorScheme.onSurface,
                    textSecondary = colorScheme.onSurfaceSecondary,
                    contentPadding = innerPadding
                )
            }
        }
    }
}

/** CW 练习页面的状态机取值；两套风格实现共用（Material 版在另一个文件里）。 */
internal enum class CWPage { Main, FreeSettings, FreePractice, Tutorial, TutorialPractice, MorseCode }
