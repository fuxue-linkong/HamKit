import Foundation
import UserNotifications
import Shared

/// 过境提醒：AOS 前 N 分钟发一条本地通知。
///
/// 用本地通知（`UNUserNotificationCenter`）而不是远程推送：
/// 过境时间完全可以在本地算出来，不需要服务器，也不需要推送证书。
final class NotificationManager {

    static let shared = NotificationManager()

    private let center = UNUserNotificationCenter.current()

    /// 通知 ID：`pass-<NORAD>-<aosMs>`。
    /// 之前只有 `pass-<aosMs>`，两颗卫星同时刻过境时后一条会覆盖前一条。
    static func identifier(norad: Int32, aosMs: Int64) -> String {
        "pass-\(norad)-\(aosMs)"
    }

    /// 只在用户第一次用到提醒时申请权限；结果通过回调回传，
    /// 排程方据此提示「已设提醒」还是「通知权限被拒绝」。
    func requestAuthorizationIfNeeded(completion: ((Bool) -> Void)? = nil) {
        center.getNotificationSettings { [weak self] settings in
            guard let self else { return }
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                completion?(true)
            case .denied:
                completion?(false)
            case .notDetermined:
                self.center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                    completion?(granted)
                }
            @unknown default:
                completion?(false)
            }
        }
    }

    /// 为一次过境安排提醒；AOS 已过（或不足提前量）时忽略。
    @discardableResult
    func schedulePassReminder(
        satelliteName: String,
        norad: Int32,
        aosMs: Int64,
        leadSeconds: TimeInterval = 300
    ) -> Bool {
        let aosDate = Date(timeIntervalSince1970: Double(aosMs) / 1000.0)
        let fireDate = aosDate.addingTimeInterval(-leadSeconds)
        guard fireDate > Date() else { return false }

        let content = UNMutableNotificationContent()
        content.title = "\(satelliteName) 即将过境"
        content.body = "约 \(Int(leadSeconds / 60)) 分钟后 AOS，打开 HamKit 查看方位与仰角。"
        content.sound = .default

        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: max(1, fireDate.timeIntervalSinceNow),
            repeats: false
        )
        let request = UNNotificationRequest(
            identifier: Self.identifier(norad: norad, aosMs: aosMs),
            content: content,
            trigger: trigger
        )
        center.add(request)
        return true
    }

    /// 清掉所有过境提醒（关闭提醒、改提前量、重新排期前都会先调它）。
    /// `completion` 在删除真正落盘后回调——重建流程必须等它再排新通知，
    /// 否则异步删除会把刚排上的新提醒一并删掉。
    func cancelPassReminders(completion: (() -> Void)? = nil) {
        center.getPendingNotificationRequests { [weak self] requests in
            let identifiers = requests.map(\.identifier).filter { $0.hasPrefix("pass-") }
            if identifiers.isEmpty {
                completion?()
                return
            }
            self?.center.removePendingNotificationRequests(withIdentifiers: identifiers)
            // removePendingNotificationRequests 没有完成回调；给系统一点处理时间，
            // 重建流程等 completion 再排新通知，避免删除和排程交叠。
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                completion?()
            }
        }
    }
}
