import Foundation

/// 高德 Web 服务 Key（天气 + 逆地理编码）。
///
/// 源码里不再写死 Key：构建时由 `AMAP_WEB_KEY` 构建设置注入到
/// `Info.plist` 的 `AmapWebKey`，运行时从这里读取。
///
/// - 命令行：`xcodebuild ... AMAP_WEB_KEY=xxx`
/// - Xcode：在 HamKit target 的 Build Settings 里填 `AMAP_WEB_KEY`
/// - CI：仓库 Secret `AMAP_WEB_KEY`（见 `.github/workflows/ios.yml`，与 Android 同一个）
///
/// 未配置时为空字符串，`WeatherStore` 会给出明确提示，而不是拿空 Key 去请求。
enum AmapKey {
    static let web: String = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "AmapWebKey") as? String ?? ""
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }()
}
