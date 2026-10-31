# HamKit iOS 适配方案（Kotlin Multiplatform + SwiftUI 壳）

> 状态：**P0 完成**（跨平台内核抽取 + iOS 壳工程可运行骨架）。
> 本文记录取舍、已完成的部分、平台能力替换清单与后续阶段，供排期与 code review 使用。

## 1. 结论先行

HamKit 目前是纯 Android 工程：267 个 Kotlin 文件、Jetpack Compose UI，
深度依赖高德 3D 地图、Android `AudioTrack`/`AudioRecord`、`AlarmManager`/`WorkManager`、
Room、OkHttp 等 Android-only 组件。

因此 iOS 适配没有走「照抄一份 Swift」的路线（两套算法必然漂移，且社区反馈里
[SSTV / FT8 这类 DSP 逻辑](../README.md)加起来近万行，重写成本极高），而是：

```
        ┌──────────────────────────────────────────┐
        │  :app  (Android)   Compose / 平台能力     │
        ├──────────────────────────────────────────┤
        │  :shared (KMP)     commonMain 领域内核    │  ← 分区定位 / SGP4-SDP4 / 过境搜索
        │                    androidMain / iosMain  │
        └──────────────────────────────────────────┘
                    ▲                      ▲
                    │                      │
          HamKit（Android）        HamKit（iOS, SwiftUI）
```

- **业务与算法只有一份**（Kotlin，`commonMain`）；
- **UI 与平台能力各写一套**（Compose / SwiftUI、CoreLocation / AMap……）；
- 不在本轮引入 Compose Multiplatform：现有 UI 大量使用 Material3 Expressive、
  Miuix、Navigation3、RuntimeShader 等，迁移 UI 到 CMP 的收益/风险比远低于先打通内核。

## 2. 本次已完成（P0）

### 2.1 新增 `:shared` 模块

`shared/build.gradle.kts`：

| 目标 | 用途 |
|---|---|
| `androidLibrary` | 被 `:app` 以 `implementation(project(":shared"))` 依赖 |
| `jvm()` | 在 Windows / Linux CI 上也能编译校验 `commonMain`，无需 macOS |
| `iosArm64()` | iPhone 真机 |
| `iosSimulatorArm64()` | Apple Silicon 模拟器 |
| `Shared.framework`（static） | Swift 侧 `import Shared` |

> 注意：iOS 目标只能在 macOS 上**链接**（Kotlin/Native 对 Apple 平台的限制）。
> 在 Windows 上声明目标是安全的，`*IosArm64` 任务会被自动禁用。

### 2.2 迁入 `commonMain` 的领域代码

| 文件 | 说明 |
|---|---|
| `data/ZoneInfo.kt` | 分区结果模型（移除 `@Immutable`，见 2.4） |
| `data/zone/MaidenheadCalculator.kt` | 6 位网格正/反算 |
| `data/zone/ZoneResolver.kt` | CQ / ITU 分区（包围盒 + 经度兜底） |
| `data/satellite/predict/OrbitMath.kt` | 儒略日、GMST、ECI/ECF/大地坐标 |
| `data/satellite/predict/Sgp4Constants.kt` | WGS-72 常数 |
| `data/satellite/predict/Sgp4Satellite.kt` | 自研 SGP4/SDP4 引擎（1296 行） |
| `data/satellite/predict/TleElements.kt` | TLE 定长解析 |
| `data/satellite/predict/SatellitePropagator.kt` | 给定时刻 → 方位/仰角/星下点 |
| `data/satellite/DopplerCalculator.kt` | 多普勒频移 |
| `data/satellite/SatelliteCatalog.kt` | NORAD ↔ 模式 / AMSAT 名称映射 |
| `data/satellite/SatelliteModes.kt` | 模式常量 |

新增：

| 文件 | 说明 |
|---|---|
| `data/satellite/pass/PassFinder.kt` | 过境搜索（粗扫 5 min + 二分法精化 + 最大仰角精化），从 `SatellitePredictor` 抽出 |
| `shared/HamKitApi.kt` | **Swift 唯一入口**：只暴露 String/Double/Int/Long/Bool 与三个 data class |
| `shared/HamKitCore.kt` | 版本号 / 冒烟检查 |

### 2.3 平台无关化改造

