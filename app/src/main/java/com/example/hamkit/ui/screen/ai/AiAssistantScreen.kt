package com.example.hamkit.ui.screen.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.example.hamkit.ui.component.dialog.rememberConfirmDialog
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
import com.example.hamkit.data.ai.AiModelProfile
import com.example.hamkit.data.ai.AiRole
import com.example.hamkit.data.ai.AiSessionSummary
import com.example.hamkit.data.ai.tool.AiCard
import com.example.hamkit.data.ai.tool.SatelliteCardData
import com.example.hamkit.data.ai.tool.ZoneCardData
import com.example.hamkit.ui.component.StatCell
import com.example.hamkit.ui.component.StatEmphasis
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
import com.example.hamkit.ui.viewmodel.AprsViewModel
import com.example.hamkit.ui.viewmodel.Ft8ViewModel
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
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.hamkit.ui.theme.SafeColors
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import com.example.hamkit.data.ai.AiReasoningEffort

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
    val ft8ViewModel = appViewModel<Ft8ViewModel>()
    val aprsViewModel = appViewModel<AprsViewModel>()
    val facade = remember(mainViewModel, navigator, ft8ViewModel, aprsViewModel) {
        AiAppFacadeImpl(mainViewModel, navigator, ft8ViewModel, aprsViewModel)
    }
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
    var pendingDelete by remember { mutableStateOf<AiSessionSummary?>(null) }
    // D2 整改：删除会话确认统一走共享 ConfirmDialog（与 Material 版及全 App 一致）
    val currentPendingDelete by rememberUpdatedState(pendingDelete)
    val deleteSessionConfirm = rememberConfirmDialog(
        onConfirm = {
            currentPendingDelete?.let { viewModel.deleteSession(it.id) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    )
    var pendingExport by remember { mutableStateOf<String?>(null) }
    // C1 整改：导出完成后给 Snackbar 反馈（Miuix 版）
    val snackbarHostState = remember { top.yukonga.miuix.kmp.basic.SnackbarHostState() }
    val exportDoneMessage = stringResource(R.string.ai_export_done)
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val content = pendingExport
        var exported = false
        if (uri != null && content != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { exported = true }
        }
        pendingExport = null
        if (exported) scope.launch { snackbarHostState.showSnackbar(exportDoneMessage) }
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

    // 顶栏随滚动收起大标题：否则内容会被大标题下的硬边裁切（与 About/色板/APRS 设置页一致）
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = stringResource(R.string.ai_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshSessions(); showHistory = true },
                        modifier = Modifier.clip(CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = stringResource(R.string.ai_history_desc),
                            tint = colorScheme.primary,
                        )
                    }
                    // D1 整改：补「新建会话」入口，与 Material 版顶栏动作对齐
                    IconButton(
                        onClick = viewModel::newSession,
                        modifier = Modifier.clip(CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.ai_new_chat_desc),
                            tint = colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = { navigator.push(Route.AiSettings) },
                        modifier = Modifier.clip(CircleShape),
                    ) {
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
        snackbarHost = {
            top.yukonga.miuix.kmp.basic.SnackbarHost(state = snackbarHostState)
        },
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

            // 内容区吃满剩余高度：空态与消息列表都在这里消化，
            // 输入框才不会被可滚动的空态挤扁（此前空态无 weight，会把 Composer 压成只剩一行提示）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
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
                    // B7 整改：键盘弹出时隐藏空态（避免与输入框争抢可视区），空态交给内部滚动
                    if (imeBottom == 0.dp) {
                        EmptyStateMiuix(
                            onPick = { prompt -> viewModel.onInputChange(prompt) },
                            modifier = Modifier
                                .weight(1f)
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
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
            }

            state.pendingConfirm?.let { request ->
                AiConfirmDialogMiuix(
                    request = request,
                    onApprove = { viewModel.resolveConfirm(true) },
                    onDeny = { viewModel.resolveConfirm(false) },
                )
            }
            if (showHistory) {
                AiHistorySheetMiuix(
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
            // D2：pendingDelete 触发共享确认弹窗（LaunchedEffect 保证 showConfirm 只调一次）。
            // 字符串在组合期解析，LaunchedEffect 内不能调用 @Composable。
            val deleteTitle = stringResource(R.string.ai_history_delete_title)
            val deleteDesc = stringResource(R.string.ai_history_delete_desc)
            val deleteLabel = stringResource(R.string.ai_delete)
            val cancelLabel = stringResource(R.string.ai_cancel)
            LaunchedEffect(pendingDelete?.id) {
                if (pendingDelete != null) {
                    deleteSessionConfirm.showConfirm(
                        title = deleteTitle,
                        content = deleteDesc,
                        confirm = deleteLabel,
                        dismiss = cancelLabel,
                    )
                }
            }
            ComposerMiuix(
                // 与底部导航栏/键盘之间留 8dp 呼吸位，避免输入框直接贴在导航栏上
                modifier = Modifier.padding(bottom = bottomInset + 8.dp),
                input = state.input,
                streaming = state.isStreaming,
                attachments = state.attachments,
                onInputChange = viewModel::onInputChange,
                onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                onRemoveAttachment = viewModel::removeAttachment,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                profiles = state.profiles,
                activeProfileId = state.activeProfileId,
                activeModelId = state.activeModelId,
                effort = state.config.reasoningEffort,
                onSwitchModel = viewModel::switchModel,
                onEffort = viewModel::setReasoningEffort,
                onOpenSettings = { navigator.push(Route.AiSettings) },
                onReload = viewModel::reloadConfig,
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
private fun EmptyStateMiuix(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val samples = listOf(
        stringResource(R.string.ai_sample_1),
        stringResource(R.string.ai_sample_2),
        stringResource(R.string.ai_sample_3),
        stringResource(R.string.ai_sample_4),
    )
    Column(
        // B7 整改：小屏/横屏下空态整体可滚动，避免超出可视区
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp),
                    )
                    // 触控区专项：48dp 最小可点高度
                    .heightIn(min = 48.dp)
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
            if (message.reasoning.isNotBlank()) {
                ReasoningCardMiuix(message)
                Spacer(Modifier.height(6.dp))
            }
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
                Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
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
                // 工具调用默认折叠成一行（运行中/失败时展开），历史消息不含工具，天然不显示
                val anyActive = message.tools.any { it.state != AiToolUiState.SUCCESS }
                var toolsExpanded by remember(message.id) { mutableStateOf(anyActive) }
                LaunchedEffect(anyActive) { toolsExpanded = anyActive }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { toolsExpanded = !toolsExpanded }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Build,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (toolsExpanded) R.string.ai_tools_expanded else R.string.ai_tools_collapsed,
                            message.tools.size,
                        ),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (toolsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (toolsExpanded) {
                    Spacer(Modifier.height(8.dp))
                    message.tools.forEach { tool ->
                        ToolCardMiuix(tool)
                        Spacer(Modifier.height(8.dp))
                    }
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
    // A4 整改：文本 "● ● ●"（8sp，字形基线不可控）改为 3 个 Circle 绘制
    val transition = rememberInfiniteTransition(label = "aiDots")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "aiDotsAlpha",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
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
    profiles: List<AiModelProfile>,
    activeProfileId: String,
    activeModelId: String?,
    effort: AiReasoningEffort,
    onInputChange: (String) -> Unit,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSwitchModel: (String, String) -> Unit,
    onEffort: (AiReasoningEffort) -> Unit,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
) {
    val canSend = (input.isNotBlank() || attachments.any { it.status == AiAttachmentStatus.READY }) &&
        attachments.none { it.status == AiAttachmentStatus.PARSING }
    var focused by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val active = profiles.firstOrNull { it.id == activeProfileId }
    val modelLabel = buildString {
        append(active?.currentEntry?.label ?: active?.currentModelId?.takeIf { it.isNotBlank() } ?: "选择模型")
        if (effort != AiReasoningEffort.OFF) append(" ${effort.label}")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(
                colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(26.dp),
            )
            .border(
                width = 1.dp,
                color = if (focused) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.16f),
                shape = RoundedCornerShape(26.dp),
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colorScheme.onSurface.copy(alpha = 0.06f), CircleShape)
                    .clickable(onClick = onPickFiles),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.ai_attach_desc),
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .alpha(if (streaming) 0.4f else 1f)
                    // B1 整改：streaming 时模型选择禁用（点击不再误触 reloadConfig）
                    .clickable(enabled = !streaming) {
                        onReload()
                        pickerOpen = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modelLabel,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.ai_model_switch),
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
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

        if (pickerOpen) {
            ModelPickerPopupMiuix(
                profiles = profiles,
                activeProfileId = activeProfileId,
                activeModelId = activeModelId,
                effort = effort,
                streaming = streaming,
                onDismiss = { pickerOpen = false },
                onPick = { profileId, modelId ->
                    onSwitchModel(profileId, modelId)
                    pickerOpen = false
                },
                onEffort = onEffort,
                onManage = {
                    pickerOpen = false
                    onOpenSettings()
                },
            )
        }
    }
}

@Composable
private fun ModelPickerPopupMiuix(
    profiles: List<AiModelProfile>,
    activeProfileId: String,
    activeModelId: String?,
    effort: AiReasoningEffort,
    streaming: Boolean,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit,
    onEffort: (AiReasoningEffort) -> Unit,
    onManage: () -> Unit,
) {
    var page by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val filtered = profiles.mapNotNull { profile ->
        val entries = profile.models.filter { entry ->
            query.isBlank() ||
                profile.name.contains(query, ignoreCase = true) ||
                entry.modelId.contains(query, ignoreCase = true) ||
                entry.displayName?.contains(query, ignoreCase = true) == true
        }
        if (entries.isEmpty()) null else profile to entries
    }
    val activeEntry = profiles.firstOrNull { it.id == activeProfileId }?.currentEntry

    // C7 整改：入场动画（fade+scale，右下角原点），替代硬编码偏移的瞬间弹出
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val popupAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(120),
        label = "popupAlpha",
    )
    val popupScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.92f,
        animationSpec = tween(120),
        label = "popupScale",
    )
    Popup(
        alignment = Alignment.BottomEnd,
        offset = IntOffset(0, -56),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            modifier = Modifier
                .width(260.dp)
                .graphicsLayer {
                    alpha = popupAlpha
                    scaleX = popupScale
                    scaleY = popupScale
                    transformOrigin = TransformOrigin(1f, 1f) // 右下角为缩放原点
                },
        ) {
            Column(Modifier.padding(6.dp)) {
                when (page) {
                    0 -> {
                        PickerRowMiuix(
                            label = "模型",
                            value = activeEntry?.label ?: activeModelId ?: "未选择",
                        ) { page = 1 }
                        PickerRowMiuix(label = "推理等级", value = effort.label) { page = 2 }
                    }

                    1 -> {
                        PickerBackMiuix("模型") { page = 0 }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(
                                    colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            textStyle = TextStyle(fontSize = 12.sp, color = colorScheme.onSurface),
                            cursorBrush = SolidColor(colorScheme.primary),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) {
                                        Text(
                                            "搜索模型…",
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        if (filtered.isEmpty()) {
                            Text(
                                "没有匹配的模型",
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                                filtered.forEach { (profile, entries) ->
                                    item(key = "p_${profile.id}") {
                                        Text(
                                            profile.name,
                                            fontSize = 9.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                        )
                                    }
                                    items(entries, key = { "${profile.id}_${it.id}" }) { entry ->
                                        val current = profile.id == activeProfileId && entry.id == activeModelId
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (current) colorScheme.primary.copy(alpha = 0.08f)
                                                    else Color.Transparent,
                                                )
                                                .clickable { if (!streaming) onPick(profile.id, entry.id) }
                                                .padding(horizontal = 6.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                entry.label,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (current) {
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = colorScheme.primary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "管理模型…",
                            fontSize = 11.sp,
                            color = colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onManage)
                                .padding(vertical = 6.dp),
                        )
                    }

                    else -> {
                        PickerBackMiuix("推理等级") { page = 0 }
                        AiReasoningEffort.entries.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onEffort(item)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(item.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (item == effort) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRowMiuix(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PickerBackMiuix(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onBack)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowLeft,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
        Text(title, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun ReasoningCardMiuix(message: AiChatMessageUi) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A3 整改：emoji 换 Material 图标，统一图标族
            Icon(
                Icons.Rounded.Psychology,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "思考过程",
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (message.reasoningDone) {
                    if (expanded) "收起" else "展开"
                } else {
                    "思考中…"
                },
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                message.reasoning,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 30,
                overflow = TextOverflow.Ellipsis,
            )
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
                .clip(CircleShape)
                .clickable { onRemove(attachment.id) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.ai_remove_attachment),
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
                .clip(RoundedCornerShape(14.dp))
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
private fun MetricMiuix(label: String, value: String, compact: Boolean = false) {
    // D6/E2 整改：原 label 9.5sp 低于无障碍最小字号，统一收敛到 StatCell.Inline（11sp）
    StatCell(
        label = label,
        value = value,
        emphasis = StatEmphasis.Inline
    )
}

@Composable
private fun SatelliteCardMiuix(data: SatelliteCardData) {
    val navigator = LocalNavigator.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            // E3 整改：默认折叠为「名称 + 在境状态 + 最大仰角」摘要行，点击展开完整数据
            var expanded by remember(data.catalogNumber) { mutableStateOf(false) }
            val inPassNow = data.passes.any { it.isInPass }
            val maxElev = data.passes.maxOfOrNull { it.maxElevation }?.toInt()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Text(
                    data.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (inPassNow) {
                    Text(
                        "在境",
                        fontSize = 10.sp,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                maxElev?.let {
                    Text(
                        "最大 ${it}°",
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (!expanded) return@Column

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

            data.live?.let { live ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SatelliteRadar(
                        azimuthDeg = live.azimuthDeg,
                        elevationDeg = live.elevationDeg,
                        primary = colorScheme.primary,
                        outline = colorScheme.onSurface.copy(alpha = 0.25f),
                        diameter = 112.dp,
                        isEclipsed = live.isEclipsed,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricMiuix("方位 AZ", "%.1f°".format(live.azimuthDeg), compact = true)
                            MetricMiuix("仰角 EL", "%.1f°".format(live.elevationDeg), compact = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricMiuix("距离", "%.0f km".format(live.rangeKm), compact = true)
                            MetricMiuix("高度", "%.0f km".format(live.altitudeKm), compact = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricMiuix(
                                "星下点",
                                "%.1f°,%.1f°".format(live.subpointLatDeg, live.subpointLonDeg),
                                compact = true,
                            )
                            MetricMiuix("相位", "%.0f°".format(live.phaseDeg), compact = true)
                        }
                    }
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
                Text("过境", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                data.passes.take(3).forEach { pass ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(
                            buildString {
                                if (pass.isInPass) append("正在过境 · ")
                                append("${formatAiTime(pass.aos)} – ${formatAiTime(pass.los)}")
                            },
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
private fun AiHistorySheetMiuix(
    sessions: List<AiSessionSummary>,
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .background(
                        colorScheme.surface,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    // 消费面板内的点击，避免穿透到遮罩导致误关闭
                    .clickable { }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            colorScheme.onSurface.copy(alpha = 0.2f),
                            CircleShape,
                        )
                        .align(Alignment.CenterHorizontally),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ai_history_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onNewChat, modifier = Modifier.clip(CircleShape)) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.ai_new_chat_desc),
                            tint = colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.clip(CircleShape)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.ai_close),
                            tint = colorScheme.onSurfaceVariantSummary,
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
                            tint = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.ai_history_empty),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.ai_history_empty_desc),
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(top = 8.dp),
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
                                            colorScheme.primary.copy(alpha = 0.08f)
                                        } else {
                                            colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        },
                                        RoundedCornerShape(12.dp),
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (current) {
                                            colorScheme.primary.copy(alpha = 0.5f)
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
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${formatSessionTime(session.updatedAt)} · " +
                                            stringResource(
                                                R.string.ai_history_messages,
                                                session.messageCount,
                                            ),
                                        fontSize = 11.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable { onExport(session.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.FileDownload,
                                        contentDescription = stringResource(R.string.ai_export),
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable { onDelete(session.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.ai_delete),
                                        tint = colorScheme.error,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    text = stringResource(R.string.ai_close),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

/** 历史列表的相对时间：今天显示时分，昨天显示「昨天」，更早显示日期。 */
private fun formatSessionTime(epochMs: Long): String {
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

@Composable
private fun AiModelPickerMiuix(
    profiles: List<AiModelProfile>,
    activeId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .background(
                        colorScheme.surface,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    .clickable { }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(colorScheme.onSurface.copy(alpha = 0.2f), CircleShape)
                        .align(Alignment.CenterHorizontally),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ai_models_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.clip(CircleShape)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.ai_close),
                            tint = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (profiles.isEmpty()) {
                    Text(
                        stringResource(R.string.ai_model_empty),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            val current = profile.id == activeId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (current) colorScheme.primary.copy(alpha = 0.08f)
                                        else colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onPick(profile.id) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, fontSize = 14.sp)
                                    Text(
                                        "${profile.protocol.label} · ${profile.currentModelId}",
                                        fontSize = 11.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                if (current) {
                                    Text(
                                        stringResource(R.string.ai_model_current),
                                        fontSize = 10.sp,
                                        color = colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
