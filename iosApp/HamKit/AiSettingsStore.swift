import Foundation
import Security
import Shared

/// AI 设置（与 Android `AiConfig` 字段一一对应）。
///
/// 之所以在 Swift 侧再定义一个 struct：Kotlin 的 data class 没有可用的 `copy()`，
/// 设置页每改一个字段都要重建整个对象；这边用 struct 改字段，再交给
/// `AiBridge.configJson(...)` 生成内核认的 JSON。
struct AiSettings: Equatable {

    var protocolName: String = "OPENAI_CHAT"
    var preset: String = "DEEPSEEK"
    var baseUrl: String = "https://api.deepseek.com/v1"
    var model: String = "deepseek-chat"
    var temperature: Double = 0.6
    var maxOutputTokens: Int = 2048
    var saveHistory: Bool = true
    var historyLimit: Int = 200
    var systemPrompt: String = ""
    var disabledTools: Set<String> = []
    var confirmSideEffects: Bool = true
    var maxToolRounds: Int = 4

    var presetLabel: String { AiBridge.shared.presetLabel(name: preset) }
    var protocolLabel: String { AiBridge.shared.protocolLabel(name: protocolName) }
    var presetModels: [String] {
        AiBridge.shared.presetModels(name: preset)
            .split(separator: ",")
            .map(String.init)
            .filter { !$0.isEmpty }
    }

    /// 生成内核配置 JSON（字段与 `AiConfig` 对齐，由内核负责校验与裁剪）。
    var json: String {
        AiBridge.shared.configJson(
            protocolName: protocolName,
            presetName: preset,
            baseUrl: baseUrl,
            model: model,
            temperature: temperature,
            maxOutputTokens: Int32(maxOutputTokens),
            saveHistory: saveHistory,
            historyLimit: Int32(historyLimit),
            allowCleartext: false,
            systemPrompt: systemPrompt,
            disabledTools: disabledTools.sorted().joined(separator: ","),
            confirmSideEffects: confirmSideEffects,
            maxToolRounds: Int32(maxToolRounds)
        )
    }

    var config: AiConfig { AiBridge.shared.configFromJson(json: json) }

    static func from(json: String) -> AiSettings {
        var settings = AiSettings()
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return settings
        }
        settings.protocolName = root["protocol"] as? String ?? settings.protocolName
        settings.preset = root["preset"] as? String ?? settings.preset
        settings.baseUrl = root["baseUrl"] as? String ?? settings.baseUrl
        settings.model = root["model"] as? String ?? settings.model
        settings.temperature = root["temperature"] as? Double ?? settings.temperature
        settings.maxOutputTokens = root["maxOutputTokens"] as? Int ?? settings.maxOutputTokens
        settings.saveHistory = root["saveHistory"] as? Bool ?? settings.saveHistory
        settings.historyLimit = root["historyLimit"] as? Int ?? settings.historyLimit
        settings.systemPrompt = root["systemPrompt"] as? String ?? ""
        settings.confirmSideEffects = root["confirmSideEffects"] as? Bool ?? true
        settings.maxToolRounds = root["maxToolRounds"] as? Int ?? settings.maxToolRounds
        let disabled = root["disabledTools"] as? String ?? ""
        settings.disabledTools = Set(disabled.split(separator: ",").map(String.init))
        return settings
    }
}

/// AI 设置的持久化：配置存 UserDefaults，**API Key 存钥匙串**。
///
/// 与 Android 的对应关系：Android 用 `EncryptedSharedPreferences`（Keystore AES-GCM）
/// 存 Key；iOS 上等价能力是 Keychain（`kSecClassGenericPassword`），并且默认
/// `WhenUnlockedThisDeviceOnly`——不随 iCloud 钥匙串同步、不进 iTunes 备份。
final class AiSettingsStore: ObservableObject {

    static let shared = AiSettingsStore()

    private let defaults = UserDefaults.standard
    private let configKey = "hamkit.ai.config"
    private let keychainService = "click.hamkit.ios.ai"
    private let keychainAccount = "api-key"

    @Published var settings: AiSettings {
        didSet {
            defaults.set(settings.json, forKey: configKey)
        }
    }

    /// 是否已保存 API Key（不暴露明文）。
    @Published private(set) var hasApiKey: Bool

    private init() {
        if let stored = defaults.string(forKey: configKey) {
            settings = AiSettings.from(json: stored)
        } else {
            settings = AiSettings()
        }
        hasApiKey = Self.readKey()?.isEmpty == false
    }

    // ── API Key ─────────────────────────────────────────────────────────────

    var apiKey: String { Self.readKey() ?? "" }

