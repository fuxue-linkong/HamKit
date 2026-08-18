package com.example.hamkit.data.satellite

import android.util.Log
import com.example.hamkit.data.satellite.predict.TleElements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.example.hamkit.data.network.HttpClientProvider
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 协程安全的 [runCatching]：捕获异常时重新抛出 [CancellationException]，
 * 避免破坏 Kotlin 协程的结构化并发语义。
 */
private suspend inline fun <R> runCatchingCancellable(block: suspend () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * 带 TLE 数据来源标记的包装类。
 */
data class SourcedTLE(
    val tle: TleElements,
    val source: String, // "CT" / "SNOGS" / "ALL"
    val status: String = "", // AMSAT 状态：Heard / Telemetry Only / Not Heard / Crew Active
    val rawLines: Array<String> = arrayOf("", "", "") // 原始三行 TLE，用于本地缓存序列化
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourcedTLE) return false
        return tle == other.tle && source == other.source && status == other.status &&
            rawLines.contentEquals(other.rawLines)
    }

    override fun hashCode(): Int {
        var result = tle.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + rawLines.contentHashCode()
        return result
    }
}

/**
 * 卫星 TLE 数据源，同时从 SatNOGS 和 CelesTrak 获取并合并去重，
 * 再附加 AMSAT 状态报告。返回全部业余卫星（不做 catalog 过滤），
 * 不在 SatelliteCatalog 中的卫星 modes 为空（UI 显示"未知"）。
 */
data class TleSourceUrls(
    val satnogs: String,
    val amateur: String,
    val active: String,
    val iss: String,
) {
    companion object {
        val Cdn = TleSourceUrls(
            satnogs = "https://tle.hamkit.click/tle/satnogs.3le",
            amateur = "https://tle.hamkit.click/tle/amateur.3le",
            active = "https://tle.hamkit.click/tle/active.3le",
            iss = "https://tle.hamkit.click/tle/iss.3le",
        )
    }
}

