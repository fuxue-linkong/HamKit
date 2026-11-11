import SwiftUI
import Shared

/// 过境提醒设置（对应 Android `reminderSettings`：总开关 + 提前量）。
final class ReminderSettingsStore: ObservableObject {

    static let shared = ReminderSettingsStore()

    static let leadOptions = [5, 10, 15, 30]

    @Published var enabled: Bool {
        didSet { defaults.set(enabled, forKey: "hamkit.reminder.enabled") }
    }
    @Published var leadMinutes: Int {
        didSet { defaults.set(leadMinutes, forKey: "hamkit.reminder.lead_minutes") }
    }

    private let defaults = UserDefaults.standard

    private init() {
        enabled = defaults.object(forKey: "hamkit.reminder.enabled") as? Bool ?? true
        let stored = defaults.object(forKey: "hamkit.reminder.lead_minutes") as? Int
        leadMinutes = stored ?? 5
    }

    var leadSeconds: TimeInterval { TimeInterval(leadMinutes * 60) }
}

/// 「设置」页：对齐 fork miuix 分支 `SettingsMiuix.kt` 的卡片分组结构 ——
/// 页面 12 边距 + 卡片间 12，每组一张 Miuix 卡片，行图标用 onBackground 色
/// （fork 里 startAction 统一 `tint = colorScheme.onBackground`）。
struct SettingsView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject private var reminderSettings = ReminderSettingsStore.shared
    @ObservedObject private var theme = ThemeStore.shared
    @State private var showRevokeConfirm = false

    var body: some View {
        MiuixPage {
            // 观测
            MiuixGroupCard {
                navRow(.locationDetail, "定位详情", summary: "定位状态与地面站坐标", systemImage: "location")
                navRow(.reminderList, "过境提醒列表", summary: "本地通知提醒即将过境的卫星", systemImage: "bell")
            }

            // 过境提醒（对应 fork「日程提醒」分组）
            MiuixGroupCard {
                MiuixSwitchRow(
                    title: "启用过境提醒",
                    summary: "在卫星过境前发出本地通知",
                    systemImage: "notifications",
                    isOn: $reminderSettings.enabled
                )
                MiuixMenuRow(
                    title: "提前量",
                    systemImage: "clock",
                    disabled: !reminderSettings.enabled,
                    options: ReminderSettingsStore.leadOptions,
                    label: { "提前 \($0) 分钟" },
                    selection: $reminderSettings.leadMinutes
                )
            }

            // 外观
            MiuixGroupCard {
                navRow(
                    .colorPalette,
                    "主题配色",
                    summary: theme.appearance.label + " · " + theme.accent.label,
                    systemImage: "paintpalette"
                )
            }

            // 数据源
            MiuixGroupCard {
                MiuixValueRow(title: "TLE 源", value: "tle.hamkit.click（amateur + satnogs + active）")
                MiuixValueRow(title: "轨道目录", value: "\(model.satellites.count) 颗")
                MiuixValueRow(title: "业余卫星", value: "\(model.amateurSatellites.count) 颗")
                MiuixValueRow(title: "更新于", value: model.lastUpdatedText)
                MiuixValueRow(title: "转发器（SatNOGS）", value: model.transmitterStatusText)
                actionRow(
                    "立即刷新 TLE",
                    summary: "重新拉取并解析轨道数据",
                    systemImage: "arrow.triangle.2.circlepath",
                    isLoading: model.isLoadingSatellites
                ) {
                    model.loadSatellites(force: true)
                }
                actionRow(
                    "重新拉取转发器频率",
                    summary: "从 SatNOGS 同步转发器",
                    systemImage: "antenna.radiowaves.left.and.right",
                    isLoading: model.isTransmittersLoading
                ) {
                    model.loadTransmitters()
                }
            }

            // 隐私
            MiuixGroupCard {
                navRow(.privacyPolicy, "隐私政策", summary: "数据处理与第三方服务说明", systemImage: "hand.raised")
                navRow(.permissions, "权限管理", summary: "定位 / 麦克风权限状态", systemImage: "lock.shield")
                MiuixArrowRow(
                    title: "撤回隐私同意",
                    summary: "撤回后重新走首次启动同意流程",
                    systemImage: "arrow.uturn.backward",
                    destructive: true
                ) {
                    showRevokeConfirm = true
                }
            }

            // 关于
            MiuixGroupCard {
                navRow(.about, "关于 HamKit", summary: "版本与开发者名单", systemImage: "info.circle")
                MiuixValueRow(title: "内核版本", value: HamKitApi.shared.version())
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .tint(MiuixTheme.primary)
        .onChange(of: reminderSettings.enabled) { _ in
            model.applyReminderSettings()
        }
        .confirmationDialog(
            "撤回后需要重新阅读并同意隐私政策才能继续使用需要位置/音频的功能。",
            isPresented: $showRevokeConfirm,
            titleVisibility: .visible
        ) {
            Button("撤回同意", role: .destructive) {
                PrivacyConsentStore.shared.revoke()
            }
            Button("取消", role: .cancel) {}
        }
        .onChange(of: reminderSettings.leadMinutes) { _ in
            model.applyReminderSettings()
        }
        // 路由目标由 AppRootView 统一注册，避免同一 stack 重复注册 for: Route.self
    }

    /// 值式导航行：包一层 NavigationLink(value:) 走统一 NavigationPath。
    private func navRow(
        _ route: Route,
        _ title: String,
        summary: String? = nil,
        systemImage: String? = nil
    ) -> some View {
        NavigationLink(value: route) {
            MiuixRow(title: title, summary: summary, systemImage: systemImage, showsChevron: true)
        }
        .buttonStyle(.plain)
    }

    /// 动作行（刷新数据等）：进行中在行尾显示 ProgressView（用透明度切换，避免
    /// 在非 ViewBuilder 闭包里写分支）。
    private func actionRow(
        _ title: String,
        summary: String? = nil,
        systemImage: String? = nil,
        isLoading: Bool,
        action: @escaping () -> Void
    ) -> some View {
        let tap: (() -> Void)? = isLoading ? nil : action
        return MiuixRow(
            title: title,
            summary: summary,
            systemImage: systemImage,
            showsChevron: false,
            onClick: tap,
            end: AnyView(
                ProgressView()
                    .controlSize(.small)
                    .opacity(isLoading ? 1 : 0)
            )
        )
    }
}

/// 「关于」页：miuix 卡片分组。名单与 Android `AboutScreen` 同一份数据
/// （姓名 + 呼号 + GitHub id），头像取 GitHub，点整行打开对应主页。
struct AboutView: View {

    /// 与 Android `AboutScreen` 保持一致的名单；改一边记得改另一边。
    private let developers: [Contributor] = [
        Contributor(name: "覆雪凌空", callsign: nil, githubId: "fuxue-linkong"),
        Contributor(name: "2048lr", callsign: nil, githubId: "2048lr"),
    ]
    private let donors: [Contributor] = [
        Contributor(name: "starskyham", callsign: "BG7SJU", githubId: "starskyham"),
    ]

    private var versionText: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"
        return "\(short) (\(build))"
    }

    var body: some View {
        MiuixPage {
            MiuixGroupCard {
                MiuixValueRow(title: "应用", value: "HamKit iOS")
                MiuixValueRow(title: "版本", value: versionText)
                MiuixValueRow(title: "内核版本", value: HamKitApi.shared.version())
            }
            MiuixGroupCard {
                MiuixValueRow(title: "定位", value: "业余无线电卫星观测工具")
                MiuixValueRow(title: "内核", value: "Kotlin Multiplatform（与 Android 同源）")
                MiuixValueRow(title: "开源", value: "github.com/fuxue-linkong/HamKit")
            }
            contributorCard("开发者", contributors: developers)
            if !donors.isEmpty {
                contributorCard("捐赠者", contributors: donors, footer: "感谢对项目的支持。")
            }
            MiuixGroupCard {
                Text("iOS 端为 SwiftUI 壳，业务计算全部由共享内核提供：分区定位、SGP4/SDP4 过境计算、多普勒。")
                    .font(.system(size: 14))
                    .foregroundStyle(MiuixTheme.textSecondary)
                    .padding(MiuixTheme.cardPadding)
            }
        }
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
        .tint(MiuixTheme.primary)
    }

    private func contributorCard(_ title: String, contributors: [Contributor], footer: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(MiuixTheme.textSecondary)
                .padding(.horizontal, 16)
            MiuixGroupCard {
                ForEach(contributors) { contributor in
                    ContributorRow(contributor: contributor)
                }
            }
            if let footer {
                Text(footer)
                    .font(.system(size: 12))
                    .foregroundStyle(MiuixTheme.textTertiary)
                    .padding(.horizontal, 16)
            }
        }
    }
}

