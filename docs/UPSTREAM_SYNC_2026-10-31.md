# 上游同步（2026-10-31）：53 个提交逐项对照与 iOS 落地

> 对比对象：`upstream/miuix` @ `2aba3d3`（最上游仓库 `fuxue-linkong/Dual-zone_network_positioning`）
> 基线：`feat/ios-kmp` 上次同步点 → 本次合并 `5174770`
> 结论：**共享内核已对齐，iOS 侧本轮补齐卫星/隐私/关于/SSTV 与 AI 助手（P0+P1 核心）**，
> 剩余缺口见 §6。

## 0. 方法与范围

```bash
git fetch upstream --prune
git log --oneline 5174770^1..5174770^2        # 53 个提交
git diff --stat 5174770^2~53 5174770^2        # 219 文件 / +32338 −2842
```

上游没有 `shared/` 目录（KMP 内核是本仓独有），所以每个提交都要判断落在哪一层：

| 层 | 处置方式 |
|---|---|
| `shared/src/commonMain`（内核） | 直接同步 → Android / iOS 自动同时生效 |
| `app/`（Compose UI、Android 平台能力） | 按语义在 iOS 复刻，或按平台差异改写 |
| `website/`、`.github/`、`docs/` | 与 App 无关，不移植 |
| `app/src/test` | 对应行为在内核补 Kotlin 单测 |

## 1. 卫星链路（最关键的一批）

### `a6a0fa9` fix(satellite): 修正名称解析、在境 AOS 语义与模式聚合

四个独立缺陷，逐个对照：

| # | 上游做法 | 同步落点 | 状态 |
|---|---|---|---|
| 1 | 新增 `SatelliteNameMatcher`：NORAD 全等 → 名称/主名称全等 → 前缀 → 单词边界 → 子串，分层匹配；同层多颗返回「有歧义」而非静默取第一条 | **移入内核** `shared/.../data/satellite/SatelliteNameMatcher.kt`（`app` 侧同名文件删除，避免两份实现漂移） | ✅ 内核 + iOS 搜索排序 |
| 2 | 在境卫星回溯真实 AOS（5 分钟步长 + 二分精化，上限 `min(1.5×周期, 6h)`），不再把 AOS 写成预测时刻 | 上次合并时已同步到内核 `PassFinder.nextPass(lookbackMs)` | ✅ 内核（双端） |
| 3 | 模式聚合统一为 `SatelliteCatalog.effectiveModes`（活跃转发器 ∪ 目录，去重） | 内核已有该方法；**本次修好它的编译错误**（见 §2.1），iOS 列表改为同一合并语义 | ✅ 双端 |
| 4 | 搜索排序先按匹配层级、再按在境/AOS，避免「子串假阳性」排前面 | iOS `SatelliteStore.sorted(_:keyword:)` 接入 `nameMatchTier` | ✅ iOS |

输出语义也一并对齐到 iOS 的工具层：

- `next_aos` 只在 AOS 位于未来时输出；在境输出 `in_pass_since`；
- `get_satellite_passes` 输出 `in_pass` / `is_geo`，在境时附 `note`（AOS 可能已过去，用 LOS 判断剩余时间）；
- 卫星卡片过境行标注「正在过境」。

### `2aba3d3` fix(test): 缓存 AOS 断言按毫秒比较

纯测试改动：内核侧 `PassFinder` 的时间语义本来就以毫秒为准，无需移植。

## 2. 全库审计修复与「只改文案」的补实现

### `b60b60d` fix: 30 项审计缺陷（P1×6 / P2×15 / P3×9）

按模块对照（完整清单见 `docs/BUG_AUDIT_2026-10-24.md`）：

| 模块 | 内核层（双端自动生效） | iOS 需单独处理的 |
|---|---|---|
| SSTV | `SstvMode`（PD-290 VIS 0x5E）、`SstvStreamDecoder` 并发/自动保存、`SstvSync` | 无（iOS 走同一内核解码器） |
| 分区 | `ZoneResolver` 优先级修正（哈瓦那/圣安东尼奥/利马/拉各斯） | 无 |
| 卫星 | `SatellitePredictor` 峰值窗口、缓存 `isGeo` 回填 | iOS 用 `PassFinder`，峰值窗口同源 |
| APRS | — | ⚠️ 需逐条复核（会话列表、收件人校验、ack 前缀、去重、msgCounter 持久化、marker 更新）→ 见 §6 |
| CW / FT8 | — | ⚠️ `MorseCode` 的 U 前缀往返、FT8 15 秒对齐；内核侧已含 `MorseCode`，FT8 对齐在 App 层 → 见 §6 |
| 导航 | — | 通知深链 intent、Saver 空栈兜底属于 Android 概念，iOS 无对应 |

