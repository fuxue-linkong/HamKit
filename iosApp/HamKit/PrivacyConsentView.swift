import SwiftUI
import Shared

/// 首次启动的隐私同意页（对齐 Android `PrivacyConsentScreen`）。
///
/// 与 Android 的差异：
/// - Android 的「不同意」会直接退出进程；iOS 不允许 App 主动退出（会被审核判为
///   「强制退出」），所以这里停留在本页并给出说明，用户仍可关掉 App；
/// - 同意之前不请求定位、不发起任何网络请求（与 Android 的「先同意再初始化」一致）。
struct PrivacyConsentView: View {

    /// 同意回调，参数是「是否允许在开启 APRS 上报时公开位置」。
    let onAgree: (Bool) -> Void

    @State private var policyAccepted = false
    @State private var locationAccepted = false
    @State private var aprsPublicAccepted = false
    @State private var showPolicy = false
    @State private var declined = false

    var body: some View {
        ZStack {
            MiuixTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("隐私政策与个人信息保护")
                        .font(.title2.weight(.semibold))

                    Text("HamKit 需要处理位置、麦克风和音频等信息才能提供分区定位、地图、卫星、APRS、FT8 与 SSTV 等功能。请先阅读并同意下面的说明；不同意时我们不会收集任何个人信息。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    ConsentSection(
                        title: "精确位置（敏感个人信息）",
                        text: "用于计算 CQ/ITU 分区与 Maidenhead 网格、显示地图、查询天气，以及在 APRS 中上报位置。精确位置属于敏感个人信息，需要你单独同意。你也可以在系统设置中随时关闭定位权限。"
                    )

                    ConsentSection(
                        title: "APRS 位置公开（可选）",
                        text: "开启 APRS 上报后，你的呼号、精确位置和消息会发布到 APRS-IS 及关联网站，可能被任何人看到。该功能默认关闭；不勾选不影响接收和其他功能。"
                    )

                    ConsentSection(
                        title: "AI 助手（可选）",
                        text: "需要你自己填写 API Key。对话内容与附件会发送给你配置的服务商；Key 与会话记录只加密保存在本机。"
                    )

                    Divider()

                    ConsentCheckbox(
                        isOn: $policyAccepted,
                        text: "我已阅读并同意《隐私政策》"
                    )
                    ConsentCheckbox(
                        isOn: $locationAccepted,
                        text: "我同意处理我的精确位置信息（敏感个人信息）"
                    )
                    ConsentCheckbox(
                        isOn: $aprsPublicAccepted,
                        text: "我同意在开启 APRS 上报时公开我的位置（可选）"
                    )

                    Button("查看完整隐私政策") { showPolicy = true }
                        .font(.subheadline)

                    Button {
                        onAgree(aprsPublicAccepted)
                    } label: {
                        Text("同意并继续")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!(policyAccepted && locationAccepted))

                    Button("不同意") { declined = true }
                        .font(.subheadline)

                    if declined {
                        Text("不同意将无法使用需要位置与音频的功能。你可以直接关闭 HamKit，我们不会在同意前收集任何个人信息。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    Text("你可以随时在系统设置中关闭定位/麦克风权限，也可以在「设置 → 关于 → 隐私政策」中查看本政策。若需撤回同意、删除数据或有疑问，请联系 liurun637@gmail.com。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
                .padding(22)
            }
        }
        .sheet(isPresented: $showPolicy) {
            NavigationStack {
                PrivacyPolicyView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("关闭") { showPolicy = false }
                        }
                    }
            }
        }
    }
}

private struct ConsentSection: View {
    let title: String
    /// 不能叫 `body`：会与 View 协议的 `body` 冲突（Invalid redeclaration of 'body'）。
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.footnote.weight(.semibold))
            Text(text).font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct ConsentCheckbox: View {
    @Binding var isOn: Bool
    let text: String

    var body: some View {
        Toggle(isOn: $isOn) {
            Text(text).font(.subheadline)
        }
        .toggleStyle(.switch)
    }
}

/// 同意状态：只记「是否已同意 + APRS 公开是否勾选 + 时间」，不存任何个人信息。
final class PrivacyConsentStore: ObservableObject {

    static let shared = PrivacyConsentStore()

    private let defaults = UserDefaults.standard
    private let acceptedKey = "hamkit.privacy.consent.accepted"
    private let aprsPublicKey = "hamkit.privacy.consent.aprs_public"
    private let acceptedAtKey = "hamkit.privacy.consent.accepted_at"

    @Published private(set) var accepted: Bool
    /// 是否允许在开启 APRS 上报时公开位置（默认关闭）。
    private(set) var aprsPublicAllowed: Bool

    private init() {
        accepted = defaults.bool(forKey: acceptedKey)
        aprsPublicAllowed = defaults.bool(forKey: aprsPublicKey)
    }

    func accept(aprsPublic: Bool) {
        aprsPublicAllowed = aprsPublic
        defaults.set(true, forKey: acceptedKey)
        defaults.set(aprsPublic, forKey: aprsPublicKey)
        defaults.set(Date().timeIntervalSince1970, forKey: acceptedAtKey)
        accepted = true
    }

    /// 撤回同意（设置页「撤回隐私同意」用）：清掉标记，下次启动重新走同意页。
    func revoke() {
        defaults.removeObject(forKey: acceptedKey)
        defaults.removeObject(forKey: aprsPublicKey)
        defaults.removeObject(forKey: acceptedAtKey)
        aprsPublicAllowed = false
        accepted = false
    }
}
