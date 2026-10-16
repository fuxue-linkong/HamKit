package com.example.hamkit.ui.screen.reminder

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.ReminderListScreen
import com.example.hamkit.ui.LocalMainViewModel
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
 * 提醒列表路由入口：按当前界面风格分发到 Miuix / Material 实现。
 *
 * 两套实现共用同一个 [com.example.hamkit.ui.viewmodel.MainViewModel]（由 `LocalMainViewModel`
 * 提供），因此切换风格不会丢列表状态。
 */
@Composable
fun ReminderListRouteScreen() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> ReminderListRouteScreenMiuix()
        UiMode.Material -> ReminderListRouteScreenMaterial()
    }
}

/**
 * Miuix（MIUI）风格实现：复用 [ReminderListScreen]（基于 Miuix 组件），
 * 在外层套 MiuixTheme 保证子组件色彩一致。
 */
@Composable
fun ReminderListRouteScreenMiuix() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    // MainViewModel 的状态是 Compose State<T>（非 StateFlow），直接用 by 委托即可
    val items by mainViewModel.reminderItems

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    MiuixTheme {
        Scaffold(
            topBar = {
                BlurredBar(backdrop) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.reminder_list_title),
                        navigationIcon = {
                            Box(modifier = Modifier.padding(start = 12.dp)) {
                                IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
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
            ReminderListScreen(
                items = items,
                onToggleEnabled = mainViewModel::setReminderItemEnabled,
                onDelete = mainViewModel::deleteReminderItem,
                contentPadding = innerPadding
            )
        }
    }
}
