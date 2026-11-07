package com.example.hamkit.data.mcp

import android.content.Context
import java.security.SecureRandom

/** 局域网 MCP 的开关 / 端口 / Token 持久化。Token 需要展示给用户复制，故不加密。 */
class McpSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    /** 已有 token 直接返回，否则生成并保存。 */
    fun ensureToken(): String {
        prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return resetToken()
    }

    fun resetToken(): String {
        val token = "mcp_" + buildString {
            repeat(24) { append(HEX[random.nextInt(HEX.length)]) }
        }
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private companion object {
        const val PREFS_NAME = "mcp_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val DEFAULT_PORT = 8765
        const val HEX = "0123456789abcdef"
        val random = SecureRandom()
    }
}
