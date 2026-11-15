import Foundation

/// 按模型 ID 启发式判断是否支持图片输入。
///
/// 对齐 miuix 98b9b67 的 Android `AiVision.kt`：
/// 只做提示与发送前拦截；返回 `nil` 表示未知，调用方应放行
/// （避免误伤本地自定义模型）。
enum AiVision {

    /// - Returns: `true` 确定支持视觉，`false` 确定不支持，`nil` 未知。
    static func modelVisionSupport(_ modelId: String) -> Bool? {
        let id = modelId.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }
        let visionKeywords = [
            "vl", "vision", "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4",
            "claude-3", "claude-4", "claude-sonnet", "claude-opus",
            "gemini", "llava", "pixtral", "internvl", "minicpm-v",
        ]
        if visionKeywords.contains(where: { id.contains($0) }) {
            return true
        }
        // DeepSeek 全系当前无视觉输入（v3/v4/flash）
        if id.contains("deepseek") {
            return false
        }
        return nil
    }
}
