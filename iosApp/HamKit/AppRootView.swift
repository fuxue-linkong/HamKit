import SwiftUI
import Shared

/// iOS 端根视图：底部 Tab（首页 / 工具 / 设置），对应 Android 的
/// 「Home + 功能子页面 + 设置」结构。
///
/// 全局只持有一份 `TrackerModel` 与 `LocationProvider`，三个 Tab 共享，
/// 避免 TLE 重复拉取、定位重复请求（Android 端由 MainViewModel 承担同样职责）。
struct AppRootView: View {

    @StateObject private var model = TrackerModel()
    @StateObject private var location = LocationProvider()
    @ObservedObject private var theme = ThemeStore.shared
    /// 隐私同意状态：未同意时不请求定位（对齐 Android 首启流程：同意前不申请权限）。
    @StateObject private var consent = PrivacyConsentStore.shared
    @Environment(\.scenePhase) private var scenePhase
    /// 首页的导航栈。平时由 `NavigationLink(value:)` 驱动；
    /// CI 冒烟场景（`-smokeManage` / `-smokeDetail`）需要代码里主动入栈，
    /// 所以把它提成可写的 path。
    @State private var homePath = NavigationPath()

    var body: some View {
        TabView {
            NavigationStack(path: $homePath) {
                ContentView(model: model, location: location)
                    .navigationDestination(for: Route.self) { route in
                        RouteDestination(route: route, model: model, location: location)
                    }
            }
            .tabItem {
                Label("首页", systemImage: "house.fill")
            }

            NavigationStack {
                ToolsView(model: model)
                    .navigationDestination(for: Route.self) { route in
                        RouteDestination(route: route, model: model, location: location)
                    }
            }
            .tabItem {
                Label("工具", systemImage: "wrench.and.screwdriver.fill")
            }

            NavigationStack {
                SettingsView(model: model)
                    .navigationDestination(for: Route.self) { route in
                        RouteDestination(route: route, model: model, location: location)
                    }
            }
            .tabItem {
                Label("设置", systemImage: "gearshape.fill")
            }
        }
        // 主题：强调色 + 深浅模式（设置里改完立即生效）
        .tint(theme.accentColor)
        .preferredColorScheme(theme.appearance.colorScheme)
        // 首启隐私同意：盖住整个 Tab（Android 是独立 Activity，iOS 用全屏覆盖）
        .overlay {
            if !consent.accepted {
                PrivacyConsentView { aprsPublic in
                    consent.accept(aprsPublic: aprsPublic)
                    // 同意之后才请求定位：与 Android「同意前不初始化高德、不申请权限」一致
                    location.requestLocation()
                }
                .transition(.opacity)
            }
        }
        .onAppear {
            // 数据层必须在启动时就接上：之前 SatelliteStore.configure 只在
            // 「卫星管理」页的 init 里调用，导致筛选页 / 分类页 / 详情页在首次
            // 进入管理页之前全是空数据——表现就是「必须退出再进一次才看得到」。
            SatelliteStore.shared.configure(model: model)
            // AI 工具层需要同一份卫星 / 定位数据；「打开页面」走首页导航栈
            AiAppFacadeImpl.shared.attach(model: model)
            AiAppFacadeImpl.shared.onNavigate = { [self] route in
                homePath.append(route)
            }
            model.start()
            // 只有已经同意过隐私政策的用户才主动要定位；
            // 首启时等用户在同意页点「同意并继续」再请求（见上面的 overlay）。
            if consent.accepted {
                location.requestLocation()
            }
            runSmokeScenarioIfNeeded()
        }
        .onReceive(location.$coordinate.dropFirst()) { coordinate in
            guard let coordinate else { return }
            model.updateStation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .background:
                // 申请后台提醒刷新；音频会话保持激活以尽量延续接收
                BackgroundReminderScheduler.shared.schedule()
                AprsStore.shared.appDidEnterBackground()
            case .active:
                AudioSession.refreshIfNeeded()
                BackgroundReminderScheduler.shared.schedule()
                // APRS-IS 是常驻 TCP：进后台会被系统挂起，回前台必须确认还在线
                AprsStore.shared.appDidBecomeActive()
            default:
                break
            }
        }
    }

    /// CI 冒烟场景：没有 Mac 的机器上只能靠截图验证。
    ///
    /// - `-smokeManage`：等 TLE 拉取完成后自动进入「卫星管理」页（截图里能看到
    ///   轨道目录 / 业余卫星两个计数）；
    /// - `-smokeDetail`：再进一层卫星详情，验证「点卫星直接进详情」。
    ///
    /// 正常启动时这两个分支都不会命中，对用户零影响。
    private func runSmokeScenarioIfNeeded() {
        let args = ProcessInfo.processInfo.arguments
        // 冒烟/截图场景是全新安装，隐私同意页会挡在前面；CI 只验证界面，
        // 这类启动参数下直接记为已同意，避免截图全部停在同意页。
        if args.contains(where: { $0.hasPrefix("-smoke") }), !consent.accepted {
            consent.accept(aprsPublic: false)
        }
        let wantsManage = args.contains("-smokeManage") || args.contains("-smokeDetail")
        guard wantsManage else { return }
        // 给 TLE（业余源 + 全量目录）留出下载与解析时间。
        // 转义闭包里必须显式写 self（Swift 的 capture 规则），@State 的存储是引用盒，
        // 拷贝出去的 self 仍然指向同一份 NavigationPath。
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [self] in
            self.homePath.append(Route.satelliteManagement)
            guard args.contains("-smokeDetail") else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [self] in
                self.homePath.append(Route.satelliteDetail(norad: 25544))
            }
        }
    }
}

#Preview {
    AppRootView()
}