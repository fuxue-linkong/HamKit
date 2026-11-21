package com.example.hamkit.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.ft8.QsoRecordValidator
import com.example.hamkit.data.qso.QsoExportFormat
import com.example.hamkit.data.qso.QsoExporter
import com.example.hamkit.data.qso.QsoStore
import com.example.hamkit.ui.util.QsoLogEvent
import com.example.hamkit.ui.util.QsoLogEventTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** 呼号筛选匹配方式：前缀（输入"BG"命中 BG7SJU 等）/ 精确（全呼号相等） */
enum class QsoCallsignMatchMode {
    PREFIX, EXACT,
}

/** 时间范围筛选：四档预设 + 自定义区间（startMs..endMs 毫秒时间戳，闭区间） */
enum class QsoTimeRange {
    ALL, TODAY, LAST_7_DAYS, LAST_30_DAYS, CUSTOM,
}

data class QsoLogUiState(
    /** 筛选后的记录（时间倒序） */
    val records: List<Ft8QsoRecord> = emptyList(),
    /** 全部记录条数 */
    val totalCount: Int = 0,
    val filterCallsign: String = "",
    /** null = 全部频段 */
    val filterBand: Ft8Band? = null,
    val timeRange: QsoTimeRange = QsoTimeRange.ALL,
    /** 呼号匹配方式：前缀（默认）/ 精确 */
    val callsignMatchMode: QsoCallsignMatchMode = QsoCallsignMatchMode.PREFIX,
    /** 自定义时间范围（CUSTOM 档生效）；null = 未设置 */
    val customRange: Pair<Long, Long>? = null,
)

/**
 * 删除流程的待撤销状态：记录已从列表移除并进缓冲，10 秒内可撤销。
 *
 * [callsign] 供 Snackbar 文案"已删除 XX"；null 表示当前没有待撤销的删除。
 */
data class QsoPendingDelete(
    val bufferSeq: Long,
    val recordId: Long,
    val callsign: String,
)

/**
 * QSO 通联日志 ViewModel：筛选态 + 增删改 + 导出内容生成。
 *
 * 与 Ft8ViewModel 各自经 Room Flow 读同一 qso_database，无需共享实例。
 */
class QsoLogViewModel(application: Application) : AndroidViewModel(application) {

    private val qsoStore = QsoStore(application)

    private val filterCallsign = MutableStateFlow("")
    private val filterBand = MutableStateFlow<Ft8Band?>(null)
    private val timeRange = MutableStateFlow(QsoTimeRange.ALL)
    private val callsignMatchMode = MutableStateFlow(QsoCallsignMatchMode.PREFIX)

    /** 自定义时间范围（CUSTOM 档生效）；null = 未设置 */
    private val customRange = MutableStateFlow<Pair<Long, Long>?>(null)

    /**
     * 已删除记录缓冲：删除先进缓冲 10 秒，期间可撤销，超时才真正落库删除。
     * 过期回调在 viewModelScope 内触发（超时任务也在该作用域）。
     */
    private val deletedBuffer = DeletedRecordBuffer(
        scope = viewModelScope,
        onExpire = { record ->
            viewModelScope.launch { qsoStore.delete(record) }
            _pendingDelete.value = null
        },
    )

    /** 待撤销删除状态，UI 据此展示"已删除 + 撤销"Snackbar */
    private val _pendingDelete = MutableStateFlow<QsoPendingDelete?>(null)
    val pendingDelete: StateFlow<QsoPendingDelete?> = _pendingDelete.asStateFlow()

    /** 全量记录缓存，供"导出全部"使用（筛选在内存 combine 中进行） */
    private var allRecords: List<Ft8QsoRecord> = emptyList()

    /** 筛选态打包：combine 最多支持 5 流，先聚合筛选流再与记录流合并 */
    private data class FilterState(
        val callsign: String,
        val band: Ft8Band?,
        val range: QsoTimeRange,
        val matchMode: QsoCallsignMatchMode,
        val customRange: Pair<Long, Long>?,
    )

