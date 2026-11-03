import Foundation
import Combine
import Shared

/// 每日一言：对齐 Android `MainViewModel.refreshDailyQuote()` 的行为。
///
/// - 数据源：`https://v1.hitokoto.cn/`（经共享内核 `HamKitApi.fetchDailyQuote`，
///   与 Android `HitokotoApiService` 同源同解析）；
/// - 同一天内只请求一次，日期持久化在 `UserDefaults`（Android 用
///   `SettingsStore.dailyQuoteEpochDay`）；进程重启后当天不会重复请求；
/// - 请求失败**不**记录日期，下次进入首页会重试；
/// - 任何情况下都先展示本地文案池（`DailyQuote.today()`），
///   所以时间卡上永远不会出现空白。
final class DailyQuoteStore: ObservableObject {

    static let shared = DailyQuoteStore()

    /// 正文（不含来源）
    @Published private(set) var text: String = DailyQuote.today()
    /// 来源（作品名），可能为空
    @Published private(set) var from: String = ""
    @Published private(set) var isLoading = false

    private static let keyEpochDay = "hamkit.dailyQuote.epochDay"
    private static let keyText = "hamkit.dailyQuote.text"
    private static let keyFrom = "hamkit.dailyQuote.from"

    private init() {
        let defaults = UserDefaults.standard
        if let cached = defaults.string(forKey: Self.keyText), !cached.isEmpty {
            text = cached
            from = defaults.string(forKey: Self.keyFrom) ?? ""
        }
    }

    /// 展示文案：正文 + 「 —— 来源」（与 Android `toDisplayText()` 一致）。
    var displayText: String {
        from.isEmpty ? text : "\(text) —— \(from)"
    }

    /// 首页每次出现时调用：当天已拿到就跳过，否则请求一次。
    func refreshIfNeeded() {
        let defaults = UserDefaults.standard
        let cachedDay = defaults.integer(forKey: Self.keyEpochDay)
        if cachedDay != 0, Int64(cachedDay) == Self.epochDay() {
            return
        }
        refresh()
    }

    /// 手动刷新（设置页/调试用）。
    func refresh() {
        guard !isLoading else { return }
        isLoading = true
        HamKitApi.shared.fetchDailyQuote { [weak self] snapshot in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isLoading = false
                let content = snapshot.text.trimmingCharacters(in: .whitespacesAndNewlines)
                // 失败：保留本地兜底文案，且不写日期 → 下次仍会重试
                guard snapshot.error == nil, !content.isEmpty else { return }
                self.text = content
                self.from = snapshot.from
                let defaults = UserDefaults.standard
                defaults.set(Int(Self.epochDay()), forKey: Self.keyEpochDay)
                defaults.set(content, forKey: Self.keyText)
                defaults.set(snapshot.from, forKey: Self.keyFrom)
            }
        }
    }

    /// 本地日期（epoch day）。用 Calendar 取当天 0 点，跨时区/夏令时不会算错。
    private static func epochDay(_ date: Date = Date()) -> Int64 {
        let start = Calendar.current.startOfDay(for: date)
        return Int64(start.timeIntervalSince1970 / 86_400)
    }
}
