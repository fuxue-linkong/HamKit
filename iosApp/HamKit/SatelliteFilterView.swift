import SwiftUI
import Shared

/// 卫星筛选页：字段与 Android `SatelliteFilter` 一致；
/// 草稿在本地编辑，点「应用」写回 `SatelliteStore.filter`。
struct SatelliteFilterView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject private var store = SatelliteStore.shared
    @ObservedObject private var categoryManager = SatelliteCategoryManager.shared
    @Environment(\.dismiss) private var dismiss

    @State private var draft = SatelliteFilter()

    var body: some View {
        List {
            Section("名称 / 编号") {
                TextField("搜索卫星名称或 NORAD", text: $draft.nameQuery)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }

            Section("工作模式") {
                ForEach(SatelliteFilter.selectableModes, id: \.self) { mode in
                    toggleRow(
                        title: mode,
                        selected: draft.modes.contains(mode)
                    ) {
                        if draft.modes.contains(mode) { draft.modes.remove(mode) }
                        else { draft.modes.insert(mode) }
                    }
                }
                toggleRow(title: "未知模式", selected: draft.modes.contains("")) {
                    if draft.modes.contains("") { draft.modes.remove("") }
                    else { draft.modes.insert("") }
                }
            }

            Section("分类") {
                if categoryManager.snapshot.sortedCategories.isEmpty {
                    Text("还没有分类，先到「分类管理」新建。").foregroundStyle(.secondary)
                }
                ForEach(categoryManager.snapshot.sortedCategories) { category in
                    toggleRow(
                        title: category.name,
                        selected: draft.categoryIds.contains(category.id)
                    ) {
                        if draft.categoryIds.contains(category.id) {
                            draft.categoryIds.remove(category.id)
                        } else {
                            draft.categoryIds.insert(category.id)
                        }
                    }
                }
            }

            Section("状态") {
                // 在境与即将入境互斥（对齐 Android）：同时开启会筛出空集
                Toggle("仅当前在境", isOn: Binding(
                    get: { draft.onlyInPass },
                    set: { draft.onlyInPass = $0; if $0 { draft.onlyUpcoming = false } }
                ))
                Toggle("仅即将入境", isOn: Binding(
                    get: { draft.onlyUpcoming },
                    set: { draft.onlyUpcoming = $0; if $0 { draft.onlyInPass = false } }
                ))
                Toggle("仅有 AMSAT 状态报告", isOn: $draft.onlyAmsat)
            }

            Section {
                Button("应用筛选") {
                    store.filter = draft
                    dismiss()
                }
                Button("清除全部", role: .destructive) {
                    draft = SatelliteFilter()
                    store.filter = draft
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("筛选卫星")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { draft = store.filter }
    }

    private func toggleRow(title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                }
            }
        }
    }
}