/// 贡献者：姓名、可选呼号、GitHub id（头像与主页都用它拼）。
struct Contributor: Identifiable {
    let name: String
    let callsign: String?
    let githubId: String

    var id: String { githubId }
    var avatarURL: URL? { URL(string: "https://github.com/\(githubId).png?size=120") }
    var homeURL: URL? { URL(string: "https://github.com/\(githubId)") }
}

private struct ContributorRow: View {

    let contributor: Contributor

    var body: some View {
        Link(destination: contributor.homeURL ?? URL(string: "https://github.com")!) {
            HStack(spacing: 12) {
                AsyncImage(url: contributor.avatarURL) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "person.crop.circle.fill")
                        .resizable()
                        .foregroundStyle(MiuixTheme.textTertiary)
                }
                .frame(width: 36, height: 36)
                .clipShape(Circle())

                VStack(alignment: .leading, spacing: 2) {
                    Text(contributor.name)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(MiuixTheme.text)
                    if let callsign = contributor.callsign, !callsign.isEmpty {
                        Text(callsign)
                            .font(.system(size: 14))
                            .foregroundStyle(MiuixTheme.textSecondary)
                    }
                }
                Spacer()
                Text("@\(contributor.githubId)")
                    .font(.system(size: 14))
                    .foregroundStyle(MiuixTheme.textTertiary)
            }
            .padding(.horizontal, MiuixTheme.cardPadding)
            .padding(.vertical, MiuixTheme.cardPadding)
            .contentShape(Rectangle())
        }
    }
}

