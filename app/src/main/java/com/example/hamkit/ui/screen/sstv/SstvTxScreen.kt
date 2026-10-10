package com.example.hamkit.ui.screen.sstv

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvEncoder
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
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

/**
 * SSTV 发射：选图 → 编码成 SSTV 音频 → 对着电台话筒播出（或导出 WAV）。
 *
 * **一期欠账**：编码器 [SstvEncoder] 一期就写完了并有自环单测，但**全仓库没有任何
 * 调用点** —— 发射功能事实上不存在。本页把它接到 UI 与播放链路上。
 *
 * **两条发射路径**（对应二期风险项 R1「声学链路失真不可控」）：
 * 1. 直接播放：手机扬声器对着电台话筒。方便，但电台 AGC、扬声器频响、环境噪声
 *    都会劣化对方收到的画质；
 * 2. 导出 WAV：用音频线从耳机口接到电台 LINE IN / 数据口，绕开声学环节。
 *    对画质有要求时应走这条。
 *
 * **合规**：SSTV 发射意味着电台要上电发射，必须持有相应操作资格并遵守《无线电管理
 * 条例》。因此本页默认不自动开始，且显著位置给出提示（与 FT8CN 的免责声明同一思路）。
 *
 * @param onNavigate 路由跳转（用于跳到图库选图）
 */
@Composable
fun SstvTxScreen(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

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
                title = "SSTV 发射",
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
                        "图库",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.SstvGallery(pickForTx = true)) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
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

            // ── 合规提示（放在最上方，进入页面第一眼就能看到）──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "发射前请确认",
                        style = MiuixTheme.textStyles.title4,
                        color = Color(0xFFD9534F),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• SSTV 发射会让电台实际发信，须持有相应操作资格并遵守当地无线电管理规定\n" +
                            "• 请确认所用频率无人占用，功率与带宽符合执照许可\n" +
                            "• 未持照时请只使用「导出 WAV」，用于本地自测或演示",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 图像预览 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("待发射图像", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
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
                                "尚未选择图片",
                                style = MiuixTheme.textStyles.body1,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        uiState.txStatus,
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
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
                        Button(
                            onClick = { onNavigate(Route.SstvGallery(pickForTx = true)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("从图库选图")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 发射参数 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("发射参数", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "单帧时长 ${"%.0f".format(scriptSeconds)} 秒（含 0.91 秒 VIS 头）" +
                            " · 分辨率 ${mode.linePixels}×${mode.imageLines}",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SstvMode.DECODABLE_MODES.forEach { candidate ->
                            Button(
                                onClick = { viewModel.setTxMode(candidate) },
                                colors = if (uiState.txModeName == candidate.name) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors(
                                        color = Color.Transparent,
                                        contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                    )
                                },
                            ) {
                                Text(candidate.displayName)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "包含 VIS 头",
                        summary = "关闭后对方需手动锁定模式；只有自测时才建议关闭",
                        checked = uiState.txIncludeVis,
                        onCheckedChange = { viewModel.setTxIncludeVis(it) },
                    )
                    SwitchRow(
                        title = "叠加呼号",
                        summary = "在右下角写上前缀/呼号，便于对方确认来源",
                        checked = uiState.txOverlayCallsign,
                        onCheckedChange = { viewModel.setTxOverlayCallsign(it) },
                    )
                    if (uiState.txOverlayCallsign) {
                        Spacer(Modifier.height(6.dp))
                        TextField(
                            value = uiState.callsign,
                            onValueChange = { viewModel.setCallsign(it) },
                            label = "呼号（如 BG5XXX）",
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 发射控制 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("发射", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))

                    // 进度条（与接收页同样的两段 Box 实现）
                    val progress = uiState.txProgress.coerceIn(0f, 1f)
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
                        "进度 ${(progress * 100).toInt()}%",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { if (uiState.txPlaying) viewModel.stopTx() else viewModel.startTx() },
                            modifier = Modifier.weight(1f),
                            colors = if (uiState.txPlaying) {
                                ButtonDefaults.buttonColors(
                                    color = Color.Transparent,
                                    contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            } else {
                                ButtonDefaults.buttonColorsPrimary()
                            },
                        ) {
                            Text(if (uiState.txPlaying) "停止发射" else "开始发射")
                        }
                        Button(
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• 直接播放：手机扬声器对准电台话筒，音量以不失真为准\n" +
                            "• 导出 WAV：用音频线接电台 LINE IN / 数据口，能显著改善对方收到的画质\n" +
                            "• 最长模式单帧接近 5 分钟，发射期间请保持屏幕唤醒",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
