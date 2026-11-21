package com.example.hamkit.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.qso.QsoDatabase
import com.example.hamkit.ui.util.QsoLogEventTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.time.LocalDate
import java.time.ZoneId

/**
 * [QsoLogViewModel] 扩展筛选逻辑测试（Robolectric，真实 Room 数据库）。
 *
 * 覆盖：
 * - 自定义日期范围筛选（含边界：当日 00:00 起 / 当日 23:59:59.999 止）
 * - 呼号精确匹配 vs 前缀匹配
 * - clearFilters 重置自定义范围
 */
@RunWith(RobolectricTestRunner::class)
class QsoLogViewModelFilterTest {

    private lateinit var viewModel: QsoLogViewModel
    private lateinit var db: QsoDatabase

    private val zone = ZoneId.systemDefault()

    private fun atDate(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 60 * 60 * 1000 // 当日中午

    private val today: LocalDate = LocalDate.now()

    private suspend fun seed() {
        val store = com.example.hamkit.data.qso.QsoStore(
            ApplicationProvider.getApplicationContext()
        )
        // 用 insert 直接落库（绕过 viewModel 的异步 launch，保证测试时序）
        listOf(
            Ft8QsoRecord(
                id = 0, callsign = "BG7SJU", grid = null,
                band = Ft8Band.BAND_20M, qsoTime = atDate(today.minusDays(1)),
            ),
            Ft8QsoRecord(
                id = 0, callsign = "BG3JJG", grid = null,
                band = Ft8Band.BAND_20M, qsoTime = atDate(today.minusDays(10)),
            ),
            Ft8QsoRecord(
                id = 0, callsign = "BH7X", grid = null,
                band = Ft8Band.BAND_20M, qsoTime = atDate(today.minusDays(40)),
            ),
        ).forEach { store.insert(it) }
    }

    @Before
    fun setUp() {
        ShadowLog.reset()
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("qso_log_events", Context.MODE_PRIVATE)
            .edit().clear().commit()
        QsoLogEventTracker.now = { System.currentTimeMillis() }
        QsoLogEventTracker.init(context)
        // 换成本次测试专用的内存库（与 QsoLogViewModelTrackingTest 同模式）
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, QsoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        QsoDatabaseSwap.replace(QsoDatabase::class.java, db)
        viewModel = QsoLogViewModel(context)
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        db.close()
        QsoDatabaseSwap.replace(QsoDatabase::class.java, null)
        ShadowLog.reset()
    }

    /** 反射替换 Room 单例 INSTANCE（与 CWProgressStoreTest 同模式） */
    private object QsoDatabaseSwap {
        fun replace(clazz: Class<*>, instance: Any?) {
            clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, instance)
        }
    }

