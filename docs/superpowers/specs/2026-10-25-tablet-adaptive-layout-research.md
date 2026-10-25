# HamKit 平板 / 大屏适配调研与布局方案

> 日期：2026-10-25
> 目标：优化平板等大屏设备体验，仿照参考截图（Cirno，左侧导航 + 非对称卡片网格）重排主页与导航壳。
> 性质：调研 + 设计，不含代码改动。

---

## 1. 结论摘要

1. **目标形态**：宽度 ≥ 600dp 时使用**常驻左导航栏（NavigationRail）+ 右侧内容区**，内容区从"单列卡片流"改为**非对称卡片网格**（左主卡 + 右列小卡 + 底部全宽卡）。
2. **改动集中在 4 处**：导航判断（`MainActivity.kt`）、主页 Material 版（`HomeMaterial.kt`）、主页 Miuix 版（`HomeMiuix.kt`）、设置页（`SettingsMaterial.kt` / `SettingsMiuix.kt`）。
3. **零新依赖即可落地**：项目 `material3 1.5.0-alpha22` 的 POM 已传递依赖 `material3-window-size-class`（缓存已存在），断点 API 与 `NavigationRail` 均可直接用；无需引入 `material3-adaptive` 系列。
4. **分两阶段**：阶段一（导航壳 + 主页/设置网格化，约 1 人日）；阶段二（列表-详情双栏，复用 Navigation3 官方 `TwoPaneScene` 配方，约 1~2 人日）。

---

## 2. 现状诊断（基于代码）

### 2.1 导航壳：只按"横屏"判断，平板竖屏仍是底栏

`app/src/main/java/com/example/hamkit/ui/MainActivity.kt:425-426`

```kotlin
val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
val useNavigationRail = isLandscape && !(uiMode == UiMode.Miuix && enableFloatingBottomBar)
```

问题：
- 平板**竖屏**（宽度 800dp 级）仍走底栏，与手机形态相同，浪费宽度；
- 折叠屏展开、桌面分屏（窗口宽度变窄/变宽）无法正确切换，因为只看 orientation 不看窗口宽度；
- rail 分支内容仅为"rail + 原单列 pager"，内容区没有随宽度变化（`MainActivity.kt:449-484`）。

### 2.2 主页：单列卡片流，宽屏会被拉长

- `app/src/main/java/com/example/hamkit/ui/screen/home/HomeMaterial.kt:75-110`：`Column(verticalScroll)` + 16dp 间距，所有卡片 `fillMaxWidth`。
- `app/src/main/java/com/example/hamkit/ui/screen/home/HomeMiuix.kt:101`：同样是单列 `LazyColumn`。
- 卡片内部大量 `Modifier.fillMaxWidth()`（如 `HomeMaterial.kt:367` 卫星卡、462-550 各入口卡），在 1200dp 宽屏上单卡横跨整屏，文本行长远超 75 字符的可读上限。

### 2.3 设置页：单列 SegmentedColumn

- `app/src/main/java/com/example/hamkit/ui/screen/settings/SettingsMaterial.kt:58-82`：`Column(verticalScroll)` 内多个 `SegmentedColumn`，宽屏下大量留白。

### 2.4 已具备的资产（可复用）

| 资产 | 位置 | 说明 |
|---|---|---|
| `NavigationRailMaterial` / `NavigationRailMiuix` | `ui/component/bottombar/` | 标准 M3 rail，图标+文字，宽度自适应 |
| `SideRail` 分派 | `ui/component/bottombar/BottomBar.kt:100-108` | 按 UiMode 选择 rail 实现 |
| 双主题壳 | `MainActivity.kt:455-483` | Material/Miuix 各一套 Scaffold |
| `material3-window-size-class` | 传递依赖（material3 POM） | 含 `WindowSizeClass` / `calculateWindowSizeClass()` |
| Navigation3 | `ui/navigation3/`（NavDisplay + Route） | 官方有现成双栏 Scene 配方 |
| `pageScale` | `MainActivityViewModel.kt:204-206` | 已有全局密度缩放，可复用 |

### 2.5 缺失项

- 无任何宽度断点判断（全项目 grep `WindowWidthSizeClass` 无结果）；
- 详情页（卫星、APRS）均为整页压栈，宽屏下无列表-详情同屏；
- 无内容最大宽度约束（平板上的"拉宽"问题）。

