import Foundation
import Security
import Shared

// MARK: - 模型配置数据

/// 一个提供商下的模型条目（对应 Android `AiModelEntry`）。
struct AiModelEntry: Identifiable, Equatable, Codable {

    var id: String
    var modelId: String
    var displayName: String?

    /// 列表展示名：优先显示名称，否则模型 ID。
    var label: String {
        let name = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? modelId : name
    }
}

/// 一组可切换的模型配置：提供商 + 多个模型 + 独立 Key 槽位。
///
/// 与 Android `AiModelProfile` 同义；全局设置（系统提示词、工具、历史）不在其中，
/// 切换提供商 / 模型不影响这些全局项。
struct AiProfile: Identifiable, Equatable, Codable {

    static let defaultId = "default"

    var id: String
    var name: String
    var preset: String
    var protocolName: String
    var baseUrl: String
    var models: [AiModelEntry]
    var selectedModelId: String?
    var temperature: Double
    var maxOutputTokens: Int

    /// 当前选中的模型条目；选中项失效时回退到第一条。
    var currentEntry: AiModelEntry? {
        models.first { $0.id == selectedModelId } ?? models.first
    }

    var currentModelId: String { currentEntry?.modelId ?? "" }

    var presetLabel: String { AiBridge.shared.presetLabel(name: preset) }
    var protocolLabel: String { AiBridge.shared.protocolLabel(name: protocolName) }

    /// 清洗规则与 Android `AiModelProfile.normalized()` 对齐：
    /// 名称去空白、Base URL 去尾部斜杠、模型去空行、选中项回退第一条、参数夹取范围。
    func normalized() -> AiProfile {
        let cleaned = models.compactMap { entry -> AiModelEntry? in
            let modelId = entry.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !modelId.isEmpty else { return nil }
            let display = entry.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return AiModelEntry(
                id: entry.id,
                modelId: modelId,
                displayName: display.isEmpty ? nil : display
            )
        }
        let selected = cleaned.contains { $0.id == selectedModelId }
            ? selectedModelId
            : cleaned.first?.id
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return AiProfile(
            id: id,
            name: trimmedName.isEmpty ? "未命名" : trimmedName,
            preset: preset,
            protocolName: protocolName,
            baseUrl: Self.trimmedBaseUrl(baseUrl),
            models: cleaned,
            selectedModelId: selected,
            temperature: min(max(temperature, 0), 2),
            maxOutputTokens: min(max(maxOutputTokens, 64), 65536)
        )
    }

    /// 旧版单模型配置 → `default` profile（对应 Android `fromConfig`）。
    static func fromConfig(id: String, name: String, settings: AiSettings) -> AiProfile {
        let fallback = AiBridge.shared.presetDefaultModel(name: settings.preset)
        let model = settings.model.isEmpty ? fallback : settings.model
        return AiProfile(
            id: id,
            name: name,
            preset: settings.preset,
            protocolName: settings.protocolName,
            baseUrl: settings.baseUrl,
            models: [AiModelEntry(id: "m1", modelId: model)],
            selectedModelId: "m1",
            temperature: settings.temperature,
            maxOutputTokens: settings.maxOutputTokens
        ).normalized()
    }

    /// 空数据兜底：只在持久化数据完全不可读时使用。
    static var fallback: AiProfile {
        let preset = "DEEPSEEK"
        return AiProfile(
            id: defaultId,
            name: "默认",
            preset: preset,
            protocolName: AiBridge.shared.presetProtocol(name: preset),
            baseUrl: AiBridge.shared.presetBaseUrl(name: preset),
            models: [
                AiModelEntry(
                    id: "m1",
                    modelId: AiBridge.shared.presetDefaultModel(name: preset)
                ),
            ],
            selectedModelId: "m1",
            temperature: 0.6,
            maxOutputTokens: 8192
        )
    }

    private static func trimmedBaseUrl(_ value: String) -> String {
        var text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }
}

/// 全局设置：不随模型切换（对应 Android `AiConfig` 里除连接参数外的部分）。
struct AiGlobalSettings: Equatable, Codable {

