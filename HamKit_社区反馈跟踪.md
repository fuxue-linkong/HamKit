# HamKit 社区反馈整合（BUG / 需求 / 时间线）

> 数据来源：HamCQ 论坛帖 <https://forum.hamcq.cn/d/8940>（21 楼全量，2026-08-09 ~ 2026-10-06）、GitHub <https://github.com/fuxue-linkong/HamKit> issues 与 PR、本地仓库 `miuix` 分支代码定位。

## 一、概览

| 项目 | 数量 |
| --- | --- |
| BUG 条目 | 13（帖子报告 3 个 + 仓库已修 10 个） |
| 需求条目 | 4 |
| 论坛楼层记录 | 21 |
| 数据来源 | 6 |

## 二、BUG 跟踪

| 编号 | 标题 | 严重级别 | 分类 | 状态 | 来源 | 报告人 | 报告时间 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| HK-BUG-001 | APRS 设置页「位置上报」输入框被输入法键盘遮挡 | 中 | UI / 软键盘适配 | 已修复（AVD 验证） | HamCQ 帖子 https://forum.hamcq.cn/d/8940/9 | BG5LU (HamCQ uid 1107) | 2026-08-23 |
| HK-BUG-002 | Android 虚拟导航栏返回键全应用失效，主页无法返回桌面 | 高 | 系统交互 / 导航 | 已修复（AVD 验证） | HamCQ 帖子 https://forum.hamcq.cn/d/8940/9 | BG5LU (HamCQ uid 1107) | 2026-08-23 |
| HK-BUG-003 | APRS-IS passcode 合规问题：App 内自动计算、显示并自动填充验证码 | 高（合规） | 合规 / 功能设计 | 已整改（AVD 验证） | HamCQ 帖子 https://forum.hamcq.cn/d/8940/18 与 https://forum.hamcq.cn/d/8940/21 | BH6SVA_AC3QU (HamCQ uid 8857) | 2026-10-06 |
| HK-BUG-004 | 卫星过境列表最大仰角显示 0°/1° | 中 | 卫星预测 / 单位换算 | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/71 | 开发团队自测 (2048lr) | 2026-08-07 |
| HK-BUG-005 | 部分卫星过境时长超出合理范围的预测异常 | 中 | 卫星预测 | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/82 | 开发团队自测 (2048lr) | 2026-08-14 |
| HK-BUG-006 | 过境结束后仍显示「0 秒」 | 低 | 卫星预测 / UI | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/83 | 开发团队自测 (2048lr) | 2026-08-14 |
| HK-BUG-007 | 卫星列表只有约 600 颗（active 源未生效） | 高 | 卫星数据源 | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/91 | 开发团队自测 (2048lr) | 2026-08-16 |
| HK-BUG-008 | 刷新卫星源后目录降级为约 672 颗并覆盖缓存 | 高 | 卫星数据源 / 缓存 | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/92 | 开发团队自测 (2048lr) | 2026-08-17 |
| HK-BUG-009 | 老版本安卓崩溃：RuntimeShader 在 API < 33 被无条件实例化 | 高 | 兼容性 / 渲染 | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/77 | 开发团队自测 (2048lr) | 2026-08-14 |
| HK-BUG-010 | 检查更新误报「已是最新版本」 | 中 | 应用更新 | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/78 | 开发团队自测 (2048lr) | 2026-08-14 |
| HK-BUG-011 | 官网版本号 badge 更新不及时 | 低 | 官网 / CI | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/72 | 开发团队自测 (2048lr) | 2026-08-07 |
| HK-BUG-012 | relay 工作流 gh 无法推断仓库导致 Pages 部署失败 | 低 | CI | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/75 | 开发团队自测 (2048lr) | 2026-08-08 |
| HK-BUG-013 | Release 触发的 Pages 部署被 tag 环境保护规则拒绝 | 低 | CI | 已修复 | https://github.com/fuxue-linkong/HamKit/pull/73 | 开发团队自测 (2048lr) | 2026-08-07 |

### BUG 详情

#### HK-BUG-001　APRS 设置页「位置上报」输入框被输入法键盘遮挡

- **严重级别**：中　**分类**：UI / 软键盘适配　**状态**：已修复（AVD 实地验证；MIUI / API 35+ 待补）
- **来源**：HamCQ 帖子 https://forum.hamcq.cn/d/8940/9　**报告人**：BG5LU (HamCQ uid 1107)　**报告时间**：2026-08-23
- **关联 Issue**：仓库内 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-4（未开公开 issue）

**现象**

> APRS 设置 → 位置上报的两个编辑框，编辑时被弹出的输入法键盘挡住内容，用户看不到正在输入的内容。用户附 3 张截图。

**根因（代码位置）**