/// 「隐私政策」页：条款与 Android 同源，只描述 iOS 的实际情况。
struct PrivacyPolicyView: View {
    var body: some View {
        MiuixPage {
            MiuixGroupCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text("HamKit 隐私政策（iOS）")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(MiuixTheme.text)
                    Text("""
1. 我们处理哪些信息
• 精确位置（敏感个人信息）：仅用于本地计算分区、卫星过境与地图定位。除非你主动开启 APRS 上报或使用天气，位置不会离开设备。
• 麦克风与音频：仅在你主动开始 CW / FT8 / SSTV / HF Fax 接收时采集，用于本地解码，不上传。
• 呼号、APRS 与 QSO 日志：保存在本机；开启 APRS 上报后，呼号、位置与消息会发布到 APRS-IS 及关联网站，可能被任何人看到。
• 设置与图片：保存在本机沙盒，不参与云端同步。

2. 第三方服务
• TLE 数据：从 tle.hamkit.click 与 CelesTrak 匿名拉取，不含用户身份。
• 天气与城市名：调用高德 Web 服务，会上传当前坐标（不含呼号与个人信息）。
• APRS-IS：由你配置的服务器，属境外网络，可能传输呼号、位置与消息。
• AI 助手（可选）：只有你填写 API Key 并使用该功能时，对话内容（可能包含坐标、呼号、卫星记录）才会发送到你自己选择的服务商。Key 存于系统钥匙串（Keychain）、会话记录加密保存在本机，清理由你自行操作。

3. 数据出境
APRS-IS、CelesTrak、SatNOGS、AMSAT、GitHub 位于中国境外；使用相关功能时请求可能出境。AI 请求是否出境取决于你配置的服务商（选择境内服务商或内网地址时不出境）。

4. 你的权利
• 在系统设置中随时关闭定位/麦克风权限；
• 在「设置 → 隐私」撤回同意，App 会重新走首次启动的同意流程；
• 删除本机数据：卸载 App 即可清除全部本地记录（QSO 日志、会话历史、设置）。

5. 其他
无广告、无统计 SDK、无第三方追踪。若需撤回同意、删除数据或有疑问，请联系 liurun637@gmail.com。
""")
                    .font(.system(size: 14))
                    .foregroundStyle(MiuixTheme.textSecondary)
                }
                .padding(MiuixTheme.cardPadding)
            }
        }
        .navigationTitle("隐私政策")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 「权限管理」页：iOS 权限由系统设置管理，这里展示状态并给跳转。
