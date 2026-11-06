import SwiftUI
import Shared

/// AI 设置页（对应 Android `AiSettingsScreen`）。
///
/// 结构对齐 miuix 最新版：
/// - 顶部「当前模型」摘要卡 + 切换提供商；
/// - 模型区按「预设服务商 / 自定义 API」分 Tab，提供商下可维护多个模型
///   （每个模型有 ID 与可选显示名称），统一保存 / 取消；
/// - API Key 按提供商独立保存（钥匙串槽位）；
/// - 全局项（系统提示词、工具、历史）仍即时保存。
private struct AiToolRow: Identifiable {
    let name: String
    let title: String
    let risk: String
    var id: String { name }
}

struct AiSettingsView: View {

    /// 以 sheet 呈现时显示「完成」；作为导航栈里的页面时只保留系统返回键。
    var isModal = false

    @ObservedObject private var store = AiSettingsStore.shared
    @ObservedObject private var history = AiHistoryStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var draft: AiProfile?
    @State private var customApi = false
    @State private var keyDraft = ""
    @State private var showKey = false
    @State private var showProfilePicker = false
    @State private var showPromptEditor = false
    @State private var promptDraft = ""
    @State private var showClearAll = false
    @State private var catalog: [String] = []
    @State private var fetchingCatalog = false
    @State private var catalogError: String?
    @State private var notice: String?
    @State private var errorMessage: String?
    @State private var showSaveError = false

    private var presets: [String] {
        AiBridge.shared.presetNames().split(separator: ",").map(String.init)
    }

    private var protocols: [String] {
        AiBridge.shared.protocolNames().split(separator: ",").map(String.init)
    }

    /// 工具清单来自内核（名称 + 标题 + 风险），设置页只做开关。
    private var tools: [AiToolRow] {
        let json = AiBridge.shared.toolCatalogJson(toolbox: AiToolbox(facade: AiAppFacadeImpl.shared))
        guard let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return array.map {
            AiToolRow(
                name: $0["name"] as? String ?? "",
                title: $0["title"] as? String ?? "",
                risk: $0["risk"] as? String ?? "READ_ONLY"
            )
        }
    }

