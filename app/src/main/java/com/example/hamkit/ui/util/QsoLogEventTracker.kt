package com.example.hamkit.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * QSO 通联记录页面的用户操作埋点事件。
 *
 * 目的：用户反馈"操作逻辑体验差"，先收集行为数据诊断。事件覆盖进入/离开页面、
 * 筛选操作、记录增删改、导出全链路，完整埋点清单见 docs/QSO_LOG_EVENTS_TRACKING.md。
 *
 * 命名与埋点位置对应关系：
 * - `screen_enter` / `screen_duration`：页面首次加载与离开（QsoLogScreen）
 * - `filter_*`：筛选卡内的输入与下拉（ViewModel 筛选方法 + 展开回调）
 * - `record_*`：编辑对话框与删除确认（UI 状态变化 + ViewModel 增删改）
 * - `export_*`：导出对话框与导出动作（UI 回调）
 */
sealed class QsoLogEvent(
    /** 事件名，写入日志与持久化存储（下划线小写，便于后续查询聚合） */
    val name: String,
) {
    /** 进入页面 */
    data object ScreenEnter : QsoLogEvent("screen_enter")

    /** 离开页面，带总停留秒数与本次会话内的操作计数 */
    data class ScreenDuration(val durationSeconds: Long, val actionCount: Int) :
        QsoLogEvent("screen_duration")

    /** 展开筛选下拉（频段 / 时间范围） */
    data object FilterOpened : QsoLogEvent("filter_opened")

    /** 修改呼号筛选，带当前值与本次会话内的修改次数 */
    data class FilterCallsignChanged(val value: String, val changeCount: Int) :
        QsoLogEvent("filter_callsign_changed")

    /** 修改频段筛选，null 表示"全部频段" */
    data class FilterBandChanged(val band: String?, val changeCount: Int) :
        QsoLogEvent("filter_band_changed")

    /** 修改时间范围筛选 */
    data class FilterTimeChanged(val range: String, val changeCount: Int) :
        QsoLogEvent("filter_time_changed")

    /** 切换呼号匹配方式（前缀 / 精确） */
    data class FilterMatchModeChanged(val mode: String, val changeCount: Int) :
        QsoLogEvent("filter_match_mode_changed")

    /** 清除全部筛选，带清除前的筛选状态 */
    data class FilterCleared(
        val previousCallsign: String,
        val previousBand: String?,
        val previousTimeRange: String,
    ) : QsoLogEvent("filter_cleared")

    /** 点击顶栏"新增" */
    data object RecordAddStarted : QsoLogEvent("record_add_started")

    /** 打开编辑对话框（点击记录卡或新增），isAdd 区分两种入口 */
    data class RecordEditOpened(val recordId: Long, val isAdd: Boolean) :
        QsoLogEvent("record_edit_opened")

    /** 编辑对话框内修改某个字段，带字段名与本次会话内的修改次数 */
    data class RecordFieldChanged(val field: String, val changeCount: Int) :
        QsoLogEvent("record_field_changed")

    /** 保存记录（新增或更新），在 ViewModel 增/改方法入口记录 */
    data class RecordSaved(val operation: String, val recordId: Long) :
        QsoLogEvent("record_saved")

    /** 保存被校验拦截（必填字段缺失 / 频率非法），对话框保持打开 */
    data object RecordSaveBlocked : QsoLogEvent("record_save_blocked")

    /** 编辑对话框内通过校验、即将提交保存（ViewModel 层的 record_saved 不重复记录） */
    data object RecordSavedFromDialog : QsoLogEvent("record_saved_from_dialog")

    /** 打开删除确认对话框 */
    data class RecordDeleteOpened(val recordId: Long) : QsoLogEvent("record_delete_opened")

    /** 确认删除，在 ViewModel 删除方法入口记录 */
    data class RecordDeleted(val recordId: Long) : QsoLogEvent("record_deleted")

    /** 关闭删除确认对话框但未删除 */
    data object RecordDeleteCancelled : QsoLogEvent("record_delete_cancelled")

    /** 撤销删除：点 Snackbar"撤销"把记录写回数据库 */
    data class RecordDeleteUndone(val recordId: Long) : QsoLogEvent("record_delete_undone")

    /** 打开导出对话框 */
    data object ExportOpened : QsoLogEvent("export_opened")

    /** 切换导出格式（ADIF / XML） */
    data class ExportFormatChanged(val format: String) : QsoLogEvent("export_format_changed")

    /** 切换导出范围（筛选结果 / 全部），带对应条数 */
    data class ExportScopeChanged(val scope: String, val recordCount: Int) :
        QsoLogEvent("export_scope_changed")

    /** 导出文件写入完成 */
    data class ExportSaved(val format: String, val scope: String, val recordCount: Int) :
        QsoLogEvent("export_saved")

    /** 触发系统分享 */
    data class ExportShared(val format: String, val scope: String, val recordCount: Int) :
        QsoLogEvent("export_shared")

    /**
     * 展开成 key-value 键值对。值为 null 的键不输出，保证日志行简洁可读。
     */
    fun toParams(): List<Pair<String, String>> = when (this) {
        is ScreenEnter -> listOf("user_id" to "default")
        is ScreenDuration -> listOf(
            "duration_seconds" to durationSeconds.toString(),
            "action_count" to actionCount.toString(),
        )
        is FilterOpened -> emptyList()
        is FilterCallsignChanged -> listOf(
            "value" to value,
            "change_count" to changeCount.toString(),
        )
        is FilterBandChanged -> listOfNotNull(
            band?.let { "band" to it },
            "change_count" to changeCount.toString(),
        )
        is FilterTimeChanged -> listOf(
            "range" to range,
            "change_count" to changeCount.toString(),
        )
        is FilterMatchModeChanged -> listOf(
            "mode" to mode,
            "change_count" to changeCount.toString(),
        )
        is FilterCleared -> listOfNotNull(
            "previous_callsign" to previousCallsign,
            previousBand?.let { "previous_band" to it },
            "previous_time_range" to previousTimeRange,
        )
        is RecordAddStarted -> emptyList()
        is RecordEditOpened -> listOf(
            "record_id" to recordId.toString(),
            "is_add" to isAdd.toString(),
        )
        is RecordFieldChanged -> listOf(
            "field" to field,
            "change_count" to changeCount.toString(),
        )
        is RecordSaved -> listOf(
            "operation" to operation,
            "record_id" to recordId.toString(),
        )
        is RecordSaveBlocked -> emptyList()
        is RecordSavedFromDialog -> emptyList()
        is RecordDeleteOpened -> listOf("record_id" to recordId.toString())
        is RecordDeleted -> listOf("record_id" to recordId.toString())
        is RecordDeleteCancelled -> emptyList()
        is RecordDeleteUndone -> listOf("record_id" to recordId.toString())
        is ExportOpened -> emptyList()
        is ExportFormatChanged -> listOf("format" to format)
        is ExportScopeChanged -> listOf(
            "scope" to scope,
            "record_count" to recordCount.toString(),
        )
        is ExportSaved -> listOf(
            "format" to format,
            "scope" to scope,
            "record_count" to recordCount.toString(),
        )
        is ExportShared -> listOf(
            "format" to format,
            "scope" to scope,
            "record_count" to recordCount.toString(),
        )
    }
}

