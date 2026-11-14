# HamKit 竞品劣势分析与后期修复计划

> 日期：2026-09-28
> 分析对象：本仓库（HamKit，`com.example.hamkit`，仓库内版本 3.0.1 / versionCode 18；最新公开 Release v3.0.0 / versionCode 16）
> 对比对象：APRSdroid、Look4Sat、Gpredict、FT8CN、Robot36、Ham2K PoLo、IZ2UUF Morse Koch CW 等同类软件
> 方法：① 联网调研竞品公开资料（官网 / README / Release Notes / 商店与发行版页面）；② 本仓库代码与文档静态审计；③ 社区反馈（HamCQ 论坛）；④ 本项目 GitHub Issue/PR 历史
> 结论一句话：**HamKit 的“一体化 + 中文 + 卫星体验”是真实差异化优势，但目前除卫星模块外，每个模块的“深度”都落后于对应垂直竞品，且缺少“射频闭环 + 设备生态 + 日志生态”三块拼图；同时工程发布链路与合规存在结构性缺陷，导致已修好的功能无法到达用户。**

---

## 一、结论摘要（TL;DR）

| # | 结论 | 影响面 | 证据等级 |
|---|------|--------|----------|
| C1 | **射频闭环缺失**：APRS 只能走 APRS-IS（明文 TCP），无 AFSK / KISS / 蓝牙 TNC；FT8 只能“播音频”无 PTT/CAT；SSTV 只能收不能发（编码器已写但未接线）；CW 只能发不能抄 | 全功能 | 代码直接证据 |
| C2 | **设备生态脱节**：无 CAT/CI-V/串口/蓝牙电台控制，无天线旋转器控制；多普勒只“显示数字”不“调电台” | 卫星 / FT8 | 代码 + 竞品证据 |
| C3 | **日志与线上服务生态缺失**：日志仅覆盖 FT8 记录，仅 ADIF/XML 导出（无导入），无 LoTW / eQSL / QRZ / Cloudlog / Wavelog 上传，无 POTA/SOTA/WWFF | QSO 日志 | 代码 + 竞品证据 |
| C4 | **发布链路断裂**：CI 打包/发布工作流已被删除（本地手工打包上传）；公开版本落后仓库 2 个 versionCode，PR #91/#92 的关键修复未到达用户；历史上出现过签名不一致需卸载重装、包名变更需重新授权 | 全部用户 | 仓库 + Release 证据 |
| C5 | **分发与合规风险**：仅官网/GitHub APK 分发，未上 Google Play / F-Droid；`REQUEST_INSTALL_PACKAGES` 自更新 + 双精确闹钟权限在 Play 政策下受限；高德地图 SDK 带来跨境数据与合规声明负担 | 上架 / 出海 | 代码 + 政策链接 |
| C6 | **工程质量细节**：包名 `com.example.hamkit`、依赖含 `latest.integration` 与 alpha 版 Material3、`hiddenapibypass` 绕过隐藏 API、`secrets.dat` 入库且“三碎片密钥”在公开仓库可完整还原（混淆≠加密） | 安全 / 可维护性 | 代码直接证据 |
| C7 | **i18n 架构不成立**：默认英文资源 367 条，中文仅 81 条，而 UI 层有 250+ 处硬编码中文；46 个 `values-*` 目录多数是模板残留（德语仅 8 条、日语 32 条） | 国际化 | 代码直接证据 |
| C8 | **社区可感知的可用性缺陷**：APRS 设置输入框被输入法遮挡；虚拟/手势导航机型系统返回键失效（主页返回键也无法回桌面，只能点左上角箭头） | 体验 | 社区反馈 |
| C9 | **量化差距**：APK 21.8 MB（Look4Sat 8.0 MB）；GitHub Star 7（APRSdroid 604 / Look4Sat 1498 / FT8CN 632 / Robot36 515）；卫星预测 48 h（Look4Sat 10 天） | 竞争力 | 公开数据 |

**优势也要写清楚（避免自我否定）**：自研 SGP4/SDP4 引擎 + 差分测试、FT8 从“自创协议”改造成与 WSJT-X/FT8CN 互通、42 个 JVM 单测 + 5 个仪器测试、MIT 许可、国内 CDN 镜像（`tle.hamkit.click` / `apk.hamkit.click`）解决弱网、双 UI（Material / Miuix）与“卫星过境 + 日历提醒”体验被社区认可。这些是**不该在修复过程中被破坏**的资产。

---

## 二、竞品横向能力矩阵

