# iosApp —— HamKit iOS 客户端

SwiftUI 外壳 + Kotlin Multiplatform 内核。业务算法（SGP4/SDP4、TLE、CW、FT8、SSTV、HF Fax、APRS 报文、QSO、AI 引擎）全部在仓库根目录的 `:shared` 里，Android 与 iOS 跑同一份代码；Swift 侧只负责界面与平台能力（定位、音频、通知、网络、地图、Keychain）。

> 状态：**测试版**（`ios` 分支 @ `5b22dc76`，2026-11-03）。
> 功能已对齐 Android 的主要模块，没有 App Store / TestFlight（项目无收入，买不起开发者账号），
> 以**未签名 IPA** 分发，需自行用 Apple ID 重签。

## 分支与维护

- **Android 与 iOS 目前分两条分支维护**：Android 走默认分支 `miuix`，iOS 走 `ios`；
- **`:shared` 内核目前只存在于 `ios` 分支**。主分支 `miuix` 仍使用原有实现，不依赖 `:shared`。
  - 原因：共享内核还在验证期，可能存在不稳定性；Android 是主力端，优先保证它的体验。
  - 代价：两端的算法暂时存在两份实现，修复需要在两条分支间同步（cherry-pick / 手工对齐）。
    这是**临时状态**，不是长期方案。
- 计划在**合适的时间点**把 `ios` 合并回主干。合并的前提是这套共享内核经过足够的真机验证；
- 因此 `ios` 分支不等于发布分支，正式版仍以主干为准。

## 已实现

**定位与卫星**

- 一键定位 → CQ Zone / ITU Zone / 6 位梅登海德网格；定位失败静默重试
- TLE 三源合并（amateur + satnogs + active，1.6 万+ 颗），自建 CDN 优先、CelesTrak 兜底
- TLE、SatNOGS 转发器都落盘缓存，冷启动先渲染缓存再后台刷新；断网可用
- 首页：时间卡（天气 / UTC / 每日一言）、附近过境、功能入口
- 卫星管理：搜索、统计、筛选（模式 / 分类 / 在境 / AMSAT）、分类体系、来源标记
- 单星详情：实时面板、过境倒计时、多普勒上下行、转发器列表、日照判定、SSTV 入口
- Look4Sat 风格极坐标雷达（Canvas）+ MapKit 地面轨迹地图（可拖动缩放）
- 收藏、过境本地提醒（提前量可配）、后台刷新

**数字模式与图像**

- CW：字符 / 呼号 / 文本练习，节拍时间线 + AVAudioEngine 合成
- FT8：编解码 + LDPC/GFSK，自动 CQ、自动应答、自动记 QSO
- SSTV：接收（逐行预览、图库）、发射（相册 / 图库选图、进度、导出 WAV）、音频文件导入解码
- HF Fax：天气传真接收解码

**日志与网络**

- APRS：APRS-IS 连接（自动重连、连接日志、服务器原因透出）、站点 / 消息、位置上报、符号选择、站点地图
- QSO 日志：记录、编辑、ADIF / ADX 导出

**其它**

- 天气（高德 Web 服务，Key 构建时注入）+ 每日一言 + UTC
- 主题：深浅模式 + 9 档强调色，Miuix 风格卡片
- AI 助手：OpenAI / Anthropic / Gemini 三协议，SSE 流式，Key 存 Keychain，可查卫星 / FT8 / APRS / QSO
- 首启隐私同意、权限管理页、撤回同意

## 架构

```
iosApp/ (SwiftUI)                         shared/ (Kotlin Multiplatform)
├─ Views / 路由 / 状态层        <—>      ├─ data/satellite   SGP4-SDP4 / TLE / 过境 / 太阳位置
├─ 平台能力：                    ├─ data/ft8、sstv      协议与 DSP
│  CoreLocation / AVAudioEngine  ├─ shared/Aprs、Qso、MorseCode
│  UNUserNotificationCenter      ├─ shared/ai          AI 引擎、工具、SSE
│  URLSession / MapKit           └─ shared/net         HttpTransport（平台注入）
├─ 缓存：TLE / 转发器 JSON
└─ MapKit / Keychain
```