### `b00ffe3`、`22dbe31`、`674b069`

- `b00ffe3`：把「只改了文案」的六项功能真正做出来（SSTV 流式解码、CW 练习路由、APRS 各页、FT8）；
  iOS 的 APRS/FT8/CW 是独立实现，需要按同一**行为**核对，不能按补丁照搬 → 列入 §6 复核清单。
- `22dbe31`：注释/文案与实现不符的批量修正。iOS 侧同类问题本轮修了两处：
  `PrivacyPolicyView` 曾写「iOS 不涉及高德数据上传」，而天气实际走**高德 Web 服务并上传坐标**；
  `PassSnapshot.isCurrentlyVisible` 的 KDoc 还写着「aosMs 等于查询时刻」，与回溯修复后的语义相反。
- `674b069`：随 SSTV/分区修复调整的两处测试断言，内核测试已覆盖新语义。

## 3. 隐私合规、密钥与更新链（`8752626` + `a6062fa` + `2d592f5`）

### `8752626` feat(security): 隐私合规、密钥与更新链加固

| 上游改动 | iOS 处置 |
|---|---|
| 首启 `PrivacyConsentScreen`：政策勾选 + 精确位置单独同意 + APRS 公开可选；**同意前不初始化高德、不申请权限** | ✅ 新增 `PrivacyConsentView.swift`：三段说明（位置 / APRS / AI）、三个开关、政策全文入口；未同意时 `AppRootView` 全屏覆盖，且**推迟 `requestLocation()`** 到同意之后 |
| 不同意则退出应用 | ⚠️ 平台差异：iOS 不允许 App 主动退出（会被判「强制退出」），改为停留本页并给出说明 |
| `PrivacyConsentStore`：只记同意状态与时间 | ✅ `PrivacyConsentStore`（UserDefaults），并在设置页提供「撤回隐私同意」 |
| `SecretManager` + `secrets.dat`（Key 不落明文、不随备份导出） | ⚠️ 平台差异：Android 用 Keystore AES-GCM；iOS 用 **Keychain（`WhenUnlockedThisDeviceOnly`）**，不同步 iCloud、不进 iTunes 备份 |
| 隐私政策正文新增 AI 条款、数据出境说明、删除路径 | ✅ 重写 `PrivacyPolicyView` 文案（含 AI、APRS-IS 出境、撤回/删除路径） |
| 更新链加固（APK 校验、下载来源） | — iOS 无应用内更新，走 App Store / TestFlight |
| 官网隐私页 + 后台安全（`website/`） | — 与 App 无关 |

### `2d592f5` feat(about): 关于页新增开发者与捐赠者名单

✅ iOS `AboutView` 增加「开发者 / 捐赠者」两段：姓名 + 呼号 + GitHub id，头像取
`https://github.com/<id>.png`，整行跳主页；版本号改为读 `CFBundleShortVersionString` + `CFBundleVersion`
（原来硬编码 `0.2.0`，与工程里的 `MARKETING_VERSION` 不一致）。

### `a6062fa` fix(about): 移除关于页的应用 Logo 图标

iOS 关于页本来就没有 Logo 图标，无需改动。

## 4. SSTV 与配色（8 个提交）

| 提交 | 内容 | iOS 处置 |
|---|---|---|
| `075e280` | 行同步搜索先做 2ms 滑动平均（真实录音才能解码） | ✅ 内核 `SstvSync`（上次合并已同步，双端生效） |
| `5152b79` | 同步平滑改零相位居中窗口，消掉 7 像素水平偏移 | ✅ 同上 |
| `daafd50` | 同步段判定回退到原频率流，平滑流仅兜底 | ✅ 同上 |
| `aed112e` | 未收录 VIS 码：按「码值未收录」报错并带码值，不再与「解码失败」混为一谈 | ✅ 内核 `SstvDecoder` 区分两种失败（`未识别到 VIS 头` / `该 VIS 码未收录（0xNN）`），iOS 直接把内核文案显示在状态行 |
| `a361351` | 图库返回不再落在大图上（查看器状态在应用级 VM 里） | — 平台差异：iOS 详情页是 `NavigationStack` 的独立路由，天然不会残留；无对应缺陷 |
| `e4f9b44` | 移除跨帧融合（实测 +1dB，远低于理论值） | — 内核侧 iOS 未接入融合开关（`SstvBridge` 无该参数），无残留 |
| `841876d`/`edd256c`/`bc7ad5b` | 自定义背景图裁剪直存、`ColorPaletteUiState` 组装、图库返回吞返回键 | — iOS 主题页只有「外观模式 + 9 档强调色」，没有自定义背景图与相册返回栈，不适用 |
| `b35772d` | 合并 PR #99（SSTV 预览/发射竞态） | ✅ 内核 `SstvStreamDecoder` + `RollingRefreshHelper`（iOS 未接滚动预览，见 §6） |

