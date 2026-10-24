package com.example.hamkit.ui.screen.sstv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedListItem
import com.example.hamkit.ui.component.material.SegmentedRadioItem
import com.example.hamkit.ui.component.material.SegmentedSwitchItem
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import kotlin.math.roundToInt

/**
 * SSTV 接收主界面的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版逐项对应：预览与操作、接收设置（音源 / 模式 / 开关 / 瀑布图 / 明细）、
 * 盲扫候选。版式规则也一致：明细默认收起、「保存原图」只在开启后处理时出现、
 * 导入中禁用导入按钮。
 *
 * 前置逻辑（卫星预置、过境提醒直达、权限申请、录音导入、播放捕获）与 Miuix 版
 * 完全一致 —— 两套实现共享同一个 [SstvViewModel]，因此切换风格不会中断接收。
 */
@Composable
fun SstvMainScreenMaterial(
    presetModeName: String? = null,
    autoStart: Boolean = false,
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

    // 修复 P3-8：通知权限运行时请求（Android 13+ POST_NOTIFICATIONS）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // 由卫星详情页或过境提醒进入时预置模式。必须与 startReceiving 同处一个 LaunchedEffect：
    // startReceiving 读的正是 applyPreset 刚写下的 presetOverride。
    LaunchedEffect(presetModeName, autoStart) {
        val mode = presetModeName?.let { name -> SstvMode.entries.find { it.name == name } }
        if (mode != null) viewModel.applyPreset(mode)

        autoStartReceivingWithPermissions(
            autoStart = autoStart,
            context = context,
            viewModel = viewModel,
            requestAudioPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            requestNotificationPermission = {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
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
            if (granted) {
                viewModel.startReceiving()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSTV 慢扫描电视") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { onNavigate(Route.SstvTx) }) { Text("发射") }
                    TextButton(onClick = { onNavigate(Route.SstvGallery()) }) { Text("图库") }
                    TextButton(onClick = { onNavigate(Route.SstvSettings) }) { Text("设置") }
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
            // ── 卡 1：预览与操作 ──
            item {
                SstvCardMaterial {
                    val aspect = uiState.mode?.let { it.linePixels.toFloat() / it.imageLines } ?: (4f / 3f)
                    SstvPreviewMaterial(
                        aspect = aspect,
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
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (uiState.phaseSpan > 0) {
                                "${uiState.qualityLabel} · ${"%.0f".format(uiState.qualityDb)} dB"
                            } else {
                                "信号 —"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    if (uiState.persistent && uiState.isReceiving) {
                        // 持续接收没有「一帧」，进度条会误导用户 —— 换成实时行数
                        Text(
                            text = buildString {
                                append("已解 ${uiState.rollingRows} 行")
                                if (uiState.rollingScrolled) append("（画面只保留最近一段）")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { uiState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    uiState.importStatus?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    uiState.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // 主操作：开始/停止接收独占一行，其余两两一行
                    Spacer(Modifier.height(12.dp))
                    if (uiState.isReceiving) {
                        OutlinedButton(onClick = { toggleReceiving() }, modifier = Modifier.fillMaxWidth()) {
                            Text("停止接收")
                        }
                    } else {
                        Button(onClick = { toggleReceiving() }, modifier = Modifier.fillMaxWidth()) {
                            Text("开始接收")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { importAudio.launch(arrayOf("audio/*", "application/octet-stream")) },
                            modifier = Modifier.weight(1f),
                            // 导入期间禁用而不是换配色：换配色仍可点，重复导入会被 ViewModel 静默忽略
                            enabled = !uiState.importing,
                        ) {
                            Text(if (uiState.importing) "导入中…" else "导入录音")
                        }
                        OutlinedButton(onClick = { viewModel.saveCurrent() }, modifier = Modifier.weight(1f)) {
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
                            OutlinedButton(onClick = { viewModel.saveRaw() }, modifier = Modifier.weight(1f)) {
                                Text("保存原图")
                            }
                        }
                        OutlinedButton(onClick = { viewModel.clearImage() }, modifier = Modifier.weight(1f)) {
                            Text("清空")
                        }
                    }
                }
            }

            // ── 卡 2：接收设置 ──
            item {
                Column {
                    SstvCardMaterial(contentPadding = PaddingValues(vertical = 16.dp)) {
                        Text(
                            text = "接收设置",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))

                        // 音源：两选一
                        SegmentedColumn(
                            title = "音源",
                            content = buildList<@Composable () -> Unit> {
                                add {
                                    SegmentedRadioItem(
                                        title = "麦克风",
                                        summary = "手机对着电台扬声器；也可用音频线直连（更干净）",
                                        selected = !uiState.usesPlaybackCapture,
                                        onClick = { viewModel.useMicrophone() },
                                    )
                                }
                                add {
                                    SegmentedRadioItem(
                                        title = "播放捕获",
                                        summary = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                            "需要 Android 10 及以上（可解码其它应用正在播放的音频）"
                                        } else {
                                            "解码其它应用正在播放的音频：先在别处播放录音或打开 WebSDR，再开始接收"
                                        },
                                        selected = uiState.usesPlaybackCapture,
                                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                                        onClick = {
                                            requestPlaybackCapture(context, viewModel, captureLauncher)
                                        },
                                    )
                                }
                            },
                        )

                        Spacer(Modifier.height(16.dp))
                        // 模式：横向胶囊，「自动」恒在首位
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = if (uiState.presetApplied) {
                                    "卫星预置模式，仅本次有效；选择任意模式即可取消"
                                } else {
                                    "模式（默认自动识别 VIS）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.presetApplied) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = uiState.manualModeName == null,
                                    onClick = { viewModel.setManualMode(null) },
                                    label = { Text("自动") },
                                )
                                SstvMode.DECODABLE_MODES.forEach { mode ->
                                    FilterChip(
                                        selected = uiState.manualModeName == mode.name,
                                        onClick = { viewModel.setManualMode(mode) },
                                        label = { Text(mode.displayName) },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        SegmentedColumn(
                            title = "接收选项",
                            content = listOf<@Composable () -> Unit>(
                                {
                                    SegmentedSwitchItem(
                                        title = "持续接收",
                                        summary = "不需要 VIS 头、不等帧边界，从任意时刻开始解，" +
                                            "画面像卷纸一样不断延长（需先锁定模式）",
                                        checked = uiState.persistent,
                                        onCheckedChange = { viewModel.setPersistent(it) },
                                    )
                                },
                                {
                                    SegmentedSwitchItem(
                                        title = "连续接收",
                                        summary = "收满一帧后保留模式与失谐量，自动接续下一帧（ISS 连发多张时用）",
                                        checked = uiState.continuous,
                                        onCheckedChange = { viewModel.setContinuous(it) },
                                    )
                                },
                                {
                                    SegmentedSwitchItem(
                                        title = "后处理",
                                        summary = "直方图拉伸 + 中值降噪 + 坏行修复，可在设置里分别开关",
                                        checked = uiState.postProcessEnabled,
                                        onCheckedChange = { viewModel.setPostProcessAll(it) },
                                    )
                                },
                                {
                                    SegmentedSwitchItem(
                                        title = "瀑布图",
                                        summary = "显示解调信号的频率-时间图，便于调谐与判断信号质量",
                                        checked = uiState.waterfallEnabled,
                                        onCheckedChange = { viewModel.setWaterfallEnabled(it) },
                                    )
                                },
                            ),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 瀑布图只在开启时出现，避免卡片里留一块空白
                    if (uiState.waterfallEnabled) {
                        SstvCardMaterial {
                            Text(
                                text = "信号瀑布图",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "1200 Hz 偏红橙、1500 Hz 偏黄绿、2300 Hz 偏青蓝（频率越低越暖、越高越冷）。" +
                                    "调谐时看那条同步线是否稳稳落在青色区；散成一片说明信噪比差。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // 接收明细：默认收起
                    SstvCardMaterial(contentPadding = PaddingValues(vertical = 8.dp)) {
                        SegmentedColumn(
                            title = "接收明细",
                            content = buildList<@Composable () -> Unit> {
                                add {
                                    SegmentedListItem(
                                        onClick = { detailsExpanded = !detailsExpanded },
                                        headlineContent = { Text("接收明细") },
                                        supportingContent = {
                                            Text("VIS 码、失谐补偿、行周期比、同步脉冲与噪声")
                                        },
                                        trailingContent = {
                                            // 展开/收起用文字表达：省掉两个方向图标依赖，也更容易一眼看懂
                                            Text(if (detailsExpanded) "收起" else "展开")
                                        },
                                    )
                                }
                            },
                        )
                        if (detailsExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                SstvInfoRowMaterial(
                                    "VIS 码",
                                    uiState.visCode?.let { "0x${it.toString(16).uppercase()}" } ?: "—",
                                )
                                SstvInfoRowMaterial("失谐补偿", "${uiState.hedrShiftHz.roundToInt()} Hz")
                                SstvInfoRowMaterial("行周期比", "%.4f".format(uiState.slantRatio))
                                SstvInfoRowMaterial("同步脉冲", "${uiState.syncPulseCount} 个")
                                SstvInfoRowMaterial(
                                    "接收进度",
                                    "${uiState.decodedLines} / ${uiState.totalLines} 行",
                                )
                                if (uiState.phaseSpan > 0) {
                                    SstvInfoRowMaterial(
                                        "信号质量",
                                        "${uiState.qualityLabel}（${"%.0f".format(uiState.qualityDb)} dB，" +
                                            "积分窗 ${uiState.phaseSpan} 样本）",
                                    )
                                }
                                if (uiState.noiseHz > 0.0) {
                                    SstvInfoRowMaterial("频率噪声", "${"%.1f".format(uiState.noiseHz)} Hz")
                                }
                                if (uiState.rollingRows > 0) {
                                    SstvInfoRowMaterial("持续接收", "已解 ${uiState.rollingRows} 行")
                                }
                                if (uiState.continuous) {
                                    SstvInfoRowMaterial(
                                        "连续接收",
                                        "已完成 ${uiState.completedFrames} 帧（上限 ${uiState.maxFrames}）",
                                    )
                                }
                                uiState.savedFileName?.let { SstvInfoRowMaterial("已保存", it) }
                            }
                        }
                    }
                }
            }

            // ── 盲扫候选（VIS 缺失时的兜底结果；没有结果就不占版面）──
            if (uiState.blindCandidates.isNotEmpty()) {
                item {
                    SstvCardMaterial {
                        Text(
                            text = "盲扫候选",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "VIS 头缺失时按行同步间隔反推的模式。行周期相同的模式本质上不可分，" +
                                "若图像不对可换一个候选重解" +
                                "（置信度 ${(uiState.blindConfidence * 100).toInt()}%）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        uiState.blindCandidates.take(5).forEach { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${candidate.mode.displayName}  ·  " +
                                            "得分 ${"%.2f".format(candidate.score)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "同步脉冲 ${candidate.pulseCount} 个 · 行周期比 " +
                                            "${"%.4f".format(candidate.slantRatio)} · " +
                                            "失谐 ${candidate.measuredShiftHz.roundToInt()} Hz",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (uiState.mode == candidate.mode) {
                                    Button(onClick = { viewModel.decodeImportedWith(candidate.mode) }) {
                                        Text("重解")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { viewModel.decodeImportedWith(candidate.mode) },
                                    ) {
                                        Text("重解")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 图像预览区域（Material 版）：黑底 + 按模式宽高比。 */
@Composable
private fun SstvPreviewMaterial(
    aspect: Float,
    bitmap: android.graphics.Bitmap?,
    placeholder: String,
) {
    Box(
        modifier = Modifier
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
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/** 统一的卡片外壳：Material 版 SSTV 各页的所有分区都用同一套底色与圆角。 */
@Composable
internal fun SstvCardMaterial(
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/** 参数行（Material 版）：左标签右取值，标签 1 份宽、取值 1.6 份宽（与 Miuix 版一致）。 */
@Composable
private fun SstvInfoRowMaterial(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.6f),
        )
    }
}
