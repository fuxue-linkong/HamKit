package com.example.hamkit.ui.screen.sstv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.data.sstv.SstvRecorder
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * SSTV 主界面：实时接收、离线导入、盲扫候选、信号质量、发射/图库入口。
 *
 * 图像会随接收进度自上而下逐步显现（每 [SstvRecorder] 部分解码一次刷新一屏）。
 *
 * 二期在这里新增了三条入口，各自解决一个具体问题：
 * - **导入 WAV**：不必守着电台，可事后解码录音（一期只能实时收）；
 * - **盲扫候选**：VIS 头被噪声吃掉时不再只能手动逐个试模式；
 * - **信号质量**：让用户能判断「要不要调整音量/手机位置」，而不是盲等。
 *
 * **版式（2026-10 整理）**：从上到下只有「预览与操作」与「接收设置」两张卡，外加
 * 一个按需出现的「盲扫候选」卡。接收明细（VIS、失谐、行周期比…）默认收起 ——
 * 这些是调试参数，常看的只有「模式 + 进度 + 信号质量」，所以把后三者留在预览卡里。
 *
 * @param presetModeName 由卫星详情页预置的模式名（如 ISS 的 `PD_120`）；非空时锁定该模式
 * @param autoStart 进入页面后立即开始接收（二期 T8：卫星过境提醒直达接收态）
 */
@Composable
fun SstvMainScreen(
    presetModeName: String? = null,
    autoStart: Boolean = false,
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SstvMainScreenMiuix(presetModeName, autoStart, onNavigate, onNavigateBack)
        UiMode.Material -> SstvMainScreenMaterial(presetModeName, autoStart, onNavigate, onNavigateBack)
    }
}

