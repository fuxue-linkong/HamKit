import SwiftUI

/// 路由分发：enum Route → 具体页面。对应 Android Navigation3 的 NavHost。
/// 首页 / 设置两个 Tab 各自持有 NavigationStack，子页统一从这里解析。
struct RouteDestination: View {

    let route: Route
    let model: TrackerModel
    @ObservedObject var location: LocationProvider

    init(route: Route, model: TrackerModel, location: LocationProvider? = nil) {
        self.route = route
        self.model = model
        self.location = location ?? LocationProvider()
    }

    var body: some View {
        switch route {
        // 主入口
        case .home:              ContentView(model: model, location: location)
        case .settings:          SettingsView(model: model)
        case .about:             AboutView()
        case .privacyPolicy:     PrivacyPolicyView()
        case .colorPalette:      ColorPaletteView()
        case .permissions:       PermissionsView(location: location)

        // 卫星链路
        case .satelliteManagement: SatelliteManagementView(model: model, location: location)
        case .satelliteFilter:     SatelliteFilterView(model: model)
        case .satelliteCategoryManager: SatelliteCategoryManagerView(model: model)
        case .satelliteAssignment(let norad): SatelliteAssignmentView(model: model, norad: norad)
        case .satelliteDetail:     SatelliteDetailScreen(model: model, norad: route.noradParam)
        case .satelliteRadar:      SatelliteRadarView(model: model, norad: route.noradParam)
        case .satelliteMap:        SatelliteMapView(model: model, norad: route.noradParam)
        case .reminderList:        ReminderListView(model: model)
        case .locationDetail:      LocationDetailView(model: model, location: location)

        // APRS
        case .aprsMain:          AprsMainView(model: model, location: location)
        case .aprsSettings:      AprsSettingsView()
        case .aprsStations:      AprsStationsView()
        case .aprsMessages:      AprsMessagesView()
        case .aprsMap:           AprsMapView()
        case .aprsSymbolPicker:  AprsSymbolPickerView()

        // FT8
        case .ft8Main:           Ft8MainView()
        case .ft8Settings:       Ft8SettingsView()

        // SSTV
        case .sstvMain(let preset, let autoStart): SstvMainView(presetModeName: preset, autoStart: autoStart)
        case .sstvSettings:        SstvSettingsView()
        case .sstvGallery(let pick): SstvGalleryView(pickForTx: pick)
        case .sstvTx:              SstvTxView()

        // AI 助手
        case .aiAssistant:       AiAssistantView()
        case .aiSettings:        AiSettingsView()
        case .aiHistory:         AiHistoryView()

        // 其他
        case .hfFax:             HfFaxView()
        case .cwPractice:        CwPracticeHomeView()
        case .cwFreePractice:    CwPracticeView()
        case .cwTutorial:        CwTutorialView()
        case .cwMorseCodec:      CwMorseCodecView()
        case .qsoLogList:        QsoLogListView()
        }
    }
}

private extension Route {
    /// satelliteDetail/radar/map 携带的 NORAD；其他路由返回 0（不使用）。
    var noradParam: Int32 {
        switch self {
        case .satelliteDetail(let n), .satelliteRadar(let n), .satelliteMap(let n):
            return n
        default:
            return 0
        }
    }
}
