package com.example.hamkit.ui.screen.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.ai.AiRole
import com.example.hamkit.data.ai.tool.AiCard
import com.example.hamkit.data.ai.tool.SatelliteCardData
import com.example.hamkit.data.ai.tool.ZoneCardData
import com.example.hamkit.ui.component.markdown.MarkdownPalette
import com.example.hamkit.ui.component.markdown.MarkdownText
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.AiAppFacadeImpl
import com.example.hamkit.ui.viewmodel.AiAssistantViewModel
import com.example.hamkit.ui.viewmodel.AiChatMessageUi
import com.example.hamkit.ui.viewmodel.AiChatUiState
import com.example.hamkit.ui.viewmodel.AiToolUi
import com.example.hamkit.ui.viewmodel.AiToolUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * AI 助手主页（底部导航第 2 项）。
 *
 * 只是**风格分发入口**：Miuix / Material 共用同一个 [AiAssistantViewModel]（application 级），
 * 切页、切换 UI 风格都不会中断正在进行的流式请求。
 */
@Composable
fun AiAssistantScreen(bottomInnerPadding: Dp = 0.dp) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> AiAssistantScreenMiuix(bottomInnerPadding)
        UiMode.Material -> AiAssistantScreenMaterial(bottomInnerPadding)
    }
}

@Composable
fun AiAssistantScreenMiuix(bottomInnerPadding: Dp = 0.dp) {
    val viewModel = appViewModel<AiAssistantViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val facade = remember(mainViewModel, navigator) { AiAppFacadeImpl(mainViewModel, navigator) }
    DisposableEffect(facade) {
        viewModel.attachFacade(facade)
        onDispose { viewModel.detachFacade() }
    }
    val listState = rememberLazyListState()
    // 底部导航是 pager 外层 Scaffold 的 bottomBar，AI 页必须自己让出这段高度；
    // 键盘弹出时取两者较大值，避免输入框被导航栏或 IME 遮挡。
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomInset = maxOf(bottomInnerPadding, imeBottom)

    // 新消息入列时滚到底；流式增量不滚，避免用户上翻时被反复拉回
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "AI 助手",
                actions = {
                    Text(
                        "新对话",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { viewModel.newSession() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Text(
                        "设置",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { navigator.push(Route.AiSettings) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                },
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.initialized) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator()
                }
                return@Scaffold
            }

            if (!state.hasKey) {
                KeyGuideCardMiuix(onOpenSettings = { navigator.push(Route.AiSettings) })
            }

            state.error?.let { message ->
                ErrorCardMiuix(message = message, onDismiss = viewModel::consumeError)
            }

            if (state.messages.isEmpty()) {
                EmptyStateMiuix(onPick = { prompt -> viewModel.onInputChange(prompt) })
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageItemMiuix(message)
                    }
                }
            }

            if (state.messages.isEmpty()) Spacer(Modifier.weight(1f))

            ComposerMiuix(
                modifier = Modifier.padding(bottom = bottomInset),
                input = state.input,
                streaming = state.isStreaming,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        }
    }
}

@Composable
private fun KeyGuideCardMiuix(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("还没有配置 API Key", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "AI 助手使用你自己的 API Key。默认预设是 DeepSeek，也可以切换其它服务商或内网地址。",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("去配置")
            }
        }
    }
}