/** Miuix（MIUI）风格实现。 */
@Composable
fun SstvMainScreenMiuix(
    presetModeName: String? = null,
    autoStart: Boolean = false,
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    // 接收明细默认收起：常看的参数已放在预览卡的状态行里
    var detailsExpanded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearPreset() }
    }

    // 图库的大图查看器与接收页共用应用级 ViewModel。进入接收页时先收起它，
    // 这样从接收页返回图库（图 1）时看到的是列表，而不是上次留下的大图（图 3）。
    LaunchedEffect(Unit) { viewModel.closeViewer() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startReceiving() else viewModel.onPermissionDenied()
    }

    // 由卫星详情页或过境提醒进入时预置模式：ISS 等 SSTV 卫星可直接锁定其常用模式。
    // 走 applyPreset（不写设置）而非 setManualMode，并在离开页面时清除 ——
    // ViewModel 是应用级单例，否则预置会一直黏住，让首页进入也变成手动模式。
    LaunchedEffect(presetModeName, autoStart) {
        val mode = presetModeName?.let { name -> SstvMode.entries.find { it.name == name } }
        if (mode != null) viewModel.applyPreset(mode)

        // 二期 T8：卫星过境提醒直达接收态。必须与 applyPreset 同处一个 LaunchedEffect，
        // 因为 startReceiving 读的正是 applyPreset 刚写下的 presetOverride —— 拆到两个
        // 协程里就没有先后保证了。权限已授予就直接开收，否则弹一次系统授权。
        if (!autoStart) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startReceiving()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 音频文件导入：OpenDocument 无需存储权限；WAV 的 mime 在不同提供方之间不统一，
    // 因此额外放行 octet-stream（部分文件管理器就是这么标的）
    val importAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importAudio(it) } }

    // 播放捕获（Android 10+）：先向系统要投影授权，拿到 RESULT_OK 后才起 mediaProjection
    // 前台服务并取投影 —— 顺序不能反，部分厂商 ROM 在无有效授权时启动该类型前台服务会崩溃
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> handleCaptureResult(context, viewModel, result.resultCode, result.data) }

    /** 「开始/停止接收」：未授权时先弹系统授权。 */
    fun toggleReceiving() {
        if (uiState.isReceiving) {
            viewModel.stopReceiving()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.startReceiving() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "SSTV 慢扫描电视",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    TopBarAction("发射") { onNavigate(Route.SstvTx) }
                    TopBarAction("图库") { onNavigate(Route.SstvGallery()) }
                    TopBarAction("设置") { onNavigate(Route.SstvSettings) }
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

            // ── 卡 1：预览与操作 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SstvPreview(
                        aspect = uiState.mode?.let { it.linePixels.toFloat() / it.imageLines } ?: (4f / 3f),
                        bitmap = uiState.bitmap,
                        placeholder = "等待信号…\n把手机靠近电台，音量以不失真为准",
                    )

                    Spacer(Modifier.height(12.dp))
                    // 状态行：模式与信号质量常看，直接摆在进度条上方
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.mode?.displayName ?: "待识别",
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        val quality = if (uiState.phaseSpan > 0) {
                            "${uiState.qualityLabel} · ${"%.0f".format(uiState.qualityDb)} dB"
                        } else {
                            "信号 —"
                        }
                        Text(
                            text = quality,
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.onBackground,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    if (uiState.persistent && uiState.isReceiving) {
                        // 持续接收没有「一帧」的概念，进度条会把用户引向错误预期 ——
                        // 换成实时的行数与滚动状态说明
                        Text(
                            text = buildString {
                                append("已解 ${uiState.rollingRows} 行")
                                if (uiState.rollingScrolled) append("（画面只保留最近一段）")
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.primary,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = uiState.progress.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.status,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
                    )
                    uiState.importStatus?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = colorScheme.primary)
                    }
                    uiState.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = colorScheme.error)
                    }

                    // 主操作：开始/停止接收独占一行（接收中最常点的按钮），其余两两一行
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { toggleReceiving() },
                        modifier = Modifier.fillMaxWidth(),
                        // 停止接收是次要动作：用普通按钮（主色只在「开始接收」时出现）
                        colors = if (uiState.isReceiving) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.buttonColorsPrimary()
                        },
                    ) {
                        Text(if (uiState.isReceiving) "停止接收" else "开始接收")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { importAudio.launch(arrayOf("audio/*", "application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                            // 导入期间禁用而不是换配色：换配色仍可点，重复导入会被 ViewModel 静默忽略
                            enabled = !uiState.importing,
                        ) {
                            Text(if (uiState.importing) "导入中…" else "导入录音")
                        }
                        Button(
                            onClick = { viewModel.saveCurrent() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (uiState.postProcessEnabled) "保存增强图" else "保存图像")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 「保存原图」只在开着后处理时才有意义（否则与「保存图像」重复）
                        if (uiState.postProcessEnabled) {
                            Button(onClick = { viewModel.saveRaw() }, modifier = Modifier.weight(1f)) {
                                Text("保存原图")
                            }
                        }
                        Button(onClick = { viewModel.clearImage() }, modifier = Modifier.weight(1f)) {
                            Text("清空")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 卡 2：接收设置 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "接收设置",
                    style = MiuixTheme.textStyles.title4,
                    color = colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )

                // 音源：两选一用单选，比并排两个按钮更贴合「当前是哪一个」
                SstvRadioRow(
                    title = "麦克风",
                    summary = "手机对着电台扬声器；也可用音频线直连（更干净）",
                    selected = !uiState.usesPlaybackCapture,
                    onClick = { viewModel.useMicrophone() },
                )
                SstvRadioRow(
                    title = "播放捕获",
                    summary = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        "需要 Android 10 及以上（可解码其它应用正在播放的音频）"
                    } else {
                        "解码其它应用正在播放的音频：先在别处播放录音或打开 WebSDR，再开始接收"
                    },
                    selected = uiState.usesPlaybackCapture,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onClick = { requestPlaybackCapture(context, viewModel, captureLauncher) },
                )

                HorizontalDivider(color = colorScheme.dividerLine)

                // 模式：横向胶囊。「自动」恒在首位，选中即为 VIS 自动识别
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = if (uiState.presetApplied) {
                            "卫星预置模式，仅本次有效；选择任意模式即可取消"
                        } else {
                            "模式（默认自动识别 VIS）"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (uiState.presetApplied) colorScheme.primary else colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SstvChip("自动", uiState.manualModeName == null) { viewModel.setManualMode(null) }
                        SstvMode.DECODABLE_MODES.forEach { mode ->
                            SstvChip(mode.displayName, uiState.manualModeName == mode.name) {
                                viewModel.setManualMode(mode)
                            }
                        }
                    }
                }

                HorizontalDivider(color = colorScheme.dividerLine)

                // 开关：只留接收中会想调的两项，其余一次性配置放在设置页
                SstvSwitchRow(
                    title = "持续接收",
                    summary = "不需要 VIS 头、不等帧边界，从任意时刻开始解，画面像卷纸一样不断延长（需先锁定模式）",
                    checked = uiState.persistent,
                    onCheckedChange = { viewModel.setPersistent(it) },
                )
                SstvSwitchRow(
                    title = "连续接收",
                    summary = "收满一帧后保留模式与失谐量，自动接续下一帧（ISS 连发多张时用）",
                    checked = uiState.continuous,
                    onCheckedChange = { viewModel.setContinuous(it) },
                )
                SstvSwitchRow(
                    title = "后处理",
                    summary = "直方图拉伸 + 中值降噪 + 坏行修复，可在设置里分别开关",
                    checked = uiState.postProcessEnabled,
                    onCheckedChange = { viewModel.setPostProcessAll(it) },
                )
                SstvSwitchRow(
                    title = "瀑布图",
                    summary = "显示解调信号的频率-时间图，便于调谐与判断信号质量",
                    checked = uiState.waterfallEnabled,
                    onCheckedChange = { viewModel.setWaterfallEnabled(it) },
                )

                // 瀑布图只在开启时占位，避免卡片里留一块空白
                if (uiState.waterfallEnabled) {
                    HorizontalDivider(color = colorScheme.dividerLine)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "信号瀑布图",
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "1200 Hz 偏红橙、1500 Hz 偏黄绿、2300 Hz 偏青蓝（频率越低越暖、越高越冷）。" +
                                "调谐时看那条同步线是否稳稳落在青色区；散成一片说明信噪比差。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF101014)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val scope = uiState.waterfall
                            if (scope != null) {
                                Image(
                                    bitmap = scope.asImageBitmap(),
                                    contentDescription = "信号瀑布图",
                                    modifier = Modifier.fillMaxSize(),
                                    // 不做插值：瀑布图是逐格数据，平滑会糊掉谱线粗细这一关键信息
                                    contentScale = ContentScale.FillBounds,
                                    filterQuality = FilterQuality.None,
                                )
                            } else {
                                Text(
                                    text = "开始接收后显示",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colorScheme.dividerLine)

                // 接收明细：默认收起。单项 BasicComponent 排列的卡片本身就有分隔线，
                // 这里再画一条只会得到两条相邻的线，因此收起态放在分隔线组里
                BasicComponent(
                    title = "接收明细",
                    summary = "VIS 码、失谐补偿、行周期比、同步脉冲与噪声",
                    endActions = {
                        Text(
                            text = if (detailsExpanded) "收起" else "展开",
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.primary,
                        )
                    },
                    onClick = { detailsExpanded = !detailsExpanded },
                )
                if (detailsExpanded) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SstvInfoRow(
                            "VIS 码",
                            uiState.visCode?.let { "0x${it.toString(16).uppercase()}" } ?: "—",
                        )
                        SstvInfoRow("失谐补偿", "${uiState.hedrShiftHz.roundToInt()} Hz")
                        SstvInfoRow("行周期比", "%.4f".format(uiState.slantRatio))
                        SstvInfoRow("同步脉冲", "${uiState.syncPulseCount} 个")
                        SstvInfoRow("接收进度", "${uiState.decodedLines} / ${uiState.totalLines} 行")
                        if (uiState.phaseSpan > 0) {
                            SstvInfoRow(
                                "信号质量",
                                "${uiState.qualityLabel}（${"%.0f".format(uiState.qualityDb)} dB，" +
                                    "积分窗 ${uiState.phaseSpan} 样本）",
                            )
                        }
                        if (uiState.noiseHz > 0.0) {
                            SstvInfoRow("频率噪声", "${"%.1f".format(uiState.noiseHz)} Hz")
                        }
                        if (uiState.continuous) {
                            SstvInfoRow(
                                "连续接收",
                                "已完成 ${uiState.completedFrames} 帧（上限 ${uiState.maxFrames}）",
                            )
                        }
                        if (uiState.rollingRows > 0) {
                            SstvInfoRow("持续接收", "已解 ${uiState.rollingRows} 行")
                        }
                        uiState.savedFileName?.let { SstvInfoRow("已保存", it) }
                    }
                }
            }

            // ── 盲扫候选（VIS 缺失时的兜底结果；没有结果就不占版面）──
            if (uiState.blindCandidates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "盲扫候选",
                            style = MiuixTheme.textStyles.title4,
                            color = colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "VIS 头缺失时按行同步间隔反推的模式。行周期相同的模式本质上不可分，" +
                                "若图像不对可换一个候选重解（置信度 ${(uiState.blindConfidence * 100).toInt()}%）。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(8.dp))
                        uiState.blindCandidates.take(5).forEach { candidate ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${candidate.mode.displayName}  ·  得分 ${"%.2f".format(candidate.score)}",
                                        style = MiuixTheme.textStyles.body2,
                                        color = colorScheme.onBackground,
                                    )
                                    Text(
                                        "同步脉冲 ${candidate.pulseCount} 个 · 行周期比 " +
                                            "${"%.4f".format(candidate.slantRatio)} · " +
                                            "失谐 ${candidate.measuredShiftHz.roundToInt()} Hz",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = colorScheme.primary,
                                    )
                                }
                                Button(
                                    onClick = { viewModel.decodeImportedWith(candidate.mode) },
                                    colors = if (uiState.mode == candidate.mode) {
                                        ButtonDefaults.buttonColorsPrimary()
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                                ) {
                                    Text("重解")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 发起「播放捕获」授权流程。
 *
 * **顺序**：先向系统要投影授权（用户点「开始共享」），授权成功后在
 * [handleCaptureResult] 里才启动 mediaProjection 前台服务，最后取投影。
 *
 * 之所以不是「先起服务、再要授权」：AOSP 只要求在取投影前有该前台服务，但国产 ROM
 * （MIUI / HyperOS 等）在启动 mediaProjection 前台服务时会额外校验投影 token
 * （`CAPTURE_VIDEO_OUTPUT` / `project_media`），无授权即抛 `SecurityException` 并崩溃进程。
 * 先授权后起服务可同时满足两边。Miuix / Material 两套共用。
 */
internal fun requestPlaybackCapture(
    context: Context,
    viewModel: SstvViewModel,
    captureLauncher: ActivityResultLauncher<Intent>,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        // 低于 Android 10 根本没有播放捕获能力，直接走「未授权」分支
        viewModel.usePlaybackCapture(Activity.RESULT_CANCELED, null)
        return
    }
    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    val intent = manager?.createScreenCaptureIntent()
    if (intent != null) {
        // 起点就是授权弹窗：拿到 RESULT_OK 后由 handleCaptureResult 接着起服务+取投影
        captureLauncher.launch(intent)
    } else {
        viewModel.usePlaybackCapture(Activity.RESULT_CANCELED, null)
    }
}

/**
 * 处理投影授权结果：**成功分支才启动前台服务**，再由 ViewModel 取投影并切换音源；
 * 失败分支不会启动服务（避免厂商 ROM 因「无授权启动 mediaProjection FGS」而崩溃）。
 */
internal fun handleCaptureResult(
    context: Context,
    viewModel: SstvViewModel,
    resultCode: Int,
    data: Intent?,
) {
    // 启动前台服务 + 取投影 + 失败回退都在 ViewModel 内完成，UI 只转交授权结果
    viewModel.usePlaybackCapture(resultCode, data)
}

/** 顶栏文字动作（发射 / 图库 / 设置）。 */
@Composable
private fun TopBarAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1,
        color = colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

// ══════════════════════════════════════════════════════════════════
// SSTV 页面共用的小组件（Miuix）
// ══════════════════════════════════════════════════════════════════

/**
 * 图像预览区域：黑底 + 按模式宽高比。三处（主界面 / 发射 / 瀑布图以外的图像）共用。
 *
 * 传入的 `bitmap` 为 null 时显示 [placeholder]，字号小一号以免在窄屏上撑高画框。
 */
@Composable
internal fun SstvPreview(
    aspect: Float,
    bitmap: android.graphics.Bitmap?,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "SSTV 图像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = placeholder,
                style = MiuixTheme.textStyles.footnote1,
                color = Color.White,
            )
        }
    }
}

