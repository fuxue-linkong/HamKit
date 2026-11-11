import SwiftUI
import Shared

/// AI 助手对话页（对应 Android `AiAssistantScreen`）。
///
/// 与 Android 的差异：没有图片附件、没有 token 用量展示；
/// 思考过程以折叠卡展示（推理等级 OFF 时为空）；
/// 其余交互（流式输出、工具卡、卫星卡、副作用确认、历史抽屉）保持一致。
struct AiAssistantView: View {

    @ObservedObject private var chat = AiChatModel.shared
    @ObservedObject private var settingsStore = AiSettingsStore.shared
    @ObservedObject private var history = AiHistoryStore.shared

    @State private var input = ""
    @State private var showHistory = false
    @State private var showSettings = false
    @State private var showModelPicker = false
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
        .sheet(isPresented: $showModelPicker) {
            AiModelSwitchView(
                store: settingsStore,
                isStreaming: chat.isStreaming,
                onManage: {
                    showModelPicker = false
                    // 等当前 sheet 完成收起再呈现设置，避免 presentation 冲突。
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        showSettings = true
                    }
                }
            )
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                AiSettingsView(isModal: true)
            }
        }
        .onAppear {
            // CI 截图用：直接弹出模型切换面板（正常启动不会命中）
            if ProcessInfo.processInfo.arguments.contains("-smokeAIModels") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { showModelPicker = true }
            }
            if ProcessInfo.processInfo.arguments.contains("-smokeAISettingsSheet") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { showSettings = true }
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
                if !message.reasoning.isEmpty {
                    ReasoningCard(text: message.reasoning, done: message.reasoningDone)
                }
                if message.text.isEmpty && message.isStreaming {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.mini)
                        Text(message.reasoning.isEmpty ? "正在思考…" : "思考中，正文即将输出…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
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

    /// 单容器输入栏：文本在上、附件/模型切换/发送在下一行（对齐 miuix 最新版）。
    private var inputBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            TextField("问点什么…", text: $input, axis: .vertical)
                .lineLimit(1...6)
                .textFieldStyle(.plain)
                .focused($inputFocused)
                .disabled(chat.isStreaming)
                .padding(.horizontal, 12)
                .padding(.top, 10)

            HStack(spacing: 8) {
                Button {
                    showModelPicker = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "sparkles")
                            .font(.system(size: 10))
                        Text(modelLabel)
                            .font(.caption)
                            .lineLimit(1)
                            .frame(maxWidth: 120, alignment: .leading)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                    }
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(Color.secondary.opacity(0.10))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("切换模型")

                Spacer()

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
                        .foregroundStyle(canSend || chat.isStreaming ? Color.accentColor : Color.secondary.opacity(0.5))
                }
                .disabled(!canSend && !chat.isStreaming)
                .accessibilityLabel(chat.isStreaming ? "停止生成" : "发送")
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
        }
        .background(Color.secondary.opacity(0.10))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(
                    inputFocused ? Color.accentColor : Color.secondary.opacity(0.18),
                    lineWidth: 1
                )
        )
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private var modelLabel: String {
        let model = settingsStore.activeProfile.currentModelId
        let effort = settingsStore.global.reasoningEffort
        let base = model.isEmpty ? "选择模型" : model
        if effort == "OFF" || effort.isEmpty { return base }
        let label = AiBridge.shared.reasoningEffortLabel(name: effort)
        return "\(base) \(label)"
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

// MARK: - 思考过程卡

/// 折叠的推理过程卡（对齐 Android `ReasoningCardMiuix`）：正文输出后自动收起。
private struct ReasoningCard: View {

    let text: String
    let done: Bool

    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) { expanded.toggle() }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: done ? "brain.head.profile" : "brain")
                        .font(.caption2)
                    Text(done ? "思考过程" : "思考中…")
                        .font(.caption2)
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption2)
                }
                .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            if expanded {
                Text(text)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.secondary.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
        }
        .padding(8)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
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
            // 模型偶尔把多个要点挤进同一行（"网格- 卫星：…"），先拆行再渲染
            let segments = Self.segments(AiViews.normalizeInlineBullets(text))
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

extension AiViews {

