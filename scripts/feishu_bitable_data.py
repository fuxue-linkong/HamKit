# -*- coding: utf-8 -*-
"""
HamKit 社区反馈数据集（BUG / 需求 / 反馈时间线 / 来源 / 修复历史）

数据来源：
  1. HamCQ 论坛帖 https://forum.hamcq.cn/d/8940 （21 楼全量解析，2026-08-09 ~ 2026-10-06）
  2. GitHub fuxue-linkong/HamKit issues 与 Pull Requests
  3. 本地仓库 C:\\Users\\Admin\\Dual-zone_network_positioning 代码定位（分支 miuix）

本文件只提供数据，不执行任何网络操作。
"""

FORUM = "https://forum.hamcq.cn/d/8940"
REPO = "https://github.com/fuxue-linkong/HamKit"
LOCAL_REPO = "Dual-zone_network_positioning"

# ---------------------------------------------------------------- 1. BUG 跟踪
# 字段: 编号/标题/严重级别/分类/状态/来源/报告人/报告时间/现象/根因(代码位置)/修复建议/关联Issue
BUG_FIELDS = ["编号", "标题", "严重级别", "分类", "状态", "来源", "报告人",
              "报告时间", "现象", "根因(代码位置)", "修复建议", "关联Issue"]

BUGS = [
    [
        "HK-BUG-001",
        "APRS 设置页「位置上报」输入框被输入法键盘遮挡",
        "中",
        "UI / 软键盘适配",
        "待修复",
        f"HamCQ 帖子 {FORUM}/9",
        "BG5LU (HamCQ uid 1107)",
        "2026-08-23",
        "APRS 设置 → 位置上报的两个编辑框，编辑时被弹出的输入法键盘挡住内容，用户看不到正在输入的内容。用户附 3 张截图。",
        "已确认（CONFIRMED），三层叠加缺一不可：\n"
        "① [build.gradle.kts](build.gradle.kts#L12-L14) compileSdk/targetSdk = 37（≥35）→ Android 15+ 边到边强制，Manifest 里的 adjustResize 失效；\n"
        "② [MainActivity.kt](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L143-L156) enableEdgeToEdge() 执行 setDecorFitsSystemWindows(false)，窗口不再被 IME 顶起，IME inset 必须由 Compose 层消费；\n"
        "③ [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L79-L90) Scaffold 的 contentWindowInsets 被显式限制为 only(Horizontal)，把底部/IME inset 从 innerPadding 剔除，且整页无 imePadding()；\n"
        "④ 出问题的两个输入框正好在长列表底部 [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L159-L177)（评论 L164-169、发送间隔 L171-177），列表末尾仅 Spacer(32.dp) [L228]，无 BringIntoViewRequester → 键盘直接覆盖且页面不上滚。\n"
        "对照：同功能目录下已修好的写法见 [AprsMessageScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsMessageScreen.kt#L109-L109) L109 的 .imePadding()。\n"
        "同类未修页面：Ft8SettingsScreen.kt L88/L101、QsoLogScreen.kt L209。仓库内已有同题记录 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-4。",
        "最小改动：给 [AprsSettingsScreen.kt L82-90](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L82-L90) 的滚动 Column 加 .imePadding()（放在 verticalScroll 相邻位置，作为底部内边距让输入框可滚入可见区）。\n"
        "加强：把 Scaffold 的 contentWindowInsets 改为 WindowInsets.safeDrawing（已含 ime），或保留现写法并 add(WindowInsets.ime)；若仍不自动滚动，给两个 TextField 加 .imeNestedScroll() 或 BringIntoViewRequester。\n"
        "不要依赖 Manifest 的 adjustResize（可保留但已失效）；建议按 P0-4 统一整改 Ft8SettingsScreen / QsoLogScreen 等同类页面。",
        "仓库内 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-4（未开公开 issue）",
    ],
    [
        "HK-BUG-002",
        "Android 虚拟导航栏返回键全应用失效，主页无法返回桌面",
        "高",
        "系统交互 / 导航",
        "待修复",
        f"HamCQ 帖子 {FORUM}/9",
        "BG5LU (HamCQ uid 1107)",
        "2026-08-23",
        "底部有虚拟返回键的手机上，系统返回键无效，只能点左上角箭头返回；返回到主页后返回键依旧无效，无法退出应用回到桌面。用户附 2 张截图。",
        "根因一（CONFIRMED，缺失实现）：应用层完全没有自己的返回键接线。\n"
        "[MainActivity.kt](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L114-L114) 仅继承 ComponentActivity，全文 531 行无 onBackPressed / onKeyDown / dispatchKeyEvent / OnBackPressedCallback；全仓 ui/ 下 grep finish() / moveTaskToBack 零命中 → 应用自身从未实现「主页返回退出」。\n"
        "全仓未使用 Navigation Compose（NavHost/rememberNavController 零命中），而是自定义 [Navigator.kt](app/src/main/java/com/example/hamkit/ui/navigation3/Navigator.kt#L19-L22)（backStack + pop()）。\n"
        "返回键只有两个挂载点：[MainActivity.kt L184-193](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L184-L193) 的 NavDisplay(onBack = { navigator.pop() })（子页），和 [MainActivity.kt L413-L433](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L413-L433) 的 MainScreenBackHandler。\n"
        "后者 L418-421 的启用条件是「栈深 1 且 selectedPage != 0」，**主页恒为 false**；其回调也只做 animateToPage(0)，没有任何 finish()。\n"
        "于是主页场景下没有任何 enabled 的 handler，退到桌面完全依赖框架 fallback。\n"
        "根因二（SUSPECTED，子页返回也失效）：NavDisplay 并非官方 androidx.navigation3:navigation3-ui，而是 Miuix fork 打包的 miuix-navigation3-ui 0.9.3（AAR 内含 androidx/navigation3/{ui,scene}），却与官方 navigation3-runtime 1.1.4、navigationevent 1.1.2、activity 1.13.0 混用，疑致 navigationevent 回调链未触发。\n"
        "需真机 logcat（NavigationEventDispatcher / OnBackPressedDispatcher / OnBackInvoked）、adb dumpsys activity 栈变化、官方 navigation3-ui 同版本最小复现来收口。\n"
        "另：本文档中 [BottomBar.kt](app/src/main/java/com/example/hamkit/ui/component/bottombar/BottomBar.kt#L37-L68) 切页只动 pager，不碰 backStack，故顶层恒为 [Route.Main]。\n"
        "已排除：KeyEventBlocker.kt L14-28（无调用点）；[SuperSearchBar.kt](app/src/main/java/com/example/hamkit/ui/component/miuix/SuperSearchBar.kt#L190-L204) 的 NavigationBackHandler 硬编码 isBackEnabled = true（危险但当前是死代码，全仓无调用点）；另一处 BackHandler 在 [CWPracticeRouteScreen.kt L52](app/src/main/java/com/example/hamkit/ui/cw/CWPracticeRouteScreen.kt#L52-L52)（CW 页内部，与主路径无关）。\n"
        "仓库内已有同题记录 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-3。",
        "1) 在 [MainActivity.kt L176-L240](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L176-L240) 的 HamKitTheme 内、NavDisplay 之外加一个总兜底返回处理器（androidx.activity.compose.BackHandler，或 onBackPressedDispatcher.addCallback，后者完全不依赖 navigationevent）：\n"
        "   enabled = true；内容 = if (navigator.backStackSize() > 1) navigator.pop() else if (mainPagerState.selectedPage != 0) mainPagerState.animateToPage(0) else finish()（需防误触可加双击返回退出）。\n"
        "2) 收敛 handler 优先级：精简/删除 [MainScreenBackHandler](app/src/main/java/com/example/hamkit/ui/MainActivity.kt#L413-L433)，把 pager 分支并入根 handler，消除「有 handler 注册但全 disabled」的空窗；同时修掉 [SuperSearchBar.kt L192-203](app/src/main/java/com/example/hamkit/ui/component/miuix/SuperSearchBar.kt#L192-L203) 的 isBackEnabled = true 硬编码。\n"
        "3) 可选降风险：NavDisplay 切回官方 androidx.navigation3:navigation3-ui，并把 navigation3-runtime / navigation3-ui / navigationevent 锁成同一版本（[libs.versions.toml L9-L10](gradle/libs.versions.toml#L9-L10)），Manifest 显式声明 android:enableOnBackInvokedCallback。\n"
        "验收：MIUI / 原生 / 手势导航三机走查，子页返回与左上角箭头行为一致，主页返回可退出应用。",
        "仓库内 docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md P0-3（未开公开 issue）",
    ],
    [
        "HK-BUG-003",
        "APRS-IS passcode 合规问题：App 内自动计算、显示并自动填充验证码",
        "高（合规）",
        "合规 / 功能设计",
        "整改中",
        f"HamCQ 帖子 {FORUM}/18 与 {FORUM}/21",
        "BH6SVA_AC3QU (HamCQ uid 8857)",
        "2026-10-06",
        "App 在本地实现 APRS-IS passcode 算法，用户填呼号即得验证码；设置页直接展示并提示「留空自动计算」；登录时留空自动套用。\n"
        "APRS-IS 官方原文：Authors, YOU are responsible for issuing passcodes to amateur radio operators ONLY. "
        "Do not make this available in an ondemand fashion nor point your software users to someone else.\n"
        "（软件作者有责任仅向业余无线电操作者发放验证码，不得按需提供，也不得把用户指引到第三方获取。）",
        "根因 = 三处逻辑叠加，确认存在：\n"
        "① 算法真源 [AprsPacket.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsPacket.kt#L9-L17) passcode()（0x73e2 成对 XOR & 0x7fff）；\n"
        "② [AprsModels.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsModels.kt#L64-L68) computedPasscode（呼号为空返回 -1）；\n"
        "③ [AprsSettingsScreen.kt](app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt#L119-L133) label「Passcode (留空自动计算)」+ L129 内联重算展示；\n"
        "④ [AprsConnection.kt](app/src/main/java/com/example/hamkit/data/aprs/AprsConnection.kt#L54-L60) L57 登录留空回退 computedPasscode。\n"
        "附带问题：设置页 L129 重复实现算法（未复用 config.computedPasscode）；label 硬编码中文未走 strings.xml；\n"
        "[AprsPacketTest.kt](app/src/test/java/com/example/hamkit/AprsPacketTest.kt#L10-L31) 只断言 >0 / <=32767 / 自洽，无金标准期望值，算法写错也能通过。",
        "P0 移除 passcode 计算逻辑（删 AprsPacket.passcode() 与 AprsConfig.computedPasscode，同步删测试），确保算法不随发行包发布；\n"
        "P0 设置页去掉「计算 Passcode」展示，label 改为「Passcode（需自行持有，留空为只读）」；\n"
        "P0 留空时以只读方式连接（pass -1），可收报文不可注入；\n"
        "P0 首次登录前增加持照声明确认；\n"
        "P1 docs/ 增加合规说明，并复查官网 / README / 应用商店描述无 passcode 指引；\n"
        "补充：为 passcode 增加已知呼号→已知期望值 的金标准单测（如 N0CALL=13023）。",
        "GitHub Issue #93（open, label: bug）",
    ],
    [
        "HK-BUG-004",
        "卫星过境列表最大仰角显示 0°/1°",
        "中",
        "卫星预测 / 单位换算",
        "已修复",
        f"{REPO}/pull/71",
        "开发团队自测 (2048lr)",
        "2026-08-07",
        "卫星过境列表的最大仰角显示为 0° 或 1°，明显不符合实际。",
        "predict4java 的 SatPos 内部以弧度存储 elevation/azimuth，重写后的 SatellitePredictor 采样逻辑直接当角度使用。",
        "在 elevationAt / azimuthAt / maxElevationAzimuthAt 三处统一用 Math.toDegrees() 换算。",
        "PR #71（merged）",
    ],
    [
        "HK-BUG-005",
        "部分卫星过境时长超出合理范围的预测异常",
        "中",
        "卫星预测",
        "已修复",
        f"{REPO}/pull/82",
        "开发团队自测 (2048lr)",
        "2026-08-14",
        "部分卫星的过境时长预测值超出合理范围。",
        "过境判定/采样逻辑边界处理不当（详见 PR #82）。",
        "修正采样与 AOS/LOS 判定逻辑。",
        "PR #82",
    ],
    [
        "HK-BUG-006",
        "过境结束后仍显示「0 秒」",
        "低",
        "卫星预测 / UI",
        "已修复",
        f"{REPO}/pull/83",
        "开发团队自测 (2048lr)",
        "2026-08-14",
        "卫星过境已经结束，界面仍停留在显示 0 秒的倒计时状态。",
        "过境结束后的状态未清理（详见 PR #83）。",
        "过境结束后清除倒计时状态。",
        "PR #83",
    ],
    [
        "HK-BUG-007",
        "卫星列表只有约 600 颗（active 源未生效）",
        "高",
        "卫星数据源",
        "已修复",
        f"{REPO}/pull/91",
        "开发团队自测 (2048lr)",
        "2026-08-16",
        "App 只显示约 600 颗卫星（satnogs + amateur 合并数量），active 源含 16k+ 却未贡献进列表；Look4Sat 同源数据有 16k+ 颗。",
        "active.csv（2.4MB，gzip 后 900KB）在 30 秒读取超时内未读完，被 runCatchingCancellable 静默跳过（仅 Log.w），satnogs/amateur 仅几十 KB 仍成功，于是只剩约 600 颗且无任何错误提示。",
        "① active 源单独使用长超时客户端（readTimeout 90s）；② enableActive 默认值由 false 改为 true，与 SettingsStore.tleSourceActive 默认值一致；③ Worker 显式传参，与前台 MainViewModel 行为一致。",
        "PR #91（merged）",
    ],
    [
        "HK-BUG-008",
        "刷新卫星源后目录降级为约 672 颗并覆盖缓存",
        "高",
        "卫星数据源 / 缓存",
        "已修复",
        f"{REPO}/pull/92",
        "开发团队自测 (2048lr)",
        "2026-08-17",
        "点击「更新卫星源」后只剩约 672 颗，而 tle.hamkit.click 的 active.csv 含 16,340 颗且 HTTP 200 正常。",
        "① tle_source_active 是持久化 SharedPreferences 值，历史安装可能留下 false，且该开关没有 UI 可让用户恢复；前台 MainViewModel 与后台 ReminderRefreshWorker 都读该值，导致 active 源根本没参与请求；\n"
        "② 单源失败可容忍，约 672 条部分结果会直接写入缓存，覆盖已有的 16k+ 全量目录。",
        "① 两处调用显式传 enableActive = true，不再读持久化开关；② 新增 MIN_ACTIVE_TLE_COUNT = 10_000，不足阈值抛 IOException 阻止降级覆盖；③ 新增 TleSourceUrls 便于注入测试；④ 错误文案改为真实 CDN 源名；⑤ 修正 TleParser 零值指数符号。",
        "PR #92（merged）",
    ],
    [
        "HK-BUG-009",
        "老版本安卓崩溃：RuntimeShader 在 API < 33 被无条件实例化",
        "高",
        "兼容性 / 渲染",
        "已修复",
        f"{REPO}/pull/77",
        "开发团队自测 (2048lr)",
        "2026-08-14",
        "运行 Android 13 以下版本的部分设备崩溃。FloatingBottomBar 会无条件创建 InteractiveHighlight，旧版本设备解析类字段时直接抛 NoSuchMethodError。",
        "shader 字段非 lazy，在 API < 33 上类解析即失败；而 FloatingBottomBar 无条件创建 InteractiveHighlight。",
        "将 shader 改为 lazy，并用 Build.VERSION.SDK_INT >= TIRAMISU 守卫；API < 33 回退为普通白色按压高亮，与 BgEffectBackground / Lens 既有的 isRuntimeShaderSupported() 策略一致。",
        "PR #77",
    ],
    [
        "HK-BUG-010",
        "检查更新误报「已是最新版本」",
        "中",
        "应用更新",
        "已修复",
        f"{REPO}/pull/78",
        "开发团队自测 (2048lr)",
        "2026-08-14",
        "实际有新版本发布时，检查更新仍提示已是最新版本。",
        "版本比较逻辑缺陷（详见 PR #78）。",
        "修正版本检查逻辑。",
        "PR #78",
    ],
    [
        "HK-BUG-011",
        "官网版本号 badge 更新不及时",
        "低",
        "官网 / CI",
        "已修复",
        f"{REPO}/pull/72",
        "开发团队自测 (2048lr)",
        "2026-08-07",
        "发布新版本后官网 hero badge 仍显示旧版本号。",
        "① script.js 运行时请求 /releases/latest 会跳过预发布；② 匿名请求被限流（60 次/时/IP），失败后回退到写死的旧版本号；③ 部署工作流只监听 website/** 变更，发版不触发重新部署。",
        "① 部署时用 GITHUB_TOKEN 拉取最新 release（含预发布）烘焙成 website/version.json；② script.js 改为三级策略（本地 version.json → localStorage → /releases 列表接口）；③ 新增仓库内默认 version.json。",
        "PR #72",
    ],
    [
        "HK-BUG-012",
        "relay 工作流 gh 无法推断仓库导致 Pages 部署失败",
        "低",
        "CI",
        "已修复",
        f"{REPO}/pull/75",
        "开发团队自测 (2048lr)",
        "2026-08-08",
        "relay 工作流报错：failed to run git: fatal: not a git repository。",
        "gh workflow run 需要从当前目录 git 远程推断目标仓库，而 relay 工作流没有 actions/checkout，runner 工作目录无 .git。",
        "在 Dispatch 步骤显式设置 GH_REPO: ${{ github.repository }}。",
        "PR #75",
    ],
    [
        "HK-BUG-013",
        "Release 触发的 Pages 部署被 tag 环境保护规则拒绝",
        "低",
        "CI",
        "已修复",
        f"{REPO}/pull/73",
        "开发团队自测 (2048lr)",
        "2026-08-07",
        "发布 Release 时部署失败：Tag \"v2.1.1\" is not allowed to deploy to github-pages due to environment protection rules。",
        "deploy-pages.yml 监听 release: published，但该 run 运行在 tag ref 上，而 github-pages 环境保护规则只允许 miuix 分支。",
        "deploy-pages.yml 移除 release 触发；新增 relay-pages-deploy.yml 在 miuix 分支上重新触发部署。",
        "PR #73",
    ],
]

