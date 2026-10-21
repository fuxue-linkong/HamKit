package com.example.hamkit.ui.util

/** 更新信息来自官网还是 GitHub Releases（用于决定是否强制要求 SHA-256）。 */
enum class UpdateSource {
    WEBSITE,
    GITHUB,
}

data class LatestVersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val changelog: String = "",
    /** 官网 update.json / Release 正文提供的 SHA-256（可为空，下载后仍会校验签名）。 */
    val sha256: String = "",
    val source: UpdateSource = UpdateSource.WEBSITE,
)

/**
 * 语义化版本号比较。
 *
 * 支持 `major.minor.patch` 及可选预发布后缀（如 `2.1.3-beta.1`）。
 * 遵循 SemVer：预发布版本低于同号正式版，但高于更低的正式版。
 *
 * @return latest 严格更新于 current 时返回 true
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    val l = parseSemVer(latest) ?: return false
    val c = parseSemVer(current) ?: return false
    for (i in 0 until 3) {
        if (l.core[i] != c.core[i]) return l.core[i] > c.core[i]
    }
    // 主版本号相同：预发布 < 正式版
    val lPre = l.prerelease
    val cPre = c.prerelease
    if (lPre.isEmpty() && cPre.isEmpty()) return false
    if (lPre.isEmpty()) return true
    if (cPre.isEmpty()) return false
    return comparePrerelease(lPre, cPre) > 0
}

private data class SemVer(
    val core: IntArray, // [major, minor, patch]
    val prerelease: List<String>,
)

private fun parseSemVer(raw: String): SemVer? {
    val s = raw.trim().removePrefix("v").removePrefix("V")
    val coreStr = s.substringBefore('-')
    val preStr = s.substringAfter('-', "")
    val parts = coreStr.split(".")
    if (parts.isEmpty() || parts.all { it.isEmpty() }) return null
    val core = IntArray(3)
    for (i in 0 until 3) {
        core[i] = parts.getOrNull(i)?.toIntOrNull() ?: 0
    }
    val prerelease = if (preStr.isEmpty()) emptyList() else preStr.split(".")
    return SemVer(core, prerelease)
}

private fun comparePrerelease(a: List<String>, b: List<String>): Int {
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val x = a.getOrNull(i)
        val y = b.getOrNull(i)
        if (x == null) return -1
        if (y == null) return 1
        val xn = x.toIntOrNull()
        val yn = y.toIntOrNull()
        val cmp = when {
            xn != null && yn != null -> xn.compareTo(yn)
            xn != null -> -1 // 数字 < 字母
            yn != null -> 1
            else -> x.compareTo(y)
        }
        if (cmp != 0) return cmp
    }
    return 0
}
