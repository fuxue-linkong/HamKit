package com.example.hamkit.data.location

/**
 * 定位性能指标记录器接口。
 *
 * 一次定位完成后由采集方构建 [LocationMetric] 并交给本接口记录。
 * 未来埋点上报服务只需实现本接口（如转发到后端聚合接口），
 * 采集侧代码无需变动。
 */
interface LocationMetricRecorder {
    /**
     * 记录一次定位的性能指标。
     * @param metric 定位性能数据
     */
    suspend fun record(metric: LocationMetric)
}

/**
 * 本地日志存储实现（默认实现，数据存于内存）。
 *
 * - 线程安全：record 可能来自不同协程/线程，用同步列表防止并发写损坏。
 * - 容量上限：超过 [MAX_SIZE] 时丢弃最旧记录，避免长期驻留内存无限增长。
 * - 数据仅存内存，进程退出即清空；不落盘、不外发，无隐私增量。
 */
class LocalLocationMetricRecorder : LocationMetricRecorder {

    private val metrics = java.util.Collections.synchronizedList(mutableListOf<LocationMetric>())

    override suspend fun record(metric: LocationMetric) {
        metrics.add(metric)
        while (metrics.size > MAX_SIZE) {
            metrics.removeAt(0)
        }
    }

    /** 获取当前已记录的全部指标（快照，与内部列表隔离） */
    fun getAllMetrics(): List<LocationMetric> = synchronized(metrics) { metrics.toList() }

    /** 清空已记录的指标 */
    fun clearMetrics() = metrics.clear()

    companion object {
        /** 内存中最多保留的指标条数 */
        const val MAX_SIZE = 500
    }
}
