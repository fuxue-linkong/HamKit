package com.example.hamkit.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.qso.QsoExportFormat
import com.example.hamkit.data.qso.QsoExporter
import com.example.hamkit.data.qso.QsoStore
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

/** 时间范围筛选（四档预设，自定义区间范围外） */
enum class QsoTimeRange {
    ALL, TODAY, LAST_7_DAYS, LAST_30_DAYS,
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

    /** 全量记录缓存，供"导出全部"使用（筛选在内存 combine 中进行） */
    private var allRecords: List<Ft8QsoRecord> = emptyList()

    val uiState: StateFlow<QsoLogUiState> =
        combine(qsoStore.records, filterCallsign, filterBand, timeRange) {
                records, callsign, band, range ->
            allRecords = records
            val filtered = records.filter { record ->
                val callsignMatch = callsign.isBlank() ||
                    record.callsign.startsWith(callsign.trim().uppercase())
                val bandMatch = band == null || record.band == band
                val timeMatch = when (range) {
                    QsoTimeRange.ALL -> true
                    QsoTimeRange.TODAY -> record.qsoTime >= startOfToday()
                    QsoTimeRange.LAST_7_DAYS -> record.qsoTime >= daysAgo(7)
                    QsoTimeRange.LAST_30_DAYS -> record.qsoTime >= daysAgo(30)
                }
                callsignMatch && bandMatch && timeMatch
            }
            QsoLogUiState(
                records = filtered,
                totalCount = records.size,
                filterCallsign = callsign,
                filterBand = band,
                timeRange = range,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QsoLogUiState(),
        )

    fun setFilterCallsign(value: String) {
        filterCallsign.value = value
    }

    fun setFilterBand(band: Ft8Band?) {
        filterBand.value = band
    }

    fun setTimeRange(range: QsoTimeRange) {
        timeRange.value = range
    }

    fun clearFilters() {
        filterCallsign.value = ""
        filterBand.value = null
        timeRange.value = QsoTimeRange.ALL
    }

    fun addRecord(record: Ft8QsoRecord) {
        viewModelScope.launch { qsoStore.insert(record) }
    }

    fun updateRecord(record: Ft8QsoRecord) {
        viewModelScope.launch { qsoStore.update(record) }
    }

    fun deleteRecord(record: Ft8QsoRecord) {
        viewModelScope.launch { qsoStore.delete(record) }
    }

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