    func saveApiKey(_ key: String) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            Self.deleteKey()
        } else {
            Self.writeKey(trimmed)
        }
        hasApiKey = !trimmed.isEmpty
    }

    func clearApiKey() {
        Self.deleteKey()
        hasApiKey = false
    }

    private static func writeKey(_ value: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "click.hamkit.ios.ai",
            kSecAttrAccount as String: "api-key",
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private static func readKey() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "click.hamkit.ios.ai",
            kSecAttrAccount as String: "api-key",
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func deleteKey() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "click.hamkit.ios.ai",
            kSecAttrAccount as String: "api-key",
        ]
        SecItemDelete(query as CFDictionary)
    }

    // ── 预设切换 ────────────────────────────────────────────────────────────

    /// 换服务商时同步切协议、Base URL 与默认模型（对齐 Android 设置页行为）。
    func applyPreset(_ presetName: String) {
        var next = settings
        next.preset = presetName
        next.protocolName = AiBridge.shared.presetProtocol(name: presetName)
        let base = AiBridge.shared.presetBaseUrl(name: presetName)
        let model = AiBridge.shared.presetDefaultModel(name: presetName)
        if !base.isEmpty { next.baseUrl = base }
        if !model.isEmpty { next.model = model }
        settings = next
    }
}

/// 单条会话（标题 + 消息 JSON + 更新时间）。
struct AiSessionMeta: Identifiable, Equatable {
    let id: String
    var title: String
    var updatedAt: Date
    var messageCount: Int
}

/// 会话历史：一个会话一个 JSON 文件（与 QSO 日志一样走沙盒文件，不引数据库）。
final class AiHistoryStore: ObservableObject {

    static let shared = AiHistoryStore()

    @Published private(set) var sessions: [AiSessionMeta] = []

    private let dir: URL
    private let indexURL: URL

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        dir = docs.appendingPathComponent("AI", isDirectory: true)
        indexURL = dir.appendingPathComponent("index.json")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        loadIndex()
    }

    private func loadIndex() {
        guard let data = try? Data(contentsOf: indexURL),
              let list = try? JSONDecoder().decode([AiSessionMetaDTO].self, from: data) else {
            sessions = []
            return
        }
        sessions = list.map { $0.toMeta() }.sorted { $0.updatedAt > $1.updatedAt }
    }

    private func saveIndex() {
        let dto = sessions.map { AiSessionMetaDTO(from: $0) }
        if let data = try? JSONEncoder().encode(dto) {
            try? data.write(to: indexURL, options: .atomic)
        }
    }

    func messages(of id: String) -> [AiMessage] {
        let url = dir.appendingPathComponent("\(id).json")
        guard let json = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return AiBridge.shared.messagesFromJson(json: json)
    }

    func save(id: String, messages: [AiMessage]) {
        guard !messages.isEmpty else { return }
        let json = AiBridge.shared.messagesToJson(messages: messages)
        let url = dir.appendingPathComponent("\(id).json")
        try? json.write(to: url, atomically: true, encoding: .utf8)

        let title = AiBridge.shared.conversationTitle(messages: messages)
        if let index = sessions.firstIndex(where: { $0.id == id }) {
            sessions[index].title = title
            sessions[index].updatedAt = Date()
            sessions[index].messageCount = messages.count
        } else {
            sessions.insert(
                AiSessionMeta(id: id, title: title, updatedAt: Date(), messageCount: messages.count),
                at: 0
            )
        }
        sessions.sort { $0.updatedAt > $1.updatedAt }
        saveIndex()
    }

    func delete(id: String) {
        let url = dir.appendingPathComponent("\(id).json")
        try? FileManager.default.removeItem(at: url)
        sessions.removeAll { $0.id == id }
        saveIndex()
    }

    func deleteAll() {
        for session in sessions {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent("\(session.id).json"))
        }
        sessions = []
        saveIndex()
    }

    /// 导出 Markdown 到临时目录（交给系统分享）。
    func exportMarkdown(id: String) -> URL? {
        let messages = messages(of: id)
        guard !messages.isEmpty else { return nil }
        let title = AiBridge.shared.conversationTitle(messages: messages)
        let markdown = AiBridge.shared.messagesToMarkdown(messages: messages, title: title)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("HamKit-AI-\(id).md")
        do {
            try markdown.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }
}

private struct AiSessionMetaDTO: Codable {
    let id: String
    let title: String
    let updatedAt: Double
    let messageCount: Int

    init(from meta: AiSessionMeta) {
        id = meta.id
        title = meta.title
        updatedAt = meta.updatedAt.timeIntervalSince1970
        messageCount = meta.messageCount
    }

    func toMeta() -> AiSessionMeta {
        AiSessionMeta(
            id: id,
            title: title,
            updatedAt: Date(timeIntervalSince1970: updatedAt),
            messageCount: messageCount
        )
    }
}
