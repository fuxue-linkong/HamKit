package com.example.hamkit.ui.screen.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.hamkit.R
import com.example.hamkit.data.ai.AiAttachment
import com.example.hamkit.data.ai.AiAttachmentStatus
import com.example.hamkit.data.ai.AiRole
import com.example.hamkit.data.ai.AiSessionSummary
import com.example.hamkit.data.ai.tool.AiCard
import com.example.hamkit.data.ai.tool.SatelliteCardData
import com.example.hamkit.data.ai.tool.ZoneCardData
import com.example.hamkit.ui.component.markdown.MarkdownPalette
import com.example.hamkit.ui.component.markdown.MarkdownText
import com.example.hamkit.ui.screen.ai.component.SatelliteRadar
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.AiAppFacadeImpl
import com.example.hamkit.ui.viewmodel.AiConfirmRequest
import com.example.hamkit.ui.viewmodel.AiAssistantViewModel
import com.example.hamkit.ui.viewmodel.AiChatMessageUi
import com.example.hamkit.ui.viewmodel.AiChatUiState
import com.example.hamkit.ui.viewmodel.AiToolUi
import com.example.hamkit.ui.viewmodel.AiToolUiState
import com.example.hamkit.ui.viewmodel.SstvViewModel

/** Material 风格实现，与 Miuix 版共用 [AiAssistantViewModel]。 */
@Composable
fun AiAssistantScreenMaterial(bottomInnerPadding: Dp = 0.dp) {
    val viewModel = appViewModel<AiAssistantViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val facade = remember(mainViewModel, navigator) { AiAppFacadeImpl(mainViewModel, navigator) }
    val sstvViewModel = appViewModel<SstvViewModel>()
    DisposableEffect(facade, sstvViewModel) {
        viewModel.attachFacade(facade)
        viewModel.attachSstv(sstvViewModel)
        onDispose {
            viewModel.detachFacade()
            viewModel.detachSstv()
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addAttachments(uris) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showHistory by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val content = pendingExport
        if (uri != null && content != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
            }
        }
        pendingExport = null
    }
    val listState = rememberLazyListState()
    // 与 Miuix 版一致：让出底部导航高度，键盘弹出时取 ime 与导航栏的较大值
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomInset = maxOf(bottomInnerPadding, imeBottom)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = { viewModel.refreshSessions(); showHistory = true }) {
                        Text(stringResource(R.string.ai_history))
                    }
                    TextButton(onClick = viewModel::newSession) { Text(stringResource(R.string.ai_new_chat)) }
                    TextButton(onClick = { navigator.push(Route.AiSettings) }) { Text(stringResource(R.string.ai_settings)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.initialized) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            if (!state.hasKey) {
                KeyGuideCardMaterial(onOpenSettings = { navigator.push(Route.AiSettings) })
            }
            state.error?.let { message ->
                ErrorCardMaterial(message = message, onDismiss = viewModel::consumeError)
            }

            if (state.messages.isEmpty()) {
                EmptyStateMaterial(onPick = { prompt -> viewModel.onInputChange(prompt) })
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        Box(Modifier.animateItem()) {
                            MessageItemMaterial(message)
                        }
                    }
                }
            }

            state.pendingConfirm?.let { request ->
                AiConfirmDialogMaterial(
                    request = request,
                    onApprove = { viewModel.resolveConfirm(true) },
                    onDeny = { viewModel.resolveConfirm(false) },
                )
            }
            if (showHistory) {
                AiHistoryDialogMaterial(
                    sessions = state.sessions,
                    onDismiss = { showHistory = false },
                    onLoad = { id -> viewModel.loadSession(id); showHistory = false },
                    onDelete = viewModel::deleteSession,
                    onExport = { id ->
                        scope.launch {
                            val markdown = viewModel.exportMarkdown(id) ?: return@launch
                            pendingExport = markdown
                            exportPicker.launch("hamkit-ai.md")
                        }
                    },
                )
            }
            ComposerMaterial(
                modifier = Modifier.padding(bottom = bottomInset),
                input = state.input,
                streaming = state.isStreaming,
                attachments = state.attachments,
                onInputChange = viewModel::onInputChange,
                onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                onRemoveAttachment = viewModel::removeAttachment,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        }
    }
}

