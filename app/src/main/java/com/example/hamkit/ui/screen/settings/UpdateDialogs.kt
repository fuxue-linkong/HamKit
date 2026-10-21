package com.example.hamkit.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.hamkit.BuildConfig
import com.example.hamkit.R
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.launch

/**
 * 更新检查与下载安装对话框（双主题）。
 *
 * 新版本发现时使用 Bottom Sheet 展示版本信息、更新日志和下载/取消按钮。
 * 其他状态（检查中、下载中、错误、已是最新）仍使用对话框。
 *
 * 关闭时调用 [SettingsScreenActions.onClearUpdateResult] 重置状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialogs(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
) {
    val info = uiState.latestVersionInfo

    // 检查中：loading 对话框（双主题共用逻辑，内部分支渲染）
    if (uiState.updateChecking) {
        UpdateCheckingDialog()
        return
    }

    // 下载中：进度对话框
    if (uiState.downloadProgress in 0..99) {
        UpdateDownloadingDialog(uiState.downloadProgress)
        return
    }

    // 下载/校验失败：错误对话框
    if (uiState.updateError) {
        UpdateErrorDialog(actions, uiState.updateErrorMessage)
        return
    }

    // 发现新版本：Bottom Sheet
    if (uiState.updateAvailable) {
        UpdateAvailableBottomSheet(info, actions)
        return
    }

    // 检查完成但无更新：已是最新
    if (info.versionName.isNotEmpty()) {
        UpdateLatestDialog(actions)
    }
}

// ── 检查中 ──

@Composable
private fun UpdateCheckingDialog() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> WindowDialog(
            show = true,
            title = stringResource(R.string.settings_check_update),
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    InfiniteProgressIndicator(color = MiuixTheme.colorScheme.onBackground)
                    MiuixText(
                        modifier = Modifier.padding(start = 12.dp),
                        text = stringResource(R.string.update_checking),
                    )
                }
            },
        )
        UiMode.Material -> AlertDialog(
            onDismissRequest = { /* 检查中不可关闭 */ },
            confirmButton = {},
            title = { Text(stringResource(R.string.settings_check_update)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    InfiniteProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }
}

// ── 下载中 ──

