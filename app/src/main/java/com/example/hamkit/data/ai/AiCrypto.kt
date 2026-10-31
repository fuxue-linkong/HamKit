package com.example.hamkit.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM 加密工具（API Key 与会话历史共用）。
 *
 * 密钥由 Android Keystore 生成并保管，**私钥不出安全硬件**；磁盘上只留
 * `[version 1B][IV 12B][ciphertext + tag 16B]`。
 *
 * 为什么不用 EncryptedSharedPreferences：项目规则禁止新增第三方依赖，
 * 而 Keystore + GCM 是平台 API，足够。
 *
 * [encryptWithKey] / [decryptWithKey] 暴露给单元测试注入普通 JCE 密钥，
 * 避免测试依赖 Robolectric 的 Keystore 影子实现。
 */
object AiCrypto {

    private const val VERSION: Byte = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /** 加密后写入磁盘/Preferences 的完整字节块。 */
    fun encrypt(alias: String, plain: ByteArray, aad: ByteArray? = null): ByteArray =
        encryptWithKey(getOrCreateKey(alias), plain, aad)

    /**
     * 解密；密钥被清除、数据被篡改或版本不认识时返回 null（调用方按"无数据"处理）。
     */
    fun decrypt(alias: String, blob: ByteArray, aad: ByteArray? = null): ByteArray? {
        val key = loadKey(alias) ?: return null
        return decryptWithKey(key, blob, aad)
    }

    /** 删除 Keystore 中的密钥；删除后旧密文永久不可解（清除 Key / 重置历史时调用）。 */
    fun deleteKey(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    // ── 测试可注入的实现 ────────────────────────────────────────────────────

    internal fun encryptWithKey(
        key: SecretKey,
        plain: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        aad?.let { cipher.updateAAD(it) }
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "unexpected GCM IV length: ${iv.size}" }
        return ByteArray(1 + IV_LENGTH + encrypted.size).also { out ->
            out[0] = VERSION
            System.arraycopy(iv, 0, out, 1, IV_LENGTH)
            System.arraycopy(encrypted, 0, out, 1 + IV_LENGTH, encrypted.size)
        }
    }

    internal fun decryptWithKey(
        key: SecretKey,
        blob: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray? = runCatching {
        if (blob.size <= 1 + IV_LENGTH || blob[0] != VERSION) return null
        val iv = blob.copyOfRange(1, 1 + IV_LENGTH)
        val data = blob.copyOfRange(1 + IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        cipher.doFinal(data)
    }.getOrNull()

    /** 生成普通 AES-256 密钥，仅供单测使用。 */
    internal fun generateTestKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply {
            init(256, SecureRandom())
        }.generateKey()

    // ── Android Keystore ───────────────────────────────────────────────────

    private fun loadKey(alias: String): SecretKey? = runCatching {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    private fun getOrCreateKey(alias: String): SecretKey {
        loadKey(alias)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 聊天与历史读取需要静默使用，不能要求每次解锁
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
