package com.example.hamkit.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 持续接收预览刷新的收集器（SSTV 三期 Q2 的「实时显示」修复）。
 *
 * 上游：[docs/SSTV_PHASE3_PLAN.md](../../../../../../../docs/SSTV_PHASE3_PLAN.md)
 *
 * ## 为什么单独抽出来
 *
 * 刷新协程需要「请求合并 + 时间节流」两件事同时成立，而这两件事都很容易写错，
 * 又只能在协程时序上验证（ViewModel 依赖 Android `Bitmap`，纯 JVM 测不了）。
 * 因此把**纯协程逻辑**放在这里，ViewModel 只提供「重建一张位图」的副作用。
 *
 * ## 请求合并语义（关键）
 *
 * 用「常驻协程 + StateFlow 自带的合并」而不是「协程活跃就丢弃新请求」：
 *
 *  - 解码线程每 4 行递增一次计数，写计数是 O(1)，从不等建图；
 *  - `StateFlow` 本身就有**合并（conflation）**语义：收集端忙的时候中间值被覆盖，
 *    只保留最新值，且**最后一次一定执行**。所以这里直接 `collect` 即可，
 *    再套 `.conflate()` 是空操作（编译器会按 error 级别报废弃）；
 *  - 早期的「活跃就 return」写法会让最新请求永久丢失 —— 建图越慢越丢，
 *    画面最后停住不动，正是「持续接收不实时显示图像」的表现。
 *
 * @param requests 请求计数（每次 +1 表示「有新行，可以重建了」）
 * @param minIntervalMs 两次重建的最小间隔；距上次太近时先 `delay` 补齐
 * @param now 单调时钟（毫秒）。默认 [System.currentTimeMillis]；测试可注入虚拟时钟，
 *   这样节流时序与 `kotlinx-coroutines-test` 的 `advanceTimeBy` 对齐
 * @param onRender 真正执行重建的挂起函数（在协程上下文里跑，可自行切线程）
 */
fun CoroutineScope.launchRollingRefresher(
    requests: StateFlow<Int>,
    minIntervalMs: Long,
    now: () -> Long = System::currentTimeMillis,
    onRender: suspend () -> Unit,
): Job {
    return launch {
        // lastAt < 0 表示「还没重建过」：首次请求立即执行，不受节流影响
        var lastAt = -1L
        // StateFlow 自带合并：忙时只保留最新计数，最后一次必达（无需 .conflate()）
        requests.collect {
            if (lastAt >= 0) {
                val wait = minIntervalMs - (now() - lastAt)
                if (wait > 0) delay(wait)
            }
            onRender()
            lastAt = now()
        }
    }
}
