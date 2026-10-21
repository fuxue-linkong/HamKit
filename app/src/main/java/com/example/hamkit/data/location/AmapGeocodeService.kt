package com.example.hamkit.data.location

import com.example.hamkit.BuildConfig
import com.example.hamkit.data.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 高德逆地理编码结果。 */
data class AmapAddress(
    val formatted: String,
    val city: String,
)

/**
 * 高德 Web 服务逆地理编码。
 *
 * 与 [com.example.hamkit.data.weather.WeatherApiService] 共用同一个
 * `BuildConfig.AMAP_WEB_KEY`；Key 随 APK 分发，不是秘密。
 * 失败/无 Key/超时统一返回 null，由调用方决定回退展示。
 */
class AmapGeocodeService {

    private val client = HttpClientProvider.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun reverse(latitude: Double, longitude: Double): AmapAddress? {
        val key = BuildConfig.AMAP_WEB_KEY
        if (key.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val location = String.format(Locale.US, "%.6f,%.6f", longitude, latitude)
                val url = REGEO_BASE_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("location", location)
                    .addQueryParameter("key", key)
                    .build()
                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body.string())
                    if (json.optString("status") != "1") return@use null

                    val component = json.optJSONObject("regeocode")
                        ?.optJSONObject("addressComponent")
                        ?: return@use null

                    val province = component.optString("province")
                    val rawCity = component.optString("city")
                    val city = if (rawCity.isNotBlank() && rawCity != "[]") rawCity else province
                    val district = component.optString("district")
                    val township = component.optString("township")
                    val street = component.optJSONObject("streetNumber")
                        ?.optString("street")
                        .orEmpty()

                    val formatted = listOf(province, city, district, township, street)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")

                    if (formatted.isBlank()) null else AmapAddress(formatted, city)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        private const val REGEO_BASE_URL = "https://restapi.amap.com/v3/geocode/regeo"
    }
}