    /// 把中文行内列表拆成多行：`网格- 卫星：…` → `网格\n- 卫星：…`。
    ///
    /// 对齐 Android `normalizeInlineBullets`（62aa805）：只命中「前一个字符是
    /// 中文/右括号、`-` 后接 1-12 个中文 + 冒号」的模式，避免误伤数字范围（2-3）、
    /// 英文连字符（FM-xxx）与 URL。代码块（```）内不处理。
    static func normalizeInlineBullets(_ markdown: String) -> String {
        guard markdown.contains("-") else { return markdown }
        // 行内列表：中文/右括号 + 空白 + "- " + 短中文 + 冒号
        let inlineList = try? NSRegularExpression(
            pattern: "(?<=[\\x{4e00}-\\x{9fff}\\x{ff09}])\\s*-\\s+(?=[\\x{4e00}-\\x{9fff}]{1,12}[：:])"
        )
        // 行首 "-文字" 补空格（排除 "---" 分隔线）
        let lineStart = try? NSRegularExpression(pattern: "^-(?!-)(\\S)", options: [.anchorsMatchLines])
        let parts = markdown.components(separatedBy: "```")
        var rebuilt: [String] = []
        for (index, part) in parts.enumerated() {
            if index % 2 == 1 {
                rebuilt.append(part)
                continue
            }
            var section = part
            if let inlineList {
                section = inlineList.stringByReplacingMatches(
                    in: section,
                    range: NSRange(section.startIndex..., in: section),
                    withTemplate: "\n- "
                )
            }
            if let lineStart {
                section = lineStart.stringByReplacingMatches(
                    in: section,
                    range: NSRange(section.startIndex..., in: section),
                    withTemplate: "- $1"
                )
            }
            rebuilt.append(section)
        }
        return rebuilt.joined(separator: "```")
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

// MARK: - 模型切换

/// 按提供商分组、可搜索的模型切换面板（对齐 miuix 最新版）。
///
/// 生成中仍可打开面板查看，但不能切换；底部提供「管理模型…」跳设置页。
private struct AiModelSwitchView: View {

    @ObservedObject var store: AiSettingsStore
    let isStreaming: Bool
    let onManage: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private struct ModelGroup: Identifiable {
        let profile: AiProfile
        let entries: [AiModelEntry]
        var id: String { profile.id }
    }

    private var groups: [ModelGroup] {
        store.profiles.compactMap { profile in
            let entries = profile.models.filter { entry in
                query.isEmpty ||
                    profile.name.localizedCaseInsensitiveContains(query) ||
                    entry.modelId.localizedCaseInsensitiveContains(query) ||
                    (entry.displayName ?? "").localizedCaseInsensitiveContains(query)
            }
            return entries.isEmpty ? nil : ModelGroup(profile: profile, entries: entries)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                // 推理等级：与 miuix 两级菜单对齐（iOS 用内联菜单表达）
                Section("推理等级") {
                    Picker("推理等级", selection: effortBinding) {
                        ForEach(reasoningEfforts, id: \.name) { effort in
                            Text(effort.label).tag(effort.name)
                        }
                    }
                    .pickerStyle(.inline)
                }

                if groups.isEmpty {
                    Text("没有匹配的模型")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(groups) { group in
                        Section(group.profile.name) {
                            ForEach(group.entries) { entry in
                                Button {
                                    switchTo(profileId: group.profile.id, modelId: entry.id)
                                } label: {
                                    row(entry, profile: group.profile)
                                }
                                .disabled(isStreaming)
                            }
                        }
                    }
                }

                Section {
                    Button {
                        onManage()
                        dismiss()
                    } label: {
                        Label("管理模型…", systemImage: "gearshape")
                    }
                }

                if isStreaming {
                    Section {
                        Label("生成中不能切换模型", systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }
            }
            .searchable(text: $query, prompt: "搜索模型…")
            .navigationTitle("模型")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ entry: AiModelEntry, profile: AiProfile) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.label)
                    .foregroundStyle(.primary)
                if entry.label != entry.modelId {
                    Text(entry.modelId)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if store.activeProfileId == profile.id,
               profile.currentEntry?.id == entry.id {
                Image(systemName: "checkmark")
                    .foregroundStyle(Color.accentColor)
            }
        }
    }

    private func switchTo(profileId: String, modelId: String) {
        guard !isStreaming else { return }
        if store.switchModel(profileId: profileId, modelId: modelId) {
            dismiss()
        }
    }

    private var reasoningEfforts: [(name: String, label: String)] {
        AiBridge.shared.reasoningEffortNames()
            .split(separator: ",")
            .map { name in
                let key = String(name)
                return (key, AiBridge.shared.reasoningEffortLabel(name: key))
            }
    }

    private var effortBinding: Binding<String> {
        Binding(
            get: {
                let value = store.global.reasoningEffort
                return value.isEmpty ? "MEDIUM" : value
            },
            set: { value in
                store.updateGlobal { $0.reasoningEffort = value }
            }
        )
    }
}