| 改造 | 原因 | 影响面 |
|---|---|---|
| `java.lang.Math.*` → `kotlin.math.*` | `java.*` 在 Kotlin/Native 不存在 | 5 个文件、85 处；纯机械替换 |
| `java.time.Instant` → `Long`（UTC 毫秒） | 同上；且跨平台用统一时间表示最简单 | `TleElements.epoch` → `epochMillis`；`PropagatedPosition.time` 删除（原本无人使用） |
| `@Immutable` 从 `ZoneInfo` 移除 | `androidx.compose.runtime` 不属于内核 | `data class` + 全 `val` 的稳定类型，Compose 仍能推断为 stable |
| `SatellitePredictor` 复用 `PassFinder` | 避免 Android / iOS 两套过境算法 | 删掉 170 行私有实现，行为由既有单测 + 差分测试锁定 |
| `internal` → `public`（`Sgp4Satellite` / `EciState` / `ParsedTleElements` / `toParsedElements`） | `internal` 不跨模块，app 侧差分测试需要访问 | 作为内核的低层 API 公开，已在 KDoc 说明 |

### 2.4 时间表示的统一约定

内核只认 **UTC 毫秒（`Long`）**：

```kotlin
// Android
val instant = Instant.ofEpochMilli(pos.timeMs)
// Swift
let date = Date(timeIntervalSince1970: Double(pos.timeMs) / 1000)
```

这样 `commonMain` 既不需要 `kotlinx-datetime`，也不会把某个平台的日期库泄漏到 API 上。

### 2.5 iOS 壳工程

```
iosApp/
├── project.yml              # XcodeGen（.xcodeproj 不入库）
└── HamKit/
    ├── HamKitApp.swift
    ├── ContentView.swift    # 地面站 / 分区 / 卫星数据 / 实时位置 / 过境
    ├── TrackerModel.swift
    ├── LocationProvider.swift
    ├── Networking.swift     # URLSession 实现共享内核的 HttpTransport
    └── Info.plist
```

构建期由 Run Script 调用 `./gradlew :shared:embedAndSignAppleFrameworkForXcode`，
`FRAMEWORK_SEARCH_PATHS` 指向 `shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`。
详见 [iosApp/README.md](../iosApp/README.md)。

### 2.6 网络拉取（TLE）

iOS 端已经不再使用硬编码 TLE，改为运行时拉取：

| 层次 | 位置 | 职责 |
|---|---|---|
| `HttpTransport` | `commonMain/net` | 只定义 `get(url, timeoutMs, callback)`，用值表达失败，不抛异常 |
| `TleClient` | `commonMain/tle` | 按顺序尝试 **自建 CDN → CelesTrak 官方**，任一成功即返回 |
| `TleTextParser` | `commonMain/tle` | 解析 3 行 TLE（含空行、缺名称、重复 NORAD、坏行跳过） |
| `TleRepository` | `commonMain/tle` | **按分组**缓存（amateur / satnogs / active 各自驻留）6 小时；失败回退上次成功数据（离线可用）；active 单独 90s 超时 |
| `URLSessionTransport` | `iosMain`（Swift） | 唯一平台实现：`URLSession` + 主线程回调 |
| `HamKitApi.fetchCatalog` | `commonMain` | 对外入口：三源合并（amateur → satnogs → active），分段回调 |
| `HamKitApi.fetchTles` | `commonMain` | 只拉单个分组（调试/备用入口） |

几条设计约定：

- **不用 Ktor**：只发一个 GET，平台原生网络栈更贴合 ATS/代理/证书策略，也避免多一份依赖；
- **不让 commonMain 直接发请求**：`HttpTransport` 由平台注入（`installHttpTransport`），
  这样 iOS 的网络行为完全由系统控制；
- **数据源与 Android 一致**：`amateur.3le` + `satnogs.3le` + `active.3le`（1.6 万+ 颗）
  按 NORAD 合并去重，先到者优先、active 只补全缺失项；active 解析结果低于 1 万条
  视为源被截断，直接丢弃（对齐 Android `MIN_ACTIVE_TLE_COUNT`）；
- **分段回调**：业余源到手就先回调一次（`catalogComplete = false`），首页「附近过境」
  立刻可用；active 合并完再回调一次全量，卫星管理页才需要它；
- **过境只算业余星**：`SatelliteStore` 展示全量目录，但 `searchNearbyPasses` 只对
  `TrackerModel.amateurSatellites` 计算——既与 Android 语义一致，也避免 1.6 万颗 ×
  48 小时把首页拖到几十秒；
