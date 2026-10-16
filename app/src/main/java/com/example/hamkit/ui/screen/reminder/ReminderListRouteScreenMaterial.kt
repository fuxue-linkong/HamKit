package com.example.hamkit.ui.screen.reminder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.ReminderListScreenMaterial
import com.example.hamkit.ui.navigation3.LocalNavigator

/**
 * 提醒列表的 Material（Material 3）风格实现。
 *
 * 内容区交给 [ReminderListScreenMaterial]，这里只负责 Scaffold 与顶栏。
 * 与 Miuix 版共用同一个 `MainViewModel`，因此开关、删除等操作在两套风格下完全等价。
 *
 * 与 Miuix 版的一点差异：Miuix 版顶栏接了 `BlurredBar` 背景模糊。Material 3 的
 * `TopAppBar` 自身已有 surface 底色的滚动容器色，再加模糊层会与 Material 的
 * 层级语义冲突，故这里保持标准顶栏。
 */
@Composable
fun ReminderListRouteScreenMaterial() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    // MainViewModel 的状态是 Compose State<T>（非 StateFlow），直接用 by 委托即可
    val items by mainViewModel.reminderItems

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminder_list_title)) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        ReminderListScreenMaterial(
            items = items,
            onToggleEnabled = mainViewModel::setReminderItemEnabled,
            onDelete = mainViewModel::deleteReminderItem,
            contentPadding = innerPadding,
        )
    }
}