## 5. AI 助手（40 个提交，本轮新增模块）

上游按 P0 → P1 → UI 打磨 → i18n 的顺序演进，Android 侧 `data/ai` + `ui/screen/ai` 共 **8303 行**。
iOS 此前完全没有该功能。

### 5.1 落地方式

按约定把**引擎放进 `:shared`**（可编译、可单测），Swift 只做界面与平台适配：

```
shared/src/commonMain/kotlin/com/example/hamkit/ai/
├── AiJson.kt        极简 JSON 树 + 解析/序列化（不引第三方依赖）
├── AiModel.kt       协议/预设/配置/消息/工具/事件/错误
├── AiSse.kt         SSE 增量分帧（跨分片、多行 data、裸 JSON 兜底）
├── AiProtocol.kt    三协议请求体 + 流式解析器（OpenAI / Anthropic / Gemini）
├── AiPrompts.kt     默认系统提示词（按 iOS 实际工具集裁剪）
├── AiChat.kt        对话状态机（工具往返、轮次上限、强制总结、取消）
├── AiToolbox.kt     工具实现（9 个）+ 平台数据门面 `AiAppFacade`
└── AiBridge.kt      Swift 桥接（配置/会话 JSON、错误构造、Markdown 导出）
```

Swift 侧：

| 文件 | 职责 |
|---|---|
| `AiTransport.swift` | `URLSession` 流式 POST；**按换行切分**保证 UTF-8 不跨分片截断；非 2xx 不当事件流解析 |
| `AiSettingsStore.swift` | 设置（UserDefaults）+ API Key（Keychain）+ 会话历史（沙盒 JSON 文件） |
| `AiFacade.swift` | `AiAppFacade` 实现：卫星/转发器/AMSAT/定位/TLE + 动作（跳页、建提醒） |
| `AiChatModel.swift` | 流式消息状态、工具行/卫星卡、副作用确认、历史读写 |
| `AiViews.swift` | 对话页（气泡/工具卡/卫星卡/空态示例/历史抽屉/导出） |
| `AiSettingsView.swift` | 服务商、协议、Base URL、模型、温度、token、提示词、工具开关 |

### 5.2 与上游行为逐项对照

| 上游能力（提交） | iOS 实现 | 说明 |
|---|---|---|
| 三协议对话、SSE 流式（`7a21720`） | ✅ 内核 `AiProtocol.kt` | 单测覆盖三家真实响应片段 |
| Key 加密存储（`7a21720`） | ✅ Keychain（设备专属，不同步） | 平台差异 |
| 导航第 3 项（`7a21720`） | ✅ 首页「AI 助手」入口卡 | iOS 是卡片式首页，不是底部导航 |
| 修复空响应/Key 不生效（`12e5167`） | ✅ 内核按状态码分类 + 明确报错 | — |
| 多 JSON 帧解析（`9497aaa`） | ✅ `AiSse` + `parseJsonObjects` | 含裸 JSON 兜底 |
| Markdown 渲染（`6daefca`/`520fa9a`/`3981dce`） | ⚠️ 简化：代码块 + 行内样式；**表格不渲染** | 见 §6 |
| 模型列表自动获取（`6daefca`） | ⚠️ 未做：只给预设建议模型 | 需要非流式探测接口 |
| 文件附件 + 音频 SSTV 解码（`f3bbb28`） | ❌ 未做 | 见 §6 |
| 图片多模态（`278092f`） | ❌ 未做（内核 `AiImage` 已支持，缺 UI 与压缩） | 见 §6 |
| 工具调用（`5c453a4`）+ 名称歧义/在境语义（`a6a0fa9`） | ✅ 9 个工具：分区、过境、实时、转发器、搜索、AMSAT、UTC、跳页、建提醒 | 名称解析复用内核 `SatelliteNameMatcher` |
| 工具权限开关（`fb36fa6`） | ✅ 设置页按工具开关，禁用后不进请求 | — |
| 副作用二次确认（`aa3cf8e`） | ✅ `ConfirmingToolHost` + 设置开关 | — |
| 轮次用尽强制总结 / 工具去重（`23e62a5`） | ✅ 内核 `maxToolRounds`，超限后不带 tools 重发 | 单测覆盖 |
| 会话历史 + Markdown 导出（`6bbc6c1`） | ✅ 历史抽屉、删除确认、分享导出 | — |
| 可编辑系统提示词（`cfb0174`） | ✅ 设置页编辑器 + 恢复默认 | — |
| 工具卡折叠、动效、雷达图（`ac879b2`/`68960c0`/`3981dce`） | ⚠️ 工具行常显（单行摘要 + 卡片），无展开动效、无雷达图 | 见 §6 |
| i18n（11 个提交） | — iOS 目前只有中文界面，与现有页面一致 | 后续统一做 |
| 首启隐私补充 AI 说明（`6bbc6c1`） | ✅ 同意页 + 政策页都有 AI 条款 | — |

