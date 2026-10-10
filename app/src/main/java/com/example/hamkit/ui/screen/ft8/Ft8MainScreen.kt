package com.example.hamkit.ui.screen.ft8

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.Ft8ViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun Ft8MainScreen(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = appViewModel<Ft8ViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config = uiState.config
    val context = LocalContext.current

    var messageInput by remember { mutableStateOf("") }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startDecoding()
        } else {
            viewModel.notifyPermissionDenied()
        }
    }

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "FT8",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    Text(
                        "日志",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.QsoLogList) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    Text(
                        "设置",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.Ft8Settings) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .overScrollVertical()
                .scrollEndHaptic()
                .padding(horizontal = 16.dp)
                .padding(top = innerPadding.calculateTopPadding())
        ) {

        // 呼号 + 网格显示
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "呼号",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        config.callsign.ifEmpty { "未设置" },
                        style = MiuixTheme.textStyles.body1,
                        color = if (config.callsign.isEmpty()) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "网格",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        config.grid.ifEmpty { "-" },
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "当前波段: ${config.band.displayName} (${config.band.freqHz / 1000} kHz)",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }

        // 消息编辑
        Spacer(Modifier.height(16.dp))
        SectionTitle("消息编辑")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                TextField(
                    value = messageInput,
                    onValueChange = { newValue ->
                        val filtered = newValue.uppercase()
                            .filter { it.isLetterOrDigit() || it == ' ' || it == '/' || it == '-' }
                        if (filtered.length <= 37) {
                            messageInput = filtered
                        }
                    },
                    label = "CQ CALLSIGN GRID",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${messageInput.length}/37",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }

        // 波段选择
        Spacer(Modifier.height(16.dp))
        SectionTitle("波段选择")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val bands = Ft8Band.entries
                bands.chunked(3).forEach { rowBands ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        rowBands.forEach { band ->
                            val isSelected = band == config.band
                            Button(
                                onClick = { viewModel.setBand(band) },
                                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                                colors = if (isSelected) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors(
                                        color = Color.Transparent,
                                        contentColor = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                }
                            ) {
                                Text(
                                    band.displayName,
                                    style = MiuixTheme.textStyles.body1
                                )
                            }
                        }
                    }
                }
            }
        }

        // 解码控制
        Spacer(Modifier.height(16.dp))
        SectionTitle("解码")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (uiState.isDecoding) "正在监听 FT8 信号…" else "监听麦克风解码 FT8",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            uiState.decodeStatus.ifEmpty { "点击开始后，将启动麦克风并持续检测 FT8 信号" },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (uiState.isDecoding) {
                                viewModel.stopDecoding()
                            } else {
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
                                color = MiuixTheme.colorScheme.error,
                                contentColor = MiuixTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            ButtonDefaults.buttonColorsPrimary()
                        }
                    ) {
                        Text(if (uiState.isDecoding) "停止" else "开始解码")
                    }
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
                SectionTitle("解码结果 (${uiState.decodedEntries.size})")
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { viewModel.clearDecoded() }) {
                    Text("清空", style = MiuixTheme.textStyles.body1)
                }
            }
            uiState.decodedEntries.forEach { entry ->
                val r = entry.result
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (r.success) "✓ ${r.messageText}" else "✗ ${r.messageText}",
                            style = MiuixTheme.textStyles.body1,
                            color = if (r.success) MiuixTheme.colorScheme.onSurface
                            else MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        if (r.success) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "SNR: %.1f dB · DT: %+.2f s · 频率: %.0f Hz · 同步分: %.1f".format(
                                    r.snr, r.dt, r.freqHz, r.syncScore
                                ),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                            Text(
                                "CRC: ${if (r.crcValid) "有效" else "无效"} · RAW: 0x${r.raw77Hex}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
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
            enabled = !uiState.isEncoding && messageInput.isNotBlank() && config.callsign.isNotBlank()
        ) {
            Text(
                when {
                    uiState.isEncoding -> "编码中…"
                    uiState.isPlaying -> "停止播放"
                    else -> "编码并播放"
                }
            )
        }

        uiState.lastError?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(
                "错误: $error",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error
            )
        }

        // 编码结果
        uiState.generatedPcm?.let { pcm ->
            Spacer(Modifier.height(16.dp))
            SectionTitle("已编码")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        uiState.lastMessageText,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${pcm.size} samples @ ${com.example.hamkit.data.ft8.Ft8Encoder.SAMPLE_RATE} Hz (${pcm.size / com.example.hamkit.data.ft8.Ft8Encoder.SAMPLE_RATE}s)",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    if (uiState.isPlaying) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在播放…",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary
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
        }

        Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.body1,
        color = colorScheme.onSurfaceSecondary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