    var saveHistory: Bool = true
    var historyLimit: Int = 200
    var systemPrompt: String = ""
    var disabledTools: Set<String> = []
    var confirmSideEffects: Bool = true
    var maxToolRounds: Int = 6
    /// 推理等级（OFF/LOW/MEDIUM/HIGH），对应 Android `AiConfig.reasoningEffort`。
    var reasoningEffort: String = "MEDIUM"
    /// 信任自签 HTTPS（内核只在私有地址上生效）。
    var allowSelfSigned: Bool = false

    init() {}

    init(
        saveHistory: Bool,
        historyLimit: Int,
        systemPrompt: String,
        disabledTools: Set<String>,
        confirmSideEffects: Bool,
        maxToolRounds: Int,
        reasoningEffort: String = "MEDIUM",
        allowSelfSigned: Bool = false
    ) {
        self.saveHistory = saveHistory
        self.historyLimit = historyLimit
        self.systemPrompt = systemPrompt
        self.disabledTools = disabledTools
        self.confirmSideEffects = confirmSideEffects
        self.maxToolRounds = maxToolRounds
        self.reasoningEffort = reasoningEffort
        self.allowSelfSigned = allowSelfSigned
    }

    /// 手写解码：旧 JSON 缺字段时用默认值，避免升级后整个设置读不出来。
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        saveHistory = try container.decodeIfPresent(Bool.self, forKey: .saveHistory) ?? true
        historyLimit = try container.decodeIfPresent(Int.self, forKey: .historyLimit) ?? 200
        systemPrompt = try container.decodeIfPresent(String.self, forKey: .systemPrompt) ?? ""
        disabledTools = try container.decodeIfPresent(Set<String>.self, forKey: .disabledTools) ?? []
        confirmSideEffects = try container.decodeIfPresent(Bool.self, forKey: .confirmSideEffects) ?? true
        maxToolRounds = try container.decodeIfPresent(Int.self, forKey: .maxToolRounds) ?? 6
        reasoningEffort = try container.decodeIfPresent(String.self, forKey: .reasoningEffort) ?? "MEDIUM"
        allowSelfSigned = try container.decodeIfPresent(Bool.self, forKey: .allowSelfSigned) ?? false
    }
}

/// AI 设置（与 Android `AiConfig` 字段一一对应）。
///
/// Swift 侧用 struct 改字段，再交给 `AiBridge.configJson(...)` 生成内核认的 JSON。
/// 连接参数来自当前 profile，其余来自全局设置。
struct AiSettings: Equatable {

    var protocolName: String = "OPENAI_CHAT"
    var preset: String = "DEEPSEEK"
    var baseUrl: String = "https://api.deepseek.com/v1"
    var model: String = "deepseek-chat"
    var temperature: Double = 0.6
    var maxOutputTokens: Int = 8192
    var saveHistory: Bool = true
    var historyLimit: Int = 200
    var systemPrompt: String = ""
    var disabledTools: Set<String> = []
    var confirmSideEffects: Bool = true
    var maxToolRounds: Int = 6
    var reasoningEffort: String = "MEDIUM"
    var allowSelfSigned: Bool = false

    init() {}

    init(profile: AiProfile, global: AiGlobalSettings) {
        protocolName = profile.protocolName
        preset = profile.preset
        baseUrl = profile.baseUrl
        model = profile.currentModelId
        temperature = profile.temperature
        maxOutputTokens = profile.maxOutputTokens
        saveHistory = global.saveHistory
        historyLimit = global.historyLimit
        systemPrompt = global.systemPrompt
        disabledTools = global.disabledTools
        confirmSideEffects = global.confirmSideEffects
        maxToolRounds = global.maxToolRounds
        reasoningEffort = global.reasoningEffort
        allowSelfSigned = global.allowSelfSigned
    }

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
            allowSelfSigned: allowSelfSigned,
            systemPrompt: systemPrompt,
            disabledTools: disabledTools.sorted().joined(separator: ","),
            confirmSideEffects: confirmSideEffects,
            maxToolRounds: Int32(maxToolRounds),
            reasoningEffort: reasoningEffort
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
        settings.reasoningEffort = root["reasoningEffort"] as? String ?? settings.reasoningEffort
        settings.allowSelfSigned = root["allowSelfSigned"] as? Bool ?? settings.allowSelfSigned
        let disabled = root["disabledTools"] as? String ?? ""
        settings.disabledTools = Set(disabled.split(separator: ",").map(String.init))
        return settings
    }
}

// MARK: - 设置持久化

