package com.example.hamkit.data.satellite

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * 卫星过境预测结果本地缓存。进程重启后可即时回填 [SatelliteInfo] 列表，
 * 避免每次进入应用都重新执行 CPU 密集的 SGP4 计算。
 *
 * 缓存格式：JSON 对象，包含 satellites 数组 + 预测时的经纬度 + 预测时刻 + TLE 快照哈希。
 *
 * 与 [SatelliteCacheStore]（TLE 缓存，24h）不同，本缓存面向"预测结果"，
 * 有效期较短（2 小时），过期后回退到重新预测。
 */
class SatellitePredictCacheStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取缓存的预测结果。无缓存或解析失败时返回 null。
     */
    fun load(): CachedPrediction? {
        val ts = prefs.getLong(KEY_PREDICT_AT, -1L)
        if (ts < 0) return null
        val json = prefs.getString(KEY_PREDICT_JSON, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val array = obj.optJSONArray(KEY_SATELLITES) ?: return null
            val list = ArrayList<SatelliteInfo>(array.length())
            for (i in 0 until array.length()) {
                val s = array.getJSONObject(i)
                // 过滤掉已过期的过境（LOS 早于缓存写入时间）
                val losMillis = s.optLong("los", 0L)
                if (losMillis in 1L..(ts - 1L)) continue
                list.add(
                    SatelliteInfo(
                        name = s.optString("name", ""),
                        catalogNumber = s.optInt("cat", 0),
                        modes = s.optJSONArray("modes")?.let { arr ->
                            ArrayList<String>(arr.length()).apply {
                                for (j in 0 until arr.length()) add(arr.optString(j, ""))
                            }
                        } ?: emptyList(),
                        aosTime = Instant.ofEpochMilli(s.optLong("aos", 0L)),
                        losTime = Instant.ofEpochMilli(losMillis),
                        maxElevation = s.optDouble("maxEl", 0.0),
                        aosAzimuth = s.optInt("aosAz", 0),
                        losAzimuth = s.optInt("losAz", 0),
                        isCurrentlyVisible = s.optBoolean("visible", false),
                        source = s.optString("source", ""),
                        status = s.optString("status", ""),
                        // 修复 P3-3：回填 isGeo（旧缓存缺该字段时默认 false，保持向后兼容）
                        isGeo = s.optBoolean("isGeo", false)
                    )
                )
            }
            if (list.isEmpty()) return null
            CachedPrediction(
                satellites = list,
                latitude = obj.optDouble("lat", Double.NaN),
                longitude = obj.optDouble("lon", Double.NaN),
                predictedAt = Instant.ofEpochMilli(ts),
                tleFingerprint = obj.optString("tleFp", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 保存预测结果及上下文信息。
     *
     * @param tleFingerprint TLE 快照指纹（如 NORAD 编号集合哈希），用于 TLE 刷新后失效旧预测
     */
    fun save(
        satellites: List<SatelliteInfo>,
        latitude: Double,
        longitude: Double,
        predictedAt: Instant = Instant.now(),
        tleFingerprint: String = ""
    ) {
        val array = JSONArray()
        for (s in satellites) {
            val obj = JSONObject()
            obj.put("name", s.name)
            obj.put("cat", s.catalogNumber)
            val modesArr = JSONArray()
            s.modes.forEach { modesArr.put(it) }
            obj.put("modes", modesArr)
            obj.put("aos", s.aosTime.toEpochMilli())
            obj.put("los", s.losTime.toEpochMilli())
            obj.put("maxEl", s.maxElevation)
            obj.put("aosAz", s.aosAzimuth)
            obj.put("losAz", s.losAzimuth)
            obj.put("visible", s.isCurrentlyVisible)
            obj.put("source", s.source)
            obj.put("status", s.status)
            // 修复 P3-3：序列化 isGeo，回填时还原，避免缓存恢复后 GEO 卫星被误判为常规过境
            obj.put("isGeo", s.isGeo)
            array.put(obj)
        }
        val root = JSONObject()
        root.put(KEY_SATELLITES, array)
        root.put("lat", latitude)
        root.put("lon", longitude)
        root.put("tleFp", tleFingerprint)
        prefs.edit()
            .putString(KEY_PREDICT_JSON, root.toString())
            .putLong(KEY_PREDICT_AT, predictedAt.toEpochMilli())
            .apply()
    }

    /**
     * 清空缓存（TLE 拉取失败或预测失败时调用，避免脏数据残留）。
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_PREDICT_JSON)
            .remove(KEY_PREDICT_AT)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "radio_area_predict_cache"
        private const val KEY_PREDICT_JSON = "cached_predict_json"
        private const val KEY_PREDICT_AT = "cached_predict_at"
        private const val KEY_SATELLITES = "satellites"

        /**
         * 预测缓存有效期：2 小时。
         * 过境预测对时间敏感（AOS/LOS 随地球自转漂移），超过 2 小时回退到重新预测。
         */
        const val CACHE_TTL_MINUTES = 120L

        /**
         * 坐标偏移阈值（度）：超过此值视为新位置，需重新预测。
         * 0.01° ≈ 1.1km，对过境预测精度影响可忽略。
         */
        const val COORD_TOLERANCE = 0.01
    }
}

/**
 * 缓存的预测结果及其上下文。
 */
@Immutable
data class CachedPrediction(
    val satellites: List<SatelliteInfo>,
    val latitude: Double,
    val longitude: Double,
    val predictedAt: Instant,
    /** TLE 快照指纹，TLE 刷新后可用于失效旧预测（空字符串表示不校验） */
    val tleFingerprint: String
) {
    /**
     * 缓存是否在有效期内（[CACHE_TTL_MINUTES] 小时）。
     */
    fun isFresh(now: Instant = Instant.now()): Boolean {
        return java.time.Duration.between(predictedAt, now).toMinutes() < SatellitePredictCacheStore.CACHE_TTL_MINUTES
    }

    /**
     * 缓存坐标是否与给定坐标接近（[COORD_TOLERANCE] 度内）。
     */
    fun matchesLocation(lat: Double, lon: Double): Boolean {
        return !latitude.isNaN() && !longitude.isNaN() &&
            Math.abs(latitude - lat) < SatellitePredictCacheStore.COORD_TOLERANCE &&
            Math.abs(longitude - lon) < SatellitePredictCacheStore.COORD_TOLERANCE
    }
}