| 能力 | HamKit | APRSdroid | Look4Sat | FT8CN | Robot36 | PoLo (Ham2K) | Gpredict (桌面) |
|------|--------|-----------|----------|-------|---------|--------------|-----------------|
| APRS-IS | ✅（明文 TCP） | ✅ | — | — | — | — | — |
| APRS 射频（AFSK / KISS / 蓝牙 TNC） | ❌ | ✅ | — | — | — | — | — |
| APRS iGate / digipeater | ❌ | 部分（配合外置 TNC） | — | — | — | — | — |
| APRS 天气 / 遥测 / 状态报文 | ❌（解析器仅消息/位置/对象/项目） | ✅ | — | — | — | — | — |
| 离线地图 | ❌（在线高德 3D SDK） | ✅（OSM 离线） | — | — | — | — | — |
| 卫星多普勒自动调谐（CAT） | ❌（仅显示） | — | ✅（v4.4.4 起支持 Icom CAT、线性转发器多普勒） | — | — | — | ✅（Hamlib rigctld） |
| 天线旋转器控制 | ❌（REQ-0003 未实现） | — | — | — | — | — | ✅（Hamlib rotctld） |
| 自定义 TLE / 数据源导入 | ❌（4 个 CDN URL 硬编码） | — | ✅（.txt 导入 / 自定义数据源） | — | — | — | ✅ |
| 预测时长 | 48 h | — | 10 天 | — | — | — | 长期 |
| FT8 收发 | ✅（音频，无 PTT） | — | — | ✅（含 CAT 控制电台、日志上传 Cloudlog/QRZ） | — | — | — |
| FT8 多信号解码 | ⚠️（每时隙最强 1 条） | — | — | ✅ | — | — | — |
| SSTV 解码 | ✅（11 模式，自研 DSP） | — | — | — | ✅（解码定位） | — | — |
| SSTV 发射 | ❌（`SstvEncoder` 未接线） | — | — | — | ❌（仅解码） | — | — |
| CW 抄收解码 | ❌ | — | — | — | — | — | — |
| CW 训练深度（Farnsworth / 自适应难点 / 免看屏 / 语音校验 / prosign） | ❌（Koch 26 课 + 字符组/呼号/文本） | — | — | — | — | — | — |
| QSO 日志 | ⚠️（仅 FT8 记录 + ADIF/XML 导出） | — | — | ✅（+ 云同步） | — | ✅（POTA/SOTA/WWFF，多端） | — |
| 桌面 / iOS / Web | ❌（仅 Android） | ❌ | ❌ | ❌ | ❌ | ✅（Android/iOS/桌面/Web） | ✅（Linux 桌面） |
| 商店分发 | ❌（官网/GitHub APK） | ✅（Play + 官网 APK） | ✅（Play + F-Droid + GitHub） | ✅（GitHub Releases） | ✅（Play） | ✅（Play + App Store） | ✅（发行版仓库） |
| 开源许可 | MIT | GPL-2.0 | GPL-3.0 | MIT | 0BSD | MPL-2.0 | GPL |

> 说明：表中“—”表示该竞品不提供该项能力（非缺陷），仅供判断“同一需求在市场上由谁满足”。

---

## 三、分模块劣势详解（含证据）

### 3.1 分区定位与地图

- **只有一条地图通路**：全部地图/逆地理编码走 `com.amap.api:3dmap` + 高德 Web 服务（`data/weather/WeatherApiService.kt:260` 读取 `amap.api.key`），没有 OSM/离线瓦片方案。
  - 后果 1：**境外不可用/体验差**（高德海外覆盖弱），而 App 有 46 个语言目录、官网英文版，定位与分发是矛盾的。
  - 后果 2：**隐私合规成本**——高德 SDK 属于第三方信息共享，需要在隐私政策与商店 Data Safety 中声明并取得同意（参见高德开放平台隐私权政策）。
  - 对照：APRSdroid 提供 OSM 离线地图（`aprsdroid.org/osm/`）。
- **强绑定 Google Play Services Location**：无 GMS 设备（部分国产 ROM、去 GMS 用户）会退化，纯 AOSP 场景未做兼容声明。
- **定位精度**：项目自身 Release Notes 记录过“精度为 0 被误判为高精度缓存”“`X_PI` 误用为 π 导致 GCJ02 标记偏移”等缺陷，说明坐标链路测试薄弱。

### 3.2 卫星（当前最强模块，差距集中在“从看到到用上”）

- **无电台控制（CAT）**：`DopplerCalculator` 只把多普勒频移算出来给 UI 显示（`ui/screen/satellite/SatelliteDetailScreen.kt:169`），用户仍需手动改频。
  - Look4Sat 在 v4.4.4 已加入 **Icom CAT 支持**与线性转发器多普勒计算；Gpredict 通过 **Hamlib rigctld** 做多普勒调谐。
  - 这是“过境提醒 → 真的通联上”之间缺失的最后一跳。
- **无旋转器控制**：`requirements/requirements/REQ-0003-antenna-rotator-control.md` 仍未实现，Gpredict 已通过 Hamlib rotctld 支持。
- **数据源不可扩展 + 单点依赖**：`data/satellite/SatelliteDataSource.kt:68` 四个 URL 全部硬编码为 `tle.hamkit.click`，用户无法导入自定义 TLE/OMM，也无备用源；镜像脚本与 CDN 由作者自建（`scripts/mirror_celestrak.sh`，PR #90）。
  - 对照：Look4Sat 支持 `.txt` TLE 导入与自定义数据源（v4.4.6“customize data sources via import”）。
  - 历史事故：PR #91 记录 `active.csv` 30 s 超时被静默跳过 → 用户只看到 ~600 颗卫星；PR #92 记录持久化开关残留导致同样问题，并新增 `MIN_ACTIVE_TLE_COUNT` 防降级。
- **预测时长 48 h vs 竞品 10 天**（README vs Look4Sat README）。
- **无观测/状态上报以外的社区闭环**：Look4Sat v4.4.7 已支持提交 AMSAT 卫星状态报告；SatNOGS 观测上传常见于同类工具，HamKit 仅只读查询 AMSAT 页面（`AmsatPageScraper`，且曾因非英文 locale 解析失败）。
- **SSTV 与卫星的衔接是亮点**（详情页可跳 SSTV、ARISS PD-120 提示），应保留并在文档/官网强化。

### 3.3 FT8

- **无 PTT / CAT**：`ui/screen/ft8/Ft8SettingsScreen.kt` 只有“允许发射”开关，发射即 `Ft8AudioPlayer` 播放音频（VOX 由用户自己解决），没有串口/蓝牙/网络 PTT。FT8CN 的核心竞争力正是**多型号电台的 CAT 控制与 PTT**（其 README 致谢里逐台致谢测试者）。
- **单条解码**：`docs/FT8_DEFECT_ANALYSIS.md` 遗留项 #8 自述“每时隙只解 1 条”，而 FT8CN/WSJT-X 同槽可解多条——这直接决定“能不能在热闹频段上被叫到”。
- **无自动通联状态机（QSO 序列）**：无 CQ → 呼叫 → 报告 → 73 的自动时序，也无 Fox/Hound 等特殊模式。
- **日志生态**：FT8CN 已把日志上传到 Cloudlog/QRZ（其 README 明确致谢贡献该功能的 BG5VLI），HamKit 仅本地记录 + 导出。
- **时钟同步**：FT8 对 UTC 时隙敏感，当前仅按 `System.currentTimeMillis()/15s` 近似对齐（见 FT8 分析文档遗留 #3），未提供“时钟偏差异常”提示或 NTP 校准。