@Composable
private fun ErrorCardMiuix(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onDismiss),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = colorScheme.error,
            )
            Text("关闭", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun EmptyStateMiuix(onPick: (String) -> Unit) {
    val samples = listOf(
        "业余无线电 CQ 分区怎么划分？",
        "FT8 的 73 和 RR73 是什么意思？",
        "ISS 的 SSTV 活动一般用什么模式？",
        "如何选择 HF 天线的长度？",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("HamKit AI", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "可以问业余无线电知识，也可以让我调用 App 功能：查双区定位、算卫星过境、开 SSTV 接收。",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            samples.forEach { sample ->
                Text(
                    sample,
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(sample) }
                        .padding(vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageItemMiuix(message: AiChatMessageUi) {
    if (message.role == AiRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .background(colorScheme.primary, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(message.text, fontSize = 14.sp, color = colorScheme.onPrimary)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (message.isStreaming) "正在生成…" else "AI",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                message.elapsedMs?.let { elapsed ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${elapsed / 1000.0} s",
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (!message.isStreaming && message.text.isNotBlank()) {
                // 生成完成后用 Compose 原生 Markdown 渲染（流式阶段保持纯文本，避免频繁重排）
                MarkdownText(
                    markdown = message.text,
                    colors = MarkdownPalette(
                        text = colorScheme.onSurface,
                        secondary = colorScheme.onSurfaceVariantSummary,
                        primary = colorScheme.primary,
                        codeBackground = colorScheme.surfaceVariant,
                        divider = colorScheme.onSurface.copy(alpha = 0.12f),
                    ),
                )
            } else {
                Text(
                    message.text.ifBlank { if (message.isStreaming) "…" else "（无文本输出）" },
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = if (message.text.isBlank() && !message.isStreaming) {
                        colorScheme.onSurfaceVariantSummary
                    } else {
                        colorScheme.onSurface
                    },
                )
            }
            message.error?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(error, fontSize = 11.sp, color = colorScheme.error)
            }
            if (message.tools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                message.tools.forEach { tool ->
                    ToolCardMiuix(tool)
                    Spacer(Modifier.height(8.dp))
                }
            }
            message.cards.forEach { card ->
                when (card) {
                    is AiCard.Satellite -> SatelliteCardMiuix(card.data)
                    is AiCard.Zone -> ZoneCardMiuix(card.data)
                    is AiCard.Sstv -> InfoCardMiuix(
                        title = "SSTV 接收已开始",
                        detail = card.mode?.let { "模式：$it" } ?: "模式：VIS 自动识别",
                    )
                    is AiCard.Reminder -> InfoCardMiuix(
                        title = "过境提醒已创建",
                        detail = card.nextAos?.let { "${card.satellite} · 下次 AOS ${formatAiTime(it)}" }
                            ?: card.satellite,
                    )
                    is AiCard.Error -> Unit
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ComposerMiuix(
    modifier: Modifier = Modifier,
    input: String,
    streaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )
            if (input.isEmpty()) {
                Text(
                    "问问业余无线电…",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (streaming) {
            Button(onClick = onStop) {
                Text("停止")
            }
        } else {
            Button(
                onClick = onSend,
                enabled = input.isNotBlank(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("发送")
            }
        }
    }
}

private val AI_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatAiTime(instant: Instant): String = AI_TIME_FMT.format(instant)

@Composable
private fun ToolCardMiuix(tool: AiToolUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tool.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val (label, color) = when (tool.state) {
                    AiToolUiState.RUNNING -> "进行中" to colorScheme.primary
                    AiToolUiState.SUCCESS -> "完成" to colorScheme.primary
                    AiToolUiState.ERROR -> "失败" to colorScheme.error
                }
                Text(label, fontSize = 10.sp, color = color)
            }
            val progress = tool.progress
            if (tool.state == AiToolUiState.RUNNING && progress != null) {
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            tool.stage?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (tool.state == AiToolUiState.RUNNING && progress == null) {
                Text(
                    "执行中…",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            tool.summary?.let {
                Text(it, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
            }
            tool.error?.let {
                Text(it, fontSize = 11.sp, color = colorScheme.error)
            }
        }
    }
}

@Composable
private fun ZoneCardMiuix(data: ZoneCardData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("双区定位", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricMiuix("CQ 分区", data.cqZone?.toString() ?: "-")
                MetricMiuix("ITU 分区", data.ituZone?.toString() ?: "-")
                MetricMiuix("网格", data.maidenhead)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "%.4f, %.4f".format(data.latitude, data.longitude) +
                    if (data.address.isNotBlank()) " · ${data.address}" else "",
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun InfoCardMiuix(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun MetricMiuix(label: String, value: String) {
    Column {
        Text(label, fontSize = 9.5.sp, color = colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SatelliteCardMiuix(data: SatelliteCardData) {
    val navigator = LocalNavigator.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    data.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "#${data.catalogNumber}",
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            if (data.modes.isNotEmpty() || data.amsatStatus != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    if (data.modes.isNotEmpty()) {
                        Text(
                            data.modes.joinToString(" · "),
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    data.amsatStatus?.let {
                        Text(it, fontSize = 10.sp, color = colorScheme.primary)
                    }
                }
            }

            data.live?.let { live ->
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MetricMiuix("方位 AZ", "%.1f°".format(live.azimuthDeg))
                    MetricMiuix("仰角 EL", "%.1f°".format(live.elevationDeg))
                    MetricMiuix("距离", "%.0f km".format(live.rangeKm))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MetricMiuix("高度", "%.0f km".format(live.altitudeKm))
                    MetricMiuix(
                        "星下点",
                        "%.1f°, %.1f°".format(live.subpointLatDeg, live.subpointLonDeg),
                    )
                    MetricMiuix("相位", "%.0f°".format(live.phaseDeg))
                }
                Text(
                    if (live.isEclipsed) "蚀中（地影）" else "日照中",
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (data.passes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("未来过境", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                data.passes.take(3).forEach { pass ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(
                            "${formatAiTime(pass.aos)} – ${formatAiTime(pass.los)}",
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "最大 ${pass.maxElevation.toInt()}°",
                            fontSize = 11.sp,
                            color = colorScheme.primary,
                        )
                    }
                }
            }

            if (data.radios.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "转发器 ${data.radios.count { it.status == "active" }}/${data.radios.size} 活跃",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                data.radios.take(4).forEach { radio ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(
                            buildString {
                                radio.downlinkHz?.let { append("%.3f".format(it / 1_000_000.0)) }
                                radio.uplinkHz?.let { append(" / %.3f".format(it / 1_000_000.0)) }
                                append(" MHz")
                            },
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            radio.mode.ifBlank { "-" },
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "打开详情",
                    onClick = { navigator.push(Route.SatelliteDetail(data.catalogNumber)) },
                )
                TextButton(
                    text = "雷达图",
                    onClick = { navigator.push(Route.SatelliteRadar(data.catalogNumber)) },
                )
            }
        }
    }
}
