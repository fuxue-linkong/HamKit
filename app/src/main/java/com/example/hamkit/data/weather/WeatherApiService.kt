package com.example.hamkit.data.weather

import com.example.hamkit.BuildConfig
import com.example.hamkit.data.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 高德天气 API 封装。
 *
 * API 文档：https://lbs.amap.com/api/webservice/guide/api/weatherinfo
 *
 * Key 处理：
 * - 本项目按需求同时使用高德 Android SDK 与 Web 服务（天气 + 逆地理编码）。
 * - Web 服务 Key 通过 `AMAP_WEB_KEY` 环境变量或 `local.properties` 的
 *   `amap.api.key` 在构建时写入 `BuildConfig`；它随 APK 分发，**不是秘密**，
 *   必须依靠高德侧的配额/用量监控，不能把它当加密资产。
 * - 缺 Key 时抛 [ApiKeyMissingException]，避免发出必然失败的请求。
 */
class WeatherApiService {

    // 基于共享单例派生：共享连接池/线程池，仅覆盖本服务的超时配置
    private val client = HttpClientProvider.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 完整天气数据获取流程：
     * 1. 用经纬度逆地理编码获取 adcode（行政区划代码）和城市名
     * 2. 用 adcode 查实时天气（extensions=base）
     * 3. 用 adcode 查 4 天预报（extensions=all）
     *
     * @throws ApiKeyMissingException Web 服务 Key 未配置
     * @throws WeatherNetworkException 网络请求失败
     * @throws WeatherApiException 高德 API 返回业务错误
     */
    suspend fun fetchWeather(latitude: Double, longitude: Double): WeatherResult {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            throw WeatherApiException("无效坐标: $latitude,$longitude")
        }
        val key = apiKey()
        if (key.isBlank()) {
            throw ApiKeyMissingException()
        }

