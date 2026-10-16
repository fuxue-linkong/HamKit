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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.example.hamkit.ui.component.material.SwitchItem
import com.example.hamkit.ui.viewmodel.HfFaxViewModel

/**
 * HF Fax（WEFAX）接收界面的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版的差别只在组件：状态参数表、固定高度可滚动横带、控制按钮、后处理开关、
 * 说明卡片一一对应，行为完全一致（同一个 [HfFaxViewModel]）。
 */
@Composable
fun HfFaxScreenMaterial(
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<HfFaxViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                title = { Text("HF Fax 天气传真") },
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
            // ── 状态 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = uiState.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        FaxInfoRow("已接收", "${uiState.decodedLines} / ${uiState.maxLines} 行")
                        FaxInfoRow("行速率", "120 行/分（每行 0.5 秒）")
                        FaxInfoRow(
                            "白边对齐",
                            if (uiState.aligned) "已对齐（循环移位 ${uiState.shiftPixels} 列）" else "统计中…",
                        )
                        FaxInfoRow(
                            "启动音",
                            when {
                                uiState.startToneDetected -> "已检测到（等待图像开始）"
                                uiState.waitingForStartTone -> "侦听中（300 Hz）"
                                else -> "已越过"
                            },
                        )
                        uiState.measuredOffsetHz?.let {
                            FaxInfoRow("频偏估计", "${"%.0f".format(it)} Hz")
                        }
                        uiState.savedFileName?.let { FaxInfoRow("已保存", it) }
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
            }

            // ── 图像：固定高度的可滚动横带 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "图像",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FAX_IMAGE_VIEWPORT_HEIGHT)
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
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "等待 300 Hz 启动音…\nWEFAX 没有行同步，收到启动音后才开始出图",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 控制 ──
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
                            onClick = { viewModel.startImmediately() },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.isReceiving && uiState.waitingForStartTone,
                        ) {
                            Text("立即开始")
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
                            Text("保存到图库")
                        }
                        OutlinedButton(
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
                        enabled = !uiState.importing,
                    ) {
                        Text(if (uiState.importing) "正在解码录音…" else "从录音解码（WAV / FLAC / MP3）")
                    }
                }
            }

            // ── 后处理 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "图像后处理",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "灰度传真图对这三项都很敏感：拉伸能把灰蒙蒙的底图拉开对比，" +
                                "中值去脉冲噪声，坏行修复补上丢行造成的黑条。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        SwitchItem(
                            title = "直方图拉伸",
                            summary = "把实际亮度范围拉满，改善灰蒙蒙的底图",
                            checked = uiState.postProcess.histogramStretch,
                            onCheckedChange = { viewModel.setHistogramStretch(it) },
                        )
                        SwitchItem(
                            title = "中值降噪",
                            summary = "3×3 中值滤波去脉冲噪点（对传真图常见的细颗粒噪声有效）",
                            checked = uiState.postProcess.medianFilter,
                            onCheckedChange = { viewModel.setMedianFilter(it) },
                        )
                        SwitchItem(
                            title = "坏行修复",
                            summary = "用上下相邻行插值补上丢失的整行",
                            checked = uiState.postProcess.repairBadLines,
                            onCheckedChange = { viewModel.setRepairBadLines(it) },
                        )
                    }
                }
            }

            // ── 说明 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "关于 HF Fax",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "• 与 SSTV 不是同一套协议：没有 VIS 头、也没有逐行同步脉冲，" +
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 参数行（Material 版）。
 *
 * 与 Miuix 版同样用「标签 1 份宽 / 取值 1.4 份宽」，只是字号与配色换成 Material 的
 * 层级：标签用 onSurfaceVariant、取值用 onSurface。
 */
@Composable
private fun FaxInfoRow(label: String, value: String) {
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

/** 图像可视区高度：太矮看不清整幅天气图，太高会把控制按钮挤出屏幕。 */
private val FAX_IMAGE_VIEWPORT_HEIGHT = 320.dp