### 3.4 APRS（与竞品差距最大的模块）

证据：`data/aprs/AprsConnection.kt`（纯 `Socket` 连 APRS-IS，默认 `china.aprs2.net:14580`）、`data/aprs/AprsModels.kt:52`、`data/aprs/AprsPacketParser.kt:44`。

- **只能走互联网**：无 AFSK 1200 bps 调制/解调，无 KISS/TCP、无蓝牙 TNC 连接——**无法通过电台发射或接收 APRS**。APRSdroid 的三大通路是 APRS-IS / AFSK 音频 / 蓝牙 TNC（`aprsdroid.org`），并可配合外置 LoRa KISS 调制解调器工作。
- **无 iGate / digipeater 能力**（该网络的基础设施职能）。
- **报文类型覆盖窄**：解析器只处理 `:`（消息）、`!` `=` `/` `@`（位置）、`;`（对象）、`)`（项目），**不支持** `_`（天气）、`T#`（遥测）、`>`（状态）、公告/查询/NWS 等；气象站符号虽可显示，但收不到天气数据内容。
- **死代码与未接线配置**：
  - `data/aprs/AprsHttpUploader.kt`（aprs.fi `addloc` 上报）**全仓库无调用点**；
  - `useTls` 在模型/设置中持久化（`AprsModels.kt:54`、`AprsSettingsStore.kt:31`），但连接实现里**从未使用** → 实际只有明文 TCP。
  - 这类“看起来有、其实没通”的功能比“明确没有”更危险：用户以为有加密、以为能上报到 aprs.fi。
- **地图与站点体验**：站点列表/地图依赖高德在线地图，无离线；无站点距离/方位排序之外的高级过滤（约 3.8 万行的卫星模块对比下，APRS 模块总量仅约 1 万行）。
- **社区实测缺陷（HamCQ #8940）**：
  1. APRS 设置页“位置上报”两个输入框被输入法遮挡；
  2. 虚拟/手势导航机型系统返回键失效，返回主页后仍无法返回桌面，只能点左上角箭头。

### 3.5 SSTV

- **只能收不能发**：`data/sstv/SstvEncoder.kt` 已实现（13 KB），但**全仓库没有任何调用点**（grep 仅命中定义行）→ 发射功能事实上不存在。对照：桌面端 MMSSTV/QSSTV/EasyPal 均支持发射，EasyPal 还支持数字 SSTV。
- **图像产物留在应用私有目录**：设置页自述“导出到相册属二期功能”，用户拿到图片需额外步骤；Robot36 这类竞品以“解码即得图”为定位。
- **模式覆盖是优势**：11 种模式（PD/Robot/Martin/Scottie）+ VIS 自动识别 + slant/失谐补偿，实测鲁棒性描述具体，应作为卖点固化到官网与商店描述中。
- **三期方向（流式解码、多帧融合）已在 `docs/SSTV_PHASE3_PLAN.md` 规划**，与竞品差距更多在“发射 + 导出 + 分享”而非解码算法。

### 3.6 CW

- **无抄收（RX）解码**：`data/cw/*` 只有生成器与播放器（`MorseCodeGenerator` / `MorseCodePlayer`），没有音频→文本的解码；而业余无线电的刚需是“抄收真实电台/自己发的报是否正确”。
- **训练深度不及专业训练 App**：IZ2UUF Morse Koch CW 提供 Farnsworth 间隔、难点字母自适应加权、**免看屏/息屏练习**、语音校对答案、QSO 生成器（1116 个名字 / 544 个城市 / 31 国 / 560 种设备）、100+ 国际字符与 prosign、练习配置保存；HamKit 目前是 Koch 26 课 + 字符组/呼号/文本四种模式（WPM/音调/播放模式可调）。
- **无电键输入通路**（直键/双桨键经 USB/音频），无法用真实电键练习。

### 3.7 QSO 日志

- **只服务 FT8**：记录模型与界面位于 FT8 分支（`data/qso/QsoStore.kt`、`ui/screen/ft8/QsoLogScreen.kt`），无手工录入、无其他模式日志、无竞赛/字段校验（RST、QTH、QSL 状态等）。
- **只能导出，不能导入**：`docs/QSO记录与导出功能的设计方案.md` 与 `QsoExporters.kt` 仅覆盖 ADIF(.adi, 3.1.4)/ADX XML 导出；无 ADIF 导入（用户无法从 Log4OM/PoLo/其他 App 迁移历史）。
- **中文备注会被丢**：`data/qso/QsoExporters.kt:83` 注释明确“ADIF 字段仅允许 ASCII 32–126；其余字符直接剥离”，只有 XML 路径才有 `COMMENT_INTL`。
- **无在线日志服务对接**：LoTW、eQSL、QRZ Logbook、Cloudlog、Wavelog 全无（FT8CN 至少有 Cloudlog/QRZ 自动上传）。
- **无排行榜/奖项支撑**：PoLo 面向 POTA/SOTA/WWFF，GridTracker 类工具有地图与告警；HamKit 无对应能力。

### 3.8 “一体化”带来的结构性取舍

- **广而浅**：卫星模块约 3.8 万行，APRS 约 1 万行，但对照垂直竞品仍缺关键闭环；“什么都有”不能替代“某件事最好”。
- **单体与包体**：APK 21.8 MB（v3.0.0 Release 资产）vs Look4Sat 8.0 MB，未做 ABI/语言资源裁剪（`resConfigs`）。
- **只有 Android**：PoLo 已覆盖 Android/iOS/桌面/Web；HamKit 的“外出通联”场景在桌面/平板上无延伸。
- **人力结构风险**：README 自述为“学生、非 HAM、非专业团队”，这是真实约束，必须在路线图上体现（宁可少承诺、按时交付，也不要留大量“看起来有”的功能）。

