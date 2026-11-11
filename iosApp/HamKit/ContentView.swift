import SwiftUI
import Combine
import CoreLocation
import Foundation
import Shared

/// iOS 端首页：对齐 fork miuix 分支 `HomeMiuix.kt` 的卡片结构 ——
/// 时间卡（本地时间大字 + UTC + 天气 + 每日一言）→ 附近过境卫星卡（top5 + 管理）→
/// 定位详情 / CW / APRS / FT8 / SSTV / QSO 入口卡。
///
/// 与 Android 的差异：
/// - 天气走 iOS 侧 WeatherStore（高德 Web 服务），嵌在时间卡里；
/// - 雷达图移到「卫星详情」子页（Android 在 SatelliteDetail 页展示）。
struct ContentView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider

    var body: some View {
        MiuixPage {
            HomeTimeCard()

            NearbyPassesCard(model: model)

            EntryCard(
                title: "AI 助手",
                summary: "卫星过境 / 频率 / 分区，直接问；可调用工具查真实数据",
                systemImage: "sparkles",
                route: .aiAssistant
            )

            EntryCard(
                title: "定位详情",
                summary: "定位状态与地面站坐标",
                systemImage: "location.fill",
                route: .locationDetail
            )

            EntryCard(title: "CW 练习", summary: "自由练习 / 教程 / 摩斯电码编解码", systemImage: "dot.radiowaves.left.and.right", route: .cwPractice)
            // 原来的 dot.circle.and.cursorlines.rectangle 不是合法 SF Symbol，图标位是空的
            EntryCard(title: "APRS", summary: "业余无线电位置报告", systemImage: "antenna.radiowaves.left.and.right", route: .aprsMain)
            EntryCard(title: "FT8", summary: "数字模式编解码与通联", systemImage: "waveform.path.ecg", route: .ft8Main)
            EntryCard(title: "SSTV", summary: "慢扫描电视接收与解码", systemImage: "photo.on.rectangle.angled", route: .sstvMain(presetModeName: nil, autoStart: false))
            EntryCard(title: "QSO 日志", summary: "通联记录与 ADIF 导出", systemImage: "book.closed", route: .qsoLogList)
        }
        .navigationTitle("HamKit")
        .onAppear {
            WeatherStore.shared.refresh(latitude: model.latitude, longitude: model.longitude)
        }
        .onReceive(model.$latitude.dropFirst()) { _ in
            WeatherStore.shared.refresh(latitude: model.latitude, longitude: model.longitude)
        }
    }
}

// MARK: - 时间卡

/// 时间 + 日期 + UTC + 每日一言，对齐 Android `HomeHeaderMiuix` 的时间卡：
/// 本地时间大字（有天气 → primary，无 → outline），UTC 为次要文字行（无胶囊）。
/// 时钟每秒刷新只在本组件内部持有，避免整个首页跟着每秒重建。
private struct HomeTimeCard: View {

    @ObservedObject private var weather = WeatherStore.shared
    @ObservedObject private var quote = DailyQuoteStore.shared
    @State private var now = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    /// 对齐 fork：时间色跟天气状态联动（拿到天气 → primary；还没有 → outline）。
    private var timeColor: Color {
        weather.now != nil ? MiuixTheme.primary : MiuixTheme.outline
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(Self.timeFormatter.string(from: now))
                    .font(.system(size: 34, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(timeColor)
                Spacer()
                Text(Self.dateFormatter.string(from: now))
                    .font(.system(size: 13))
                    .foregroundStyle(MiuixTheme.textSecondary)
            }

            // UTC 单独一行次要文字：HAM 习惯按 UTC 记时间，扫一眼就要能定位
            Text(Self.utcFormatter.string(from: now) + " UTC")
                .font(.system(size: 14).monospacedDigit())
                .foregroundStyle(MiuixTheme.textSecondary)

            Text(weather.summary)
                .font(.system(size: 14))
                .foregroundStyle(MiuixTheme.textSecondary)

            Text(quote.displayText)
                .font(.system(size: 12))
                .foregroundStyle(MiuixTheme.textTertiary)
                .lineLimit(2)
                .padding(.top, 2)
        }
        .miuixCard()
        .onReceive(timer) { now = $0 }
        .onAppear { quote.refreshIfNeeded() }
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()
    private static let utcFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()
    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEE  yyyy-MM-dd"
        return f
    }()
}