    /** 挂后台订阅者保持 uiState 管线活跃，轮询等待条件满足后返回当前状态 */
    private fun awaitState(
        timeoutMs: Long = 5_000,
        predicate: (QsoLogUiState) -> Boolean,
    ): QsoLogUiState {
        val scope = CoroutineScope(Job())
        val job = viewModel.uiState.launchIn(scope)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            val looper = android.os.Looper.getMainLooper()
            while (!predicate(viewModel.uiState.value)) {
                org.robolectric.Shadows.shadowOf(looper).idle()
                if (System.currentTimeMillis() > deadline) {
                    org.junit.Assert.fail(
                        "uiState 未在 ${timeoutMs}ms 内满足条件: ${viewModel.uiState.value}"
                    )
                }
                Thread.sleep(20)
            }
            return viewModel.uiState.value
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    // ── 自定义日期范围 ──

    @Test
    fun `custom range filters by inclusive bounds`() = runBlocking {
        // 只含 -1 天与 -10 天（-40 天在范围外）
        val start = atDate(today.minusDays(10)) - 12 * 60 * 60 * 1000 // -10 天 00:00
        val end = atDate(today.minusDays(1)) + 12 * 60 * 60 * 1000 - 1 // -1 天 23:59:59.999
        viewModel.setCustomDateRange(start, end)

        val state = awaitState { it.timeRange == QsoTimeRange.CUSTOM && it.records.size == 2 }
        assertEquals(setOf("BG7SJU", "BG3JJG"), state.records.map { it.callsign }.toSet())
        assertEquals(start, state.customRange?.first)
        assertEquals(end, state.customRange?.second)
    }

    @Test
    fun `custom range with invalid order is ignored`() {
        viewModel.setCustomDateRange(200L, 100L)
        // 非法区间不生效，仍为 ALL
        assertEquals(QsoTimeRange.ALL, viewModel.uiState.value.timeRange)
        assertNull(viewModel.uiState.value.customRange)
    }

    @Test
    fun `clearFilters resets custom range`() = runBlocking {
        viewModel.setCustomDateRange(atDate(today.minusDays(1)), atDate(today))
        awaitState { it.timeRange == QsoTimeRange.CUSTOM }

        viewModel.clearFilters()
        val state = awaitState { it.timeRange == QsoTimeRange.ALL && it.customRange == null }

        assertEquals(QsoTimeRange.ALL, state.timeRange)
        assertNull(state.customRange)
        assertEquals(3, state.records.size)
    }

    @Test
    fun `switching back to preset keeps filter working after custom`() = runBlocking {
        viewModel.setCustomDateRange(atDate(today.minusDays(1)), atDate(today))
        awaitState { it.timeRange == QsoTimeRange.CUSTOM }

        viewModel.setTimeRange(QsoTimeRange.LAST_30_DAYS)
        val state = awaitState { it.timeRange == QsoTimeRange.LAST_30_DAYS && it.records.size == 2 }
        // -40 天的 BH7X 在近 30 天之外
        assertEquals(setOf("BG7SJU", "BG3JJG"), state.records.map { it.callsign }.toSet())
    }

    // ── 呼号匹配方式 ──

    @Test
    fun `prefix match is default behavior`() = runBlocking {
        viewModel.setFilterCallsign("BG")
        val state = awaitState { it.callsignMatchMode == QsoCallsignMatchMode.PREFIX && it.records.size == 2 }
        assertEquals(setOf("BG7SJU", "BG3JJG"), state.records.map { it.callsign }.toSet())
    }

    @Test
    fun `exact match only shows full callsign equality`() = runBlocking {
        viewModel.setCallsignMatchMode(QsoCallsignMatchMode.EXACT)
        viewModel.setFilterCallsign("BG7S") // 前缀能命中 BG7SJU，精确则不行
        val state = awaitState { it.callsignMatchMode == QsoCallsignMatchMode.EXACT && it.records.isEmpty() }
        assertTrue(state.records.isEmpty())

        viewModel.setFilterCallsign("BG7SJU")
        val exact = awaitState { it.records.size == 1 }
        assertEquals("BG7SJU", exact.records.single().callsign)
    }

    @Test
    fun `switching mode back to prefix restores prefix behavior`() = runBlocking {
        viewModel.setCallsignMatchMode(QsoCallsignMatchMode.EXACT)
        viewModel.setFilterCallsign("BG7S")
        awaitState { it.records.isEmpty() }

        viewModel.setCallsignMatchMode(QsoCallsignMatchMode.PREFIX)
        val state = awaitState { it.records.size == 1 }
        assertEquals("BG7SJU", state.records.single().callsign)
    }

    @Test
    fun `exact match is case-insensitive after uppercase trim`() = runBlocking {
        // 输入框层已 uppercase，这里验证 ViewModel 侧 trim + uppercase 语义
        viewModel.setCallsignMatchMode(QsoCallsignMatchMode.EXACT)
        viewModel.setFilterCallsign("bg7sju")
        val state = awaitState { it.callsignMatchMode == QsoCallsignMatchMode.EXACT && it.records.size == 1 }
        assertEquals("BG7SJU", state.records.single().callsign)
    }

    @Test
    fun `blank callsign shows all records in both modes`() = runBlocking {
        viewModel.setCallsignMatchMode(QsoCallsignMatchMode.EXACT)
        val state = awaitState { it.callsignMatchMode == QsoCallsignMatchMode.EXACT && it.records.size == 3 }
        assertFalse(state.records.isEmpty())
    }
}
