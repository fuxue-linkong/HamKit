package com.example.hamkit.ui.screen.ft8

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.ft8.QsoRecordValidator
import com.example.hamkit.data.qso.AdifExporter
import com.example.hamkit.data.qso.QsoExportFormat
import com.example.hamkit.data.qso.QsoExporter
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.util.QsoLogEvent
import com.example.hamkit.ui.util.QsoLogEventTracker
import com.example.hamkit.ui.viewmodel.DeletedRecordBuffer
import com.example.hamkit.ui.viewmodel.QsoCallsignMatchMode
import com.example.hamkit.ui.viewmodel.QsoLogViewModel
import com.example.hamkit.ui.viewmodel.QsoTimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.hamkit.ui.theme.Radii

/**
 * QSO 通联日志分发入口：按当前 UI 风格选择 Miuix / Material 实现。
 *
 * 两者共用同一个 application 级 [QsoLogViewModel] 实例，切换风格不会丢状态。
 */
@Composable
fun QsoLogScreen(
    onNavigateBack: () -> Unit = {},
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> QsoLogScreenMiuix(onNavigateBack = onNavigateBack)
        UiMode.Material -> QsoLogScreenMaterial(onNavigateBack = onNavigateBack)
    }
}

/**
 * QSO 通联日志：FT8 通联记录的列表 / 筛选 / 增删改 / ADIF-XML 导出。
 *
 * Miuix 单主题，对齐 Ft8SettingsScreen。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QsoLogScreenMiuix(
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<QsoLogViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = 关闭；record.id == 0 → 新增
    var editingRecord by remember { mutableStateOf<Ft8QsoRecord?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    // 删除即生效 + Snackbar 撤销：缓冲超时由 ViewModel 管理（DeletedRecordBuffer 10 秒）
    val snackbarHostState = remember { SnackbarHostState() }
    val undoText = stringResource(R.string.qso_log_undo)
    LaunchedEffect(Unit) {
        viewModel.pendingDelete.collect { pending ->
            if (pending == null) return@collect
            val message = context.getString(R.string.qso_log_deleted_snackbar, pending.callsign)
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = undoText,
                    withDismissAction = false,
                    duration = SnackbarDuration.Custom(DeletedRecordBuffer.DEFAULT_TIMEOUT_MILLIS),
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoDelete()
                } else if (result == SnackbarResult.Dismissed) {
                    viewModel.dismissPendingDelete()
                }
            }
        }
    }
    var showCustomRangeDialog by remember { mutableStateOf(false) }

    // CreateDocument 往返期间记住导出参数（mimeType 在 launcher 构造时已固定）
    var pendingFormat by remember { mutableStateOf(QsoExportFormat.ADIF) }
    var pendingUseFiltered by remember { mutableStateOf(true) }
    val exportSavedText = stringResource(R.string.qso_export_saved)

    val writeSavedExport: (Uri) -> Unit = { uri ->
        val format = pendingFormat
        val useFiltered = pendingUseFiltered
        scope.launch(Dispatchers.IO) {
            val content = viewModel.buildExport(format, useFiltered)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
            // 埋点：文件写入成功（失败由 openOutputStream 返回 null 表示，不记录）
            QsoLogEventTracker.log(
                QsoLogEvent.ExportSaved(
                    format.name,
                    if (useFiltered) "filtered" else "all",
                    viewModel.exportCount(useFiltered),
                )
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(context, exportSavedText, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val saveAdifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> if (uri != null) writeSavedExport(uri) }
    val saveXmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri: Uri? -> if (uri != null) writeSavedExport(uri) }

    val shareText = stringResource(R.string.qso_export_share)
    val shareExport: (QsoExportFormat, Boolean) -> Unit = { format, useFiltered ->
        scope.launch {
            val content = viewModel.buildExport(format, useFiltered)
            val file = File(context.cacheDir, QsoExporter.suggestFilename(format))
            withContext(Dispatchers.IO) { file.writeText(content, Charsets.UTF_8) }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                setDataAndType(uri, format.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, shareText))
        }
    }

    val scrollBehavior = MiuixScrollBehavior()

    // 埋点：进入页面记录 screen_enter，离开时记录 screen_duration（停留时长 + 操作计数）
    DisposableEffect(Unit) {
        QsoLogEventTracker.logScreenEnter()
        onDispose { QsoLogEventTracker.endSession() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.qso_log_title),
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
                        stringResource(R.string.qso_log_add),
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                QsoLogEventTracker.log(QsoLogEvent.RecordAddStarted)
                                editingRecord = Ft8QsoRecord(
                                    id = 0,
                                    callsign = "",
                                    grid = null,
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    Text(
                        stringResource(R.string.qso_log_export),
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                QsoLogEventTracker.log(QsoLogEvent.ExportOpened)
                                showExportDialog = true
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        snackbarHost = {
            top.yukonga.miuix.kmp.basic.SnackbarHost(state = snackbarHostState)
        },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 边到边下 IME inset 需在 Compose 层消费：
                // 筛选呼号输入框位于列表顶部，键盘弹出后必须仍可见（HK-BUG-001 同类页面）
                .imePadding()
                .imeNestedScroll()
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
            overscrollEffect = null,
        ) {
            // ── 筛选卡 ──
            item(key = "filter") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        TextField(
                            value = uiState.filterCallsign,
                            onValueChange = { value ->
                                viewModel.setFilterCallsign(value.uppercase().filter { it.isLetterOrDigit() }.take(12))
                            },
                            label = stringResource(R.string.qso_log_filter_callsign),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        Spacer(Modifier.height(4.dp))
                        OverlayDropdownPreference(
                            title = stringResource(R.string.qso_log_match_mode_title),
                            items = listOf(
                                stringResource(R.string.qso_log_match_mode_prefix),
                                stringResource(R.string.qso_log_match_mode_exact),
                            ),
                            selectedIndex = uiState.callsignMatchMode.ordinal,
                            onExpandedChange = { expanded ->
                                if (expanded) QsoLogEventTracker.log(QsoLogEvent.FilterOpened)
                            },
                            onSelectedIndexChange = { index ->
                                viewModel.setCallsignMatchMode(QsoCallsignMatchMode.entries[index])
                            },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.qso_log_filter_band),
                            items = listOf(stringResource(R.string.qso_log_filter_all_band)) +
                                Ft8Band.entries.map { it.displayName },
                            selectedIndex = uiState.filterBand?.let { Ft8Band.entries.indexOf(it) + 1 } ?: 0,
                            onExpandedChange = { expanded ->
                                if (expanded) QsoLogEventTracker.log(QsoLogEvent.FilterOpened)
                            },
                            onSelectedIndexChange = { index ->
                                viewModel.setFilterBand(if (index == 0) null else Ft8Band.entries[index - 1])
                            },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.qso_log_filter_time),
                            items = listOf(
                                stringResource(R.string.qso_log_time_all),
                                stringResource(R.string.qso_log_time_today),
                                stringResource(R.string.qso_log_time_7d),
                                stringResource(R.string.qso_log_time_30d),
                                stringResource(R.string.qso_log_time_custom),
                            ),
                            // 选中"自定义范围"时摘要显示具体日期区间，其余显示预设名
                            selectedIndex = uiState.timeRange.ordinal,
                            summary = if (uiState.timeRange == QsoTimeRange.CUSTOM) {
                                uiState.customRange?.let { (start, end) ->
                                    stringResource(
                                        R.string.qso_log_time_custom_summary,
                                        formatLocalDate(start),
                                        formatLocalDate(end),
                                    )
                                } ?: stringResource(R.string.qso_log_time_custom)
                            } else {
                                null
                            },
                            onExpandedChange = { expanded ->
                                if (expanded) QsoLogEventTracker.log(QsoLogEvent.FilterOpened)
                            },
                            onSelectedIndexChange = { index ->
                                if (index == QsoTimeRange.CUSTOM.ordinal) {
                                    showCustomRangeDialog = true
                                } else {
                                    viewModel.setTimeRange(QsoTimeRange.entries[index])
                                }
                            },
                        )
                        TextButton(
                            text = stringResource(R.string.qso_log_filter_clear),
                            onClick = { viewModel.clearFilters() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ── 计数 ──
            item(key = "count") {
                Text(
                    text = if (uiState.records.size == uiState.totalCount) {
                        stringResource(R.string.qso_log_count, uiState.totalCount)
                    } else {
                        stringResource(
                            R.string.qso_log_count_filtered,
                            uiState.records.size, uiState.totalCount,
                        )
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            // ── 记录列表 / 空态 ──
            if (uiState.records.isEmpty()) {
                item(key = "empty") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(R.string.qso_log_empty),
                                style = MiuixTheme.textStyles.body1,
                                color = colorScheme.onSurfaceSecondary,
                            )
                        }
                    }
                }
            } else {
                items(uiState.records, key = { it.id }) { record ->
                    QsoRecordCard(record = record, onClick = {
                        QsoLogEventTracker.log(QsoLogEvent.RecordEditOpened(record.id, isAdd = false))
                        editingRecord = record
                    })
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    // ── 添加 / 编辑弹窗 ──
    editingRecord?.let { record ->
        QsoEditDialog(
            record = record,
            onOpened = {
                QsoLogEventTracker.log(QsoLogEvent.RecordEditOpened(record.id, isAdd = record.id == 0L))
            },
            onDismiss = { editingRecord = null },
            onSave = { updated ->
                // record_saved 由 ViewModel 的 addRecord/updateRecord 记录
                if (updated.id == 0L) viewModel.addRecord(updated) else viewModel.updateRecord(updated)
                editingRecord = null
            },
            onDelete = if (record.id == 0L) null else { {
                // 删除即生效（无二次确认）：记录进缓冲，Snackbar 提供 10 秒撤销窗口
                QsoLogEventTracker.log(QsoLogEvent.RecordDeleteOpened(record.id))
                viewModel.deleteRecord(record)
                editingRecord = null
            } },
        )
    }

    // ── 自定义日期范围弹窗 ──
    if (showCustomRangeDialog) {
        QsoCustomRangeDialog(
            initialRange = if (uiState.timeRange == QsoTimeRange.CUSTOM) uiState.customRange else null,
            onDismiss = { showCustomRangeDialog = false },
            onConfirm = { startMs, endMs ->
                viewModel.setCustomDateRange(startMs, endMs)
                showCustomRangeDialog = false
            },
        )
    }

    // ── 导出弹窗 ──
    if (showExportDialog) {
        QsoExportDialog(
            onDismiss = { showExportDialog = false },
            filteredCount = uiState.records.size,
            totalCount = uiState.totalCount,
            filterCallsign = uiState.filterCallsign,
            filterBand = uiState.filterBand,
            timeRange = uiState.timeRange,
            customRange = uiState.customRange,
            onSaveToFile = { format, useFiltered ->
                pendingFormat = format
                pendingUseFiltered = useFiltered
                showExportDialog = false
                val filename = QsoExporter.suggestFilename(format)
                val launcher = if (format == QsoExportFormat.ADIF) saveAdifLauncher else saveXmlLauncher
                launcher.launch(filename)
            },
            onShare = { format, useFiltered ->
                showExportDialog = false
                QsoLogEventTracker.log(
                    QsoLogEvent.ExportShared(
                        format.name,
                        if (useFiltered) "filtered" else "all",
                        viewModel.exportCount(useFiltered),
                    )
                )
                shareExport(format, useFiltered)
            },
        )
    }
}

/**
 * 自定义日期范围弹窗（Miuix 版）：开始/结束日期各一个 yyyy-MM-dd 文本输入。
 * 校验：格式合法、开始 <= 结束；不合法时禁用确定按钮。
 */
