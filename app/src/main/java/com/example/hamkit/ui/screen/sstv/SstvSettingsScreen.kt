package com.example.hamkit.ui.screen.sstv

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/** 连续接收可选的最大帧数（与设置页的下拉项一一对应）。 */
private val MAX_FRAME_OPTIONS = listOf(2, 3, 5, 10)

/**
 * SSTV 设置：接收行为、后处理、模式锁定、图库与发射入口。
 *
 * **与主界面的分工**：主界面只保留「接收中会想改」的东西（音源、模式、连续接收、
 * 后处理总开关、瀑布图），其余一次性配置项都在本页。因此本页**不再重复**主界面的
 * 「连续接收」开关与「后处理总开关」，只提供它们下面的细项（最大帧数、三种处理算法）。
 *
 * **版式（2026-10 整理）**：各卡片统一为「标题 + 说明 + 设置行」的结构，文字按钮换成
 * `BasicComponent`/`ArrowPreference` 这类可点行，与全局设置页的观感一致。
 *
 * 本函数只是**风格分发入口**：Miuix / Material 两套实现共用同一个 [SstvViewModel]
 * 实例（ViewModelStoreOwner 是 application 级），因此切换风格不会丢状态。
 */
@Composable
fun SstvSettingsScreen(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SstvSettingsScreenMiuix(onNavigate, onNavigateBack)
        UiMode.Material -> SstvSettingsScreenMaterial(onNavigate, onNavigateBack)
    }
}

