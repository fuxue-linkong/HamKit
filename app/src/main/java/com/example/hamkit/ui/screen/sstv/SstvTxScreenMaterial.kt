package com.example.hamkit.ui.screen.sstv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material3.HorizontalDivider
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
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedSwitchItem
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel

/**
 * SSTV 发射的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版逐项对应（版式规则也一致）：图像与发射控制合并成一张卡、参数独立一张卡、
 * 合规提示放最下方用 `errorContainer` 强调。
 *
 * 模式选择用横向滚动的 `FilterChip`：18 个模式下 chip 的选中态更紧凑，也符合 Material
 * 的多选一表达。
 */
@Composable
fun SstvTxScreenMaterial(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 相册选图：首选系统内置图片选择器（见 SstvTxScreen.kt 中同段注释）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.setTxImage(it) } }

    // 兜底：传统文件选择器（可到非媒体类目录取图）
    val pickImageLegacy = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.setTxImage(it) } }

    // 导出 WAV：CreateDocument 的 mimeType 在 launcher 构造时固定
    val createWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-wav"),
    ) { uri -> uri?.let { viewModel.exportTxAudio(it) } }

    val mode = uiState.txMode
    // 时长与实际编码一致：VIS 头由「包含 VIS 头」开关决定，不能恒按含 VIS 估算
    val scriptSeconds = mode.frameSeconds +
        (SstvEncoder.VIS_TOTAL_SECONDS.takeIf { uiState.txIncludeVis } ?: 0.0)

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
            // ── 卡 1：图像与发射控制 ──
            item {
                SstvCardMaterial {
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
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.txStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 一句说完「发什么、发多久」：源文件名 + 模式分辨率 + 单帧时长
                    Text(
                        text = listOfNotNull(
                            uiState.txSourceLabel,
                            "$mode ${mode.linePixels}×${mode.imageLines}",
                            // 与 scriptSeconds 的口径一致：开关关掉时 VIS 头不编码，也不该出现在文案里
                            "单帧 ${"%.0f".format(scriptSeconds)} 秒" +
                                if (uiState.txIncludeVis) "（含 0.91 秒 VIS 头）" else "",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("相册选图")
                        }
                        OutlinedButton(
                            onClick = { onNavigate(Route.SstvGallery(pickForTx = true)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("图库选图")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 相册里没有的原图（下载目录、U 盘、其他 App 私有目录）走文件选择器
                    OutlinedButton(
                        onClick = { pickImageLegacy.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("从文件选择")
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { uiState.txProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "进度 ${(uiState.txProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 主操作：开始/停止独占一行，导出 WAV 次之
                    Spacer(Modifier.height(10.dp))
                    if (uiState.txPlaying) {
                        OutlinedButton(
                            onClick = { viewModel.stopTx() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("停止发射")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startTx() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("开始发射")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            createWav.launch(
                                "SSTV_${mode.displayName.replace(' ', '_')}_" +
                                    System.currentTimeMillis().toString().takeLast(6) + ".wav",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导出 WAV")
                    }
                }
            }

            // ── 卡 2：发射参数 ──
            item {
                SstvCardMaterial {
                    Text(
                        text = "发射参数",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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
                }
            }

            item {
                SegmentedColumn(
                    content = listOf<@Composable () -> Unit>(
                        {
                            SegmentedSwitchItem(
                                title = "包含 VIS 头",
                                summary = "关闭后对方需手动锁定模式；只有自测时才建议关闭",
                                checked = uiState.txIncludeVis,
                                onCheckedChange = { viewModel.setTxIncludeVis(it) },
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                title = "叠加呼号",
                                summary = "在右下角写上前缀/呼号，便于对方确认来源",
                                checked = uiState.txOverlayCallsign,
                                onCheckedChange = { viewModel.setTxOverlayCallsign(it) },
                            )
                        },
                    ),
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

            // ── 卡 3：合规与操作要点（静态文字，放在最下方不挡主流程）──
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
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "• SSTV 发射会让电台实际发信，须持有相应操作资格并遵守当地无线电管理规定\n" +
                                "• 请确认所用频率无人占用，功率与带宽符合执照许可\n" +
                                "• 未持照时请只使用「导出 WAV」，用于本地自测或演示\n" +
                                "• 直接播放：手机扬声器对准电台话筒，音量以不失真为准\n" +
                                "• 导出 WAV：用音频线接电台 LINE IN / 数据口，能显著改善对方收到的画质\n" +
                                "• 最长模式单帧接近 5 分钟，发射期间请保持屏幕唤醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
