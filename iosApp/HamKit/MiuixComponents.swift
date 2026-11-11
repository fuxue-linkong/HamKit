import SwiftUI

// MARK: - miuix 基础组件（对齐 top.yukonga.miuix.kmp.basic / preference）
//
// 规格取自上游 miuix 0.9.x 源码：
// - Card：圆角 16（`CardDefaults.CornerRadius`），底色 surfaceContainer；
// - BasicComponent：最小行高 56（`BasicComponentDefaults`），内边距 16，
//   标题 17sp Medium（headline1），摘要 14sp（body2 / onSurfaceVariantSummary 60% 正色），
//   startAction 与 endActions 两侧留 8dp 间距；
// - ArrowPreference：右侧 10×16 箭头，色 onSurfaceVariantActions（40% 正色）；
// - SwitchPreference：右端 Switch，选中轨道色 primary；
// - fork（HamKit miuix 分支）的设置页统一为：12dp 水平边距 + 卡片间 12dp 的卡片分组。

/// 卡片分组容器：圆角 16、`MiuixTheme.card` 底色，行由内容自带内边距。
struct MiuixGroupCard<Content: View>: View {

    var spacing: CGFloat = 0
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(MiuixTheme.card)
        .clipShape(RoundedRectangle(cornerRadius: MiuixTheme.radius, style: .continuous))
    }
}

/// miuix `BasicComponent` / `ArrowPreference` 的 SwiftUI 等价物：
/// 左侧可选图标（startAction，24pt，onBackground 色，对齐 fork 设置页的用法），
/// 中间标题 + 摘要，右侧 endAction 槽（开关 / 值文字 / 菜单），可选右箭头。
/// 右侧槽用 AnyView 存储，省略时不参与泛型推断。
struct MiuixRow: View {

    let title: String
    var summary: String? = nil
    /// startAction 图标（SF Symbol），nil 则不占位。
    var systemImage: String? = nil
    /// 禁用态置灰。
    var enabled: Bool = true
    /// 标题颜色（危险操作行可传 error 色）。
    var titleColor: Color = MiuixTheme.text
    var showsChevron: Bool = false
    /// 整行点击回调；nil 则整行不可点（无按压反馈）。
    var onClick: (() -> Void)? = nil
    /// 右侧动作槽（开关 / 值 / 菜单）。使用箭头行时一般留空。
    var end: AnyView? = nil

    var body: some View {
        row
            .padding(.horizontal, MiuixTheme.cardPadding)
            .padding(.vertical, MiuixTheme.cardPadding)
            .frame(minHeight: MiuixTheme.rowMinHeight)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { onClick?() }
    }

    @ViewBuilder
    private var row: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 17))
                    .frame(width: 24)
                    .foregroundStyle(enabled ? MiuixTheme.text : MiuixTheme.textTertiary)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(enabled ? titleColor : MiuixTheme.textTertiary)
                if let summary, !summary.isEmpty {
                    Text(summary)
                        .font(.system(size: 14))
                        .foregroundStyle(MiuixTheme.textSecondary)
                }
            }
            Spacer(minLength: 8)
            if let end {
                end
            }
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(MiuixTheme.textTertiary)
            }
        }
    }
}

/// 标题 + 右侧值文字（对应 Android 设置页里的 `LabeledContent` 行）。
/// 值较长时折行放右侧，色为次要文字。
struct MiuixValueRow: View {

    let title: String
    let value: String
    var titleColor: Color = MiuixTheme.text

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(titleColor)
            Spacer(minLength: 12)
            Text(value)
                .font(.system(size: 14))
                .foregroundStyle(MiuixTheme.textSecondary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, MiuixTheme.cardPadding)
        .padding(.vertical, MiuixTheme.cardPadding)
        .frame(minHeight: MiuixTheme.rowMinHeight)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 可点击的值行：值显示在右侧，整行带箭头（`ArrowPreference` 带摘要用法）。
struct MiuixArrowRow: View {

    let title: String
    var summary: String? = nil
    var systemImage: String? = nil
    var enabled: Bool = true
    /// 危险操作（撤回同意等）标题用 error 色。
    var destructive: Bool = false
    let onClick: () -> Void

    var body: some View {
        // 显式可空闭包变量，避免 `cond ? closure : nil` 的类型推断歧义
        let tap: (() -> Void)? = {
            guard enabled else { return nil }
            return { onClick() }
        }()
        MiuixRow(
            title: title,
            summary: summary,
            systemImage: systemImage,
            enabled: enabled,
            titleColor: destructive ? MiuixTheme.danger : MiuixTheme.text,
            showsChevron: true,
            onClick: tap
        )
    }
}

/// miuix `SwitchPreference`：整行可点切换开关，右端 Switch 轨道色 primary。
///
/// Switch 关闭自身点击（allowsHitTesting(false)），由整行 tap 驱动——
/// 否则点在开关上会同时触发开关与行 tap，等于没切。
struct MiuixSwitchRow: View {

    let title: String
    var summary: String? = nil
    var systemImage: String? = nil
    @Binding var isOn: Bool
    var disabled: Bool = false

    var body: some View {
        let tap: (() -> Void)? = {
            guard !disabled else { return nil }
            return { isOn.toggle() }
        }()
        MiuixRow(
            title: title,
            summary: summary,
            systemImage: systemImage,
            enabled: !disabled,
            showsChevron: false,
            onClick: tap,
            end: AnyView(
                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    .tint(MiuixTheme.primary)
                    .disabled(disabled)
                    .allowsHitTesting(false)
                    .fixedSize()
            )
        )
    }
}

/// miuix `OverlayDropdownPreference` 的简化版：标题 + 右侧 Menu 选值。
struct MiuixMenuRow<Option: Hashable>: View {

    let title: String
    var summary: String? = nil
    var systemImage: String? = nil
    var disabled: Bool = false
    let options: [Option]
    /// 选项显示文案。
    let label: (Option) -> String
    @Binding var selection: Option

    var body: some View {
        MiuixRow(
            title: title,
            summary: summary,
            systemImage: systemImage,
            enabled: !disabled,
            showsChevron: false,
            end: AnyView(
                Menu {
                    ForEach(options, id: \.self) { option in
                        Button {
                            selection = option
                        } label: {
                            if option == selection {
                                Label(label(option), systemImage: "checkmark")
                            } else {
                                Text(label(option))
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(label(selection))
                            .font(.system(size: 14))
                            .foregroundStyle(disabled ? MiuixTheme.textTertiary : MiuixTheme.textSecondary)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(MiuixTheme.textTertiary)
                    }
                }
                .disabled(disabled)
            )
        )
    }
}

/// miuix 页面骨架：surface 底色 + 12 水平边距 + 卡片间 12 间距（对齐 fork 各 Miuix 页）。
struct MiuixPage<Content: View>: View {

    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(spacing: MiuixTheme.cardSpacing) {
                content()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
        }
        .background(MiuixTheme.background)
    }
}
