package com.example.hamkit.data.mcp

import android.content.Context
import com.example.hamkit.data.ai.tool.AiAppFacade

/**
 * 局域网 MCP 服务生命周期。
 *
 * MVP：服务跟随 AI 页面（attach 时启动、detach 时停止），App 被杀后停止。
 * 后续可改为前台服务常驻。
 */
object McpController {

    @Volatile
    private var server: McpServer? = null

    @Volatile
    private var facadeProvider: (() -> AiAppFacade?)? = null

    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context, provider: () -> AiAppFacade?) {
        appContext = context.applicationContext
        facadeProvider = provider
        maybeStart()
    }

    fun detach() {
        stop()
    }

    fun isRunning(): Boolean = server != null

    fun maybeStart(): Boolean {
        val context = appContext ?: return false
        val provider = facadeProvider ?: return false
        val store = McpSettingsStore(context)
        if (!store.enabled) {
            stop()
            return false
        }
        if (server != null) return true
        val instance = McpServer(store.port, store.ensureToken(), provider)
        return if (instance.start()) {
            server = instance
            true
        } else {
            false
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val store = McpSettingsStore(context.applicationContext)
        store.enabled = enabled
        return if (enabled) {
            appContext = context.applicationContext
            maybeStart()
        } else {
            stop()
            true
        }
    }

    fun setPort(context: Context, port: Int): Boolean {
        val store = McpSettingsStore(context.applicationContext)
        store.port = port
        if (store.enabled) {
            stop()
            return maybeStart()
        }
        return true
    }

    fun resetToken(context: Context): String {
        val store = McpSettingsStore(context.applicationContext)
        val token = store.resetToken()
        if (store.enabled) {
            stop()
            maybeStart()
        }
        return token
    }

    fun token(context: Context): String =
        McpSettingsStore(context.applicationContext).ensureToken()

    fun port(context: Context): Int =
        McpSettingsStore(context.applicationContext).port

    fun enabled(context: Context): Boolean =
        McpSettingsStore(context.applicationContext).enabled
}
