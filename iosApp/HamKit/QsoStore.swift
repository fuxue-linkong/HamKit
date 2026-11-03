import Foundation
import Combine
import Shared

/// 单条 QSO 记录（Codable 持久化用；字段对齐共享内核 `QsoRecord`）。
struct QsoEntry: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var callsign: String
    var grid: String
    var reportSent: String
    var reportReceived: String
    var bandName: String
    var freqHz: Int64
    var mode: String
    var qsoTime: Date
    var isComplete: Bool
    var operatorCallsign: String
    var myGrid: String
    var comment: String

    func toShared() -> QsoRecord {
        QsoRecord(
            callsign: callsign,
            grid: grid.isEmpty ? nil : grid,
            reportSent: reportSent.isEmpty ? "-99" : reportSent,
            reportReceived: reportReceived.isEmpty ? "-99" : reportReceived,
            bandName: bandName,
            freqHz: freqHz,
            mode: mode,
            qsoTime: Int64(qsoTime.timeIntervalSince1970 * 1000),
            isComplete: isComplete,
            operator: operatorCallsign.isEmpty ? nil : operatorCallsign,
            myGrid: myGrid.isEmpty ? nil : myGrid,
            comment: comment.isEmpty ? nil : comment
        )
    }
}

/// QSO 日志存储：JSON 文件持久化（Android 用 Room；iOS 用 Documents/QSO.json）。
final class QsoStore: ObservableObject {

    static let shared = QsoStore()

    @Published private(set) var entries: [QsoEntry] = []

    private let fileURL: URL

    private init() {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        fileURL = dir.appendingPathComponent("QSO.json")
        load()
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([QsoEntry].self, from: data) else {
            return
        }
        entries = decoded.sorted { $0.qsoTime > $1.qsoTime }
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(entries) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    func upsert(_ entry: QsoEntry) {
        if let index = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[index] = entry
        } else {
            entries.append(entry)
        }
        entries.sort { $0.qsoTime > $1.qsoTime }
        save()
    }

    func delete(at offsets: IndexSet) {
        entries.remove(atOffsets: offsets)
        save()
    }

    func delete(id: UUID) {
        entries.removeAll { $0.id == id }
        save()
    }

    /// 导出到临时文件；失败返回 nil。
    func export(format: String) -> URL? {
        let text = QsoExporter.shared.export(
            records: entries.map { $0.toShared() },
            format: format
        )
        let name = QsoExporter.shared.suggestFilename(
            format: format,
            nowMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }
}
