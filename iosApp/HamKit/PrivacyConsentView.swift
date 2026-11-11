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
    @State private var agreementAccepted = false
    @State private var locationAccepted = false
    @State private var aprsPublicAccepted = false
    @State private var showPolicy = false
    @State private var showAgreement = false
    @State private var declined = false

    /// 老用户（已同意 v1 隐私政策）补看 v2 协议：复用本页，不再重复介绍功能。
    var isAgreementUpdate = false

    var body: some View {
        ZStack {
            MiuixTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if isAgreementUpdate {
                        Text("协议更新")
                            .font(.title2.weight(.semibold))
                        Text("用户协议与隐私政策已更新到 v2。继续使用前请阅读并同意新版本；不同意时我们不会收集任何个人信息。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
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
                    }

                    Divider()

                    ConsentCheckbox(
                        isOn: $policyAccepted,
                        text: "我已阅读并同意《隐私政策》"
                    )
                    ConsentCheckbox(
                        isOn: $agreementAccepted,
                        text: "我已阅读并同意《用户协议》"
                    )
                    if !isAgreementUpdate {
                        ConsentCheckbox(
                            isOn: $locationAccepted,
                            text: "我同意处理我的精确位置信息（敏感个人信息）"
                        )
                        ConsentCheckbox(
                            isOn: $aprsPublicAccepted,
                            text: "我同意在开启 APRS 上报时公开我的位置（可选）"
                        )
                    }

                    HStack(spacing: 18) {
                        Button("查看完整隐私政策") { showPolicy = true }
                        Button("查看《用户协议》") { showAgreement = true }
                    }
                    .font(.subheadline)

                    Button(isAgreementUpdate ? "同意新版协议并继续" : "同意并继续") {
                        onAgree(aprsPublicAccepted)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!(policyAccepted && agreementAccepted && (isAgreementUpdate || locationAccepted)))

                    if !isAgreementUpdate {
                        Button("不同意") { declined = true }
                            .font(.subheadline)
                    }

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
        .sheet(isPresented: $showAgreement) {
            NavigationStack {
                UserAgreementView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("关闭") { showAgreement = false }
                        }
                    }
            }
        }
    }
}

/// 用户协议（对齐 miuix `004dfe6` 新增的用户协议；正文与 Android assets 同源）。
private struct UserAgreementView: View {

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("HamKit 用户协议（v2）")
                    .font(.title3.weight(.semibold))
                Text("更新日期：2026-11-08")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                agreementSection(
                    "一、协议的接受",
                    "欢迎使用 HamKit（下称“本应用”）。请在使用前阅读本协议。你勾选同意、或实际使用本应用，即视为已阅读并同意本协议全部内容。"
                )
                agreementSection(
                    "二、应用性质",
                    "本应用是一款业余无线电工具箱，提供双区定位、卫星过境预测、SSTV 收发、HF Fax、CW 摩尔斯、FT8、APRS 与 QSO 日志等功能，全部计算在你的设备本地完成。"
                )
                agreementSection(
                    "三、业余无线电合规",
                    "你承诺在合法合规的前提下使用本应用：持有相应执照/操作证，遵守所在地无线电管理规定与《业余无线电管理办法》，不得用于商业广播、不得干扰其他业务、不得发送违法信息。APRS 上报内容公开可见，请自行把握披露范围。"
                )
                agreementSection(
                    "四、AI 助手服务",
                    "AI 助手需要你自行配置第三方模型服务商的 API Key。对话内容会发送给你配置的服务商并由其处理，请勿在对话中输入敏感个人信息。本应用不对 AI 生成内容的准确性作任何担保，涉及频率、操作规程等专业内容请自行核实。"
                )
                agreementSection(
                    "五、数据与隐私",
                    "本应用不内置任何数据上报；定位、日志与对话记录仅保存在本机。详细了解处理规则请阅读《隐私政策》。"
                )
                agreementSection(
                    "六、免责声明",
                    "本应用按“现状”提供。卫星过境预测、TLE 数据、转发器频率等来自第三方数据源，可能存在误差；在涉及设备安全、频谱合规的操作中，请以官方数据与法规为准。对于因使用本应用而产生的任何直接或间接损失，开发者不承担责任。"
                )
                agreementSection(
                    "七、协议变更",
                    "协议如有重大变更，会在应用内提示并请你重新确认。继续使用即视为接受更新后的协议。"
                )
                agreementSection(
                    "八、联系我们",
                    "如有问题或建议，请联系 liurun637@gmail.com。"
                )
            }
            .padding(20)
        }
        .navigationTitle("用户协议")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func agreementSection(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.footnote.weight(.semibold))
            Text(text).font(.caption).foregroundStyle(.secondary)
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

/// 同意状态：版本 + 「是否已同意 + APRS 公开是否勾选 + 时间」，不存任何个人信息。
///
/// v2（对齐 miuix `004dfe6`）：新增《用户协议》。
/// 老用户（已同意 v1）升级后只弹「协议更新」页，不重复介绍功能。
final class PrivacyConsentStore: ObservableObject {

    static let shared = PrivacyConsentStore()

    /// 与协议文案的版本保持一致；升版后老用户需要重新确认。
    static let currentVersion = 2

    private let defaults = UserDefaults.standard
    private let acceptedKey = "hamkit.privacy.consent.accepted"
    private let versionKey = "hamkit.privacy.consent.version"
    private let aprsPublicKey = "hamkit.privacy.consent.aprs_public"
    private let acceptedAtKey = "hamkit.privacy.consent.accepted_at"

    @Published private(set) var accepted: Bool
    /// 是否允许在开启 APRS 上报时公开位置（默认关闭）。
    private(set) var aprsPublicAllowed: Bool
    /// 老用户（同意过 v1）需要补看新版协议。
    private(set) var needsAgreementUpdate: Bool

    private init() {
        let acceptedV1 = defaults.bool(forKey: acceptedKey)
        let version = defaults.integer(forKey: versionKey)
        aprsPublicAllowed = defaults.bool(forKey: aprsPublicKey)
        accepted = acceptedV1 && version >= Self.currentVersion
        needsAgreementUpdate = acceptedV1 && version < Self.currentVersion
    }

    func accept(aprsPublic: Bool) {
        defaults.set(true, forKey: acceptedKey)
        defaults.set(Self.currentVersion, forKey: versionKey)
        defaults.set(aprsPublic, forKey: aprsPublicKey)
        defaults.set(Date().timeIntervalSince1970, forKey: acceptedAtKey)
        aprsPublicAllowed = aprsPublic
        needsAgreementUpdate = false
        accepted = true
    }

    /// 撤回同意（设置页「撤回隐私同意」用）：清掉标记，下次启动重新走同意页。
    func revoke() {
        defaults.removeObject(forKey: acceptedKey)
        defaults.removeObject(forKey: versionKey)
        defaults.removeObject(forKey: aprsPublicKey)
        defaults.removeObject(forKey: acceptedAtKey)
        aprsPublicAllowed = false
        needsAgreementUpdate = false
        accepted = false
    }
}