### 5.3 本轮顺带修掉的上游遗留问题

1. **合并时手写的共享内核适配编译不过**：`SatelliteCatalog.effectiveModes(catalogNumber, radios: List<RadioInfo>)`
   引用了只存在于 `:app` 的 `RadioInfo`，`:shared` 无法编译（`Unresolved reference 'RadioInfo'`）。
   改为 `activeRadioModes: List<String>`（内核不认识平台转发器模型，由调用方投影），同步修
   `SatelliteListItem`、`SatellitePredictor`、`SatelliteTools`、`SatelliteCatalogTest` 四处调用。
2. `SatelliteNameMatcher` 从 `app` 移到内核后，`app` 侧同名文件删除（否则同包同名类两份）。
3. `SstvBridge.decodePcm` 的函数体被挤在同一行（格式化事故），已修。

## 6. 仍未对齐的缺口（按优先级）

| 优先级 | 缺口 | 原因 / 建议 |
|---|---|---|
| P1 | AI：图片附件与文件附件（含音频自动 SSTV 解码） | 内核 `AiImage` 已就绪，缺 Swift 侧压缩与附件 UI；建议下一轮做 |
| P1 | AI：Android 的 `get_weather` / `cw_encode` / `start_sstv_receive` 工具 | 依赖 iOS 的天气会话与音频生命周期，单独一轮接 |
| P2 | AI：「测试连接」与模型列表自动获取 | 需要非流式探测入口（内核加 `probe` 即可，接口已留） |
| P2 | APRS：`b60b60d` 里 9 项修复的 iOS 复核 | iOS APRS 是独立实现，需逐条对照（会话未读、收件人校验、ack 前缀、去重、msgCounter 持久化、marker 更新、重连计数、发送间隔、前台服务残留） |
| P2 | FT8/CW：15 秒 UTC 对齐、U 前缀往返、自动 CQ | iOS FT8 自动 CQ 本就未移植（既有缺口） |
| P3 | AI：工具卡折叠动效、思考过程展示、token 用量、Markdown 表格 | 体验项 |
| P3 | 卫星：BJT 分段状态时间线 | 既有缺口（`SatelliteStatusSegmenter` 依赖 java.time） |

## 7. 验证

| 检查 | 命令 | 结果 |
|---|---|---|
| 内核编译 | `./gradlew :shared:compileKotlinJvm` | 通过（修复前：`Unresolved reference 'RadioInfo'`） |
| 内核单测 | `./gradlew :shared:jvmTest` | **30 个用例全绿**：JSON 5 / SSE 6 / 协议 9 / 会话状态机 5 / 桥接 5 |
| Android 编译 | `./gradlew :app:compileDebugKotlin` | 通过 |
| iOS 编译 | 需 macOS + Xcode | **未验证**：本机无 Swift 工具链，交付后请跑一次 `xcodegen generate` + `xcodebuild` |

> iOS 侧本轮新增 6 个 Swift 文件、改动 7 个；Swift 语法按现有工程模式编写，
> 但**没有在本机编译过**（Windows 环境限制）。若编译报错，最可能集中在
> Kotlin/Native 的导出签名（枚举 `.name`、`Int32`、可选参数）上。
