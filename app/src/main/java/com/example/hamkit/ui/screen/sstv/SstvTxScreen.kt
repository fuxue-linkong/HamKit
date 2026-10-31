package com.example.hamkit.ui.screen.sstv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvEncoder
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
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
 * **版式（2026-10 整理）**：原来的四张卡（合规提示 / 图像 / 参数 / 控制）按「先看结果、
 * 再设参数、最后动手」重排为三张：图像与控制合并（选完图紧接着就是发射按钮）、
 * 参数独立、合规提示是静态长文本，挪到最下方并用错误色卡片强调。
 *
 * @param onNavigate 路由跳转（用于跳到图库选图）
 */
@Composable
fun SstvTxScreen(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SstvTxScreenMiuix(onNavigate, onNavigateBack)
        UiMode.Material -> SstvTxScreenMaterial(onNavigate, onNavigateBack)
    }
}

/** Miuix（MIUI）风格实现。 */
@Composable
fun SstvTxScreenMiuix(
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()

    // 相册选图：首选系统内置图片选择器 PickVisualMedia。
    //
    // 为什么不再用 `OpenDocument(arrayOf("image/*"))`：它把选图交给第三方 provider（网盘、
    // 聊天软件、某些 ROM 的自研相册），这些 provider 常只允许打开一次流或不给可持久化的
    // 读权限，SSTV 旧实现要在同一 URI 上开两次流，结果就是「选完图没反应」。内置选择器
    // 由系统进程授予一次性读权限，且 ViewModel 侧会立刻把内容复制到缓存文件。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.setTxImage(it) } }

    // 兜底：传统文件选择器（可到非媒体类目录取图；老 ROM / 无内置选择器时用）
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
            Spacer(Modifier.height(12.dp))

            // ── 卡 1：图像与发射控制 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SstvPreview(
                        aspect = mode.linePixels.toFloat() / mode.imageLines,
                        bitmap = uiState.txPreview,
                        placeholder = "尚未选择图片",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.txStatus,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
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
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("相册选图")
                        }
                        Button(
                            onClick = { onNavigate(Route.SstvGallery(pickForTx = true)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("图库选图")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 相册里没有的原图（下载目录、U 盘、其他 App 私有目录）走文件选择器
                    Button(
                        onClick = { pickImageLegacy.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text("从文件选择")
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = colorScheme.dividerLine)
                    Spacer(Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = uiState.txProgress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "进度 ${(uiState.txProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
                    )

                    // 主操作：开始/停止独占一行，导出 WAV 次之
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { if (uiState.txPlaying) viewModel.stopTx() else viewModel.startTx() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (uiState.txPlaying) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.buttonColorsPrimary()
                        },
                    ) {
                        Text(if (uiState.txPlaying) "停止发射" else "开始发射")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
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

            Spacer(Modifier.height(12.dp))

            // ── 卡 2：发射参数 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SstvCardTitle("发射参数")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "模式",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SstvMode.DECODABLE_MODES.forEach { candidate ->
                            SstvChip(candidate.displayName, uiState.txModeName == candidate.name) {
                                viewModel.setTxMode(candidate)
                            }
                        }
                    }
                }

                HorizontalDivider(color = colorScheme.dividerLine)

                SstvSwitchRow(
                    title = "包含 VIS 头",
                    summary = "关闭后对方需手动锁定模式；只有自测时才建议关闭",
                    checked = uiState.txIncludeVis,
                    onCheckedChange = { viewModel.setTxIncludeVis(it) },
                )
                SstvSwitchRow(
                    title = "叠加呼号",
                    summary = "在右下角写上前缀/呼号，便于对方确认来源",
                    checked = uiState.txOverlayCallsign,
                    onCheckedChange = { viewModel.setTxOverlayCallsign(it) },
                )
                if (uiState.txOverlayCallsign) {
                    TextField(
                        value = uiState.callsign,
                        onValueChange = { viewModel.setCallsign(it) },
                        label = "呼号（如 BG5XXX）",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 卡 3：合规与操作要点（静态文字，放在最下方不挡主流程）──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(color = colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "发射前请确认",
                        style = MiuixTheme.textStyles.title4,
                        color = colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• SSTV 发射会让电台实际发信，须持有相应操作资格并遵守当地无线电管理规定\n" +
                            "• 请确认所用频率无人占用，功率与带宽符合执照许可\n" +
                            "• 未持照时请只使用「导出 WAV」，用于本地自测或演示\n" +
                            "• 直接播放：手机扬声器对准电台话筒，音量以不失真为准\n" +
                            "• 导出 WAV：用音频线接电台 LINE IN / 数据口，能显著改善对方收到的画质\n" +
                            "• 最长模式单帧接近 5 分钟，发射期间请保持屏幕唤醒",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onErrorContainer,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
