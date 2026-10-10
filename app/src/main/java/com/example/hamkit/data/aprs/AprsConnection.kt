package com.example.hamkit.data.aprs

import android.util.Log
import com.example.hamkit.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class AprsConnection(
    private val config: AprsConfig,
    private val listener: AprsConnectionListener
) {
    interface AprsConnectionListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onPacketReceived(packet: String)
        fun onError(error: String)
    }

    private val TAG = "AprsConnection"
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val baseReconnectDelayMs = 5000L
    private val maxReconnectDelayMs = 60000L

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext
        running.set(true)

        while (running.get() && reconnectAttempts < maxReconnectAttempts) {
            try {
                Log.d(TAG, "Connecting to ${config.server}:${config.port}")

                socket = Socket().apply {
                    connect(InetSocketAddress(config.server, config.port), 15000)
                    soTimeout = 120000
                    keepAlive = true
                }

                writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))

                // 合规（HK-BUG-003）：不在本地计算 passcode。
                // 用户未自行填写时使用 READ_ONLY_PASSCODE（pass -1）建立只读会话：
                // 可接收报文，服务器拒绝本连接注入的任何报文。
                val loginLine = AprsPacket.formatLogin(
                    config.callsign,
                    config.ssid,
                    config.effectivePasscode,
                    "HamKit-${BuildConfig.VERSION_NAME}"
                )
                writer?.println(loginLine)

                if (config.isReadOnly) {
                    Log.i(TAG, "Connecting in READ-ONLY mode (no passcode supplied, pass -1)")
                }

                // APRS-IS 协议：不发 filter 时服务器仅推送发给本 callsign 的直接流量。
                // m/N filter 需入口站点已上报位置，未上报时服务器无法计算"最近"故不推送。
                // 本项目位置上报默认关闭，故默认用地理范围 r/35/105/1000（中国中部 1000km）。
                val effectiveFilter = config.filter.ifEmpty { "r/35/105/1000" }
                writer?.println("# filter $effectiveFilter")

                connected.set(true)
                reconnectAttempts = 0
                listener.onConnected()

                readLoop()

            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(TAG, "Connection error: ${e.message}")
                listener.onError(e.message ?: "Connection error")
                connected.set(false)
                listener.onDisconnected(e.message ?: "Disconnected")
            }

            cleanup()

            if (running.get() && reconnectAttempts < maxReconnectAttempts) {
                val delay = (baseReconnectDelayMs * (1 shl reconnectAttempts.coerceAtMost(4)))
                    .coerceAtMost(maxReconnectDelayMs)
                reconnectAttempts++
                Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
                kotlinx.coroutines.delay(delay)
            }
        }
        running.set(false)
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        try {
            while (running.get() && isActive && connected.get()) {
                val line = reader?.readLine()
                if (line == null) {
                    connected.set(false)
                    listener.onDisconnected("Server closed connection")
                    break
                }
                if (line.isNotEmpty() && line[0] != '#') {
                    Log.d(TAG, "RX: $line")
                    listener.onPacketReceived(line)
                } else if (line.isNotEmpty()) {
                    Log.d(TAG, "Server: $line")
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                connected.set(false)
                listener.onError(e.message ?: "Read error")
            }
        }
    }

    suspend fun sendPacket(packet: String) = withContext(Dispatchers.IO) {
        try {
            if (connected.get() && writer != null) {
                Log.d(TAG, "TX: $packet")
                writer?.println(packet)
                writer?.flush()
                true
            } else {
                Log.w(TAG, "Cannot send packet: not connected")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            false
        }
    }

    fun isConnected(): Boolean = connected.get() && socket?.isConnected == true

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        running.set(false)
        connected.set(false)
        cleanup()
    }

    private fun cleanup() {
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        reader = null
        writer = null
        socket = null
    }
}
