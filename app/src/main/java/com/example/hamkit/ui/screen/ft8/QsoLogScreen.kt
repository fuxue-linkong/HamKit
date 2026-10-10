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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.qso.AdifExporter
import com.example.hamkit.data.qso.QsoExportFormat
import com.example.hamkit.data.qso.QsoExporter
import com.example.hamkit.ui.appViewModel
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * QSO 通联日志：FT8 通联记录的列表 / 筛选 / 增删改 / ADIF-XML 导出。
 *
 * Miuix 单主题，对齐 Ft8SettingsScreen。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QsoLogScreen(
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<QsoLogViewModel>()
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
                            .clickable { showExportDialog = true }
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
                            title = stringResource(R.string.qso_log_filter_band),
                            items = listOf(stringResource(R.string.qso_log_filter_all_band)) +
                                Ft8Band.entries.map { it.displayName },
                            selectedIndex = uiState.filterBand?.let { Ft8Band.entries.indexOf(it) + 1 } ?: 0,
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
                            ),
                            selectedIndex = uiState.timeRange.ordinal,
                            onSelectedIndexChange = { index ->
                                viewModel.setTimeRange(QsoTimeRange.entries[index])
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
                    QsoRecordCard(record = record, onClick = { editingRecord = record })
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    // ── 添加 / 编辑弹窗 ──
    editingRecord?.let { record ->
        QsoEditDialog(
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
        WindowDialog(
            show = true,
            title = stringResource(R.string.qso_log_delete),
            onDismissRequest = { deletingRecord = null },
            content = {
                Column {
                    Text(stringResource(R.string.qso_log_delete_confirm, record.callsign))
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(
                            text = stringResource(android.R.string.cancel),
                            onClick = { deletingRecord = null },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(R.string.qso_log_delete),
                            onClick = {
                                viewModel.deleteRecord(record)
                                deletingRecord = null
                                editingRecord = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
    }

    // ── 导出弹窗 ──
    if (showExportDialog) {
        QsoExportDialog(
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
                            shape = RoundedCornerShape(6.dp),
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
                            callsign = value.uppercase().filter { it.isLetterOrDigit() }.take(12)
                        },
                        label = stringResource(R.string.qso_log_field_callsign),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    TextField(
                        value = grid,
                        onValueChange = { value ->
                            grid = value.uppercase().filter { it.isLetterOrDigit() }.take(8)
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
                        },
                        label = stringResource(R.string.qso_log_field_rst_sent),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    TextField(
                        value = reportReceived,
                        onValueChange = { value ->
                            reportReceived = value.uppercase().filter { it.isDigit() || it == '+' || it == '-' || it == 'R' }.take(4)
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
                            freqMhz = value.filter { it.isDigit() || it == '.' }.take(10)
                        },
                        label = stringResource(R.string.qso_log_field_freq),
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
                        Switch(checked = isComplete, onCheckedChange = { isComplete = it })
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
                                    if (record.id == 0L) freqMhz = AdifExporter.formatMhz(b.freqHz)
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
    onSaveToFile: (QsoExportFormat, Boolean) -> Unit,
    onShare: (QsoExportFormat, Boolean) -> Unit,
) {
    var format by remember { mutableStateOf(QsoExportFormat.ADIF) }
    var useFiltered by remember { mutableStateOf(true) }
    val count = if (useFiltered) filteredCount else totalCount

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
                            onClick = { format = f },
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
                        label = stringResource(R.string.qso_export_scope_filtered),
                        selected = useFiltered,
                        modifier = Modifier.weight(1f),
                        onClick = { useFiltered = true },
                    )
                    ChoiceChipButton(
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
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (count > 0) colorScheme.onSurfaceSecondary else colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
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
