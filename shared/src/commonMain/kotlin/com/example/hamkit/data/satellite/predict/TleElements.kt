package com.example.hamkit.data.satellite.predict

import kotlin.math.*

/**
 * 卫星 TLE 元素集（自研解析，替代 predict4java 的 TLE 类）。
 *
 * 由标准三行 TLE（名称行 + line1 + line2）解析而来，按 Spacetrack Report #3
 * 的固定列位规则解析，并提供 [Sgp4Satellite] 传播所需的全部轨道元素。
 *
 * 该类不可变、线程安全，可在 [Sgp4Satellite.fromTle] 中复用。
 */
class TleElements private constructor(
    val name: String,
    val catalogNumber: Int,
    val epochYear: Int,
    val epochDays: Double,
    internal val jdsatepoch: Double,
    val bstar: Double,
    val inclinationDeg: Double,
    val raanDeg: Double,
    val eccentricity: Double,
    val argPerigeeDeg: Double,
    val meanAnomalyDeg: Double,
    /** 平均运动（转/天） */
    val meanMotion: Double,
    private val ndot: Double,
    private val nddot: Double,
    private val rawLines: Array<String>,
) {

    /** NORAD 目录编号（predict4java TLE.getCatnum 兼容别名） */
    val catnum: Int
        get() = catalogNumber

    /** 平均运动（转/天，predict4java TLE.getMeanmo 兼容别名） */
    val meanmo: Double
        get() = meanMotion

    /**
     * 历元时刻（UTC 毫秒）。
     *
     * 跨平台内核只用「毫秒时间戳」这一种时间表示，不引用任何平台的日期时间类型：
     * Android 侧可直接 `Instant.ofEpochMilli(epochMillis)`，iOS 侧可直接
     * `Date(timeIntervalSince1970: epochMillis / 1000.0)`。
     */
    val epochMillis: Long by lazy {
        val mdhms = OrbitMath.days2mdhms(epochYear, epochDays)
        val jd = OrbitMath.jday(epochYear, mdhms[0].toInt(), mdhms[1].toInt(), mdhms[2].toInt(), mdhms[3].toInt(), mdhms[4])
        // 由儒略日重建时间戳（秒级精度，含小数日）
        ((jd - 2440587.5) * 86400.0 * 1000.0).toLong()
    }

    /** 是否为深空轨道（周期 ≥ 225 分钟，SGP4/SDP4 分派阈值） */
    val isDeepSpace: Boolean
        get() = (2.0 * PI) / meanMotionRadMin >= 225.0

    /** 平均运动（rad/min） */
    val meanMotionRadMin: Double
        get() = meanMotion / Sgp4Constants.XP_DOT_P

    /** 供 [Sgp4Satellite] 使用的解析元素 */
    fun toParsedElements(): ParsedTleElements = ParsedTleElements(
        catalogNumber = catalogNumber,
        epochYear = epochYear,
        epochDays = epochDays,
        jdsatepoch = jdsatepoch,
        bstar = bstar,
        inclinationRad = (inclinationDeg * PI / 180.0),
        raanRad = (raanDeg * PI / 180.0),
        eccentricity = eccentricity,
        argPerigeeRad = (argPerigeeDeg * PI / 180.0),
        meanAnomalyRad = (meanAnomalyDeg * PI / 180.0),
        meanMotionRadMin = meanMotionRadMin,
        ndotRadMin2 = ndot / (Sgp4Constants.XP_DOT_P * 1440.0),
        nddotRadMin3 = nddot / (Sgp4Constants.XP_DOT_P * 1440.0 * 1440.0),
    )

    /** 原始三行 TLE（用于本地缓存序列化） */
    fun rawLines(): Array<String> = rawLines.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TleElements) return false
        return catalogNumber == other.catalogNumber &&
            name == other.name &&
            epochYear == other.epochYear &&
            epochDays == other.epochDays &&
            meanMotion == other.meanMotion &&
            rawLines.contentEquals(other.rawLines)
    }

    override fun hashCode(): Int {
        var result = catalogNumber
        result = 31 * result + name.hashCode()
        result = 31 * result + epochYear
        result = 31 * result + epochDays.hashCode()
        result = 31 * result + meanMotion.hashCode()
        result = 31 * result + rawLines.contentHashCode()
        return result
    }

    override fun toString(): String = "TleElements($name #$catalogNumber)"

    companion object {

        /**
         * 由标准三行 TLE 文本解析元素集。
         *
         * @param tle 三行数组：[名称行, line1, line2]
         * @return 解析成功的元素集
         * @throws IllegalArgumentException 行数不足 / 列位越界 / 数值解析失败
         */
        fun fromThreeLines(tle: Array<String>): TleElements {
            if (tle.size < 3) {
                throw IllegalArgumentException("TLE 需要三行（名称 + line1 + line2），实际 ${tle.size} 行")
            }
            return parse(tle[0], tle[1], tle[2])
        }

        /**
         * 由名称行与两行 TLE 数据解析元素集。
         *
         * @throws IllegalArgumentException 列位越界 / 数值解析失败
         */
        fun parse(nameLine: String, line1: String, line2: String): TleElements {
            require(line1.length >= 69) { "TLE line1 长度不足：${line1.length}" }
            require(line2.length >= 69) { "TLE line2 长度不足：${line2.length}" }

            val name = nameLine.trim()
            val catnum = parseField(line1, 2, 7, "catalog number").toInt()
            val epochYy = parseField(line1, 18, 20, "epoch year").toInt()
            val epochDay = parseField(line1, 20, 32, "epoch day")
            val ndot = parseField(line1, 33, 43, "ndot")
            val nddotRaw = parseExponential(line1, 44, 52, "nddot")
            val bstar = parseExponential(line1, 53, 61, "bstar")

            val incl = parseField(line2, 8, 16, "inclination")
            val raan = parseField(line2, 17, 25, "raan")
            val eccRaw = parseField(line2, 26, 33, "eccentricity")
            val argp = parseField(line2, 34, 42, "arg perigee")
            val meanan = parseField(line2, 43, 51, "mean anomaly")
            val meanmo = parseField(line2, 52, 63, "mean motion")

            // 偏心率隐含小数点：列 26-33 为 " 0007358" → 0.0007358
            val ecc = eccRaw / 1e7
            val year = if (epochYy < 57) epochYy + 2000 else epochYy + 1900

            // 历元儒略日（由年/日内数转换，保留亚秒精度）
            val mdhms = OrbitMath.days2mdhms(year, epochDay)
            val jd = OrbitMath.jday(year, mdhms[0].toInt(), mdhms[1].toInt(), mdhms[2].toInt(), mdhms[3].toInt(), mdhms[4])

            return TleElements(
                name = name,
                catalogNumber = catnum,
                epochYear = year,
                epochDays = epochDay,
                jdsatepoch = jd,
                bstar = bstar,
                inclinationDeg = incl,
                raanDeg = raan,
                eccentricity = ecc,
                argPerigeeDeg = argp,
                meanAnomalyDeg = meanan,
                meanMotion = meanmo,
                ndot = ndot,
                nddot = nddotRaw,
                rawLines = arrayOf(nameLine, line1, line2),
            )
        }

        /**
         * 解析 TLE 指数记数字段（如 B*、NDDOT）。
         *
         * 与 predict4java 语义对齐：列 [start, start+1) 为符号，[start+1, start+6) 为
         * 5 位尾数（隐含小数点：m × 1e-5），指数取末位数字 e（忽略指数符号位），
         * 结果为 sign × m / 10^e。
         * 例如 " 89319-4" → 0.89319 / 10⁴ = 8.9319e-5。
         */
        private fun parseExponential(line: String, start: Int, end: Int, label: String): Double {
            require(end <= line.length) { "TLE $label 列位越界" }
            val sign = line.substring(start, start + 1).trim().let { if (it == "-") -1.0 else 1.0 }
            val mantissaStr = line.substring(start + 1, start + 6).trim()
            val exponentStr = line.substring(end - 1, end).trim()
            val mantissa = if (mantissaStr.isEmpty()) 0.0 else mantissaStr.toDouble() / 1e5
            val exponent = if (exponentStr.isEmpty()) 0.0 else exponentStr.toDouble()
            return sign * mantissa / (10.0).pow(exponent)
        }

        /**
         * 解析固定列位十进制字段（如倾角、平均运动）。
         */
        private fun parseField(line: String, start: Int, end: Int, label: String): Double {
            require(end <= line.length) { "TLE $label 列位越界" }
            val raw = line.substring(start, end).trim()
            return raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("TLE $label 解析失败：'$raw'")
        }
    }
}
