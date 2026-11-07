package com.example.hamkit.data.ai

/**
 * 剥离服务商/模型误输出的 DSML 工具调用文本。
 *
 * 部分模型会把工具调用写成 `<||DSML||calls>…` 文本，而不是走 API 的 tool_calls；
 * 这些内容不应出现在回答正文里。只影响包含 DSML 标记的文本。
 */
fun stripDsml(text: String): String {
    if (!text.contains("||DSML||")) return text
    var result = text
    // 完整块（可能跨多行）
    result = Regex("""<\|{2}DSML\|{2}calls>[\s\S]*?</\|{2}DSML\|{2}calls>""").replace(result, "")
    // 残留标记与后续行
    result = Regex("""</?\|{2}DSML\|{2}[^\n]*""").replace(result, "")
    return result.trim()
}