---

## 3. 参考截图拆解（Cirno）

截图参数：2560×1600 像素 ≈ **1280×800dp**（@2x），横屏平板。

| 区域 | 占比/尺寸 | 内容 |
|---|---|---|
| 左侧导航栏 | ≈ 80dp 宽，常驻 | 图标 + 文字（主页 / 应用列表 / 运行列表 / 设置），选中项有 pill 高亮，垂直居中 |
| 内容区标题 | 顶部居中 | 应用名 "Cirno" |
| 内容区边距 | 左右 ≈ 24dp，卡片间距 ≈ 16dp | — |
| 卡片网格 | 左列 ≈ 55%，右列 ≈ 45% | 左：大状态卡（工作中 / 版本）；右：两张小卡堆叠（白名单 12 / 黑名单 0） |
| 底部 | 通栏 | 一张全宽信息卡（管理器版本 / Hook 类型 / Android 版本 / Xposed 版本 / 系统指纹） |
| 视觉 | 大圆角（16~24dp）、半透明模糊底、主次文字分层 | — |

**可迁移的 3 个布局规律**：
1. **导航与内容分离**：左侧固定 rail 不参与滚动，内容区独立滚动；
2. **非对称两列**：主信息大卡占左列，次级指标小卡堆叠右列，而非等宽网格；
3. **通栏摘要卡收尾**：底部用全宽卡承载"键值对列表"类信息（版本、系统信息）。

映射到 HamKit：

| 参考图元素 | HamKit 对应 |
|---|---|
| 左侧 rail | 主页 / 设置（未来可扩卫星、APRS、FT8、QSO） |
| 左列大卡 | 时间 + 天气合并卡（信息量最大） |
| 右列小卡 | 附近过境卫星卡、定位入口卡 |
| 底部通栏卡 | 功能入口区（CW / APRS / FT8 / SSTV / QSO），宽屏时排成多列 |
| 通栏键值卡 | 设置页在宽屏下的分组卡片 |

---

## 4. 平台规范调研

### 4.1 窗口断点（WindowSizeClass）

| 类 | 宽度 | 导航建议 | 内容建议 |
|---|---|---|---|
| Compact | < 600dp | 底部导航栏 | 单栏 |
| Medium | 600–840dp | NavigationRail | 单栏，可选列表-详情 |
| Expanded | ≥ 840dp | Rail 展开 / 常驻抽屉 | 列表-详情、多栏 |

