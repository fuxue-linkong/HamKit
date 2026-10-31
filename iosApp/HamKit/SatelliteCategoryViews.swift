import SwiftUI
import Shared

/// 卫星分类管理页：对应 Android `SatelliteCategoryManagerDialogMiuix`。
/// 新建 / 重命名 / 删除分类；内置「我的关注」不可删除；菜单里可按分类筛选卫星。
struct SatelliteCategoryManagerView: View {

    @ObservedObject var manager = SatelliteCategoryManager.shared
    @ObservedObject var model: TrackerModel
    /// 筛选条件只有一份真相：直接读写 SatelliteStore.filter。
    /// 之前这里用 `.constant([])` 传入 Binding，菜单里「按此分类筛选」写进去是空操作，
    /// 分类筛选看起来完全没反应——这是「分类乱套」的直接原因之一。
    @ObservedObject private var store = SatelliteStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var newCategoryName = ""
    @State private var editingId: String?
    @State private var editingName = ""
    @State private var pendingDelete: SatelliteCategoryManager.Snapshot.Category?

    private var selectedCategoryIds: Set<String> { store.filter.categoryIds }

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("新分类名称", text: $newCategoryName)
                        .onSubmit(createCategory)
                    Button("新建", action: createCategory)
                        .disabled(newCategoryName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } footer: {
                if newCategoryName.trimmingCharacters(in: .whitespaces).isEmpty && !newCategoryName.isEmpty {
                    Text("名称不能为空")
                }
            }

            Section("分类（点行筛选）") {
                if manager.snapshot.sortedCategories.isEmpty {
                    Text("还没有分类；新建一个把卫星归类，方便浏览与筛选。")
                        .foregroundStyle(.secondary)
                }
                ForEach(manager.snapshot.sortedCategories) { category in
                    categoryRow(category)
                }
            }

            Section("提醒开关") {
                Text("过境提醒是独立的卫星级开关，在「卫星归类」页里逐颗设置；把卫星加入分类不会创建或删除提醒。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("分类管理")
        .navigationBarTitleDisplayMode(.inline)
        .alert("删除分类", isPresented: Binding(
            get: { pendingDelete != nil },
            set: { if !$0 { pendingDelete = nil } }
        )) {
            Button("删除", role: .destructive) {
                if let target = pendingDelete {
                    manager.removeCategory(id: target.id)
                    // 同步清掉可能残留的筛选条件，否则列表会被一个已不存在的分类筛成空
                    store.filter.categoryIds.remove(target.id)
                }
                pendingDelete = nil
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            if let target = pendingDelete {
                Text("删除「\(target.name)」？该分类下 \(manager.snapshot.satelliteCount(of: target.id)) 颗卫星将失去归属（不影响它们的提醒开关）。")
            }
        }
    }

    @ViewBuilder
    private func categoryRow(_ category: SatelliteCategoryManager.Snapshot.Category) -> some View {
        if editingId == category.id {
            HStack {
                TextField("分类名称", text: $editingName)
                    .onSubmit(commitRename)
                Button("保存", action: commitRename)
                Button("取消") { editingId = nil }
            }
        } else {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(category.name)
                        if category.isBuiltin {
                            Text("内置")
                                .font(.caption2)
                                .padding(.horizontal, 5)
                                .background(Color.accentColor.opacity(0.12))
                                .clipShape(Capsule())
                        }
                    }
                    Text("\(manager.snapshot.satelliteCount(of: category.id)) 颗卫星")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if selectedCategoryIds.contains(category.id) {
                    Image(systemName: "line.3.horizontal.decrease.circle.fill")
                        .foregroundStyle(Color.accentColor)
                }
                Menu {
                    Button {
                        store.filter.categoryIds = [category.id]
                        dismiss()
                    } label: {
                        Label("按此分类筛选", systemImage: "line.3.horizontal.decrease.circle")
                    }
                    Button {
                        store.filter.categoryIds.removeAll()
                    } label: {
                        Label("取消分类筛选", systemImage: "xmark.circle")
                    }
                    .disabled(selectedCategoryIds.isEmpty)
                    Button {
                        editingId = category.id
                        editingName = category.name
                    } label: {
                        Label("重命名", systemImage: "pencil")
                    }
                    Button(role: .destructive) {
                        pendingDelete = category
                    } label: {
                        Label("删除", systemImage: "trash")
                    }
                    .disabled(category.isBuiltin)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
    }

    private func createCategory() {
        let name = newCategoryName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        manager.addCategory(name: name)
        newCategoryName = ""
    }

    private func commitRename() {
        guard let id = editingId else { return }
        manager.renameCategory(id: id, newName: editingName)
        editingId = nil
    }
}

/// 卫星归类页：对应 Android `SatelliteCategoryPickerDialogMiuix`。
/// 多选归属分类 + 该卫星的过境提醒开关。
struct SatelliteAssignmentView: View {

    @ObservedObject var manager = SatelliteCategoryManager.shared
    @ObservedObject var model: TrackerModel
    let norad: Int32

    private var record: TleRecord? {
        model.satellites.first { $0.norad == norad }
    }

    private var assignedIds: Set<String> {
        manager.snapshot.categoryIds(of: norad)
    }

    var body: some View {
        List {
            Section {
                LabeledContent("卫星", value: record?.name ?? "NORAD \(norad)")
                LabeledContent("NORAD", value: "\(norad)")
            }

            Section("归属分类") {
                if manager.snapshot.sortedCategories.isEmpty {
                    Text("还没有分类；先到「分类管理」新建。")
                        .foregroundStyle(.secondary)
                }
                ForEach(manager.snapshot.sortedCategories) { category in
                    Button {
                        manager.toggleAssignment(norad: norad, categoryId: category.id)
                    } label: {
                        HStack {
                            Text(category.name).foregroundStyle(.primary)
                            Spacer()
                            if assignedIds.contains(category.id) {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            }

            Section {
                Toggle(
                    "过境提醒（AOS 前 5 分钟）",
                    isOn: Binding(
                        get: { manager.snapshot.reminderFlags.contains(norad) },
                        set: { _ in manager.toggleReminder(norad: norad) }
                    )
                )
            } footer: {
                Text("提醒与分类归属互相独立：开启后这颗卫星每次过境前都会收到本地通知。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("卫星归类")
        .navigationBarTitleDisplayMode(.inline)
    }
}
