package com.example.hamkit.ui.screen.sstv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedListItem
import com.example.hamkit.ui.component.material.SegmentedRadioItem
import com.example.hamkit.ui.component.material.SegmentedSwitchItem
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel

/**
 * SSTV 设置的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版逐项对应：接收行为 / 图像后处理 / 模式锁定 / 图库与发射 / 使用提示。
 * 行为完全一致，差别只在组件与配色 —— 两套实现共享同一个 [SstvViewModel]，
 * 因此切换界面风格不会丢设置或接收状态。
 *
 * 模式锁定有 19 个选项，这里用分段单选列表而不是 Miuix 版的一列按钮：
 * Material 的列表项自带选中态与触感反馈，长列表下比按钮堆叠更易读。
 */
@Composable
fun SstvSettingsScreenMaterial(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 图库数量由 ViewModel 在协程中统计（文件 IO 不落在主线程）
    val galleryCount = uiState.galleryCount
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSTV 设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 接收行为 ──
            item {
                SegmentedColumn(
                    title = "接收行为",
                    content = listOf<@Composable () -> Unit>(
                        {
                            SegmentedSwitchItem(
                                title = "实时预览",
                                summary = "接收过程中逐行刷新画面；关闭可省电，收满仍会显示图像",
                                checked = uiState.livePreview,
                                onCheckedChange = { viewModel.setLivePreview(it) },
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                title = "自动保存",
                                summary = "整帧接收完成后自动存入图库",
                                checked = uiState.autoSave,
                                onCheckedChange = { viewModel.setAutoSave(it) },
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                title = "连续接收",
                                summary = "收满一帧后保留模式与失谐量、自动接续下一帧（ISS 连发多张时用）",
                                checked = uiState.continuous,
                                onCheckedChange = { viewModel.setContinuous(it) },
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                title = "弱信号自适应积分窗",
                                summary = "按实测信噪比调整积分长度：弱信号多平均（细节略糊），强信号不变",
                                checked = uiState.adaptiveSpan,
                                onCheckedChange = { viewModel.setAdaptiveSpan(it) },
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                title = "VIS 失败时盲扫",
                                summary = "导入录音时若 VIS 头缺失，按行同步间隔反推模式后再解码",
                                checked = uiState.blindScanOnFailure,
                                onCheckedChange = { viewModel.setBlindScanOnFailure(it) },
                            )
                        },
                    ),
                )
            }

            // 最大帧数只在连续接收开启时有意义 —— 与 Miuix 版一致，关闭时整块隐藏
            if (uiState.continuous) {
                item {
                    SegmentedColumn(
                        title = "最大帧数",
                        content = listOf(2, 3, 5, 10).map { frames ->
                            {
                                SegmentedRadioItem(
                                    title = "$frames 张",
                                    selected = uiState.maxFrames == frames,
                                    onClick = { viewModel.setMaxFrames(frames) },
                                )
                            }
                        },
                    )
                }
            }

            // ── 图像后处理 ──
            item {
                Column {
                    SegmentedColumn(
                        title = "图像后处理",
                        content = listOf<@Composable () -> Unit>(
                            {
                                SegmentedSwitchItem(
                                    title = "直方图拉伸",
                                    summary = "把实际亮度范围拉满，修复自动增益压缩导致的灰蒙蒙",
                                    checked = uiState.postProcess.histogramStretch,
                                    onCheckedChange = { viewModel.setHistogramStretch(it) },
                                )
                            },
                            {
                                SegmentedSwitchItem(
                                    title = "中值降噪",
                                    summary = "3×3 中值滤波去脉冲噪点（比均值滤波更保边缘）",
                                    checked = uiState.postProcess.medianFilter,
                                    onCheckedChange = { viewModel.setMedianFilter(it) },
                                )
                            },
                            {
                                SegmentedSwitchItem(
                                    title = "坏行修复",
                                    summary = "同步丢失导致的纯色行用上下相邻行插值补上",
                                    checked = uiState.postProcess.repairBadLines,
                                    onCheckedChange = { viewModel.setRepairBadLines(it) },
                                )
                            },
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "处理不可逆，因此默认全关。开启后主界面仍可用「保存原图」保留未经处理的结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // ── 模式锁定 ──
            item {
                SegmentedColumn(
                    title = "模式锁定",
                    content = buildList<@Composable () -> Unit> {
                        add {
                            SegmentedRadioItem(
                                title = "自动识别（推荐）",
                                summary = "由 VIS 头自动判定模式",
                                selected = uiState.manualModeName == null,
                                onClick = { viewModel.setManualMode(null) },
                            )
                        }
                        SstvMode.DECODABLE_MODES.forEach { mode ->
                            add {
                                SegmentedRadioItem(
                                    title = mode.displayName,
                                    summary = buildString {
                                        append("${mode.linePixels}×${mode.imageLines}")
                                        append("  ·  ${"%.0f".format(mode.frameSeconds)} s")
                                        if (mode.isMonochrome) append("  ·  黑白")
                                    },
                                    selected = uiState.manualModeName == mode.name,
                                    onClick = { viewModel.setManualMode(mode) },
                                )
                            }
                        }
                    },
                )
            }

            // ── 图库与发射 ──
            item {
                SegmentedColumn(
                    title = "图库与发射",
                    content = listOf<@Composable () -> Unit>(
                        {
                            SegmentedListItem(
                                onClick = { onNavigate(Route.SstvGallery()) },
                                headlineContent = { Text("打开图库") },
                                supportingContent = {
                                    Text("已保存 $galleryCount 张图像（应用私有目录，可分享或导出到相册）")
                                },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { onNavigate(Route.SstvTx) },
                                headlineContent = { Text("打开发射页") },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { onNavigate(Route.HfFax) },
                                headlineContent = { Text("打开 HF Fax（天气传真）") },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { confirmClear = true },
                                headlineContent = {
                                    Text("清空图库", color = MaterialTheme.colorScheme.error)
                                },
                            )
                        },
                    ),
                )
            }

            // ── 使用提示 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "使用提示",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "• 采样率固定 48 kHz：SSTV 最短像素仅 137 µs，低采样率无法分辨\n" +
                                "• ISS 的 ARISS 活动使用 PD-120，频率 145.800 MHz，单帧约 126 秒\n" +
                                "• 手机麦克风对准电台扬声器，音量以不失真为准\n" +
                                "• 若 VIS 识别失败：可手动锁定模式，或用「导入录音」走盲扫判定\n" +
                                "• 支持 PD / Robot / Martin / Scottie 四族共 ${SstvMode.DECODABLE_MODES.size} 种模式" +
                                "（含 PD-50/90/160/290 与 Robot B/W 黑白族）\n" +
                                "• 最长模式 PD-290 单帧约 289 秒，连续接收的上限见「最大帧数」\n" +
                                "• 发射须持相应操作资格；未持照请只用「导出 WAV」自测\n" +
                                "• 图库在应用私有目录，卸载即清除，重要图像请导出到相册",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空图库") },
            text = { Text("将删除全部 $galleryCount 张图像，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearGallery()
                        confirmClear = false
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            },
        )
    }
}