来源：[大屏设备应用质量](https://developer.android.com/docs/quality-guidelines/large-screen-app-quality)、[构建自适应导航](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)。

### 4.2 Material 3 Adaptive 1.2.0（可选参考，不建议本期引入）

- `NavigationSuiteScaffold` 一行代码按窗口宽度切换 `NavigationBar` / `NavigationRail` / `WideNavigationRail*` / `PermanentDrawerSheet`；
- `NavigationSuiteType` 现有类型：`ShortNavigationBarCompact`、`ShortNavigationBarMedium`、`WideNavigationRailCollapsed`、`WideNavigationRailExpanded`、`NavigationBar`、`NavigationRail`、`NavigationDrawer`、`None`；
- 官方推荐用 `currentWindowAdaptiveInfoV2()` + `NavigationSuiteScaffoldDefaults.navigationSuiteType(...)` 自动选择。

代价：`material3-adaptive-navigation-suite` 与 `material3-adaptive` 为新依赖，且其 Scaffold 与项目 Miuix 主题双壳（`MainActivity.kt:455-483`）集成成本较高，**本期不引入**，仅作为后续演进参考。

参考：[NavigationSuiteType API](https://composables.com/jetpack-compose/androidx.compose.material3/material3-adaptive-navigation-suite/classes/NavigationSuiteType/api)、[Material 3 Adaptive 1.2.0 发布](https://android-developers.googleblog.com/2025/10/material-3-adaptive-120-is-stable.html)。

### 4.3 Navigation3 官方双栏配方（可直接用，零新依赖）

Navigation3 的 `SceneStrategy` 可在返回栈顶部两个 entry 都声明支持双栏时，把两个页面并排渲染：

- 触发条件：窗口宽度 ≥ **600dp**（`WindowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)`）；
- 每个路由通过 `metadata` 声明"可双栏"（`TwoPaneScene.twoPane()`）；
- `NavDisplay(sceneStrategies = listOf(twoPaneStrategy))` 即可生效。

官方示例（`android/skills` 仓库 `scenes-twopane` 配方）核心签名：

```kotlin
data class TwoPaneScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    val firstEntry: NavEntry<T>,
    val secondEntry: NavEntry<T>,
) : Scene<T> { /* Row 50/50 渲染两个 entry */ }

class TwoPaneSceneStrategy<T : Any>(val windowSizeClass: WindowSizeClass) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)) return null
        // 取最后两个 entry，若都带 TwoPaneKey 元数据则返回 TwoPaneScene
    }
}
```

来源：[Two-Pane Scene Recipe](https://github.com/android/skills/blob/main/navigation/navigation-3/references/android/guide/navigation/navigation-3/recipes/scenes-twopane.md)。

### 4.4 大屏质量分层

| 层级 | 要求 | HamKit 现状 |
|---|---|---|
| Tier 3 基础 | 全屏、旋转/折叠/分屏不崩、基础键鼠 | 基本满足（未锁方向），分屏未验证 |
| Tier 2 优化 | 响应式布局、状态保持 | **不满足**（单列布局） |
| Tier 1 差异化 | 拖拽分屏、折叠姿态、桌面窗口 | 本期不做 |

---

## 5. 方案对比

| 方案 | 做法 | 新增依赖 | 工作量 | 风险 |
|---|---|---|---|---|
| **A. 自建断点 + 复用现有组件**（推荐） | `WindowSizeClass` 判断 → 复用 `NavigationRail`/`BottomBar`；主页改 `LazyVerticalGrid` 自适应列 | 无 | 低（~1 人日） | 需同时改 Material/Miuix 两套，注意一致性 |
| B. 官方 material3-adaptive | `NavigationSuiteScaffold` + `ListDetailPaneScaffold` | 是（2~3 个 androidx 模块） | 中（~2~3 人日） | 与 Miuix 双主题壳冲突，需重构导航层 |
| C. Nav3 `TwoPaneScene` | `SceneStrategy` 让"列表 → 详情"并排 | 无（window-core 已传递） | 中（~1 人日） | 需要给路由加 metadata，返回语义要定义 |

**推荐组合：A（阶段一，覆盖 90% 场景）+ C（阶段二，解决列表-详情）**。B 留作长期演进（若未来统一到纯 M3 主题）。

---

## 6. 详细设计

### 6.1 导航壳：宽度断点替代横屏判断

`MainActivity.kt:425-426` 改为：

```kotlin
// 断点判断：平板/折叠屏/分屏按"窗口宽度"而非"屏幕方向"，分屏变窄时能正确回落到底栏
val windowSizeClass = calculateWindowSizeClass(context as Activity)
val useNavigationRail =
    windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact &&
    !(uiMode == UiMode.Miuix && enableFloatingBottomBar)
```

- `calculateWindowSizeClass()` 来自已传递引入的 `material3-window-size-class`（`@ExperimentalMaterial3WindowSizeClassApi`）；
- Miuix 浮动底栏的例外开关保留（用户既有设置）；
- 若 `MainScreen` 内也想判断（例如内容区栅格列数），用 `LocalConfiguration.current.screenWidthDp` 或同一 `windowSizeClass` 下传，避免重复计算。

### 6.2 主页布局：三段式 + 自适应网格

**推荐形态**（兼顾参考图与实现成本）：

```
┌──────────┬──────────────────────────────────────────────────┐
│          │  [时间 + 天气合并卡]（通栏）                        │
│   Rail   ├────────────────────────┬─────────────────────────┤
│  (80dp)  │  附近卫星卡（主列）      │  定位入口 ┐              │
│          │  跨 2 行                │  CW      ├ 2×2 功能网格 │
│          │                        │  APRS    │              │
│          │                        │  FT8     ┘              │
│          ├────────────────────────┴─────────────────────────┤
│          │  [SSTV / QSO / 设置类入口]（通栏 2~3 列网格）         │
└──────────┴──────────────────────────────────────────────────┘
```

实现要点（Material 版，`HomeMaterial.kt`）：

> 可交互原型见 [`docs/mockups/tablet-layout-lab.html`](../mockups/tablet-layout-lab.html)：拖动窗口右缘/滑杆即可实时预览 600dp、1080dp 断点下的导航形态与列数变化（1px = 1dp）。

```kotlin
// 宽屏（≥ 600dp）走网格，窄屏保持现有单列，避免手机形态回归
if (useWideLayout) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { HomeHeaderMaterial(...) }   // 时间天气通栏
        item { SatelliteListCard(...) }                                          // 主列
        item { LocationEntryCard(...) }
        item { CwEntryCard(...) }
        item { AprsEntryCard(...) }
        item { Ft8EntryCard(...) }
        item { SstvEntryCard(...) }
        item { QsoLogEntryCard(...) }
    }
} else {
    // 现有 Column(verticalScroll) 保持不变
}
```

- 列数自适应：320dp 最小列宽 → 600~840dp 出 2 列、840~1280dp 出 2~3 列、1280dp+ 出 3~4 列，无需硬编码断点；
- **内容最大宽度约束**：宽屏时给内容区加 `Modifier.widthIn(max = 1280.dp)` 并居中，防止超宽屏上卡片被拉到离谱；
- Miuix 版（`HomeMiuix.kt`）同样改造；建议把"卡片清单 + 网格列数"抽成一份共享的 `HomeLayoutSpec`，两套主题只负责卡片绘制，减少双份维护（当前两文件各 550+ 行，重复严重）。

### 6.3 设置页

- 宽屏（≥ 840dp）：SegmentedColumn 两列排布（左侧通用/显示，右侧关于/更新），或单列 + `widthIn(max = 840.dp)` 居中；
- 推荐先做"限宽居中"（1 行代码级改动），再视反馈决定是否分栏。

### 6.4 列表-详情双栏（阶段二）

优先改造"列表 → 详情"高频路径，复用 Nav3 官方配方：

| 流程 | 现状 | 双栏后 |
|---|---|---|
| 卫星管理 → 卫星详情 | 整页压栈 | 左列表 / 右详情（宽屏） |
| APRS 消息列表 → 详情 | 整页压栈 | 左列表 / 右消息 |
| QSO 日志 → 编辑 | 整页压栈 | 左列表 / 右编辑 |

接入步骤：
1. 新增 `ui/navigation3/TwoPaneScene.kt`（照官方配方，约 100 行）；
2. 在 `MainActivity.kt:257-315` 的 `NavDisplay` 增加 `sceneStrategies = listOf(twoPaneStrategy)`；
3. 给 `Route.SatelliteManagement`、`Route.SatelliteDetail` 等 entry 加 `metadata = TwoPaneScene.twoPane()`；
4. 返回语义按官方建议：宽屏并排时返回仍只弹栈一次（`previousEntries = entries.dropLast(1)`）。

### 6.5 折叠屏 / 多窗口

- 宽度断点已天然覆盖折叠态与分屏（窗口宽度变化会触发重组）；
- 姿态（半折）不做专门处理，属 Tier 1，保持现状即可。

---

## 7. 改动清单（文件级）

| 文件 | 改动 | 阶段 |
|---|---|---|
| `app/src/main/java/com/example/hamkit/ui/MainActivity.kt:425` | `isLandscape` → `WindowSizeClass` 断点 | 一 |
| `app/src/main/java/com/example/hamkit/ui/screen/home/HomeMaterial.kt:75-110` | 宽屏走 `LazyVerticalGrid`，窄屏保留单列 | 一 |
| `app/src/main/java/com/example/hamkit/ui/screen/home/HomeMiuix.kt:101` | 同上（Miuix 版） | 一 |
| `app/src/main/java/com/example/hamkit/ui/screen/home/HomeLayoutSpec.kt`（新增） | 抽出共享的卡片清单与网格参数 | 一 |
| `app/src/main/java/com/example/hamkit/ui/screen/settings/SettingsMaterial.kt:58-82` | 宽屏限宽居中（可选两列） | 一 |
| `app/src/main/java/com/example/hamkit/ui/navigation3/TwoPaneScene.kt`（新增） | Nav3 双栏 SceneStrategy | 二 |
| `app/src/main/java/com/example/hamkit/ui/MainActivity.kt:257-315` | `NavDisplay.sceneStrategies` + 路由 metadata | 二 |

依赖：**无新增**（`material3-window-size-class` 已由 material3 POM 传递引入；`androidx.window:window-core` 亦然）。

---

## 8. 风险与注意事项

1. **双主题维护成本**：Material / Miuix 两套布局，改一处必须同步另一处，否则主题切换后形态不一致（建议按 6.2 抽 `HomeLayoutSpec`）。
2. **`HorizontalPager` + Rail 组合**：宽屏下 pager 是否仍保留左右滑动手势需确认（当前 `MainActivity.kt:433-445` 未设 `userScrollEnabled` 为 false），rail 与 pager 并存时滑动与点击可能重复触发，建议宽屏下禁用 pager 滑动。
3. **返回栈与双栏**：宽屏并排时返回只弹一层，需在真机确认不出现"详情已关但列表还在"的错觉。
4. **`pageScale` 叠加**：已有全局密度缩放（`MainActivity.kt:204-206`），断点判断应基于物理窗口宽度，勿被 `pageScale` 影响（`WindowSizeClass` 不受 `LocalDensity` 影响，安全）。
5. **实验 API**：`calculateWindowSizeClass` 带 `@ExperimentalMaterial3WindowSizeClassApi`，升级 Compose 时需关注弃用（M3 1.5 已推荐 `currentWindowAdaptiveInfoV2`，但那需要 adaptive 依赖，本期不引入）。

---

## 9. 测试清单

- [ ] 手机竖屏：底栏 + 单列（无回归）
- [ ] 手机横屏：rail 出现，卡片 2 列（原为单列拉宽）
- [ ] 平板竖屏（800dp）：rail（原先为底栏，重点回归）
- [ ] 平板横屏（1280dp）：rail + 2~3 列网格
- [ ] 折叠屏展开/折叠切换：形态瞬时切换、不丢状态
- [ ] 分屏（窗口宽度 <600dp）：回落底栏
- [ ] 旋转前后：所选页、滚动位置保持
- [ ] Material / Miuix 两主题下布局均正确

---

## 10. 参考链接

- [大屏设备应用质量 | Android Developers](https://developer.android.com/docs/quality-guidelines/large-screen-app-quality)
- [构建自适应导航（Compose）](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)
- [NavigationSuiteType API（material3-adaptive-navigation-suite 1.2.0）](https://composables.com/jetpack-compose/androidx.compose.material3/material3-adaptive-navigation-suite/classes/NavigationSuiteType/api)
- [Material 3 Adaptive 1.2.0 发布博客](https://android-developers.googleblog.com/2025/10/material-3-adaptive-120-is-stable.html)
- [Navigation3 双栏 Scene 官方配方](https://github.com/android/skills/blob/main/navigation/navigation-3/references/android/guide/navigation/navigation-3/recipes/scenes-twopane.md)
- [ListDetailPaneScaffold 示例（adaptive-layout）](https://composables.com/jetpack-compose/androidx.compose.material3.adaptive/adaptive-layout/composable-functions/ListDetailPaneScaffold/examples)

---

## 11. 阶段一实施记录

已按本方案落地阶段一（不含列表-详情双栏）：

| 文件 | 改动 |
|---|---|
| `ui/MainActivity.kt` | 导航形态由「横屏判断」改为「窗口宽度 ≥ 600dp」，覆盖平板竖屏 / 折叠屏 / 分屏 |
| `ui/screen/home/HomeMaterial.kt`、`HomeMiuix.kt` | 内容区 ≥700dp 走「通栏时间卡 + 主列卫星卡 + 侧列 2×2 入口 + 底部入口行」，≥1080dp 侧列扩为两列；窄屏保持原单列 |
| `ui/screen/home/HomeLayout.kt`（新增） | 两套主题共享的断点、限宽、间距参数 |
| `ui/screen/settings/SettingsMaterial.kt`、`SettingsMiuix.kt` | 设置内容限宽 840dp 居中 |

说明：列数断点按「内容区宽度」计算，扣除约 86dp 侧栏与留白后，窗口约 786dp 起两栏、1166dp 起三栏。
未做：本地构建验证（按项目约定默认不在本地跑构建）；列表-详情双栏属阶段二。