/** Miuix（MIUI）风格实现。 */
@Composable
fun SstvSettingsScreenMiuix(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
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
            Spacer(Modifier.height(12.dp))

            // ── 接收行为 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                SstvSectionHeader(
                    title = "接收行为",
                    summary = "自动保存与实时预览在接收期间生效；「持续接收」「连续接收」在主界面上切换。",
                )
                SstvSwitchRow(
                    title = "实时预览",
                    summary = "接收过程中逐行刷新画面；关闭可省电，收满仍会显示图像",
                    checked = uiState.livePreview,
                    onCheckedChange = { viewModel.setLivePreview(it) },
                )
                SstvSwitchRow(
                    title = "自动保存",
                    summary = "整帧接收完成后自动存入图库",
                    checked = uiState.autoSave,
                    onCheckedChange = { viewModel.setAutoSave(it) },
                )
                // 最大帧数只在连续接收开启时有意义 —— 关闭时整行隐藏
                if (uiState.continuous) {
                    OverlaySpinnerPreference(
                        title = "连续接收上限",
                        summary = "收满这么多帧后停止",
                        // 下拉项用 DropdownItem：Miuix 的 OverlaySpinnerPreference 只接受对象列表
                        items = MAX_FRAME_OPTIONS.map { frames ->
                            DropdownItem(text = "$frames 帧", onClick = { viewModel.setMaxFrames(frames) })
                        },
                        selectedIndex = MAX_FRAME_OPTIONS.indexOf(uiState.maxFrames)
                            .coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            MAX_FRAME_OPTIONS.getOrNull(index)?.let { viewModel.setMaxFrames(it) }
                        },
                    )
                }
                SstvSwitchRow(
                    title = "弱信号自适应积分窗",
                    summary = "按实测信噪比调整积分长度：弱信号多平均（细节略糊），强信号不变",
                    checked = uiState.adaptiveSpan,
                    onCheckedChange = { viewModel.setAdaptiveSpan(it) },
                )
                SstvSwitchRow(
                    title = "VIS 失败时盲扫",
                    summary = "导入录音时若 VIS 头缺失，按行同步间隔反推模式后再解码",
                    checked = uiState.blindScanOnFailure,
                    onCheckedChange = { viewModel.setBlindScanOnFailure(it) },
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 图像后处理 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                SstvSectionHeader(
                    title = "图像后处理",
                    summary = "处理不可逆，因此默认全关。开启后主界面仍可用「保存原图」保留" +
                        "未经处理的结果；总开关在主界面。",
                )
                SstvSwitchRow(
                    title = "直方图拉伸",
                    summary = "把实际亮度范围拉满，修复自动增益压缩导致的灰蒙蒙",
                    checked = uiState.postProcess.histogramStretch,
                    onCheckedChange = { viewModel.setHistogramStretch(it) },
                )
                SstvSwitchRow(
                    title = "中值降噪",
                    summary = "3×3 中值滤波去脉冲噪点（比均值滤波更保边缘）",
                    checked = uiState.postProcess.medianFilter,
                    onCheckedChange = { viewModel.setMedianFilter(it) },
                )
                SstvSwitchRow(
                    title = "坏行修复",
                    summary = "同步丢失导致的纯色行用上下相邻行插值补上",
                    checked = uiState.postProcess.repairBadLines,
                    onCheckedChange = { viewModel.setRepairBadLines(it) },
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 模式锁定 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                SstvSectionHeader(
                    title = "模式锁定",
                    summary = "默认由 VIS 头自动识别。弱信号下识别失败时可在此手动锁定，" +
                        "共 ${SstvMode.DECODABLE_MODES.size} 种模式；主界面顶部也有同一个开关。",
                )
                // 自动 = 清空锁定；其余每个模式一行，选中项用主色标出
                BasicComponent(
                    title = "自动识别",
                    summary = "推荐，由 VIS 头自动判定",
                    titleColor = SstvModeSelectionTitleColor(selected = uiState.manualModeName == null),
                    endActions = {
                        if (uiState.manualModeName == null) {
                            Text("已选", style = MiuixTheme.textStyles.body1, color = colorScheme.primary)
                        }
                    },
                    onClick = { viewModel.setManualMode(null) },
                )
                SstvMode.DECODABLE_MODES.forEach { mode ->
                    val selected = uiState.manualModeName == mode.name
                    BasicComponent(
                        title = mode.displayName,
                        summary = modeSummary(mode),
                        titleColor = SstvModeSelectionTitleColor(selected = selected),
                        endActions = {
                            if (selected) {
                                Text("已选", style = MiuixTheme.textStyles.body1, color = colorScheme.primary)
                            }
                        },
                        onClick = { viewModel.setManualMode(mode) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 图库与发射 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                SstvSectionHeader(
                    title = "图库与发射",
                    summary = "已保存 $galleryCount 张图像（应用私有目录，可分享或导出到相册）",
                )
                ArrowPreference(
                    title = "打开图库",
                    summary = "查看、分享或导出历史图像，也可重解接收录音",
                    onClick = { onNavigate(Route.SstvGallery()) },
                )
                ArrowPreference(
                    title = "打开发射页",
                    summary = "选图 → 编码成 SSTV 音频 → 播放或导出 WAV",
                    onClick = { onNavigate(Route.SstvTx) },
                )
                ArrowPreference(
                    title = "HF Fax（天气传真）",
                    summary = "120 行/分的灰度气象图接收，与 SSTV 是独立通路",
                    onClick = { onNavigate(Route.HfFax) },
                )
                ArrowPreference(
                    title = "清空图库",
                    summary = "删除全部已保存图像，不可撤销",
                    titleColor = BasicComponentDefaults.titleColor(color = colorScheme.error),
                    onClick = { confirmClear = true },
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 使用提示 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                SstvSectionHeader(title = "使用提示")
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
                    style = MiuixTheme.textStyles.footnote1,
                    color = colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
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

/** 卡片内统一的「标题 + 说明」头部（Miuix 版）。 */
@Composable
private fun SstvSectionHeader(title: String, summary: String? = null) {
    Column(
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            // 有说明时多留一点，和下方设置行拉开层次
            bottom = if (summary == null) 4.dp else 8.dp,
        ),
    ) {
        SstvCardTitle(title)
        if (summary != null) {
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MiuixTheme.textStyles.footnote1, color = colorScheme.onBackground)
        }
    }
}

/** 模式行的标题色：选中为主色，未选中为正文色。 */
@Composable
private fun SstvModeSelectionTitleColor(selected: Boolean) =
    BasicComponentDefaults.titleColor(
        color = if (selected) colorScheme.primary else colorScheme.onBackground,
    )

/** 模式的一行说明：分辨率 · 单帧时长 · 是否黑白（两套界面共用文案）。 */
internal fun modeSummary(mode: SstvMode): String = buildString {
    append("${mode.linePixels}×${mode.imageLines}")
    append("  ·  ${"%.0f".format(mode.frameSeconds)} 秒")
    if (mode.isMonochrome) append("  ·  黑白")
}
