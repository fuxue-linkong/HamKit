package com.example.hamkit.ui.util

import com.example.hamkit.radioApp
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 官网更新清单（优先源）；服务器不可达时回退 GitHub Releases。 */
private const val UPDATE_JSON_URL = "https://hamkit.click/update.json"

/** GitHub Releases API：拉取最近若干 release（含预发布），取最新非草稿（回退源） */
private const val RELEASES_URL =
    "https://api.github.com/repos/fuxue-linkong/Dual-zone_network_positioning/releases?per_page=5"

/** 官网使用的 CloudFront APK 分发域名，源站为 S3。 */
private const val APK_CDN_HOST = "apk.hamkit.click"

/** 允许下载更新包的域名。新增分发域名时必须同步更新这里，不接受任意 URL。 */
private val ALLOWED_UPDATE_HOSTS = setOf(APK_CDN_HOST)

/**
 * 更新地址白名单校验。
 *
 * 只接受 HTTPS + 官方 APK CDN；官网后台即使被接管，也无法把用户引到任意域名。
 */
internal fun isAllowedUpdateUrl(url: String): Boolean = runCatching {
    val parsed = url.toHttpUrl()
    parsed.scheme == "https" &&
        parsed.host in ALLOWED_UPDATE_HOSTS &&
        parsed.port == 443
}.getOrDefault(false)

/**
 * 查询最新版本信息。
 *
 * 优先从官网 `hamkit.click/update.json` 拉取更新日志（字段与 [LatestVersionInfo] 一一对应）；
 * 任何失败（连接超时 / DNS 失败 / 非 2xx / JSON 解析失败 / `versionCode <= 0` /
 * `downloadUrl` 为空）均视为服务器不可达，回退到 GitHub Releases API。
 *
 * GitHub 回退路径使用 releases 列表接口（而非 /releases/latest，后者会跳过预发布版本），
 * versionCode 提取优先级：
 * 1. release body 中的 `Version: <name> (<code>)`（release.yml 自动发布时写入）；
 * 2. 手工上传的 APK 文件名 `HamKit_<name>_<code>(-release).apk`。
 * GitHub 路径下 APK 文件名来自 GitHub asset，但下载流量走 `apk.hamkit.click` 对应的
 * CloudFront 分配；官网 JSON 路径下 `downloadUrl` 由 JSON 直接给出。
 *
 * @return 最新版本信息；网络失败或两源均无可用数据时返回默认空值
 */
fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(radioApp)) return LatestVersionInfo()

    // 官网请求用短超时客户端，避免 hamkit.click 不可达时用户长时间等待才回退 GitHub。
    // newBuilder() 复用 radioApp.okhttpClient 的连接池与缓存，开销可忽略。
    val quickClient = radioApp.okhttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val fromWebsite = runCatching {
        quickClient.newCall(Request.Builder().url(UPDATE_JSON_URL).build()).execute()
            .use { response ->
                if (!response.isSuccessful) return@runCatching null
                parseUpdateJson(response.body.string())
            }
    }.getOrNull()
    if (fromWebsite != null &&
        fromWebsite.versionCode > 0 &&
        fromWebsite.downloadUrl.isNotEmpty()
    ) {
        return fromWebsite
    }

    // 回退到 GitHub Releases。
    return checkNewVersionFromGitHub()
}

/**
 * 从 GitHub Releases API 拉取最新 release 信息，并将 APK asset 映射到官网 CDN。
 *
 * @return 最新版本信息；网络失败或无 release 时返回默认空值
 */
private fun checkNewVersionFromGitHub(): LatestVersionInfo {
    val defaultValue = LatestVersionInfo()
    runCatching {
        radioApp.okhttpClient.newCall(Request.Builder().url(RELEASES_URL).build()).execute()
            .use { response ->
                if (!response.isSuccessful) return defaultValue
                return parseLatestVersion(response.body.string())
            }
    }
    return defaultValue
}

/**
 * 解析官网 update.json（单个 JSON 对象，字段与 [LatestVersionInfo] 一一对应）。
 *
 * 字段缺失或非法 JSON 时返回空 [LatestVersionInfo]，由调用方据此触发 GitHub 回退。
 */
