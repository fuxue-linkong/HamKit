package com.example.hamkit.ui.screen.sstv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.viewmodel.SstvViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** SSTV 设置：模式锁定、自动保存、实时预览与图库管理。 */
@Composable
fun SstvSettingsScreen(
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val galleryCount = remember(uiState.status) { viewModel.galleryEntries().size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "SSTV 设置",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .overScrollVertical()
                .scrollEndHaptic()
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("接收行为", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "实时预览",
                        summary = "接收过程中逐行显示图像（关闭可省电）",
                        checked = uiState.livePreview,
                        onCheckedChange = { viewModel.setLivePreview(it) },
                    )
                    SwitchRow(
                        title = "自动保存",
                        summary = "整帧接收完成后自动存入图库",
                        checked = uiState.autoSave,
                        onCheckedChange = { viewModel.setAutoSave(it) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("模式锁定", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "默认由 VIS 头自动识别模式。若弱信号下识别失败，可在此手动锁定。",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.setManualMode(null) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (uiState.manualModeName == null) {
                            ButtonDefaults.buttonColorsPrimary()
                        } else {
                            ButtonDefaults.buttonColors(color = Color.Transparent, contentColor = MiuixTheme.colorScheme.onSurfaceSecondary)
                        },
                    ) {
                        Text("自动识别（推荐）")
                    }
                    Spacer(Modifier.height(6.dp))
                    SstvMode.DECODABLE_MODES.forEach { mode ->
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { viewModel.setManualMode(mode) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (uiState.manualModeName == mode.name) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors(color = Color.Transparent, contentColor = MiuixTheme.colorScheme.onSurfaceSecondary)
                            },
                        ) {
                            Text(
                                "${mode.displayName}  ·  ${mode.linePixels}×${mode.imageLines}" +
                                    "  ·  ${"%.0f".format(mode.frameSeconds)} s",
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("图库", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已保存 $galleryCount 张图像（应用私有目录，导出到相册属二期功能）",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.clearGallery() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("清空图库")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("使用提示", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• 采样率固定 48 kHz：SSTV 最短像素仅 137 µs，低采样率无法分辨\n" +
                            "• ISS 的 ARISS 活动使用 PD-120，频率 145.800 MHz，单帧约 126 秒\n" +
                            "• 手机麦克风对准电台扬声器，音量以不失真为准\n" +
                            "• 若 VIS 识别失败，请手动锁定模式后重试\n" +
                            "• Scottie 族（行中同步）暂不支持解码，二期加入",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1, color = colorScheme.onBackground)
            Text(summary, style = MiuixTheme.textStyles.footnote1, color = colorScheme.onBackground)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
