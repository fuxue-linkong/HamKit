package com.example.hamkit.ui.screen.sstv

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * SSTV 设置：接收行为、后处理、模式锁定、图库与发射入口。
 *
 * 二期的开关都放在这里而不是主界面 —— 主界面只保留「接收中会想改」的东西
 * （连续接收、后处理总开关），其余一次性配置项收到本页，避免主界面过长。
 */
@Composable
fun SstvSettingsScreen(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    // 图库数量由 ViewModel 在协程中统计（文件 IO 不落在主线程，也不随 status 反复触发）
    val galleryCount = uiState.galleryCount
    var confirmClear by remember { mutableStateOf(false) }

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

            // ── 接收行为 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("接收行为", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "实时预览",
                        summary = "接收过程中逐行刷新画面；关闭可省电，收满仍会显示图像",
                        checked = uiState.livePreview,
                        onCheckedChange = { viewModel.setLivePreview(it) },
                    )
                    SwitchRow(
                        title = "自动保存",
                        summary = "整帧接收完成后自动存入图库",
                        checked = uiState.autoSave,
                        onCheckedChange = { viewModel.setAutoSave(it) },
                    )
                    SwitchRow(
                        title = "连续接收",
                        summary = "收满一帧后保留模式与失谐量、自动接续下一帧（ISS 连发多张时用）",
                        checked = uiState.continuous,
                        onCheckedChange = { viewModel.setContinuous(it) },
                    )
                    if (uiState.continuous) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "最大帧数",
                            style = MiuixTheme.textStyles.body2,
                            color = colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            listOf(2, 3, 5, 10).forEach { frames ->
                                Button(
                                    onClick = { viewModel.setMaxFrames(frames) },
                                    modifier = Modifier.padding(end = 6.dp),
                                    colors = if (uiState.maxFrames == frames) {
                                        ButtonDefaults.buttonColorsPrimary()
                                    } else {
                                        ButtonDefaults.buttonColors(
                                            color = Color.Transparent,
                                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                        )
                                    },
                                ) {
                                    Text("$frames")
                                }
                            }
                        }
                    }
                    SwitchRow(
                        title = "弱信号自适应积分窗",
                        summary = "按实测信噪比调整积分长度：弱信号多平均（细节略糊），强信号不变",
                        checked = uiState.adaptiveSpan,
                        onCheckedChange = { viewModel.setAdaptiveSpan(it) },
                    )
                    SwitchRow(
                        title = "VIS 失败时盲扫",
                        summary = "导入录音时若 VIS 头缺失，按行同步间隔反推模式后再解码",
                        checked = uiState.blindScanOnFailure,
                        onCheckedChange = { viewModel.setBlindScanOnFailure(it) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 图像后处理 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("图像后处理", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "处理不可逆，因此默认全关。开启后主界面仍可用「保存原图」保留未经处理的结果。",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "直方图拉伸",
                        summary = "把实际亮度范围拉满，修复自动增益压缩导致的灰蒙蒙",
                        checked = uiState.postProcess.histogramStretch,
                        onCheckedChange = { viewModel.setHistogramStretch(it) },
                    )
                    SwitchRow(
                        title = "中值降噪",
                        summary = "3×3 中值滤波去脉冲噪点（比均值滤波更保边缘）",
                        checked = uiState.postProcess.medianFilter,
                        onCheckedChange = { viewModel.setMedianFilter(it) },
                    )
                    SwitchRow(
                        title = "坏行修复",
                        summary = "同步丢失导致的纯色行用上下相邻行插值补上",
                        checked = uiState.postProcess.repairBadLines,
                        onCheckedChange = { viewModel.setRepairBadLines(it) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 模式锁定 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("模式锁定", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "默认由 VIS 头自动识别模式。若弱信号下识别失败，可在此手动锁定。" +
                            "共 ${SstvMode.DECODABLE_MODES.size} 种模式。",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    ModeButton(
                        label = "自动识别（推荐）",
                        selected = uiState.manualModeName == null,
                        onClick = { viewModel.setManualMode(null) },
                    )
                    SstvMode.DECODABLE_MODES.forEach { mode ->
                        Spacer(Modifier.height(6.dp))
                        ModeButton(
                            label = "${mode.displayName}  ·  ${mode.linePixels}×${mode.imageLines}" +
                                "  ·  ${"%.0f".format(mode.frameSeconds)} s" +
                                if (mode.isMonochrome) "  ·  黑白" else "",
                            selected = uiState.manualModeName == mode.name,
                            onClick = { viewModel.setManualMode(mode) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 图库与发射 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("图库与发射", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已保存 $galleryCount 张图像（应用私有目录，可分享或导出到相册）",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onNavigate(Route.SstvGallery()) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("打开图库")
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { onNavigate(Route.SstvTx) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开发射页")
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { onNavigate(Route.HfFax) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开 HF Fax（天气传真）")
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { confirmClear = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("清空图库")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 使用提示 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("使用提示", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• 采样率固定 48 kHz：SSTV 最短像素仅 137 µs，低采样率无法分辨\n" +
                            "• ISS 的 ARISS 活动使用 PD-120，频率 145.800 MHz，单帧约 126 秒\n" +
                            "• 手机麦克风对准电台扬声器，音量以不失真为准\n" +
                            "• 若 VIS 识别失败：可手动锁定模式，或用「导入录音」走盲扫判定\n" +
                            "• 支持 PD / Robot / Martin / Scottie 四族共 ${SstvMode.DECODABLE_MODES.size} 种模式" +
                            "（含 PD-50/90/160/290 与 Robot B/W 黑白族）\n" +
                            "• 最长模式 PD-290 单帧约 289 秒，连续接收的上限见「最大帧数」\n" +
                            "• 发射须持相应操作资格；未持照请只用「导出 WAV」自测\n" +
                            "• 图库在应用私有目录，卸载即清除，重要图像请导出到相册",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        WindowDialog(
            show = true,
            title = "清空图库",
            onDismissRequest = { confirmClear = false },
            content = {
                Column {
                    Text("将删除全部 $galleryCount 张图像，此操作不可撤销。")
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(
                            text = "取消",
                            onClick = { confirmClear = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "清空",
                            onClick = {
                                viewModel.clearGallery()
                                confirmClear = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColors(
                color = Color.Transparent,
                contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        },
    ) {
        Text(label)
    }
}