/**
 * QSO 通联记录页面的埋点追踪器。
 *
 * 职责：
 * 1. 记录事件：Logcat 结构化输出（`adb logcat -s QsoLog` 可观察）；
 * 2. 持久化：SharedPreferences 内 JSON 数组（500 条上限，超出丢最旧），便于后续整包查询；
 * 3. 非阻塞：主线程只做内存操作，写盘经 SharedPreferences.apply() 异步完成；
 * 4. 防抖：持久化合并到每秒一次，高频事件（TextField onChange）不触发同步 I/O；
 * 5. 会话统计：[beginSession] / [endSession] 维护页面停留时长与操作计数，
 *    并为"修改次数"类参数提供自增计数器（进入页面时自动清零）。
 *
 * 页面宿主（MainActivity）初始化一次；未初始化时降级为仅 Logcat 输出。
 * 单元测试可通过 [init] 注入内存 SharedPreferences、[now] 注入固定时钟。
 */
object QsoLogEventTracker {

    private const val LOG_TAG = "QsoLog"
    private const val PREFS_NAME = "qso_log_events"
    private const val PREF_KEY_EVENTS = "events_json"
    private const val PREF_KEY_EVENT_SEQ = "event_seq"
    private const val MAX_PERSISTED_EVENTS = 500
    private const val FLUSH_INTERVAL_MS = 1_000L

    /** SharedPreferences 引用；null 表示尚未初始化（只输出 Logcat，不持久化） */
    @Volatile
    private var prefs: SharedPreferences? = null

    /** 事件自增序号（init 时从持久层续接），用于后续查询时排序去重 */
    private var eventSeq = 0L

    /** 内存中的待写事件队列；调用线程写、flush 读，用同步块保护 */
    private val pendingEvents = ArrayDeque<JSONObject>()

