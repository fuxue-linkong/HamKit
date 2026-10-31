import Foundation
import SwiftUI
import Shared

/// 消息在 UI 里的表示（不直接用 Kotlin 的 AiMessage：SwiftUI 需要 Identifiable + 可变流式态）。
struct ChatMessageVM: Identifiable {

    enum Role { case user, assistant, tool }

    let id: String
    var role: Role
    var text: String
    var toolName: String
    var toolSummary: String
    var isError: Bool
    var card: SatelliteCardVM?
    /// 流式输出中（用于显示光标/禁用发送）。
    var isStreaming: Bool

    init(
        id: String = UUID().uuidString,
        role: Role,
        text: String,
        toolName: String = "",
        toolSummary: String = "",
        isError: Bool = false,
        card: SatelliteCardVM? = nil,
        isStreaming: Bool = false
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.toolName = toolName
        self.toolSummary = toolSummary
        self.isError = isError
        self.card = card
        self.isStreaming = isStreaming
    }
}

/// 工具返回的卫星卡（解析内核给的 cardJson）。
struct SatelliteCardVM {

    struct Pass { let aos: String; let los: String; let inPass: Bool; let maxElevation: Double }
    struct Radio { let name: String; let downlinkHz: Int64; let uplinkHz: Int64; let mode: String }

    let name: String
    let norad: Int
    let modes: [String]
    let status: String
    let pass: Pass?
    let radios: [Radio]

    static func parse(_ json: String) -> SatelliteCardVM? {
        guard !json.isEmpty,
              let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let pass: Pass? = (root["pass"] as? [String: Any]).map { dict in
            Pass(
                aos: dict["aos"] as? String ?? "",
                los: dict["los"] as? String ?? "",
                inPass: dict["in_pass"] as? Bool ?? false,
                maxElevation: dict["max_elevation"] as? Double ?? 0
            )
        }
        let radios: [Radio] = (root["radios"] as? [[String: Any]] ?? []).map { dict in
            Radio(
                name: dict["name"] as? String ?? "",
                downlinkHz: Int64(dict["downlink_hz"] as? Int ?? 0),
                uplinkHz: Int64(dict["uplink_hz"] as? Int ?? 0),
                mode: dict["mode"] as? String ?? ""
            )
        }
        return SatelliteCardVM(
            name: root["name"] as? String ?? "",
            norad: root["norad"] as? Int ?? 0,
            modes: root["modes"] as? [String] ?? [],
            status: root["amsat_status"] as? String ?? "",
            pass: pass,
            radios: radios
        )
    }
}

/// 等待用户确认的副作用工具调用。
struct PendingToolConfirmation {
    let callId: String
    let name: String
    let title: String
    /// 用户点「执行」后回调给内核
    let proceed: () -> Void
    /// 用户点「跳过」后回调给内核
    let skip: () -> Void
}

/// AI 对话中心：持有引擎、流式状态与会话历史。
///
/// 线程模型：内核状态机不是线程安全的，所以**所有入口都在主线程**——UI 直接调用，
/// 网络回调经 `MainThreadStreamTransport` 切回主线程后再喂给内核。
final class AiChatModel: ObservableObject {

    static let shared = AiChatModel()

    @Published private(set) var messages: [ChatMessageVM] = []
    @Published private(set) var isStreaming = false
    @Published var errorText: String?
    @Published var permissionWarning: String?
    @Published var pendingConfirmation: PendingToolConfirmation?
    @Published private(set) var sessionId: String = UUID().uuidString

    private var engine: AiChatEngine!
    private var kotlinMessages: [AiMessage] = []
    private var streamingMessageId: String?
    private let tools: AiToolbox

    private init() {
        let box = AiToolbox(facade: AiAppFacadeImpl.shared)
        let host = ConfirmingToolHost(box: box)
        tools = box
        toolHost = host
        let adapter = EngineListener()
        engine = AiChatEngine(
            transport: MainThreadStreamTransport(),
            listener: adapter
        )
        // 所有存储属性就绪后才能引用 self
        host.model = self
        adapter.model = self
    }

    private var toolHost: ConfirmingToolHost!

    // MARK: - 发送

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isStreaming else { return }

