import SwiftUI
import Shared

/// 「工具」Tab：功能入口列表，对应 Android Home 页的功能入口集合
/// （Android 在首页直接铺卡片，iOS 收进工具 Tab，入口一致）。
struct ToolsView: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        List {
            Section("卫星") {
                NavigationLink(value: Route.satelliteManagement) {
                    // `satellite` 这个 SF Symbol 在部分系统版本上取不到，会渲染成「没图标」
                    // （截图里那行文字直接顶到了图标位）。改用一定有、语义也贴的 scope。
                    Label("卫星管理", systemImage: "scope")
                }
                NavigationLink(value: Route.reminderList) {
                    Label("过境提醒", systemImage: "bell")
                }
                NavigationLink(value: Route.locationDetail) {
                    Label("定位详情", systemImage: "location")
                }
            }

            Section("数字模式与图像") {
                NavigationLink(value: Route.cwPractice) {
                    Label("CW 练习", systemImage: "dot.radiowaves.left.and.right")
                }
                NavigationLink(value: Route.aprsMain) {
                    Label("APRS", systemImage: "antenna.radiowaves.left.and.right")
                }
                NavigationLink(value: Route.ft8Main) {
                    Label("FT8", systemImage: "waveform.path.ecg")
                }
                NavigationLink(value: Route.sstvMain(presetModeName: nil, autoStart: false)) {
                    Label("SSTV", systemImage: "photo.on.rectangle.angled")
                }
                NavigationLink(value: Route.hfFax) {
                    Label("HF 传真", systemImage: "printer")
                }
            }

            Section("日志") {
                NavigationLink(value: Route.qsoLogList) {
                    Label("QSO 日志", systemImage: "book.closed")
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(MiuixTheme.background)
        .navigationTitle("工具")
        .navigationBarTitleDisplayMode(.inline)
        // 路由目标由 AppRootView 的 NavigationStack 统一注册；
        // 这里再注册一次会与根注册冲突（同一 stack 两个 for: Route.self）。
    }
}
