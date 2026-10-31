package com.example.hamkit.data.satellite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * [SatellitePredictCacheStore] 缓存版本迁移测试。
 *
 * v1 缓存可能保存了“在境卫星 AOS=预测时刻”的错误结果，load 必须忽略它，
 * 让 App 重新预测；v2 写入后应能正常回填。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SatellitePredictCacheStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `legacy v1 cache is ignored`() {
        val now = System.currentTimeMillis()
        val future = now + 3_600_000L
        // 旧版 JSON：没有 "v" 字段，模拟升级前缓存的错误 AOS 数据
        val legacyJson = """
            {"satellites":[{"name":"LEGACY","cat":1002,"modes":[],"aos":$now,
            "los":$future,"maxEl":0.0,"aosAz":0,"losAz":0,"visible":true,
            "source":"","status":""}],"lat":0.0,"lon":0.0}
        """.trimIndent().replace("\n", "")

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, legacyJson)
            .putLong(KEY_AT, now)
            .commit()

        assertNull("旧版本预测缓存必须失效", SatellitePredictCacheStore(context).load())
    }

    @Test
    fun `v2 cache round trips and keeps real in-pass AOS`() {
        val store = SatellitePredictCacheStore(context)
        val now = Instant.now()

        store.save(
            satellites = listOf(
                SatelliteInfo(
                    name = "TEST",
                    catalogNumber = 12345,
                    modes = listOf("FM"),
                    aosTime = now.minusSeconds(600),
                    losTime = now.plusSeconds(600),
                    maxElevation = 45.0,
                    aosAzimuth = 10,
                    losAzimuth = 20,
                    isCurrentlyVisible = true,
                ),
            ),
            latitude = 1.0,
            longitude = 2.0,
            predictedAt = now,
        )

        val loaded = store.load()
        assertNotNull(loaded)
        val satellite = loaded!!.satellites.single()
        assertEquals(12345, satellite.catalogNumber)
        // 缓存以毫秒存储，比较 epochMillis 而不是带纳秒的 Instant
        assertEquals(
            "在境卫星的真实 AOS 应保留",
            now.minusSeconds(600).toEpochMilli(),
            satellite.aosTime.toEpochMilli(),
        )
        assertEquals(true, satellite.isCurrentlyVisible)
    }

    private companion object {
        private const val PREFS_NAME = "radio_area_predict_cache"
        private const val KEY_JSON = "cached_predict_json"
        private const val KEY_AT = "cached_predict_at"
    }
}