@Composable
private fun UpdateDownloadingDialog(progress: Int) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> WindowDialog(
            show = true,
            title = stringResource(R.string.update_download),
            content = {
                MiuixText(text = stringResource(R.string.update_downloading, progress))
            },
        )
        UiMode.Material -> AlertDialog(
            onDismissRequest = { /* 下载中不可关闭 */ },
            confirmButton = {},
            title = { Text(stringResource(R.string.update_download)) },
            text = {
                Text(
                    text = stringResource(R.string.update_downloading, progress),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}

// ── 下载失败 ──

@Composable
private fun UpdateErrorDialog(actions: SettingsScreenActions, message: String) {
    val fallback = stringResource(R.string.update_download_failed)
    val text = message.ifBlank { fallback }
    when (LocalUiMode.current) {
        UiMode.Miuix -> WindowDialog(
            show = true,
            title = stringResource(R.string.settings_check_update),
            onDismissRequest = actions.onClearUpdateResult,
            content = {
                Column {
                    MiuixText(text = text)
                    BottomSheetDialogButtons(
                        confirmText = stringResource(R.string.update_retry),
                        onConfirm = { /* retry handled by caller */ },
                        dismissText = stringResource(R.string.cancel),
                        onDismiss = actions.onClearUpdateResult,
                    )
                }
            },
        )
        UiMode.Material -> AlertDialog(
            onDismissRequest = actions.onClearUpdateResult,
            confirmButton = {
                TextButton(onClick = actions.onClearUpdateResult) {
                    Text(stringResource(R.string.confirm))
                }
            },
            title = { Text(stringResource(R.string.settings_check_update)) },
            text = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}

// ── 已是最新 ──

@Composable
private fun UpdateLatestDialog(actions: SettingsScreenActions) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> WindowDialog(
            show = true,
            title = stringResource(R.string.settings_check_update),
            onDismissRequest = actions.onClearUpdateResult,
            content = {
                Column {
                    MiuixText(text = stringResource(R.string.update_latest))
                    BottomSheetDialogButtons(
                        confirmText = stringResource(R.string.confirm),
                        onConfirm = actions.onClearUpdateResult,
                    )
                }
            },
        )
        UiMode.Material -> AlertDialog(
            onDismissRequest = actions.onClearUpdateResult,
            confirmButton = {
                TextButton(onClick = actions.onClearUpdateResult) {
                    Text(stringResource(R.string.confirm))
                }
            },
            title = { Text(stringResource(R.string.settings_check_update)) },
            text = {
                Text(
                    text = stringResource(R.string.update_latest),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}

// ── 发现新版本：Bottom Sheet（核心改动）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateAvailableBottomSheet(
    info: com.example.hamkit.ui.util.LatestVersionInfo,
    actions: SettingsScreenActions,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Expanded)
    val scope = rememberCoroutineScope()

    val dismissSheet: () -> Unit = {
        scope.launch {
            sheetState.hide()
            actions.onClearUpdateResult()
        }
    }

    ModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        containerColor = when (LocalUiMode.current) {
            UiMode.Miuix -> MiuixTheme.colorScheme.surfaceContainer
            UiMode.Material -> MaterialTheme.colorScheme.surfaceContainer
        },
        content = {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                // 标题
                UpdateSheetTitle()

                Spacer(Modifier.height(16.dp))

                // 版本对比
                UpdateVersionComparison(info)

                // 更新日志
                if (info.changelog.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    UpdateChangelogSection(info.changelog)
                }

                Spacer(Modifier.height(24.dp))

                // 按钮区
                UpdateSheetButtons(
                    onDownload = {
                        scope.launch { sheetState.hide() }
                        actions.onDownloadAndInstall()
                    },
                    onCancel = dismissSheet,
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    )
}

@Composable
private fun UpdateSheetTitle() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> MiuixText(
            text = stringResource(R.string.update_available_title),
        )
        UiMode.Material -> Text(
            text = stringResource(R.string.update_available_title),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun UpdateVersionComparison(
    info: com.example.hamkit.ui.util.LatestVersionInfo
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> Column {
            MiuixText(text = "${stringResource(R.string.update_current_version)}: ${BuildConfig.VERSION_NAME}")
            Spacer(Modifier.height(4.dp))
            MiuixText(text = "${stringResource(R.string.update_latest_version)}: ${info.versionName}")
        }
        UiMode.Material -> Column {
            Text(
                text = "${stringResource(R.string.update_current_version)}: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${stringResource(R.string.update_latest_version)}: ${info.versionName}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun UpdateChangelogSection(changelog: String) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> Column {
            MiuixText(
                text = stringResource(R.string.update_changelog_title),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            Spacer(Modifier.height(4.dp))
            MiuixText(
                text = changelog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
        UiMode.Material -> Column {
            Text(
                text = stringResource(R.string.update_changelog_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = changelog,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun UpdateSheetButtons(
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> BottomSheetDialogButtons(
            confirmText = stringResource(R.string.update_download),
            onConfirm = onDownload,
            dismissText = stringResource(R.string.cancel),
            onDismiss = onCancel,
        )
        UiMode.Material -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.update_download))
            }
        }
    }
}

/**
 * Miuix 风格底部按钮区。
 */
@Composable
private fun BottomSheetDialogButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val dismissState = LocalDismissState.current
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
    ) {
        if (dismissText != null) {
            MiuixTextButton(
                text = dismissText,
                onClick = {
                    onDismiss?.invoke()
                    dismissState?.invoke()
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
        }
        MiuixTextButton(
            text = confirmText,
            onClick = {
                onConfirm()
                dismissState?.invoke()
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}
