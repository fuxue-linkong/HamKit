package com.example.hamkit.ui.screen.sstv

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.viewmodel.HfFaxViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * HF Fax（WEFAX）天气传真接收界面。
 *
 * **与 SSTV 界面的差别都来自协议差异**，不是设计选择：
 *  - 没有模式可选、没有 VIS 码、没有行同步 —— 因此没有「模式选择」「失谐补偿」「slant」这些卡片；
 *  - 图像是**一条不断变长的横带**（每行 0.5 秒、最多 1200 行），不是定长的一帧，
 *    所以用「固定高度的可滚动窗口」而不是按宽高比缩放的画框；
 *  - 开头可能有一段 phasing 条纹（黑白相间的校准行），这是正常现象。
 *
 * 界面里明确标注了「未在真实 WEFAX 信号上验证」——本功能只在合成信号测试中验证过。
 */
@Composable
fun HfFaxScreen(
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<HfFaxViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startReceiving() else viewModel.stopReceiving()
    }

    // 音频文件导入：气象传真台多在固定时刻播发，录下来再解是常规用法。
    // OpenDocument 不需要存储权限；WAV 之外的容器由 MediaExtractor/MediaCodec 处理
    val importAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importAudio(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "HF Fax 天气传真",
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

            // ── 状态 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(uiState.status, style = MiuixTheme.textStyles.body1, color = colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    InfoRow("已接收", "${uiState.decodedLines} / ${uiState.maxLines} 行")
                    InfoRow("行速率", "120 行/分（每行 0.5 秒）")
                    InfoRow(
                        "白边对齐",
                        if (uiState.aligned) "已对齐（循环移位 ${uiState.shiftPixels} 列）" else "统计中…",
                    )
                    InfoRow(
                        "启动音",
                        when {
                            uiState.startToneDetected -> "已检测到（等待图像开始）"
                            uiState.waitingForStartTone -> "侦听中（300 Hz）"
                            else -> "已越过"
                        },
                    )
                    uiState.measuredOffsetHz?.let { InfoRow("频偏估计", "${"%.0f".format(it)} Hz") }
                    uiState.savedFileName?.let { InfoRow("已保存", it) }
                    uiState.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MiuixTheme.textStyles.body2, color = Color(0xFFD9534F))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 图像：固定高度的可滚动横带 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("图像", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IMAGE_VIEWPORT_HEIGHT)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                    ) {
                        val bitmap = uiState.bitmap
                        if (bitmap != null) {
                            // 横带比可视区高得多，用 verticalScroll 让用户能上下翻看已收到的部分；
                            // 宽度固定填满（640 像素宽的传真行按宽度铺开即可辨认）
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "HF Fax 接收图像",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth,
                                )
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "等待 300 Hz 启动音…\nWEFAX 没有行同步，收到启动音后才开始出图",
                                    style = MiuixTheme.textStyles.body2,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 控制 ──
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    onClick = { viewModel.startImmediately() },
                    modifier = Modifier.weight(1f),
                    colors = if (uiState.isReceiving && uiState.waitingForStartTone) {
                        ButtonDefaults.buttonColorsPrimary()
                    } else {
                        ButtonDefaults.buttonColors(
                            color = Color.Transparent,
                            contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    },
                ) {
                    Text("立即开始")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.saveCurrent() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存到图库")
                }
                Button(
                    onClick = { viewModel.clearImage() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空")
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { importAudio.launch(arrayOf("audio/*", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (uiState.importing) {
                    ButtonDefaults.buttonColors(
                        color = Color.Transparent,
                        contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                } else {
                    ButtonDefaults.buttonColorsPrimary()
                },
            ) {
                Text(if (uiState.importing) "正在解码录音…" else "从录音解码（WAV / FLAC / MP3）")
            }

            Spacer(Modifier.height(12.dp))

            // ── 后处理 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("图像后处理", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "灰度传真图对这三项都很敏感：拉伸能把灰蒙蒙的底图拉开对比，" +
                            "中值去脉冲噪声，坏行修复补上丢行造成的黑条。",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        title = "直方图拉伸",
                        summary = "把实际亮度范围拉满，改善灰蒙蒙的底图",
                        checked = uiState.postProcess.histogramStretch,
                        onCheckedChange = { viewModel.setHistogramStretch(it) },
                    )
                    SwitchRow(
                        title = "中值降噪",
                        summary = "3×3 中值滤波去脉冲噪点（对传真图常见的细颗粒噪声有效）",
                        checked = uiState.postProcess.medianFilter,
                        onCheckedChange = { viewModel.setMedianFilter(it) },
                    )
                    SwitchRow(
                        title = "坏行修复",
                        summary = "用上下相邻行插值补上丢失的整行",
                        checked = uiState.postProcess.repairBadLines,
                        onCheckedChange = { viewModel.setRepairBadLines(it) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 说明 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("关于 HF Fax", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• 与 SSTV 不是同一套协议：没有 VIS 头、也没有逐行同步脉冲，" +
                            "行节拍靠「120 行/分钟」这个约定自由运行\n" +
                            "• 因此垂直方向无法逐行校正：图像可能整体偏移半行，" +
                            "开头几行也可能是 phasing 校准条纹，属正常现象\n" +
                            "• 水平方向靠图像左侧的粗白边自动对齐（统计各列平均亮度）\n" +
                            "• 发射前通常有 5–10 秒的 300 Hz 启动音，本页靠它找到图像起点；" +
                            "若录音从图像中间开始，请点「立即开始」\n" +
                            "• 灰度映射与 SSTV 相同：1500 Hz = 黑、2300 Hz = 白\n" +
                            "• 常见业务：各地气象传真台按 120 行/分播发天气图与分析图，" +
                            "具体频率与时刻表见各地气象传真台的播发计划（会随季节调整）\n" +
                            "• 本功能尚未在真实 WEFAX 信号上验证，目前只在合成信号测试中通过",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 图像可视区高度：太矮看不清整幅天气图，太高会把控制按钮挤出屏幕。 */
private val IMAGE_VIEWPORT_HEIGHT = 320.dp