---

## 四、工程、安全与合规缺陷（可直接定位）

### 4.1 版本与发布链路

| 问题 | 证据 | 后果 |
|------|------|------|
| 公开版本落后仓库 | 仓库 `build.gradle.kts`：versionCode 18 / 3.0.1；最新 Release：v3.0.0 / versionCode 16（2026-08-18） | PR #91/#92 的“卫星只剩 600 颗”修复**未到达用户**（PR #92 正文自述“设备上仍是 versionCode=16”） |
| CI 发布被删除 | PR #74 删除 `release.yml` / `release-test.yml`，改为“本地打包手动上传”；`.github/workflows/ci.yml` 仅监听 `miuix` 分支构建产物 | 发版依赖个人机器与 keystore，节奏不可预测、不可审计 |
| 升级路径曾断裂 | Release v2.1.1 正文：“如果遇到签名不一致问题，请卸载后重装”；v3.0.0 正文：“由于包名变更，旧版本覆盖安装后需重新授权定位等权限” | 用户数据丢失、信任受损；应用内自更新链路同样受影响 |
| 包名不专业且已变更一次 | `applicationId = "com.example.hamkit"`（`app/build.gradle.kts`） | `com.example.*` 无法上架，且每次改名都会重演上述迁移痛苦 —— 应一次到位并永久冻结 |

### 4.2 分发与商店政策