- **失败可读**：任何一层都不抛异常，UI 拿到的永远是「数据 + 可选错误文案」。

## 3. 验证结果（本次）

在 Windows（无 macOS）下完成的验证：

| 检查 | 命令 | 结果 |
|---|---|---|
| 内核可跨平台编译 | `:shared:compileAndroidMain`（由 `:app` 依赖触发） | 通过 |
| Android 全量编译 | `:app:compileDebugKotlin` | 通过 |
| 单元测试编译 | `:app:compileDebugUnitTestKotlin` | 通过 |
| 定位 / 卫星用例 | `:app:testDebugUnitTest --tests "*zone.*" "*predict.*" "*PredictorGeo*"` | 57 个用例全绿 |
| 网络 / TLE 用例 | `:app:testDebugUnitTest --tests "com.example.hamkit.shared.*"` | 21 个用例全绿（解析 / 多源回落 / 缓存 / 轨迹 / 入口） |
| APK 打包 | `:app:assembleDebug` | 通过（`app/build/outputs/apk/debug/HamKit_4.1.0_17-debug.apk`） |
| iOS 编译 + 模拟器冒烟 | GitHub Actions `macos-15`（`.github/workflows/ios.yml`） | 通过：编译 → 装模拟器 → 启动 → 两张截图 |

其中最关键的是 `SatellitePropagatorDifferentialTest`：用 predict4java 作为参考实现，
在 LEO 与 GEO 两条路径上校验 `Math.* → kotlin.math.*` 改造后数值未漂移
（星下点 < 0.5°、方位/仰角 < 1°、斜距相对误差 < 2%）。

> `:shared:compileKotlinJvm` 在**完全离线**且本地缓存缺少
> `kotlin-scripting-compiler-embeddable` 的机器上会失败（KMP 默认给每个
> compilation 挂 Kotlin 脚本编译器插件）。这是环境限制，不是代码问题：
> 联网构建无需任何额外参数；离线机器可加 `-PhamkitOfflineBuild=true` 跳过该依赖。

## 4. 平台能力替换矩阵

| 能力 | Android 现状 | iOS 方案 | 阶段 |
|---|---|---|---|
| 定位 | Play Services Location + `LocationHelper` | **CoreLocation**（壳内已实现最小版）；精度筛选 / 超时兜底待补 | P1 |
| 地图 | 高德 3D SDK + `AMapCard` | 高德 iOS SDK（与现有 Key 体系一致）或 MapKit（免 key，但国内数据一般） | P4 |
| 逆地理 / 天气 | 高德 Web 服务（OkHttp） | 同一套 HTTP 接口，网络层换 Ktor Darwin 或 URLSession | P2 |
| 网络 | OkHttp 5 | **URLSession**（已实现，经 `HttpTransport` 注入给共享内核）；其余接口迁移见 P2 | P1 ✅ |
| 本地存储 | Room 2.7 + DataStore | Room KMP（2.7 起支持 KMP）或 SQLDelight；DataStore KMP（Preferences） | P2 |
| 音频采集/播放 | `AudioRecord` / `AudioTrack` / `MediaCodec` | **AVAudioEngine + AVAudioSession**；`AVAudioConverter` 做重采样 | P3 |
| 后台持续接收 | 前台服务（`SstvCaptureService`） | `UIBackgroundModes: audio` + 后台音频；**没有** Android 那种常驻前台服务 | P3 |
| 定时提醒 | `AlarmManager` 精确闹钟 + `WorkManager` | `UNUserNotificationCenter`（本地通知）+ `BGTaskScheduler`（刷新 TLE） | P5 |
| 通知权限 | `POST_NOTIFICATIONS` | `UNUserNotificationCenter.requestAuthorization` | P5 |
| 分享 / 导出 | `FileProvider` + `Intent.ACTION_SEND` | `UIActivityViewController` + `UIDocumentPicker` | P3 |
| 相册 | MediaStore 写图 | `PHPhotoLibrary`（需 `NSPhotoLibraryAddUsageDescription`） | P3 |
| 应用内更新 | 下载 APK + `ApkVerifier` | **移除**，改走 App Store / TestFlight | P6 |
| 动态取色 | MaterialKolor（Palette 取色） | 自研取色（CoreImage / vImage）+ SwiftUI 主题色 | P5 |
| Markdown / 隐私页 | WebView + commonmark | `WKWebView` 或 swift-markdown-ui | P5 |
| 图表 | MPAndroidChart | Swift Charts（iOS 16+） | P5 |
| 图片裁剪 | Android-Image-Cropper | 自研 SwiftUI 裁剪（或 TOCropViewController） | P3 |

