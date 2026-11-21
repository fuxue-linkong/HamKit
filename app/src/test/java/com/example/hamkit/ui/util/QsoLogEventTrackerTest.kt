package com.example.hamkit.ui.util

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * [QsoLogEventTracker] 与 [QsoLogEvent] 单元测试（Robolectric）。
 *
 * 覆盖：
 * - 事件参数展开（toParams）
 * - Logcat 结构化输出格式（`event=xxx ... timestamp=...`）
 * - 持久化写入 SharedPreferences（JSON 数组、上限裁剪、flush 落盘）
 * - 会话统计：停留时长（注入固定时钟）、操作计数、修改次数计数器
 * - 未初始化时降级为仅 Logcat（不持久化）
 */
@RunWith(RobolectricTestRunner::class)
class QsoLogEventTrackerTest {

    /** 固定时钟：测试中手动推进 */
    private var fakeNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        ShadowLog.reset()
        // 重置单例内部状态：Robolectric 每个测试方法重建 Application，
        // 单例若持有上一个测试的 prefs 引用，持久化会写进"过期"实例
        resetTrackerState()
        QsoLogEventTracker.now = { fakeNow }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        QsoLogEventTracker.init(context)
    }

    /** 通过反射清空单例状态（追踪器为 object，无可见的测试钩子） */
    private fun resetTrackerState() {
        val clazz = QsoLogEventTracker::class.java
        fun field(name: String) = clazz.getDeclaredField(name).apply { isAccessible = true }
        field("prefs").set(QsoLogEventTracker, null)
        // pendingEvents 是 val（final 引用），只能原地清空，不能替换
        (field("pendingEvents").get(QsoLogEventTracker) as ArrayDeque<*>).clear()
        field("eventSeq").setLong(QsoLogEventTracker, 0L)
        field("lastFlushAt").setLong(QsoLogEventTracker, 0L)
        field("sessionEnterAt").set(QsoLogEventTracker, null)
        field("sessionActionCount").setInt(QsoLogEventTracker, 0)
        (field("changeCounters").get(QsoLogEventTracker) as MutableMap<*, *>).clear()
    }

    // ── toParams：事件参数展开 ──

    @Test
    fun `toParams expands event fields`() {
        val params = QsoLogEvent.FilterCallsignChanged("BG7S", 1).toParams()
        assertEquals(listOf("value" to "BG7S", "change_count" to "1"), params)
    }

    @Test
    fun `toParams omits null values`() {
        // band = null（全部频段）时不输出 band 键，只保留 change_count
        val params = QsoLogEvent.FilterBandChanged(null, 2).toParams()
        assertEquals(listOf("change_count" to "2"), params)
    }

    @Test
    fun `toParams keeps band when present`() {
        val params = QsoLogEvent.FilterBandChanged("20m", 1).toParams()
        assertEquals(listOf("band" to "20m", "change_count" to "1"), params)
    }

    @Test
    fun `toParams for screen enter carries default user id`() {
        val params = QsoLogEvent.ScreenEnter.toParams()
        assertEquals(listOf("user_id" to "default"), params)
    }

    @Test
    fun `toParams for filter cleared carries previous state`() {
        val params = QsoLogEvent.FilterCleared("BG7S", null, "TODAY").toParams()
        assertEquals(
            listOf("previous_callsign" to "BG7S", "previous_time_range" to "TODAY"),
            params,
        )
    }

    // ── log：Logcat 输出格式 ──

    @Test
    fun `log writes structured key-value line to logcat`() {
        QsoLogEventTracker.log(QsoLogEvent.RecordAddStarted)

        val lines = ShadowLog.getLogsForTag("QsoLog")
        assertEquals(1, lines.size)
        assertEquals(Log.INFO, lines[0].type)
        val expected = "event=record_add_started timestamp=$fakeNow"
        assertEquals(expected, lines[0].msg)
    }

    @Test
    fun `log line includes params in insertion order`() {
        QsoLogEventTracker.log(QsoLogEvent.RecordEditOpened(42L, isAdd = true))

        val line = ShadowLog.getLogsForTag("QsoLog").single().msg
        assertEquals(
            "event=record_edit_opened record_id=42 is_add=true timestamp=$fakeNow",
            line,
        )
    }

    // ── 持久化：flush 写入 SharedPreferences ──

    @Test
    fun `flush persists pending events as json array`() {
        QsoLogEventTracker.log(QsoLogEvent.RecordAddStarted)
        QsoLogEventTracker.log(QsoLogEvent.ExportOpened)
        QsoLogEventTracker.flush()

        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("qso_log_events", android.content.Context.MODE_PRIVATE)
        val stored = org.json.JSONArray(prefs.getString("events_json", "[]"))
        assertEquals(2, stored.length())
        assertEquals("record_add_started", stored.getJSONObject(0).getString("event"))
        assertEquals("export_opened", stored.getJSONObject(1).getString("event"))
        assertEquals(fakeNow, stored.getJSONObject(0).getLong("timestamp"))
    }

    @Test
    fun `flush is noop when nothing pending`() {
        QsoLogEventTracker.log(QsoLogEvent.RecordAddStarted)
        QsoLogEventTracker.flush()
        // 再次 flush（队列为空）不报错、不改变存储
        QsoLogEventTracker.flush()
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("qso_log_events", android.content.Context.MODE_PRIVATE)
        assertEquals(1, org.json.JSONArray(prefs.getString("events_json", "[]")).length())
    }

    // ── 会话：screen_enter / screen_duration ──

    @Test
    fun `logScreenEnter then endSession records duration and action count`() {
        QsoLogEventTracker.logScreenEnter() // fakeNow = T0

        fakeNow += 65_000 // 页面停留 65 秒
        // 会话内 3 次操作（不含 screen_enter / screen_duration 自身）
        QsoLogEventTracker.log(QsoLogEvent.RecordAddStarted)
        QsoLogEventTracker.log(QsoLogEvent.ExportOpened)
        QsoLogEventTracker.log(QsoLogEvent.RecordDeleted(7L))

        QsoLogEventTracker.endSession()

        val lines = ShadowLog.getLogsForTag("QsoLog")
        assertEquals(5, lines.size)
        val durationLine = lines.last().msg
        assertTrue(durationLine.contains("event=screen_duration"))
        assertTrue(durationLine.contains("duration_seconds=65"))
        assertTrue(durationLine.contains("action_count=3"))
    }

    @Test
    fun `endSession without session logs nothing`() {
        QsoLogEventTracker.endSession()
        assertTrue(ShadowLog.getLogsForTag("QsoLog").isEmpty())
    }

    @Test
    fun `endSession flushes duration event immediately`() {
        QsoLogEventTracker.logScreenEnter()
        QsoLogEventTracker.endSession()

        // endSession 强制落盘，无需手动 flush
        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("qso_log_events", android.content.Context.MODE_PRIVATE)
        val stored = org.json.JSONArray(prefs.getString("events_json", "[]"))
        assertEquals("screen_enter", stored.getJSONObject(0).getString("event"))
        assertEquals("screen_duration", stored.getJSONObject(1).getString("event"))
    }

    @Test
    fun `beginSession resets change counters`() {
        QsoLogEventTracker.logScreenEnter()
        assertEquals(1, QsoLogEventTracker.nextChangeCount("filter_callsign_changed"))
        assertEquals(2, QsoLogEventTracker.nextChangeCount("filter_callsign_changed"))

        // 重新进入页面：计数器清零
        QsoLogEventTracker.logScreenEnter()
        assertEquals(1, QsoLogEventTracker.nextChangeCount("filter_callsign_changed"))
    }

    // ── 降级：未初始化时仅 Logcat ──

    @Test
    fun `log works without init`() {
        // 模拟未初始化：通过反射清空 prefs 字段
        val field = QsoLogEventTracker::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(QsoLogEventTracker, null)

        QsoLogEventTracker.log(QsoLogEvent.RecordAddStarted)

        // Logcat 仍输出
        assertEquals(1, ShadowLog.getLogsForTag("QsoLog").size)
        // flush 无持久层引用，安全空操作
        QsoLogEventTracker.flush()
        // 恢复初始化，避免影响后续测试
        QsoLogEventTracker.init(ApplicationProvider.getApplicationContext())
    }

    // ── ViewModel 集成：埋点不干扰业务状态 ──

    @Test
    fun `event names follow snake_case convention`() {
        // 所有事件名统一小写下划线，便于后续按名聚合查询
        val names = listOf(
            QsoLogEvent.ScreenEnter,
            QsoLogEvent.ScreenDuration(10, 1),
            QsoLogEvent.FilterOpened,
            QsoLogEvent.FilterCallsignChanged("", 1),
            QsoLogEvent.FilterBandChanged(null, 1),
            QsoLogEvent.FilterTimeChanged("TODAY", 1),
            QsoLogEvent.FilterCleared("", null, "ALL"),
            QsoLogEvent.RecordAddStarted,
            QsoLogEvent.RecordEditOpened(0L, true),
            QsoLogEvent.RecordFieldChanged("callsign", 1),
            QsoLogEvent.RecordSaved("add", 0L),
            QsoLogEvent.RecordDeleteOpened(1L),
            QsoLogEvent.RecordDeleted(1L),
            QsoLogEvent.RecordDeleteCancelled,
            QsoLogEvent.ExportOpened,
            QsoLogEvent.ExportFormatChanged("ADIF"),
            QsoLogEvent.ExportScopeChanged("all", 10),
            QsoLogEvent.ExportSaved("ADIF", "filtered", 5),
            QsoLogEvent.ExportShared("XML", "all", 5),
        ).map { it.name }
        names.forEach { name ->
            assertTrue("事件名必须为小写下划线: $name", name == name.lowercase())
            assertFalse(name.contains(' '))
            assertNull(name.firstOrNull { it.isUpperCase() })
        }
        // 清单要求的 19 个事件节点全部存在且不重名
        assertEquals(19, names.distinct().size)
    }
}
