# HamKit v3.0.0 更新日志

> 版本号：3.0.0（versionCode 16）
> 发布日期：2026-08-17
> 提交范围：`5ab707f`(#74) → `60c0ac7`
> 涵盖提交：40 个（2026-08-08 ~ 2026-08-17）

本次为大版本更新：应用正式更名为 **HamKit**，卫星功能以自研 SGP4/SDP4 引擎重写并参考 Look4Sat 重做雷达图，TLE 卫星源与 APK 下载全面接入 hamkit.click CDN 加速，并修复多项功能与并发缺陷。

---

## 一、更新总览

| 类别 | 模块 | 简述 |
|------|------|------|
| 重大更新 | 全局 | 应用更名为 HamKit，同步包名/主题/APK 文件名 |
| 重大更新 | 图标 | 全新自适应应用图标，适配 Android 8.0+ |
| 重大更新 | 卫星 | 自研 SGP4/SDP4 轨道引擎替代 predict4java |
| 功能 | 雷达 | 参考 Look4Sat 重写雷达图 |
| 功能 | 卫星 | SatNOGS 转发器频率库 + 名称兜底 |
| 功能 | 卫星 | TLE 源走 tle.hamkit.click CDN 加速 |
| 功能 | 卫星 | 更新卫星源改为乐观先行 |
| 功能 | 首页 | 时间卡默认背景 the_moon + 动态取色 |
| 功能 | 首页 | 未授权时仅显示权限引导卡 |
| 功能 | 更新 | APK 下载走 apk.hamkit.click 加速 |
| 功能 | 更新 | 更新日志优先官网拉取，回退 GitHub |
| 修复 | 兼容性 | RuntimeShader 仅 API 33+ 实例化 |
| 修复 | 天气 | secrets.dat 解密失败 |
| 修复 | 卫星 | active 源超时导致列表仅 600 颗 |
| 修复 | 关于页 | 加载自适应图标崩溃 |
| 修复 | 多项 | 坐标转换/线程竞态/历元精度等 |

---

## 二、重大更新

### 1. 应用更名为 HamKit

- 应用名：「双区网络定位」→ **HamKit**
- 包名：`com.example.radioarealocator` → `com.example.hamkit`
- Application 类：`RadioAreaLocatorApplication` → `HamKitApplication`
- Theme 函数：`RadioAreaLocatorTheme` → `HamKitTheme`
- 资源主题：`Theme.RadioAreaLocator` → `Theme.HamKit`
- APK 文件名、APRS 标识、User-Agent 等同步更新

> 相关提交：`750a6c7`、`29bda48`(#76)

### 2. 全新自适应应用图标

- 新增自适应图标 `mipmap-anydpi-v26`（白底 background + 透明 foreground）
- 生成全密度 mipmap 图标（mdpi ~ xxxhdpi）及圆形 roundIcon
- 保留传统 fallback 图标，兼容老版本安卓

> 相关提交：`7857e28`

### 3. 自研 SGP4/SDP4 轨道引擎

以自研轨道传播引擎替代 predict4java，predict4java 降为 `testImplementation`（仅差分验证）。

新增能力：

- `data/satellite/predict/`：自研 SGP4/SDP4 轨道传播引擎（`TleElements` / `SatellitePropagator`）
- `CelestialComputer`：日月位置 + 蚀检测
- `DopplerCalculator`：多普勒频移计算
- `TleParser` / `SatelliteListItem` / `SatelliteModes` / `RadioInfo` / `RadioInfoRepository`
- `TransmitterDataSource` / `TransmitterDatabase`（SatNOGS 转发器数据库）
- `SatelliteSession` / `SatelliteCompass`：实时追踪会话与手机朝向传感器
- `SatelliteDetailScreen` / `SatelliteMapScreen` / `SatelliteOrbitMap`：详情/地图页
- 单元测试：SGP4 差分测试、日月位置、多普勒、TLE 解析等

> 相关提交：`ef61e9a`(#81)、`cde3adc`(#84)

---

## 三、卫星功能

### 1. 参考 Look4Sat 重写雷达图

重写 `RadarView.kt`（Compose Canvas）：

- 新增 `SweepGradientShader` 旋转扫描波束动画（8s 一圈）
- 新增整图罗盘旋转模式（heads-up，随手机朝向旋转）
- 新增卫星轨迹路径（三角箭头 `StampedPathEffect` 标记方向）
- 新增日月矢量图标渲染（`ic_sun` / `ic_moon` drawables）
- 新增瞄准十字线（罗盘模式下在地平环指示设备朝向）
- 新增 180 翻转选项（适合车载架设）
- 坐标系统改用 Look4Sat 的 `(PI_2 - elev) / PI_2` 径向映射

`LiveSatelliteTracker` 新增 `trackHistory: StateFlow`，自动采集最近 150 个轨迹点，卫星低于 -5 仰角时自动清空轨迹历史。

> 相关提交：`ef61e9a`(#81)

### 2. 雷达图视觉完全对齐 Look4Sat

- 移除深色背景填充（Look4Sat 无此元素）
- 移除方位角刻度与 N/E/S/W 标签
- 虚线十字改为实心水平/垂直线
- 仰角标签改为 15.sp 沿纵轴定位
- 轨迹线宽统一为 `STROKE_WIDTH = 6f`
- 绘制顺序严格匹配：sweep → 网格 → 标签 → track → 卫星 → 太阳 → 月亮 → aim

> 相关提交：`342f3ca`(#85)

### 3. SatNOGS 转发器频率库

- 转发器展示名称优先取 `description`，其次 `name`，剔除 null/空白/中括号
- 均缺失时按「模式 + 频率」生成名称，不再显示「未命名转发器」（参照 Look4Sat）
- 数据源清洗 JSON null 字段（`optString` 返回的字面量 null）与中括号
- Room 转发器缓存库 v2 → v3，清空历史含 null 的旧缓存
- `MainViewModel` 集成转发器频率库与预测范围过滤（`filterPredictableTles`）
- 单元测试：`TransmitterDataSource` 解析与 `displayName` / `displayMode` 边界（17 项）

> 相关提交：`cde3adc`(#84)、`342f3ca`(#85)

### 4. TLE 卫星源走 CDN 加速

将 TLE 数据源从直连 `celestrak.org` 切换到 `tle.hamkit.click` CDN（S3 源站 + CloudFront 分发），解决国内访问国际源不稳定的问题。

- 客户端保留全部解析/合并/去重逻辑不变，仅切换 URL 常量
- 四个 URL 常量改指向 `tle.hamkit.click/tle/*.3le|.csv`
- 新增服务器端镜像脚本 `scripts/mirror_celestrak.sh`，定时从 CelesTrak 拉取并上传到 S3

> 相关提交：`3b68391`(#90)

### 5. 更新卫星源改为乐观先行

点击「更新卫星源」瞬间用现有缓存就地回显（有定位则立即重算预测，命中 15 分钟预测缓存时 loading 瞬间结束），最新 TLE 在后台从 CDN 静默拉取后替换结果，UI 在 100ms 内完成响应，与网络无关。

> 相关提交：`3b68391`(#90)

---

## 四、首页

### 1. 时间卡默认背景 the_moon

- 无自定义背景时回退到打包的 `the_moon.jpg` 原始字节，不重压缩
- 用户设置自定义背景时优先使用自定义图
- 支持自定义背景裁剪与 MaterialKolor 动态取色

> 相关提交：`f2ab162`

### 2. 调整时间卡字号

UTC 时间 17.6sp → 26sp，天气温度 26sp → 17.6sp。`WeatherCard` 新增 `temperatureFontSize` 参数，独立天气卡保持默认 26sp 不变。

> 相关提交：`ba14f65`

### 3. 未授权时仅显示权限引导卡

未授权时主页只保留权限引导卡，隐藏每日言、天气及 CW/APRS/FT8 功能入口；已授权后完整显示。移除不再使用的 `DailyQuoteCard` 及 `TonalCard` import。

> 相关提交：`056bc37`

---

## 五、更新与下载

### 1. APK 下载走 CloudFront 加速

APK 下载走 `apk.hamkit.click` CloudFront 加速，与官网一致。

> 相关提交：`01c1f87`(#86)

### 2. 更新日志优先官网拉取

`checkNewVersion()` 改为两级策略：先请求官网 `hamkit.click/update.json`（带 5s/8s 短超时，复用 `radioApp.okhttpClient` 连接池），任何失败（连接超时/DNS/非 2xx/JSON 解析失败/versionCode ≤ 0/downloadUrl 为空）均回退到原 GitHub Releases API 路径。

- GitHub 逻辑抽为 `checkNewVersionFromGitHub()`，行为不变
- 新增 `parseUpdateJson()` 解析单个 JSON 对象，字段与 `LatestVersionInfo` 一一对应
- 下游 `SettingsViewModel`、UI 全部无需改动
- 新增 3 个 `parseUpdateJson` 单测；`DownloaderTest` 共 7 个测试全过

> 相关提交：`b0a697a`(#87)

---

## 六、问题修复

### 兼容性

- **RuntimeShader 闪退**：`RuntimeShader` 仅在 API 33+ 实例化，修复老版本安卓 `NoSuchMethodError` 闪退。(`fe2efc3` #77)
- **精确闹钟权限崩溃**：`canScheduleExactAlarms` 无版本判断导致 Android 8-11 崩溃。(`10c1c09`)
- **关于页图标崩溃**：Android 8.0+ 上 `R.mipmap.ic_launcher` 解析为 adaptive-icon XML，Compose 的 `painterResource` 只支持 VectorDrawable 与位图导致崩溃。改用 `PackageManager.getApplicationIcon()` 获取系统渲染好的图标并转为 `BitmapPainter`。(`60c0ac7`)

### 卫星

- **active 源超时被跳过**：App 只显示约 600 颗卫星，active 源含全部活跃卫星 16k+ 却未贡献。根因是 `active.csv`（2.4MB，gzip 后 900KB）在 30 秒读取超时内未读完被 `runCatchingCancellable` 静默跳过。修复：给 active 源单独的 90 秒读取超时客户端；`enableActive` 默认值从 false 改为 true；`ReminderRefreshWorker` 显式传入 settingsStore 的三个 TLE 源开关。(`b02f411` #91)
- **卫星历元亚秒精度丢失**：修复历元丢失亚秒精度。(`10c1c09`)
- **日月位置误用卫星星下点**：修复日月位置误用卫星星下点。(`10c1c09`)
- **轨迹历史跨线程竞态**：修复轨迹历史跨线程竞态。(`10c1c09`)
- **TleElements 儒略日参数错位**：修复 `TleElements.epoch` 儒略日参数错位、`getTrack` 步长校验。(`10c1c09`)

### 天气

- **secrets.dat 解密失败**：包名从 `com.example.radioarealocator` 改为 `com.example.hamkit` 后，`ShardC(SHA-256(packageName+SALT))` 变化，导致 `secrets.dat` 无法解密，`amap.api.key` 为空，天气抛 `ApiKeyMissingException`。用新包名重新生成 `secrets.dat`。并修复直辖市 regeo 返回 `city` 为空数组 `[]` 时 `optString` 得到 `"[]"` 导致省名回退失效、城市名显示为 `[]` 的问题。(`89112f7`)

### 定位与地图

- **GCJ02 坐标转换错误**：`X_PI` 误用为 π，导致地图标记偏移。(`10c1c09`)
- **定位精度为 0 误判**：定位精度为 0 被误判为高精度缓存。(`10c1c09`)

### FT8 / APRS / CW

- **FT8 录音读线程永不退出**：修复 FT8 录音读线程永不退出及 `AudioRecord` 双重释放竞态。(`10c1c09`)
- **AMSAT 抓取失败**：修复 AMSAT 抓取非英文 locale 解析失败。(`10c1c09`)
- **CW 摩尔斯间隔**：修复 CW 摩尔斯字符/词间隔多 1 单位。(`10c1c09`)
- **APRS 多项缺陷**：修复 APRS 消息编号正则、压缩坐标未取整、passcode locale、会话排序。(`10c1c09`)

### 更新检查

- **误报已是最新版本**：改用 releases 列表接口（含预发布），避免 `/releases/latest` 跳过 beta；`versionCode` 提取改为两级回退（body 的 Version 行 → APK 文件名）；新增 `isNewerVersion` 语义化版本比较；新增 `VersionCompareTest` 覆盖预发布与边界用例。(`b8ae57e` #78)

### 其他

- **背景图流泄漏**：修复背景图流泄漏。(`10c1c09`)
- **广播接收器主线程 IO**：修复广播接收器主线程 IO。(`10c1c09`)
- **通知误清**：修复通知误清。(`10c1c09`)
- **OkHttp UA 统一**：统一 OkHttp User-Agent。(`10c1c09`)

---

## 七、官网与文档

- 官网品牌标题由「双区网络定位」改为 HamKit，修正仓库链接为 `fuxue-linkong/HamKit`，新增 FT8 / APRS 功能卡片，卫星描述更新为自研 SGP4/SDP4 引擎。(`9b478e9`)
- 应用版本实时从 GitHub 拉取，`version.json` 仅作兜底；缓存键改为 `hamkit-latest-version`。(`2ea6a04`)
- 移除网站及 GitHub Pages 部署工作流。(`bdad2c8`)
- 同步 README 与最新项目现状：路线图、卫星过境预测、首页时间卡、技术栈、Issues 链接。(`664b865` #88)
- 新增英文 README 并从中文版链接。(`da2d9ca`)
- 新增 QQ 群二维码到 README。(`1cc60d2`、`10f0b8a`)

---

## 八、基础设施与 CI

- **relay 工作流修复**：显式设置 `GH_REPO`，避免未 checkout 时 `gh` 无法推断仓库而失败。(`600ad2f` #75)
- **统一刷新加载动画**：全站 `CircularProgressIndicator` / `LoadingIndicator` 统一为 `InfiniteProgressIndicator`（WeatherCard、SatelliteManagement、UpdateDialogs、MarkdownContent 等）。(`f07436a`)

---

## 九、升级说明

- **版本号**：3.0.0（versionCode 16）
- **下载地址**：`https://apk.hamkit.click/release/HamKit_3.0.0_16-release.apk`
- **更新日志**：优先从 `hamkit.click/update.json` 拉取，服务器不可达时回退 GitHub Releases
- **兼容性**：Android 8.0+（RuntimeShader 相关特性仅 API 33+ 生效，不影响低版本运行）
- **数据迁移**：Room 转发器缓存库 v2 → v3，升级后自动清空历史含 null 的旧缓存并重新拉取

> 由于包名变更，旧版本覆盖安装后需重新授权定位等权限；天气密钥已随新包名重新生成，无需手动操作。