# ---------------------------------------------------------------- 2. 需求池
REQ_FIELDS = ["编号", "需求", "类型", "提出人", "来源", "提出时间", "开发者答复", "优先级", "状态"]

REQS = [
    [
        "HK-REQ-001",
        "支持通联记录（QSO 日志），出门只带手机即可完成记录",
        "功能新增",
        "BH9BZB (HamCQ uid 2197)",
        f"HamCQ 帖子 {FORUM}/15",
        "2026-09-28",
        "#16 楼（2026-10-01）：「我们已经将该功能列入开发清单中，预计在不久后上线」",
        "中",
        "已列入开发清单",
    ],
    [
        "HK-REQ-002",
        "提供 iOS 客户端",
        "平台扩展",
        "BH6BXM (HamCQ uid 11580)",
        f"HamCQ 帖子 {FORUM}/17",
        "2026-10-02",
        "#19 楼（2026-10-06）：「抱歉，目前暂时没有 IOS 端的开发计划」",
        "低",
        "暂不排期",
    ],
    [
        "HK-REQ-003",
        "APRS-IS 只读模式（passcode 留空时以 pass -1 连接，可收不可发）",
        "合规改造衍生",
        "开发团队 (2048lr)",
        f"GitHub {REPO}/issues/93",
        "2026-10-10",
        "作为 BUG-003 整改方案的组成部分，P0 项，下个版本发布前完成",
        "高",
        "整改中（P0）",
    ],
    [
        "HK-REQ-004",
        "首次使用 APRS-IS 前增加持照声明确认",
        "合规改造衍生",
        "开发团队 (2048lr)",
        f"GitHub {REPO}/issues/93",
        "2026-10-10",
        "明示「你呼号下的所有流量由你本人负责」，未确认不可登录",
        "高",
        "整改中（P0）",
    ],
]

