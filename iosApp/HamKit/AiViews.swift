import SwiftUI
import Shared

/// AI 助手对话页（对应 Android `AiAssistantScreen`）。
///
/// 与 Android 的差异：没有图片附件、没有思考过程折叠、没有 token 用量展示；
/// 其余交互（流式输出、工具卡、卫星卡、副作用确认、历史抽屉）保持一致。
struct AiAssistantView: View {

    @ObservedObject private var chat = AiChatModel.shared
    @ObservedObject private var settingsStore = AiSettingsStore.shared
    @ObservedObject private var history = AiHistoryStore.shared

    @State private var input = ""
    @State private var showHistory = false
    @State private var showSettings = false
    @State private var shareURL: URL?
    @FocusState private var inputFocused: Bool

    private let examples = [
        "ISS 今晚什么时候过境？最大仰角多少？",
        "我这个网格的 CQ / ITU 分区是多少？",
        "AO-91 的上行和下行频率是多少？",
        "帮我看看现在有哪些卫星正在过境",
    ]

    var body: some View {
        VStack(spacing: 0) {
            messageList
            if let warning = chat.permissionWarning {
                banner(text: warning, color: .orange) { chat.permissionWarning = nil }
            }
            if let error = chat.errorText {
                banner(text: error, color: .red) { chat.errorText = nil }
            }
            inputBar
        }
        .background(MiuixTheme.background)
        .navigationTitle("AI 助手")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        chat.newSession()
                    } label: {
                        Label("新对话", systemImage: "square.and.pencil")
                    }
                    Button {
                        showHistory = true
                    } label: {
                        Label("历史对话", systemImage: "clock.arrow.circlepath")
                    }
                    Button {
                        showSettings = true
                    } label: {
                        Label("AI 设置", systemImage: "gearshape")
                    }
                    if let url = history.exportMarkdown(id: chat.sessionId) {
                        Button {
                            shareURL = url
                        } label: {
                            Label("导出 Markdown", systemImage: "square.and.arrow.up")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showHistory) {
            NavigationStack {
                AiHistoryView()
            }
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                AiSettingsView()
            }
        }
        .sheet(item: Binding(
            get: { shareURL.map { SharedAiFile(url: $0) } },
            set: { if $0 == nil { shareURL = nil } }
        )) { file in
            ShareSheet(url: file.url)
        }
        .alert(
            "执行工具「\(chat.pendingConfirmation?.title ?? "")」？",
            isPresented: Binding(
                get: { chat.pendingConfirmation != nil },
                set: { if !$0 { chat.pendingConfirmation?.skip() } }
            )
        ) {
            Button("执行") { chat.pendingConfirmation?.proceed() }
            Button("跳过", role: .cancel) { chat.pendingConfirmation?.skip() }
        } message: {
            Text("这个操作会修改本地状态（例如创建过境提醒）。可以在 AI 设置里关掉二次确认。")
        }
    }

    // MARK: - 消息列表

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if chat.messages.isEmpty {
                        emptyState
                    }
                    ForEach(chat.messages) { message in
                        row(for: message).id(message.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
            }
            .onChange(of: chat.messages.count) { _ in
                if let last = chat.messages.last {
                    withAnimation(.easeOut(duration: 0.15)) {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func row(for message: ChatMessageVM) -> some View {
        switch message.role {
        case .user:
            HStack {
                Spacer(minLength: 40)
                Text(message.text)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.accentColor.opacity(0.15))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        case .assistant:
            VStack(alignment: .leading, spacing: 6) {
                if message.text.isEmpty && message.isStreaming {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.mini)
                        Text("正在思考…").font(.footnote).foregroundStyle(.secondary)
                    }
                } else {
                    MarkdownLiteView(text: message.text)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .tool:
            ToolRow(message: message)
        }
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("问我卫星过境、频率、分区，或者直接让我打开某个页面。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            ForEach(examples, id: \.self) { text in
                Button {
                    input = text
                    inputFocused = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "sparkles").font(.caption)
                        Text(text).font(.footnote)
                        Spacer()
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(Color.secondary.opacity(0.1))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            if !settingsStore.hasApiKey {
                Text("首次使用请先到「AI 设置」里填写 API Key（存在系统钥匙串）。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - 输入栏

    private var inputBar: some View {
        HStack(spacing: 8) {
            TextField("问点什么…", text: $input, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Color.secondary.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .focused($inputFocused)
                .disabled(chat.isStreaming)

            Button {
                if chat.isStreaming {
                    chat.stop()
                } else {
                    let text = input
                    input = ""
                    chat.send(text)
                }
            } label: {
                Image(systemName: chat.isStreaming ? "stop.circle.fill" : "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(canSend ? Color.accentColor : Color.secondary.opacity(0.5))
            }
            .disabled(!canSend && !chat.isStreaming)
            .accessibilityLabel(chat.isStreaming ? "停止生成" : "发送")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private var canSend: Bool {
        !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func banner(text: String, color: Color, onClose: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(color)
            Text(text).font(.caption)
            Spacer()
            Button {
                onClose()
            } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(color.opacity(0.1))
    }
}

// MARK: - 工具行与卡片

private struct ToolRow: View {

    let message: ChatMessageVM

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: message.isError ? "exclamationmark.circle" : "wrench.and.screwdriver")
                    .font(.caption)
                    .foregroundStyle(message.isError ? .red : .secondary)
                Text(message.text)
                    .font(.caption.weight(.medium))
                Text(message.toolSummary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer()
            }
            if let card = message.card {
                SatelliteCardView(card: card)
            }
        }
        .padding(10)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct SatelliteCardView: View {

    let card: SatelliteCardVM

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(card.name).font(.subheadline.weight(.semibold))
                Text("#\(card.norad)").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                if !card.status.isEmpty {
                    Text(card.status)
                        .font(.caption2)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Color.accentColor.opacity(0.15))
                        .clipShape(Capsule())
                }
            }
            if !card.modes.isEmpty {
                Text(card.modes.joined(separator: " · "))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if let pass = card.pass {
                Text(
                    (pass.inPass ? "正在过境（AOS \(pass.aos)）" : "下一次 AOS \(pass.aos)") +
                        " · 最大仰角 \(String(format: "%.0f°", pass.maxElevation))"
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            ForEach(Array(card.radios.prefix(3).indices), id: \.self) { index in
                let radio = card.radios[index]
                Text(
                    "\(radio.mode.isEmpty ? "—" : radio.mode)  " +
                        "↓\(Self.freq(radio.downlinkHz)) ↑\(Self.freq(radio.uplinkHz))"
                )
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
        .padding(8)
        .background(Color.secondary.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private static func freq(_ hz: Int64) -> String {
        hz <= 0 ? "—" : String(format: "%.4f MHz", Double(hz) / 1_000_000)
    }
}

// MARK: - 轻量 Markdown

/// 只做「代码块 + 行内 Markdown」两级渲染：iOS 16 的 AttributedString 已能处理
/// 粗体/斜体/行内代码/链接，表格与标题在此简化处理（Android 端是自研渲染器）。
private struct MarkdownLiteView: View {

    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            let segments = Self.segments(text)
            ForEach(Array(segments.indices), id: \.self) { index in
                let segment = segments[index]
                switch segment {
                case .code(let code):
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(code)
                            .font(.system(.caption, design: .monospaced))
                            .padding(8)
                    }
                    .background(Color.black.opacity(0.06))
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                case .prose(let prose):
                    if let attributed = try? AttributedString(
                        markdown: prose,
                        options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
                    ) {
                        Text(attributed).font(.callout)
                    } else {
                        Text(prose).font(.callout)
                    }
                }
            }
        }
    }

    private enum Segment {
        case prose(String)
        case code(String)
    }

    /// 按 ``` 围栏切分：偶数段是正文，奇数段是代码。
    private static func segments(_ text: String) -> [Segment] {
        var result: [Segment] = []
        var buffer = ""
        var inCode = false
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                if !buffer.isEmpty {
                    result.append(inCode ? .code(buffer) : .prose(buffer))
                    buffer = ""
                }
                inCode.toggle()
                continue
            }
            buffer += line + "\n"
        }
        if !buffer.isEmpty {
            result.append(inCode ? .code(buffer) : .prose(buffer))
        }
        return result.isEmpty ? [.prose(text)] : result
    }
}

private struct SharedAiFile: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

// MARK: - 历史对话

struct AiHistoryView: View {

    @ObservedObject private var history = AiHistoryStore.shared
    @ObservedObject private var chat = AiChatModel.shared
    @Environment(\.dismiss) private var dismiss
    @State private var pendingDelete: AiSessionMeta?

    var body: some View {
        List {
            Section {
                Button {
                    chat.newSession()
                    dismiss()
                } label: {
                    Label("开始新对话", systemImage: "square.and.pencil")
                }
            }
            if history.sessions.isEmpty {
                Text("还没有历史对话").foregroundStyle(.secondary)
            } else {
                Section("历史对话") {
                    ForEach(history.sessions) { session in
                        Button {
                            chat.loadSession(id: session.id)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(session.title).font(.subheadline)
                                Text("\(session.messageCount) 条 · \(session.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                pendingDelete = session
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("历史对话")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("关闭") { dismiss() }
            }
        }
        .confirmationDialog(
            "删除这段对话？",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let session = pendingDelete { chat.deleteSession(id: session.id) }
                pendingDelete = nil
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        }
    }
}
