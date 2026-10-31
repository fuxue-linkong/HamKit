import SwiftUI
import Combine
import CoreLocation
import Foundation
import Shared

/// iOS 端首页：照抄 Android `HomeMiuix.kt` 的卡片结构 ——
/// 时间卡（本地时间大字 + UTC + 每日一言）→ 附近过境卫星卡（top5 + 管理）→
/// 定位详情 / CW / APRS / FT8 / SSTV / QSO 入口卡。
///
/// 与 Android 的差异：
/// - 天气走 iOS 侧 WeatherStore（高德 Web 服务），嵌在时间卡里；
/// - 雷达图移到「卫星详情」子页（Android 在 SatelliteDetail 页展示）。
struct ContentView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
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
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
        }
        .background(MiuixTheme.background)
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

/// 时间 + 日期 + UTC + 每日一言，对应 Android `HomeHeaderMiuix` 的时间卡。
/// 时钟每秒刷新只在本组件内部持有，避免整个首页跟着每秒重建。
private struct HomeTimeCard: View {

    @ObservedObject private var weather = WeatherStore.shared
    @ObservedObject private var quote = DailyQuoteStore.shared
    @State private var now = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(Self.timeFormatter.string(from: now))
                    .font(.system(size: 34, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Spacer()
                Text(Self.dateFormatter.string(from: now))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            // UTC 单独给个底色：HAM 习惯按 UTC 记时间，扫一眼就要能定位
            HStack(spacing: 5) {
                Image(systemName: "globe")
                    .font(.caption2)
                Text(Self.utcFormatter.string(from: now))
                    .font(.footnote.monospacedDigit())
                Text("UTC")
                    .font(.caption2.weight(.semibold))
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .foregroundStyle(Color.accentColor)
            .background(Color.accentColor.opacity(0.14))
            .clipShape(Capsule())

            Text(weather.summary)
                .font(.caption)
                .foregroundStyle(.secondary)

            Text(quote.displayText)
                .font(.caption2)
                .foregroundStyle(.tertiary)
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
        f.dateFormat = "EEE\nyyyy-MM-dd"
        f.dateFormat = "EEE  yyyy-MM-dd"
        return f
    }()
}

// MARK: - 附近过境卫星卡

/// 附近过境卫星 top5 + 「管理」入口，对应 Android `SatelliteListCardMiuix`。
private struct NearbyPassesCard: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("附近过境卫星")
                    .font(.headline)
                Spacer()
                NavigationLink(value: Route.satelliteManagement) {
                    Text("管理")
                        .font(.subheadline)
                }
            }

            if model.isSearchingNearby && model.nearbyPasses.isEmpty {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在计算过境…").foregroundStyle(.secondary)
                }
            } else if model.nearbyPasses.isEmpty {
                Text("48 小时内暂无过境").foregroundStyle(.secondary)
            } else {
                ForEach(Array(model.nearbyPasses.prefix(5).enumerated()), id: \.element.norad) { _, item in
                    NavigationLink(value: Route.satelliteDetail(norad: Int32(item.norad))) {
                        HStack(alignment: .firstTextBaseline) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                    .font(.subheadline.weight(.medium))
                                Text(item.pass.isCurrentlyVisible ? "在境" : "即将过境")
                                    .font(.caption)
                                    .foregroundStyle(item.pass.isCurrentlyVisible ? Color.green : Color.secondary)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text("AOS " + Self.timeFormatter.string(from: item.pass.aosMs.date))
                                    .font(.caption)
                                    .monospacedDigit()
                                Text("最大仰角 \(String(format: "%.0f", item.pass.maxElevationDeg))°")
                                    .font(.caption)
                                    .monospacedDigit()
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            NavigationLink(value: Route.satelliteDetail(norad: model.selectedNorad ?? 0)) {
                Text("查看雷达与详情")
                    .font(.subheadline)
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

/// 功能入口卡，对应 Android `BasicComponent` 风格：标题 + 摘要 + 右箭头。
struct EntryCard: View {

    let title: String
    let summary: String
    let systemImage: String
    /// 统一用值式导航：与页面内「设置」等 Route 链接走同一套 NavigationPath，
    /// 避免视图式 + 值式混用时新页面被压到栈底（点了没反应、返回才出现）。
    let route: Route

    var body: some View {
        NavigationLink(value: route) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .frame(width: 28)
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body)
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
            }
            .miuixCard(padding: 14, radius: 14)
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