## 5. 分阶段路线

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **P0（本次）** | 内核抽取 + iOS 壳骨架 | Android 构建/测试全绿；iOS 工程能在 macOS 上跑通首屏 |
| P1（进行中） | 定位权限与流程、TLE 网络拉取、卫星列表、雷达图、过境 | 已实现 TLE 拉取（CDN→CelesTrak 回落）+ 6h 缓存 + 卫星列表页 + Look4Sat 风格雷达（轨迹来自 `HamKitApi.satelliteTrack`）+ 定位失败静默重试；待补详情页与 macOS 实机验证 |
| P2 | 网络层（Ktor）、持久化（DataStore/Room KMP）、设置页 | 重启后设置与收藏不丢 |
| P3 | 音频：CW 播放、SSTV 解码/编码、FT8、WEFAX | 用真机 + 电台/录音回放验证解码结果与 Android 一致 |
| P4 | APRS、QSO 日志与 ADIF 导出、地图 | 与 Android 端数据格式互通（ADIF / APRS-IS） |
| P5 | 过境提醒、首页时间卡、主题、关于页 | 通知在 AOS 前准时触发（含后台） |
| P6 | TestFlight、隐私清单（Privacy Manifest）、上架素材 | 通过 App Store 审核 |

> 建议 P1/P2 先合入主干，再决定是否投入 P3：SSTV/FT8 的 iOS 音频链路
> （尤其「播放捕获」在 iOS 上没有对应能力）是最大的不确定项，值得先做一次
> 技术验证再排期。

## 6. 开发约定（重要）

1. **`shared/src/commonMain` 禁止出现平台 API**：

   ```bash
   # CI 可以加这条守卫
   ! grep -rnE '^import (android|java|androidx)\.' shared/src/commonMain
   ```

   平台相关代码放 `androidMain` / `iosMain` / `jvmMain`，用 `expect/actual` 暴露。

2. **Swift 只调用 `HamKitApi`**：不要直接把 `SatellitePropagator` 等内部类型暴露给
   Swift，否则 Kotlin 的 `Int?` / `Array` / 异常语义会渗到 Swift 侧，后续改 API 会
   破坏兼容性。新增能力时在 `HamKitApi` 上加入口，并补 `HamKitApiTest`。

3. **跨语言异常**：Kotlin/Native 的异常跨到 Swift 会直接终止进程。`HamKitApi` 的
   公开方法要么返回 `null`/`Boolean`，要么标注 `@Throws`（`zone()` 属于后者）。

4. **时间**：内核 API 一律用 `Long`（UTC 毫秒），不要引入平台日期类型。

5. **算法只改一处**：Android 侧的 `SatellitePredictor` 已经改为调用 `PassFinder`；
   后续新增过境相关能力（如「连续过境」「可见性预报」）也应放内核。

## 7. 风险与待决事项

| 风险 | 说明 | 缓解 |
|---|---|---|
| 无 macOS 无法验证 iOS 链接 | 本次所有 iOS 代码未在 Xcode 编译过 | 交付后需在 macOS 上跑一次 `linkDebugFrameworkIosSimulatorArm64` + Xcode 构建 |
| 后台音频 / 持续接收 | iOS 对后台音频限制严格，SSTV「持续接收」体验可能与 Android 不同 | P3 先做技术验证（15 分钟连续接收 + 锁屏） |
| 播放捕获（Android 10+） | iOS 没有「录其它 App 音频」的公开能力 | iOS 端只保留麦克风 / 音频文件导入 |
| Kotlin/Native 包体积 | 静态 framework 会增大 IPA | 开启 `isStatic`（已开）、发布时 Strip、必要时按需拆模块 |
| 高德 iOS SDK 合规 | Key 需按 Bundle ID 申请；国内地图资质 | 与 Android 同一套账号，提前申请 |
| 双端 UI 漂移 | Compose 与 SwiftUI 两套实现 | 先把「数据/算法」统一（已完成），UI 差异接受；后续评估 Compose Multiplatform |

## 7.1 功能移植进度（按模块）

> 目标：页面与业务逻辑对齐 Android；共享逻辑优先放 `:shared`，页面用 SwiftUI 复刻。