        return withContext(Dispatchers.IO) {
            // Step 1: 经纬度逆地理编码获取 adcode
            val locationInfo = fetchAdcode(latitude, longitude, key)

            // Step 2 & 3: 并行获取实时天气和预报
            coroutineScope {
                val nowDeferred = async { fetchNowWeather(locationInfo.first, key) }
                val dailyDeferred = async { fetchDailyForecast(locationInfo.first, key) }
                val now = nowDeferred.await()
                val daily = dailyDeferred.await()

                WeatherResult(
                    cityName = locationInfo.second,
                    now = now,
                    daily = daily
                )
            }
        }
    }

    /**
     * 逆地理编码：经纬度 → adcode + 城市名。
     */
    private suspend fun fetchAdcode(lat: Double, lng: Double, key: String): Pair<String, String> {
        // 高德 API location 参数格式：经度,纬度（lng,lat）
        val location = String.format(Locale.US, "%.6f,%.6f", lng, lat)
        val url = buildUrl(REGEO_BASE_URL, mapOf("location" to location, "key" to key))
        val response = executeRequest(url)
        val json = parseJson(response)
        checkApiStatus(json)
        val regeocode = json.optJSONObject("regeocode")
            ?: throw WeatherApiException("regeo 响应缺少 regeocode 字段")
        val addrComponent = regeocode.optJSONObject("addressComponent")
            ?: throw WeatherApiException("regeo 响应缺少 addressComponent 字段")

        val adcode = addrComponent.optString("adcode")
        if (adcode.isEmpty()) throw WeatherApiException("adcode 为空")

        // city 可能为空（直辖市），回退到 province。
        // 高德 regeo 对直辖市返回 city 为空数组 []，optString 会得到字符串 "[]"，
        // 需一并视为空，否则城市名会错误显示为 "[]"。
        val city = addrComponent.optString("city")
        val province = addrComponent.optString("province")
        val cityName = if (city.isNotEmpty() && city != "[]") city else province

        if (cityName.isEmpty()) {
            throw WeatherApiException("城市名为空")
        }
        return Pair(adcode, cityName)
    }

    /**
     * 获取实时天气（extensions=base）。
     */
    private suspend fun fetchNowWeather(adcode: String, key: String): WeatherNow {
        val url = buildUrl(WEATHER_BASE_URL, mapOf("city" to adcode, "key" to key, "extensions" to "base"))
        val response = executeRequest(url)
        val json = parseJson(response)
        checkApiStatus(json)

        val livesArr = json.optJSONArray("lives")
            ?: throw WeatherApiException("lives 为空")
        if (livesArr.length() == 0) throw WeatherApiException("lives 数组为空")

        val live = livesArr.optJSONObject(0)
            ?: throw WeatherApiException("lives[0] 为空")
        return WeatherNow(
            temp = live.optString("temperature"),
            text = live.optString("weather"),
            windDir = live.optString("winddirection"),
            windPower = live.optString("windpower"),
            humidity = live.optString("humidity"),
            reportTime = live.optString("reporttime")
        )
    }

    /**
     * 获取 4 天预报（extensions=all）。
     */
    private suspend fun fetchDailyForecast(adcode: String, key: String): List<WeatherDay> {
        val url = buildUrl(WEATHER_BASE_URL, mapOf("city" to adcode, "key" to key, "extensions" to "all"))
        val response = executeRequest(url)
        val json = parseJson(response)
        checkApiStatus(json)

        val forecastsArr = json.optJSONArray("forecasts")
            ?: throw WeatherApiException("forecasts 为空")
        if (forecastsArr.length() == 0) throw WeatherApiException("forecasts 数组为空")

        val forecast = forecastsArr.optJSONObject(0)
            ?: throw WeatherApiException("forecasts[0] 为空")
        val castsArr = forecast.optJSONArray("casts")
            ?: throw WeatherApiException("casts 为空")

        return (0 until castsArr.length()).mapNotNull { idx ->
            val cast = castsArr.optJSONObject(idx) ?: return@mapNotNull null
            WeatherDay(
                date = cast.optString("date"),
                week = cast.optString("week"),
                dayWeather = cast.optString("dayweather"),
                nightWeather = cast.optString("nightweather"),
                dayTemp = cast.optString("daytemp"),
                nightTemp = cast.optString("nighttemp"),
                dayWind = cast.optString("daywind"),
                nightWind = cast.optString("nightwind"),
                dayPower = cast.optString("daypower"),
                nightPower = cast.optString("nightpower")
            )
        }
    }

    /**
     * 校验高德 API 响应状态。status != "1" 时抛出 [WeatherApiException] 携带 info。
     */
    private fun checkApiStatus(json: JSONObject) {
        if (json.optString("status") != "1") {
            val info = json.optString("info", "未知错误")
            val infocode = json.optString("infocode", "")
            throw WeatherApiException("$info(${infocode})")
        }
    }

    /**
     * 构建带 query 参数的 URL，参数值自动 URL 编码，避免特殊字符破坏 URL 结构。
     */
    private fun buildUrl(base: String, params: Map<String, String>): String {
        val builder = base.toHttpUrl().newBuilder()
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    /**
     * 安全解析 JSON 响应体。服务器返回 200 但 Body 非 JSON（如代理劫持页）时，
     * 转换为 [WeatherApiException] 而非让 JSONException 直接传播。
     */
    private fun parseJson(response: String): JSONObject {
        return try {
            JSONObject(response)
        } catch (e: JSONException) {
            throw WeatherApiException("响应不是有效 JSON: ${e.message}")
        }
    }

    /**
     * 执行 HTTP 请求，返回响应体字符串。
     */
    private fun executeRequest(url: String): String {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string()
                } else {
                    throw WeatherNetworkException("HTTP ${response.code}")
                }
            }
        } catch (e: WeatherNetworkException) {
            throw e
        } catch (e: Exception) {
            throw WeatherNetworkException(e.message ?: "网络请求异常")
        }
    }

    /** 构建时注入的高德 Web 服务 Key（可被反编译提取，不是秘密）。 */
    private fun apiKey(): String = BuildConfig.AMAP_WEB_KEY

    companion object {
        // 高德 Web 服务 API 基础 URL
        private const val WEATHER_BASE_URL = "https://restapi.amap.com/v3/weather/weatherInfo"
        private const val REGEO_BASE_URL = "https://restapi.amap.com/v3/geocode/regeo"
    }
}

/**
 * API Key 未配置：构建时未注入 AMAP_WEB_KEY / local.properties amap.api.key。
 */
class ApiKeyMissingException :
    Exception("高德 Web 服务 Key 未配置，请设置 AMAP_WEB_KEY 或 local.properties 的 amap.api.key")

/**
 * 网络层异常：连接超时、DNS 解析失败、断网、HTTP 非 2xx 等。
 */
class WeatherNetworkException(message: String) : Exception(message)

/**
 * 高德 API 业务错误：status != "1"，如 Key 无效、配额超限、参数错误等。
 */
class WeatherApiException(message: String) : Exception(message)
