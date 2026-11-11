import SwiftUI

@main
struct HamKitApp: App {

    init() {
        // TabBar / NavBar / 原生 List 的外观对齐 miuix（surface 底 + primary 选中色）
        MiuixTheme.configureUIKitAppearance()
        // BGTaskScheduler 必须在启动完成前注册
        BackgroundReminderScheduler.shared.register()
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
        }
    }
}