| 模块 | 共享逻辑 | iOS 页面 | 状态 / 差异 |
|---|---|---|---|
| 卫星链路 | `HamKitApi`、`SatelliteCategory`、`SatnogsTransmitters`、`AmsatStatus` | 管理页、筛选、分类、详情、雷达、MapKit 地图、提醒 | ✅ 除 BJT 分段状态时间线外已对齐 |
| 设置 / 隐私 / 权限 / 关于 | — | 全部页面 | ✅ |
| CW | `MorseCode`（编解码、训练文本、节拍时间线） | 练习首页、自由练习、教程、编解码 | ✅ 音频用 AVAudioEngine；无「后台持续收听」 |
| APRS | `Aprs`（包格式化/解析） | 主页面、设置、站点、消息、MapKit 地图、符号选择 | ✅ 原始 TCP 用 Network.framework；站点/消息存 JSON 文件 |
| FT8 | `Ft8Protocol` / `Ft8Ldpc` / `Ft8Encoder` / `Ft8Decoder` / `Ft8Bridge` | 主页面、设置、自动应答状态机、AVAudioEngine 收发 | ⚠️ 自动应答（报告/RR73/记 QSO）已通；自动 CQ 与多候选择优未移植 |
| SSTV | 模式表/VIS/同步/FFT/解调/解码/编码（从 Android 移植） | 接收、音频文件解码、图库（分享/删除）、设置、发射 | ✅ 前台收发；后台接收受系统限制 |
| HF Fax | `HfFaxDecoder`（WEFAX 解调/解码） | 接收、解码、保存相册 | ✅ 仅前台接收 |
| QSO 日志 | `QsoRecord`、`QsoExporter`（ADIF 3.1.4 / ADX XML） | 列表、新增/编辑、删除、分享导出 | ✅ 持久化用 JSON 文件（Android 用 Room） |
| 天气 | `WeatherApi`（高德 Web 直连，Key 写死在 `AmapKey.swift`） | 首页时间卡天气 + 城市 | ✅ 与 Android 同源；Key 随 App 分发 |
| AI 助手 | `AiChatEngine` / 三协议适配 / SSE / 工具层 / 会话编解码（全部在 `commonMain`） | 对话页、历史抽屉、AI 设置 | ✅ P0+P1 核心：文本对话、工具调用（9 个）、副作用确认、历史与导出；缺图片/文件附件、Markdown 表格、思考过程展示 |

### iOS 无等价能力的近似方案

| Android 能力 | iOS 近似 |
|---|---|
| 前台服务 + 后台持续音频接收 | 仅前台接收；锁屏后由系统决定是否继续 |
| 「播放捕获」录其他 App 音频 | 仅麦克风 / 音频文件导入 |
| WorkManager 定时刷新过境提醒 | `BGAppRefreshTask` + 启动/回前台重建；系统调度不保证准点 |
| 高德 Android SDK 地图 | MapKit；如需高德需按 Bundle ID 申请 iOS Key |
| 应用内下载 APK 更新 | 不适用，iOS 走 App Store / TestFlight / 自签 |

## 8. 参考

- [iosApp/README.md](../iosApp/README.md)：macOS 上的构建步骤
- [shared/build.gradle.kts](../shared/build.gradle.kts)：KMP 目标与 framework 配置
- [docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md](./COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md)：当时对「是否维护 iOS 原生」的评估

### 已知缺口（截至本轮）

- 卫星：BJT 分段状态时间线未移植（`SatelliteStatusSegmenter` 依赖 java.time）
- FT8：自动 CQ 主叫、多候选择优、重发策略未移植；自动应答仅覆盖「呼叫本台」路径
- SSTV：滚动预览（`SstvRollingImage`）与「播放捕获」未移植；仅前台接收
- APRS：无 HTTP 上传器、无消息重试/ack 跟踪、地图仅 MapKit 标注
- 天气：Key 写死在 `iosApp/HamKit/AmapKey.swift`，公开仓库会暴露 Key（用户明确要求）
- AI 助手：图片/文件附件、模型列表自动获取、测试连接、工具卡折叠动效、Markdown 表格未移植；
  内核侧接口（`AiImage`、`AiProtocolCodec.probe`）已留好，属 UI 与探测入口的后续工作
- 隐私：首启同意页在 iOS 上「不同意」只停留本页（系统不允许主动退出），与 Android 的退出行为不同

- Android 的 WorkManager 后台提醒刷新在 iOS 上退化为「启动/回前台时重建」