    /** 最近一次持久化时间，用于防抖 */
    private var lastFlushAt = 0L

    /** 当前页面会话的进入时间戳；null 表示无活动会话 */
    private var sessionEnterAt: Long? = null

    /** 当前会话内的用户操作计数（screen_enter/duration 自身不计入） */
    private var sessionActionCount = 0

    /** "修改次数"类参数的计数器，键为事件名，进入页面时清零 */
    private val changeCounters = HashMap<String, Int>()

    /**
     * 时钟函数，可注入固定值用于测试停留时长计算。
     */
    @Volatile
    var now: () -> Long = { System.currentTimeMillis() }

    /**
     * 初始化持久化存储。重复调用无副作用。
     * 未初始化时追踪器降级为仅 Logcat 输出（单元测试外的兜底路径）。
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                val target = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                eventSeq = target.getLong(PREF_KEY_EVENT_SEQ, 0L)
                prefs = target
            }
        }
    }

    /**
     * 进入页面：重置会话统计（停留时长起点、操作计数、修改次数计数器）。
     * 与 [logScreenEnter] 配套使用；重复调用视为重新进入，同样重置。
     */
    fun beginSession() {
        synchronized(this) {
            sessionEnterAt = now()
            sessionActionCount = 0
            changeCounters.clear()
        }
    }

    /**
     * 离开页面：记录 [QsoLogEvent.ScreenDuration]（停留秒数 + 操作计数）并立即落盘，
     * 保证进程被杀前事件不丢。无活动会话时不记录任何事件。
     */
    fun endSession() {
        val enterAt = synchronized(this) {
            val value = sessionEnterAt ?: return
            sessionEnterAt = null
            value
        }
        val durationSeconds = ((now() - enterAt).coerceAtLeast(0)) / 1000
        log(QsoLogEvent.ScreenDuration(durationSeconds, sessionActionCount))
        flush()
    }

    /** 便捷方法：进入页面（beginSession + 记录 screen_enter） */
    fun logScreenEnter() {
        beginSession()
        log(QsoLogEvent.ScreenEnter)
    }

    /** "修改次数"类参数取值：自增并返回，键建议用事件名 */
    fun nextChangeCount(key: String): Int = synchronized(this) {
        val next = (changeCounters[key] ?: 0) + 1
        changeCounters[key] = next
        next
    }

    /**
     * 记录一个事件：立即输出 Logcat，事件入队等待防抖持久化。
     * 主线程调用安全（无同步 I/O）。
     */
    fun log(event: QsoLogEvent) {
        val timestamp = now()
        if (event !is QsoLogEvent.ScreenEnter && event !is QsoLogEvent.ScreenDuration) {
            synchronized(this) { sessionActionCount++ }
        }
        val params = event.toParams()
        val line = buildString {
            append("event=").append(event.name)
            for ((key, value) in params) {
                append(' ').append(key).append('=').append(value)
            }
            append(" timestamp=").append(timestamp)
        }
        Log.i(LOG_TAG, line)

        enqueue(event, timestamp)
        maybeFlush()
    }

    /**
     * 把内存队列写入持久化存储（SharedPreferences JSON 数组）。
     * 防抖触发或页面离开时调用；未初始化持久层时为空操作。
     */
    fun flush() {
        val target = prefs ?: return
        synchronized(this) {
            if (pendingEvents.isEmpty()) return
            val stored = JSONArray(
                target.getString(PREF_KEY_EVENTS, "[]") ?: "[]"
            )
            for (event in pendingEvents) stored.put(event)
            while (stored.length() > MAX_PERSISTED_EVENTS) stored.remove(0)
            target.edit()
                .putString(PREF_KEY_EVENTS, stored.toString())
                .putLong(PREF_KEY_EVENT_SEQ, eventSeq)
                .apply()
            pendingEvents.clear()
            lastFlushAt = now()
        }
    }

    /** 事件入队；每个事件带自增序号，便于后续排序去重 */
    private fun enqueue(event: QsoLogEvent, timestamp: Long) {
        val json = JSONObject()
        synchronized(this) {
            eventSeq++
            json.put("seq", eventSeq)
        }
        json.put("event", event.name)
        json.put("timestamp", timestamp)
        for ((key, value) in event.toParams()) {
            json.put(key, value)
        }
        synchronized(this) { pendingEvents.addLast(json) }
    }

    /** 防抖：距上次持久化超过 [FLUSH_INTERVAL_MS] 才真正写盘 */
    private fun maybeFlush() {
        if (now() - lastFlushAt >= FLUSH_INTERVAL_MS) flush()
    }
}