/** 横向胶囊选择项：用于模式这类「多选一、数量多、不需要说明」的集合。 */
@Composable
internal fun SstvChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    ) {
        Text(text)
    }
}

/** 开关行：标题 + 说明 + 右侧开关（整行不响应点击，只有开关本身可点）。 */
@Composable
internal fun SstvSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
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

/**
 * 单选行：整行可点，右侧显示「已选」。
 *
 * 不用 Miuix 的下拉/单选组件，是因为这里只有两个互斥选项（麦克风 / 播放捕获），
 * 直接铺开比收进弹层更快 —— 用户切音源往往是临时起意。禁用态用于「播放捕获」
 * 在 Android 10 以下不可用时。
 */
@Composable
internal fun SstvRadioRow(
    title: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        titleColor = BasicComponentDefaults.titleColor(
            color = if (!enabled) {
                colorScheme.disabledOnSurface
            } else if (selected) {
                colorScheme.primary
            } else {
                colorScheme.onBackground
            },
        ),
        onClick = if (enabled) onClick else null,
        endActions = {
            if (selected) {
                Text("已选", style = MiuixTheme.textStyles.body1, color = colorScheme.primary)
            }
        },
    )
}

/**
 * 参数行：左标签右取值。
 *
 * 两列都允许换行（取值可能是文件名），但标签保持不收缩的观感 —— 用 1:1.6 的权重比例，
 * 顺带让长文件名仍能完整显示。
 */
@Composable
internal fun SstvInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1.6f),
        )
    }
}

/** 卡片内的小标题（与主页 / 设置页保持同一字号与边距）。 */
@Composable
internal fun SstvCardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.title4,
        color = colorScheme.onBackground,
        modifier = modifier,
    )
}