        let settings = AiSettingsStore.shared.settings
        if settings.baseUrl.isEmpty || settings.model.isEmpty {
            errorText = "请先在「AI 设置」里填写服务商与模型"
            return
        }
        if AiSettingsStore.shared.apiKey.isEmpty {
            errorText = "请先在「AI 设置」里填写 API Key"
            return
        }

        errorText = nil
        messages.append(ChatMessageVM(role: .user, text: trimmed))
        kotlinMessages.append(AiBridge.shared.userMessage(text: trimmed))

        startTurn(settings: settings)
    }

    private func startTurn(settings: AiSettings) {
        isStreaming = true
        let assistant = ChatMessageVM(role: .assistant, text: "", isStreaming: true)
        streamingMessageId = assistant.id
        messages.append(assistant)
        engine.start(
            config: settings.config,
            apiKey: AiSettingsStore.shared.apiKey,
            messages: kotlinMessages,
            toolHost: toolHost
        )
    }

    func stop() {
        engine.cancel()
        isStreaming = false
        finishStreamingMessage()
    }

    func newSession() {
        if isStreaming { stop() }
        sessionId = UUID().uuidString
        messages = []
        kotlinMessages = []
        errorText = nil
    }

    func loadSession(id: String) {
        if isStreaming { stop() }
        sessionId = id
        kotlinMessages = AiHistoryStore.shared.messages(of: id)
        messages = kotlinMessages.map { ChatMessageVM(role: Self.role(of: $0), text: $0.text) }
        errorText = nil
    }

    func deleteSession(id: String) {
        AiHistoryStore.shared.delete(id: id)
        if id == sessionId { newSession() }
    }

    private static func role(of message: AiMessage) -> ChatMessageVM.Role {
        // Kotlin 的 enum 在 Swift 里不是 Swift enum，不能做 `case .user` 模式匹配，
        // 统一用 name 比较（与 risk / stop 的处理方式一致）。
        switch message.role.name {
        case "USER": return .user
        case "TOOL": return .tool
        default: return .assistant
        }
    }

    // MARK: - 引擎回调（主线程）

    fileprivate func handleDelta(_ text: String) {
        guard let id = streamingMessageId,
              let index = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[index].text += text
    }

    fileprivate func handleToolStarted(callId: String, name: String, title: String) {
        finishStreamingMessage()
        messages.append(
            ChatMessageVM(
                id: "tool-\(callId)",
                role: .tool,
                text: title,
                toolName: name,
                toolSummary: "执行中…"
            )
        )
        // 工具执行期间模型不再输出，先收起「正在输入」占位
        isStreaming = true
    }

    fileprivate func handleToolFinished(
        callId: String,
        name: String,
        summary: String,
        isError: Bool,
        cardJson: String
    ) {
        guard let index = messages.firstIndex(where: { $0.id == "tool-\(callId)" }) else { return }
        messages[index].toolSummary = summary
        messages[index].isError = isError
        messages[index].card = SatelliteCardVM.parse(cardJson)
    }

    fileprivate func handleCompleted(_ newMessages: [AiMessage], stop: AiStopReason) {
        kotlinMessages = newMessages
        isStreaming = false
        finishStreamingMessage()

        // 若模型本轮没有文本（只调了工具），补一条空的助手消息占位由下一轮填充
        if stop.name == "TOOL_USE" {
            let assistant = ChatMessageVM(role: .assistant, text: "", isStreaming: true)
            streamingMessageId = assistant.id
            messages.append(assistant)
            isStreaming = true
            return
        }
        persistIfNeeded()
    }

    fileprivate func handleFailed(_ error: AiError) {
        isStreaming = false
        finishStreamingMessage()
        if error.kind.name != "CANCELLED" {
            errorText = AiBridge.shared.errorText(error: error)
        }
        persistIfNeeded()
    }

    private func finishStreamingMessage() {
        guard let id = streamingMessageId,
              let index = messages.firstIndex(where: { $0.id == id }) else {
            streamingMessageId = nil
            return
        }
        messages[index].isStreaming = false
        if messages[index].text.isEmpty {
            messages.remove(at: index)
        }
        streamingMessageId = nil
    }

    private func persistIfNeeded() {
        guard AiSettingsStore.shared.settings.saveHistory, !kotlinMessages.isEmpty else { return }
        AiHistoryStore.shared.save(id: sessionId, messages: kotlinMessages)
    }

    // MARK: - 副作用确认

    fileprivate func requestConfirmation(
        callId: String,
        name: String,
        title: String,
        proceed: @escaping () -> Void,
        skip: @escaping () -> Void
    ) {
        pendingConfirmation = PendingToolConfirmation(
            callId: callId,
            name: name,
            title: title,
            proceed: { [weak self] in
                self?.pendingConfirmation = nil
                proceed()
            },
            skip: { [weak self] in
                self?.pendingConfirmation = nil
                skip()
            }
        )
    }
}

