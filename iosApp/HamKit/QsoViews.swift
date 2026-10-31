import SwiftUI
import UIKit
import Shared

/// QSO 日志列表：对应 Android `QsoLogList` —— 列表 / 新增 / 编辑 / 删除 / ADIF+XML 导出。
struct QsoLogListView: View {

    @ObservedObject var store = QsoStore.shared

    @State private var editing: QsoEntry?
    @State private var showingNew = false
    @State private var exportURL: URL?
    @State private var exportError: String?

    var body: some View {
        List {
            if store.entries.isEmpty {
                Section {
                    Text("还没有通联记录；点右上角 + 新增，或等 FT8 自动记入。")
                        .foregroundStyle(.secondary)
                }
            }

            Section("记录（\(store.entries.count)）") {
                ForEach(store.entries) { entry in
                    Button {
                        editing = entry
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(entry.callsign).font(.headline).foregroundStyle(.primary)
                                Spacer()
                                Text(entry.bandName)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if entry.isComplete {
                                    Image(systemName: "checkmark.seal.fill")
                                        .font(.caption)
                                        .foregroundStyle(.green)
                                }
                            }
                            HStack(spacing: 10) {
                                Text(Self.dateFormatter.string(from: entry.qsoTime))
                                Text(entry.mode)
                                if !entry.grid.isEmpty { Text(entry.grid) }
                                Spacer()
                                Text(Self.freqText(entry.freqHz))
                            }
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { store.delete(at: $0) }
            }

            Section("导出") {
                Button {
                    export(format: "adi")
                } label: {
                    Label("导出 ADIF (.adi)", systemImage: "square.and.arrow.up")
                }
                Button {
                    export(format: "xml")
                } label: {
                    Label("导出 ADX XML (.xml)", systemImage: "square.and.arrow.up")
                }
                if let exportError {
                    Text(exportError).font(.footnote).foregroundStyle(.red)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("QSO 日志")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showingNew = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showingNew) {
            NavigationStack {
                QsoEditView(entry: QsoEntry(
                    callsign: "",
                    grid: "",
                    reportSent: "-99",
                    reportReceived: "-99",
                    bandName: "40m",
                    freqHz: 7_074_000,
                    mode: "FT8",
                    qsoTime: Date(),
                    isComplete: true,
                    operatorCallsign: "",
                    myGrid: "",
                    comment: ""
                ))
            }
        }
        .sheet(item: $editing) { entry in
            NavigationStack {
                QsoEditView(entry: entry)
            }
        }
        .sheet(item: Binding(
            get: { exportURL.map { ExportFile(url: $0) } },
            set: { if $0 == nil { exportURL = nil } }
        )) { file in
            ShareSheet(url: file.url)
        }
    }

    private func export(format: String) {
        if let url = store.export(format: format) {
            exportURL = url
            exportError = nil
        } else {
            exportError = "导出失败：无法写入临时文件"
        }
    }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f
    }()

    private static func freqText(_ hz: Int64) -> String {
        String(format: "%.3f MHz", Double(hz) / 1_000_000)
    }
}

/// 新增 / 编辑 QSO。
struct QsoEditView: View {

    @State var entry: QsoEntry
    @ObservedObject var store = QsoStore.shared
    @Environment(\.dismiss) private var dismiss

    private let bands: [(String, Int64)] = [
        ("160m", 1_840_000), ("80m", 3_573_000), ("60m", 5_357_000),
        ("40m", 7_074_000), ("30m", 10_136_000), ("20m", 14_074_000),
        ("17m", 18_100_000), ("15m", 21_074_000), ("12m", 24_915_000),
        ("10m", 28_074_000), ("6m", 50_313_000), ("2m", 144_174_000),
    ]

    var body: some View {
        Form {
            Section("对方") {
                TextField("呼号", text: $entry.callsign)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("网格（可选）", text: $entry.grid)
                    .textInputAutocapitalization(.characters)
            }

            Section("报告") {
                TextField("发送（RST）", text: $entry.reportSent)
                TextField("接收（RST）", text: $entry.reportReceived)
            }

            Section("频段") {
                Picker("频段", selection: $entry.bandName) {
                    ForEach(bands, id: \.0) { band in
                        Text(band.0).tag(band.0)
                    }
                }
                .onChange(of: entry.bandName) { name in
                    if let match = bands.first(where: { $0.0 == name }) {
                        entry.freqHz = match.1
                    }
                }
                TextField("频率（Hz）", value: $entry.freqHz, format: .number)
                    .keyboardType(.numberPad)
                TextField("模式", text: $entry.mode)
            }

            Section("时间") {
                DatePicker("QSO 时间", selection: $entry.qsoTime)
            }

            Section("本台") {
                TextField("操作员呼号", text: $entry.operatorCallsign)
                TextField("本台网格", text: $entry.myGrid)
                TextField("备注", text: $entry.comment)
            }

            Section {
                Toggle("通联完成", isOn: $entry.isComplete)
            }

            Section {
                Button("保存") {
                    store.upsert(entry)
                    dismiss()
                }
                .disabled(entry.callsign.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("删除", role: .destructive) {
                    store.delete(id: entry.id)
                    dismiss()
                }
            }
        }
        .navigationTitle(entry.callsign.isEmpty ? "新增 QSO" : "编辑 QSO")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("取消") { dismiss() }
            }
        }
    }
}

/// 导出文件包装（用于 sheet(item:)）。
private struct ExportFile: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// UIActivityViewController 包装，用于分享导出的 ADIF/XML。
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
