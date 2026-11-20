package com.example.hamkit.data.location

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LocationMetric JSON 序列化与 LocationMetricRecorder 的验证测试。
 *
 * org.json 在 JVM 单测中由 Robolectric 提供（与 OnboardingStoreTest 同配置）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocationMetricTest {

    /** 验收 schema 示例数据：序列化输出字段名/类型必须与之对应 */
    @Test
    fun `toJson matches acceptance schema`() {
        val metric = LocationMetric(
            traceId = "abc12345",
            result = "SUCCESS",
            resultReason = "NETWORK",
            durationMs = 650L,
            provider = "network",
            accuracy = 22.5f,
            cacheCheckMs = 5L,
            providerRegisterMs = mapOf("network" to 2L, "gps" to 3L),
            providerFirstResponseMs = mapOf("network" to 450L),
            isSlow = true,
            slowReason = "NETWORK_PROVIDER_SLOW",
            timestamp = 1700000000000L,
            deviceInfo = "Android 13, Pixel 6"
        )

        val obj = JSONObject(metric.toJson())

        assertEquals("abc12345", obj.getString("traceId"))
        assertEquals("SUCCESS", obj.getString("result"))
        assertEquals("NETWORK", obj.getString("resultReason"))
        assertEquals(650L, obj.getLong("durationMs"))
        assertEquals("network", obj.getString("provider"))
        assertEquals(22.5, obj.getDouble("accuracy"), 0.001)
        assertEquals(5L, obj.getLong("cacheCheckMs"))
        // 嵌套 map 序列化为 JSON 对象
        assertEquals(2L, obj.getJSONObject("providerRegisterMs").getLong("network"))
        assertEquals(3L, obj.getJSONObject("providerRegisterMs").getLong("gps"))
        assertEquals(450L, obj.getJSONObject("providerFirstResponseMs").getLong("network"))
        assertTrue(obj.getBoolean("isSlow"))
        assertEquals("NETWORK_PROVIDER_SLOW", obj.getString("slowReason"))
        assertEquals(1700000000000L, obj.getLong("timestamp"))
        assertEquals("Android 13, Pixel 6", obj.getString("deviceInfo"))
    }

    @Test
    fun `toJson omits null fields and roundtrip preserves values`() {
        val metric = LocationMetric(
            traceId = "deadbeef",
            result = "TIMEOUT",
            resultReason = "NO_RESPONSE_10S",
            durationMs = 9000L,
            provider = "timeout",
            accuracy = -1f,
            cacheCheckMs = 3L,
            providerRegisterMs = emptyMap(),
            providerFirstResponseMs = mapOf("gps" to 8500L),
            isSlow = true,
            slowReason = null,
            timestamp = 1700000000001L,
            deviceInfo = null
        )

        val obj = JSONObject(metric.toJson())
        // null 字段省略不输出
        assertFalse(obj.has("slowReason"))
        assertFalse(obj.has("deviceInfo"))
        // 空 map 输出为空对象
        assertEquals(0, obj.getJSONObject("providerRegisterMs").length())

        val restored = LocationMetric.fromJson(metric.toJson())
        assertEquals(metric, restored)
    }

    @Test
    fun `deviceInfoString matches expected format`() {
        val info = deviceInfoString()
        assertTrue(info.startsWith("Android ${android.os.Build.VERSION.SDK_INT}, "))
        assertTrue(info.contains(android.os.Build.MODEL))
    }

    @Test
    fun `recorder stores clears and evicts oldest`() = runTest {
        val recorder = LocalLocationMetricRecorder()
        fun metricOf(traceId: String) = LocationMetric(
            traceId = traceId,
            result = "SUCCESS",
            resultReason = "CACHE",
            durationMs = 10L,
            provider = "cache",
            accuracy = -1f,
            cacheCheckMs = 5L,
            providerRegisterMs = emptyMap(),
            providerFirstResponseMs = emptyMap(),
            isSlow = false,
            timestamp = 1L
        )

        recorder.record(metricOf("a"))
        recorder.record(metricOf("b"))
        assertEquals(listOf("a", "b"), recorder.getAllMetrics().map { it.traceId })

        // 超容量：最旧的被丢弃
        repeat(LocalLocationMetricRecorder.MAX_SIZE) { recorder.record(metricOf("fill$it")) }
        assertEquals(LocalLocationMetricRecorder.MAX_SIZE, recorder.getAllMetrics().size)
        assertEquals("fill0", recorder.getAllMetrics().first().traceId)

        recorder.clearMetrics()
        assertTrue(recorder.getAllMetrics().isEmpty())
    }
}
