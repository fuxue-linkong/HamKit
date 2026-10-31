package com.example.hamkit.shared

import com.example.hamkit.shared.net.HttpTransport

/**
 * 天气数据 —— 从 Android `data/weather` 移植。
 *
 * 数据源与 Android 相同：高德 Web 服务
 * - 逆地理编码：`/v3/geocode/regeo`（经纬度 → adcode + 城市名）
 * - 实时天气：`/v3/weather/weatherInfo?extensions=base`
 * - 4 天预报：`/v3/weather/weatherInfo?extensions=all`
 *
 * Key 由调用方传入（iOS 从 Info.plist 的 `AMapWebKey` 读取）；Key 缺失时返回错误。
 */
data class WeatherNow(
    val temp: String,
    val text: String,
    val windDir: String,
    val windPower: String,
    val humidity: String,
    val reportTime: String,
)

data class WeatherDay(
    val date: String,
    val week: String,
    val dayWeather: String,
    val nightWeather: String,
    val dayTemp: String,
    val nightTemp: String,
    val dayWind: String,
    val nightWind: String,
    val dayPower: String,
    val nightPower: String,
)

data class WeatherInfo(
    val cityName: String,
    val now: WeatherNow,
    val daily: List<WeatherDay>,
)

data class WeatherSnapshot(
    val info: WeatherInfo?,
    val error: String?,
    val fetchedAtMs: Long,
)

object WeatherApi {

    private const val WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo"
    private const val REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo"

    /**
     * 拉取天气（逆地理编码 → 实时 → 预报）。
     * 网络/业务失败时回调 error，不抛异常。
     */
    fun fetch(
        transport: HttpTransport,
        latitude: Double,
        longitude: Double,
        apiKey: String,
        callback: (WeatherSnapshot) -> Unit,
    ) {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            callback(failure("无效坐标: $latitude,$longitude"))
            return
        }
        if (apiKey.isBlank()) {
            callback(failure("未配置高德 Web 服务 Key（AMapWebKey）"))
            return
        }

