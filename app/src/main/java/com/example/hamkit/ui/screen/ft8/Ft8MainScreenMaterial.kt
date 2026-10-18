package com.example.hamkit.ui.screen.ft8

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8Encoder
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.Ft8ViewModel

/**
 * FT8 主页（Material 3 风格）。
 *
 * 与 [Ft8MainScreenMiuix] 行为完全一致：呼号 / 网格展示、消息编辑与 37 字符限制、
 * 波段选择、麦克风解码（含录音权限申请）、解码结果列表，以及编码播放。
 * 两套实现读同一个 [Ft8ViewModel]（application 级 ViewModelStoreOwner），状态天然同步。
 */
@Composable
fun Ft8MainScreenMaterial(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<Ft8ViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config = uiState.config
    val context = LocalContext.current

    var messageInput by remember { mutableStateOf("") }

    // 与 Miuix 版一致：权限回调里按授予结果决定开始解码还是提示被拒
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startDecoding()
        } else {
            viewModel.notifyPermissionDenied()
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FT8") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    // Miuix 版把「日志 / 设置」做成可点击文本，这里用 TextButton 保持一致的可点区域语义
                    TextButton(onClick = { onNavigate(Route.QsoLogList) }) {
                        Text("日志")
                    }
                    TextButton(onClick = { onNavigate(Route.Ft8Settings) }) {
                        Text("设置")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        )
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 呼号 + 网格显示
            Ft8CardMaterial {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "呼号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = config.callsign.ifEmpty { "未设置" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        // 未设置呼号时用错误色提醒用户先完成身份配置
                        color = if (config.callsign.isEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "网格",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = config.grid.ifEmpty { "-" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "当前波段: ${config.band.displayName} (${config.band.freqHz / 1000} kHz)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 消息编辑
            Spacer(Modifier.height(16.dp))
            Ft8SectionTitleMaterial("消息编辑")
            Ft8CardMaterial {
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { newValue ->
                        // 只允许 FT8 消息合法字符集，并硬限制 37 字符（标准消息长度）
                        val filtered = newValue.uppercase()
                            .filter { it.isLetterOrDigit() || it == ' ' || it == '/' || it == '-' }
                        if (filtered.length <= 37) {
                            messageInput = filtered
                        }
                    },
                    label = { Text("CQ CALLSIGN GRID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${messageInput.length}/37",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            // 波段选择
            Spacer(Modifier.height(16.dp))
            Ft8SectionTitleMaterial("波段选择")
            Ft8CardMaterial {
                // 与 Miuix 版同样 3 列一组，保持波段矩阵的排布一致
                Ft8Band.entries.chunked(3).forEach { rowBands ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowBands.forEach { band ->
                            Ft8BandButtonMaterial(
                                band = band,
                                selected = band == config.band,
                                onClick = { viewModel.setBand(band) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 解码控制
            Spacer(Modifier.height(16.dp))
            Ft8SectionTitleMaterial("解码")
            Ft8CardMaterial {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.isDecoding) "正在监听 FT8 信号…" else "监听麦克风解码 FT8",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = uiState.decodeStatus.ifEmpty { "点击开始后，将启动麦克风并持续检测 FT8 信号" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (uiState.isDecoding) {
                                viewModel.stopDecoding()
                            } else {
                                // 先查权限，已授权直接开始；否则走运行时申请
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    viewModel.startDecoding()
                                } else {
                                    recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        colors = if (uiState.isDecoding) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(if (uiState.isDecoding) "停止" else "开始解码")
                    }
                }
            }

            // 解码结果列表
            if (uiState.decodedEntries.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ft8SectionTitleMaterial("解码结果 (${uiState.decodedEntries.size})")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearDecoded() }) {
                        Text("清空")
                    }
                }
                uiState.decodedEntries.forEach { entry ->
                    val r = entry.result
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = if (r.success) "✓ ${r.messageText}" else "✗ ${r.messageText}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (r.success) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 只有解码成功的条目才有 SNR/DT/CRC 等可信度信息
                            if (r.success) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "SNR: %.1f dB · DT: %+.2f s · 频率: %.0f Hz · 同步分: %.1f".format(
                                        r.snr, r.dt, r.freqHz, r.syncScore
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "CRC: ${if (r.crcValid) "有效" else "无效"} · RAW: 0x${r.raw77Hex}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 编码并播放按钮
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (uiState.isPlaying) {
                        viewModel.stopPlayback()
                    } else {
                        viewModel.encodeMessage(messageInput)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                // 编码中、消息为空或未配置呼号时禁用（与 Miuix 版判定一致）
                enabled = !uiState.isEncoding && messageInput.isNotBlank() && config.callsign.isNotBlank()
            ) {
                Text(
                    text = when {
                        uiState.isEncoding -> "编码中…"
                        uiState.isPlaying -> "停止播放"
                        else -> "编码并播放"
                    }
                )
            }

            uiState.lastError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "错误: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 编码结果
            uiState.generatedPcm?.let { pcm ->
                Spacer(Modifier.height(16.dp))
                Ft8SectionTitleMaterial("已编码")
                Ft8CardMaterial {
                    Text(
                        text = uiState.lastMessageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${pcm.size} samples @ ${Ft8Encoder.SAMPLE_RATE} Hz " +
                                "(${pcm.size / Ft8Encoder.SAMPLE_RATE}s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.isPlaying) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "正在播放…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (uiState.isPlaying) {
                                viewModel.stopPlayback()
                            } else {
                                viewModel.playPcm(pcm)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (uiState.isPlaying) "停止播放" else "重新播放")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Material 风格卡片：统一使用 surfaceContainerHigh 容器色 */
@Composable
private fun Ft8CardMaterial(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** Material 风格分组标题 */
@Composable
private fun Ft8SectionTitleMaterial(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/** 波段按钮：选中态用 primary 实心，未选中用低强调的 surfaceVariant，避免透明按钮在深色主题下不可见 */
@Composable
private fun Ft8BandButtonMaterial(
    band: Ft8Band,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (selected) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Text(text = band.displayName, style = MaterialTheme.typography.labelMedium)
    }
}