约定：

- **算法只写一遍**：能进 `:shared` 的逻辑不留在 Swift / Kotlin-Android，避免两端结果漂移；
- **网络由平台注入**：`HttpTransport` 由 iOS 的 `URLSessionTransport` 实现，TLS / ATS / 代理走系统默认；
- **时间统一 Long 毫秒**，跨语言不传日期类型；异常不跨 Kotlin/Native 边界（失败用 null / 错误字段表达）；
- **单一数据层**：`SatelliteStore` 持有目录 / 筛选 / 分类，`TrackerModel` 负责定位、过境、提醒；
- 页面跳转只用一套 `navigationDestination(for: Route.self)`，避免视图式与值式导航混用。

## 目录

| 模块 | 文件 |
| --- | --- |
| 入口 / 导航 | `HamKitApp.swift`、`AppRootView.swift`、`Routes.swift`、`RouteDestination.swift` |
| 首页 | `ContentView.swift`、`WeatherStore.swift`、`DailyQuoteStore.swift`、`AmapKey.swift` |
| 卫星 | `TrackerModel.swift`、`SatelliteStore.swift`、`SatelliteManagementView.swift`、`SatelliteDetailScreen.swift`、`SatellitePages.swift`、`SatelliteFilterView.swift`、`SatelliteCategoryManager.swift`、`SatelliteCategoryViews.swift`、`SatelliteMapView.swift`、`RadarView.swift` |
| CW | `CwViews.swift`、`MorsePlayer.swift` |
| FT8 | `Ft8Views.swift`、`Ft8Audio.swift`、`Ft8LogStore.swift` |
| SSTV / HF Fax | `SstvViews.swift`、`HfFaxView.swift` |
| APRS | `AprsClient.swift`、`AprsViews.swift` |
| QSO | `QsoStore.swift`、`QsoViews.swift` |
| AI 助手 | `AiChatModel.swift`、`AiTransport.swift`、`AiFacade.swift`、`AiViews.swift`、`AiSettingsView.swift`、`AiSettingsStore.swift` |
| 设置 / 主题 / 合规 | `SettingsView.swift`、`ToolsView.swift`、`MiuixTheme.swift`、`LiquidGlass.swift`、`PrivacyConsentView.swift` |
| 平台能力 | `LocationProvider.swift`、`Networking.swift`、`AudioSession.swift`、`NotificationManager.swift`、`BackgroundReminderScheduler.swift` |
| 缓存 | `TleDiskCache.swift`、`TransmitterDiskCache.swift` |

`Routes.swift` 里的 `Route` 与 Android `Routes.kt` 一一对应（首页、卫星管理 / 详情 / 雷达 / 地图 / 筛选 / 分类、APRS 六个页面、FT8、SSTV、HF Fax、CW、QSO、AI、设置类页面）。

## 构建

前置（必须在 macOS 上）：

