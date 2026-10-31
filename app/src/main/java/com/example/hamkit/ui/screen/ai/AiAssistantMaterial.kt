package com.example.hamkit.ui.screen.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.example.hamkit.data.ai.AiAttachmentKind
import java.util.Locale
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.hamkit.ui.theme.SafeColors
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.ui.text.style.TextOverflow
import java.util.Calendar

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
    var pendingDelete by remember { mutableStateOf<AiSessionSummary?>(null) }
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
                    IconButton(onClick = { viewModel.refreshSessions(); showHistory = true }) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = stringResource(R.string.ai_history_desc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = viewModel::newSession) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.ai_new_chat_desc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { navigator.push(Route.AiSettings) }) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.ai_settings_desc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
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
            if (state.messages.lastOrNull()?.error == null) {
                state.error?.let { message ->
                    ErrorCardMaterial(message = message, onDismiss = viewModel::consumeError)
                }
            }

            if (state.messages.isEmpty()) {
                EmptyStateMaterial(onPick = { prompt -> viewModel.onInputChange(prompt) })
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageItemMaterial(message)
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
                AiHistorySheetMaterial(
                    sessions = state.sessions,
                    currentSessionId = state.sessionId,
                    onNewChat = { viewModel.newSession(); showHistory = false },
                    onDismiss = { showHistory = false },
                    onLoad = { id -> viewModel.loadSession(id); showHistory = false },
                    onDelete = { id -> pendingDelete = state.sessions.firstOrNull { it.id == id } },
                    onExport = { id ->
                        scope.launch {
                            val markdown = viewModel.exportMarkdown(id) ?: return@launch
                            pendingExport = markdown
                            exportPicker.launch("hamkit-ai.md")
                        }
                    },
                )
            }
            pendingDelete?.let { session ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text(stringResource(R.string.ai_history_delete_title)) },
                    text = { Text(stringResource(R.string.ai_history_delete_desc)) },
                    confirmButton = {
                        Button(onClick = {
                            pendingDelete = null
                            viewModel.deleteSession(session.id)
                        }) { Text(stringResource(R.string.ai_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) {
                            Text(stringResource(R.string.ai_cancel))
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("HamKit AI", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.ai_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        samples.forEach { sample ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onPick(sample) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sample,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
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
                shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    message.attachments.forEach { attachment ->
                        Row(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                attachment.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (message.isStreaming) stringResource(R.string.ai_generating) else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message.elapsedMs?.let { elapsed ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        String.format(Locale.US, "%.1fs", elapsed / 1000.0),
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
                Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(
                        message.text.ifBlank {
                            if (message.isStreaming) "" else stringResource(R.string.ai_no_text)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.text.isBlank() && !message.isStreaming) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (message.isStreaming) {
                        Text("▍", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            message.error?.let { error ->
                Spacer(Modifier.height(6.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (message.tools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // 与 Miuix 版一致：工具调用折叠成一行，运行中/失败时展开
                val anyActive = message.tools.any { it.state != AiToolUiState.SUCCESS }
                var toolsExpanded by remember(message.id) { mutableStateOf(anyActive) }
                LaunchedEffect(anyActive) { toolsExpanded = anyActive }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { toolsExpanded = !toolsExpanded }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (toolsExpanded) R.string.ai_tools_expanded else R.string.ai_tools_collapsed,
                            message.tools.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (toolsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (toolsExpanded) {
                    Spacer(Modifier.height(8.dp))
                    message.tools.forEach { tool ->
                        ToolCardMaterial(tool)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            message.cards.forEach { card ->
                when (card) {
                    is AiCard.Satellite -> SatelliteCardMaterial(card.data)
                    is AiCard.Zone -> ZoneCardMaterial(card.data)
                    is AiCard.Sstv -> SstvCardMaterial(card.mode, card.imagePath)
                    is AiCard.Reminder -> InfoCardMaterial(
                        title = stringResource(R.string.ai_reminder_created),
                        detail = card.nextAos?.let { "${card.satellite} · ${formatAiTimeMaterial(it)}" }
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
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(24.dp),
            )
            .border(
                width = 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
            Spacer(Modifier.height(6.dp))
        }

        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            decorationBox = { innerTextField ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    if (input.isEmpty()) {
                        Text(
                            stringResource(R.string.ai_input_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPickFiles) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.ai_attach_desc),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (attachments.isEmpty()) {
                Text(
                    stringResource(R.string.ai_attach_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
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
                Icon(
                    if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(
                        if (streaming) R.string.ai_stop_desc else R.string.ai_send_desc,
                    ),
                    tint = if (streaming || !canSend) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier.size(20.dp),
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
        val icon = when (attachment.kind) {
            AiAttachmentKind.AUDIO -> Icons.Rounded.AudioFile
            AiAttachmentKind.IMAGE -> Icons.Rounded.Image
            else -> Icons.Rounded.Description
        }
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            attachment.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 120.dp),
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
                .padding(2.dp)
                .semantics { contentDescription = "移除附件" },
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
    var expanded by remember(tool.callId) { mutableStateOf(tool.state != AiToolUiState.SUCCESS) }
    LaunchedEffect(tool.state) {
        when (tool.state) {
            AiToolUiState.SUCCESS -> expanded = false
            AiToolUiState.ERROR -> expanded = true
            AiToolUiState.RUNNING -> Unit
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { expanded = !expanded }
                .padding(12.dp),
        ) {
            val statusColor = when (tool.state) {
                AiToolUiState.RUNNING -> MaterialTheme.colorScheme.primary
                AiToolUiState.SUCCESS -> SafeColors.successIcon
                AiToolUiState.ERROR -> MaterialTheme.colorScheme.error
            }
            val label = when (tool.state) {
                AiToolUiState.RUNNING -> stringResource(R.string.ai_tool_running)
                AiToolUiState.SUCCESS -> stringResource(R.string.ai_tool_done)
                AiToolUiState.ERROR -> stringResource(R.string.ai_tool_failed)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        toolIconMaterial(tool.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tool.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(statusColor, CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                    if (!expanded) {
                        tool.summary?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (expanded) {
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
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (tool.state == AiToolUiState.RUNNING && progress == null) {
                    Text(
                        stringResource(R.string.ai_executing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                tool.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                tool.argsPreview.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                tool.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun toolIconMaterial(name: String): ImageVector = when {
    name == "get_zone_info" -> Icons.Rounded.Place
    name == "search_satellites" || name.startsWith("get_satellite") ||
        name == "get_amsat_status" -> Icons.Rounded.SatelliteAlt
    name == "start_sstv_receive" -> Icons.Rounded.Image
    name == "open_screen" -> Icons.Rounded.ArrowForward
    name == "add_pass_reminder" -> Icons.Rounded.Notifications
    else -> Icons.Rounded.Build
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
private fun MetricMaterial(label: String, value: String, compact: Boolean = false) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
        )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SatelliteRadar(
                        azimuthDeg = live.azimuthDeg,
                        elevationDeg = live.elevationDeg,
                        primary = MaterialTheme.colorScheme.primary,
                        outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        diameter = 112.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricMaterial("方位 AZ", "%.1f°".format(live.azimuthDeg), compact = true)
                            MetricMaterial("仰角 EL", "%.1f°".format(live.elevationDeg), compact = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricMaterial("距离", "%.0f km".format(live.rangeKm), compact = true)
                            MetricMaterial("高度", "%.0f km".format(live.altitudeKm), compact = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricMaterial(
                                "星下点",
                                "%.1f°,%.1f°".format(live.subpointLatDeg, live.subpointLonDeg),
                                compact = true,
                            )
                            MetricMaterial("相位", "%.0f°".format(live.phaseDeg), compact = true)
                        }
                    }
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
private fun AiHistorySheetMaterial(
    sessions: List<AiSessionSummary>,
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNewChat) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.ai_new_chat_desc),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ai_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.ai_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ai_history_empty_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val current = session.id == currentSessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (current) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                                    RoundedCornerShape(12.dp),
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (current) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable { onLoad(session.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatSessionTimeMaterial(session.updatedAt)} · " +
                                        stringResource(
                                            R.string.ai_history_messages,
                                            session.messageCount,
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            IconButton(onClick = { onExport(session.id) }) {
                                Icon(
                                    Icons.Rounded.FileDownload,
                                    contentDescription = stringResource(R.string.ai_export),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = { onDelete(session.id) }) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.ai_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

/** Material 版历史相对时间（与 Miuix 版保持一致）。 */
private fun formatSessionTimeMaterial(epochMs: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = epochMs }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> String.format(Locale.US, "%02d:%02d", then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
        sameYear && now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "昨天"
        sameYear -> String.format(Locale.US, "%02d-%02d", then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH))
        else -> String.format(Locale.US, "%04d-%02d-%02d", then.get(Calendar.YEAR), then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH))
    }
}
