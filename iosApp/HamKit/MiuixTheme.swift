import SwiftUI

/// 深浅模式：跟随系统 / 强制浅色 / 强制深色（对应 Android `AppSettings.colorMode`）。
enum MiuixAppearance: String, CaseIterable, Identifiable {

    case system
    case light
    case dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "跟随系统"
        case .light:  return "浅色"
        case .dark:   return "深色"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light:  return .light
        case .dark:   return .dark
        }
    }
}

/// 强调色（keyColor）。
///
/// 色值对齐 Android 端 `SafeColors.keyColorSeed`（Material Design 400 系，全部经过
/// 视觉验证，避免产生肉色、酒红色等诡异色调）；case 名保持与 iOS 历史版本兼容，
/// 老用户已保存的偏好不会失效。iOS 没有 Monet 动态取色，所以没有 Android 的
/// 「默认（从壁纸取色）」选项，默认落在 MIUI 蓝（miuix 库默认 primary）。
enum MiuixAccent: String, CaseIterable, Identifiable {

    case blue, red, pink, purple, deepPurple, indigo, cyan, teal, green, yellow, amber, orange, brown, gray, sakura

    var id: String { rawValue }

    var label: String {
        switch self {
        case .blue:       return "蓝"
        case .red:        return "红"
        case .pink:       return "粉"
        case .purple:     return "紫"
        case .deepPurple: return "深紫"
        case .indigo:     return "靛青"
        case .cyan:       return "青蓝"
        case .teal:       return "水鸭绿"
        case .green:      return "绿"
        case .yellow:     return "黄"
        case .amber:      return "琥珀"
        case .orange:     return "橙"
        case .brown:      return "棕"
        case .gray:       return "蓝灰"
        case .sakura:     return "樱粉"
        }
    }

    /// 浅色模式主色：与 Android `SafeColors.keyColorSeed` 一一对应的 Material 400 色值。
    /// 其中 `blue` 取 miuix 库默认 primary `#3482FF`（iOS 端的「默认」档）。
    var lightHex: UInt32 {
        switch self {
        case .blue:       return 0x3482FF  // miuix 默认 primary（= Android 未选 keyColor 时的观感）
        case .red:        return 0xEF5350  // Red 400
        case .pink:       return 0xEC407A  // Pink 400
        case .purple:     return 0xAB47BC  // Purple 400
        case .deepPurple: return 0x7E57C2  // Deep Purple 400
        case .indigo:     return 0x5C6BC0  // Indigo 400
        case .cyan:       return 0x26C6DA  // Cyan 400
        case .teal:       return 0x26A69A  // Teal 400
        case .green:      return 0x66BB6A  // Green 400
        case .yellow:     return 0xFFEE58  // Yellow 400
        case .amber:      return 0xFFCA28  // Amber 400
        case .orange:     return 0xFFA726  // Orange 400
        case .brown:      return 0x8D6E63  // Brown 400
        case .gray:       return 0x78909C  // Blue Grey 400
        case .sakura:     return 0xF48FB1  // Sakura (Pink 200)
        }
    }

    /// 深色模式主色（略微提亮，模拟 Android Monet 深色 tone 映射，保证深底对比度）。
    var darkHex: UInt32 {
        switch self {
        case .blue:       return 0x5E9BFF
        case .red:        return 0xFF7B78
        case .pink:       return 0xFF7BA2
        case .purple:     return 0xC97DDC
        case .deepPurple: return 0xA982DA
        case .indigo:     return 0x8C9BE0
        case .cyan:       return 0x4DD8E9
        case .teal:       return 0x4DD0C2
        case .green:      return 0x81D685
        case .yellow:     return 0xFFF58F
        case .amber:      return 0xFFD54F
        case .orange:     return 0xFFB65C
        case .brown:      return 0xB08F7F
        case .gray:       return 0x9FB3C0
        case .sakura:     return 0xFFB3D1
        }
    }

    /// 跟随当前深浅色的实际颜色（UIColor 动态色，由 trait 决定）。
    var color: Color {
        Color(UIColor { trait in
            UIColor(hex: trait.userInterfaceStyle == .dark ? self.darkHex : self.lightHex)
        })
    }
}

/// 主题设置：深浅模式 + 强调色 + 背景不透明度，持久化在 UserDefaults。
///
/// 对应 Android 的 `SettingsStore.colorMode` / `keyColor` / `backgroundAlpha`：
/// 改完立即生效，不需要重启，也不需要重启导航栈。
final class ThemeStore: ObservableObject {

    static let shared = ThemeStore()

    @Published var appearance: MiuixAppearance {
        didSet { defaults.set(appearance.rawValue, forKey: Self.keyAppearance) }
    }

    @Published var accent: MiuixAccent {
        didSet { defaults.set(accent.rawValue, forKey: Self.keyAccent) }
    }

    /// 背景不透明度：1 不透明；0.5 半透明（系统壁纸透出，文字与卡片保持不透明）。
    @Published var backgroundAlpha: Double {
        didSet {
            let clamped = min(max(backgroundAlpha, 0.5), 1)
            if clamped != backgroundAlpha { backgroundAlpha = clamped }
            defaults.set(clamped, forKey: Self.keyBackgroundAlpha)
        }
    }