        val location = formatCoord(longitude) + "," + formatCoord(latitude)
        val regeoUrl = buildUrl(
            REGEO_URL,
            listOf("location" to location, "key" to apiKey),
        )
        transport.get(regeoUrl, 15_000) { resp ->
            if (!resp.isSuccess) {
                callback(failure(resp.error ?: "逆地理编码失败：HTTP ${resp.status}"))
                return@get
            }
            val parsed = try {
                parseRegeo(resp.body.orEmpty())
            } catch (e: Exception) {
                callback(failure(e.message ?: "逆地理编码解析失败"))
                return@get
            }
            if (parsed == null) {
                callback(failure("逆地理编码返回空结果"))
                return@get
            }
            val (adcode, city) = parsed
            val nowUrl = buildUrl(
                WEATHER_URL,
                listOf("city" to adcode, "key" to apiKey, "extensions" to "base"),
            )
            transport.get(nowUrl, 15_000) { nowResp ->
                if (!nowResp.isSuccess) {
                    callback(failure(nowResp.error ?: "实时天气失败：HTTP ${nowResp.status}"))
                    return@get
                }
                val now = try {
                    parseNow(nowResp.body.orEmpty())
                } catch (e: Exception) {
                    callback(failure(e.message ?: "实时天气解析失败"))
                    return@get
                }
                val dailyUrl = buildUrl(
                    WEATHER_URL,
                    listOf("city" to adcode, "key" to apiKey, "extensions" to "all"),
                )
                transport.get(dailyUrl, 15_000) { dailyResp ->
                    if (!dailyResp.isSuccess) {
                        callback(failure(dailyResp.error ?: "天气预报失败：HTTP ${dailyResp.status}"))
                        return@get
                    }
                    val daily = try {
                        parseDaily(dailyResp.body.orEmpty())
                    } catch (e: Exception) {
                        callback(failure(e.message ?: "天气预报解析失败"))
                        return@get
                    }
                    callback(
                        WeatherSnapshot(
                            info = WeatherInfo(cityName = city, now = now, daily = daily),
                            error = null,
                            fetchedAtMs = 0L,
                        )
                    )
                }
            }
        }
    }

    // MARK: - 解析

    private fun parseRegeo(json: String): Pair<String, String>? {
        val root = MiniJson.parseObject(json)
        checkStatus(root)
        val regeocode = root["regeocode"] as? Map<*, *> ?: throw IllegalArgumentException("regeo 缺少 regeocode")
        val component = regeocode["addressComponent"] as? Map<*, *>
            ?: throw IllegalArgumentException("regeo 缺少 addressComponent")
        val adcode = (component["adcode"] as? String).orEmpty()
        if (adcode.isEmpty()) throw IllegalArgumentException("adcode 为空")
        val city = (component["city"] as? String).orEmpty()
        val province = (component["province"] as? String).orEmpty()
        // 直辖市 city 为空或 "[]"，回退 province
        val name = if (city.isNotEmpty() && city != "[]") city else province
        if (name.isEmpty()) throw IllegalArgumentException("城市名为空")
        return adcode to name
    }

    private fun parseNow(json: String): WeatherNow {
        val root = MiniJson.parseObject(json)
        checkStatus(root)
        val lives = root["lives"] as? List<*> ?: throw IllegalArgumentException("lives 为空")
        val live = lives.firstOrNull() as? Map<*, *> ?: throw IllegalArgumentException("lives 为空")
        return WeatherNow(
            temp = stringOf(live["temperature"]),
            text = stringOf(live["weather"]),
            windDir = stringOf(live["winddirection"]),
            windPower = stringOf(live["windpower"]),
            humidity = stringOf(live["humidity"]),
            reportTime = stringOf(live["reporttime"]),
        )
    }

    private fun parseDaily(json: String): List<WeatherDay> {
        val root = MiniJson.parseObject(json)
        checkStatus(root)
        val forecasts = root["forecasts"] as? List<*> ?: throw IllegalArgumentException("forecasts 为空")
        val forecast = forecasts.firstOrNull() as? Map<*, *> ?: throw IllegalArgumentException("forecasts 为空")
        val casts = forecast["casts"] as? List<*> ?: throw IllegalArgumentException("casts 为空")
        return casts.mapNotNull { raw ->
            val cast = raw as? Map<*, *> ?: return@mapNotNull null
            WeatherDay(
                date = stringOf(cast["date"]),
                week = stringOf(cast["week"]),
                dayWeather = stringOf(cast["dayweather"]),
                nightWeather = stringOf(cast["nightweather"]),
                dayTemp = stringOf(cast["daytemp"]),
                nightTemp = stringOf(cast["nighttemp"]),
                dayWind = stringOf(cast["daywind"]),
                nightWind = stringOf(cast["nightwind"]),
                dayPower = stringOf(cast["daypower"]),
                nightPower = stringOf(cast["nightpower"]),
            )
        }
    }

    private fun checkStatus(root: Map<String, Any?>) {
        if (stringOf(root["status"]) != "1") {
            val info = stringOf(root["info"]).ifEmpty { "未知错误" }
            val code = stringOf(root["infocode"])
            throw IllegalArgumentException("$info($code)")
        }
    }

    private fun stringOf(value: Any?): String = when (value) {
        is String -> if (value == "[]" || value == "null") "" else value
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        null -> ""
        else -> value.toString()
    }

    private fun formatCoord(value: Double): String {
        val scaled = kotlin.math.round(value * 1_000_000).toLong()
        val sign = if (scaled < 0) "-" else ""
        val abs = kotlin.math.abs(scaled)
        val whole = abs / 1_000_000
        val frac = abs % 1_000_000
        return sign + whole + "." + frac.toString().padStart(6, '0')
    }

    private fun buildUrl(base: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        return "$base?$query"
    }

    private fun urlEncode(value: String): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val ch = b.toChar()
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(hex[b shr 4]).append(hex[b and 0xF])
            }
        }
        return sb.toString()
    }

    private fun failure(reason: String) = WeatherSnapshot(
        info = null,
        error = reason,
        fetchedAtMs = 0L,
    )
}
