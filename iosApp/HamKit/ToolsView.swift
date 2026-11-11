import SwiftUI
import Shared

/// 「工具」Tab：功能入口列表，对齐 fork miuix 分支的卡片分组风格
/// （对应 Android Home 页的功能入口集合；Android 在首页直接铺卡片，
/// iOS 收进工具 Tab，入口一致）。
struct ToolsView: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        MiuixPage {
            // 卫星
            MiuixGroupCard {
                navRow(Route.satelliteManagement, "卫星管理", summary: "轨道目录 / 业余卫星 / 分类与筛选", systemImage: "scope")
                navRow(Route.reminderList, "过境提醒", summary: "本地通知提醒即将过境的卫星", systemImage: "bell")
                navRow(Route.locationDetail, "定位详情", summary: "定位状态与地面站坐标", systemImage: "location")
            }

            // 数字模式与图像
            MiuixGroupCard {
                navRow(Route.cwPractice, "CW 练习", summary: "自由练习 / 教程 / 摩斯电码编解码", systemImage: "dot.radiowaves.left.and.right")
                navRow(Route.aprsMain, "APRS", summary: "业余无线电位置报告", systemImage: "antenna.radiowaves.left.and.right")
                navRow(Route.ft8Main, "FT8", summary: "数字模式编解码与通联", systemImage: "waveform.path.ecg")
                navRow(Route.sstvMain(presetModeName: nil, autoStart: false), "SSTV", summary: "慢扫描电视接收与解码", systemImage: "photo.on.rectangle.angled")
                navRow(Route.hfFax, "HF 传真", summary: "短波传真图接收", systemImage: "printer")
            }

            // 日志
            MiuixGroupCard {
                navRow(Route.qsoLogList, "QSO 日志", summary: "通联记录与 ADIF 导出", systemImage: "book.closed")
            }
        }
        .navigationTitle("工具")
        .navigationBarTitleDisplayMode(.inline)
        .tint(MiuixTheme.primary)
        // 路由目标由 AppRootView 的 NavigationStack 统一注册；
        // 这里再注册一次会与根注册冲突（同一 stack 两个 for: Route.self）。
    }

    /// 值式导航行：包一层 NavigationLink(value:) 走统一 NavigationPath。
    private func navRow(_ route: Route, _ title: String, summary: String, systemImage: String) -> some View {
        NavigationLink(value: route) {
            MiuixRow(title: title, summary: summary, systemImage: systemImage, showsChevron: true)
        }
        .buttonStyle(.plain)
    }
}
