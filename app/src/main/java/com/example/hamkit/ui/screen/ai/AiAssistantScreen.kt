package com.example.hamkit.ui.screen.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.hamkit.R
import com.example.hamkit.data.ai.AiAttachment
import com.example.hamkit.data.ai.AiAttachmentKind
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
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
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
import java.time.ZoneId
import kotlinx.coroutines.launch
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.Instant
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.hamkit.ui.theme.SafeColors

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                title = stringResource(R.string.ai_title),
                actions = {
                    IconButton(onClick = { viewModel.refreshSessions(); showHistory = true }) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = stringResource(R.string.ai_history_desc),
                            tint = colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { navigator.push(Route.AiSettings) }) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.ai_settings_desc),
                            tint = colorScheme.primary,
                        )
                    }
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

            // 最后一条消息已带错误时不再重复显示顶层错误卡
            if (state.messages.lastOrNull()?.error == null) {
                state.error?.let { message ->
                    ErrorCardMiuix(message = message, onDismiss = viewModel::consumeError)
                }
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
                        Box(Modifier.animateItem()) {
                            MessageItemMiuix(message)
                        }
                    }
                }
            }

            if (state.messages.isEmpty()) Spacer(Modifier.weight(1f))

            state.pendingConfirm?.let { request ->
                AiConfirmDialogMiuix(
                    request = request,
                    onApprove = { viewModel.resolveConfirm(true) },
                    onDeny = { viewModel.resolveConfirm(false) },
                )
            }
            if (showHistory) {
                AiHistoryDialogMiuix(
                    sessions = state.sessions,
                    onNewChat = { viewModel.newSession(); showHistory = false },
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
            ComposerMiuix(
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
private fun KeyGuideCardMiuix(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.ai_need_key_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                Text(stringResource(R.string.ai_go_config))
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
            Text(stringResource(R.string.ai_close), fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun EmptyStateMiuix(onPick: (String) -> Unit) {
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
                .background(colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("HamKit AI", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.ai_empty_desc),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        samples.forEach { sample ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(
                        colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onPick(sample) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sample,
                    fontSize = 12.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(14.dp),
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
                    .widthIn(max = 320.dp)
                    .background(
                        colorScheme.primary,
                        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    Text(
                        message.text,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = colorScheme.onPrimary,
                    )
                    message.attachments.forEach { attachment ->
                        Row(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .background(
                                    colorScheme.onPrimary.copy(alpha = 0.15f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Description,
                                contentDescription = null,
                                tint = colorScheme.onPrimary.copy(alpha = 0.85f),
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                attachment.name,
                                fontSize = 10.sp,
                                color = colorScheme.onPrimary.copy(alpha = 0.9f),
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
                        .background(colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (message.isStreaming) stringResource(R.string.ai_generating) else "AI",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                if (message.isStreaming) {
                    Spacer(Modifier.width(6.dp))
                    StreamingDotsMiuix()
                }
                message.elapsedMs?.let { elapsed ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        String.format(Locale.US, "%.1fs", elapsed / 1000.0),
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (!message.isStreaming && message.text.isNotBlank()) {
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
                Row {
                    Text(
                        message.text.ifBlank {
                            if (message.isStreaming) "" else stringResource(R.string.ai_no_text)
                        },
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = if (message.text.isBlank() && !message.isStreaming) {
                            colorScheme.onSurfaceVariantSummary
                        } else {
                            colorScheme.onSurface
                        },
                    )
                    if (message.isStreaming) StreamingCursorMiuix()
                }
            }
            message.error?.let { error ->
                Spacer(Modifier.height(6.dp))
                Text(
                    error,
                    fontSize = 11.sp,
                    color = colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            colorScheme.error.copy(alpha = 0.10f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
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
                    is AiCard.Sstv -> SstvCardMiuix(card.mode, card.imagePath)
                    is AiCard.Reminder -> InfoCardMiuix(
                        title = stringResource(R.string.ai_reminder_created),
                        detail = card.nextAos?.let { "${card.satellite} · ${formatAiTime(it)}" }
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
private fun StreamingDotsMiuix() {
    val transition = rememberInfiniteTransition(label = "aiDots")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "aiDotsAlpha",
    )
    Text(
        "● ● ●",
        fontSize = 8.sp,
        color = colorScheme.primary.copy(alpha = alpha),
    )
}

@Composable
private fun StreamingCursorMiuix() {
    val transition = rememberInfiniteTransition(label = "aiCursor")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "aiCursorAlpha",
    )
    Text(
        "▍",
        fontSize = 14.sp,
        color = colorScheme.primary.copy(alpha = alpha),
    )
}

@Composable
private fun ComposerMiuix(
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
                colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(24.dp),
            )
            .border(
                width = 1.dp,
                color = if (focused) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.18f),
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
                    AttachmentChipMiuix(attachment, onRemoveAttachment)
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
                color = colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(colorScheme.primary),
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
                            fontSize = 14.sp,
                            color = colorScheme.onSurfaceVariantSummary,
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
                    tint = colorScheme.primary,
                )
            }
            if (attachments.isEmpty()) {
                Text(
                    stringResource(R.string.ai_attach_hint),
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        when {
                            streaming -> colorScheme.surfaceVariant
                            canSend -> colorScheme.primary
                            else -> colorScheme.surfaceVariant
                        },
                        CircleShape,
                    )
                    .clickable {
                        if (streaming) onStop() else if (canSend) onSend()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(
                        if (streaming) R.string.ai_stop_desc else R.string.ai_send_desc,
                    ),
                    tint = if (streaming || !canSend) {
                        colorScheme.onSurfaceVariantSummary
                    } else {
                        colorScheme.onPrimary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentChipMiuix(attachment: AiAttachment, onRemove: (String) -> Unit) {
    val icon = when (attachment.kind) {
        AiAttachmentKind.AUDIO -> Icons.Rounded.AudioFile
        AiAttachmentKind.IMAGE -> Icons.Rounded.Image
        else -> Icons.Rounded.Description
    }
    Row(
        modifier = Modifier
            .background(
                colorScheme.surfaceVariant.copy(alpha = 0.8f),
                RoundedCornerShape(50),
            )
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            attachment.name,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        when (attachment.status) {
            AiAttachmentStatus.PARSING -> {
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.ai_att_parsing),
                    fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            AiAttachmentStatus.ERROR -> {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .background(colorScheme.error, CircleShape),
                )
            }
            AiAttachmentStatus.READY -> Unit
        }
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clickable { onRemove(attachment.id) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun ToolCardMiuix(tool: AiToolUi) {
    val animatedProgress by animateFloatAsState(
        targetValue = (tool.progress ?: 0f).coerceIn(0f, 1f),
        label = "aiToolProgress",
    )
    // 完成后自动折叠；运行中/失败默认展开，用户点击可手动切换
    var expanded by remember(tool.callId) { mutableStateOf(tool.state != AiToolUiState.SUCCESS) }
    LaunchedEffect(tool.state) {
        when (tool.state) {
            AiToolUiState.SUCCESS -> expanded = false
            AiToolUiState.ERROR -> expanded = true
            AiToolUiState.RUNNING -> Unit
        }
    }
    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .padding(12.dp),
        ) {
            val statusColor = when (tool.state) {
                AiToolUiState.RUNNING -> colorScheme.primary
                AiToolUiState.SUCCESS -> SafeColors.successIcon
                AiToolUiState.ERROR -> colorScheme.error
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
                        .background(colorScheme.primary.copy(alpha = 0.10f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        toolIcon(tool.name),
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tool.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(statusColor, CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(label, fontSize = 10.sp, color = statusColor)
                    }
                    if (!expanded) {
                        tool.summary?.let {
                            Text(
                                it,
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariantSummary,
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
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (expanded) {
                val progress = tool.progress
                if (tool.state == AiToolUiState.RUNNING && progress != null) {
                    LinearProgressIndicator(
                        progress = animatedProgress,
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
                        stringResource(R.string.ai_executing),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                tool.summary?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                tool.argsPreview.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 9.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                tool.error?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
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
                SatelliteRadar(
                    azimuthDeg = live.azimuthDeg,
                    elevationDeg = live.elevationDeg,
                    primary = colorScheme.primary,
                    outline = colorScheme.onSurface.copy(alpha = 0.25f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
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

@Composable
private fun AiConfirmDialogMiuix(
    request: AiConfirmRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Dialog(onDismissRequest = onDeny) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Text(request.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                request.message,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDeny, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_cancel))
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.ai_allow))
                }
            }
        }
    }
}

@Composable
private fun AiHistoryDialogMiuix(
    sessions: List<AiSessionSummary>,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .background(colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.ai_history_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.ai_history_empty),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLoad(session.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(session.title, fontSize = 13.sp, maxLines = 1)
                                Text(
                                    "${formatAiTime(Instant.ofEpochMilli(session.updatedAt))} · " +
                                        "${session.messageCount} 条",
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                stringResource(R.string.ai_export),
                                fontSize = 11.sp,
                                color = colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onExport(session.id) }
                                    .padding(6.dp),
                            )
                            Text(
                                stringResource(R.string.ai_delete),
                                fontSize = 11.sp,
                                color = colorScheme.error,
                                modifier = Modifier
                                    .clickable { onDelete(session.id) }
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }
            TextButton(text = stringResource(R.string.ai_close), onClick = onDismiss)
        }
    }
}

@Composable
private fun SstvCardMiuix(mode: String?, imagePath: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("SSTV 图像", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                mode?.let { "模式：$it" } ?: "模式：VIS 自动识别",
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
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
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}

private val AI_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatAiTime(instant: Instant): String = AI_TIME_FMT.format(instant)

private fun toolIcon(name: String): ImageVector = when {
    name == "get_zone_info" -> Icons.Rounded.Place
    name == "search_satellites" || name.startsWith("get_satellite") ||
        name == "get_amsat_status" -> Icons.Rounded.SatelliteAlt
    name == "start_sstv_receive" -> Icons.Rounded.Image
    name == "open_screen" -> Icons.Rounded.ArrowForward
    name == "add_pass_reminder" -> Icons.Rounded.Notifications
    else -> Icons.Rounded.Build
}
