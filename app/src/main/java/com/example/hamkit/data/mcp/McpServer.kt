package com.example.hamkit.data.mcp

import com.example.hamkit.data.ai.tool.AiAppFacade
import com.example.hamkit.data.ai.tool.AiToolCall
import com.example.hamkit.data.ai.tool.AiToolContext
import com.example.hamkit.data.ai.tool.AiToolExecutor
import com.example.hamkit.data.ai.tool.AiToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网 MCP 服务（JSON-RPC 2.0 over HTTP）。
 *
 * 不引入 Ktor/Netty：手写最小 HTTP/1.1 解析，单端点 `POST /mcp`，另有 `GET /health`。
 * 只暴露 [McpProtocol.readOnlySpecs] 里的只读工具；写工具不开放，避免无 UI 确认。
 */
class McpServer(
    private val port: Int,
    private val token: String,
    private val facadeProvider: () -> AiAppFacade?,
) {

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val callSeq = AtomicInteger(1)

    /** 返回 false 表示端口被占用/绑定失败。 */
    fun start(): Boolean {
        if (running) return true
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            running = true
            pool = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "hamkit-mcp").apply { isDaemon = true }
            }
            pool?.execute { acceptLoop(socket) }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        pool?.shutdownNow()
        serverSocket = null
        pool = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { pool?.execute { handle(client) } }
        }
    }

    private fun handle(socket: Socket) {
        runCatching {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.use { connection ->
                val input = connection.getInputStream()
                val reader = input.reader(Charsets.UTF_8).buffered()
                val requestLine = reader.readLine() ?: return@use
                val parts = requestLine.split(' ')
                if (parts.size < 2) return@use
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = readHeaders(reader)
                val body = readBody(reader, input, headers["content-length"]?.toIntOrNull() ?: 0)

                val (status, payload) = when {
                    method == "GET" && path == "/health" ->
                        "200 OK" to """{"ok":true}"""

                    method == "POST" && path == "/mcp" -> {
                        if (!McpProtocol.authorize(headers["authorization"], token)) {
                            "401 Unauthorized" to """{"error":"invalid token"}"""
                        } else {
                            "200 OK" to handleRpc(body)
                        }
                    }

                    else -> "404 Not Found" to """{"error":"not found"}"""
                }
                writeResponse(connection.getOutputStream(), status, payload)
            }
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER_CHARS) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] =
                    line.substring(index + 1).trim()
            }
        }
        return headers
    }

    private fun readBody(reader: BufferedReader, input: InputStream, length: Int): String {
        if (length <= 0 || length > MAX_BODY_CHARS) return ""
        val buffer = CharArray(length)
        var read = 0
        while (read < length) {
            val count = reader.read(buffer, read, length - read)
            if (count <= 0) break
            read += count
        }
        return String(buffer, 0, read)
    }

    private fun writeResponse(output: java.io.OutputStream, status: String, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun handleRpc(body: String): String {
        val request = runCatching { JSONObject(body) }.getOrNull()
            ?: return McpProtocol.error(null, -32700, "Parse error")
        val id = request.opt("id")
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "initialize" -> McpProtocol.result(
                id,
                JSONObject().apply {
                    put(
                        "protocolVersion",
                        params.optString("protocolVersion").ifBlank { McpProtocol.PROTOCOL_VERSION },
                    )
                    put("capabilities", JSONObject().put("tools", JSONObject()))
                    put(
                        "serverInfo",
                        JSONObject().apply {
                            put("name", "hamkit")
                            put("version", "1.0")
                        },
                    )
                },
            )

            "ping" -> McpProtocol.result(id, JSONObject())

            "tools/list" -> McpProtocol.result(
                id,
                JSONObject().put(
                    "tools",
                    McpProtocol.toolDescriptors(AiToolRegistry().specs()),
                ),
            )

            "tools/call" -> {
                val facade = facadeProvider()
                    ?: return McpProtocol.error(id, -32000, "App not ready")
                val name = params.optString("name")
                if (name.isBlank()) {
                    return McpProtocol.error(id, -32602, "Missing tool name")
                }
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val call = AiToolCall(
                    id = "mcp_${callSeq.getAndIncrement()}",
                    name = name,
                    argsJson = arguments.toString(),
                )
                val outcome = runBlocking {
                    AiToolExecutor(AiToolRegistry())
                        .execute(call, AiToolContext(facade) { _, _ -> })
                }
                McpProtocol.result(
                    id,
                    JSONObject().apply {
                        put(
                            "content",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", outcome.modelText)
                                },
                            ),
                        )
                        put("isError", outcome.error != null)
                    },
                )
            }

            else -> McpProtocol.error(id, -32601, "Method not found: $method")
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_HEADER_CHARS = 8 * 1024
        const val MAX_BODY_CHARS = 1024 * 1024
    }
}
