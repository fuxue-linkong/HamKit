package com.example.hamkit.ui.screen.ft8

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.qso.AdifExporter
import com.example.hamkit.data.qso.QsoExportFormat
import com.example.hamkit.data.qso.QsoExporter
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.QsoLogViewModel
import com.example.hamkit.ui.viewmodel.QsoTimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * QSO 通联日志（Material 3 风格）。
 *
 * 与 [QsoLogScreenMiuix] 行为完全等价：筛选（呼号前缀 / 频段 / 时间范围）、
 * 记录列表与空态、新增 / 编辑 / 删除（含确认）、ADIF-XML 的保存到文件与分享。
 * 文件导出链路（CreateDocument 往返时记住格式与范围、FileProvider 分享）逐字保持原逻辑。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QsoLogScreenMaterial(
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<QsoLogViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = 关闭；record.id == 0 → 新增
    var editingRecord by remember { mutableStateOf<Ft8QsoRecord?>(null) }
    var deletingRecord by remember { mutableStateOf<Ft8QsoRecord?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

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
            // 分享走 FileProvider：缓存目录文件不能直接以 file:// 暴露给外部应用
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

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qso_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    // 「添加」直接打开一张空白记录的编辑弹窗（id = 0 表示新增）
                    TextButton(
                        onClick = {
                            editingRecord = Ft8QsoRecord(
                                id = 0,
                                callsign = "",
                                grid = null,
                            )
                        }
                    ) {
                        Text(stringResource(R.string.qso_log_add))
                    }
                    TextButton(onClick = { showExportDialog = true }) {
                        Text(stringResource(R.string.qso_log_export))
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        )
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 边到边下 IME inset 需在 Compose 层消费：
                // 筛选呼号输入框位于列表顶部，键盘弹出后必须仍可见（HK-BUG-001 同类页面）
                .imePadding()
                .imeNestedScroll()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            )
        ) {
            // ── 筛选卡 ──
            item(key = "filter") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = uiState.filterCallsign,
                            onValueChange = { value ->
                                viewModel.setFilterCallsign(
                                    value.uppercase().filter { it.isLetterOrDigit() }.take(12)
                                )
                            },
                            label = { Text(stringResource(R.string.qso_log_filter_callsign)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        Spacer(Modifier.height(4.dp))
                        QsoDropdownFieldMaterial(
                            label = stringResource(R.string.qso_log_filter_band),
                            items = listOf(stringResource(R.string.qso_log_filter_all_band)) +
                                    Ft8Band.entries.map { it.displayName },
                            // 索引 0 表示「全部频段」，其余索引对应 Ft8Band.entries[index - 1]
                            selectedIndex = uiState.filterBand?.let { Ft8Band.entries.indexOf(it) + 1 } ?: 0,
                            onItemSelected = { index ->
                                viewModel.setFilterBand(if (index == 0) null else Ft8Band.entries[index - 1])
                            },
                        )
                        QsoDropdownFieldMaterial(
                            label = stringResource(R.string.qso_log_filter_time),
                            items = listOf(
                                stringResource(R.string.qso_log_time_all),
                                stringResource(R.string.qso_log_time_today),
                                stringResource(R.string.qso_log_time_7d),
                                stringResource(R.string.qso_log_time_30d),
                            ),
                            selectedIndex = uiState.timeRange.ordinal,
                            onItemSelected = { index ->
                                viewModel.setTimeRange(QsoTimeRange.entries[index])
                            },
                        )
                        TextButton(
                            onClick = { viewModel.clearFilters() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.qso_log_filter_clear))
                        }
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            // ── 记录列表 / 空态 ──
            if (uiState.records.isEmpty()) {
                item(key = "empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.qso_log_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(uiState.records, key = { it.id }) { record ->
                    QsoRecordCardMaterial(record = record, onClick = { editingRecord = record })
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    // ── 添加 / 编辑弹窗 ──
    editingRecord?.let { record ->
        QsoEditDialogMaterial(
            record = record,
            onDismiss = { editingRecord = null },
            onSave = { updated ->
                if (updated.id == 0L) viewModel.addRecord(updated) else viewModel.updateRecord(updated)
                editingRecord = null
            },
            onDelete = if (record.id == 0L) null else { { deletingRecord = record } },
        )
    }

    // ── 删除确认 ──
    deletingRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { deletingRecord = null },
            title = { Text(stringResource(R.string.qso_log_delete)) },
            text = { Text(stringResource(R.string.qso_log_delete_confirm, record.callsign)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRecord(record)
                        deletingRecord = null
                        editingRecord = null
                    }
                ) {
                    Text(stringResource(R.string.qso_log_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingRecord = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // ── 导出弹窗 ──
    if (showExportDialog) {
        QsoExportDialogMaterial(
            onDismiss = { showExportDialog = false },
            filteredCount = uiState.records.size,
            totalCount = uiState.totalCount,
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
                shareExport(format, useFiltered)
            },
        )
    }
}

/**
 * 记录卡片：呼号 + 完成标记 + 本地时间 + 频段/模式/频率 + 网格与 RST + 备注。
 * 整卡可点击进入编辑。
 */
@Composable
private fun QsoRecordCardMaterial(record: Ft8QsoRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.callsign,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (record.isComplete) {
                        stringResource(R.string.qso_log_complete)
                    } else {
                        stringResource(R.string.qso_log_incomplete)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    // 完成状态用底色胶囊区分：完成=主色，未完成=中性色
                    color = if (record.isComplete) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = if (record.isComplete) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                            },
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatLocalTimeMaterial(record.qsoTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${record.band.displayName} · ${record.mode} · " +
                        "${AdifExporter.formatMhz(record.freqHz)} MHz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 有网格、或任一 RST 不是缺省值 -99 时才输出这一行
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
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            record.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = comment,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 新增 / 编辑弹窗。
 *
 * 所有输入校验、默认值推导（切换频段时按 id==0 才回填频率）与 [QsoLogScreenMiuix] 内部实现一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QsoEditDialogMaterial(
    record: Ft8QsoRecord,
    onDismiss: () -> Unit,
    onSave: (Ft8QsoRecord) -> Unit,
    onDelete: (() -> Unit)?,
) {
    // 以 record.id 为 key：切换到另一条记录时重置表单草稿
    var callsign by remember(record.id) { mutableStateOf(record.callsign) }
    var grid by remember(record.id) { mutableStateOf(record.grid ?: "") }
    var reportSent by remember(record.id) { mutableStateOf(record.reportSent) }
    var reportReceived by remember(record.id) { mutableStateOf(record.reportReceived) }
    var band by remember(record.id) { mutableStateOf(record.band) }
    var freqMhz by remember(record.id) { mutableStateOf(AdifExporter.formatMhz(record.freqHz)) }
    var comment by remember(record.id) { mutableStateOf(record.comment ?: "") }
    var isComplete by remember(record.id) { mutableStateOf(record.isComplete) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (record.id == 0L) R.string.qso_log_add_title else R.string.qso_log_edit_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imeNestedScroll()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = callsign,
                        onValueChange = { value ->
                            callsign = value.uppercase().filter { it.isLetterOrDigit() }.take(12)
                        },
                        label = { Text(stringResource(R.string.qso_log_field_callsign)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    OutlinedTextField(
                        value = grid,
                        onValueChange = { value ->
                            grid = value.uppercase().filter { it.isLetterOrDigit() }.take(8)
                        },
                        label = { Text(stringResource(R.string.qso_log_field_grid)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reportSent,
                        onValueChange = { value ->
                            reportSent = value.uppercase()
                                .filter { it.isDigit() || it == '+' || it == '-' || it == 'R' }
                                .take(4)
                        },
                        label = { Text(stringResource(R.string.qso_log_field_rst_sent)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    OutlinedTextField(
                        value = reportReceived,
                        onValueChange = { value ->
                            reportReceived = value.uppercase()
                                .filter { it.isDigit() || it == '+' || it == '-' || it == 'R' }
                                .take(4)
                        },
                        label = { Text(stringResource(R.string.qso_log_field_rst_rcvd)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = freqMhz,
                        onValueChange = { value ->
                            freqMhz = value.filter { it.isDigit() || it == '.' }.take(10)
                        },
                        label = { Text(stringResource(R.string.qso_log_field_freq)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.qso_log_field_complete),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.Switch(
                            checked = isComplete,
                            onCheckedChange = { isComplete = it }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 频段：6 列 × 2 行紧凑选择（与 Miuix 版排布一致）
                Ft8Band.entries.chunked(6).forEach { rowBands ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowBands.forEach { b ->
                            QsoBandChipMaterial(
                                label = b.displayName,
                                selected = b == band,
                                onClick = {
                                    band = b
                                    // 仅新增记录时按所选频段回填频率，编辑已有记录不覆盖用户手填值
                                    if (record.id == 0L) freqMhz = AdifExporter.formatMhz(b.freqHz)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { value ->
                        if (value.length <= 60) comment = value
                    },
                    label = { Text(stringResource(R.string.qso_log_field_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 频率文本非法时退回原值，避免把记录改坏
                    val freqHz = freqMhz.toDoubleOrNull()?.times(1_000_000)?.toLong()
                        ?: record.freqHz
                    onSave(
                        record.copy(
                            callsign = callsign.trim().uppercase(),
                            grid = grid.trim().uppercase().ifEmpty { null },
                            reportSent = reportSent.ifBlank { "-99" },
                            reportReceived = reportReceived.ifBlank { "-99" },
                            band = band,
                            freqHz = freqHz,
                            comment = comment.trim().ifEmpty { null },
                            isComplete = isComplete,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.qso_log_save))
            }
        },
        dismissButton = {
            Row {
                // 编辑已有记录才提供删除入口；点删除先关掉编辑态再弹确认框
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text(
                            text = stringResource(R.string.qso_log_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

/**
 * 导出弹窗：格式（ADIF / XML）+ 范围（当前筛选 / 全部记录），可保存到文件或分享。
 * 没有任何可导出记录时两个动作都禁用。
 */
@Composable
private fun QsoExportDialogMaterial(
    onDismiss: () -> Unit,
    filteredCount: Int,
    totalCount: Int,
    onSaveToFile: (QsoExportFormat, Boolean) -> Unit,
    onShare: (QsoExportFormat, Boolean) -> Unit,
) {
    var format by remember { mutableStateOf(QsoExportFormat.ADIF) }
    var useFiltered by remember { mutableStateOf(true) }
    val count = if (useFiltered) filteredCount else totalCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qso_log_export)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.qso_export_format),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QsoExportFormat.entries.forEach { f ->
                        QsoChoiceChipMaterial(
                            label = if (f == QsoExportFormat.ADIF) "ADIF (.adi)" else "XML (.xml)",
                            selected = format == f,
                            modifier = Modifier.weight(1f),
                            onClick = { format = f },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.qso_export_scope),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QsoChoiceChipMaterial(
                        label = stringResource(R.string.qso_export_scope_filtered),
                        selected = useFiltered,
                        modifier = Modifier.weight(1f),
                        onClick = { useFiltered = true },
                    )
                    QsoChoiceChipMaterial(
                        label = stringResource(R.string.qso_export_scope_all),
                        selected = !useFiltered,
                        modifier = Modifier.weight(1f),
                        onClick = { useFiltered = false },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (count > 0) {
                        stringResource(R.string.qso_log_count, count)
                    } else {
                        stringResource(R.string.qso_export_empty)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (count > 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSaveToFile(format, useFiltered) },
                enabled = count > 0,
            ) {
                Text(stringResource(R.string.qso_export_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onShare(format, useFiltered) },
                enabled = count > 0,
            ) {
                Text(stringResource(R.string.qso_export_share))
            }
        },
    )
}

/** 频段选择胶囊：选中态用 primary 实心，未选中用 surfaceVariant 保持可见性 */
@Composable
private fun QsoBandChipMaterial(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        colors = if (selected) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 导出弹窗里的二选一胶囊（格式 / 范围），语义上是单选，故用 FilterChip */
@Composable
private fun QsoChoiceChipMaterial(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier,
    )
}

/**
 * 下拉选择行（Material 版）：用于筛选卡的频段 / 时间范围。
 *
 * 用 ExposedDropdownMenuBox 托管展开态与锚点定位，避免自绘 Box 锚点在弹窗内错位。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QsoDropdownFieldMaterial(
    label: String,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val safeIndex = if (items.isEmpty()) -1 else selectedIndex.coerceIn(0, items.lastIndex)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .clickable { expanded = true }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (safeIndex >= 0) items[safeIndex] else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEachIndexed { index, text ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        // 防御 items 与外部索引不同步导致的越界
                        if (index in items.indices) {
                            onItemSelected(index)
                        }
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun formatLocalTimeMaterial(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