    var body: some View {
        Form {
            activeModelSection

            if let binding = draftBinding {
                modelSection(profile: binding)
                apiKeySection(profile: binding.wrappedValue)
            }

            generationSection
            promptSection
            toolsSection
            historySection
            saveSection
        }
        .navigationTitle("AI 设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isModal {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") {
                        // 离开前把能保存的改动落盘，避免用户以为「完成」= 保存。
                        persistDraftIfValid()
                        dismiss()
                    }
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") {
                    saveDraft()
                    if errorMessage == nil {
                        dismiss()
                    } else {
                        showSaveError = true
                    }
                }
                .disabled(draft == nil)
            }
        }
        .onAppear {
            if draft == nil { loadDraft() }
        }
        .sheet(isPresented: $showProfilePicker) {
            NavigationStack {
                AiProfilePickerView(
                    store: store,
                    onSelect: { id in
                        // 与 Android 一致：切换前先把当前表单写回，避免丢改动。
                        persistDraftIfValid()
                        store.selectProfile(id)
                        showProfilePicker = false
                        loadDraft()
                    },
                    onAdd: {
                        persistDraftIfValid()
                        _ = store.addProfile()
                        showProfilePicker = false
                        loadDraft()
                    },
                    onDeletedActive: {
                        showProfilePicker = false
                        loadDraft()
                    }
                )
            }
        }
        .sheet(isPresented: $showPromptEditor) {
            NavigationStack {
                TextEditor(text: $promptDraft)
                    .font(.callout)
                    .padding(8)
                    .navigationTitle("系统提示词")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("取消") { showPromptEditor = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("保存") {
                                store.updateGlobal { $0.systemPrompt = promptDraft }
                                showPromptEditor = false
                            }
                        }
                        ToolbarItem(placement: .bottomBar) {
                            Button("恢复默认") { promptDraft = "" }
                        }
                    }
            }
        }
        .confirmationDialog(
            "清除所有对话？",
            isPresented: $showClearAll,
            titleVisibility: .visible
        ) {
            Button("清除", role: .destructive) {
                history.deleteAll()
                AiChatModel.shared.newSession()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只删除本机的对话记录，不影响服务商侧的数据。")
        }
        .alert(
            "无法保存",
            isPresented: $showSaveError
        ) {
            Button("好") {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    // MARK: - 当前模型

    private var activeModelSection: some View {
        Section {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(store.activeProfile.name)
                            .font(.headline)
                        Text("当前")
                            .font(.caption2)
                            .foregroundStyle(Color.accentColor)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.accentColor.opacity(0.12))
                            .clipShape(Capsule())
                    }
                    Text(
                        "\(store.activeProfile.protocolLabel) · " +
                            (store.activeProfile.currentModelId.isEmpty
                                ? "未配置模型"
                                : store.activeProfile.currentModelId)
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }
                Spacer()
                Button("切换") { showProfilePicker = true }
            }
        } header: {
            Text("当前模型")
        } footer: {
            Text("切换只影响连接参数；系统提示词、工具与历史为全局设置。")
        }
    }

    // MARK: - 模型配置

    @ViewBuilder
    private func modelSection(profile: Binding<AiProfile>) -> some View {
        Section {
            Picker("配置方式", selection: $customApi) {
                Text("预设服务商").tag(false)
                Text("自定义 API").tag(true)
            }
            .pickerStyle(.segmented)

            if customApi {
                Picker("协议", selection: profile.protocolName) {
                    ForEach(protocols, id: \.self) { name in
                        Text(AiBridge.shared.protocolLabel(name: name)).tag(name)
                    }
                }
                TextField("Base URL", text: profile.baseUrl)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(presets, id: \.self) { name in
                            presetChip(name, profile: profile)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }

            TextField("显示名称", text: profile.name)

            HStack {
                Text("模型目录")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button(fetchingCatalog ? "获取中…" : "获取模型列表") {
                    Task { await fetchCatalog(for: profile.wrappedValue) }
                }
                .font(.caption)
                .disabled(fetchingCatalog)
            }

            if let catalogError {
                Text(catalogError)
                    .font(.caption2)
                    .foregroundStyle(.red)
            }
            if catalog.isEmpty {
                Text("还没获取模型；目录外 ID 仍可直接填写。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(catalog, id: \.self) { model in
                            Button(model) {
                                addModel(model, to: profile)
                            }
                            .font(.caption2)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(Color.secondary.opacity(0.12))
                            .foregroundStyle(.primary)
                            .clipShape(Capsule())
                        }
                    }
                    .padding(.vertical, 2)
                }
            }

            ForEach(profile.wrappedValue.models) { entry in
                modelRow(entry, profile: profile)
            }

            Button {
                appendModel(to: profile)
            } label: {
                Label("新建模型", systemImage: "plus")
            }

            Button {
                addProvider()
            } label: {
                Label("添加模型提供商", systemImage: "plus.circle")
                    .frame(maxWidth: .infinity)
            }
            .padding(.vertical, 2)
        } header: {
            Text("模型")
        } footer: {
            Text("一个提供商下可保存多个模型；列表里打勾的是当前使用的模型。")
        }
    }

    private func presetChip(_ name: String, profile: Binding<AiProfile>) -> some View {
        let selected = profile.wrappedValue.preset == name
        return Button {
            applyPreset(name, to: profile)
        } label: {
            Text(AiBridge.shared.presetLabel(name: name))
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(selected ? Color.accentColor : Color.secondary.opacity(0.12))
                .foregroundStyle(selected ? Color.white : Color.primary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func modelRow(_ entry: AiModelEntry, profile: Binding<AiProfile>) -> some View {
        let selected = profile.wrappedValue.selectedModelId == entry.id
        return HStack(spacing: 8) {
            VStack(spacing: 4) {
                TextField("模型 ID", text: modelIdBinding(entry.id, profile: profile))
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("显示名称（可选）", text: displayNameBinding(entry.id, profile: profile))
                    .font(.caption)
            }
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.accentColor)
            }
            Button {
                removeModel(entry.id, from: profile)
            } label: {
                Image(systemName: "xmark.circle")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            profile.wrappedValue.selectedModelId = entry.id
        }
    }

    // MARK: - API Key

    private func apiKeySection(profile: AiProfile) -> some View {
        Section {
            HStack {
                if showKey {
                    TextField("API Key", text: $keyDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } else {
                    SecureField("API Key", text: $keyDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Button {
                    showKey.toggle()
                } label: {
                    Image(systemName: showKey ? "eye.slash" : "eye")
                }
                .buttonStyle(.borderless)
            }
            Button("保存 Key") {
                store.saveApiKey(keyDraft, for: profile.id)
                keyDraft = ""
                notice = "API Key 已保存"
                errorMessage = nil
            }
            .disabled(keyDraft.trimmingCharacters(in: .whitespaces).isEmpty)
            if store.keyExists(for: profile.id) {
                Button("清除 Key", role: .destructive) {
                    store.clearApiKey(for: profile.id)
                    notice = "API Key 已清除"
                }
            }
        } header: {
            Text("API Key")
        } footer: {
            if let masked = store.maskedApiKey(for: profile.id) {
                Text("已保存：\(masked)。每个提供商使用独立的钥匙串槽位，不同步 iCloud、不进 iTunes 备份。")
            } else {
                Text("当前提供商还没有保存 Key。Key 只在本机钥匙串加密保存，不写入日志。")
            }
        }
    }

    // MARK: - 生成参数

    private var generationSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("温度")
                    Spacer()
                    Text(String(format: "%.2f", draft?.temperature ?? 0.6))
                        .foregroundStyle(.secondary)
                }
                Slider(
                    value: Binding(
                        get: { draft?.temperature ?? 0.6 },
                        set: { draft?.temperature = $0 }
                    ),
                    in: 0...2,
                    step: 0.1
                )
            }
            Stepper(
                "最大输出 \(draft?.maxOutputTokens ?? 2048) tokens",
                value: Binding(
                    get: { draft?.maxOutputTokens ?? 2048 },
                    set: { draft?.maxOutputTokens = $0 }
                ),
                in: 256...8192,
                step: 256
            )
            Stepper(
                "工具往返上限 \(store.global.maxToolRounds) 次",
                value: Binding(
                    get: { store.global.maxToolRounds },
                    set: { value in store.updateGlobal { $0.maxToolRounds = value } }
                ),
                in: 1...8
            )
        } header: {
            Text("生成参数")
        } footer: {
            Text("温度与最大输出随提供商配置保存；工具往返上限为全局设置。")
        }
    }

    // MARK: - 系统提示词

    private var promptSection: some View {
        Section {
            Button {
                promptDraft = store.global.systemPrompt
                showPromptEditor = true
            } label: {
                LabeledContent("系统提示词") {
                    Text(store.global.systemPrompt.isEmpty ? "默认" : "自定义")
                        .foregroundStyle(.secondary)
                }
            }
        } footer: {
            Text("留空使用内置提示词（已写明可调用的工具与「不要编造数据」的要求）。")
        }
    }

    // MARK: - 工具

    private var toolsSection: some View {
        Section {
            ForEach(tools) { tool in
                Toggle(isOn: Binding(
                    get: { !store.global.disabledTools.contains(tool.name) },
                    set: { enabled in
                        store.updateGlobal { global in
                            if enabled {
                                global.disabledTools.remove(tool.name)
                            } else {
                                global.disabledTools.insert(tool.name)
                            }
                        }
                    }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tool.title)
                        Text(tool.name + (tool.risk == "SIDE_EFFECT" ? " · 会改本地状态" : ""))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Toggle("副作用工具执行前确认", isOn: Binding(
                get: { store.global.confirmSideEffects },
                set: { value in store.updateGlobal { $0.confirmSideEffects = value } }
            ))
        } header: {
            Text("工具")
        } footer: {
            Text("关掉的工具不会出现在请求里，模型完全看不到，也就不会调用。")
        }
    }

    // MARK: - 历史

    private var historySection: some View {
        Section("历史") {
            Toggle("保存对话历史", isOn: Binding(
                get: { store.global.saveHistory },
                set: { value in store.updateGlobal { $0.saveHistory = value } }
            ))
            Button("清除所有对话", role: .destructive) {
                showClearAll = true
            }
        }
    }

    // MARK: - 保存

    /// 统一保存提供商配置（连接参数 / 模型列表 / 选中项 / 生成参数）。
    /// 全局项（提示词、工具、历史）在各自开关处即时保存。
    private var saveSection: some View {
        Section {
            Button {
                saveDraft()
            } label: {
                Text("保存设置")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            Button("取消修改") { loadDraft() }
                .frame(maxWidth: .infinity)

            if let notice {
                Text(notice)
                    .font(.caption)
                    .foregroundStyle(.green)
            }
            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } footer: {
            Text("保存当前提供商的连接参数、模型列表与生成参数；系统提示词、工具与历史为即时保存。")
        }
    }

    // MARK: - 草稿与操作

    private var draftBinding: Binding<AiProfile>? {
        guard draft != nil else { return nil }
        return Binding(
            get: { self.draft ?? AiProfile.fallback },
            set: { self.draft = $0 }
        )
    }

    private func loadDraft() {
        let profile = store.activeProfile
        draft = profile
        customApi = profile.preset == "CUSTOM"
        keyDraft = ""
        showKey = false
        catalog = []
        catalogError = nil
        notice = nil
        errorMessage = nil
    }

    private func saveDraft() {
        guard var value = draft else { return }
        value = value.normalized()
        guard !value.baseUrl.isEmpty else {
            errorMessage = "Base URL 不能为空"
            return
        }
        guard !value.models.isEmpty else {
            errorMessage = "至少填写一个模型 ID"
            return
        }
        guard let url = URL(string: value.baseUrl),
              let scheme = url.scheme?.lowercased(),
              (scheme == "https" || scheme == "http"),
              url.host?.isEmpty == false else {
            errorMessage = "Base URL 格式不正确（需要 http/https）"
            return
        }
        draft = value
        _ = store.updateProfile(value)
        catalog = []
        catalogError = nil
        notice = "设置已保存"
        errorMessage = nil
    }

    private func persistDraftIfValid() {
        guard let value = draft, !value.models.isEmpty, !value.baseUrl.isEmpty else { return }
        _ = store.updateProfile(value)
    }

    private func addProvider() {
        persistDraftIfValid()
        let profile = store.addProfile()
        draft = profile
        customApi = profile.preset == "CUSTOM"
        keyDraft = ""
        catalog = []
        catalogError = nil
        notice = "已新建「\(profile.name)」，请填写 Key"
        errorMessage = nil
    }

    private func applyPreset(_ name: String, to profile: Binding<AiProfile>) {
        var value = profile.wrappedValue
        value.preset = name
        value.protocolName = AiBridge.shared.presetProtocol(name: name)
        let base = AiBridge.shared.presetBaseUrl(name: name)
        if !base.isEmpty { value.baseUrl = base }
        if value.models.isEmpty {
            let model = AiBridge.shared.presetDefaultModel(name: name)
            if !model.isEmpty {
                value.models = [AiModelEntry(id: "m1", modelId: model)]
                value.selectedModelId = "m1"
            }
        }
        profile.wrappedValue = value
        catalog = []
        catalogError = nil
    }

    private func appendModel(to profile: Binding<AiProfile>) {
        var value = profile.wrappedValue
        let entry = AiModelEntry(id: "m_" + Self.shortId(), modelId: "", displayName: nil)
        value.models.append(entry)
        value.selectedModelId = entry.id
        profile.wrappedValue = value
        notice = nil
        errorMessage = nil
    }

    private func addModel(_ modelId: String, to profile: Binding<AiProfile>) {
        var value = profile.wrappedValue
        if let existing = value.models.first(where: { $0.modelId == modelId }) {
            value.selectedModelId = existing.id
        } else {
            let entry = AiModelEntry(id: "m_" + Self.shortId(), modelId: modelId, displayName: nil)
            value.models.append(entry)
            value.selectedModelId = entry.id
        }
        profile.wrappedValue = value
        errorMessage = nil
    }

    private func removeModel(_ entryId: String, from profile: Binding<AiProfile>) {
        var value = profile.wrappedValue
        guard value.models.count > 1 else {
            errorMessage = "至少保留一个模型"
            return
        }
        value.models.removeAll { $0.id == entryId }
        if value.selectedModelId == entryId {
            value.selectedModelId = value.models.first?.id
        }
        profile.wrappedValue = value
        errorMessage = nil
    }

    private func modelIdBinding(_ entryId: String, profile: Binding<AiProfile>) -> Binding<String> {
        Binding(
            get: { profile.wrappedValue.models.first { $0.id == entryId }?.modelId ?? "" },
            set: { newValue in
                var value = profile.wrappedValue
                guard let index = value.models.firstIndex(where: { $0.id == entryId }) else { return }
                value.models[index].modelId = newValue
                profile.wrappedValue = value
            }
        )
    }

    private func displayNameBinding(_ entryId: String, profile: Binding<AiProfile>) -> Binding<String> {
        Binding(
            get: { profile.wrappedValue.models.first { $0.id == entryId }?.displayName ?? "" },
            set: { newValue in
                var value = profile.wrappedValue
                guard let index = value.models.firstIndex(where: { $0.id == entryId }) else { return }
                let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                value.models[index].displayName = trimmed.isEmpty ? nil : newValue
                profile.wrappedValue = value
            }
        )
    }

    @MainActor
    private func fetchCatalog(for profile: AiProfile) async {
        let key = store.apiKey(for: profile.id)
        guard !key.isEmpty else {
            errorMessage = "请先保存当前提供商的 API Key"
            return
        }
        fetchingCatalog = true
        catalogError = nil
        errorMessage = nil
        do {
            catalog = try await AiModelCatalog.fetch(profile: profile, apiKey: key)
        } catch {
            catalogError = error.localizedDescription
        }
        fetchingCatalog = false
    }

    private static func shortId() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)).lowercased()
    }
}