    private let defaults = UserDefaults.standard
    private static let keyAppearance = "hamkit.theme.appearance"
    private static let keyAccent = "hamkit.theme.accent"
    private static let keyBackgroundAlpha = "hamkit.theme.background-alpha"

    private init() {
        appearance = MiuixAppearance(rawValue: defaults.string(forKey: Self.keyAppearance) ?? "") ?? .system
        accent = MiuixAccent(rawValue: defaults.string(forKey: Self.keyAccent) ?? "") ?? .blue
        let stored = defaults.object(forKey: Self.keyBackgroundAlpha) as? Double ?? 1
        backgroundAlpha = min(max(stored, 0.5), 1)
    }

    /// 当前强调色（已按深浅模式解析）。
    var accentColor: Color { accent.color }
}

/// Miuix（MIUI / HyperOS 设计语言）主题，色板逐项对齐上游 miuix 0.9.x
/// `top.yukonga.miuix.kmp.theme.Colors` 的 light/dark 默认值。
///
/// 上游 token → iOS 映射（页面底色取 miuix `Scaffold.containerColor = surface`）：
/// - 页面底色（surface）：浅色 `#F7F7F7` / 深色纯黑；
/// - 卡片（surfaceContainer / surfaceVariant）：浅色白 / 深色 `#242424`；
/// - 分隔线（dividerLine）：`#E0E0E0` / `#393939`；描边（outline）：`#D9D9D9` / `#404040`；
/// - 文字三级（onBackground / onSurfaceVariantSummary / onSurfaceVariantActions）：
///   分别为 100% / 60% / 40% 不透明度的正色；
/// - 错误（error）：`#E94634` / `#F12522`；
/// - 强调色由「设置 → 主题配色」决定（默认 miuix 蓝 `#3482FF`，即 `lightColorScheme()` 默认值）。
///
/// 状态指示色来自 Android 端 `SafeColors`（成功/错误/信息 图标色 + 容器色），
/// 供权限卡、连接状态等场景使用。
///
/// 用法：
/// ```swift
/// .background(MiuixTheme.background)
/// card.miuixCard()
/// MiuixGroupCard { MiuixRow(...) }
/// ```
enum MiuixTheme {

    // MARK: - 颜色（自动跟随系统深浅色，对齐上游 light/darkColorScheme()）

    /// miuix `surface`：页面底色（Scaffold / 底栏 / 顶栏）。
    ///
    /// 跟随「设置 → 背景不透明度」：降到 1 以下时半透明，系统壁纸透出
    /// （对齐 Android `backgroundAlpha`，卡片与文字保持不透明）。
    static var background: Color {
        Color(UIColor { [alpha = ThemeStore.shared.backgroundAlpha] trait in
            let hex = trait.userInterfaceStyle == .dark ? 0x000000 : 0xF7F7F7
            let base = UIColor(hex: hex)
            var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, opacity: CGFloat = 0
            base.getRed(&red, green: &green, blue: &blue, alpha: &opacity)
            return UIColor(red: red, green: green, blue: blue, alpha: alpha)
        })
    }

    /// miuix `surfaceContainer` / `surfaceVariant`：卡片底色。
    static let card = dynamic(light: 0xFFFFFF, dark: 0x242424)
    /// miuix `surfaceContainerHighest`：卡片内再一层（浅色 `#E8E8E8` / 深色 `#2D2D2D`）。
    static let cardElevated = dynamic(light: 0xE8E8E8, dark: 0x2D2D2D)
    /// miuix `secondary`：填充控件底色。
    static let fill = dynamic(light: 0xE6E6E6, dark: 0x505050)

    /// 强调色：跟随「设置 → 主题配色」里的选择。
    static var primary: Color { ThemeStore.shared.accentColor }
    static let onPrimary = dynamic(light: 0xFFFFFF, dark: 0xFFFFFF)

    /// miuix `onBackground`：正文（浅色纯黑 / 深色 90% 白）。
    static let text = dynamic(light: 0x000000, dark: 0xFFFFFF, darkAlpha: 0.90)
    /// miuix `onSurfaceVariantSummary`：次要文字（60% 正色）。
    static let textSecondary = dynamic(light: 0x000000, dark: 0xFFFFFF, darkAlpha: 0.60)
    /// miuix `onSurfaceVariantActions`：三级文字与右箭头（40% 正色）。
    static let textTertiary = dynamic(light: 0x000000, dark: 0xFFFFFF, darkAlpha: 0.40)

    /// miuix `outline`：描边。
    static let outline = dynamic(light: 0xD9D9D9, dark: 0x404040)
    /// miuix `dividerLine`：行分隔线。
    static let divider = dynamic(light: 0xE0E0E0, dark: 0x393939)
    /// miuix `error`。
    static let danger = dynamic(light: 0xE94634, dark: 0xF12522)
    /// 上游没有 success token，取 Android `SafeColors.successIcon`。
    static let success = Color(UIColor(hex: 0x36D167))

