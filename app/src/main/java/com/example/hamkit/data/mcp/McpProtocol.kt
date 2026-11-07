package com.example.hamkit.data.mcp

import com.example.hamkit.data.ai.tool.AiToolSpec
import com.example.hamkit.data.ai.tool.ToolRisk
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 协议里与网络无关的纯逻辑：鉴权、工具过滤、JSON-RPC 响应构造。
 *
 * 单独放这里便于 JVM 单测（不依赖 Socket / Android）。
 */
object McpProtocol {

    const val PROTOCOL_VERSION = "2024-11-05"

    /** 只读工具才允许通过局域网暴露。 */
    fun readOnlySpecs(specs: List<AiToolSpec>): List<AiToolSpec> =
        specs.filter { it.risk == ToolRisk.READ_ONLY }

    /** Bearer Token 校验；token 为空时一律拒绝。 */
    fun authorize(authorization: String?, token: String): Boolean {
        if (token.isBlank()) return false
        val value = authorization?.trim().orEmpty()
        return value.equals("Bearer $token", ignoreCase = true)
    }

    fun toolDescriptors(specs: List<AiToolSpec>): JSONArray = JSONArray().apply {
        readOnlySpecs(specs).forEach { spec ->
            put(
                JSONObject().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("inputSchema", spec.parameters)
                },
            )
        }
    }

    fun result(id: Any?, result: JSONObject): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            putId(id)
            put("result", result)
        }.toString()

    fun error(id: Any?, code: Int, message: String): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            putId(id)
            put(
                "error",
                JSONObject().apply {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString()

    private fun JSONObject.putId(id: Any?) {
        if (id == null) put("id", JSONObject.NULL) else put("id", id)
    }
}