@Composable
private fun KeyGuideCardMaterial(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.ai_need_key_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "AI 助手使用你自己的 API Key。默认预设是 DeepSeek，也可以切换其它服务商或内网地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.ai_go_config)) }
        }
    }
}

@Composable
private fun ErrorCardMaterial(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_close)) }
        }
    }
}

@Composable
private fun EmptyStateMaterial(onPick: (String) -> Unit) {
    val samples = listOf(
        stringResource(R.string.ai_sample_1),
        stringResource(R.string.ai_sample_2),
        stringResource(R.string.ai_sample_3),
        stringResource(R.string.ai_sample_4),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("HamKit AI", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ai_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            samples.forEach { sample ->
                TextButton(
                    onClick = { onPick(sample) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        sample,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageItemMaterial(message: AiChatMessageUi) {
    if (message.role == AiRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    message.attachments.forEach { attachment ->
                        Text(
                            "📎 ${attachment.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (message.isStreaming) "正在生成…" else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message.elapsedMs?.let { elapsed ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${elapsed / 1000.0} s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (!message.isStreaming && message.text.isNotBlank()) {
                MarkdownText(
                    markdown = message.text,
                    colors = MarkdownPalette(
                        text = MaterialTheme.colorScheme.onSurface,
                        secondary = MaterialTheme.colorScheme.onSurfaceVariant,
                        primary = MaterialTheme.colorScheme.primary,
                        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        divider = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            } else {
                Text(
                    message.text.ifBlank { if (message.isStreaming) "…" else stringResource(R.string.ai_no_text) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.text.isBlank() && !message.isStreaming) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            message.error?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (message.tools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                message.tools.forEach { tool ->
                    ToolCardMaterial(tool)
                    Spacer(Modifier.height(8.dp))
                }
            }
            message.cards.forEach { card ->
                when (card) {
                    is AiCard.Satellite -> SatelliteCardMaterial(card.data)
                    is AiCard.Zone -> ZoneCardMaterial(card.data)
                    is AiCard.Sstv -> SstvCardMaterial(card.mode, card.imagePath)
                    is AiCard.Reminder -> InfoCardMaterial(
                        title = "过境提醒已创建",
                        detail = card.nextAos?.let { "${card.satellite} · 下次 AOS ${formatAiTimeMaterial(it)}" }
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
private fun ComposerMaterial(
    modifier: Modifier = Modifier,
    input: String,
    streaming: Boolean,
    attachments: List<AiAttachment>,
    onInputChange: (String) -> Unit,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = (input.isNotBlank() || attachments.any { it.status == AiAttachmentStatus.READY }) &&
        attachments.none { it.status == AiAttachmentStatus.PARSING }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { attachment ->
                    AttachmentChipMaterial(attachment, onRemoveAttachment)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.ai_input_hint)) },
            maxLines = 6,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPickFiles) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.ai_attach_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (streaming || !canSend) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        CircleShape,
                    )
                    .clickable { if (streaming) onStop() else if (canSend) onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (streaming) "■" else "↑",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (streaming || !canSend) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentChipMaterial(attachment: AiAttachment, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            attachment.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        if (attachment.status == AiAttachmentStatus.PARSING) {
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.ai_att_parsing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "×",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clickable { onRemove(attachment.id) }
                .padding(2.dp),
        )
    }
}


private val AI_TIME_FMT_MATERIAL: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatAiTimeMaterial(instant: Instant): String = AI_TIME_FMT_MATERIAL.format(instant)

@Composable
private fun ToolCardMaterial(tool: AiToolUi) {
    val animatedProgress by animateFloatAsState(
        targetValue = (tool.progress ?: 0f).coerceIn(0f, 1f),
        label = "aiToolProgress",
    )
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tool.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val (label, color) = when (tool.state) {
                    AiToolUiState.RUNNING -> stringResource(R.string.ai_tool_running) to MaterialTheme.colorScheme.primary
                    AiToolUiState.SUCCESS -> stringResource(R.string.ai_tool_done) to MaterialTheme.colorScheme.primary
                    AiToolUiState.ERROR -> stringResource(R.string.ai_tool_failed) to MaterialTheme.colorScheme.error
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            }
            val progress = tool.progress
            if (tool.state == AiToolUiState.RUNNING && progress != null) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            tool.stage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tool.state == AiToolUiState.RUNNING && progress == null) {
                Text(
                    "执行中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tool.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tool.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ZoneCardMaterial(data: ZoneCardData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("双区定位", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricMaterial("CQ 分区", data.cqZone?.toString() ?: "-")
                MetricMaterial("ITU 分区", data.ituZone?.toString() ?: "-")
                MetricMaterial("网格", data.maidenhead)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "%.4f, %.4f".format(data.latitude, data.longitude) +
                    if (data.address.isNotBlank()) " · ${data.address}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCardMaterial(title: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricMaterial(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SatelliteCardMaterial(data: SatelliteCardData) {
    val navigator = LocalNavigator.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    data.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "#${data.catalogNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    data.amsatStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            data.live?.let { live ->
                Spacer(Modifier.height(10.dp))
                SatelliteRadar(
                    azimuthDeg = live.azimuthDeg,
                    elevationDeg = live.elevationDeg,
                    primary = MaterialTheme.colorScheme.primary,
                    outline = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MetricMaterial("方位 AZ", "%.1f°".format(live.azimuthDeg))
                    MetricMaterial("仰角 EL", "%.1f°".format(live.elevationDeg))
                    MetricMaterial("距离", "%.0f km".format(live.rangeKm))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MetricMaterial("高度", "%.0f km".format(live.altitudeKm))
                    MetricMaterial(
                        "星下点",
                        "%.1f°, %.1f°".format(live.subpointLatDeg, live.subpointLonDeg),
                    )
                    MetricMaterial("相位", "%.0f°".format(live.phaseDeg))
                }
                Text(
                    if (live.isEclipsed) "蚀中（地影）" else "日照中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (data.passes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "未来过境",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                data.passes.take(3).forEach { pass ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(
                            "${formatAiTimeMaterial(pass.aos)} – ${formatAiTimeMaterial(pass.los)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "最大 ${pass.maxElevation.toInt()}°",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (data.radios.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "转发器 ${data.radios.count { it.status == "active" }}/${data.radios.size} 活跃",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                data.radios.take(4).forEach { radio ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(
                            buildString {
                                radio.downlinkHz?.let { append("%.3f".format(it / 1_000_000.0)) }
                                radio.uplinkHz?.let { append(" / %.3f".format(it / 1_000_000.0)) }
                                append(" MHz")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            radio.mode.ifBlank { "-" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { navigator.push(Route.SatelliteDetail(data.catalogNumber)) }) {
                    Text("打开详情")
                }
                TextButton(onClick = { navigator.push(Route.SatelliteRadar(data.catalogNumber)) }) {
                    Text("雷达图")
                }
            }
        }
    }
}

@Composable
private fun AiConfirmDialogMaterial(
    request: AiConfirmRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = {
            Button(onClick = onApprove) { Text(stringResource(R.string.ai_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text(stringResource(R.string.ai_cancel)) }
        },
    )
}

@Composable
private fun AiHistoryDialogMaterial(
    sessions: List<AiSessionSummary>,
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_history_title)) },
        text = {
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.ai_history_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLoad(session.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(session.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    "${formatAiTimeMaterial(Instant.ofEpochMilli(session.updatedAt))} · " +
                                        "${session.messageCount} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onExport(session.id) }) { Text(stringResource(R.string.ai_export)) }
                            TextButton(onClick = { onDelete(session.id) }) { Text(stringResource(R.string.ai_delete)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_close)) }
        },
    )
}

@Composable
private fun SstvCardMaterial(mode: String?, imagePath: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("SSTV 图像", style = MaterialTheme.typography.titleSmall)
            Text(
                mode?.let { "模式：$it" } ?: "模式：VIS 自动识别",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (imagePath != null && File(imagePath).isFile) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = "SSTV 解码图像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
        }
    }
}