# ---------------------------------------------------------------- 3. 反馈时间线
TL_FIELDS = ["楼层", "用户", "用户ID", "时间", "内容摘要", "类型"]

TIMELINE = [
    ["1", "BG7OFU", "8528", "2026-08-09", "发布帖：HamKit 开源免费 Android 业余无线电工具箱，含卫星过境预测、CW 摩尔斯训练、一键定位、APRS 位置报告；MIT 开源，项目地址已给出。", "发布"],
    ["2", "BG5JIX", "5820", "2026-08-09", "支持", "支持"],
    ["3", "BH5HIE", "10871", "2026-08-09", "最近项目好多啊", "讨论"],
    ["4", "BI4OAE", "3235", "2026-08-10", "加油，感谢分享", "支持"],
    ["5", "BG7OFU", "8528", "2026-08-14", "官网上线：www.hamkit.click", "公告"],
    ["6", "BH1LAF", "1459", "2026-08-14", "支持", "支持"],
    ["7", "BG7OFU", "8528", "2026-08-18", "发布 v3.0.0，可在 hamkit.click 下载", "公告"],
    ["8", "hhhooo0676", "7397", "2026-08-21", "这个好", "支持"],
    ["9", "BG5LU", "1107", "2026-08-23", "肯定卫星部分（活跃与模式标记对新 HAM 友好）；提出 2 个 BUG：① APRS 设置-位置上报的两个编辑框被输入法框挡住；② 有虚拟返回键的手机底部返回键无效，回主页后仍无法返回桌面。附 5 张截图。", "BUG 报告"],
    ["10", "y010204025", "2570", "2026-08-25", "顶起来", "支持"],
    ["11", "BG6HVG", "6672", "2026-08-29", "开源、免费、无广告，必须支持", "支持"],
    ["12", "BG7OBO", "3604", "2026-08-29", "开发者大气、威武", "支持"],
    ["13", "BG5CYO", "3198", "2026-08-29", "支持！", "支持"],
    ["14", "BG7OFU", "8528", "2026-09-27", "@BG5LU 抱歉现在才看到反馈。针对您所提出的几个 bug，我们将记录并在下版本进行修复。", "开发者答复"],
    ["15", "BH9BZB", "2197", "2026-09-28", "支持，要是能带上通联记录就更好了，出门手机搞定。", "需求"],
    ["16", "BG7OFU", "8528", "2026-10-01", "@BH9BZB 已经将该功能列入开发清单中，预计在不久后上线。", "开发者答复"],
    ["17", "BH6BXM", "11580", "2026-10-02", "苹果手机没法用吗？", "需求/提问"],
    ["18", "BH6SVA_AC3QU", "8857", "2026-10-06", "设计很好，但发现一个问题：根据 APRS 官方要求，貌似不能直接在应用里自动为用户计算验证码，这是为了防止 APRS 被乱用。", "BUG/合规报告"],
    ["19", "BG7OFU", "8528", "2026-10-06", "@BH6BXM 抱歉，目前暂时没有 IOS 端的开发计划。", "开发者答复"],
    ["20", "BG7OFU", "8528", "2026-10-06", "@BH6SVA_AC3QU 我们将咨询 APRS 官方，如确有相关规定，我们将进行整改。", "开发者答复"],
    ["21", "BH6SVA_AC3QU", "8857", "2026-10-06", "贴出 APRS 官方页面截图与翻译：软件作者们，你们有责任仅向业余无线电操作者发放验证码。不要以按需的方式提供验证码，也不要把你的软件用户指引到其他人那里去获取验证码。", "官方依据"],
]