- macOS 14+ / Xcode 16+（要启用 iOS 26 的 Liquid Glass 需要 Xcode 26+；旧版 Xcode 仍可构建，但会退回半透明材质）
- JDK **21**（`brew install --cask temurin@21`）
- [XcodeGen](https://github.com/yonaskolb/XcodeGen)：`brew install xcodegen`
- 首次构建会下载 Kotlin/Native 编译器与 Apple 平台依赖（约 1–2 GB）

```bash
cd iosApp
xcodegen generate
open HamKit.xcodeproj      # 选 Apple Silicon 模拟器或真机，⌘R
```

Xcode 的 Run Script 会执行 `./gradlew :shared:embedAndSignAppleFrameworkForXcode`。
脚本会自己解析 JDK 21 并校验 `Shared.framework` 是否真的产出——Xcode GUI 的脚本只继承
`/usr/bin:/bin:/usr/sbin:/sbin`，终端里 `export JAVA_HOME` 传不进去，缺 JDK 时 Swift 只会报
`Unable to resolve module dependency: 'Shared'`，很难定位。

真机 / Release 还要多看一眼 Run Destination：`Shared.framework` 是按
`$(CONFIGURATION)/$(SDK_NAME)` 分开生成的（`shared/build/xcode-frameworks/...`）。
刚切到真机、或还没为这个 SDK 构建过时，Xcode 的实时索引会对所有 `import Shared` 报
`Unable to resolve module dependency: 'Shared'`——先在该 destination 上 Build 一次，
Run Script 会生成对应 SDK 的 framework，报错即消失；只想跑模拟器的话把 destination 切回模拟器即可。

也可以先在命令行验证 framework：

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
# 产物：shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework
```

### Liquid Glass（iOS 26）

iOS 26 的新外观只对**用 iOS 26 SDK 构建**的应用生效；而且系统只会处理
`TabView` / `NavigationStack` 等标准容器自带的栏，**UIKit / SwiftUI 不会给自定义视图
自动注入 Liquid Glass**。本项目的处理方式：

- **标准栏**：交给系统。用 Xcode 26+ 构建后，底部标签栏、导航栏自动获得 Liquid Glass，
  不需要额外代码；
- **自定义浮层**：地图信息卡、「回到卫星」「框选全部」等悬浮按钮，显式调用
  `LiquidGlass.swift` 里的 `.liquidGlass(...)` / `.liquidGlassCircle(...)`；
- **兼容**：iOS < 26、用旧 SDK（Swift < 6.2）构建，或用户在
  「设置 → 主题配色 → 液态玻璃」里关掉开关时，自动退回 `.ultraThinMaterial`，
  旧系统与新系统都能正常显示；
- **不要**在 `Info.plist` 里加 `UIDesignRequiresCompatibility`——那个键会让 App
  整体退回 iOS 18 的旧外观，等于主动放弃 Liquid Glass。

用 Xcode 16 构建也能通过编译（原生 API 由 `#if compiler(>=6.2)` 保护），但产物里
只有材质兜底。CI 会选最新稳定版 Xcode，确保分发的未签名 IPA 带原生玻璃。

### 高德 Web Key

天气与逆地理编码走高德 Web 服务，**Key 不写进源码**：`Info.plist` 里是
`$(AMAP_WEB_KEY)`，构建时注入。

```bash
xcodebuild ... AMAP_WEB_KEY=<你的 Key>
AMAP_WEB_KEY=<你的 Key> ./scripts/export-unsigned-ipa.sh
```

Xcode GUI：target → Build Settings → 搜 `AMAP_WEB_KEY` 填入（改的是本地生成的工程，
重新 `xcodegen generate` 后要重填）。CI 读仓库 Secret `AMAP_WEB_KEY`，未配置时首页提示
「未配置高德 Web Key」，其余功能不受影响。

### 权限

`Info.plist` 已声明：定位、麦克风（CW / FT8 / SSTV / HF Fax）、相册写入（保存解码图片）。
首次使用对应功能时弹窗；音频只在本机解码，不上传。

## 未签名 IPA（没有开发者账号怎么装）

项目没有 Apple Developer 账号，所以**不提供 App Store / TestFlight**。CI 会构建一份
标准结构的未签名 IPA（`Payload/HamKit.app`，附 SHA-256）。

### 自己导出

```bash
./iosApp/scripts/export-unsigned-ipa.sh          # Release，默认
./iosApp/scripts/export-unsigned-ipa.sh --debug  # Debug
```

产物：`iosApp/HamKit-unsigned.ipa` + `.sha256`，日志在仓库根目录 `ios-device-build.log`。
脚本与 CI 同一套流程：`xcodegen generate` → `xcodebuild -sdk iphoneos`（`ARCHS=arm64`，
`CODE_SIGNING_ALLOWED=NO`）→ `Payload/` → zip。

> 没有命令行也可以走 Xcode 图形界面：Scheme 选 `HamKit`，运行目标 **Any iOS Device (arm64)**，
> Signing 全部设为 None，`Product → Build`（**不要用 Archive**，未签名 archive 无法 Export），
> 再到 DerivedData 里把 `HamKit.app` 放进 `Payload/` 压缩、改名 `.ipa`。

### 安装

1. 电脑装 [Sideloadly](https://sideloadly.io/) 或 [AltStore](https://altstore.io/)；
2. 用自己的 Apple ID 重签安装——免费账号 **7 天有效**，到期重签，App 数据不丢；
3. 有付费证书的，重签一次可长期使用；
4. TrollStore 只支持特定系统版本，不保证可用。

### 报错「无法安装此 App，因为无法验证其完整性」

iOS 校验证书链失败时的统一提示，与 App 内容无关：

| 情况 | 处理 |
| --- | --- |
| 直接把未签名 IPA 拖进 Finder / 用「文件」App 打开 | 不能直接装，必须用 Sideloadly / AltStore 重签 |
| 用了几天忽然打不开 | 免费证书 7 天到期，重新签一次 |
| 同 Bundle ID 的旧版本、证书不同 | 先删掉设备上的旧 HamKit 再装 |
| 设备日期 / 时间不对 | 设置 → 通用 → 日期与时间 → 自动设置 |
| Sideloadly 过旧 / 没装命令行工具 | 更新 Sideloadly；`xcode-select --install` |
| iOS 18+ 首次装自签 App | 设置 → 隐私与安全性 → 开发者模式；或在「VPN 与设备管理」信任证书 |

**最省事**：手机连 Mac，Xcode 里选自己的 Apple ID，⌘R 直接装，签名续签都交给 Xcode。

> 只从官方仓库 <https://github.com/fuxue-linkong/HamKit> 和官网 <https://hamkit.click> 下载。
> 不要用来历不明的「企业证书」重签——证书被吊销后 App 直接打不开。

## 测试与 CI

- 共享内核单测：`./gradlew :shared:jvmTest`（AI 协议 / 工具、SGP4、TLE、APRS、QSO 等）
- CI：`.github/workflows/ios.yml`
  - `linkDebugFrameworkIosSimulatorArm64` 链接内核
  - `xcodebuild` 编译模拟器包（`ARCHS=arm64`，无需签名）
  - 启动模拟器 → 安装 → 授权定位 → 启动 → 截图（`-smokeManage` / `-smokeDetail` 可自动进页面）
  - 构建 device 包并打包未签名 IPA + SHA-256

## 已知限制

- **没有「播放捕获」**：Android 10+ 可直接录其它 App 正在播放的音频，iOS 无对应能力，
  SSTV 只能用麦克风或导入音频文件。
- **后台受限**：APRS 长连接、SSTV 长时间接收在切后台后可能被系统挂起；接了 `BGTaskScheduler`，
  但 iOS 的后台策略比 Android 严，做不到常驻。
- **无推送**：只有本地通知（过境提醒）。
- **首次启动慢**：要拉约 2.6 MB 的 active 全量目录，弱网下首页「附近过境」要等；之后有缓存。
- **Intel 模拟器未配置**：只声明了 `iosArm64` / `iosSimulatorArm64`，需要时在
  `shared/build.gradle.kts` 加 `iosX64()`。
- **iPad 未专门适配**：沿用 iPhone 布局自适应，可能出现挤压。
- **签名 7 天**：免费 Apple ID 的限制，与 App 无关。

## 相关文档

- [docs/IOS_ADAPTATION.md](../docs/IOS_ADAPTATION.md)：迁移路线、平台能力替换矩阵、阶段计划
- [docs/UPSTREAM_SYNC_2026-10-31.md](../docs/UPSTREAM_SYNC_2026-10-31.md)、
  [docs/UPSTREAM_SYNC_2026-11-01.md](../docs/UPSTREAM_SYNC_2026-11-01.md)：与上游 miuix 的同步记录
- [README.md](../README.md)：项目总览（Android + 跨平台内核）
