package com.example.hamkit.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** 获取模型列表失败（携带统一错误分类，便于 UI 展示）。 */
class AiModelListException(val error: AiError) : Exception(error.message)

/**
 * 自动获取服务商模型列表。
 *
 * 三种协议都提供 `GET .../models`（Anthropic 需要 `anthropic-version` 头；
 * Gemini 用 `x-goog-api-key` 且返回 `models[].name`，其余两家返回 `data[].id`）。
 * 只做只读请求，不修改任何配置；结果由设置页让用户点选。
 */
object AiModelList {

    suspend fun fetch(config: AiConfig, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cfg = config.normalized()
                val request = Request.Builder()
                    .url(modelsUrl(cfg))
                    .apply { authHeaders(cfg.protocol, apiKey) }
                    .get()
                    .build()

                AiHttp.blocking.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw AiModelListException(AiError.fromHttp(response.code, body))
                    }
                    val models = parseModels(cfg.protocol, body)
                    if (models.isEmpty()) {
                        throw AiModelListException(
                            AiError(AiErrorKind.INVALID_REQUEST, "服务商返回的模型列表为空")
                        )
                    }
                    models
                }
            }
        }

    internal fun modelsUrl(cfg: AiConfig): String = when (cfg.protocol) {
        AiProtocol.OPENAI_CHAT -> "${cfg.baseUrl}/models"

        AiProtocol.ANTHROPIC_MESSAGES ->
            if (cfg.baseUrl.endsWith("/v1")) "${cfg.baseUrl}/models"
            else "${cfg.baseUrl}/v1/models"

        AiProtocol.GEMINI -> {
            val root = if (cfg.baseUrl.endsWith("/v1beta") || cfg.baseUrl.endsWith("/v1")) {
                cfg.baseUrl
            } else {
                "${cfg.baseUrl}/v1beta"
            }
            "$root/models"
        }
    }

    /** 解析三种响应格式；去空、去重、排序。 */
    internal fun parseModels(protocol: AiProtocol, body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val values = when (protocol) {
            AiProtocol.GEMINI -> {
                val array = root.optJSONArray("models") ?: return emptyList()
                buildList {
                    for (i in 0 until array.length()) {
                        val name = array.optJSONObject(i)?.optString("name").orEmpty()
                        if (name.isNotBlank()) add(name.removePrefix("models/"))
                    }
                }
            }

            else -> {
                val array = root.optJSONArray("data") ?: return emptyList()
                buildList {
                    for (i in 0 until array.length()) {
                        val id = array.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) add(id)
                    }
                }
            }
        }
        return values.filter { it.isNotBlank() && it != "null" }.distinct().sorted()
    }

    private fun Request.Builder.authHeaders(protocol: AiProtocol, apiKey: String): Request.Builder =
        when (protocol) {
            AiProtocol.OPENAI_CHAT -> header("Authorization", "Bearer ${apiKey.trim()}")
            AiProtocol.ANTHROPIC_MESSAGES -> header("x-api-key", apiKey.trim())
                .header("anthropic-version", "2023-06-01")
            AiProtocol.GEMINI -> header("x-goog-api-key", apiKey.trim())
        }
}