@Composable
private fun QsoCustomRangeDialog(
    initialRange: Pair<Long, Long>?,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val zone = ZoneId.systemDefault()

    fun toText(epochMs: Long?): String =
        epochMs?.let { formatter.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""

    var startText by remember { mutableStateOf(toText(initialRange?.first)) }
    var endText by remember { mutableStateOf(toText(initialRange?.second)) }

    fun parse(text: String): LocalDate? = try {
        LocalDate.parse(text.trim(), formatter)
    } catch (_: Exception) {
        null
    }

    val start = parse(startText)
    val end = parse(endText)
    val valid = start != null && end != null && !start.isAfter(end)

    WindowDialog(
        show = true,
        title = stringResource(R.string.qso_log_time_custom_title),
        onDismissRequest = onDismiss,
        content = {
            Column {
                TextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = stringResource(R.string.qso_log_time_custom_start),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = stringResource(R.string.qso_log_time_custom_end),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.qso_log_time_custom_confirm),
                        onClick = {
                            if (valid) {
                                onConfirm(
                                    start!!.atStartOfDay(zone).toInstant().toEpochMilli(),
                                    end!!.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
                                )
                            }
                        },
                        enabled = valid,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/**
 * 汇总当前生效的筛选条件为一句可读摘要，供导出对话框展示。
 *
 * 与 ViewModel 的过滤语义保持一致：呼号空 / 频段 null / 时间 ALL 视为未启用。
 * 无任何生效条件时返回"无筛选"文案。
 */
@Composable
fun buildFilterSummary(
    callsign: String,
    band: Ft8Band?,
    timeRange: QsoTimeRange,
    customRange: Pair<Long, Long>? = null,
): String {
    val parts = buildList {
        if (callsign.isNotBlank()) {
            add(stringResource(R.string.qso_export_filter_callsign_part, callsign.trim().uppercase()))
        }
        band?.let { add(it.displayName) }
        if (timeRange != QsoTimeRange.ALL) {
            add(
                when (timeRange) {
                    QsoTimeRange.TODAY -> stringResource(R.string.qso_log_time_today)
                    QsoTimeRange.LAST_7_DAYS -> stringResource(R.string.qso_log_time_7d)
                    QsoTimeRange.LAST_30_DAYS -> stringResource(R.string.qso_log_time_30d)
                    QsoTimeRange.CUSTOM -> {
                        val (startMs, endMs) = customRange ?: (0L to 0L)
                        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        stringResource(
                            R.string.qso_log_time_custom_summary,
                            formatter.format(Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault())),
                            formatter.format(Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault())),
                        )
                    }
                    QsoTimeRange.ALL -> ""
                }
            )
        }
    }.filter { it.isNotBlank() }
    return if (parts.isEmpty()) {
        stringResource(R.string.qso_export_filter_none)
    } else {
        parts.joinToString("，")
    }
}

/**
 * 必填字段标记：Miuix TextField 的 label 是纯 String（内部按 labelColor 单色渲染），
 * 无法在 label 内混色，故在输入框 trailingIcon 位置放红色 `*`（与 Material 版
 * label 内嵌红星等价的"字段旁标记"）。
 */
@Composable
private fun requiredMarker(): @Composable () -> Unit = {
    Text(
        "*",
        color = colorScheme.error,
        style = MiuixTheme.textStyles.body1,
        modifier = Modifier.padding(end = 4.dp),
    )
}

@Composable
private fun QsoRecordCard(record: Ft8QsoRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.callsign,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (record.isComplete) {
                        stringResource(R.string.qso_log_complete)
                    } else {
                        stringResource(R.string.qso_log_incomplete)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (record.isComplete) colorScheme.primary else colorScheme.onSurfaceSecondary,
                    modifier = Modifier
                        .background(
                            color = if (record.isComplete) {
                                colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                colorScheme.onSurfaceSecondary.copy(alpha = 0.10f)
                            },
                            shape = RoundedCornerShape(Radii.Badge),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatLocalTime(record.qsoTime),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colorScheme.onSurfaceSecondary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${record.band.displayName} · ${record.mode} · ${AdifExporter.formatMhz(record.freqHz)} MHz",
                style = MiuixTheme.textStyles.footnote2,
                color = colorScheme.onSurfaceSecondary,
            )
            if (record.grid != null || record.reportSent != "-99" || record.reportReceived != "-99") {
                Spacer(Modifier.height(2.dp))
                val parts = buildList {
                    record.grid?.let { add(it) }
                    if (record.reportSent != "-99") {
                        add(stringResource(R.string.qso_log_rst_sent) + " ${record.reportSent}")
                    }
                    if (record.reportReceived != "-99") {
                        add(stringResource(R.string.qso_log_rst_rcvd) + " ${record.reportReceived}")
                    }
                }
                Text(
                    parts.joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colorScheme.onSurfaceSecondary,
                )
            }
            record.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                Spacer(Modifier.height(2.dp))
                Text(
                    comment,
                    style = MiuixTheme.textStyles.footnote2,
                    color = colorScheme.onSurfaceSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QsoEditDialog(
    record: Ft8QsoRecord,
    onOpened: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (Ft8QsoRecord) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var callsign by remember(record.id) { mutableStateOf(record.callsign) }
    var grid by remember(record.id) { mutableStateOf(record.grid ?: "") }
    var reportSent by remember(record.id) { mutableStateOf(record.reportSent) }
    var reportReceived by remember(record.id) { mutableStateOf(record.reportReceived) }
    var band by remember(record.id) { mutableStateOf(record.band) }
    var freqMhz by remember(record.id) { mutableStateOf(AdifExporter.formatMhz(record.freqHz)) }
    var comment by remember(record.id) { mutableStateOf(record.comment ?: "") }
    var isComplete by remember(record.id) { mutableStateOf(record.isComplete) }
    // 校验失败信息（null = 无错误）；保存被拦截时置位，修改对应字段后立即清除
    var callsignError by remember(record.id) { mutableStateOf<String?>(null) }
    var freqError by remember(record.id) { mutableStateOf<String?>(null) }

    // 埋点：对话框组合即视为打开（record_edit_opened），仅触发一次
    DisposableEffect(record.id) {
        onOpened()
        onDispose { }
    }

    // 埋点：记录字段修改（field 为语义化字段名，change_count 由追踪器自增）
    fun logFieldChange(field: String) {
        QsoLogEventTracker.log(QsoLogEvent.RecordFieldChanged(field, QsoLogEventTracker.nextChangeCount(field)))
    }

    val context = LocalContext.current

    WindowDialog(
        show = true,
        title = stringResource(
            if (record.id == 0L) R.string.qso_log_add_title else R.string.qso_log_edit_title
        ),
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).imeNestedScroll()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = callsign,
                        onValueChange = { value ->
                            callsign = value.uppercase()
                                .filter { c -> c in 'A'..'Z' || c in '0'..'9' }
                                .take(QsoRecordValidator.CALLSIGN_MAX_LENGTH)
                            if (callsignError != null) callsignError = null
                            logFieldChange("callsign")
                        },
                        label = stringResource(R.string.qso_log_field_callsign),
                        trailingIcon = requiredMarker(),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    TextField(
                        value = grid,
                        onValueChange = { value ->
                            grid = value.uppercase().filter { it.isLetterOrDigit() }.take(8)
                            logFieldChange("grid")
                        },
                        label = stringResource(R.string.qso_log_field_grid),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = reportSent,
                        onValueChange = { value ->
                            reportSent = value.uppercase().filter { it.isDigit() || it == '+' || it == '-' || it == 'R' }.take(4)
                            logFieldChange("report_sent")
                        },
                        label = stringResource(R.string.qso_log_field_rst_sent),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    TextField(
                        value = reportReceived,
                        onValueChange = { value ->
                            reportReceived = value.uppercase().filter { it.isDigit() || it == '+' || it == '-' || it == 'R' }.take(4)
                            logFieldChange("report_received")
                        },
                        label = stringResource(R.string.qso_log_field_rst_rcvd),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = freqMhz,
                        onValueChange = { value ->
                            freqMhz = value.filter { c -> c.isDigit() || c == '.' }.take(10)
                            if (freqError != null) freqError = null
                            logFieldChange("freq")
                        },
                        label = stringResource(R.string.qso_log_field_freq),
                        trailingIcon = requiredMarker(),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.qso_log_field_complete),
                            style = MiuixTheme.textStyles.body2,
                            color = colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(checked = isComplete, onCheckedChange = {
                            isComplete = it
                            logFieldChange("is_complete")
                        })
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 频段：6 列 × 2 行紧凑选择
                Ft8Band.entries.chunked(6).forEach { rowBands ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowBands.forEach { b ->
                            val selected = b == band
                            Button(
                                onClick = {
                                    band = b
                                    freqMhz = AdifExporter.formatMhz(b.freqHz)
                                    logFieldChange("band")
                                },
                                modifier = Modifier.weight(1f),
                                colors = if (selected) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors(
                                        color = androidx.compose.ui.graphics.Color.Transparent,
                                        contentColor = colorScheme.onSurfaceSecondary,
                                    )
                                },
                            ) {
                                Text(b.displayName, style = MiuixTheme.textStyles.footnote2)
                            }
                        }
                    }
                }
                TextField(
                    value = comment,
                    onValueChange = { value ->
                        if (value.length <= 60) comment = value
                        logFieldChange("comment")
                    },
                    label = stringResource(R.string.qso_log_field_comment),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    onDelete?.let {
                        TextButton(
                            text = stringResource(R.string.qso_log_delete),
                            onClick = it,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                    TextButton(
                        text = stringResource(R.string.qso_log_save),
                        onClick = {
                            // 保存前硬校验：呼号 + 频率；失败时标记错误并保持对话框打开
                            val trimmedCallsign = callsign.trim().uppercase()
                            val callsignProblem = QsoRecordValidator.validateCallsign(trimmedCallsign)
                            val (freqMhzValue, freqProblem) = QsoRecordValidator.validateFreqMhz(freqMhz)
                            callsignError = callsignProblem
                            freqError = freqProblem
                            if (callsignProblem != null || freqProblem != null) {
                                Toast.makeText(
                                    context,
                                    callsignProblem ?: freqProblem.orEmpty(),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                QsoLogEventTracker.log(QsoLogEvent.RecordSaveBlocked)
                                return@TextButton
                            }
                            val freqMhzChecked = freqMhzValue ?: 0.0
                            val freqHz = (freqMhzChecked * 1_000_000).toLong()
                            QsoLogEventTracker.log(QsoLogEvent.RecordSavedFromDialog)
                            onSave(
                                record.copy(
                                    callsign = trimmedCallsign,
                                    grid = grid.trim().uppercase().ifEmpty { null },
                                    reportSent = reportSent.ifBlank { "-99" },
                                    reportReceived = reportReceived.ifBlank { "-99" },
                                    band = band,
                                    freqHz = freqHz,
                                    comment = comment.trim().ifEmpty { null },
                                    isComplete = isComplete,
                                )
                            )
                            // 软提示：保存成功后如果频率远离所选频段，仅 Toast 提醒
                            if (QsoRecordValidator.isFreqFarFromBand(freqMhzChecked, band)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.qso_log_freq_band_mismatch),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

@Composable
private fun QsoExportDialog(
    onDismiss: () -> Unit,
    filteredCount: Int,
    totalCount: Int,
    filterCallsign: String,
    filterBand: Ft8Band?,
    timeRange: QsoTimeRange,
    customRange: Pair<Long, Long>?,
    onSaveToFile: (QsoExportFormat, Boolean) -> Unit,
    onShare: (QsoExportFormat, Boolean) -> Unit,
) {
    var format by remember { mutableStateOf(QsoExportFormat.ADIF) }
    var useFiltered by remember { mutableStateOf(true) }
    val count = if (useFiltered) filteredCount else totalCount

    val summary = buildFilterSummary(filterCallsign, filterBand, timeRange, customRange)
    val hasActiveFilters = filteredCount < totalCount

    // 埋点：导出格式 / 范围切换（仅在用户主动切换时记录）
    fun logFormatChange(f: QsoExportFormat) {
        QsoLogEventTracker.log(QsoLogEvent.ExportFormatChanged(f.name))
    }
    fun logScopeChange(filtered: Boolean) {
        QsoLogEventTracker.log(
            QsoLogEvent.ExportScopeChanged(if (filtered) "filtered" else "all", if (filtered) filteredCount else totalCount)
        )
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.qso_log_export),
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    stringResource(R.string.qso_export_format),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colorScheme.onSurfaceSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QsoExportFormat.entries.forEach { f ->
                        ChoiceChipButton(
                            label = if (f == QsoExportFormat.ADIF) "ADIF (.adi)" else "XML (.xml)",
                            selected = format == f,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                format = f
                                logFormatChange(f)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.qso_export_scope),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colorScheme.onSurfaceSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChipButton(
                        label = stringResource(
                            R.string.qso_export_chip_count,
                            stringResource(R.string.qso_export_scope_filtered),
                            filteredCount,
                        ),
                        selected = useFiltered,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            useFiltered = true
                            logScopeChange(true)
                        },
                    )
                    ChoiceChipButton(
                        label = stringResource(
                            R.string.qso_export_chip_count,
                            stringResource(R.string.qso_export_scope_all),
                            totalCount,
                        ),
                        selected = !useFiltered,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            useFiltered = false
                            logScopeChange(false)
                        },
                    )
                }
                // 两个范围条数不同 → 用强调色提示差异，避免"子集冒充全量"
                if (hasActiveFilters) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.qso_export_diff_warning, totalCount - filteredCount),
                        style = MiuixTheme.textStyles.footnote2,
                        color = colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.qso_export_filter_summary, summary),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colorScheme.onSurfaceSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // 最后确认文案：空 → 提示无可导出；筛选结果 → 明示匹配条数；
                    // 全部 + 有活跃筛选 → 警告不受筛选限制
                    text = if (count == 0) {
                        stringResource(R.string.qso_export_empty)
                    } else if (useFiltered) {
                        stringResource(R.string.qso_export_confirm_filtered, filteredCount)
                    } else if (hasActiveFilters) {
                        stringResource(R.string.qso_export_confirm_all_warning, totalCount)
                    } else {
                        stringResource(R.string.qso_log_count, totalCount)
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        count == 0 -> colorScheme.error
                        useFiltered || !hasActiveFilters -> colorScheme.onSurface
                        else -> colorScheme.error
                    },
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(
                        text = stringResource(R.string.qso_export_save),
                        onClick = { onSaveToFile(format, useFiltered) },
                        enabled = count > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.qso_export_share),
                        onClick = { onShare(format, useFiltered) },
                        enabled = count > 0,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
private fun ChoiceChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (selected) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColors(
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = colorScheme.onSurfaceSecondary,
            )
        },
    ) {
        Text(label, style = MiuixTheme.textStyles.footnote1)
    }
}

private fun formatLocalTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private fun formatLocalDate(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