/// 把引擎回调转成主线程上的模型更新。
private final class EngineListener: NSObject, AiChatListener {

    weak var model: AiChatModel?

    func onAssistantDelta(text: String) {
        DispatchQueue.main.async { [weak self] in self?.model?.handleDelta(text) }
    }

    func onReasoningDelta(text: String) {
        // 思考过程暂不展示（与 Android 一致：只在需要时折叠展示，这里先丢弃）
    }

    func onToolStarted(callId: String, name: String, title: String) {
        DispatchQueue.main.async { [weak self] in
            self?.model?.handleToolStarted(callId: callId, name: name, title: title)
        }
    }

    func onToolFinished(
        callId: String,
        name: String,
        title: String,
        summary: String,
        isError: Bool,
        cardJson: String
    ) {
        DispatchQueue.main.async { [weak self] in
            self?.model?.handleToolFinished(
                callId: callId,
                name: name,
                summary: summary,
                isError: isError,
                cardJson: cardJson
            )
        }
    }

    func onUsage(inputTokens: Int32, outputTokens: Int32) {
        // token 用量暂不展示
    }

    func onTurnCompleted(messages: [AiMessage], stop: AiStopReason) {
        DispatchQueue.main.async { [weak self] in
            self?.model?.handleCompleted(messages, stop: stop)
        }
    }

    func onFailed(error: AiError) {
        DispatchQueue.main.async { [weak self] in self?.model?.handleFailed(error) }
    }
}

/// 工具宿主：副作用工具按设置拦截，等用户确认后再真正执行。
private final class ConfirmingToolHost: NSObject, AiToolHost {

    private let box: AiToolbox
    weak var model: AiChatModel?

    init(box: AiToolbox) {
        self.box = box
        super.init()
    }

    func specs() -> [AiToolSpec] {
        box.specs()
    }

    func execute(call: AiToolCall, callback: @escaping (AiToolResult) -> Void) {
        let spec = box.specs().first { $0.name == call.name }
        let needsConfirm = spec?.risk.name == "SIDE_EFFECT" &&
            AiSettingsStore.shared.settings.confirmSideEffects

        guard needsConfirm, let model = model else {
            box.execute(call: call, callback: callback)
            return
        }

        DispatchQueue.main.async { [weak self] in
            model.requestConfirmation(
                callId: call.id,
                name: call.name,
                title: spec?.title ?? call.name,
                proceed: {
                    self?.box.execute(call: call, callback: callback)
                },
                skip: {
                    callback(
                        AiToolResult(
                            modelText: "用户拒绝执行该操作",
                            summary: "已取消",
                            isError: true,
                            cardJson: ""
                        )
                    )
                }
            )
        }
    }
}

/// 把网络回调切回主线程：内核状态机不是线程安全的，必须串行在主线程驱动。
private final class MainThreadStreamTransport: NSObject, AiStreamTransport {

    private let inner = AiURLSessionTransport()

    func start(request: AiHttpRequest, listener: AiStreamListener) -> AiRequestHandle {
        inner.start(request: request, listener: MainThreadStreamListener(target: listener))
    }
}

private final class MainThreadStreamListener: NSObject, AiStreamListener {

    private let target: AiStreamListener

    init(target: AiStreamListener) {
        self.target = target
        super.init()
    }

    func onChunk(text: String) {
        DispatchQueue.main.async { [target] in target.onChunk(text: text) }
    }

    func onCompleted() {
        DispatchQueue.main.async { [target] in target.onCompleted() }
    }

    func onFailed(error: AiError) {
        DispatchQueue.main.async { [target] in target.onFailed(error: error) }
    }
}