// MARK: - 附近过境卫星卡

/// 附近过境卫星 top5 + 「管理」入口，对齐 Android `SatelliteListCardMiuix`：
/// 标题 17 SemiBold + TextButton「管理」（primary 色），行内名称 14 Medium、
/// 状态/AOS/最大仰角 12 次要色，「在境」用 SafeColors.successIcon。
private struct NearbyPassesCard: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("附近过境卫星")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(MiuixTheme.text)
                Spacer()
                NavigationLink(value: Route.satelliteManagement) {
                    Text("管理")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(MiuixTheme.primary)
                }
            }

            if model.isSearchingNearby && model.nearbyPasses.isEmpty {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在计算过境…")
                        .font(.system(size: 14))
                        .foregroundStyle(MiuixTheme.textSecondary)
                }
            } else if model.nearbyPasses.isEmpty {
                Text("48 小时内暂无过境")
                    .font(.system(size: 14))
                    .foregroundStyle(MiuixTheme.textSecondary)
            } else {
                ForEach(Array(model.nearbyPasses.prefix(5).enumerated()), id: \.element.norad) { _, item in
                    NavigationLink(value: Route.satelliteDetail(norad: Int32(item.norad))) {
                        HStack(alignment: .firstTextBaseline) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundStyle(MiuixTheme.text)
                                Text(item.pass.isCurrentlyVisible ? "在境" : "即将过境")
                                    .font(.system(size: 12))
                                    .foregroundStyle(item.pass.isCurrentlyVisible ? MiuixTheme.successIcon : MiuixTheme.textSecondary)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text("AOS " + Self.timeFormatter.string(from: item.pass.aosMs.date))
                                    .font(.system(size: 12))
                                    .monospacedDigit()
                                    .foregroundStyle(MiuixTheme.textSecondary)
                                Text("最大仰角 \(String(format: "%.0f", item.pass.maxElevationDeg))°")
                                    .font(.system(size: 12))
                                    .monospacedDigit()
                                    .foregroundStyle(MiuixTheme.textSecondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }

            NavigationLink(value: Route.satelliteDetail(norad: model.selectedNorad ?? 0)) {
                Text("查看雷达与详情")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(MiuixTheme.primary)
            }
        }
        .miuixCard()
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f
    }()
}

// MARK: - 通用入口卡

/// 功能入口卡：对齐 miuix `ArrowPreference` 规格——
/// startAction 图标 24pt（onBackground 色，对齐 fork 各页 startAction 的 tint）、
/// 标题 17 Medium、摘要 14 次要色、右侧 40% 正色箭头。
struct EntryCard: View {

    let title: String
    let summary: String
    let systemImage: String
    /// 统一用值式导航：与页面内「设置」等 Route 链接走同一套 NavigationPath，
    /// 避免视图式 + 值式混用时新页面被压到栈底（点了没反应、返回才出现）。
    let route: Route

    var body: some View {
        NavigationLink(value: route) {
            MiuixRow(title: title, summary: summary, systemImage: systemImage, showsChevron: true)
                .background(MiuixTheme.card)
                .clipShape(RoundedRectangle(cornerRadius: MiuixTheme.radius, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private extension Int64 {
    var date: Date { Date(timeIntervalSince1970: Double(self) / 1000.0) }
}

#Preview {
    NavigationStack {
        ContentView(model: TrackerModel(), location: LocationProvider())
    }
}
