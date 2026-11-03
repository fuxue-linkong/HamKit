import Foundation
import Shared

/// TLE 落盘缓存：冷启动（甚至完全离线）时先用上次拉到的卫星列表，
/// 再后台刷新，避免每次打开 App 都要等网络。
///
/// 只缓存「三行 TLE + 获取时间」这类纯文本，不涉及任何用户隐私数据。
final class TleDiskCache {

    private struct CachedRecord: Codable {
        let name: String
        let norad: Int32
        let line1: String
        let line2: String
    }

    private struct File: Codable {
        let fetchedAtMs: Int64
        let records: [CachedRecord]
    }

    private let fileURL: URL

    /// - Parameter filename: 缓存文件名。全量目录（1.6 万+，约 3MB）与业余子集
    ///   （约 600 颗，约 100KB）分开存：首页「附近过境」只依赖业余子集，
    ///   冷启动时不必为了算过境去碰全量目录。
    init(filename: String = "tle-cache.json", fileManager: FileManager = .default) {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        try? fileManager.createDirectory(at: base, withIntermediateDirectories: true)
        fileURL = base.appendingPathComponent(filename)
    }

    func load() -> (records: [TleRecord], fetchedAtMs: Int64)? {
        guard let data = try? Data(contentsOf: fileURL),
              let file = try? JSONDecoder().decode(File.self, from: data),
              !file.records.isEmpty else {
            return nil
        }
        let records = file.records.map {
            TleRecord(name: $0.name, norad: $0.norad, line1: $0.line1, line2: $0.line2)
        }
        return (records, file.fetchedAtMs)
    }

    func save(records: [TleRecord], fetchedAtMs: Int64) {
        guard !records.isEmpty else { return }
        let file = File(
            fetchedAtMs: fetchedAtMs,
            records: records.map {
                CachedRecord(name: $0.name, norad: $0.norad, line1: $0.line1, line2: $0.line2)
            }
        )
        guard let data = try? JSONEncoder().encode(file) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
