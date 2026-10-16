package com.example.hamkit.ui.screen.sstv

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvEncoder
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.component.material.SwitchItem
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel

/**
 * SSTV 发射的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版逐项对应：合规提示 / 待发射图像 / 发射参数 / 发射控制。
 * 行为完全一致 —— 选图、导出 WAV、开始与停止全部走同一个 [SstvViewModel]。
 *
 * 模式选择用横向滚动的 `FilterChip`（Miuix 版是一排按钮）：18 个模式下 chip 的
 * 选中态更紧凑，也符合 Material 的多选一表达。
 */
@Composable
fun SstvTxScreenMaterial(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 相册/文件选择图片（OpenDocument 不需要任何存储权限）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.setTxImage(it) } }

    // 导出 WAV：CreateDocument 的 mimeType 在 launcher 构造时固定
    val createWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-wav"),
    ) { uri -> uri?.let { viewModel.exportTxAudio(it) } }

    val mode = uiState.txMode
    val scriptSeconds = mode.frameSeconds + SstvEncoder.VIS_TOTAL_SECONDS

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSTV 发射") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { onNavigate(Route.SstvGallery(pickForTx = true)) }) {
                        Text("图库")
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
            // ── 合规提示（放在最上方，进入页面第一眼就能看到）──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "发射前请确认",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "• SSTV 发射会让电台实际发信，须持有相应操作资格并遵守当地无线电管理规定\n" +
                                "• 请确认所用频率无人占用，功率与带宽符合执照许可\n" +
                                "• 未持照时请只使用「导出 WAV」，用于本地自测或演示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // ── 图像预览 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "待发射图像",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(mode.linePixels.toFloat() / mode.imageLines)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            val preview = uiState.txPreview
                            if (preview != null) {
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = "待发射图像",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(
                                    text = "尚未选择图片",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = uiState.txStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { pickImage.launch(arrayOf("image/*")) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("从相册选图")
                            }
                            OutlinedButton(
                                onClick = { onNavigate(Route.SstvGallery(pickForTx = true)) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("从图库选图")
                            }
                        }
                    }
                }
            }

            // ── 发射参数 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "发射参数",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "单帧时长 ${"%.0f".format(scriptSeconds)} 秒（含 0.91 秒 VIS 头）" +
                                " · 分辨率 ${mode.linePixels}×${mode.imageLines}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SstvMode.DECODABLE_MODES.forEach { candidate ->
                                FilterChip(
                                    selected = uiState.txModeName == candidate.name,
                                    onClick = { viewModel.setTxMode(candidate) },
                                    label = { Text(candidate.displayName) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SwitchItem(
                            title = "包含 VIS 头",
                            summary = "关闭后对方需手动锁定模式；只有自测时才建议关闭",
                            checked = uiState.txIncludeVis,
                            onCheckedChange = { viewModel.setTxIncludeVis(it) },
                        )
                        SwitchItem(
                            title = "叠加呼号",
                            summary = "在右下角写上前缀/呼号，便于对方确认来源",
                            checked = uiState.txOverlayCallsign,
                            onCheckedChange = { viewModel.setTxOverlayCallsign(it) },
                        )
                        if (uiState.txOverlayCallsign) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = uiState.callsign,
                                onValueChange = { viewModel.setCallsign(it) },
                                label = { Text("呼号（如 BG5XXX）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            )
                        }
                    }
                }
            }

            // ── 发射控制 ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "发射",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(12.dp))

                        val progress = uiState.txProgress.coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "进度 ${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    if (uiState.txPlaying) viewModel.stopTx() else viewModel.startTx()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (uiState.txPlaying) "停止发射" else "开始发射")
                            }
                            OutlinedButton(
                                onClick = {
                                    createWav.launch(
                                        "SSTV_${mode.displayName.replace(' ', '_')}_" +
                                            System.currentTimeMillis().toString().takeLast(6) + ".wav",
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("导出 WAV")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "• 直接播放：手机扬声器对准电台话筒，音量以不失真为准\n" +
                                "• 导出 WAV：用音频线接电台 LINE IN / 数据口，能显著改善对方收到的画质\n" +
                                "• 最长模式单帧接近 5 分钟，发射期间请保持屏幕唤醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}
