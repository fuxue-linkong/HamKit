import Foundation
import BackgroundTasks
import UserNotifications
import UIKit

/// 后台过境提醒刷新：用 `BGAppRefreshTask` 替代 Android 的 WorkManager。
///
/// 限制：iOS 由系统决定何时运行（通常 ≥15 分钟、且用户使用习惯影响），
/// 不能保证像 WorkManager 那样准点；因此同时在 App 启动/回前台时重建通知。
final class BackgroundReminderScheduler {

    static let shared = BackgroundReminderScheduler()
    static let taskIdentifier = "click.hamkit.ios.refresh-reminders"

    struct Plan: Codable {
        let satelliteName: String
        let aosMs: Int64
        let norad: Int32
    }

    private let defaults = UserDefaults.standard
    private static let plansKey = "hamkit.reminder.plans"

    private init() {}

    /// 必须在 App 启动完成前调用（`HamKitApp.init`）。
    func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.taskIdentifier,
            using: nil
        ) { [weak self] task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self?.handle(refreshTask)
        }
    }

    /// 保存最近一次预测出的提醒计划，供后台任务使用。
    func updatePlans(_ plans: [Plan]) {
        guard let data = try? JSONEncoder().encode(plans) else { return }
        defaults.set(data, forKey: Self.plansKey)
    }

    private func loadPlans() -> [Plan] {
        guard let data = defaults.data(forKey: Self.plansKey),
              let plans = try? JSONDecoder().decode([Plan].self, from: data) else {
            return []
        }
        return plans
    }

    /// 请求系统在 15 分钟后尝试唤醒；每次处理完都重新排下一次。
    func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: Self.taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // 模拟器/未授权时提交会失败，忽略
        }
    }

    private func handle(_ task: BGAppRefreshTask) {
        schedule()
        task.expirationHandler = {}

        let settings = ReminderSettingsStore.shared
        guard settings.enabled else {
            task.setTaskCompleted(success: true)
            return
        }
        let plans = loadPlans()
        let center = UNUserNotificationCenter.current()
        for plan in plans {
            guard plan.aosMs > Int64(Date().timeIntervalSince1970 * 1000) else { continue }
            let content = UNMutableNotificationContent()
            content.title = "\(plan.satelliteName) 即将过境"
            content.body = "约 \(settings.leadMinutes) 分钟后 AOS，打开 HamKit 查看方位与仰角。"
            content.sound = .default
            let fireDate = Date(timeIntervalSince1970: Double(plan.aosMs) / 1000)
                .addingTimeInterval(-settings.leadSeconds)
            guard fireDate > Date() else { continue }
            let trigger = UNTimeIntervalNotificationTrigger(
                timeInterval: max(1, fireDate.timeIntervalSinceNow),
                repeats: false
            )
            center.add(UNNotificationRequest(
                identifier: NotificationManager.identifier(norad: plan.norad, aosMs: plan.aosMs),
                content: content,
                trigger: trigger
            ))
        }
        task.setTaskCompleted(success: true)
    }
}