# ---------------------------------------------------------------- 4. 数据来源
SRC_FIELDS = ["来源名称", "类型", "链接", "说明"]

SOURCES = [
    ["HamCQ 论坛 HamKit 发布帖", "社区论坛", FORUM,
     "21 楼，2026-08-09 起，2026-10-06 止；BUG 与需求的一手来源"],
    ["GitHub HamKit 仓库", "代码仓库", REPO,
     "MIT 开源，主分支 miuix；issue/PR 为官方跟踪渠道"],
    ["GitHub Issue #93", "问题跟踪", f"{REPO}/issues/93",
     "APRS-IS passcode 合规整改，open，label: bug，2026-10-10 建立"],
    ["APRS-IS 官方文档", "规范依据", "https://www.aprs-is.net/Connecting.aspx",
     "Passcode 发放政策的官方原文出处"],
    ["仓库内缺陷分析文档", "代码仓库", f"{REPO}/blob/miuix/docs/COMPETITIVE_DEFECT_ANALYSIS_AND_FIX_PLAN.md",
     "P0-3（返回键失效）与 P0-4（输入法遮挡）的既有跟踪记录，与本次代码定位结论一致"],
    ["本地仓库代码定位", "代码证据", LOCAL_REPO,
     "分支 miuix，commit e96452e；AprsPacket/AprsModels/AprsSettingsScreen/AprsConnection 行号均实测"],
]