    // MARK: - 状态指示色（Android 端 SafeColors）

    /// 成功状态：图标色 + 容器底色。
    static let successIcon = Color(UIColor(hex: 0x36D167))
    static let successContainer = dynamic(light: 0xDFFAE4, dark: 0x1B3A24)
    /// 错误状态：图标色 + 容器底色（Android 深色容器 `#310808`）。
    static let errorIcon = Color(UIColor(hex: 0xF72727))
    static let errorContainer = dynamic(light: 0xF8E2E2, dark: 0x310808)
    /// 信息状态：图标色 + 容器底色。
    static let infoIcon = dynamic(light: 0x42A5F5, dark: 0x64B5F6)
    static let infoContainer = dynamic(light: 0xE3F2FD, dark: 0x0D2740)

    // MARK: - 尺寸（对齐 miuix Card / BasicComponent 默认值）

    /// `CardDefaults.CornerRadius`：大卡片圆角 16。
    static let radius: CGFloat = 16
    /// 小控件圆角（chip / 徽章）
    static let smallRadius: CGFloat = 10
    /// `BasicComponentDefaults.InsideMargin`：行内边距 16。
    static let cardPadding: CGFloat = 16
    /// 卡片之间的间距（fork 各 Miuix 页统一 12dp）。
    static let cardSpacing: CGFloat = 12
    /// `BasicComponentDefaults` 行最小高度 56。
    static let rowMinHeight: CGFloat = 56

    // MARK: - 工具

    /// 由一个浅色 + 一个深色十六进制值构造动态色；深色可带不透明度。
    static func dynamic(light: UInt32, dark: UInt32, darkAlpha: CGFloat = 1.0) -> Color {
        Color(UIColor { trait in
            if trait.userInterfaceStyle == .dark {
                return UIColor(hex: dark).withAlphaComponent(darkAlpha)
            }
            return UIColor(hex: light)
        })
    }

    /// 页面底色（别名，语义化使用）。
    static var surface: Color { background }

    // MARK: - 全局 UIKit 外观

    /// 把 TabBar / NavBar 外观对齐 miuix：底栏 surface 色、选中 primary、
    /// 未选中 onSurfaceVariantActions（40% 正色）。在 App 启动时调用一次，
    /// 动态色会随深浅模式自动切换，无需手动刷新。
    static func configureUIKitAppearance() {
        let surface = UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(hex: 0x000000)
                : UIColor(hex: 0xF7F7F7)
        }
        let summary = UIColor { trait in
            trait.userInterfaceStyle == .dark
                ? UIColor(white: 1.0, alpha: 0.40)
                : UIColor(white: 0.0, alpha: 0.40)
        }

        let tab = UITabBarAppearance()
        tab.configureWithOpaqueBackground()
        tab.backgroundColor = surface
        // 卡片风格 item：未选中 40% 正色，选中态不设色 → 跟随全局 tint（primary）
        tab.stackedLayoutAppearance.normal.iconColor = summary
        tab.stackedLayoutAppearance.normal.titleTextAttributes = [.foregroundColor: summary]
        UITabBar.appearance().standardAppearance = tab
        if #available(iOS 15.0, *) {
            UITabBar.appearance().scrollEdgeAppearance = tab
        }

        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = surface
        nav.shadowColor = .clear
        nav.titleTextAttributes = [.foregroundColor: UIColor.label]
        nav.largeTitleTextAttributes = [.foregroundColor: UIColor.label]
        UINavigationBar.appearance().standardAppearance = nav
        if #available(iOS 15.0, *) {
            UINavigationBar.appearance().scrollEdgeAppearance = nav
        }
        // 业务子页仍用原生 List（insetGrouped），其分组底色由系统管理，
        // 与 miuix surface 色已足够接近（#F2F2F2 vs #F7F7F7），不做强改。
    }
}

extension UIColor {
    /// 0xRRGGBB → UIColor（alpha 固定 1）。
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green: CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hex & 0xFF) / 255.0,
            alpha: 1.0
        )
    }
}

/// Miuix 卡片：`MiuixTheme.card` 底 + 连续圆角 + 标准内边距。
struct MiuixCardModifier: ViewModifier {

    var padding: CGFloat = MiuixTheme.cardPadding
    var radius: CGFloat = MiuixTheme.radius
    var background: Color = MiuixTheme.card

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {

    /// 套一层 Miuix 卡片外观。
    func miuixCard(
        padding: CGFloat = MiuixTheme.cardPadding,
        radius: CGFloat = MiuixTheme.radius,
        background: Color = MiuixTheme.card
    ) -> some View {
        modifier(MiuixCardModifier(padding: padding, radius: radius, background: background))
    }

    /// 页面级外观：Miuix 底色 + 全局主色（导航栏返回箭头、按钮、链接都跟着变）。
    func miuixScreen() -> some View {
        self
            .tint(MiuixTheme.primary)
            .background(MiuixTheme.background)
    }
}
