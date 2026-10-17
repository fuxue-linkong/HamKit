package com.example.hamkit.ui.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [launchRollingRefresher] 单元测试：持续接收「实时显示图像」的刷新语义。
 *
 * 回归背景：历史实现是「刷新协程活跃 → 丢弃新请求」。建图耗时会随卷纸高度增长，
 * 一旦变慢，解码线程每 4 行发的请求全被丢弃，画面停住 —— 用户看到的就是
 * 「持续接收时不实时显示图像」。这里逐条钉死修复后的语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RollingRefreshHelperTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `首次请求立即重建`() = runTest(dispatcher) {
        val requests = MutableStateFlow(0)
        var renders = 0
        launchRollingRefresher(requests, minIntervalMs = 120L) { renders++ }
        advanceUntilIdle()

        // StateFlow 初值也会触发一次收集（计数 0 → 一次渲染），但重点是：
        // 第一个「新行」请求不能在节流里被吞掉
        requests.value = 1
        advanceUntilIdle()
        assertTrue("首次请求后应已重建，实得 $renders 次", renders >= 1)
    }

    @Test
    fun `忙碌期间的多次请求合并但最后一次不丢`() = runTest(dispatcher) {
        val requests = MutableStateFlow(0)
        val renders = mutableListOf<Int>()
        // 渲染很慢：一次建图 500 ms，远超 120 ms 节流间隔
        launchRollingRefresher(requests, minIntervalMs = 120L) {
            renders.add(requests.value)
            kotlinx.coroutines.delay(500)
        }
        advanceUntilIdle()

        // 建图期间连发 5 次请求
        for (i in 1..5) requests.value = i
        advanceUntilIdle()

        // 关键在于「最后一次」的计数一定能被渲染到：旧的「活跃就丢弃」实现会永远停在第一次
        assertEquals("最后一次请求必须被执行", 5, renders.last())
        // 合并生效：不会 5 次全跑
        assertTrue("请求应被合并（实得 ${renders.size} 次渲染）", renders.size < 6)
    }

    @Test
    fun `重建间隔不小于节流_120ms`() = runTest(dispatcher) {
        val requests = MutableStateFlow(0)
        val renderTimes = mutableListOf<Long>()
        var virtual = 0L
        launchRollingRefresher(
            requests = requests,
            minIntervalMs = 120L,
            now = { virtual },
            onRender = { renderTimes.add(virtual) },
        )
        advanceUntilIdle()

        // 逐行推进：每 10 ms 来一行新数据（远快于节流间隔）
        repeat(30) {
            virtual += 10
            requests.value++
            advanceTimeBy(10L)
        }
        advanceUntilIdle()

        assertTrue("应有多次重建", renderTimes.size > 1)
        for (i in 1 until renderTimes.size) {
            val gap = renderTimes[i] - renderTimes[i - 1]
            assertTrue("相邻重建间隔 ${gap}ms 应 ≥ 120ms", gap >= 120L)
        }
    }

    @Test
    fun `请求计数不阻塞调用方`() = runTest(dispatcher) {
        // 解码线程只写计数，从不等待建图。这里让每次渲染都睡 1 秒，
        // 若写计数需要等建图结束，下面的 1000 次递增根本走不完
        val requests = MutableStateFlow(0)
        launchRollingRefresher(requests, minIntervalMs = 120L) {
            kotlinx.coroutines.delay(1_000)
        }
        advanceUntilIdle()

        repeat(1000) { requests.update { it + 1 } }
        assertEquals("1000 次递增应立即生效（无阻塞）", 1000, requests.value)
    }
}
