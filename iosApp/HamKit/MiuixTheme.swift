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

/// 强调色（对应 Android Miuix 的 `keyColor`，这里固定成常用的几档，避免 iOS 端
/// 再去实现一套 Monet 取色器）。
enum MiuixAccent: String, CaseIterable, Identifiable {

    case blue, indigo, purple, pink, red, orange, green, teal, gray

    var id: String { rawValue }

    var label: String {
        switch self {
        case .blue:   return "MIUI 蓝"
        case .indigo: return "靛青"
        case .purple: return "紫"
        case .pink:   return "粉"
        case .red:    return "红"
        case .orange: return "橙"
        case .green:  return "绿"
        case .teal:   return "青"
        case .gray:   return "灰"
        }
    }

    /// 浅色模式主色
    var lightHex: UInt32 {
        switch self {
        case .blue:   return 0x3482FF
        case .indigo: return 0x4F5BD5
        case .purple: return 0x8B5CF6
        case .pink:   return 0xD6438C
        case .red:    return 0xE5484D
        case .orange: return 0xE8730C
        case .green:  return 0x1F9D55
        case .teal:   return 0x0E8F8F
        case .gray:   return 0x5C5C5C
        }
    }

    /// 深色模式主色（略微提亮，保证深底上的对比度）
    var darkHex: UInt32 {
        switch self {
        case .blue:   return 0x4C9BFF
        case .indigo: return 0x8B93FF
        case .purple: return 0xB18CFF
        case .pink:   return 0xFF7AB8
        case .red:    return 0xFF6369
        case .orange: return 0xFF9F45
        case .green:  return 0x4CC38A
        case .teal:   return 0x3FD0D0
        case .gray:   return 0xA0A0A0
        }
    }

    /// 跟随当前深浅色的实际颜色（UIColor 动态色，由 trait 决定）。
    var color: Color {
        Color(UIColor { trait in
            UIColor(hex: trait.userInterfaceStyle == .dark ? self.darkHex : self.lightHex)
        })
    }
}

/// 主题设置：深浅模式 + 强调色，持久化在 UserDefaults。
///
/// 对应 Android 的 `SettingsStore.colorMode` / `keyColor`：改完立即生效，
/// 不需要重启，也不需要重启导航栈。
final class ThemeStore: ObservableObject {

    static let shared = ThemeStore()

    @Published var appearance: MiuixAppearance {
        didSet { defaults.set(appearance.rawValue, forKey: Self.keyAppearance) }
    }

    @Published var accent: MiuixAccent {
        didSet { defaults.set(accent.rawValue, forKey: Self.keyAccent) }
    }

    private let defaults = UserDefaults.standard
    private static let keyAppearance = "hamkit.theme.appearance"
    private static let keyAccent = "hamkit.theme.accent"

    private init() {
        appearance = MiuixAppearance(rawValue: defaults.string(forKey: Self.keyAppearance) ?? "") ?? .system
        accent = MiuixAccent(rawValue: defaults.string(forKey: Self.keyAccent) ?? "") ?? .blue
    }

    /// 当前强调色（已按深浅模式解析）。
    var accentColor: Color { accent.color }
}

/// Miuix（MIUI / HyperOS 设计语言）主题，对齐 Android 端的 `MiuixTheme`。
///
/// Android 侧用的是 `top.yukonga.miuix.kmp` 的 `ThemeController`（Monet 动态取色），
/// iOS 没有对应的取色器，所以这里固化一套 Miuix 语义色 + 可选的强调色：
/// - 页面底色与卡片分层（浅色 `#F7F7F7` / 白色卡片；深色纯黑 / `#1A1A1A` 卡片）；
/// - 主色由「设置 → 外观」决定（默认 MIUI 蓝 `#3482FF`）；
/// - 文字三级：正文 / 次要 / 三级，以及分隔线、填充色；
/// - 圆角统一 16（大卡片）/ 10（小控件），全部用 continuous 圆角。
///
/// 用法：
/// ```swift
/// .background(MiuixTheme.background)
/// card.miuixCard()
/// ```
enum MiuixTheme {

    // MARK: - 颜色（自动跟随系统深浅色）

    static let background = dynamic(light: 0xF7F7F7, dark: 0x000000)
    static let card = dynamic(light: 0xFFFFFF, dark: 0x1A1A1A)
    static let cardElevated = dynamic(light: 0xFFFFFF, dark: 0x242424)
    static let fill = dynamic(light: 0xF0F0F0, dark: 0x2A2A2A)

    /// 强调色：跟随「设置 → 外观」里的选择。
    static var primary: Color { ThemeStore.shared.accentColor }
    static let onPrimary = dynamic(light: 0xFFFFFF, dark: 0xFFFFFF)

    static let text = dynamic(light: 0x000000, dark: 0xFFFFFF)
    static let textSecondary = dynamic(light: 0x808080, dark: 0x9A9A9A)
    static let textTertiary = dynamic(light: 0xA6A6A6, dark: 0x6E6E6E)

    static let divider = dynamic(light: 0xE6E6E6, dark: 0x2E2E2E)
    static let success = dynamic(light: 0x2BA471, dark: 0x4CC38A)
    static let danger = dynamic(light: 0xE5484D, dark: 0xFF6369)

    // MARK: - 尺寸

    /// 大卡片圆角
    static let radius: CGFloat = 16
    /// 小控件圆角（chip / 徽章）
    static let smallRadius: CGFloat = 10
    static let cardPadding: CGFloat = 16

    // MARK: - 工具

    /// 由一个浅色 + 一个深色十六进制值构造动态色。
    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
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