/// AI 设置的持久化：profile 与全局设置存 UserDefaults，**API Key 按提供商分槽存钥匙串**。
///
/// 与 Android 的对应关系：
/// - `AiProfileStore` + `AiConfigStore` ↔ 本类的 `profiles` / `global`；
/// - `AiKeyStore`（每个 profile 一个槽位）↔ Keychain 的 `api-key` / `api-key.<profileId>`。
///
/// 旧版只有一个 `hamkit.ai.config` 与一个 `api-key`：首次启动迁移成 `default` profile，
/// 钥匙串沿用旧槽位，用户不需要重新填 Key。
final class AiSettingsStore: ObservableObject {

    static let shared = AiSettingsStore()

    @Published private(set) var profiles: [AiProfile] = []
    @Published private(set) var activeProfileId: String = AiProfile.defaultId
    @Published private(set) var global = AiGlobalSettings()
    /// 当前提供商是否已保存 API Key（只在保存/清除/切换时刷新，不暴露明文）。
    @Published private(set) var hasApiKey = false

    private let defaults = UserDefaults.standard
    private static let profilesKey = "hamkit.ai.profiles"
    private static let activeKey = "hamkit.ai.active-profile"
    private static let globalKey = "hamkit.ai.global"
    private static let legacyConfigKey = "hamkit.ai.config"
    private static let keychainService = "click.hamkit.ios.ai"

    private init() {
        load()
    }

    // ── 汇总配置 ────────────────────────────────────────────────────────────

    var activeProfile: AiProfile {
        profiles.first { $0.id == activeProfileId } ?? profiles.first ?? AiProfile.fallback
    }

    /// 交给内核与聊天页的合并配置。
    var settings: AiSettings { AiSettings(profile: activeProfile, global: global) }

    var config: AiConfig { settings.config }

    var activeModelId: String? { activeProfile.currentEntry?.id }

    var apiKey: String { apiKey(for: activeProfileId) }

    // ── 读取与迁移 ──────────────────────────────────────────────────────────

    private func load() {
        let legacySettings = defaults.string(forKey: Self.legacyConfigKey)
            .map(AiSettings.from(json:))
        var needsPersist = false

        if let data = defaults.data(forKey: Self.profilesKey),
           let decoded = try? JSONDecoder().decode([AiProfile].self, from: data),
           !decoded.isEmpty {
            profiles = decoded.map { $0.normalized() }
        } else if let legacySettings {
            profiles = [
                AiProfile.fromConfig(
                    id: AiProfile.defaultId,
                    name: "默认",
                    settings: legacySettings
                ),
            ]
            needsPersist = true
        } else {
            profiles = [
                AiProfile.fromConfig(
                    id: AiProfile.defaultId,
                    name: "默认",
                    settings: AiSettings()
                ),
            ]
            needsPersist = true
        }

        let savedActive = defaults.string(forKey: Self.activeKey)
        if let savedActive, profiles.contains(where: { $0.id == savedActive }) {
            activeProfileId = savedActive
        } else {
            activeProfileId = profiles[0].id
            needsPersist = true
        }

        var migratedGlobal = false
        if let data = defaults.data(forKey: Self.globalKey),
           let decoded = try? JSONDecoder().decode(AiGlobalSettings.self, from: data) {
            global = decoded
        } else if let legacySettings {
            global = AiGlobalSettings(
                saveHistory: legacySettings.saveHistory,
                historyLimit: legacySettings.historyLimit,
                systemPrompt: legacySettings.systemPrompt,
                disabledTools: legacySettings.disabledTools,
                confirmSideEffects: legacySettings.confirmSideEffects,
                maxToolRounds: legacySettings.maxToolRounds
            )
            migratedGlobal = true
        }

        hasApiKey = !apiKey(for: activeProfileId).isEmpty

        // 首次迁移（或旧数据损坏）后立即落盘：此后即使没有点保存，
        // 也不会在下次启动重复迁移。
        if needsPersist {
            persistProfiles()
        }
        if migratedGlobal {
            persistGlobal()
        }
    }

    private func persistProfiles() {
        if let data = try? JSONEncoder().encode(profiles) {
            defaults.set(data, forKey: Self.profilesKey)
        }
        defaults.set(activeProfileId, forKey: Self.activeKey)
    }

    private func persistGlobal() {
        if let data = try? JSONEncoder().encode(global) {
            defaults.set(data, forKey: Self.globalKey)
        }
    }