> 已确认（CONFIRMED），三层叠加缺一不可：
> ① [build.gradle.kts](build.gradle.kts#L12-L14) compileSdk/targetSdk = 37（≥35）→ Android 15+ 边到边强制，Manifest 里的 adjustResize 失效；
> ② [MainActivity.kt](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L143-L156) enableEdgeToEdge() 执行 setDecorFitsSystemWindows(false)，窗口不再被 IME 顶起，IME inset 必须由 Compose 层消费；
> ③ [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L79-L90) Scaffold 的 contentWindowInsets 被显式限制为 only(Horizontal)，把底部/IME inset 从 innerPadding 剔除，且整页无 imePadding()；
> ④ 出问题的两个输入框正好在长列表底部 [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L159-L177)（评论 L164-169、发送间隔 L171-177），列表末尾仅 Spacer(32.dp) [L228]，无 BringIntoViewRequester → 键盘直接覆盖且页面不上滚。
> 对照：同功能目录下已修好的写法见 [AprsMessageScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsMessageScreen.kt#L109-L109) L109 的 .imePadding()。
> 同类未修页面：Ft8SettingsScreen.kt L88/L101、QsoLogScreen.kt L209。仓库内已有同题记录 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-4。

**修复建议**

> ~~最小改动：给 [AprsSettingsScreen.kt L82-90](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L82-L90) 的滚动 Column 加 .imePadding()…~~
>
> **实际实施（已完成）**
>
> 按建议的最小改动 + 加强方案一并落地，并扩展到反馈中点名的同类页面：
> 1. [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L84-L99) 滚动 Column 加 `.imePadding()`（键盘高度变为可滚动底部内边距）＋ `.imeNestedScroll()`（聚焦输入框自动滚入可见区，需 `@OptIn(ExperimentalLayoutApi::class)`）；
> 2. 同类页面统一整改：[Ft8SettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/ft8/Ft8SettingsScreen.kt#L76-L92)、[QsoLogScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/ft8/QsoLogScreen.kt#L193-L202)（列表）与其编辑弹窗 [QsoEditDialog](app/src/main/java/com/example/hamkit/ui/screen/ft8/QsoLogScreen.kt#L463-L463)；
> 3. `Scaffold.contentWindowInsets` 保持仅 Horizontal，避免 IME inset 被重复消费；不再依赖 Manifest `adjustResize`（边到边下已失效，按建议保留但不再作为修复手段）；
> 4. 验证：`:app:compileDebugKotlin` 通过；**AVD 走查已通过**（见 §6.2；MIUI / API 35+ 机型待补）。
> 对照参考：同功能目录下既有正确写法 [AprsMessageScreen.kt L109](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsMessageScreen.kt#L109-L109) 的 `.imePadding()`。

#### HK-BUG-002　Android 虚拟导航栏返回键全应用失效，主页无法返回桌面

- **严重级别**：高　**分类**：系统交互 / 导航　**状态**：已修复（AVD 实地验证；MIUI / 手势导航 ROM 待补）
- **来源**：HamCQ 帖子 https://forum.hamcq.cn/d/8940/9　**报告人**：BG5LU (HamCQ uid 1107)　**报告时间**：2026-08-23
- **关联 Issue**：仓库内 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-3（未开公开 issue）

**现象**

> 底部有虚拟返回键的手机上，系统返回键无效，只能点左上角箭头返回；返回到主页后返回键依旧无效，无法退出应用回到桌面。用户附 2 张截图。

**根因（代码位置）**

> 根因一（CONFIRMED，缺失实现）：应用层完全没有自己的返回键接线。
> [MainActivity.kt](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L114-L114) 仅继承 ComponentActivity，全文 531 行无 onBackPressed / onKeyDown / dispatchKeyEvent / OnBackPressedCallback；全仓 ui/ 下 grep finish() / moveTaskToBack 零命中 → 应用自身从未实现「主页返回退出」。
> 全仓未使用 Navigation Compose（NavHost/rememberNavController 零命中），而是自定义 [Navigator.kt](app/src/main/java/com/example/hamkit/ui/navigation3/Navigator.kt#L19-L22)（backStack + pop()）。
> 返回键只有两个挂载点：[MainActivity.kt L184-193](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L184-L193) 的 NavDisplay(onBack = { navigator.pop() })（子页），和 [MainActivity.kt L413-L433](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L413-L433) 的 MainScreenBackHandler。
> 后者 L418-421 的启用条件是「栈深 1 且 selectedPage != 0」，**主页恒为 false**；其回调也只做 animateToPage(0)，没有任何 finish()。
> 于是主页场景下没有任何 enabled 的 handler，退到桌面完全依赖框架 fallback。
> 根因二（SUSPECTED，子页返回也失效）：NavDisplay 并非官方 androidx.navigation3:navigation3-ui，而是 Miuix fork 打包的 miuix-navigation3-ui 0.9.3（AAR 内含 androidx/navigation3/{ui,scene}），却与官方 navigation3-runtime 1.1.4、navigationevent 1.1.2、activity 1.13.0 混用，疑致 navigationevent 回调链未触发。
> 需真机 logcat（NavigationEventDispatcher / OnBackPressedDispatcher / OnBackInvoked）、adb dumpsys activity 栈变化、官方 navigation3-ui 同版本最小复现来收口。
> 另：本文档中 [BottomBar.kt](app/src/main/java/com/example/hamkit/ui/component/bottombar/BottomBar.kt#L37-L68) 切页只动 pager，不碰 backStack，故顶层恒为 [Route.Main]。
> 已排除：KeyEventBlocker.kt L14-28（无调用点）；[SuperSearchBar.kt](app/src/main/java/com/example/hamkit/ui/component/miuix/SuperSearchBar.kt#L190-L204) 的 NavigationBackHandler 硬编码 isBackEnabled = true（危险但当前是死代码，全仓无调用点）；另一处 BackHandler 在 [CWPracticeRouteScreen.kt L52](app/src/main/java/com/example/hamkit/ui/cw/CWPracticeRouteScreen.kt#L52-L52)（CW 页内部，与主路径无关）。
> 仓库内已有同题记录 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-3。

**修复建议**

> 1) 在 [MainActivity.kt L176-L240](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L176-L240) 的 HamKitTheme 内、NavDisplay 之外加一个总兜底返回处理器（androidx.activity.compose.BackHandler，或 onBackPressedDispatcher.addCallback，后者完全不依赖 navigationevent）：
>    enabled = true；内容 = if (navigator.backStackSize() > 1) navigator.pop() else if (mainPagerState.selectedPage != 0) mainPagerState.animateToPage(0) else finish()（需防误触可加双击返回退出）。
> 2) 收敛 handler 优先级：精简/删除 [MainScreenBackHandler](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L413-L433)，把 pager 分支并入根 handler，消除「有 handler 注册但全 disabled」的空窗；同时修掉 [SuperSearchBar.kt L192-203](app/src/main/java/com/example/hamkit/ui/component/miuix/SuperSearchBar.kt#L192-L203) 的 isBackEnabled = true 硬编码。
> 3) 可选降风险：NavDisplay 切回官方 androidx.navigation3:navigation3-ui，并把 navigation3-runtime / navigation3-ui / navigationevent 锁成同一版本（[libs.versions.toml L9-L10](gradle/libs.versions.toml#L9-L10)），Manifest 显式声明 android:enableOnBackInvokedCallback。
> 验收：MIUI / 原生 / 手势导航三机走查，子页返回与左上角箭头行为一致，主页返回可退出应用。
>
> **实际实施（已完成）**
>
> 1. 新增 [RootBackHandler](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L471-L503)，在 `HamKitTheme` 内、`NavDisplay` 之外注册 `androidx.activity.compose.BackHandler(enabled = true)`——它挂在 Activity 的 `OnBackPressedDispatcher` 上，**完全不经过 navigationevent 回调链**，因此不受根因二（Miuix fork NavDisplay 与官方 navigationevent 混用）影响；注册时机最早、处于回调栈最外层，只有内层不消费时才触发；
> 2. 优先级按建议实现：`backStackSize() > 1` → `navigator.pop()`；否则 `selectedPage != 0` → `animateToPage(0)`；否则 **双击返回退出**（首次按返回 Toast 提示「再按一次返回退出 HamKit」，2 秒内再按才 `finish()`，满足防误触要求）；
> 3. [MainScreenBackHandler](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L433-L456) 收敛为仅负责「主页面分页回到第 0 页」，主页退出交由根 handler，消除「有 handler 注册但全 disabled」的空窗；
> 4. [SuperSearchBar.kt](app/src/main/java/com/example/hamkit/ui/component/miuix/SuperSearchBar.kt#L190-L206) 的 `isBackEnabled = true` 硬编码改为 `searchStatus.shouldExpand()`，只在搜索栏展开时消费返回事件；
> 5. 验证：`:app:compileDebugKotlin` 通过、`:app:testDebugUnitTest` 496 项全绿；**AVD 走查已通过**（见 §6.2；MIUI / 手势导航 ROM 待补）。
> 6. 未做（保留为可选降风险项）：NavDisplay 切回官方 navigation3-ui 与依赖版本对齐 —— 因根 handler 已不依赖该回调链，风险已隔离，避免引入导航行为回归。

#### HK-BUG-003　APRS-IS passcode 合规问题：App 内自动计算、显示并自动填充验证码

- **严重级别**：高（合规）　**分类**：合规 / 功能设计　**状态**：已整改（AVD 实地验证只读会话与声明弹窗）
- **来源**：HamCQ 帖子 https://forum.hamcq.cn/d/8940/18 与 https://forum.hamcq.cn/d/8940/21　**报告人**：BH6SVA_AC3QU (HamCQ uid 8857)　**报告时间**：2026-10-06
- **关联 Issue**：GitHub Issue #93（open, label: bug）

**现象**

> App 在本地实现 APRS-IS passcode 算法，用户填呼号即得验证码；设置页直接展示并提示「留空自动计算」；登录时留空自动套用。
> APRS-IS 官方原文：Authors, YOU are responsible for issuing passcodes to amateur radio operators ONLY. Do not make this available in an ondemand fashion nor point your software users to someone else.
> （软件作者有责任仅向业余无线电操作者发放验证码，不得按需提供，也不得把用户指引到第三方获取。）

**根因（代码位置）**

> 根因 = 三处逻辑叠加，确认存在：
> ① 算法真源 [AprsPacket.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsPacket.kt#L9-L17) passcode()（0x73e2 成对 XOR & 0x7fff）；
> ② [AprsModels.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsModels.kt#L64-L68) computedPasscode（呼号为空返回 -1）；
> ③ [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L119-L133) label「Passcode (留空自动计算)」+ L129 内联重算展示；
> ④ [AprsConnection.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsConnection.kt#L54-L60) L57 登录留空回退 computedPasscode。
> 附带问题：设置页 L129 重复实现算法（未复用 config.computedPasscode）；label 硬编码中文未走 strings.xml；
> [AprsPacketTest.kt](app/src/test/java/com/example/hamkit/AprsPacketTest.kt#L10-L31) 只断言 >0 / <=32767 / 自洽，无金标准期望值，算法写错也能通过。

**修复建议**

> P0 移除 passcode 计算逻辑（删 AprsPacket.passcode() 与 AprsConfig.computedPasscode，同步删测试），确保算法不随发行包发布；
> P0 设置页去掉「计算 Passcode」展示，label 改为「Passcode（需自行持有，留空为只读）」；
> P0 留空时以只读方式连接（pass -1），可收报文不可注入；
> P0 首次登录前增加持照声明确认；
> P1 docs/ 增加合规说明，并复查官网 / README / 应用商店描述无 passcode 指引；
> 补充：为 passcode 增加已知呼号→已知期望值 的金标准单测（如 N0CALL=13023）。
>
> **实际实施（已完成）**
>
> | 建议项 | 落地情况 |
> | --- | --- |
> | 移除 passcode 计算逻辑 | ✅ [AprsPacket.passcode()](app/src/main/java/com/example/hamkit/data/aprs/AprsPacket.kt#L1-L30) 与 `AprsConfig.computedPasscode` 均已删除，算法不再随发行包发布；改为常量 `AprsPacket.READ_ONLY_PASSCODE = "-1"` |
> | 设置页去掉计算展示 | ✅ [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L129-L152) 删除内联重算展示；label 走 `strings.xml`（`aprs_passcode_label` = 「Passcode（需自行持有）」），并给出只读/持有两种提示 |
> | 留空只读连接（pass -1） | ✅ 新增 `AprsConfig.isReadOnly` / `effectivePasscode`；[AprsConnection.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsConnection.kt#L54-L68) 用 `effectivePasscode` 登录；[AprsViewModel](app/src/main/java/com/example/hamkit/ui/viewmodel/AprsViewModel.kt#L210-L230) 在 `transmitPosition` / `sendMessage` / `sendPendingMessages` / `sendAck` 四条注入路径全部拦截 |
> | 首次登录前持照声明 | ✅ 新增 `AprsConfig.licenseConfirmed` + `AprsSettingsStore.licenseConfirmed` 持久化；`requestConnect()` → 弹窗 → `confirmLicenseAndConnect()`，`connect()` 内保留防御性校验（未确认不得登录） |
> | docs/ 合规说明 | ✅ 新增 [docs/APRS_IS_COMPLIANCE.md](docs/APRS_IS_COMPLIANCE.md)（官方原文、整改前违规点、整改措施、对外答复口径、发布前复查清单）；官网 / README / README_EN 已 grep 复查，**无 passcode 指引** |
> | 金标准单测 | ⚠️ 按「移除算法」P0 优先执行了删除，未保留 N0CALL=13023 的算法期望值（保留即等于把算法继续留存在测试源中）。替代方案：① 反射**回归守卫**断言 `AprsPacket` 不再暴露任何 passcode 计算方法；② **金标准断言**只读登录串精确等于 `user N0CALL pass -1 vers …`、持有验证码时精确等于 `user N0CALL-7 pass 12345 vers …`（见 [AprsPacketTest.kt](app/src/test/java/com/example/hamkit/AprsPacketTest.kt#L20-L56)）；③ 新增 [AprsConfigTest.kt](app/src/test/java/com/example/hamkit/data/aprs/AprsConfigTest.kt) 覆盖只读语义 |
>
> 验证：`:app:testDebugUnitTest` 496 项全绿（`AprsPacketTest` 11 项含回归守卫与金标准、新增 `AprsConfigTest` 5 项）；**AVD 走查已通过**（见 §6.2：服务端回 `unverified`、收到 20 条报文、0 条上行、声明弹窗与持久化均验证）。

#### HK-BUG-004　卫星过境列表最大仰角显示 0°/1°

- **严重级别**：中　**分类**：卫星预测 / 单位换算　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/71　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-07
- **关联 Issue**：PR #71（merged）

**现象**

> 卫星过境列表的最大仰角显示为 0° 或 1°，明显不符合实际。

**根因（代码位置）**

> predict4java 的 SatPos 内部以弧度存储 elevation/azimuth，重写后的 SatellitePredictor 采样逻辑直接当角度使用。

**修复建议**

> 在 elevationAt / azimuthAt / maxElevationAzimuthAt 三处统一用 Math.toDegrees() 换算。

#### HK-BUG-005　部分卫星过境时长超出合理范围的预测异常

- **严重级别**：中　**分类**：卫星预测　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/82　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-14
- **关联 Issue**：PR #82

**现象**

> 部分卫星的过境时长预测值超出合理范围。

**根因（代码位置）**

> 过境判定/采样逻辑边界处理不当（详见 PR #82）。

**修复建议**

> 修正采样与 AOS/LOS 判定逻辑。

#### HK-BUG-006　过境结束后仍显示「0 秒」

- **严重级别**：低　**分类**：卫星预测 / UI　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/83　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-14
- **关联 Issue**：PR #83

**现象**

> 卫星过境已经结束，界面仍停留在显示 0 秒的倒计时状态。

**根因（代码位置）**

> 过境结束后的状态未清理（详见 PR #83）。

**修复建议**

> 过境结束后清除倒计时状态。

#### HK-BUG-007　卫星列表只有约 600 颗（active 源未生效）

- **严重级别**：高　**分类**：卫星数据源　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/91　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-16
- **关联 Issue**：PR #91（merged）

**现象**

> App 只显示约 600 颗卫星（satnogs + amateur 合并数量），active 源含 16k+ 却未贡献进列表；Look4Sat 同源数据有 16k+ 颗。

**根因（代码位置）**

> active.csv（2.4MB，gzip 后 900KB）在 30 秒读取超时内未读完，被 runCatchingCancellable 静默跳过（仅 Log.w），satnogs/amateur 仅几十 KB 仍成功，于是只剩约 600 颗且无任何错误提示。

**修复建议**

> ① active 源单独使用长超时客户端（readTimeout 90s）；② enableActive 默认值由 false 改为 true，与 SettingsStore.tleSourceActive 默认值一致；③ Worker 显式传参，与前台 MainViewModel 行为一致。

#### HK-BUG-008　刷新卫星源后目录降级为约 672 颗并覆盖缓存

- **严重级别**：高　**分类**：卫星数据源 / 缓存　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/92　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-17
- **关联 Issue**：PR #92（merged）

**现象**

> 点击「更新卫星源」后只剩约 672 颗，而 tle.hamkit.click 的 active.csv 含 16,340 颗且 HTTP 200 正常。

**根因（代码位置）**

> ① tle_source_active 是持久化 SharedPreferences 值，历史安装可能留下 false，且该开关没有 UI 可让用户恢复；前台 MainViewModel 与后台 ReminderRefreshWorker 都读该值，导致 active 源根本没参与请求；
> ② 单源失败可容忍，约 672 条部分结果会直接写入缓存，覆盖已有的 16k+ 全量目录。

**修复建议**

> ① 两处调用显式传 enableActive = true，不再读持久化开关；② 新增 MIN_ACTIVE_TLE_COUNT = 10_000，不足阈值抛 IOException 阻止降级覆盖；③ 新增 TleSourceUrls 便于注入测试；④ 错误文案改为真实 CDN 源名；⑤ 修正 TleParser 零值指数符号。

#### HK-BUG-009　老版本安卓崩溃：RuntimeShader 在 API < 33 被无条件实例化

- **严重级别**：高　**分类**：兼容性 / 渲染　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/77　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-14
- **关联 Issue**：PR #77

**现象**

> 运行 Android 13 以下版本的部分设备崩溃。FloatingBottomBar 会无条件创建 InteractiveHighlight，旧版本设备解析类字段时直接抛 NoSuchMethodError。

**根因（代码位置）**

> shader 字段非 lazy，在 API < 33 上类解析即失败；而 FloatingBottomBar 无条件创建 InteractiveHighlight。

**修复建议**

> 将 shader 改为 lazy，并用 Build.VERSION.SDK_INT >= TIRAMISU 守卫；API < 33 回退为普通白色按压高亮，与 BgEffectBackground / Lens 既有的 isRuntimeShaderSupported() 策略一致。

#### HK-BUG-010　检查更新误报「已是最新版本」

- **严重级别**：中　**分类**：应用更新　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/78　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-14
- **关联 Issue**：PR #78

**现象**

> 实际有新版本发布时，检查更新仍提示已是最新版本。

**根因（代码位置）**

> 版本比较逻辑缺陷（详见 PR #78）。

**修复建议**

> 修正版本检查逻辑。

#### HK-BUG-011　官网版本号 badge 更新不及时

- **严重级别**：低　**分类**：官网 / CI　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/72　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-07
- **关联 Issue**：PR #72

**现象**

> 发布新版本后官网 hero badge 仍显示旧版本号。

**根因（代码位置）**

> ① script.js 运行时请求 /releases/latest 会跳过预发布；② 匿名请求被限流（60 次/时/IP），失败后回退到写死的旧版本号；③ 部署工作流只监听 website/** 变更，发版不触发重新部署。

**修复建议**

> ① 部署时用 GITHUB_TOKEN 拉取最新 release（含预发布）烘焙成 website/version.json；② script.js 改为三级策略（本地 version.json → localStorage → /releases 列表接口）；③ 新增仓库内默认 version.json。

#### HK-BUG-012　relay 工作流 gh 无法推断仓库导致 Pages 部署失败

- **严重级别**：低　**分类**：CI　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/75　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-08
- **关联 Issue**：PR #75

**现象**

> relay 工作流报错：failed to run git: fatal: not a git repository。

**根因（代码位置）**

> gh workflow run 需要从当前目录 git 远程推断目标仓库，而 relay 工作流没有 actions/checkout，runner 工作目录无 .git。

**修复建议**

> 在 Dispatch 步骤显式设置 GH_REPO: ${{ github.repository }}。

#### HK-BUG-013　Release 触发的 Pages 部署被 tag 环境保护规则拒绝

- **严重级别**：低　**分类**：CI　**状态**：已修复
- **来源**：https://github.com/fuxue-linkong/HamKit/pull/73　**报告人**：开发团队自测 (2048lr)　**报告时间**：2026-08-07
- **关联 Issue**：PR #73

**现象**

> 发布 Release 时部署失败：Tag "v2.1.1" is not allowed to deploy to github-pages due to environment protection rules。

**根因（代码位置）**

> deploy-pages.yml 监听 release: published，但该 run 运行在 tag ref 上，而 github-pages 环境保护规则只允许 miuix 分支。

**修复建议**

> deploy-pages.yml 移除 release 触发；新增 relay-pages-deploy.yml 在 miuix 分支上重新触发部署。

## 三、需求池

| 编号 | 需求 | 类型 | 提出人 | 来源 | 提出时间 | 优先级 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| HK-REQ-001 | 支持通联记录（QSO 日志），出门只带手机即可完成记录 | 功能新增 | BH9BZB (HamCQ uid 2197) | HamCQ 帖子 https://forum.hamcq.cn/d/8940/15 | 2026-09-28 | 中 | 已列入开发清单 |
| HK-REQ-002 | 提供 iOS 客户端 | 平台扩展 | BH6BXM (HamCQ uid 11580) | HamCQ 帖子 https://forum.hamcq.cn/d/8940/17 | 2026-10-02 | 低 | 暂不排期 |
| HK-REQ-003 | APRS-IS 只读模式（passcode 留空时以 pass -1 连接，可收不可发） | 合规改造衍生 | 开发团队 (2048lr) | GitHub https://github.com/fuxue-linkong/HamKit/issues/93 | 2026-10-10 | 高 | 已实现（AVD 验证） |
| HK-REQ-004 | 首次使用 APRS-IS 前增加持照声明确认 | 合规改造衍生 | 开发团队 (2048lr) | GitHub https://github.com/fuxue-linkong/HamKit/issues/93 | 2026-10-10 | 高 | 已实现（AVD 验证） |

**HK-REQ-001　支持通联记录（QSO 日志），出门只带手机即可完成记录**

- 开发者答复：#16 楼（2026-10-01）：「我们已经将该功能列入开发清单中，预计在不久后上线」

**HK-REQ-002　提供 iOS 客户端**

- 开发者答复：#19 楼（2026-10-06）：「抱歉，目前暂时没有 IOS 端的开发计划」

**HK-REQ-003　APRS-IS 只读模式（passcode 留空时以 pass -1 连接，可收不可发）**

- 开发者答复：作为 BUG-003 整改方案的组成部分，P0 项，下个版本发布前完成
- 实现状态：✅ 已完成。`AprsConfig.isReadOnly`（passcode 留空为 true）→ `effectivePasscode = "-1"`；
  [AprsConnection.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsConnection.kt#L54-L68) 以 `pass -1` 登录并记录只读日志；
  [AprsViewModel](app/src/main/java/com/example/hamkit/ui/viewmodel/AprsViewModel.kt#L207-L230) 拦截位置上报 / 发消息 / 离线重发 / 自动 ACK 四条注入路径；
  APRS 主页与设置页显示「当前为只读模式：未填写 Passcode，仅接收不发送」

**HK-REQ-004　首次使用 APRS-IS 前增加持照声明确认**

- 开发者答复：明示「你呼号下的所有流量由你本人负责」，未确认不可登录
- 实现状态：✅ 已完成。新增 `AprsSettingsStore.licenseConfirmed` 持久化；
  点击「连接」→ [AprsLicenseConfirmDialog](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsMainScreen.kt#L268-L312)（标题「持照与责任声明」，明示流量责任与 passcode 不代发，确认按钮「我已持有有效执照并同意」/ 取消「暂不连接」）；
  `AprsViewModel.connect()` 保留防御性校验：未确认直接转为弹窗，阻断绕过 UI 的调用路径

## 四、反馈时间线（全 21 楼）

| 楼层 | 用户 | 时间 | 类型 | 内容摘要 |
| --- | --- | --- | --- | --- |
| 1 | BG7OFU | 2026-08-09 | 发布 | 发布帖：HamKit 开源免费 Android 业余无线电工具箱，含卫星过境预测、CW 摩尔斯训练、一键定位、APRS 位置报告；MIT 开源，项目地址已给出。 |
| 2 | BG5JIX | 2026-08-09 | 支持 | 支持 |
| 3 | BH5HIE | 2026-08-09 | 讨论 | 最近项目好多啊 |
| 4 | BI4OAE | 2026-08-10 | 支持 | 加油，感谢分享 |
| 5 | BG7OFU | 2026-08-14 | 公告 | 官网上线：www.hamkit.click |
| 6 | BH1LAF | 2026-08-14 | 支持 | 支持 |
| 7 | BG7OFU | 2026-08-18 | 公告 | 发布 v3.0.0，可在 hamkit.click 下载 |
| 8 | hhhooo0676 | 2026-08-21 | 支持 | 这个好 |
| 9 | BG5LU | 2026-08-23 | BUG 报告 | 肯定卫星部分（活跃与模式标记对新 HAM 友好）；提出 2 个 BUG：① APRS 设置-位置上报的两个编辑框被输入法框挡住；② 有虚拟返回键的手机底部返回键无效，回主页后仍无法返回桌面。附 5 张截图。 |
| 10 | y010204025 | 2026-08-25 | 支持 | 顶起来 |
| 11 | BG6HVG | 2026-08-29 | 支持 | 开源、免费、无广告，必须支持 |
| 12 | BG7OBO | 2026-08-29 | 支持 | 开发者大气、威武 |
| 13 | BG5CYO | 2026-08-29 | 支持 | 支持！ |
| 14 | BG7OFU | 2026-09-27 | 开发者答复 | @BG5LU 抱歉现在才看到反馈。针对您所提出的几个 bug，我们将记录并在下版本进行修复。 |
| 15 | BH9BZB | 2026-09-28 | 需求 | 支持，要是能带上通联记录就更好了，出门手机搞定。 |
| 16 | BG7OFU | 2026-10-01 | 开发者答复 | @BH9BZB 已经将该功能列入开发清单中，预计在不久后上线。 |
| 17 | BH6BXM | 2026-10-02 | 需求/提问 | 苹果手机没法用吗？ |
| 18 | BH6SVA_AC3QU | 2026-10-06 | BUG/合规报告 | 设计很好，但发现一个问题：根据 APRS 官方要求，貌似不能直接在应用里自动为用户计算验证码，这是为了防止 APRS 被乱用。 |
| 19 | BG7OFU | 2026-10-06 | 开发者答复 | @BH6BXM 抱歉，目前暂时没有 IOS 端的开发计划。 |
| 20 | BG7OFU | 2026-10-06 | 开发者答复 | @BH6SVA_AC3QU 我们将咨询 APRS 官方，如确有相关规定，我们将进行整改。 |
| 21 | BH6SVA_AC3QU | 2026-10-06 | 官方依据 | 贴出 APRS 官方页面截图与翻译：软件作者们，你们有责任仅向业余无线电操作者发放验证码。不要以按需的方式提供验证码，也不要把你的软件用户指引到其他人那里去获取验证码。 |

## 五、数据来源

| 来源名称 | 类型 | 链接 | 说明 |
| --- | --- | --- | --- |
| HamCQ 论坛 HamKit 发布帖 | 社区论坛 | https://forum.hamcq.cn/d/8940 | 21 楼，2026-08-09 起，2026-10-06 止；BUG 与需求的一手来源 |
| GitHub HamKit 仓库 | 代码仓库 | https://github.com/fuxue-linkong/HamKit | MIT 开源，主分支 miuix；issue/PR 为官方跟踪渠道 |
| GitHub Issue #93 | 问题跟踪 | https://github.com/fuxue-linkong/HamKit/issues/93 | APRS-IS passcode 合规整改，open，label: bug，2026-10-10 建立 |
| APRS-IS 官方文档 | 规范依据 | https://www.aprs-is.net/Connecting.aspx | Passcode 发放政策的官方原文出处 |
| 仓库内缺陷分析文档 | 代码仓库 | https://github.com/fuxue-linkong/HamKit/blob/miuix/docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md | P0-3（返回键失效）与 P0-4（输入法遮挡）的既有跟踪记录，与本次代码定位结论一致 |
| 本地仓库代码定位 | 代码证据 | Dual-zone_network_positioning | 分支 miuix，commit e96452e；AprsPacket/AprsModels/AprsSettingsScreen/AprsConnection 行号均实测 |
| 本次整改合规文档 | 代码仓库 | docs/APRS_IS_COMPLIANCE.md | HK-BUG-003 合规整改说明（官方原文、整改前后对照、对外答复口径、发布前复查清单） |

## 六、本轮整改记录（HK-BUG-001 / 002 / 003）

> 依据本跟踪表对「待修复 / 整改中」条目执行修复；涉及文件均在 `miuix` 分支工作区。
> 修复后在 Android 14（API 34）AVD `Pixel_7`（1080×2400，真机同等分辨率）上完成真机走查，见 §6.2。

| 条目 | 状态 | 关键改动文件 |
| --- | --- | --- |
| HK-BUG-001（输入法遮挡） | 已修复（AVD 验证） | `ui/screen/aprs/AprsSettingsScreen.kt`、`ui/screen/ft8/Ft8SettingsScreen.kt`、`ui/screen/ft8/QsoLogScreen.kt` |
| HK-BUG-002（返回键失效） | 已修复（AVD 验证） | `ui/MainActivity.kt`、`ui/component/miuix/SuperSearchBar.kt` |
| HK-BUG-003（passcode 合规） | 已整改（AVD 验证） | `data/aprs/AprsPacket.kt`、`data/aprs/AprsModels.kt`、`data/aprs/AprsConnection.kt`、`data/aprs/AprsSettingsStore.kt`、`ui/viewmodel/AprsViewModel.kt`、`ui/screen/aprs/AprsSettingsScreen.kt`、`ui/screen/aprs/AprsMainScreen.kt`、`res/values/strings.xml` |
| HK-REQ-003（只读模式） | 已实现（AVD 验证只读可收不可发） | 同上（`AprsConfig.isReadOnly` / `effectivePasscode` + ViewModel 四条注入路径拦截） |
| HK-REQ-004（持照声明） | 已实现（AVD 验证弹窗与持久化） | `AprsSettingsStore.licenseConfirmed` + `AprsMainScreen.AprsLicenseConfirmDialog` + `AprsViewModel.requestConnect/confirmLicenseAndConnect` |

> 本跟踪表与 `HamKit_社区反馈_CSV/` 下四个 CSV 保持同步；CSV 由 `scripts/sync_feedback_csv.py`（该目录按 `.gitignore` 本地化）从本 Markdown 单向导出，Markdown 为唯一真源：
> `python scripts/sync_feedback_csv.py`

**验证结果**

- 编译：`:app:compileDebugKotlin` 与 `:app:compileReleaseKotlin` ✅ BUILD SUCCESSFUL（offline）
- 单测：`:app:testDebugUnitTest --rerun-tasks` ✅ **496 项全绿**（含新增 `AprsPacketTest` 回归守卫与金标准断言、新增 `AprsConfigTest` 只读语义 5 项）
- **AVD 真机走查：Android 14（API 34）AVD `Pixel_7`，1080×2400 / 420dpi** ✅ 见 §6.2

### 6.2 AVD 走查结果（Android 14 / API 34 / Pixel_7）

> 走查方式：`emulator -avd Pixel_7` + `adb` 真机交互（点击 / 返回键 / 软键盘），
> 通过截图、`uiautomator dump`、`dumpsys input_method`、`logcat` 取证。
> 产物留在 `build/avd-test/`（含 15 张过程截图与 UI dump）。

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 应用冷启动 | `am start` + logcat crash buffer | ✅ 正常启动，无崩溃 |
| **启动崩溃（新发现→已修）** | 首次启动即 `FATAL EXCEPTION` | ❌→✅ 抓到 `IllegalStateException: LocalMainPagerState not provided`（本轮新增的根返回处理器在 MainScreen 之外读取该 CompositionLocal 所致），已按「职责分离」重构修复（详见下方回归项） |
| HK-BUG-002 子页返回键 | APRS 子页按 `KEYCODE_BACK` | ✅ 第 1 次关闭弹窗、第 2 次退出子页回主页（此前依赖 AppBar 箭头） |
| HK-BUG-002 主页双击返回退出 | 主页连按两次返回 | ✅ 第 1 次停留并 Toast「再按一次返回退出 HamKit」，2 秒内第 2 次退到桌面（`topResumedActivity` 变为 Launcher） |
| HK-BUG-001 键盘遮挡（聚焦框可见） | 聚焦 Passcode 输入框 + `dumpsys input_method` | ✅ `mInputShown=true`，聚焦框完整显示于键盘上方，其下提示文案与「APRS-IS 服务器」卡片正常 |
| HK-BUG-001 键盘遮挡（底部输入框） | 聚焦最底部「发送间隔」+「评论」 | ✅ 页面自动上滚，聚焦框进入键盘上方可见区（修复前需手动拖动） |
| HK-BUG-003 label 与提示 | 截图 / `uiautomator` 文本 | ✅ label 为「Passcode（需自行持有）」，无「计算 Passcode」展示；只读提示「留空即以只读方式连接（pass -1）：可接收报文，无法发送。」 |
| HK-REQ-004 持照声明 | 点击「连接」 | ✅ 弹出《持照与责任声明》（含流量责任与「HamKit 不会为你计算或发放 Passcode」），取消则连接不发生 |
| HK-REQ-004 确认持久化 | 确认后 `force-stop` 冷启动再连接 | ✅ 不再重复弹窗，直接连接 |
| HK-REQ-003 只读登录 | `logcat` 抓协议交互 | ✅ `Connecting in READ-ONLY mode (no passcode supplied, pass -1)` → 服务器回 **`# logresp BG7OFU unverified, server T2HK`**，即服务端确认了未验证（只读）会话 |
| HK-REQ-003 只读可收 | 站点列表 + `logcat RX` | ✅ 收到 20 条实时报文（如 `BH8PGM>APN000…=2636.91NT10642.78E_守听438.500MHz`），站点列表聚合出 9 个站点 |
| HK-REQ-003 只读不可发 | `logcat` 检索 `TX` / `sendPacket` | ✅ **0 条上行报文**（客户端四条注入路径拦截生效） |
| 只读横幅 | 主页/设置页截图 | ✅ 「当前为只读模式：未填写 Passcode，仅接收不发送。」两处均显示 |

**走查中被发现并修复的回归（重要）**

- 现象：装上修复版后应用**启动即崩溃**（`FATAL EXCEPTION: main`）。
- 原因：本轮新增的全局返回兜底 `RootBackHandler` 位于 `MainScreen` 之外，却读取了由
  `MainScreen` 提供的 `LocalMainPagerState`；该 CompositionLocal 的默认值为 `error(...)`，
  因此在主页尚未渲染的时序下抛 `IllegalStateException`。
  **`runCatching` 无法捕获该异常**（实测仍逃逸），说明「用 try/catch 包住错误默认值」不可靠。
- 修复：按职责分离重构 ——
  1. `RootBackHandler` 只负责「子页出栈」与「双击返回退出」，不再触碰该 CompositionLocal；
  2. 「主页面回第 0 页」交给 `MainScreen` 内部的 `MainScreenBackHandler`（它必然能取到分页状态，
     且作为 `BackHandler` 子节点注册更晚、在回调栈中位于更内层，启用时优先收到事件）；
  3. `LocalMainPagerState` 保持非空合同并在声明处写明「不要在 MainScreen 之外读取」的约束。
- 结论：**该崩溃仅由静态检查/单测无法发现，是靠 AVD 实机走查暴露的** —— 说明这类时序型缺陷必须过真机。

**尚未覆盖的项**

- 机型覆盖：仅 Android 14 / Pixel_7 AVD，**未覆盖 MIUI 与 API 35+ 边到边强制机型**（用户反馈的「虚拟导航栏返回键」机型为 MIUI）。建议按 P0-3 验收标准在三 ROM 真机补走查。
- 键盘遮挡：AVD 上聚焦框可见 + 自动上滚已确认；仍未在**极窄/极矮屏**（如 16:9 小屏 + 大字号）上量化「输入框底边 vs 键盘顶边」的余量。
- 未测路径：QSO 日志编辑弹窗内输入框的键盘行为、FT8 设置页键盘行为（同方案已改，未逐个走查）。
- 未做发送拦截的 UI 路径点击验证（只读模式下消息页发送按钮），仅以日志确认无上行报文与代码路径拦截。

**回归风险与后续项**

- 返回键兜底改为 `OnBackPressedDispatcher` 后，未再做 `NavDisplay` 切回官方 `navigation3-ui` 的版本对齐（原建议第 3 项，列为可选降风险），如后续仍观察到子页返回异常，应优先做该对齐并锁定 `navigation3-runtime / navigation3-ui / navigationevent` 同版本。
- `AprsPacket.formatLogin` 的 `vers` 字段原为硬编码 `HamKit-2.0`（落后于当前 3.0.1），本轮顺手改为 `BuildConfig.VERSION_NAME`（[AprsConnection.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsConnection.kt#L54-L63)），使 APRS-IS 服务端可见的客户端版本与实际一致。
- `AprsSettingsStore` 新增 `license_confirmed` 键为向后兼容的增量字段（旧安装首次读取为 false，即会弹一次声明），无迁移风险。

---

_生成自本地仓库 `Dual-zone_network_positioning`，分支 `miuix`；本轮整改已在工作区完成编译与单测验证。_