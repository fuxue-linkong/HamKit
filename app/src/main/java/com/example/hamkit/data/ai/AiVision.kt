package com.example.hamkit.data.ai

/**
 * 按模型 ID 启发式判断是否支持图片输入。
 *
 * 只做提示与发送前拦截；[null] 表示未知，调用方应放行（避免误伤本地自定义模型）。
 */
fun modelVisionSupport(modelId: String): Boolean? {
    val id = modelId.lowercase().trim()
    if (id.isEmpty()) return null
    val vision = listOf(
        "vl", "vision", "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4",
        "claude-3", "claude-4", "claude-sonnet", "claude-opus",
        "gemini", "llava", "pixtral", "internvl", "minicpm-v",
    )
    return when {
        vision.any { id.contains(it) } -> true
        // DeepSeek 全系当前无视觉输入（v3/v4/flash）
        id.contains("deepseek") -> false
        else -> null
    }
}
