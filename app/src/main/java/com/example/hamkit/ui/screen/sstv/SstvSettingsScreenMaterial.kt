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
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedDropdownItem
import com.example.hamkit.ui.component.material.SegmentedListItem
import com.example.hamkit.ui.component.material.SegmentedRadioItem
import com.example.hamkit.ui.component.material.SegmentedSwitchItem
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel

/** 连续接收可选的最大帧数（与 [SstvSettingsScreenMiuix] 的下拉项一一对应）。 */
private val MAX_FRAME_OPTIONS = listOf(2, 3, 5, 10)

/**
 * SSTV 设置的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版逐项对应：接收行为（含「连续接收上限」）、图像后处理、模式锁定、
 * 图库与发射、使用提示。**与主界面的分工也一致**：主界面放「接收中会想改」的开关，
 * 本页只提供它们的细项，不重复「连续接收」「后处理」总开关。
 *
 * 模式锁定有 19 个选项，这里用分段单选列表而不是一列按钮：Material 的列表项自带
 * 选中态与触感反馈，长列表下比按钮堆叠更易读。
 */
@Composable
fun SstvSettingsScreenMaterial(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 图库数量由 ViewModel 在协程中统计（文件 IO 不落在主线程）
    val galleryCount = uiState.galleryCount
    // 清空是破坏性操作：点「清空」前必须先过一道确认框（与 Miuix 版一致）
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
                SstvSectionHeaderMaterial(
                    title = "接收行为",
                    summary = "自动保存与实时预览在接收期间生效；「连续接收」在主界面上切换。",
                ) {
                    SegmentedColumn(
                        content = buildList<@Composable () -> Unit> {
                            add {
                                SegmentedSwitchItem(
                                    title = "实时预览",
                                    summary = "接收过程中逐行刷新画面；关闭可省电，收满仍会显示图像",
                                    checked = uiState.livePreview,
                                    onCheckedChange = { viewModel.setLivePreview(it) },
                                )
                            }
                            add {
                                SegmentedSwitchItem(
                                    title = "自动保存",
                                    summary = "整帧接收完成后自动存入图库",
                                    checked = uiState.autoSave,
                                    onCheckedChange = { viewModel.setAutoSave(it) },
                                )
                            }
                            // 最大帧数只在连续接收开启时才有意义 —— 关闭时整行隐藏
                            if (uiState.continuous) {
                                add {
                                    SegmentedDropdownItem(
                                        title = "连续接收上限",
                                        summary = "收满这么多帧后停止",
                                        items = MAX_FRAME_OPTIONS.map { "$it 帧" },
                                        selectedIndex = MAX_FRAME_OPTIONS.indexOf(uiState.maxFrames)
                                            .coerceAtLeast(0),
                                        onItemSelected = { index ->
                                            MAX_FRAME_OPTIONS.getOrNull(index)?.let { viewModel.setMaxFrames(it) }
                                        },
                                    )
                                }
                            }
                            add {
                                SegmentedSwitchItem(
                                    title = "弱信号自适应积分窗",
                                    summary = "按实测信噪比调整积分长度：弱信号多平均（细节略糊），强信号不变",
                                    checked = uiState.adaptiveSpan,
                                    onCheckedChange = { viewModel.setAdaptiveSpan(it) },
                                )
                            }
                            add {
                                SegmentedSwitchItem(
                                    title = "VIS 失败时盲扫",
                                    summary = "导入录音时若 VIS 头缺失，按行同步间隔反推模式后再解码",
                                    checked = uiState.blindScanOnFailure,
                                    onCheckedChange = { viewModel.setBlindScanOnFailure(it) },
                                )
                            }
                        },
                    )
                }
            }

            // ── 图像后处理 ──
            item {
                SstvSectionHeaderMaterial(
                    title = "图像后处理",
                    summary = "处理不可逆，因此默认全关。开启后主界面仍可用「保存原图」保留" +
                        "未经处理的结果；总开关在主界面。",
                ) {
                    SegmentedColumn(
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
                }
            }

            // ── 模式锁定 ──
            item {
                SstvSectionHeaderMaterial(
                    title = "模式锁定",
                    summary = "默认由 VIS 头自动识别。弱信号下识别失败时可在此手动锁定，" +
                        "共 ${SstvMode.DECODABLE_MODES.size} 种模式；主界面顶部也有同一个开关。",
                ) {
                    SegmentedColumn(
                        content = buildList<@Composable () -> Unit> {
                            add {
                                SegmentedRadioItem(
                                    title = "自动识别",
                                    summary = "推荐，由 VIS 头自动判定",
                                    selected = uiState.manualModeName == null,
                                    onClick = { viewModel.setManualMode(null) },
                                )
                            }
                            SstvMode.DECODABLE_MODES.forEach { mode ->
                                add {
                                    SegmentedRadioItem(
                                        title = mode.displayName,
                                        summary = modeSummary(mode),
                                        selected = uiState.manualModeName == mode.name,
                                        onClick = { viewModel.setManualMode(mode) },
                                    )
                                }
                            }
                        },
                    )
                }
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
                                supportingContent = { Text("选图 → 编码成 SSTV 音频 → 播放或导出 WAV") },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { onNavigate(Route.HfFax) },
                                headlineContent = { Text("HF Fax（天气传真）") },
                                supportingContent = { Text("120 行/分的灰度气象图接收，与 SSTV 是独立通路") },
                            )
                        },
                        {
                            SegmentedListItem(
                                onClick = { confirmClear = true },
                                headlineContent = {
                                    Text("清空图库", color = MaterialTheme.colorScheme.error)
                                },
                                supportingContent = { Text("删除全部已保存图像，不可撤销") },
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
                                "• 最长模式 PD-290 单帧约 289 秒，连续接收的上限见「连续接收上限」\n" +
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

/**
 * 分区的「标题 + 说明」头部（Material 版，对应 Miuix 版设置页的 SstvSectionHeader）。
 *
 * 标题用 `titleSmall + primary`，与 [SegmentedColumn] 自带的标题样式一致，
 * 让「有卡片壳」与「无卡片壳」的分区看起来是同一套。
 */
@Composable
private fun SstvSectionHeaderMaterial(
    title: String,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        content()
    }
}
