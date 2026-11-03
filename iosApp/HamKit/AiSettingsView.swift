import SwiftUI
import Shared

/// AI 设置页（对应 Android `AiSettingsScreen`）：服务商 / Key / 模型 / 提示词 / 工具开关。
///
/// 与 Android 的差异：没有「测试连接」与「自动获取模型列表」（需要非流式探测接口，
/// 见 docs/UPSTREAM_SYNC_2026-10-31.md 的缺口表）。
/// 一行工具开关的数据（不用元组：`ForEach` 的 id 需要 key path，元组不支持）。
private struct AiToolRow: Identifiable {
    let name: String
    let title: String
    let risk: String
    var id: String { name }
}

struct AiSettingsView: View {

    @ObservedObject private var store = AiSettingsStore.shared
    @ObservedObject private var history = AiHistoryStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var keyDraft = ""
    @State private var showKey = false
    @State private var showPromptEditor = false
    @State private var promptDraft = ""
    @State private var showClearAll = false

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
            Section {
                Picker("服务商", selection: Binding(
                    get: { store.settings.preset },
                    set: { store.applyPreset($0) }
                )) {
                    ForEach(presets, id: \.self) { name in
                        Text(AiBridge.shared.presetLabel(name: name)).tag(name)
                    }
                }
                Picker("协议", selection: Binding(
                    get: { store.settings.protocolName },
                    set: { store.settings.protocolName = $0 }
                )) {
                    ForEach(protocols, id: \.self) { name in
                        Text(AiBridge.shared.protocolLabel(name: name)).tag(name)
                    }
                }
                TextField("Base URL", text: Binding(
                    get: { store.settings.baseUrl },
                    set: { store.settings.baseUrl = $0 }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)

                TextField("模型", text: Binding(
                    get: { store.settings.model },
                    set: { store.settings.model = $0 }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

                if !store.settings.presetModels.isEmpty {
                    Menu {
                        ForEach(store.settings.presetModels, id: \.self) { model in
                            Button(model) { store.settings.model = model }
                        }
                    } label: {
                        Label("选择建议模型", systemImage: "list.bullet")
                    }
                }
            } header: {
                Text("服务商")
            } footer: {
                Text("默认 DeepSeek（国内可直连）。Base URL 可填代理或内网地址；App 只允许 https（http 仅限私有网段）。")
            }

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
                    store.saveApiKey(keyDraft)
                    keyDraft = ""
                }
                .disabled(keyDraft.trimmingCharacters(in: .whitespaces).isEmpty)
                if store.hasApiKey {
                    Button("清除 Key", role: .destructive) {
                        store.clearApiKey()
                    }
                }
            } header: {
                Text("API Key")
            } footer: {
                Text(store.hasApiKey ? "已保存在系统钥匙串（不同步 iCloud、不进 iTunes 备份）。" : "Key 只在本机加密保存，不写入日志。")
            }

            Section("生成参数") {
                VStack(alignment: .leading) {
                    HStack {
                        Text("温度")
                        Spacer()
                        Text(String(format: "%.2f", store.settings.temperature))
                            .foregroundStyle(.secondary)
                    }
                    Slider(
                        value: Binding(
                            get: { store.settings.temperature },
                            set: { store.settings.temperature = $0 }
                        ),
                        in: 0...2,
                        step: 0.1
                    )
                }
                Stepper(
                    "最大输出 \(store.settings.maxOutputTokens) tokens",
                    value: Binding(
                        get: { store.settings.maxOutputTokens },
                        set: { store.settings.maxOutputTokens = $0 }
                    ),
                    in: 256...8192,
                    step: 256
                )
                Stepper(
                    "工具往返上限 \(store.settings.maxToolRounds) 次",
                    value: Binding(
                        get: { store.settings.maxToolRounds },
                        set: { store.settings.maxToolRounds = $0 }
                    ),
                    in: 1...8
                )
            }

            Section {
                Button {
                    promptDraft = store.settings.systemPrompt
                    showPromptEditor = true
                } label: {
                    LabeledContent("系统提示词") {
                        Text(store.settings.systemPrompt.isEmpty ? "默认" : "自定义")
                            .foregroundStyle(.secondary)
                    }
                }
            } footer: {
                Text("留空使用内置提示词（已写明可调用的工具与「不要编造数据」的要求）。")
            }

            Section {
                ForEach(tools) { tool in
                    Toggle(isOn: Binding(
                        get: { !store.settings.disabledTools.contains(tool.name) },
                        set: { enabled in
                            if enabled {
                                store.settings.disabledTools.remove(tool.name)
                            } else {
                                store.settings.disabledTools.insert(tool.name)
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
                    get: { store.settings.confirmSideEffects },
                    set: { store.settings.confirmSideEffects = $0 }
                ))
            } header: {
                Text("工具")
            } footer: {
                Text("关掉的工具不会出现在请求里，模型完全看不到，也就不会调用。")
            }

            Section("历史") {
                Toggle("保存对话历史", isOn: Binding(
                    get: { store.settings.saveHistory },
                    set: { store.settings.saveHistory = $0 }
                ))
                Button("清除所有对话", role: .destructive) {
                    showClearAll = true
                }
            }
        }
        .navigationTitle("AI 设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("完成") { dismiss() }
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
                                store.settings.systemPrompt = promptDraft
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
    }
}