    // ── profile 操作 ────────────────────────────────────────────────────────

    @discardableResult
    func updateProfile(_ profile: AiProfile) -> AiProfile {
        let value = profile.normalized()
        var next = profiles
        if let index = next.firstIndex(where: { $0.id == value.id }) {
            next[index] = value
        } else {
            next.append(value)
        }
        profiles = next
        persistProfiles()
        if value.id == activeProfileId { hasApiKey = !apiKey(for: value.id).isEmpty }
        return value
    }

    func selectProfile(_ id: String) {
        guard profiles.contains(where: { $0.id == id }) else { return }
        activeProfileId = id
        defaults.set(id, forKey: Self.activeKey)
        hasApiKey = !apiKey(for: id).isEmpty
    }

    /// 切换到指定提供商的指定模型；成功返回 true。
    @discardableResult
    func switchModel(profileId: String, modelId: String) -> Bool {
        guard let index = profiles.firstIndex(where: { $0.id == profileId }),
              profiles[index].models.contains(where: { $0.id == modelId }) else {
            return false
        }
        var next = profiles
        next[index].selectedModelId = modelId
        profiles = next
        persistProfiles()
        selectProfile(profileId)
        return true
    }

    /// 新建提供商：沿用当前连接参数与模型，Key 槽位独立（初始为空）。
    @discardableResult
    func addProfile() -> AiProfile {
        let base = activeProfile
        let id = "p_" + Self.shortId()
        let model = base.currentModelId.isEmpty
            ? AiBridge.shared.presetDefaultModel(name: base.preset)
            : base.currentModelId
        let profile = AiProfile(
            id: id,
            name: "模型 \(profiles.count + 1)",
            preset: base.preset,
            protocolName: base.protocolName,
            baseUrl: base.baseUrl,
            models: [AiModelEntry(id: "m1", modelId: model)],
            selectedModelId: "m1",
            temperature: base.temperature,
            maxOutputTokens: base.maxOutputTokens
        ).normalized()
        var next = profiles
        next.append(profile)
        profiles = next
        activeProfileId = profile.id
        hasApiKey = false
        persistProfiles()
        return profile
    }

    /// 删除提供商；不允许删掉最后一个。成功返回 true。
    @discardableResult
    func deleteProfile(_ id: String) -> Bool {
        guard profiles.count > 1,
              let index = profiles.firstIndex(where: { $0.id == id }) else {
            return false
        }
        var next = profiles
        next.remove(at: index)
        profiles = next
        if activeProfileId == id {
            activeProfileId = next[0].id
        }
        Self.deleteKey(account: Self.account(for: id))
        hasApiKey = !apiKey(for: activeProfileId).isEmpty
        persistProfiles()
        return true
    }

    // ── 全局设置 ────────────────────────────────────────────────────────────

    func updateGlobal(_ update: (inout AiGlobalSettings) -> Void) {
        var next = global
        update(&next)
        global = next
        persistGlobal()
    }

    // ── API Key（每个 profile 一个钥匙串槽位） ──────────────────────────────

    func apiKey(for profileId: String) -> String {
        Self.readKey(account: Self.account(for: profileId)) ?? ""
    }

    func keyExists(for profileId: String) -> Bool { !apiKey(for: profileId).isEmpty }

    /// 设置页展示用（`sk-••••7f3a`），不暴露完整 Key。
    func maskedApiKey(for profileId: String) -> String? {
        let key = apiKey(for: profileId)
        guard !key.isEmpty else { return nil }
        guard key.count > 8 else { return "••••" }
        return "\(key.prefix(3))••••\(key.suffix(4))"
    }

    func saveApiKey(_ key: String, for profileId: String? = nil) {
        let id = profileId ?? activeProfileId
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            Self.deleteKey(account: Self.account(for: id))
        } else {
            Self.writeKey(trimmed, account: Self.account(for: id))
        }
        if id == activeProfileId {
            hasApiKey = !trimmed.isEmpty
        }
    }

    func clearApiKey(for profileId: String? = nil) {
        let id = profileId ?? activeProfileId
        Self.deleteKey(account: Self.account(for: id))
        if id == activeProfileId {
            hasApiKey = false
        }
    }

    /// `default` 沿用旧版账号，避免升级后 Key 丢失；其余 profile 用独立账号。
    private static func account(for profileId: String) -> String {
        profileId == AiProfile.defaultId ? "api-key" : "api-key.\(profileId)"
    }

    private static func writeKey(_ value: String, account: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private static func readKey(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func deleteKey(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func shortId() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)).lowercased()
    }
}

