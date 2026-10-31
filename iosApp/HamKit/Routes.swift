import SwiftUI

/// iOS 端路由：与 Android `Routes.kt` 一一对应（iOS 用 enum + associated value）。
/// 每个页面都是 NavigationStack 里的一个目的地；列表页 → 详情页的层级与 Android 相同。
enum Route: Hashable {
    // MARK: 主入口
    case home
    case settings
    case about
    case privacyPolicy
    case colorPalette
    case permissions

    // MARK: 卫星链路（全部做实）
    case satelliteManagement
    case satelliteFilter
    case satelliteCategoryManager
    case satelliteAssignment(norad: Int32)
    case satelliteDetail(norad: Int32)
    case satelliteRadar(norad: Int32)
    case satelliteMap(norad: Int32)
    case reminderList
    case locationDetail

    // MARK: APRS
    case aprsMain
    case aprsSettings
    case aprsStations
    case aprsMessages
    case aprsMap
    case aprsSymbolPicker

    // MARK: FT8
    case ft8Main
    case ft8Settings

    // MARK: SSTV
    case sstvMain(presetModeName: String?, autoStart: Bool)
    case sstvSettings
    case sstvGallery(pickForTx: Bool)
    case sstvTx

    // MARK: HF Fax
    case hfFax

    // MARK: AI 助手
    case aiAssistant
    case aiSettings
    case aiHistory

    // MARK: CW / QSO
    case cwPractice
    case cwFreePractice
    case cwTutorial
    case cwMorseCodec
    case qsoLogList
}
