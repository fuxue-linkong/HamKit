import Foundation
import Combine
import Shared

/// 卫星分类状态管理（iOS 侧）。
///
/// 逻辑层（增删改/归属/提醒开关）全部在共享内核 `HamKitApi`，配置以 JSON 字符串
/// 跨越 Swift/Kotlin 边界（避免 Kotlin 集合类型导出到 Swift 的兼容问题）。
/// 持久化用 UserDefaults，schema 与 Android `SatelliteCategoryStore` 同构。
///
/// 容错约定（对齐 Android Store）：解析失败退化为空配置；悬空归属引用由内核清理；
/// 历史 `hamkit.favorites` 首次运行迁移进内置「我的关注」。
final class SatelliteCategoryManager: ObservableObject {

    static let shared = SatelliteCategoryManager()

    /// 解析后的轻量快照（Swift 直接消费，不穿透 Kotlin 类型）。
    struct Snapshot {
        struct Category: Identifiable {
            let id: String
            let name: String
            let sortOrder: Int
            let isBuiltin: Bool
        }
        let categories: [Category]
        /// NORAD → 分类 id 集合
        let membership: [Int32: Set<String>]
        let reminderFlags: Set<Int32>

        static let empty = Snapshot(categories: [], membership: [:], reminderFlags: [])

        func categoryIds(of norad: Int32) -> Set<String> {
            membership[norad] ?? []
        }

        func satelliteCount(of categoryId: String) -> Int {
            membership.values.count { $0.contains(categoryId) }
        }

        func category(byId id: String) -> Category? {
            categories.first { $0.id == id }
        }

        var sortedCategories: [Category] {
            categories.sorted { $0.sortOrder < $1.sortOrder }
        }
    }

    @Published private(set) var snapshot: Snapshot

    private let defaults: UserDefaults
    private let lock = NSLock()
    private var configJson: String

    private enum Keys {
        static let config = "hamkit.satellite.category_config_json"
        static let migrated = "hamkit.satellite.legacy_migrated_v1"
        static let legacyFavorites = "hamkit.favorites"
    }

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        // 历史收藏迁移（TrackerModel 读同一个 favorites key，行为与 Android 一致）
        var json = defaults.string(forKey: Keys.config) ?? ""
        if !defaults.bool(forKey: Keys.migrated) {
            let legacy = Set(defaults.array(forKey: Keys.legacyFavorites) as? [Int] ?? [])
            if !legacy.isEmpty {
                // 逐颗开提醒（内核 toggleReminder 每次一颗）
                for norad in legacy.sorted() where norad > 0 {
                    json = HamKitApi.shared.categoryToggleReminder(configJson: json, catalogNumber: Int32(norad))
                }
                let starredId = "builtin:starred"
                json = HamKitApi.shared.categoryAdd(configJson: json, name: "我的关注", id: starredId)
                for norad in legacy.sorted() where norad > 0 {
                    json = HamKitApi.shared.categoryToggleAssignment(configJson: json, catalogNumber: Int32(norad), categoryId: starredId)
                }
                defaults.set(json, forKey: Keys.config)
            }
            defaults.set(true, forKey: Keys.migrated)
        }
        configJson = json
        snapshot = Self.decode(json)
    }

    // MARK: - 操作（全部走内核，锁内读-改-写）

    private func mutate(_ transform: (String) -> String) {
        lock.lock()
        defer { lock.unlock() }
        let updated = transform(configJson)
        guard updated != configJson else { return }
        configJson = updated
        defaults.set(updated, forKey: Keys.config)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.snapshot = Self.decode(self.configJson)
        }
    }

    func addCategory(name: String) {
        mutate { HamKitApi.shared.categoryAdd(configJson: $0, name: name, id: "") }
    }

    func renameCategory(id: String, newName: String) {
        mutate { HamKitApi.shared.categoryRename(configJson: $0, categoryId: id, newName: newName) }
    }

    func removeCategory(id: String) {
        mutate { HamKitApi.shared.categoryRemove(configJson: $0, categoryId: id) }
    }

    func toggleAssignment(norad: Int32, categoryId: String) {
        mutate { HamKitApi.shared.categoryToggleAssignment(configJson: $0, catalogNumber: norad, categoryId: categoryId) }
    }

    func toggleReminder(norad: Int32) {
        mutate { HamKitApi.shared.categoryToggleReminder(configJson: $0, catalogNumber: norad) }
    }

    // MARK: - JSON 解析（内核产出的 schema 固定，直接手工解析）

    private static func decode(_ json: String) -> Snapshot {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return .empty
        }
        let categories: [Snapshot.Category] = ((root["categories"] as? [[String: Any]]) ?? []).compactMap { obj in
            guard let id = obj["id"] as? String, !id.isEmpty else { return nil }
            return Snapshot.Category(
                id: id,
                name: obj["name"] as? String ?? "",
                sortOrder: obj["sortOrder"] as? Int ?? 0,
                isBuiltin: id.hasPrefix("builtin:")
            )
        }
        var membership: [Int32: Set<String>] = [:]
        for (key, value) in (root["membership"] as? [String: [String]]) ?? [:] {
            guard let norad = Int32(key), norad > 0 else { continue }
            membership[norad] = Set(value)
        }
        let flags = Set(((root["reminderFlags"] as? [Int]) ?? []).map { Int32($0) }.filter { $0 > 0 })
        return Snapshot(categories: categories, membership: membership, reminderFlags: flags)
    }
}
