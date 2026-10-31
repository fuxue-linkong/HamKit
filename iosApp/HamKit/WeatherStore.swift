import Foundation
import Combine
import Shared

/// 天气状态：直接走**高德 Web 服务**（逆地理编码 + 实时 + 4 天预报），30 分钟缓存。
/// Key 写死在 `AmapKey.web`（项目要求）。
final class WeatherStore: ObservableObject {

    static let shared = WeatherStore()

    @Published private(set) var city: String = ""
    @Published private(set) var now: WeatherNow?
    @Published private(set) var daily: [WeatherDay] = []
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false

    private var lastFetch: Date?
    private var lastLatitude: Double?
    private var lastLongitude: Double?

    private init() {}

    /// 位置变化或手动刷新时调用；30 分钟内且坐标未变则跳过。
    func refresh(latitude: Double, longitude: Double, force: Bool = false) {
        if !force,
           let lastFetch,
           Date().timeIntervalSince(lastFetch) < 30 * 60,
           let lastLatitude, let lastLongitude,
           abs(lastLatitude - latitude) < 0.01,
           abs(lastLongitude - longitude) < 0.01 {
            return
        }
        guard !isLoading else { return }
        isLoading = true
        error = nil

        HamKitApi.shared.fetchWeather(
            latitude: latitude,
            longitude: longitude,
            apiKey: AmapKey.web
        ) { [weak self] snapshot in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isLoading = false
                if let info = snapshot.info {
                    self.city = info.cityName
                    self.now = info.now
                    self.daily = info.daily
                    self.error = nil
                    self.lastFetch = Date()
                    self.lastLatitude = latitude
                    self.lastLongitude = longitude
                } else {
                    self.error = snapshot.error ?? "天气获取失败"
                }
            }
        }
    }

    /// 首页展示用的一行摘要。
    var summary: String {
        guard let now else {
            if let error { return error }
            return isLoading ? "天气加载中…" : "天气不可用"
        }
        var parts: [String] = []
        if !city.isEmpty { parts.append(city) }
        parts.append("\(now.temp)°C")
        parts.append(now.text)
        if !now.windDir.isEmpty {
            parts.append(now.windDir + (now.windPower.isEmpty ? "" : " \(now.windPower)级"))
        }
        if !now.humidity.isEmpty { parts.append("湿度 \(now.humidity)%") }
        return parts.joined(separator: " · ")
    }
}

/// 每日一言的**本地兜底文案池**：网络不可用（或当天还没拉到）时首页展示这些。
/// 真正的「每日一言」由 `DailyQuoteStore` 从 https://v1.hitokoto.cn/ 获取
/// （对齐 Android `HitokotoApiService` + `MainViewModel.refreshDailyQuote()`）。
enum DailyQuote {
    private static let quotes = [
        "73，愿传播常在。",
        "天线越好，运气越好。",
        "听见不等于通联，通联需要耐心。",
        "QRP 也能通全球。",
        "守听是美德。",
        "传播来了，别错过。",
        "每一次 CQ 都是一次可能。",
        "把噪声当地址，把信号当朋友。",
    ]

    static func today() -> String {
        let day = Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 1
        return quotes[day % quotes.count]
    }
}