- 无 Google Play / F-Droid 渠道，仅 `hamkit.click` / GitHub Releases。政策层风险：
  - `android.permission.REQUEST_INSTALL_PACKAGES`（应用内下载安装 APK）属于**受限权限**，Play 要求其为“核心功能”且禁止用于绕过商店自更新；
  - 同时声明 `SCHEDULE_EXACT_ALARM` 与 **受限权限** `USE_EXACT_ALARM`，Play 仅允许以闹钟/日历为核心功能的应用声明。
  - 参考：Google Play 权限政策（[REQUEST_INSTALL_PACKAGES](https://support.google.com/googleplay/android-developer/answer/16558241)、[USE_EXACT_ALARM](https://support.google.com/googleplay/android-developer/answer/15225428)）。
- 合规声明缺失项：高德 SDK 第三方共享清单、定位数据出境/使用范围说明、Data Safety 表（若上架）。参考[高德开放平台隐私权政策](https://amap.wezhan.cn/pages/privacy/)。

### 4.3 依赖与构建可复现性

`gradle/libs.versions.toml` 中的问题：

| 依赖 | 现状 | 风险 |
|------|------|------|
| `com.amap.api:3dmap` | `latest.integration` | 构建不可复现，随时可能拉入不兼容版本；无法复现历史 APK |
| `androidx.compose.material3` | `1.5.0-alpha22` | alpha API 变动导致运行时/编译期行为漂移 |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.1 | 主动绕过 Android 隐藏 API 限制，属灰色手段；Play 审核与系统升级双重风险 |
| `miuix` KMP 0.9.3 + `top.yukonga.miuix.kmp.blur` | 需 `tools:overrideLibrary` | 非公开稳定生态，长期维护依赖上游 |
| 无依赖校验 | 无 `verification-metadata.xml` / Renovate / Dependabot 配置 | 供应链与 CVE 无人值守 |

### 4.4 密钥与“加密”设计（高危）

- `app/src/main/assets/secrets.dat` **入库**（加密后的 `amap.api.key`）。
- 但**解密材料完全在公开仓库**：`app/build.gradle.kts` 内置 `CRYPTO_SHARD_A_BYTES`、`SHARD_B_RAW`、`SHARD_C_SALT`，与 `data/crypto/SecretManager.kt` 一起可完整还原 `assembleMasterKey()`。任何拿到 APK 的人都能解密出 API Key。
- 结论：**这是混淆，不是加密**。必须在文档（`docs/CRYPTO_DESIGN.md`）与代码注释中如实降级描述，且**不得**把该机制用于任何真正机密（如用户凭据、LoTW 密码）。
- 相关影响：v3.0.0 曾因包名变更导致 `secrets.dat` 解密失败 → 天气功能报 `ApiKeyMissingException`，说明该机制同时是**构建脆弱点**。

### 4.5 国际化（i18n）

| 项 | 实测 |
|----|------|
| 默认（英文）资源条目 | 367 条（`res/values/strings.xml`） |
| 中文资源条目 | 81 条（`res/values-zh-rCN/strings.xml`） |
| 其他语言目录 | 46 个 `values-*`，其中德语 8 条、日语 32 条、俄语 32 条、法语 47 条（模板残留） |
| UI 层硬编码中文 | `ui/` 下 250+ 处中文字面量（例如 `ui/viewmodel/SstvViewModel.kt`、`ui/screen/ft8/Ft8MainScreen.kt`、`ui/screen/aprs/*`、`ui/cw/*`） |

- 后果：**主用户群（中文）看到中英混杂，海外用户看到中文**；新增/修改文案必须改代码而非资源，翻译无法外包。
- 对照：Look4Sat v4.4.7 明确做了一轮 “Extract hardcoded UI strings to resources (en/zh)” —— HamKit 需要同样的手术，且越晚做成本越高。

### 4.6 测试与质量门禁（正面 + 缺口）

- 正面：42 个 JVM 测试文件（含 SGP4 差分、TLE 解析、FT8 真实录音互通）+ 5 个仪器测试（均为 data/ViewModel 集成测试）；JaCoCo 已接入；CI 会跑单测。
- 缺口：无覆盖率阈值门禁（只有报告）、无 Compose UI 测试与真机回归清单、无依赖安全扫描、无性能基线（卫星预测并行化在低端机上的耗时/内存无量化）、`FT8_DEFECT_ANALYSIS.md` 遗留项 #1–#4 仍开放。

### 4.7 社区反馈汇总（HamCQ #8940）

- 输入法遮挡 APRS 设置输入框（截图 3 张）。→ **已修复**（P0-4，见 5.0）
- 系统返回键失效：进入子页后只能点左上角箭头；回到主页后底栏返回键仍无效，无法回到桌面。→ **已修复**（P0-3，见 5.0）
- APRS-IS passcode 合规：应用内自动计算并展示验证码，违反官方发放政策。→ **已整改**（见 `docs/APRS_IS_COMPLIANCE.md`）
- 正面反馈：卫星“是否活跃 + 模式标记”对新 HAM 友好，降低打星门槛 —— 提示**入门友好**应成为产品主张。

---

## 五、后期修复计划（Fix Plan）

> 排序原则：**先把“已经承诺的能力变成真的”（P0）→ 再补“射频/设备闭环”（P1，差异化关键）→ 再补“生态与数据”（P2）→ 最后是平台化（P3）**。
> 每个任务给验收标准（DoD），避免再出现“看起来有”的功能（如 `SstvEncoder`、`AprsHttpUploader`、`useTls`）。

### 5.0 社区反馈整改（已完成，待真机走查）

| ID | 条目 | 状态 | 改动摘要 |
|----|------|------|----------|
| P0-3 | 系统返回键失效（HamCQ #9 / HK-BUG-002） | ✅ 代码已修复 | `MainActivity` 新增 `RootBackHandler`：用 `androidx.activity.compose.BackHandler` 注册到 `OnBackPressedDispatcher`（不依赖 navigationevent 回调链），优先级「子页出栈 → 主页面回第 0 页 → 双击返回退出」；`MainScreenBackHandler` 收敛为仅管分页；`SuperSearchBar` 的 `isBackEnabled = true` 硬编码改为按展开态判定 |
| P0-4 | 输入法遮挡（HamCQ #9 / HK-BUG-001） | ✅ 代码已修复 | `AprsSettingsScreen` / `Ft8SettingsScreen` 滚动容器加 `imePadding() + imeNestedScroll()`；`QsoLogScreen` 列表与编辑弹窗同样处理 |
| P0-8 | APRS-IS passcode 合规（Issue #93 / HK-BUG-003） | ✅ 代码已整改 | 删除 `AprsPacket.passcode()` / `AprsConfig.computedPasscode`；留空以 `pass -1` 只读连接并拦截全部注入路径；首次连接前增加持照与责任声明确认；详见 `docs/APRS_IS_COMPLIANCE.md` |

### P0 — 可信度与体验止血（建议 2 周内，随 3.0.2 发布）

| ID | 任务 | 具体动作 | 验收标准（DoD） |
|----|------|----------|-----------------|
| P0-1 | 恢复可重复发布（**改为本地一键发版**） | 决策：不重建 `release.yml`（GitHub Actions 对本项目过重）。改为 `scripts/release.ps1`：版本号单调性校验 → 单测 → 签名打包 → `apksigner` 指纹校验（禁止 debug key 静默兜底）→ SHA-256 → 上传 `s3://hamkit-apk-tpe/release/` → CloudFront 失效 → 生成官网发布载荷。GitHub Actions 只保留测试；GitHub Release 仅作留档与 App 回退源。详见 `docs/RELEASE_LOCAL.md` | 一条命令产出可下载 APK 且 `apk.hamkit.click` 已更新；发版前脚本能拦住「用错签名」「versionCode 没涨」「发错产物」三类事故 |
| P0-2 | 版本对齐与补发 | 把 3.0.1/3.0.2（含 PR #91/#92 修复）正式发布，并在 Release Note 中说明“卫星只剩 600 颗”的修复 | 用户端“检查更新”能升到修复版；PR #92 的问题在真机复测通过 |
| ~~P0-3~~ | ~~导航与返回键修复~~ | ✅ **代码已修复，见 5.0**（真机三 ROM 走查待执行） | 三台不同 ROM（MIUI / 原生 / 手势导航）真机走查：所有页面返回键 + 左上角箭头行为一致；主页返回可退出应用 |
| ~~P0-4~~ | ~~输入法遮挡修复~~ | ✅ **代码已修复，见 5.0**（真机键盘弹出走查待执行） | APRS 设置页两个输入框在键盘弹出时完整可见，可点击可滚动 |
| P0-5 | 死代码与假开关清理 | 二选一：接线或删除 `AprsHttpUploader`、`useTls`；为 `SstvEncoder` 明确标注“未接线/规划中”；CI 增 `detekt`/自定义规则避免新增未引用类 | 仓库中不存在“无调用点的功能类”且无“持久化但不生效的设置项”；`useTls` 若保留必须真正启用 TLS 或 UI 隐藏 |
| P0-6 | 密钥设计纠偏 | 在 `docs/CRYPTO_DESIGN.md` 与代码注释中标注“混淆非加密、API Key 可被逆向”；把可轮换的服务（高德 Key）改为按签名+包名服务端配额控制；密钥轮换流程写入文档 | 文档描述与实际安全强度一致；不再有“加密保护 API Key”的表述 |
| P0-7 | 构建可复现 | 锁定 `amap-3dmap` 具体版本；评估并移除 `hiddenapibypass`（改为最小化依赖或声明最低 API 行为）；对 alpha 依赖加注释与升级跟踪项 | `./gradlew --offline` 可复现构建；`libs.versions.toml` 中无 `latest.*` |

### P1 — 射频与设备闭环（建议 1–2 个月，v3.1）

| ID | 任务 | 关键设计 | 验收标准 |
|----|------|----------|----------|
| P1-1 | APRS 射频通路 | 新增 `Afsk1200Modulator/Demodulator`（AudioTrack/AudioRecord，1200 bps AFSK，含 TXDELAY 与 DCD）+ KISS over TCP/蓝牙 SPP 客户端；在 `AprsConfig` 增加 `transport = IS / AFSK / KISS` | 与 APRSdroid 或手持台互测：能发出被 iGate 收录的位置包；能用手机解出对讲机收到的位置包（提供实测日志与音频样本测试） |
| P1-2 | APRS 报文覆盖 | 解析/展示 `_` 天气、`T#` 遥测、`>` 状态、公告/查询；增加站点过滤（范围/符号/类型）与“仅我关注”列表 | 用 APRS-IS 实时流跑 24 h 无解析崩溃；天气站显示温度/风速/气压字段 |
| P1-3 | iGate / digipeater（可选，风险高） | 仅 APRS-IS↔RF 单向转发需谨慎，默认关闭并加显著合规提示（发射需持照） | 功能默认关闭；开启需二次确认 + 频段/功率提示；文档声明责任 |
| P1-4 | FT8 电台控制 | 新增 `RigControl` 抽象层（USB 串口 / 蓝牙 SPP / 网络 rigctld），先支持 CI-V 与常见机型 AT 指令；接 PTT（CAT 或 RTS/DTR）与频率设置，替换“VOX 播放音频” | 至少 2 台实机（如 IC-705 / (tr)uSDX 类）完成 FT8 往返：软件设置频率、PTT 发射、收到对方 73 并自动记入日志 |
| P1-5 | FT8 多信号解码 | 解码返回候选列表并去重（`docs/FT8_DEFECT_ANALYSIS.md` 遗留 #1/#2），呼号哈希表积累解析非标准呼号 | 用公开测试录音（含多条信号）解出 ≥3 条/时隙；与 FT8CN 输出对比记录到文档 |
| P1-6 | CW 抄收解码 | Goertzel/自适应阈值 + 自适应 WPM 跟踪；先做“清晰信号”场景；用于校对用户自己发送的报 | 对自录音频（WPM 15/20/25，含噪声）字符准确率 ≥90%，结果写入测试用例 |
| P1-7 | SSTV 发射 | 接线 `SstvEncoder` → 播放/发射（含模式选择、呼号叠加、预听）；补“保存到相册/分享” | 手机播放 PD-120，被 Robot36/MMSSTV 正确解码出图像（附解码截图）；图片可直接存相册 |
| P1-8 | 卫星多普勒自动调谐 | 复用 P1-4 的 `RigControl`：过境时按多普勒曲线自动改上下行频率（线性转发器反相处理），提供“手动确认/自动”两种模式 | 实机过境测试：全过境无需手调频率即可维持通联；日志记录频率曲线 |

### P2 — 生态与数据（建议 3–6 个月，v3.2–v4）

| ID | 任务 | 说明 | 验收标准 |
|----|------|------|----------|
| P2-1 | 通用 QSO 日志 | 从 FT8 模块抽出通用 `QsoRecord`：手工录入、频率/模式/波段、RST、QTH、备注、QSL 状态；支持 **ADIF 导入 + 导出**（含 `COMMENT_INTL`，不再丢中文） | 导入 1 万条 ADIF 无丢字段；往返（导入→导出）差异为 0（字段级 diff 测试） |
| P2-2 | 在线日志对接 | LoTW（TQSL 签名/上传）、eQSL、QRZ Logbook、Cloudlog、Wavelog 至少两项；凭据用 Android Keystore 加密存储 | 至少 2 个服务真实上传成功并可在网页端确认；凭据不以明文落盘 |
| P2-3 | 奖项/活动支撑 | POTA/SOTA/WWFF 的自 spotting 与参考号字段（对标 PoLo） | 完成一次 POTA 激活的完整记录与导出 |
| P2-4 | 自定义卫星数据源 | 允许导入 `.3le/.tle/OMM/CSV` 与用户自定义 URL，内置 CelesTrak 直连兜底（不再单点依赖自建 CDN） | 断网/镜像故障时仍能用手动导入的数据完成预测；`SatelliteDataSourceTest` 覆盖导入路径 |
| P2-5 | 旋转器控制（REQ-0003） | 网络 rotctld 优先（复用 `RigControl` 的 TCP 客户端），USB/蓝牙次之 | 真机旋转器或 rotctld 模拟器完成方位/俯仰跟踪 |
| P2-6 | Widget + 备份/恢复（REQ-0005） | 桌面小组件显示下一过境；设置/收藏/日志的导出导入（含校验和与版本迁移） | 换机后可 100% 还原设置、收藏、日志；不依赖 root |
| P2-7 | 中继库（REQ-0004） | 按区域加载离线中继数据 + 手动维护，明确数据来源与授权 | 离线可查询所在城市中继频率/频差/亚音 |

### P3 — 平台化与可持续（长期）

| ID | 任务 | 说明 |
|----|------|------|
| P3-1 | i18n 手术 | 全量把 UI 中文抽到 `strings.xml`（英文为默认，中文为 `values-zh-rCN`），清理 46 个模板残留语言目录，接入 Crowdin/Weblate 类平台（参考 Look4Sat v4.4.7 的做法） |
| P3-2 | 渠道上架 | Google Play（含 Data Safety、隐私政策、移除 `REQUEST_INSTALL_PACKAGES` 自更新或以“核心功能”重新论证）+ F-Droid（需可复现构建与无本体追踪依赖） |
| P3-3 | 质量门禁 | JaCoCo 覆盖率阈值（如核心 data 层 ≥60%）、detekt/ktlint、Dependabot/Renovate + 依赖校验、ANR/崩溃采集（可自建，避免强绑定第三方） |
| P3-4 | 包名/签名冻结 | 一次性迁移到自有域名反写包名（如 `click.hamkit.app`），制定签名轮换与数据迁移方案，此后永久冻结 |
| P3-5 | 多端评估 | 用 Kotlin Multiplatform 评估“桌面端过境台/日志”可行性；**不建议**同时维护 iOS 原生 |

---

## 六、优先级排序（影响 × 成本）

| 任务 | 用户影响 | 差异化贡献 | 成本 | 建议顺序 |
|------|----------|-----------|------|----------|
| P0-1/2 发布链路 | 高 | 中 | 低 | 立刻 |
| P0-3/4 返回键 + 输入法 | 高 | 低 | 低 | 立刻 |
| P1-1 APRS 射频 | 高 | **高** | 中 | 第一批 |
| P1-4/P1-8 FT8 CAT + 卫星调谐 | 高 | **高** | 中高 | 第一批 |
| P1-2 APRS 报文覆盖 | 中 | 中 | 低 | 第一批 |
| P1-6 CW 抄收 | 中 | 中 | 中 | 第二批 |
| P1-7 SSTV 发射 | 中 | 中 | 低（代码已有） | 第二批 |
| P2-1/2 日志 + 云对接 | 高 | 高 | 中高 | 第二批 |
| P2-4 自定义数据源 | 中 | 中 | 低 | 第二批 |
| P3-1 i18n | 中（海外） | 中 | 中 | 与 P2 并行 |
| P3-2 上架 | 高（增长） | 高 | 中高 | P2 之后 |

---

## 七、度量指标（用于验证修复是否真的发生）

| 指标 | 当前 | 3 个月目标 |
|------|------|-----------|
| 发版间隔（Release → Release） | 约 2–6 周，且可中断（v3.0.0 后 6 周未发布） | ≤ 3 周，且 tag 即发布 |
| 公开版本与仓库版本差 | 落后 2 个 versionCode | 0（发布流程自动化） |
| “无调用点功能类 / 无效设置项” | ≥3（`AprsHttpUploader`、`useTls`、`SstvEncoder`） | 0，CI 校验 |
| i18n 覆盖率（中文/英文条目比） | 81 / 367 ≈ 22% | ≥95%（含硬编码清零） |
| APK 体积 | 21.8 MB | ≤ 15 MB（ABI/语言裁剪） |
| 射频通路数（APRS/FT8/SSTV） | 0 条完整闭环 | ≥2 条（APRS AFSK + FT8 CAT/PTT） |
| 核心 data 层测试覆盖率 | 有报告无阈值 | ≥60% 且 CI 卡口 |
| 社区反馈缺陷闭环率 | HamCQ 2 项未回应 | 已回应并在 Release Note 标注修复 |

---

## 八、风险与“不建议做”的事

1. **不建议追求与 FT8CN/WSJT-X 的完全功能对等**：深度解码（AP 解码、多通道并行 FFT、Fox/Hound）是多年积累，人力不匹配。建议**把 FT8 定位为“能通、能记、能对上”**，把差异化让给“卫星过境 + 一体化 + 中文体验”。
2. **不建议在无持照场景下默认开启任何发射能力**：APRS iGate/digipeater、FT8/SSTV 发射都必须默认关闭 + 明确合规提示（中国境内需遵守《无线电管理条例》；FT8CN 的免责声明是很好的先例）。
3. **不要把“三碎片密钥”当作安全机制**（见 4.4）；用户凭据类数据必须走 Android Keystore。
4. **不要在没有 CI 门禁的情况下继续扩张功能面**：本项目的真实短板不是“功能不够多”，而是“已有功能无法稳定到达用户”。
5. **不要同时维护 Material 与 Miuix 两套完整 UI**：当前 `ui/screen/*Material.kt` 与 `*Miuix.kt` 成对存在（如 `SatelliteManagementScreen.kt` 49 KB + `SatelliteManagementMaterial.kt` 49 KB），维护成本翻倍。建议冻结一套为主（Miuix 为默认、Material 仅做兼容层）或抽象共用组件。
6. **包名与签名只能再动一次**：迁移后写进 CONTRIBUTING 并加 CI 校验，否则每次改名都等于一次用户流失。

---

## 九、证据清单（可复核）

### 9.1 竞品公开资料

- APRSdroid：[aprsdroid.org](https://aprsdroid.org/)（APRS-IS / AFSK / 蓝牙 TNC、离线地图、消息收发）；GitHub [ge0rg/aprsdroid](https://github.com/ge0rg/aprsdroid)（604★、GPL-2.0、2010 年起）
- Look4Sat：GitHub [rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat)（1498★、GPL-3.0；README：9000+ 卫星、10 天预测、离线优先、自定义 TLE 导入、F-Droid + Play）；[Release v4.4.4](https://github.com/rt-bishop/Look4Sat/releases/tag/v4.4.4)（Icom CAT 支持、线性转发器多普勒、自定义数据源）；[Release v4.4.7](https://github.com/rt-bishop/Look4Sat/releases/tag/v4.4.7)（硬编码字符串抽到资源 en/zh、AMSAT 状态上报）；APK 8.0 MB（v4.4.8 Release 资产）
- Gpredict：[Debian 包页](https://packages.debian.org/sid/gpredict)（Hamlib rigctld 多普勒调谐 + rotctld 旋转器控制）
- FT8CN：[N0BOY/FT8CN](https://github.com/N0BOY/FT8CN)（632★、MIT）；[README](https://raw.githubusercontent.com/N0BOY/FT8CN/master/README.md)（多机型 CAT/PTT 致谢、Cloudlog/QRZ 自动上传、呼号归属地数据）
- Robot36：[xdsopl/robot36](https://github.com/xdsopl/robot36)（515★、0BSD，描述为 “Decode SSTV encoded audio signals to images”）
- Ham2K PoLo：[ham2k.com/polo](https://ham2k.com/polo/)（Android/iOS/桌面/Web、POTA/SOTA/WWFF）；[ham2k/app-polo](https://github.com/ham2k/app-polo)
- IZ2UUF Morse Koch CW：[功能页](https://www.iz2uuf.net/cw/about.php)（Farnsworth、难点自适应、息屏免看、语音校验、QSO 生成器、100+ 国际字符与 prosign）
- ISS Detector（仅列作同类竞品，因商店页面访问受限未逐项核实）：[Play 列表](https://play.google.com/store/apps/details?id=com.runar.issdetector)

### 9.2 平台政策与合规

- Google Play [REQUEST_INSTALL_PACKAGES](https://support.google.com/googleplay/android-developer/answer/16558241)、[USE_EXACT_ALARM](https://support.google.com/googleplay/android-developer/answer/15225428)
- [高德地图开放平台隐私权政策](https://amap.wezhan.cn/pages/privacy/)
- ADIF 规范（导出字段依据）：[adif.org](https://adif.org/)

### 9.3 本项目仓库证据（文件级）

| 主题 | 位置 |
|------|------|
| 版本/包名/SDK | `build.gradle.kts`、`app/build.gradle.kts`、`gradle/libs.versions.toml` |
| 敏感文件入库与密钥碎片 | `app/src/main/assets/secrets.dat`、`app/build.gradle.kts`（`CRYPTO_SHARD_A_BYTES` / `SHARD_B_RAW` / `SHARD_C_SALT`）、`app/src/main/java/com/example/hamkit/data/crypto/SecretManager.kt`、`docs/CRYPTO_DESIGN.md` |
| 权限与政策风险 | `app/src/main/AndroidManifest.xml`（`REQUEST_INSTALL_PACKAGES`、`USE_EXACT_ALARM`、高德 key meta-data） |
| APRS 通路与解析 | `data/aprs/AprsConnection.kt`、`data/aprs/AprsModels.kt:52`、`data/aprs/AprsSettingsStore.kt:31`、`data/aprs/AprsHttpUploader.kt`、`data/aprs/AprsPacketParser.kt:44` |
| SSTV 仅收 | `data/sstv/SstvEncoder.kt:39`（无调用点）、`ui/screen/sstv/SstvSettingsScreen.kt`（“导出到相册属二期”） |
| 卫星数据源与多普勒 | `data/satellite/SatelliteDataSource.kt:68`、`data/satellite/DopplerCalculator.kt`、`ui/screen/satellite/SatelliteDetailScreen.kt:169` |
| 日志与导出 | `data/qso/QsoStore.kt`、`data/qso/QsoExporters.kt:83`、`ui/screen/ft8/QsoLogScreen.kt`、`docs/QSO记录与导出功能的设计方案.md` |
| i18n | `res/values/strings.xml`（367）、`res/values-zh-rCN/strings.xml`（81）、`res/values-de/strings.xml`（8） |
| CI/发布 | `.github/workflows/ci.yml`、`app/build.gradle.kts`（apksign）、PR #74（删除 release 工作流） |
| 待办需求 | `requirements/requirements/REQ-0003`、`REQ-0004`、`REQ-0005` |
| 既有缺陷分析 | `docs/FT8_DEFECT_ANALYSIS.md`、`docs/AUDIT_SATELLITE_CATEGORY.md`、`docs/BUG_FIX_REPORT.md` |
| 测试规模 | `app/src/test`（42 文件）、`app/src/androidTest`（5 文件） |

### 9.4 历史缺陷与发布记录

- [PR #91](https://github.com/fuxue-linkong/HamKit/pull/91)：`active` TLE 源 30 s 超时被静默跳过 → 卫星只剩 ~600 颗
- [PR #92](https://github.com/fuxue-linkong/HamKit/pull/92)：持久化开关残留 + 部分结果覆盖缓存；并自述“设备上仍是 versionCode=16，修复要下次发版才到达用户”
- [PR #90](https://github.com/fuxue-linkong/HamKit/pull/90)：TLE 源切到自建 CDN `tle.hamkit.click`（S3 + CloudFront）
- [PR #71](https://github.com/fuxue-linkong/HamKit/pull/71)：仰角/方位角弧度未换算，显示 0°/1°
- [PR #70](https://github.com/fuxue-linkong/HamKit/pull/70)：FT8 重构为标准实现，并**禁用** release 打包工作流
- [Releases](https://github.com/fuxue-linkong/HamKit/releases)：v2.1.1（“签名不一致请卸载重装”）、v3.0.0（“包名变更需重新授权”，versionCode 16，APK 21.8 MB）
- 社区反馈：[HamCQ #8940](https://forum.hamcq.cn/d/8940)（输入法遮挡、返回键失效）

### 9.5 尚未核实 / 需人工确认的事项

1. ISS Detector 的具体功能项（商店页面在当前网络策略下无法抓取，仅作同类竞品列出）。
2. Google Play 对中国大陆开发者账号与高德 SDK 的实际审核结论（政策条文已引，落地结论需以官方审核为准）。
3. `hiddenapibypass` 当前实际用在哪里、是否仍必需（需作者确认后可移除）。
4. 双 UI（Material / Miuix）中哪一套是长期主线（本报告按 `miuix` 为默认分支推断）。
5. 卫星预测在低端机上的真实耗时/内存基线（需真机 profiling，本报告未做性能实测）。

---

## 十、给下一个版本的最小可行清单（可直接开工）

1. **P0-1 本地一键发版**（`scripts/release.ps1`：单测 → 签名打包 → 验签 → SHA-256 → S3 → CloudFront → 发布载荷；不再依赖 GitHub Actions，见 `docs/RELEASE_LOCAL.md`）
2. **P0-2 发布 3.0.2**（含 #91/#92 + 返回键 + 输入法修复）
3. **P0-5 清理 3 处死代码/假开关**（`AprsHttpUploader`、`useTls`、`SstvEncoder` 标注）
4. **P0-6 修正 `docs/CRYPTO_DESIGN.md` 的安全声明**
5. **P1-4 设计 `RigControl` 抽象层（先只做接口与 CI-V 一种实现）**
6. **P1-1 AFSK 1200 调制/解调原型 + 回环测试**

> 完成 1–4 即恢复“可信交付”；完成 5–6 即打开“射频闭环”，后续 P1/P2 才能顺着这两条骨干长出来。
