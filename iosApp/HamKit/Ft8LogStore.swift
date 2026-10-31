import Foundation
import Shared

/// 一条 FT8 解码记录（会话级，重启后清空——与 Android `Ft8UiState.decodedEntries` 同义）。
struct Ft8LogEntry: Identifiable, Equatable {
    let id = UUID()
    let time: Date
    let message: String
    let snr: Int
    let dtSeconds: Double
    let freqHz: Double

    init(_ decoded: Ft8DecodedMessage, time: Date = Date()) {
        self.time = time
        self.message = decoded.message
        self.snr = Int(decoded.snr)
        self.dtSeconds = decoded.dtSeconds
        self.freqHz = decoded.freqHz
    }
}

/// FT8 会话日志。
///
/// 为什么单独立一个 store：解码结果原先只存在 `Ft8MainView` 的 `@State` 里，
/// 页面一离开就没了；AI 助手要回答「刚才解出什么了」就必须读到同一份数据。
/// 这里同时充当 UI 的数据源与会话缓存，不做持久化（与 Android 的内存语义一致）。
final class Ft8LogStore: ObservableObject {

    static let shared = Ft8LogStore()

    /// 只保留最近 200 条：FT8 每 15 秒一帧，再多也没有对话价值。
    static let maxEntries = 200

    @Published private(set) var decodes: [Ft8LogEntry] = []
    /// 正在接收/解码（工具用它说明「现在是空的，因为还没启动」）。
    @Published private(set) var isDecoding = false

    private init() {}

    func beginDecoding() {
        isDecoding = true
    }

    func endDecoding() {
        isDecoding = false
    }

    func record(_ decoded: Ft8DecodedMessage) {
        decodes.insert(Ft8LogEntry(decoded), at: 0)
        if decodes.count > Self.maxEntries {
            decodes.removeLast(decodes.count - Self.maxEntries)
        }
    }

    func clear() {
        decodes.removeAll()
    }
}
