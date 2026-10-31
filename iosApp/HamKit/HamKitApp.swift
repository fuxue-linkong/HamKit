import SwiftUI

@main
struct HamKitApp: App {

    init() {
        // BGTaskScheduler 必须在启动完成前注册
        BackgroundReminderScheduler.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
        }
    }
}