// MARK: - 模型目录

/// 自动获取服务商模型列表（只读，不修改配置）。
///
/// 与 Android `AiModelList` 对齐：OpenAI / Anthropic 返回 `data[].id`，
/// Gemini 返回 `models[].name`（去掉 `models/` 前缀）。
/// 内网自签 HTTPS：仅当全局开关打开时对该请求挂 trustAll delegate。
enum AiModelCatalog {

    /// 失败原因直接给 UI 展示。
    enum Failure: LocalizedError {
        case invalidBaseURL
        case http(String)
        case empty

        var errorDescription: String? {
            switch self {
            case .invalidBaseURL: return "Base URL 格式不正确"
            case .http(let message): return message
            case .empty: return "服务商返回的模型列表为空"
            }
        }
    }

    private final class TrustAllDelegate: NSObject, URLSessionDelegate {
        func urlSession(
            _ session: URLSession,
            didReceive challenge: URLAuthenticationChallenge,
            completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
        ) {
            if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
               let trust = challenge.protectionSpace.serverTrust {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.performDefaultHandling, nil)
            }
        }
    }

    static func fetch(profile: AiProfile, apiKey: String, allowSelfSigned: Bool = false) async throws -> [String] {
        // 与 Android `AiModelList.fetch` 一致：先归一化 Base URL（去空白、去尾部 `/`），
        // 否则手工输入 `https://host/v1/` 会拼出 `.../v1//models`。
        let normalized = profile.normalized()
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.baseUrl.isEmpty,
              let url = URL(string: modelsURL(normalized)),
              url.scheme != nil else {
            throw Failure.invalidBaseURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 30
        switch normalized.protocolName {
        case "ANTHROPIC_MESSAGES":
            request.setValue(key, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        case "GEMINI":
            request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
        default:
            request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        }

        // 自签放行与对话流一致：只对内网地址生效（内核 AiNetworkPolicy 判定）
        let trustAll = allowSelfSigned
            && url.scheme?.lowercased() == "https"
            && AiBridge.shared.isPrivateHost(host: url.host ?? "")
        let data: Data
        let response: URLResponse
        if trustAll {
            let delegate = TrustAllDelegate()
            let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
            defer { session.finishTasksAndInvalidate() }
            (data, response) = try await session.data(for: request)
        } else {
            (data, response) = try await URLSession.shared.data(for: request)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let body = String(decoding: data, as: UTF8.self)
        guard (200..<300).contains(status) else {
            let error = AiBridge.shared.httpError(status: Int32(status), body: body)
            throw Failure.http(AiBridge.shared.errorText(error: error))
        }
        let models = parse(protocolName: normalized.protocolName, body: body)
        guard !models.isEmpty else { throw Failure.empty }
        return models
    }

    static func modelsURL(_ profile: AiProfile) -> String {
        let base = profile.baseUrl
        switch profile.protocolName {
        case "ANTHROPIC_MESSAGES":
            return base.hasSuffix("/v1") ? "\(base)/models" : "\(base)/v1/models"
        case "GEMINI":
            let root = (base.hasSuffix("/v1beta") || base.hasSuffix("/v1"))
                ? base
                : "\(base)/v1beta"
            return "\(root)/models"
        default:
            return "\(base)/models"
        }
    }

    static func parse(protocolName: String, body: String) -> [String] {
        guard let data = body.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }
        var values: [String] = []
        if protocolName == "GEMINI" {
            for item in root["models"] as? [[String: Any]] ?? [] {
                guard let name = item["name"] as? String, !name.isEmpty else { continue }
                values.append(name.hasPrefix("models/") ? String(name.dropFirst(7)) : name)
            }
        } else {
            for item in root["data"] as? [[String: Any]] ?? [] {
                guard let id = item["id"] as? String, !id.isEmpty else { continue }
                values.append(id)
            }
        }
        var seen = Set<String>()
        return values
            .filter { $0 != "null" && seen.insert($0).inserted }
            .sorted()
    }
}

// MARK: - 会话历史

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
