import SwiftUI

/// iOS 26 原生 Liquid Glass 的接入点。
///
/// **为什么需要显式接入**：UIKit / SwiftUI 不会把 Liquid Glass「注入」到任意视图。
/// 只有在用 iOS 26 SDK 构建时，系统才会给 `TabView`、`NavigationStack` 等标准
/// 容器自带的栏应用新材质；地图浮层、悬浮按钮、信息卡这类自定义视图必须自己调
/// `.glassEffect(...)`，否则在 iOS 26 上仍然是普通半透明/纯色背景。
///
/// 兼容策略：
/// - iOS 26+ 且开关打开：走系统原生 `.glassEffect`；
/// - iOS < 26、开关关闭，或用旧 SDK（Swift < 6.2）编译：退回 `.ultraThinMaterial`，
///   视觉效果与之前一致，也保证旧 Xcode 仍能构建。
enum LiquidGlass {

    /// 当前运行环境 + 编译 SDK 是否支持原生 Liquid Glass。
    ///
    /// 用旧 SDK 编译时 `#if compiler(>=6.2)` 整段不参与编译，这里返回 `false`，
    /// 设置页也不会展示无效的开关。
    static var isAvailable: Bool {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            return true
        }
        #endif
        return false
    }
}

/// 「液态玻璃」开关，对应 Android 设置里的 `settings_enable_glass`。
///
/// 默认打开；关闭后所有 `liquidGlass(...)` 调用退回 `.ultraThinMaterial`，
/// 方便在玻璃影响可读性（强光、复杂地图底图）时退回旧观感。
final class LiquidGlassSettingsStore: ObservableObject {

    static let shared = LiquidGlassSettingsStore()

    @Published var enabled: Bool {
        didSet { defaults.set(enabled, forKey: Self.key) }
    }

    private let defaults = UserDefaults.standard
    private static let key = "hamkit.appearance.liquid_glass"

    private init() {
        enabled = defaults.object(forKey: Self.key) as? Bool ?? true
    }
}

/// 给任意形状的视图套一层玻璃：iOS 26+ 用系统 `Glass`，否则用材质兜底。
private struct LiquidGlassModifier<S: Shape>: ViewModifier {

    @ObservedObject private var settings = LiquidGlassSettingsStore.shared

    let shape: S
    /// 是否启用系统提供的点按/高光反馈（适合按钮，不适合信息卡）。
    let interactive: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *), settings.enabled {
            content.glassEffect(
                interactive ? .regular.interactive() : .regular,
                in: shape
            )
        } else {
            content.background(.ultraThinMaterial, in: shape)
        }
        #else
        content.background(.ultraThinMaterial, in: shape)
        #endif
    }
}

extension View {

    /// 任意形状的原生玻璃背景。
    func liquidGlass<S: Shape>(_ shape: S, interactive: Bool = false) -> some View {
        modifier(LiquidGlassModifier(shape: shape, interactive: interactive))
    }

    /// 圆角矩形玻璃（信息卡 / 浮层）。
    func liquidGlass(cornerRadius: CGFloat, interactive: Bool = false) -> some View {
        liquidGlass(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous),
            interactive: interactive
        )
    }

    /// 圆形玻璃（地图上的悬浮按钮）。
    func liquidGlassCircle(interactive: Bool = false) -> some View {
        liquidGlass(Circle(), interactive: interactive)
    }
}