    val uiState: StateFlow<QsoLogUiState> =
        combine(
            filterCallsign,
            filterBand,
            timeRange,
            callsignMatchMode,
            customRange,
        ) { callsign, band, range, matchMode, custom ->
            FilterState(callsign, band, range, matchMode, custom)
        }.let { filterState ->
            combine(qsoStore.records, filterState) { records, state ->
                allRecords = records
                val filtered = records.filter { record ->
                    val query = state.callsign.trim().uppercase()
                    val callsignMatch = state.callsign.isBlank() || when (state.matchMode) {
                        QsoCallsignMatchMode.PREFIX -> record.callsign.startsWith(query)
                        QsoCallsignMatchMode.EXACT -> record.callsign == query
                    }
                    val bandMatch = state.band == null || record.band == state.band
                    val timeMatch = when (state.range) {
                        QsoTimeRange.ALL -> true
                        QsoTimeRange.TODAY -> record.qsoTime >= startOfToday()
                        QsoTimeRange.LAST_7_DAYS -> record.qsoTime >= daysAgo(7)
                        QsoTimeRange.LAST_30_DAYS -> record.qsoTime >= daysAgo(30)
                        QsoTimeRange.CUSTOM -> state.customRange?.let { (start, end) ->
                            record.qsoTime >= start && record.qsoTime <= end
                        } ?: true
                    }
                    callsignMatch && bandMatch && timeMatch
                }
                QsoLogUiState(
                    records = filtered,
                    totalCount = records.size,
                    filterCallsign = state.callsign,
                    filterBand = state.band,
                    timeRange = state.range,
                    callsignMatchMode = state.matchMode,
                    customRange = state.customRange,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QsoLogUiState(),
        )

    // ── 以下方法只追加埋点调用，业务逻辑保持原样 ──

    fun setFilterCallsign(value: String) {
        QsoLogEventTracker.log(
            QsoLogEvent.FilterCallsignChanged(value, QsoLogEventTracker.nextChangeCount("filter_callsign_changed"))
        )
        filterCallsign.value = value
    }

    fun setFilterBand(band: Ft8Band?) {
        QsoLogEventTracker.log(
            QsoLogEvent.FilterBandChanged(band?.displayName, QsoLogEventTracker.nextChangeCount("filter_band_changed"))
        )
        filterBand.value = band
    }

    fun setTimeRange(range: QsoTimeRange) {
        QsoLogEventTracker.log(
            QsoLogEvent.FilterTimeChanged(range.name, QsoLogEventTracker.nextChangeCount("filter_time_changed"))
        )
        timeRange.value = range
    }

    /**
     * 设置自定义时间范围并切换到 CUSTOM 档。
     * 约束 startMs <= endMs；不合法时忽略（UI 层对话框已保证顺序）。
     */
    fun setCustomDateRange(startMs: Long, endMs: Long) {
        if (startMs > endMs) return
        QsoLogEventTracker.log(
            QsoLogEvent.FilterTimeChanged(
                QsoTimeRange.CUSTOM.name,
                QsoLogEventTracker.nextChangeCount("filter_time_changed")
            )
        )
        customRange.value = startMs to endMs
        timeRange.value = QsoTimeRange.CUSTOM
    }

    /** 切换呼号匹配方式（前缀 / 精确） */
    fun setCallsignMatchMode(mode: QsoCallsignMatchMode) {
        QsoLogEventTracker.log(
            QsoLogEvent.FilterMatchModeChanged(mode.name, QsoLogEventTracker.nextChangeCount("filter_match_mode_changed"))
        )
        callsignMatchMode.value = mode
    }

    fun clearFilters() {
        QsoLogEventTracker.log(
            QsoLogEvent.FilterCleared(
                previousCallsign = filterCallsign.value,
                previousBand = filterBand.value?.displayName,
                previousTimeRange = timeRange.value.name,
            )
        )
        filterCallsign.value = ""
        filterBand.value = null
        timeRange.value = QsoTimeRange.ALL
        customRange.value = null
    }

    fun addRecord(record: Ft8QsoRecord) {
        // 落库守卫：即使绕过前端校验（如未来的 AI 工具 / 批量导入），空呼号与非法频率也不入库。
        // 校验失败仅记录埋点并静默丢弃，UI 层正常路径不会走到这里。
        if (QsoRecordValidator.validateRecord(record) is QsoRecordValidator.ValidationResult.Invalid) {
            QsoLogEventTracker.log(QsoLogEvent.RecordSaveBlocked)
            return
        }
        QsoLogEventTracker.log(QsoLogEvent.RecordSaved("add", record.id))
        viewModelScope.launch { qsoStore.insert(record) }
    }

    fun updateRecord(record: Ft8QsoRecord) {
        if (QsoRecordValidator.validateRecord(record) is QsoRecordValidator.ValidationResult.Invalid) {
            QsoLogEventTracker.log(QsoLogEvent.RecordSaveBlocked)
            return
        }
        QsoLogEventTracker.log(QsoLogEvent.RecordSaved("update", record.id))
        viewModelScope.launch { qsoStore.update(record) }
    }

    fun deleteRecord(record: Ft8QsoRecord) {
        QsoLogEventTracker.log(QsoLogEvent.RecordDeleted(record.id))
        viewModelScope.launch {
            qsoStore.delete(record)
            val seq = deletedBuffer.buffer(record)
            _pendingDelete.value = QsoPendingDelete(
                bufferSeq = seq,
                recordId = record.id,
                callsign = record.callsign,
            )
        }
    }

    /**
     * 撤销最近一次删除：把缓冲中的记录重新写回数据库。
     * 无待撤销条目（已超时/无删除）时静默返回。
     */
    fun undoDelete() {
        val pending = _pendingDelete.value ?: return
        val restored = deletedBuffer.restore(pending.bufferSeq) ?: return
        QsoLogEventTracker.log(QsoLogEvent.RecordDeleteUndone(restored.id))
        _pendingDelete.value = null
        viewModelScope.launch { qsoStore.insert(restored) }
    }

    /** 清除待撤销提示（Snackbar 自动超时消失时由 UI 调用，记录已在缓冲中等待落库删除） */
    fun dismissPendingDelete() {
        _pendingDelete.value = null
    }

    /** 测试辅助：当前缓冲中的记录数 */
    fun bufferedDeleteCount(): Int = deletedBuffer.bufferedRecords().size

    /** 生成导出内容；[useFiltered] 为 true 时仅导出当前筛选结果 */
    fun buildExport(format: QsoExportFormat, useFiltered: Boolean): String {
        val source = if (useFiltered) uiState.value.records else allRecords
        return QsoExporter.export(source, format)
    }

    /** 导出记录条数（随导出范围联动，供空记录提示） */
    fun exportCount(useFiltered: Boolean): Int =
        if (useFiltered) uiState.value.records.size else allRecords.size

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun daysAgo(days: Long): Long =
        System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
}
