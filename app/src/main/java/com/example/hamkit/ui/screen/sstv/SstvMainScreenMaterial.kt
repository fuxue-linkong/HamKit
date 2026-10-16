package com.example.hamkit.ui.screen.sstv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import com.example.hamkit.data.sstv.SstvCaptureService
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.component.material.SwitchItem
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import kotlin.math.roundToInt

/**
 * SSTV 接收主界面的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版逐项对应：图像预览与进度、信号瀑布图、接收/导入控制、保存与清空、
 * 盲扫候选、接收参数与信号质量、接收选项、音源选择、模式选择。
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
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { viewModel.clearPreset() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startReceiving() else viewModel.onPermissionDenied()
    }

    // 由卫星详情页或过境提醒进入时预置模式。必须与 startReceiving 同处一个 LaunchedEffect：
    // startReceiving 读的正是 applyPreset 刚写下的 presetOverride。
    LaunchedEffect(presetModeName, autoStart) {
        val mode = presetModeName?.let { name -> SstvMode.entries.find { it.name == name } }
        if (mode != null) viewModel.applyPreset(mode)

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

    // 播放捕获（Android 10+）：先起 mediaProjection 前台服务，再向系统要投影授权。
    // 顺序不能颠倒 —— Android 14 起要求调用 getMediaProjection() 时已有该类型的前台服务
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as? MediaProjectionManager
            val projection = manager?.let {
                runCatching { it.getMediaProjection(result.resultCode, data) }.getOrNull()
            }
            viewModel.usePlaybackCapture(projection)
            if (projection == null) SstvCaptureService.stop(context)
        } else {
            viewModel.usePlaybackCapture(null)
            SstvCaptureService.stop(context)
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
            // ── 图像预览 ──
            item {
                SstvCard {
                    val aspect = uiState.mode?.let { it.linePixels.toFloat() / it.imageLines } ?: (4f / 3f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = uiState.bitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "SSTV 解码图像",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                text = "等待信号…\n把手机扬声器侧贴近电台，音量适中",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val progress = uiState.progress.coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── 瀑布图（解调信号的频率-时间显示）──
            if (uiState.waterfallEnabled) {
                item {
                    SstvCard {
                        Text(
                            text = "信号瀑布图",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "1200 Hz 冒青、1500 Hz 偏绿、2300 Hz 偏红。调谐时看那条同步线是否" +
                                "稳稳落在青色区；散成一片说明信噪比差。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(6.dp))
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
                }
            }

            // ── 控制按钮 ──
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
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
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (uiState.isReceiving) "停止接收" else "开始接收")
                        }
                        OutlinedButton(
                            onClick = {
                                importAudio.launch(arrayOf("audio/*", "application/octet-stream"))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.importing,
                        ) {
                            Text(if (uiState.importing) "导入中…" else "导入录音")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.saveCurrent() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (uiState.postProcessEnabled) "保存增强图" else "保存图像")
                        }
                        if (uiState.postProcessEnabled) {
                            OutlinedButton(
                                onClick = { viewModel.saveRaw() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("保存原图")
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearImage() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("清空")
                        }
                    }
                }
            }

            // ── 盲扫候选（VIS 缺失时的兜底结果）──
            if (uiState.blindCandidates.isNotEmpty()) {
                item {
                    SstvCard {
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

            // ── 接收参数与信号质量 ──
            item {
                SstvCard {
                    Text(
                        text = "接收参数",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    SstvInfoRow("模式", uiState.mode?.displayName ?: "待识别")
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
                    uiState.savedFileName?.let { SstvInfoRow("已保存", it) }
                }
            }

            // ── 接收选项 ──
            item {
                SstvCard {
                    Text(
                        text = "接收选项",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchItem(
                        title = "连续接收",
                        summary = "收满一帧后保留模式与失谐量，自动接续下一帧（ISS 连发多张时用）",
                        checked = uiState.continuous,
                        onCheckedChange = { viewModel.setContinuous(it) },
                    )
                    SwitchItem(
                        title = "后处理",
                        summary = "直方图拉伸 + 中值降噪 + 坏行修复，可在设置里分别开关",
                        checked = uiState.postProcessEnabled,
                        onCheckedChange = { viewModel.setPostProcessAll(it) },
                    )
                    SwitchItem(
                        title = "瀑布图",
                        summary = "显示解调信号的频率-时间图，便于调谐与判断信号质量",
                        checked = uiState.waterfallEnabled,
                        onCheckedChange = { viewModel.setWaterfallEnabled(it) },
                    )
                    SwitchItem(
                        title = "录制原始音频",
                        summary = "把接收到的音频存成 WAV（带 VIS 头，可用「导入录音」重解；" +
                            "一帧 PD-120 约 12 MB）",
                        checked = uiState.recordAudio,
                        onCheckedChange = { viewModel.setRecordAudio(it) },
                    )
                    SwitchItem(
                        title = "像素级低通",
                        summary = "零相位、截止于像素 Nyquist；慢模式（如 Scottie DX）降噪明显，" +
                            "快模式基本无影响",
                        checked = uiState.pixelNyquistFilter,
                        onCheckedChange = { viewModel.setPixelNyquistFilter(it) },
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "音源",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (uiState.usesPlaybackCapture) {
                            "播放捕获：解码其它应用正在播放的音频（Android 10+）"
                        } else {
                            "麦克风：手机对着电台扬声器；也可用音频线直连（更干净）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!uiState.usesPlaybackCapture) {
                            Button(
                                onClick = { viewModel.useMicrophone() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("麦克风")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.useMicrophone() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("麦克风")
                            }
                        }
                        if (uiState.usesPlaybackCapture) {
                            Button(
                                onClick = { requestPlaybackCapture(context, viewModel, captureLauncher) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("播放捕获")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { requestPlaybackCapture(context, viewModel, captureLauncher) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("播放捕获")
                            }
                        }
                    }
                    if (uiState.usesPlaybackCapture) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "提示：先在其它应用里播放 SSTV 录音（或打开 WebSDR），再点「开始接收」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // ── 模式选择（VIS 失败时的兜底）──
            item {
                SstvCard {
                    Text(
                        text = "模式选择",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (uiState.presetApplied) {
                            "当前为卫星预置模式，仅本次接收有效；选择任意模式即可取消"
                        } else {
                            "默认自动识别 VIS；弱信号下可手动锁定模式"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.presetApplied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(12.dp))
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
            }
        }
    }
}

/**
 * 发起「播放捕获」授权流程。
 *
 * 与 Miuix 版同一段逻辑，抽成函数只是为了在 Material 版里两个按钮分支中复用：
 * 先起前台服务，再向系统要投影授权（顺序不可颠倒，见 Android 14 的前台服务要求）。
 */
private fun requestPlaybackCapture(
    context: Context,
    viewModel: SstvViewModel,
    captureLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        viewModel.usePlaybackCapture(null)
        return
    }
    SstvCaptureService.start(context)
    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    val intent = manager?.createScreenCaptureIntent()
    if (intent != null) {
        captureLauncher.launch(intent)
    } else {
        SstvCaptureService.stop(context)
        viewModel.usePlaybackCapture(null)
    }
}

/** 统一的卡片外壳：Material 版所有分区都用同一套底色与圆角。 */
@Composable
private fun SstvCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** 参数行（Material 版）：左标签右取值，标签 1 份宽、取值 1.4 份宽。 */
@Composable
private fun SstvInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.4f),
        )
    }
}
