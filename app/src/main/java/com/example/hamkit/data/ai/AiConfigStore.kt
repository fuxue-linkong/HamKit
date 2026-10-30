package com.example.hamkit.data.ai

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * AI 配置持久化。
 *
 * 与 API Key 共用 `ai_settings` 文件，但 Key 单独加密（见 [AiKeyStore]）；
 * 这里只存非敏感参数。所有读取都对脏数据做回退，避免升级/手改导致崩溃。
 */
class AiConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AiConfig {
        val preset = AiPreset.fromName(prefs.getString(KEY_PRESET, null))
        val protocol = runCatching {
            AiProtocol.valueOf(prefs.getString(KEY_PROTOCOL, null) ?: preset.protocol.name)
        }.getOrDefault(preset.protocol)
        val baseUrl = prefs.getString(KEY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: preset.baseUrl
        val model = prefs.getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: preset.defaultModel
        return AiConfig(
            protocol = protocol,
            preset = preset,
            baseUrl = baseUrl,
            model = model,
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.6f),
            maxOutputTokens = prefs.getInt(KEY_MAX_OUTPUT_TOKENS, 2048),
            stream = prefs.getBoolean(KEY_STREAM, true),
            autoRetry = prefs.getBoolean(KEY_AUTO_RETRY, true),
            saveHistory = prefs.getBoolean(KEY_SAVE_HISTORY, true),
            historyLimit = prefs.getInt(KEY_HISTORY_LIMIT, 200),
            allowCleartext = prefs.getBoolean(KEY_ALLOW_CLEARTEXT, false),
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty(),
            confirmSideEffects = prefs.getBoolean(KEY_CONFIRM_SIDE_EFFECTS, true),
            disabledTools = prefs.getString(KEY_DISABLED_TOOLS, "").orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
                .toSet(),
        ).normalized()
    }

    fun save(config: AiConfig) {
        val cfg = config.normalized()
        prefs.edit()
            .putString(KEY_PRESET, cfg.preset.name)
            .putString(KEY_PROTOCOL, cfg.protocol.name)
            .putString(KEY_BASE_URL, cfg.baseUrl)
            .putString(KEY_MODEL, cfg.model)
            .putFloat(KEY_TEMPERATURE, cfg.temperature)
            .putInt(KEY_MAX_OUTPUT_TOKENS, cfg.maxOutputTokens)
            .putBoolean(KEY_STREAM, cfg.stream)
            .putBoolean(KEY_AUTO_RETRY, cfg.autoRetry)
            .putBoolean(KEY_SAVE_HISTORY, cfg.saveHistory)
            .putInt(KEY_HISTORY_LIMIT, cfg.historyLimit)
            .putBoolean(KEY_ALLOW_CLEARTEXT, cfg.allowCleartext)
            .putString(KEY_SYSTEM_PROMPT, cfg.systemPrompt)
            .putString(KEY_DISABLED_TOOLS, cfg.disabledTools.joinToString(","))
            .putBoolean(KEY_CONFIRM_SIDE_EFFECTS, cfg.confirmSideEffects)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "ai_settings"

        private const val KEY_PRESET = "preset"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
        private const val KEY_STREAM = "stream"
        private const val KEY_AUTO_RETRY = "auto_retry"
        private const val KEY_SAVE_HISTORY = "save_history"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_DISABLED_TOOLS = "disabled_tools"
        private const val KEY_CONFIRM_SIDE_EFFECTS = "confirm_side_effects"
    }
}

/**
 * API Key 的本机加密存储。
 *
 * 安全约束：
 * - 磁盘上只有 AES-GCM 密文，明文只在发起请求时短暂存在；
 * - 不写日志、不进导出/备份（`allowBackup=false`）；
 * - 清除时同时删掉 Keystore 条目，避免残留密钥。
 */
class AiKeyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(AiConfigStore.PREFS_NAME, Context.MODE_PRIVATE)

    fun save(key: String) {
        val plain = key.trim().toByteArray(StandardCharsets.UTF_8)
        if (plain.isEmpty()) {
            clear()
            return
        }
        val encrypted = AiCrypto.encrypt(ALIAS, plain, AAD)
        prefs.edit()
            .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? {
        val encoded = prefs.getString(KEY_CIPHER, null) ?: return null
        val blob = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
        val plain = AiCrypto.decrypt(ALIAS, blob, AAD) ?: return null
        return String(plain, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
    }

    fun hasKey(): Boolean = load() != null

    fun clear() {
        prefs.edit().remove(KEY_CIPHER).apply()
        AiCrypto.deleteKey(ALIAS)
    }

    /** 设置页展示用（`sk-••••7f3a`），不暴露完整 Key。 */
    fun masked(): String? {
        val key = load() ?: return null
        return if (key.length <= 8) {
            "••••"
        } else {
            key.take(3) + "••••" + key.takeLast(4)
        }
    }

    companion object {
        private const val KEY_CIPHER = "api_key_cipher"
        private const val ALIAS = "hamkit_ai_api_key_v1"
        private val AAD = "api_key".toByteArray(StandardCharsets.UTF_8)
    }
}