struct PermissionsView: View {

    @ObservedObject var location: LocationProvider

    var body: some View {
        MiuixPage {
            MiuixGroupCard {
                MiuixValueRow(title: "定位", value: location.statusText)
                MiuixArrowRow(title: "打开系统设置", summary: "定位 / 麦克风权限只能在系统设置里开关", systemImage: "gearshape") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
            MiuixGroupCard {
                Text("iOS 权限只能到系统设置里开关，HamKit 不存储这些状态。")
                    .font(.system(size: 14))
                    .foregroundStyle(MiuixTheme.textSecondary)
                    .padding(MiuixTheme.cardPadding)
            }
        }
        .navigationTitle("权限管理")
        .navigationBarTitleDisplayMode(.inline)
        .tint(MiuixTheme.primary)
    }
}

/// 「主题配色」页：对应 Android ColorPalette 页（外观模式 + 主题色）。
///
/// 色板对齐 Android 端 `SafeColors.keyColorSeed`（15 档 Material 400 种子色）；
/// Android 的「默认」是 Monet 从壁纸取色，iOS 没有对应 API，默认落在
/// miuix 蓝（上游 `lightColorScheme()` 的 primary 默认值）。
struct ColorPaletteView: View {

    @ObservedObject private var theme = ThemeStore.shared
    @ObservedObject private var glass = LiquidGlassSettingsStore.shared

    private let columns = [GridItem(.adaptive(minimum: 64), spacing: 12)]

