package com.example.hamkit.ui.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkVerificationResult(
    val ok: Boolean,
    val message: String = "",
)

/**
 * 应用内更新包的最终校验。
 *
 * 仅靠 HTTPS 不够：官网后台/更新清单一旦被接管，攻击者可以把 `downloadUrl` 指向任意 APK。
 * 安装前必须同时满足：
 * 1. 可选但推荐的 SHA-256 与更新清单一致；
 * 2. 包名与当前应用一致；
 * 3. versionCode 大于当前安装版本；
 * 4. APK 签名证书与当前应用一致（含签名轮换历史）。
 */
object ApkVerifier {

    fun verify(
        context: Context,
        apkFile: File,
        expectedSha256: String?,
    ): ApkVerificationResult {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            return ApkVerificationResult(false, "更新包不存在或为空")
        }

        val rawSha = expectedSha256?.trim().orEmpty()
        if (rawSha.isNotEmpty()) {
            val normalized = normalizeSha256(rawSha)
                ?: return ApkVerificationResult(false, "更新源提供的 SHA-256 格式非法")
            val actual = sha256(apkFile)
            if (!actual.equals(normalized, ignoreCase = true)) {
                return ApkVerificationResult(false, "更新包 SHA-256 校验失败")
            }
        }

        val pm = context.packageManager
        val archive = getPackageArchiveInfo(pm, apkFile.absolutePath)
            ?: return ApkVerificationResult(false, "无法读取更新包信息")
        if (archive.packageName != context.packageName) {
            return ApkVerificationResult(false, "更新包包名与当前应用不一致")
        }

        val current = getPackageInfo(pm, context.packageName)
            ?: return ApkVerificationResult(false, "无法读取当前应用信息")
        if (versionCode(archive) <= versionCode(current)) {
            return ApkVerificationResult(false, "更新包版本号未增加")
        }

        val currentCerts = certHashes(current, history = true)
        val archiveCerts = certHashes(archive, history = false)
        if (currentCerts.isEmpty() || archiveCerts.isEmpty()) {
            return ApkVerificationResult(false, "无法读取签名证书")
        }
        if (!currentCerts.containsAll(archiveCerts)) {
            return ApkVerificationResult(false, "更新包签名与当前应用不一致")
        }

        return ApkVerificationResult(true)
    }

    /** 规范化 SHA-256：容忍冒号/空格分隔，统一小写；格式非法返回 null。 */
    internal fun normalizeSha256(value: String): String? {
        val normalized = value.trim()
            .replace(":", "")
            .replace(" ", "")
            .lowercase()
        return normalized.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
        } else {
            pm.getPackageInfo(packageName, signatureFlags())
        }

    @Suppress("DEPRECATION")
    private fun getPackageArchiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
        } else {
            pm.getPackageArchiveInfo(path, signatureFlags())
        }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    @Suppress("DEPRECATION")
    private fun certHashes(info: PackageInfo, history: Boolean): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (history) signingInfo.signingCertificateHistory else signingInfo.apkContentsSigners
        } else {
            info.signatures ?: return emptySet()
        }
        return signatures
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .toHex()
            }
            .toSet()
    }
}
