package com.example.hamkit.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.qso.QsoExportFormat
import com.example.hamkit.ui.util.QsoLogEventTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.launchIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * [QsoLogViewModel] 埋点集成测试（Robolectric，真实 Room 数据库）。
 *
 * 验证埋点调用不影响现有业务逻辑：
 * - 筛选方法仍正确更新 uiState
 * - 增删改仍正确落到数据库
 * - 同时产生对应的埋点事件（Logcat 可观察）
 *
 * 关键点：
 * - uiState 是 WhileSubscribed 的 StateFlow，必须先挂一个后台订阅者，
 *   上游 combine 管线才会启动；
 * - Robolectric 主 looper 不自动执行队列 runnable（viewModelScope 与
 *   Room → stateIn 管线都经它），轮询时须反复 idle。
 */
@RunWith(RobolectricTestRunner::class)
class QsoLogViewModelTrackingTest {

    private lateinit var viewModel: QsoLogViewModel

    private val sample = Ft8QsoRecord(
        id = 0L,
        callsign = "BG7S",
        grid = null,
        band = Ft8Band.BAND_20M,
        qsoTime = System.currentTimeMillis(),
    )

    @Before
    fun setUp() {
        ShadowLog.reset()
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("qso_log_events", Context.MODE_PRIVATE)
            .edit().clear().commit()
        QsoLogEventTracker.now = { System.currentTimeMillis() }
        QsoLogEventTracker.init(context)
        // 静态单例跨测试存活会把上个测试的记录带进来：换成本次测试专用的内存库
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.hamkit.data.qso.QsoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        QsoDatabaseSwap.replace(com.example.hamkit.data.qso.QsoDatabase::class.java, db)
        viewModel = QsoLogViewModel(context)
    }

    @After
    fun tearDown() {
        db.close()
        QsoDatabaseSwap.replace(com.example.hamkit.data.qso.QsoDatabase::class.java, null)
        ShadowLog.reset()
    }

    private lateinit var db: com.example.hamkit.data.qso.QsoDatabase

    /** 反射替换 Room 单例 INSTANCE（与 CWProgressStoreTest 同模式） */
    private object QsoDatabaseSwap {
        fun replace(clazz: Class<*>, instance: Any?) {
            clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, instance)
        }
    }

    /**
     * 挂一个后台订阅者保持 uiState 管线活跃，然后执行 [block]。
     * 返回前取消订阅。返回值须在 [block] 内同步断言。
     */
    private fun withUiStateCollector(block: () -> Unit) {
        val scope = CoroutineScope(Job())
        val job = viewModel.uiState.launchIn(scope)
        try {
            // 等订阅真正建立（WhileSubscribed 需要至少一个收集者）
            val looper = android.os.Looper.getMainLooper()
            val deadline = System.currentTimeMillis() + 5_000
            while (!job.isActive && System.currentTimeMillis() < deadline) Thread.sleep(10)
            block()
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    /** 轮询等待 uiState 满足条件（含主 looper idle，驱动 Room → stateIn 管线） */
    private fun awaitState(
        timeoutMs: Long = 5_000,
        predicate: (QsoLogUiState) -> Boolean,
    ): QsoLogUiState {
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
    }

    private fun loggedEvents(): List<String> =
        ShadowLog.getLogsForTag("QsoLog").mapNotNull { line ->
            Regex("event=(\\w+)").find(line.msg)?.groupValues?.get(1)
        }

    @Test
    fun `setFilterCallsign updates state and logs event`() = runBlocking {
        withUiStateCollector {
            viewModel.setFilterCallsign("BG7S")
            val state = awaitState { it.filterCallsign == "BG7S" }

            assertEquals("BG7S", state.filterCallsign)
            assertTrue("filter_callsign_changed" in loggedEvents())
        }
    }

    @Test
    fun `setFilterBand updates state and logs event`() = runBlocking {
        withUiStateCollector {
            viewModel.setFilterBand(Ft8Band.BAND_20M)
            val state = awaitState { it.filterBand == Ft8Band.BAND_20M }

            assertEquals(Ft8Band.BAND_20M, state.filterBand)
            assertTrue("filter_band_changed" in loggedEvents())
        }
    }

    @Test
    fun `setTimeRange updates state and logs event`() = runBlocking {
        withUiStateCollector {
            viewModel.setTimeRange(QsoTimeRange.TODAY)
            val state = awaitState { it.timeRange == QsoTimeRange.TODAY }

            assertEquals(QsoTimeRange.TODAY, state.timeRange)
            assertTrue("filter_time_changed" in loggedEvents())
        }
    }

    @Test
    fun `clearFilters resets state and logs previous state`() = runBlocking {
        withUiStateCollector {
            viewModel.setFilterCallsign("BG7S")
            viewModel.setFilterBand(Ft8Band.BAND_20M)
            awaitState { it.filterCallsign == "BG7S" && it.filterBand != null }
            ShadowLog.reset()

            viewModel.clearFilters()
            val state = awaitState { it.filterCallsign.isEmpty() && it.filterBand == null }

            assertEquals("", state.filterCallsign)
            assertNull(state.filterBand)
            assertEquals(QsoTimeRange.ALL, state.timeRange)
            assertTrue("filter_cleared" in loggedEvents())
        }
    }

    @Test
    fun `addRecord logs record_saved with add operation`() = runBlocking {
        withUiStateCollector {
            viewModel.addRecord(sample)
            val state = awaitState { it.totalCount > 0 }

            assertEquals(1, state.totalCount)
            assertEquals(1, loggedEvents().count { it == "record_saved" })
        }
    }

    @Test
    fun `updateRecord logs record_saved with update operation`() = runBlocking {
        withUiStateCollector {
            viewModel.addRecord(sample)
            val inserted = awaitState { it.records.isNotEmpty() }.records.first()
            ShadowLog.reset()

            viewModel.updateRecord(inserted.copy(callsign = "BH7X"))
            val state = awaitState { it.records.firstOrNull()?.callsign == "BH7X" }

            assertEquals("BH7X", state.records.first().callsign)
            assertTrue("record_saved" in loggedEvents())
        }
    }

    @Test
    fun `deleteRecord logs record_deleted`() = runBlocking {
        withUiStateCollector {
            viewModel.addRecord(sample)
            val inserted = awaitState { it.records.isNotEmpty() }.records.first()
            ShadowLog.reset()

            viewModel.deleteRecord(inserted)
            val state = awaitState { it.totalCount == 0 }

            assertEquals(0, state.totalCount)
            assertEquals(listOf("record_deleted"), loggedEvents())
        }
    }

    @Test
    fun `buildExport works unchanged with tracking enabled`() = runBlocking {
        withUiStateCollector {
            viewModel.addRecord(sample)
            awaitState { it.totalCount > 0 }

            val adif = viewModel.buildExport(QsoExportFormat.ADIF, useFiltered = true)
            assertTrue(adif.contains("BG7S"))
            val xml = viewModel.buildExport(QsoExportFormat.XML, useFiltered = false)
            assertTrue(xml.contains("BG7S"))
            assertEquals(1, viewModel.exportCount(useFiltered = false))
        }
    }
}