class SatelliteDataSource(
    private val sourceUrls: TleSourceUrls = TleSourceUrls.Cdn,
    // 基于共享单例派生：共享连接池/线程池，仅覆盖本服务的超时配置
    private val client: okhttp3.OkHttpClient = HttpClientProvider.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    // active 源（全量活跃卫星 CSV，gzip 后约 900KB）单独用更长读取超时，
    // 避免弱网下 30s 内未读完被静默跳过，导致卫星列表只有 ~600 颗而非 16k+。
    private val activeClient: okhttp3.OkHttpClient = HttpClientProvider.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val fetchAmsatStatus: suspend () -> Map<String, String> = {
        AmsatStatusApiService().fetchStatusSummaries()
    },
) {

    /**
     * 获取业余卫星 TLE 列表（返回数据源提供的全部业余卫星，不做 catalog 过滤）。
     *
     * 数据源（Phase 3 扩展，均可独立开关，任一失败仅记录日志不影响其余源）：
     * 1. CelesTrak amateur 分组（[enableAmateur]，3le 文本）
     * 2. CelesTrak satnogs 分组（[enableSatnogs]，3le 文本）
     * 3. CelesTrak active 分组（[enableActive]，CSV，含全部活跃卫星）
     * 4. 自定义 URL（[customUrl]，自动识别 3le 或 CSV 格式）
     * 5. ISS/ARISS 单星源（恒定开启，补全 ISS 25544）
     *
     * 按 NORAD 编号合并去重：现有源（amateur/satnogs）优先，active/自定义源
     * 只补全不在列表中的卫星；同时出现在 amateur 与 satnogs 的卫星标记为 ALL。
     * 任一 TLE 源失败时容忍；仅当所有启用的 TLE 源都失败时才抛 IOException，
     * 避免返回空列表覆盖本地缓存。AMSAT 状态源失败不影响 TLE 结果。
     *
     * @param enableAmateur 是否启用 CelesTrak amateur 分组（默认 true）
     * @param enableSatnogs 是否启用 CelesTrak satnogs 分组（默认 true）
     * @param enableActive 是否启用 CelesTrak active 分组（默认 true，含全部活跃卫星 16k+）
     * @param customUrl 可选自定义 TLE URL（3le 或 CSV，默认 null）
     */
    suspend fun fetchAmateurTLEs(
        enableAmateur: Boolean = true,
        enableSatnogs: Boolean = true,
        enableActive: Boolean = true,
        customUrl: String? = null,
    ): List<SourcedTLE> = withContext(Dispatchers.IO) {
        coroutineScope {
            val tleDeferreds = mutableListOf<kotlinx.coroutines.Deferred<Result<List<SourcedTLE>>>>()
            if (enableSatnogs) {
                tleDeferreds += async { runCatchingCancellable { fetchSatnogsTLEs() } }
            }
            if (enableAmateur) {
                tleDeferreds += async { runCatchingCancellable { fetchCelesTrakTLEs() } }
            }
            val activeResultIndex = if (enableActive) {
                tleDeferreds.size.also {
                    tleDeferreds += async { runCatchingCancellable { fetchActiveTLEs() } }
                }
            } else {
                null
            }
            if (!customUrl.isNullOrBlank()) {
                tleDeferreds += async { runCatchingCancellable { fetchCustomTLEs(customUrl) } }
            }
            // ISS/ARISS 单星源：恒定补全，失败不影响结果
            val issDeferred = async { runCatchingCancellable { fetchIssTLE() } }
            // AMSAT 状态与 TLE 源正交，始终附加状态标签
            val amsatStatusDeferred = async { runCatchingCancellable { fetchAmsatStatus() } }

            val tleResults = tleDeferreds.awaitAll()
            val issResult = issDeferred.await()
            val amsatStatusResult = amsatStatusDeferred.await()

            // active 是完整卫星目录的唯一来源。若它失败或只解析出异常少的记录，
            // 不允许用 satnogs/amateur 的约 600 条部分结果覆盖已有 16k+ 缓存。
            activeResultIndex?.let { index ->
                requireCompleteActiveCatalog(
                    tleResults[index].getOrElse { throwable ->
                        throw IOException("tle.hamkit.click 全量 active 卫星源下载失败", throwable)
                    }
                )
            }

            // 所有启用的 TLE 源都失败时抛异常，避免返回空列表覆盖本地缓存。
            // AMSAT 状态失败不在此判定内。
            if (tleResults.isEmpty()) {
                throw IOException("未启用任何 TLE 数据源，无法拉取卫星数据")
            }
            if (tleResults.all { it.isFailure }) {
                throw IOException(
                    "TLE 下载失败：" + tleResults.mapIndexed { i, r ->
                        "源$i=${r.exceptionOrNull()?.message}"
                    }.joinToString(", ")
                )
            }
            // 单个源失败：记录日志后继续（保持"全失败才抛异常"的容错语义）
            tleResults.forEachIndexed { i, r ->
                if (r.isFailure) {
                    Log.w(TAG, "TLE 源 $i 拉取失败，已跳过", r.exceptionOrNull())
                }
            }

            // 合并顺序即优先级：amateur/satnogs 在前（含 ALL 标记），
            // active/custom 仅补全缺失的 NORAD 号；ISS 单星源最后兜底。
            val merged = LinkedHashMap<Int, SourcedTLE>()
            // 先合并 satnogs（若启用），再合并 amateur（若启用），标记 ALL
            if (enableSatnogs) {
                tleResults[0].getOrNull()?.forEach { stle ->
                    merged[stle.tle.catnum] = stle
                }
            }
            var amateurIdx = if (enableSatnogs) 1 else 0
            if (enableAmateur) {
                tleResults[amateurIdx].getOrNull()?.forEach { stle ->
                    val existing = merged[stle.tle.catnum]
                    merged[stle.tle.catnum] = if (existing != null && existing.source != stle.source) {
                        existing.copy(source = "ALL")
                    } else {
                        stle
                    }
                }
                amateurIdx++
            }
            // 其余源（active / custom）：只补全不在列表中的卫星
            for (i in amateurIdx until tleResults.size) {
                tleResults[i].getOrNull()?.forEach { stle ->
                    if (!merged.containsKey(stle.tle.catnum)) {
                        merged[stle.tle.catnum] = stle
                    }
                }
            }
            // ISS 单星源兜底
            issResult.getOrNull()?.forEach { stle ->
                if (!merged.containsKey(stle.tle.catnum)) {
                    merged[stle.tle.catnum] = stle
                }
            }

            // 附加 AMSAT 状态（失败时不影响 TLE 结果）
            val statusMap = amsatStatusResult.getOrNull() ?: emptyMap()
            merged.values.toList().map { sourcedTle ->
                val amsatName = SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[sourcedTle.tle.catnum]
                val status = if (amsatName != null) statusMap[amsatName] ?: "" else ""
                sourcedTle.copy(status = status)
            }
        }
    }

    /**
     * 获取 SatNOGS 维护的卫星 TLE（参考 Look4Sat 实现）。
     *
     * 不直接请求 db.satnogs.org DB API（国内访问不稳定），而是请求 CelesTrak 整理好的
     * `satnogs` 分组（3le 文本格式）。CelesTrak 已过滤失效/再入卫星，无需再做 alive 过滤。
     *
     * 实测约 649 颗，含 SatNOGS 跟踪的全部可观测卫星；不在 SatelliteCatalog 中的
     * 卫星 modes 为空列表（UI 显示"未知"），AMSAT 状态为空字符串。
     */
    private fun fetchSatnogsTLEs(): List<SourcedTLE> {
        val request = Request.Builder()
            .url(sourceUrls.satnogs)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("tle.hamkit.click satnogs 源请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("tle.hamkit.click satnogs 源响应为空")
            val triples = parseThreeLineTLEs(body)
            val tles = mutableListOf<SourcedTLE>()
            for ((tle0, tle1, tle2) in triples) {
                // NORAD 编号位于 line1 的第 3-7 列（0-based 索引 2..6）
                if (tle1.length < 7) continue
                val noradCatId = tle1.substring(2, 7).trim().toIntOrNull() ?: continue
                if (noradCatId <= 0) continue

                try {
                    tles.add(
                        SourcedTLE(
                            tle = TleElements.fromThreeLines(arrayOf(tle0, tle1, tle2)),
                            source = "SNOGS",
                            rawLines = arrayOf(tle0, tle1, tle2)
                        )
                    )
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }
            if (tles.isEmpty()) {
                return emptyList()
            }
            return tles
        }
    }

    /**
     * 从 CelesTrak 批量获取业余卫星 TLE，返回全部（不做 catalog 过滤）。
     *
     * 使用 gp.php 3le 文本接口（GROUP=amateur&FORMAT=3le），返回标准三行 TLE：
     * 第一行卫星名称，后两行为 TLE line1/line2，可直接喂给自研
     * [com.example.hamkit.data.satellite.predict.TleElements] 解析器。
     * 不在 SatelliteCatalog 中的卫星 modes 为空列表（UI 显示"未知"）。
     *
     * NORAD 编号从 line1 第 3-7 列解析（标准 TLE 格式），避免依赖名称行匹配。
     */
    private fun fetchCelesTrakTLEs(): List<SourcedTLE> {
        val request = Request.Builder()
            .url(sourceUrls.amateur)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("tle.hamkit.click amateur 源请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("tle.hamkit.click amateur 源响应为空")
            val tles = mutableListOf<SourcedTLE>()

            val triples = parseThreeLineTLEs(body)
            for ((tle0, tle1, tle2) in triples) {
                // NORAD 编号位于 line1 的第 3-7 列（0-based 索引 2..6）
                if (tle1.length < 7) continue
                val noradCatId = tle1.substring(2, 7).trim().toIntOrNull() ?: continue

                try {
                    tles.add(
                        SourcedTLE(
                            tle = TleElements.fromThreeLines(arrayOf(tle0, tle1, tle2)),
                            source = "CT",
                            rawLines = arrayOf(tle0, tle1, tle2)
                        )
                    )
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }
            if (tles.isEmpty()) {
                return emptyList()
            }
            return tles
        }
    }

    /**
     * 解析标准三行文本格式 TLE，返回 (name, line1, line2) 三元组列表。
     * 适用于 CelesTrak 3le 格式：每三行为一组，依次是名称行、line1、line2。
     */
    private fun parseThreeLineTLEs(text: String): List<Triple<String, String, String>> {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<Triple<String, String, String>>()
        var i = 0
        while (i < lines.size) {
            val isNameLine = !lines[i].startsWith("1 ") && !lines[i].startsWith("2 ")
            val name = if (isNameLine) lines[i] else ""
            val line1Index = if (isNameLine) i + 1 else i
            val line2Index = line1Index + 1

            if (line2Index >= lines.size) break

            val line1 = lines[line1Index]
            val line2 = lines[line2Index]

            if (line1.startsWith("1 ") && line2.startsWith("2 ")) {
                result.add(Triple(name, line1, line2))
            }

            i = line2Index + 1
        }
        return result
    }

    /**
     * 不允许把 16k+ active 目录降级为 satnogs/amateur 的约 672 条部分结果。
     * 该校验必须在合并前通过，调用方才会写入本地缓存。
     */
    @Throws(IOException::class)
    internal fun requireCompleteActiveCatalog(activeTles: List<SourcedTLE>) {
        if (activeTles.size < MIN_ACTIVE_TLE_COUNT) {
            throw IOException(
                "tle.hamkit.click 全量 active 卫星源数据异常：仅解析出 ${activeTles.size} 条，" +
                    "预期至少 $MIN_ACTIVE_TLE_COUNT 条"
            )
        }
    }

    /**
     * 从 tle.hamkit.click 拉取全部活跃卫星（3le 文本格式，16k+ 颗）。
     *
     * 包含所有在轨活跃卫星（含 ISS、GEO 通信星等非业余卫星）。
     * 来源标记为 "ACTIVE"；合并时仅在 amateur/satnogs 缺失的 NORAD 号上补全。
     */
    private fun fetchActiveTLEs(): List<SourcedTLE> {
        val request = Request.Builder()
            .url(sourceUrls.active)
            .build()

        activeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("tle.hamkit.click active 源请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("tle.hamkit.click active 源响应为空")
            val tles = mutableListOf<SourcedTLE>()
            for ((tle0, tle1, tle2) in parseThreeLineTLEs(body)) {
                if (tle1.length < 7) continue
                val noradCatId = tle1.substring(2, 7).trim().toIntOrNull() ?: continue
                if (noradCatId <= 0) continue
                try {
                    tles.add(
                        SourcedTLE(
                            tle = TleElements.fromThreeLines(arrayOf(tle0, tle1, tle2)),
                            source = "ACTIVE",
                            rawLines = arrayOf(tle0, tle1, tle2)
                        )
                    )
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }
            return tles
        }
    }

    /**
     * 拉取 ISS / ARISS 单星 TLE（CATNR=25544，3le 格式）。
     * 用于兜底补全 ISS，保证业余中继（ARISS）始终在列表中。
     * 来源标记为 "ARISS"。
     */
    private fun fetchIssTLE(): List<SourcedTLE> {
        val request = Request.Builder()
            .url(sourceUrls.iss)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("tle.hamkit.click ISS 源请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("tle.hamkit.click ISS 源响应为空")
            val tles = mutableListOf<SourcedTLE>()
            for ((tle0, tle1, tle2) in parseThreeLineTLEs(body)) {
                val noradCatId = tle1.substring(2, 7).trim().toIntOrNull() ?: continue
                if (noradCatId <= 0) continue
                try {
                    tles.add(
                        SourcedTLE(
                            tle = TleElements.fromThreeLines(arrayOf(tle0, tle1, tle2)),
                            source = "ARISS",
                            rawLines = arrayOf(tle0, tle1, tle2)
                        )
                    )
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }
            return tles
        }
    }

    /**
     * 从自定义 URL 拉取 TLE。自动识别格式：
     * - 内容含 CelesTrak CSV 表头（OBJECT_NAME）→ [TleParser.parseActiveCsv]
     * - 否则按标准三行文本（3le）解析
     * 来源标记为 "CUSTOM"。
     */
    private fun fetchCustomTLEs(url: String): List<SourcedTLE> {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("自定义 TLE 请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("自定义 TLE 响应为空")
            val tles = mutableListOf<SourcedTLE>()

            if (body.contains("OBJECT_NAME")) {
                for ((name, line1, line2) in TleParser.parseActiveCsv(body)) {
                    val noradCatId = line1.substring(2, 7).trim().toIntOrNull() ?: continue
                    if (noradCatId <= 0) continue
                    try {
                        tles.add(
                            SourcedTLE(
                                tle = TleElements.fromThreeLines(arrayOf(name, line1, line2)),
                                source = "CUSTOM",
                                rawLines = arrayOf(name, line1, line2)
                            )
                        )
                    } catch (_: IllegalArgumentException) {
                        // 跳过解析失败的 TLE
                    }
                }
            } else {
                for ((tle0, tle1, tle2) in parseThreeLineTLEs(body)) {
                    val noradCatId = tle1.substring(2, 7).trim().toIntOrNull() ?: continue
                    if (noradCatId <= 0) continue
                    try {
                        tles.add(
                            SourcedTLE(
                                tle = TleElements.fromThreeLines(arrayOf(tle0, tle1, tle2)),
                                source = "CUSTOM",
                                rawLines = arrayOf(tle0, tle1, tle2)
                            )
                        )
                    } catch (_: IllegalArgumentException) {
                        // 跳过解析失败的 TLE
                    }
                }
            }
            return tles
        }
    }

    companion object {
        private const val TAG = "SatelliteDataSource"

        // 当前 active.csv 有 16k+ 条。10k 是容忍目录正常波动、又能识别部分源
        // 降级（satnogs + amateur 约 672 条）的保守下限。
        internal const val MIN_ACTIVE_TLE_COUNT = 10_000
    }
}