    var body: some View {
        MiuixPage {
            // 外观模式
            MiuixGroupCard {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: "circle.lefthalf.filled")
                            .font(.system(size: 17))
                            .frame(width: 24)
                            .foregroundStyle(MiuixTheme.text)
                        Text("外观")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(MiuixTheme.text)
                    }
                    Picker("外观", selection: $theme.appearance) {
                        ForEach(MiuixAppearance.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    Text("「跟随系统」时深浅色由 iOS 显示设置决定；选浅色/深色则覆盖系统设置。")
                        .font(.system(size: 14))
                        .foregroundStyle(MiuixTheme.textSecondary)
                }
                .padding(MiuixTheme.cardPadding)
            }

            // 液态玻璃（iOS 26+ 原生 Liquid Glass；旧 SDK 编译时不展示无效开关）
            if LiquidGlass.isAvailable {
                MiuixGroupCard {
                    MiuixSwitchRow(
                        title: "液态玻璃",
                        summary: "为地图浮层与悬浮按钮启用 iOS 26 原生 Liquid Glass。关闭后改用半透明材质。系统自带的标签栏 / 导航栏由系统外观控制，不受此开关影响。",
                        systemImage: "drop",
                        isOn: $glass.enabled
                    )
                }
            }

            // 背景不透明度（对齐 miuix 3792b32/c914ba2：半透明后系统壁纸透出）
            MiuixGroupCard {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        Image(systemName: "rectangle.inset.filled.on.rectangle")
                            .font(.system(size: 17))
                            .frame(width: 24)
                            .foregroundStyle(MiuixTheme.text)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("背景不透明度 \(Int(theme.backgroundAlpha * 100))%")
                                .font(.system(size: 17, weight: .medium))
                                .foregroundStyle(MiuixTheme.text)
                            Text("降低后系统壁纸会透出；文字与卡片保持不透明。")
                                .font(.system(size: 14))
                                .foregroundStyle(MiuixTheme.textSecondary)
                        }
                    }
                    Slider(value: $theme.backgroundAlpha, in: 0.5...1, step: 0.05)
                }
                .padding(MiuixTheme.cardPadding)
            }

            // 主题色
            MiuixGroupCard {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) {
                        Image(systemName: "paintpalette")
                            .font(.system(size: 17))
                            .frame(width: 24)
                            .foregroundStyle(MiuixTheme.text)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("主题色")
                                .font(.system(size: 17, weight: .medium))
                                .foregroundStyle(MiuixTheme.text)
                            Text("应用到按钮、链接、选中态与分类高亮")
                                .font(.system(size: 14))
                                .foregroundStyle(MiuixTheme.textSecondary)
                        }
                    }
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(MiuixAccent.allCases) { accent in
                            Button {
                                theme.accent = accent
                            } label: {
                                VStack(spacing: 6) {
                                    Circle()
                                        .fill(accent.color)
                                        .frame(width: 34, height: 34)
                                        .overlay {
                                            Image(systemName: "checkmark")
                                                .font(.system(size: 13, weight: .bold))
                                                .foregroundStyle(.white)
                                                .opacity(theme.accent == accent ? 1 : 0)
                                        }
                                        .overlay {
                                            Circle()
                                                .strokeBorder(Color.white.opacity(0.85), lineWidth: 2)
                                                .opacity(theme.accent == accent ? 1 : 0)
                                        }
                                    Text(accent.label)
                                        .font(.system(size: 12))
                                        .foregroundStyle(theme.accent == accent ? MiuixTheme.primary : MiuixTheme.textSecondary)
                                }
                                .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(Text(accent.label))
                        }
                    }
                }
                .padding(MiuixTheme.cardPadding)
            }

            // 效果预览（对齐 fork 预览卡的意图：一眼看到当前配色在各元素上的表现）
            MiuixGroupCard {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: "eyeglasses")
                            .font(.system(size: 17))
                            .frame(width: 24)
                            .foregroundStyle(MiuixTheme.text)
                        Text("效果预览")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(MiuixTheme.text)
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("示例标题")
                                .font(.system(size: 17, weight: .medium))
                                .foregroundStyle(MiuixTheme.text)
                            Spacer()
                            Text("摘要文字")
                                .font(.system(size: 14))
                                .foregroundStyle(MiuixTheme.textSecondary)
                        }
                        HStack(spacing: 10) {
                            Text("主色按钮")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(MiuixTheme.onPrimary)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .background(MiuixTheme.primary)
                                .clipShape(Capsule())
                            Text("链接")
                                .font(.system(size: 14))
                                .foregroundStyle(MiuixTheme.primary)
                            Spacer()
                            Image(systemName: "circle.fill").foregroundStyle(MiuixTheme.success)
                            Image(systemName: "circle.fill").foregroundStyle(MiuixTheme.danger)
                            Image(systemName: "circle.fill").foregroundStyle(MiuixTheme.infoIcon)
                        }
                    }
                    .padding(12)
                    .background(MiuixTheme.background)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .padding(MiuixTheme.cardPadding)
            }
        }
        .navigationTitle("主题配色")
        .navigationBarTitleDisplayMode(.inline)
        .tint(MiuixTheme.primary)
    }
}
