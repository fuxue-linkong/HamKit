import Foundation
import Shared

/// 把 App 的实时数据喂给共享内核的工具层（对齐 Android `AiAppFacadeImpl`）。
///
/// 内核里的工具是纯逻辑，所有「当前卫星列表 / 转发器 / 定位 / TLE」都从这里取，
/// 因此 iOS 与 Android 的模型输出字段、歧义提示、AOS 语义完全一致。
final class AiAppFacadeImpl: NSObject, AiAppFacade {

    static let shared = AiAppFacadeImpl()

    weak var model: TrackerModel?
    /// 「打开页面」的动作出口，由 AppRootView 注入（拿到导航栈才能真的跳）。
    var onNavigate: ((Route) -> Void)?

    /// 全量目录 1.6 万颗，逐个跨语言构造对象很贵；工具一次对话里可能问两三遍，
    /// 这里按 5 秒缓存（过境数据本身也是分钟级精度，不会因此失真）。
    private var cachedSatellites: [AiSatelliteInfo] = []
    private var cachedAt: Date = .distantPast

    func attach(model: TrackerModel) {
        self.model = model
        cachedAt = .distantPast
    }

    // ── 数据 ────────────────────────────────────────────────────────────────

    func satellites() -> [AiSatelliteInfo] {
        if Date().timeIntervalSince(cachedAt) < 5, !cachedSatellites.isEmpty {
            return cachedSatellites
        }
        let items = SatelliteStore.shared.items
        guard !items.isEmpty else { return [] }
        let mapped = items.map { item in
            let pass = item.pass
            // GEO / 高轨：PassFinder 对恒定可见的星给的是 24h 窗口
            let geo = (pass?.durationSeconds ?? 0) >= 21_000
            return AiSatelliteInfo(
                norad: item.record.norad,
                name: item.record.name,
                modesCsv: item.modes.joined(separator: ","),
                visible: item.isVisible,
                hasPass: pass != nil,
                aosMs: pass?.aosMs ?? 0,
                losMs: pass?.losMs ?? 0,
                maxElevationDeg: pass?.maxElevationDeg ?? 0,
                aosAzimuthDeg: pass?.aosAzimuthDeg ?? 0,
                losAzimuthDeg: pass?.losAzimuthDeg ?? 0,
                isGeo: geo,
                status: item.amsatStatus ?? ""
            )
        }
        cachedSatellites = mapped
        cachedAt = Date()
        return mapped
    }

    func radios(norad: Int32) -> [AiRadioInfo] {
        let radios = model?.transmittersByNorad[norad] ?? []
        return radios.map { radio in
            AiRadioInfo(
                name: radio.name,
                uplinkHz: radio.uplinkHz,
                downlinkHz: radio.downlinkHz,
                mode: radio.mode,
                status: radio.status
            )
        }
    }

    func amsatStatus(norad: Int32) -> String? {
        model?.amsatStatus(norad: norad)
    }

    func location() -> AiLocationInfo? {
        guard let model = model else { return nil }
        return AiLocationInfo(
            latitude: model.latitude,
            longitude: model.longitude,
            altitudeM: model.altitudeM
        )
    }

    func tle(norad: Int32) -> AiTleInfo? {
        guard let record = model?.satellites.first(where: { $0.norad == norad }) else { return nil }
        return AiTleInfo(name: record.name, line1: record.line1, line2: record.line2)
    }

    func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    // ── 动作 ────────────────────────────────────────────────────────────────

    func performAction(action: String, norad: Int32, screen: String) -> String {
        switch action {
        case "open_screen":
            guard let route = Self.route(for: screen) else {
                return "未知页面：\(screen)"
            }
            onNavigate?(route)
            return "已打开「\(Self.screenTitle(screen))」"
        case "add_pass_reminder":
            guard let model = model else { return "数据尚未就绪，无法设置提醒" }
            SatelliteCategoryManager.shared.toggleReminder(norad: norad)
            let enabled = SatelliteCategoryManager.shared.snapshot.reminderFlags.contains(norad)
            model.applyReminderSettings()
            if enabled {
                NotificationManager.shared.requestAuthorizationIfNeeded { granted in
                    // 权限被拒时提醒不会真的弹出，这里只做一次提示（不阻塞工具返回）
                    if !granted {
                        DispatchQueue.main.async {
                            AiChatModel.shared.permissionWarning = "通知权限被拒绝，提醒不会弹出"
                        }
                    }
                }
                return "已为这颗卫星开启过境提醒（AOS 前 \(ReminderSettingsStore.shared.leadMinutes) 分钟）"
            }
            return "已取消这颗卫星的过境提醒"
        default:
            return "不支持的动作：\(action)"
        }
    }

    private static func route(for screen: String) -> Route? {
        switch screen {
        case "sstv_receive": return .sstvMain(presetModeName: nil, autoStart: true)
        case "sstv_gallery": return .sstvGallery(pickForTx: false)
        case "cw": return .cwPractice
        case "ft8": return .ft8Main
        case "aprs": return .aprsMain
        case "qso_log": return .qsoLogList
        case "satellite_list": return .satelliteManagement
        case "reminder_list": return .reminderList
        case "ai_settings": return .aiSettings
        default: return nil
        }
    }

    private static func screenTitle(_ screen: String) -> String {
        switch screen {
        case "sstv_receive": return "SSTV 接收"
        case "sstv_gallery": return "SSTV 图库"
        case "cw": return "CW 练习"
        case "ft8": return "FT8"
        case "aprs": return "APRS"
        case "qso_log": return "QSO 日志"
        case "satellite_list": return "卫星管理"
        case "reminder_list": return "过境提醒"
        case "ai_settings": return "AI 设置"
        default: return screen
        }
    }
}
