import Foundation
import Shared

/// 转发器（频率）落盘缓存。
///
/// SatNOGS 的全量转发器表有几 MB，且 `db.satnogs.org` 在国内经常超时/被污染。
/// Android 侧用 Room 缓存最近一次结果（`RadioInfoRepository`），iOS 之前没有落盘，
/// 于是「每次冷启动都要重拉几 MB，拉不到就彻底没有频率」。这里补上同样的能力：
/// 拉到一次就缓存，之后冷启动先用缓存，后台再刷新。
///
/// 只缓存公开的频率数据（NORAD / 名称 / 模式 / 上下行 Hz），不含任何用户信息。
final class TransmitterDiskCache {

    private struct Item: Codable {
        let norad: Int32
        let name: String
        let mode: String
        let uplinkHz: Int64
        let downlinkHz: Int64
        let inverted: Bool
        let status: String
    }

    private struct File: Codable {
        let fetchedAtMs: Int64
        let items: [Item]
    }

    private let fileURL: URL

    init(filename: String = "transmitters-cache.json", fileManager: FileManager = .default) {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        try? fileManager.createDirectory(at: base, withIntermediateDirectories: true)
        fileURL = base.appendingPathComponent(filename)
    }

    /// 读出上次缓存的转发器；没有缓存或文件损坏时返回 nil。
    func load() -> [Transmitter]? {
        guard let data = try? Data(contentsOf: fileURL),
              let file = try? JSONDecoder().decode(File.self, from: data),
              !file.items.isEmpty else {
            return nil
        }
        return file.items.map {
            Transmitter(
                norad: $0.norad,
                name: $0.name,
                mode: $0.mode,
                uplinkHz: $0.uplinkHz,
                downlinkHz: $0.downlinkHz,
                inverted: $0.inverted,
                status: $0.status
            )
        }
    }

    func save(_ transmitters: [Transmitter]) {
        guard !transmitters.isEmpty else { return }
        let file = File(
            fetchedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            items: transmitters.map {
                Item(
                    norad: Int32($0.norad),
                    name: $0.name,
                    mode: $0.mode,
                    uplinkHz: $0.uplinkHz,
                    downlinkHz: $0.downlinkHz,
                    inverted: $0.inverted,
                    status: $0.status
                )
            }
        )
        guard let data = try? JSONEncoder().encode(file) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
