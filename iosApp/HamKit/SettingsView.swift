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

/// 「设置」页：照抄 Android Settings 页结构 ——
/// 外观 / 观测 / 数据源 / 隐私 / 关于。
struct SettingsView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject private var reminderSettings = ReminderSettingsStore.shared
    @ObservedObject private var theme = ThemeStore.shared
    @State private var showRevokeConfirm = false

    var body: some View {
        List {
            Section("观测") {
                NavigationLink(value: Route.locationDetail) {
                    Label("定位详情", systemImage: "location")
                }
                NavigationLink(value: Route.reminderList) {
                    LabeledContent("过境提醒", value: "本地通知")
                }
            }

            Section("过境提醒") {
                Toggle("启用过境提醒", isOn: $reminderSettings.enabled)
                Picker("提前量", selection: $reminderSettings.leadMinutes) {
                    ForEach(ReminderSettingsStore.leadOptions, id: \.self) { minutes in
                        Text("提前 \(minutes) 分钟").tag(minutes)
                    }
                }
                .disabled(!reminderSettings.enabled)
            }

            Section("外观") {
                NavigationLink(value: Route.colorPalette) {
                    LabeledContent {
                        Text(theme.appearance.label + " · " + theme.accent.label)
                            .foregroundStyle(.secondary)
                    } label: {
                        Label("主题配色", systemImage: "paintpalette")
                    }
                }
            }

            Section("隐私") {
                NavigationLink(value: Route.privacyPolicy) {
                    Label("隐私政策", systemImage: "hand.raised")
                }
                NavigationLink(value: Route.permissions) {
                    Label("权限管理", systemImage: "lock.shield")
                }
                Button(role: .destructive) {
                    showRevokeConfirm = true
                } label: {
                    Label("撤回隐私同意", systemImage: "arrow.uturn.backward")
                }
            }

            Section("数据源") {
                LabeledContent("TLE 源", value: "tle.hamkit.click（amateur + satnogs + active）")
                LabeledContent("轨道目录", value: "\(model.satellites.count) 颗")
                LabeledContent("业余卫星", value: "\(model.amateurSatellites.count) 颗")
                LabeledContent("更新于", value: model.lastUpdatedText)
                LabeledContent("转发器（SatNOGS）", value: model.transmitterStatusText)
                Button {
                    model.loadSatellites(force: true)
                } label: {
                    Label("立即刷新 TLE", systemImage: "arrow.triangle.2.circlepath")
                }
                .disabled(model.isLoadingSatellites)
                Button {
                    model.loadTransmitters()
                } label: {
                    Label("重新拉取转发器频率", systemImage: "antenna.radiowaves.left.and.right")
                }
                .disabled(model.isTransmittersLoading)
            }

            Section("关于") {
                NavigationLink(value: Route.about) {
                    Label("关于 HamKit", systemImage: "info.circle")
                }
                LabeledContent("内核版本", value: HamKitApi.shared.version())
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(MiuixTheme.background)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
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
}

/// 「关于」页：对应 Android About 页。
///
/// 开发者 / 捐赠者名单与 Android `AboutScreen` 同一份数据（姓名 + 呼号 + GitHub id），
/// 头像直接取 GitHub（`https://github.com/<id>.png`），点整行打开对应主页。
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
        List {
            Section {
                LabeledContent("应用", value: "HamKit iOS")
                LabeledContent("版本", value: versionText)
                LabeledContent("内核版本", value: HamKitApi.shared.version())
            }
            Section("项目") {
                LabeledContent("定位", value: "业余无线电卫星观测工具")
                LabeledContent("内核", value: "Kotlin Multiplatform（与 Android 同源）")
                LabeledContent("开源", value: "github.com/fuxue-linkong/HamKit")
            }
            Section("开发者") {
                ForEach(developers) { ContributorRow(contributor: $0) }
            }
            if !donors.isEmpty {
                Section {
                    ForEach(donors) { ContributorRow(contributor: $0) }
                } header: {
                    Text("捐赠者")
                } footer: {
                    Text("感谢对项目的支持。")
                }
            }
            Section("说明") {
                Text("iOS 端为 SwiftUI 壳，业务计算全部由共享内核提供：分区定位、SGP4/SDP4 过境计算、多普勒。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
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
                        .foregroundStyle(.tertiary)
                }
                .frame(width: 36, height: 36)
                .clipShape(Circle())

                VStack(alignment: .leading, spacing: 2) {
                    Text(contributor.name)
                        .foregroundStyle(.primary)
                    if let callsign = contributor.callsign, !callsign.isEmpty {
                        Text(callsign)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text("@\(contributor.githubId)")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
    }
}

/// 「隐私政策」页：对应 Android PrivacyPolicy 页；条款与 Android 同源，只描述 iOS 的实际情况。
struct PrivacyPolicyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("HamKit 隐私政策（iOS）")
                    .font(.title3.weight(.semibold))
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
                .font(.callout)
                .foregroundStyle(.secondary)
            }
            .padding()
        }
        .navigationTitle("隐私政策")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 「权限管理」页：对应 Android Permissions 页；iOS 权限由系统设置管理，这里展示状态并给跳转。
struct PermissionsView: View {

    @ObservedObject var location: LocationProvider

    var body: some View {
        List {
            Section {
                LabeledContent("定位", value: location.statusText)
                Button("打开系统设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            } header: {
                Text("权限状态")
            } footer: {
                Text("iOS 权限只能到系统设置里开关，HamKit 不存储这些状态。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("权限管理")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 「主题配色」页：对应 Android ColorPalette 页（外观模式 + 主题色）。
///
/// Android 用 Miuix 的 `ThemeController`（Monet 动态取色）；iOS 没有对应的取色器，
/// 这里提供固定的 9 档色板 + 三种外观模式，效果等价于 Android 的「自定义配色」。
struct ColorPaletteView: View {

    @ObservedObject private var theme = ThemeStore.shared

    private let columns = [GridItem(.adaptive(minimum: 68), spacing: 12)]

    var body: some View {
        List {
            Section {
                Picker("外观", selection: $theme.appearance) {
                    ForEach(MiuixAppearance.allCases) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
            } header: {
                Text("外观")
            } footer: {
                Text("「跟随系统」时深浅色由 iOS 显示设置决定；选浅色/深色则覆盖系统设置。")
            }

            Section {
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
                                    .font(.caption2)
                                    .foregroundStyle(theme.accent == accent ? MiuixTheme.primary : .secondary)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(Text(accent.label))
                    }
                }
                .padding(.vertical, 6)
            } header: {
                Text("主题色")
            } footer: {
                Text("主题色会应用到按钮、链接、选中态与分类高亮。Android 端的动态取色（Monet）在 iOS 上没有对应 API，因此这里用固定色板。")
            }

            Section {
                HStack(spacing: 10) {
                    Image(systemName: "circle.fill").foregroundStyle(MiuixTheme.primary)
                    Text("示例：导航返回箭头、开关、链接都会跟随主题色")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(MiuixTheme.background)
        .navigationTitle("主题配色")
        .navigationBarTitleDisplayMode(.inline)
    }
}
