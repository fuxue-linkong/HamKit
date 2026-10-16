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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvCaptureService
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.data.sstv.SstvRecorder
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import kotlin.math.roundToInt
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
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    DisposableEffect(Unit) {
        onDispose { viewModel.clearPreset() }
    }

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
                    Text(
                        "发射",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.SstvTx) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        "图库",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.SstvGallery()) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        "设置",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.SstvSettings) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
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

            // ── 图像预览 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
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
                                style = MiuixTheme.textStyles.body1,
                                color = Color.White,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 进度条（用简单的两段 Box 实现，避免依赖具体进度条组件）
                    val progress = uiState.progress.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x33000000)),
                    ) {
                        if (progress > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(6.dp)
                                    .background(colorScheme.primary),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.status,
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    uiState.importStatus?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = colorScheme.primary)
                    }
                    uiState.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MiuixTheme.textStyles.body2, color = Color(0xFFD9534F))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 瀑布图（解调信号的频率-时间显示）──
            if (uiState.waterfallEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "信号瀑布图",
                            style = MiuixTheme.textStyles.title4,
                            color = colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "1200 Hz 冒青、1500 Hz 偏绿、2300 Hz 偏红。调谐时看那条同步线是否" +
                                "稳稳落在青色区；散成一片说明信噪比差。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.onBackground,
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
                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                                )
                            } else {
                                Text(
                                    "开始接收后显示",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            // ── 控制按钮 ──
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (uiState.isReceiving) {
                            viewModel.stopReceiving()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) viewModel.startReceiving() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = if (uiState.isReceiving) {
                        ButtonDefaults.buttonColors(
                            color = Color.Transparent,
                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    } else {
                        ButtonDefaults.buttonColorsPrimary()
                    },
                ) {
                    Text(if (uiState.isReceiving) "停止接收" else "开始接收")
                }
                Button(
                    onClick = { importAudio.launch(arrayOf("audio/*", "application/octet-stream")) },
                    modifier = Modifier.weight(1f),
                    colors = if (uiState.importing) {
                        ButtonDefaults.buttonColors(
                            color = Color.Transparent,
                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    } else {
                        ButtonDefaults.buttonColorsPrimary()
                    },
                ) {
                    Text(if (uiState.importing) "导入中…" else "导入录音")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.saveCurrent() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.postProcessEnabled) "保存增强图" else "保存图像")
                }
                if (uiState.postProcessEnabled) {
                    Button(
                        onClick = { viewModel.saveRaw() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存原图")
                    }
                }
                Button(
                    onClick = { viewModel.clearImage() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 盲扫候选（VIS 缺失时的兜底结果）──
            if (uiState.blindCandidates.isNotEmpty()) {
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
                                        ButtonDefaults.buttonColors(
                                            color = Color.Transparent,
                                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                        )
                                    },
                                ) {
                                    Text("重解")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── 接收参数与信号质量 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("接收参数", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("模式", uiState.mode?.displayName ?: "待识别")
                    InfoRow(
                        "VIS 码",
                        uiState.visCode?.let { "0x${it.toString(16).uppercase()}" } ?: "—",
                    )
                    InfoRow("失谐补偿", "${uiState.hedrShiftHz.roundToInt()} Hz")
                    InfoRow("行周期比", "%.4f".format(uiState.slantRatio))
                    InfoRow("同步脉冲", "${uiState.syncPulseCount} 个")
                    InfoRow("接收进度", "${uiState.decodedLines} / ${uiState.totalLines} 行")
                    if (uiState.phaseSpan > 0) {
                        InfoRow(
                            "信号质量",
                            "${uiState.qualityLabel}（${"%.0f".format(uiState.qualityDb)} dB，" +
                                "积分窗 ${uiState.phaseSpan} 样本）",
                        )
                    }
                    if (uiState.noiseHz > 0.0) {
                        InfoRow("频率噪声", "${"%.1f".format(uiState.noiseHz)} Hz")
                    }
                    if (uiState.continuous) {
                        InfoRow("连续接收", "已完成 ${uiState.completedFrames} 帧（上限 ${uiState.maxFrames}）")
                    }
                    uiState.savedFileName?.let { InfoRow("已保存", it) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 接收选项 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("接收选项", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        title = "连续接收",
                        summary = "收满一帧后保留模式与失谐量，自动接续下一帧（ISS 连发多张时用）",
                        checked = uiState.continuous,
                        onCheckedChange = { viewModel.setContinuous(it) },
                    )
                    SwitchRow(
                        title = "后处理",
                        summary = "直方图拉伸 + 中值降噪 + 坏行修复，可在设置里分别开关",
                        checked = uiState.postProcessEnabled,
                        onCheckedChange = { viewModel.setPostProcessAll(it) },
                    )
                    SwitchRow(
                        title = "瀑布图",
                        summary = "显示解调信号的频率-时间图，便于调谐与判断信号质量",
                        checked = uiState.waterfallEnabled,
                        onCheckedChange = { viewModel.setWaterfallEnabled(it) },
                    )
                    SwitchRow(
                        title = "录制原始音频",
                        summary = "把接收到的音频存成 WAV（带 VIS 头，可用「导入录音」重解；" +
                            "一帧 PD-120 约 12 MB）",
                        checked = uiState.recordAudio,
                        onCheckedChange = { viewModel.setRecordAudio(it) },
                    )
                    SwitchRow(
                        title = "像素级低通",
                        summary = "零相位、截止于像素 Nyquist；慢模式（如 Scottie DX）降噪明显，" +
                            "快模式基本无影响",
                        checked = uiState.pixelNyquistFilter,
                        onCheckedChange = { viewModel.setPixelNyquistFilter(it) },
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("音源", style = MiuixTheme.textStyles.body1, color = colorScheme.onBackground)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (uiState.usesPlaybackCapture) {
                            "播放捕获：解码其它应用正在播放的音频（Android 10+）"
                        } else {
                            "麦克风：手机对着电台扬声器；也可用音频线直连（更干净）"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.useMicrophone() },
                            modifier = Modifier.weight(1f),
                            colors = if (!uiState.usesPlaybackCapture) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors(
                                    color = Color.Transparent,
                                    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            },
                        ) {
                            Text("麦克风")
                        }
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                    viewModel.usePlaybackCapture(null)
                                } else {
                                    SstvCaptureService.start(context)
                                    val manager = context
                                        .getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                        as? MediaProjectionManager
                                    val intent = manager?.createScreenCaptureIntent()
                                    if (intent != null) {
                                        captureLauncher.launch(intent)
                                    } else {
                                        SstvCaptureService.stop(context)
                                        viewModel.usePlaybackCapture(null)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (uiState.usesPlaybackCapture) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors(
                                    color = Color.Transparent,
                                    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            },
                        ) {
                            Text("播放捕获")
                        }
                    }
                    if (uiState.usesPlaybackCapture) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "提示：先在其它应用里播放 SSTV 录音（或打开 WebSDR），再点「开始接收」。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 模式选择（VIS 失败时的兜底）──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("模式选择", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (uiState.presetApplied) {
                            "当前为卫星预置模式，仅本次接收有效；选择任意模式即可取消"
                        } else {
                            "默认自动识别 VIS；弱信号下可手动锁定模式"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (uiState.presetApplied) colorScheme.primary else colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = { viewModel.setManualMode(null) },
                            colors = if (uiState.manualModeName == null) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors(
                                    color = Color.Transparent,
                                    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            },
                        ) {
                            Text("自动")
                        }
                        SstvMode.DECODABLE_MODES.forEach { mode ->
                            Button(
                                onClick = { viewModel.setManualMode(mode) },
                                colors = if (uiState.manualModeName == mode.name) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors(
                                        color = Color.Transparent,
                                        contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                    )
                                },
                            ) {
                                Text(mode.displayName)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
internal fun SwitchRow(
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