internal fun parseUpdateJson(body: String): LatestVersionInfo = runCatching {
    val obj = org.json.JSONObject(body)
    LatestVersionInfo(
        versionCode = obj.optInt("versionCode", 0),
        versionName = obj.optString("versionName"),
        downloadUrl = obj.optString("downloadUrl"),
        changelog = obj.optString("changelog"),
        sha256 = obj.optString("sha256"),
        source = UpdateSource.WEBSITE,
    )
}.getOrDefault(LatestVersionInfo())

/** 将 GitHub Releases API 响应转换为应用更新信息。 */
internal fun parseLatestVersion(body: String): LatestVersionInfo {
    val releases = org.json.JSONArray(body)

    var release: org.json.JSONObject? = null
    for (i in 0 until releases.length()) {
        val candidate = releases.getJSONObject(i)
        if (!candidate.optBoolean("draft", false)) {
            release = candidate
            break
        }
    }
    release ?: return LatestVersionInfo()

    val changelog = release.optString("body")
    val versionName = release.optString("tag_name").removePrefix("v")
    val assets = release.optJSONArray("assets") ?: return LatestVersionInfo()
    var apkFileName = ""
    for (i in 0 until assets.length()) {
        val name = assets.getJSONObject(i).optString("name")
        if (name.endsWith(".apk", ignoreCase = true)) {
            apkFileName = name
            break
        }
    }

    val downloadUrl = if (apkFileName.isEmpty()) "" else buildCdnApkUrl(apkFileName)
    var versionCode = Regex("Version:\\s*\\S+\\s*\\((\\d+)\\)")
        .find(changelog)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    if (versionCode == 0 && apkFileName.isNotEmpty()) {
        versionCode = Regex("_(\\d+)(?:-release)?\\.apk$", RegexOption.IGNORE_CASE)
            .find(apkFileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // GitHub Release 正文里若写了 SHA256，就拿来用；没有则只做签名/包名校验。
    val sha256 = Regex("(?i)SHA-?256\\s*[:=]?\\s*([0-9a-f]{64})")
        .find(changelog)
        ?.groupValues
        ?.get(1)
        .orEmpty()

    return LatestVersionInfo(
        versionCode = versionCode,
        versionName = versionName,
        downloadUrl = downloadUrl,
        changelog = changelog,
        sha256 = sha256,
        source = UpdateSource.GITHUB,
    )
}

internal fun buildCdnApkUrl(apkFileName: String): String = HttpUrl.Builder()
    .scheme("https")
    .host(APK_CDN_HOST)
    .addPathSegment("release")
    .addPathSegment(apkFileName)
    .build()
    .toString()

/**
 * 下载 APK 到指定文件，回调下载进度。
 *
 * @param url 下载地址（官网 CloudFront 分发地址）
 * @param destFile 目标文件（通常位于 cacheDir，由 FileProvider 共享）
 * @param onProgress 进度回调，参数为 0-100 的整数；在 IO 线程触发
 * @return 下载成功返回 true，失败返回 false
 */
fun downloadApk(url: String, destFile: File, onProgress: (Int) -> Unit): Boolean {
    if (!isAllowedUpdateUrl(url)) return false
    val partialFile = File(destFile.parentFile, "${destFile.name}.part")
    val success = runCatching {
        radioApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    false
                } else {
                    val totalBytes = body.contentLength()
                    destFile.parentFile?.mkdirs()
                    partialFile.delete()
                    var lastReported = -1
                    body.byteStream().use { input ->
                        partialFile.outputStream().use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var readBytes = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                readBytes += read
                                if (totalBytes > 0) {
                                    val progress = (readBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
                                    if (progress != lastReported) {
                                        lastReported = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                    partialFile.length() > 0L &&
                        (!destFile.exists() || destFile.delete()) &&
                        partialFile.renameTo(destFile)
                }
            }
    }.getOrDefault(false)
    if (!success) partialFile.delete()
    return success
}
