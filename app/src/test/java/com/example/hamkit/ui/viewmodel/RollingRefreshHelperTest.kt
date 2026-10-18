package com.example.hamkit.ui.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [launchRollingRefresher] 单元测试：持续接收「实时显示图像」的刷新语义。
 *
 * 回归背景：历史实现是「刷新协程活跃 → 丢弃新请求」。建图耗时会随卷纸高度增长，
 * 一旦变慢，解码线程每 4 行发的请求全被丢弃，画面停住 —— 用户看到的就是
 * 「持续接收时不实时显示图像」。这里逐条钉死修复后的语义。
 *
 * 注意：被启动的刷新协程是**常驻**的（不会自己结束），因此每个用例结束前必须
 * 调用 `Job.cancel()`，否则 `runTest` 会以 UncompletedCoroutinesError 失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RollingRefreshHelperTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `首次请求立即重建`() = runTest(dispatcher) {
        val requests = MutableStateFlow(0)
        var renders = 0
        val job = launchRollingRefresher(requests, minIntervalMs = 120L) { renders++ }
        advanceUntilIdle()

        // 初值 0 会先触发一次；重点是「第一个新行请求」不能被节流吞掉
        val before = renders
        requests.update { it + 1 }
        advanceUntilIdle()
        assertTrue("首个新行请求后应立即重建", renders > before)
        job.cancel()
    }

    @Test
    fun `忙碌期间的多次请求合并但最后一次不丢`() = runTest(dispatcher) {
        val requests = MutableStateFlow(0)
        val renders = mutableListOf<Int>()
        // 渲染很慢：一次建图 500 ms，远超 120 ms 节流间隔
        val job = launchRollingRefresher(requests, minIntervalMs = 120L) {
            renders.add(requests.value)
            kotlinx.coroutines.delay(500)
        }
        advanceUntilIdle()

        // 建图期间连发 5 次请求
        for (i in 1..5) requests.update { i }
        advanceUntilIdle()

        // 关键在于「最后一次」的计数一定能被渲染到：旧的「活跃就丢弃」实现会永远停在第一次
        assertTrue("应有渲染发生（实得 ${renders.size} 次）", renders.isNotEmpty())
        assertEquals("最后一次请求必须被执行", 5, renders.last())
        // 合并生效：不会 5 次全跑
        assertTrue("请求应被合并（实得 ${renders.size} 次渲染）", renders.size < 6)
        job.cancel()
    }

    @Test
    fun `相邻重建间隔不小于节流值`() = runTest(dispatcher) {
        val requests = MutableStateFlow(0)
        val renderTimes = mutableListOf<Long>()
        // 时钟直接取虚拟调度器的时间：delay 与 now() 走同一个时间轴，
        // 否则「手动递增的变量」与「虚拟时间」不同步，节流判断会失真
        val job = launchRollingRefresher(
            requests = requests,
            minIntervalMs = 120L,
            now = { testScheduler.currentTime },
            onRender = { renderTimes.add(testScheduler.currentTime) },
        )
        advanceUntilIdle()

        // 每 10 ms 来一行新数据（远快于 120 ms 节流间隔），持续 600 ms
        repeat(60) {
            requests.update { it + 1 }
            advanceTimeBy(10L)
        }
        advanceUntilIdle()

        assertTrue("应有多次重建（实得 ${renderTimes.size} 次）", renderTimes.size > 1)
        for (i in 1 until renderTimes.size) {
            val gap = renderTimes[i] - renderTimes[i - 1]
            assertTrue("相邻重建间隔 ${gap}ms 应 ≥ 120ms", gap >= 120L)
        }
        job.cancel()
    }

    @Test
    fun `请求计数不阻塞调用方`() = runTest(dispatcher) {
        // 解码线程只写计数，从不等待建图。这里让第一次渲染睡 1 秒，
        // 若写计数需要等建图结束，下面的 1000 次递增根本走不完
        val requests = MutableStateFlow(0)
        val job = launchRollingRefresher(requests, minIntervalMs = 120L) {
            kotlinx.coroutines.delay(1_000)
        }
        advanceUntilIdle()

        repeat(1000) { requests.update { it + 1 } }
        assertEquals("1000 次递增应立即生效（无阻塞）", 1000, requests.value)
        job.cancel()
    }

    @Test
    fun `累计行数判断：卷纸到顶后仍会继续刷新`() {
        // 回归：旧实现用「当前缓冲行数 - 上次行数」，卷纸到达 maxRows 后恒为 0，
        // 预览会永久停住（解码其实还在继续）。改用累计追加行数后必须还能触发。
        assertFalse("只多 1 行不该重建", shouldRenderPreview(20_001, 20_000, 4))
        assertTrue("累计多 4 行必须重建", shouldRenderPreview(20_004, 20_000, 4))
    }

    @Test
    fun `累计行数判断：首行立即重建且不足阈值不重建`() {
        assertTrue("首行要立即出图", shouldRenderPreview(1, 0, 4))
        assertFalse("已渲染过且不足 4 行不重建", shouldRenderPreview(4, 3, 4))
        assertTrue("满 4 行应重建", shouldRenderPreview(7, 3, 4))
        assertFalse("没有新行不重建", shouldRenderPreview(0, 0, 4))
    }
}