// MARK: - 提供商切换

/// 提供商列表：切换、新建、删除（至少保留一个）。
struct AiProfilePickerView: View {

    @ObservedObject var store: AiSettingsStore
    /// 选中某个提供商（父页会先保存当前草稿再切换）。
    let onSelect: (String) -> Void
    /// 新建提供商（父页会先保存当前草稿）。
    let onAdd: () -> Void
    /// 删掉的是当前提供商时通知父页重新载入草稿。
    let onDeletedActive: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                ForEach(store.profiles) { profile in
                    Button {
                        onSelect(profile.id)
                        dismiss()
                    } label: {
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(profile.name)
                                    .foregroundStyle(.primary)
                                Text(
                                    "\(profile.protocolLabel) · " +
                                        (profile.currentModelId.isEmpty ? "未配置模型" : profile.currentModelId)
                                )
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            }
                            Spacer()
                            if profile.id == store.activeProfileId {
                                Text("当前")
                                    .font(.caption2)
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
                .onDelete { offsets in
                    guard let index = offsets.first,
                          store.profiles.indices.contains(index) else { return }
                    let profile = store.profiles[index]
                    let wasActive = profile.id == store.activeProfileId
                    if !store.deleteProfile(profile.id) {
                        errorMessage = "至少保留一个模型提供商"
                    } else if wasActive {
                        onDeletedActive()
                        dismiss()
                    }
                }
            } footer: {
                Text("切换只影响连接参数；系统提示词、工具与历史为全局设置。")
            }

            Section {
                Button {
                    onAdd()
                    dismiss()
                } label: {
                    Label("新建模型提供商", systemImage: "plus")
                }
            }
        }
        .navigationTitle("模型")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("关闭") { dismiss() }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                EditButton()
            }
        }
        .alert(
            "提示",
            isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )
        ) {
            Button("好") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }
}
