# 需求调研：卫星自定义分类 + 分类配置导入/导出

> 调研日期：2026-08-20
> 调研对象：HamKit（miuix 分支，HEAD `54e5c2f`）
> 结论性质：可行性调研 + 方案设计 + 实施计划，**未开始编码**

---

## 〇、已确认决策（需求澄清结果）

| 编号 | 问题 | **决策** |
|---|---|---|
| Q1 | 分类对象 | ✅ **给卫星个体打分类**（对 TLE 目录里的卫星打标签） |
| Q2 | 一星多分类 | ✅ **可以属于多个分类**（标签式模型） |
| Q3 | 分类与收藏的关系 | ✅ **二者直接合并为一套**（取消「收藏」概念，统一为分类） |
| Q3.1 | 合并后提醒由什么触发 | ✅ **卫星级提醒开关**（分类只做组织；每颗卫星单独开关过境提醒） |
| Q3.2/Q3.3 | 历史收藏迁移 | ✅ **迁移到内置分类「我的关注」，提醒行为不回退** |
| Q4 | 导入冲突策略 | ✅ **合并**（同 id 更新，新 id 追加，归属取并集） |
| Q5 | 导出范围 | ✅ **分类定义 + 卫星归属 + 卫星级提醒开关** |
| Q6 | 分类形态（MVP） | ✅ **仅名称**（颜色/图标/排序留二期） |
| Q7 | 孤儿归属 / 空分类 | ✅ **保留，不清理** |

**架构结论（核心）**：「**组织**」与「**提醒**」彻底分离为两条互不干扰的数据链路 ——

```
分类归属  Map<NORAD, Set<categoryId>>   →  组织 / 浏览 / 筛选        （新增）
提醒开关  Set<NORAD>                    →  是否创建过境提醒        （由原「收藏」平移）
```

> 为什么必须分离：原设计中「收藏」同时承担了「我关心它」和「提醒我」两个语义。合并为分类后，若沿用「属于分类即提醒」，用户把 50 颗卫星批量归入一个参考型分类（如「线性转发器大全」）时会瞬间产生 50 条闹钟。分离后分类可放心用于资料整理，提醒成为显式的、逐星的开关。

---

## 一、需求原文与拆解

用户原始描述：

> 对卫星添加自定义分类，允许导入与导出分类设置，从而分享设置。
> 对于 ui，在卫星部分那加入分类按钮，允许对获取到的卫星源，进行分类，
> 在设置中加入分类配置导入与导出。

拆解为可验收需求：

| 编号 | 需求 | 说明 |
|---|---|---|
| **R1** | 卫星自定义分类 | 用户可自建分类（如「新手常用」「线性转发器」「FM 易通联」），并把卫星归入分类 |
| **R2** | 卫星页分类入口 | 在卫星管理页增加「分类」按钮，提供分类管理与按分类筛选/浏览 |
| **R3** | 对获取到的卫星进行分类 | 对当前 TLE 目录里的卫星（约 1.6 万条 active 源）打分类标记 |
| **R4** | 设置页分类配置导入/导出 | 导出可分享的配置文件，他人导入后复用同一套分类 |
| **R5** | 收藏并入分类（Q3=C 派生） | 原「收藏」语义由分类承载，提醒链路解耦，历史数据迁移 |

**核心业务价值**：把「哪些卫星值得关注 / 怎么用」这类经验沉淀成可分享的配置文件，降低新 HAM 的上手门槛。当前项目只有扁平的「收藏」集合，既无法表达分类语义，也无法分享。

---

## 二、现状调研（代码事实）

### 2.1 卫星数据链路

```
SatelliteDataSource.fetchAmateurTLEs()   data/satellite/SatelliteDataSource.kt:114
   ├─ satnogs 源   (SNOGS)   :231
   ├─ amateur 源   (CT)      :278
   ├─ active 源    (ALL)     :366   ← 全量目录唯一来源，16k+ 条，失败必须抛错
   ├─ custom URL             :438   ← 参数存在，但无任何 UI 调用
   └─ ISS 单星源             :402
        ↓  按 NORAD 合并去重（SourcedTLE:33）
SatelliteCacheStore (SharedPreferences + JSON)  data/satellite/SatelliteCacheStore.kt
        ↓
MainViewModel.satelliteItems            ui/MainViewModel.kt:149-160
   = cachedTles × 过境预测(pass) × 转发器(radios)
        ↓
SatelliteListItem                       data/satellite/SatelliteListItem.kt:16
   { tle, pass, radios } + catalogNumber / name / effectiveModes / status
```

- 卫星的**唯一标识是 `catalogNumber`（NORAD 编号）**（`SatelliteListItem.kt:22`）—— 分类归属与导出格式都以它为键。
- 目录规模由 active 源决定，注释为「全部活跃卫星 16k+」（`SatelliteDataSource.kt:111`）。
- `fetchAmateurTLEs(customUrl=...)` 已支持自定义 TLE 源，但**从未被传入**（`MainViewModel.kt:967-971` 只传 3 个开关）。
- 卫星列表出现在**卫星管理页**与**首页时间卡（下一次过境）**两处。

### 2.2 卫星管理页 UI（双皮肤）

| 文件 | 行数 | 角色 |
|---|---|---|
| `ui/screen/satellite/SatelliteManagementScreen.kt` | 1044 | 入口分发 `:95-100` + **Miuix 实现** |
| `ui/screen/satellite/SatelliteManagementMaterial.kt` | 1037 | **Material 实现** |
| `ui/screen/satellite/SatelliteFilterScreen.kt` | 421 | 筛选内容（弹窗/子页复用，双皮肤） |
| `ui/screen/satellite/SatelliteFilterDialog.kt` | 32 | Miuix `OverlayDialog` 外壳 |

页面结构（Miuix 版）：`TopAppBar` → `LazyColumn`，第一项是合并卡 `SatelliteOverviewCard`（`:319`），内含：

1. 名称搜索框 `:350`
2. 统计文本 `:359`（含 `favorites_count` 计数，`:374`）
3. **`SatelliteFilterButton`（筛选入口）`:502-534`** ← 分类按钮的天然邻居
4. `ActionRow` × 2（获取定位 / 更新卫星源）`:445-481`

列表项 `SatelliteManagementItem`（`:540`）内有一个**星标按钮**（`Icons.Rounded.Star` / `StarBorder`，`:35-36`）用于切换收藏 —— 合并后此按钮改造为分类入口。

### 2.3 现有筛选机制

```kotlin
// ui/MainViewModel.kt:1408
data class SatelliteFilter(
    val modes: Set<String> = emptySet(),      // 工作模式多选（60 种 + 未知）
    val nameQuery: String = "",
    val onlyUpcoming: Boolean = false,
    val onlyInPass: Boolean = false,
    val onlyAmsat: Boolean = false,
    val onlyFavorites: Boolean = false,       // ← 收藏筛选，由分类筛选取代
) { val isActive: Boolean get() = ... }       // :1419

fun List<SatelliteListItem>.applyFilterToItems(filter, favorites): List<SatelliteListItem>  // :1427
fun List<SatelliteInfo>.applyFilter(filter, favorites)                                       // :1452
```

- 筛选状态存在 `MainViewModel._satelliteFilter`（`:187-192`），**纯内存，进程重启即丢失**。
- `applyFilterToItems` 只有 2 个调用点：`SatelliteManagementScreen.kt:199`、`SatelliteManagementMaterial.kt:185`。
- 筛选 UI 已有完整范式：`SatelliteFilterDialogContent`（`:59`）按 `LocalUiMode` 分发到 Miuix/Material 两套，含 `MiuixFilterSectionCard` / `MiuixFilterSelectableRow` / `MiuixFilterSwitchRow` 复用组件（`:175-265`）。
- 排序同样依赖收藏（`compareByDescending { it.catalogNumber in favorites }`，`SatelliteManagementScreen.kt:230`）。

### 2.4 收藏机制与提醒耦合图谱（Q3=C 的核心）

**存储**：`FavoriteSatellitesStore`，prefs `radio_area_favorites`，键 `favorite_satellites`，值 = NORAD 编号字符串集合（`FavoriteSatellitesStore.kt:10-49`）。

**收藏 = 提醒**。`MainViewModel.toggleFavorite()`（`:441-467`）不只是写收藏：

```kotlin
fun toggleFavorite(catalogNumber: Int) {
    val updated = favoriteStore.toggle(catalogNumber)
    _favoriteSatellites.value = updated
    if (catalogNumber in updated) {
        addReminderForSatellite(satInfo)          // :471-487 → ReminderItem + AlarmManager 闹钟
    } else {
        reminderStore.removeItem(catalogNumber)   // 取消收藏 → 删提醒 + 取消闹钟
        reminderScheduler.cancel(catalogNumber)
    }
}
```

**全量耦合点清单**（合并时必须逐一处理）：

| # | 位置 | 耦合内容 |
|---|---|---|
| 1 | `MainViewModel.kt:197-198` | `_favoriteSatellites = favoriteStore.load()` 状态源 |
| 2 | `MainViewModel.kt:441-467` | `toggleFavorite` → 建/删提醒（隐式副作用） |
| 3 | `MainViewModel.kt:471-487` | `addReminderForSatellite` 写 `ReminderStore` + `reminderScheduler.schedule` |
| 4 | `MainViewModel.kt:1103-1146` | `refreshRemindersFromPrediction`：**`filter { it.catalogNumber in favorites }`** —— 只有收藏卫星才更新提醒 |
| 5 | `MainViewModel.kt:1408/1444` | `SatelliteFilter.onlyFavorites` 与 `applyFilterToItems` 的 `favoriteOk` |
| 6 | `SatelliteManagementScreen.kt:112,153,198-202,230,303` | 收藏取用、计数、筛选、排序、星标 |
| 7 | `SatelliteManagementMaterial.kt:107,135,184-188,216,290` | 同上（Material 皮肤） |
| 8 | `SatelliteFilterScreen.kt:166-169, 344-347` | 「仅已关注」开关（双皮肤） |
| 9 | `HomeScreen.kt:45,84,93-97,106` | 首页下一次过境计算与 `HomeActions.onToggleFavorite` |
| 10 | `SettingsMiuix.kt:251` / `SettingsMaterial.kt:228` | 提醒列表卡片显示 `favorites_count` |
| 11 | `ReminderRefreshWorker.kt:41,93` | **后台 Worker 直接 `FavoriteSatellitesStore(applicationContext).load()`** 过滤收藏卫星 |
| 12 | `ReminderScheduler.kt:123` | 注释「如取消收藏，由 toggleFavorite 处理」 |
| 13 | `ReminderSettings.kt:40-43` | `ReminderItem` 文档：「每颗收藏卫星对应一条提醒，自动跟随收藏状态生成/删除」 |
| 14 | 测试 | `FavoriteSatellitesStoreIntegrationTest.kt`、`MainViewModelIntegrationTest.kt:41-65`、`SatelliteFilterTest.kt:96-134`、`SatelliteFilterIntegrationTest.kt:118,167-175` |

> ⚠️ 收藏不是一个孤立开关，而是**提醒功能的触发条件**，触发点散落在 ViewModel、后台 Worker、首页、设置页、排序与筛选共 14 处。**这是本次需求真正的工作量与风险所在**（而非分类 UI 本身）。

**提醒子系统数据结构**（改造基础）：

- `ReminderStore`：prefs `radio_area_reminders`，键 `items` = JSON 数组，每项含 `catalog/name/aos/los/max_el/aos_az/los_az/modes/enabled`（`ReminderStore.kt:130-147`）。
- `ReminderItem`：**已有 `enabled` 字段**（`ReminderSettings.kt:63`）；`setItemEnabled` / `deleteReminderItem`（`MainViewModel.kt:505-523`）已支持**单独关闭某颗卫星的提醒** —— 这正是 Q3.1=A 所需的「卫星级提醒开关」，**可直接复用，无需新概念**。
- 提醒列表页 `ReminderListRouteScreen.kt` 与设置页「查看提醒列表」入口已存在。

### 2.5 「卫星源」现状

- `SettingsStore` 已持久化 `tleSourceAmateur` / `tleSourceSatnogs` / `tleSourceActive` / `useSatnogsTransmitters`（`data/SettingsStore.kt:93-124`，keys `:128-138`）。
- **但没有任何设置 UI 暴露这些开关**（全仓库 grep 仅命中 SettingsStore 与调用点）。
- 全仓库**无 category/group/tag 数据模型**，也无任何导入导出。

### 2.6 设置页结构

```
ui/screen/settings/
├── SettingsScreen.kt    (57)  SettingPager：装配 state + actions，按 UiMode 分发
├── SettingsUiState.kt   (58)  SettingsUiState / SettingsBusinessState / SettingsScreenActions
├── SettingsMiuix.kt    (299)  Miuix 实现
├── SettingsMaterial.kt (296)  Material 实现
└── UpdateDialogs.kt    (431)
```

- 回调集中在 `SettingsScreenActions` 数据类（`SettingsUiState.kt:46-58`），由 `SettingPager` 一次性构造（`SettingsScreen.kt:39-51`）。
- 每个功能是 `Card { ArrowPreference / SwitchPreference / OverlayDropdownPreference }` 块。
- **导入/导出入口落点**：新增一张 Card，并向 `SettingsScreenActions` 追加 2 个 action。

### 2.7 文件导出/导入能力现状

| 能力 | 现状 |
|---|---|
| **导出** | ✅ 已有先例：`SendLogDialog.kt:51` 用 `ActivityResultContracts.CreateDocument("application/gzip")`，`:58-70` 用 `contentResolver.openOutputStream(uri)?.use { ... }` + Toast |
| **导入** | ❌ 全仓库未使用 `OpenDocument` / `GetContent`（仅 `CreateDocument`、`CropImageContract`、`PickVisualMedia`） |

导出可直接照抄现有范式；导入需新引入 `ActivityResultContracts.OpenDocument`，常规做法，无技术风险。

### 2.8 路由与导航

- 路由：`ui/navigation3/Routes.kt`，`sealed interface Route : NavKey, Parcelable`，`@Parcelize + @Serializable`。
- 注册：`ui/MainActivity.kt:192-225` 的 `entry<Route.X> { ... }`。
- 新增分类管理页需改 2 处。

### 2.9 字符串与多语言

- `values/strings.xml`：298 条，**215 条 `translatable="false"`**；**217 条含中文，其中 209 条同时 `translatable="false"`**。
- 默认（英文）资源里，卫星模块字符串大量是**硬编码中文 + 禁止翻译**：`filter_only_favorites`（`:145`）、`filter_title`（`:148`）、`satellite_management`（`:154`）、`favorites_count`（`:156`）。
- 46 个 locale 目录，覆盖不均（`values-zh-rCN` 81 条、`values-de` 8 条、`values-ar` 9 条）。

> **决策**：沿用现状（中文 + `translatable="false"`），与同页面 `filter_title` 保持一致；国际化债务另开 issue 统一处理。

### 2.10 测试现状

- 单元测试 35 个文件（JUnit + Robolectric）；仪器测试 5 个。
- 已有 `ui/SatelliteFilterTest.kt`、`androidTest/ui/SatelliteFilterIntegrationTest.kt`、`androidTest/data/satellite/FavoriteSatellitesStoreIntegrationTest.kt`、`androidTest/ui/MainViewModelIntegrationTest.kt`（**直接断言收藏的持久化与 toggle 行为，合并后必须重写**）。
- CI：`.github/workflows/ci.yml`（`gradle :app:testDebugUnitTest` + `assembleDebug`）、`test.yml`。

---

## 三、方案设计

### 3.1 数据模型

```kotlin
// data/satellite/SatelliteCategory.kt（新建）
@Immutable
data class SatelliteCategory(
    val id: String,          // UUID，跨设备分享的稳定标识
    val name: String,
    val sortOrder: Int = 0,  // MVP 仅名称；颜色/图标二期
)

/** 归属关系：NORAD → 分类 id 集合（多对多，Q2=A） */
typealias CategoryMembership = Map<Int, Set<String>>

/** 卫星级提醒开关（Q3.1=A，替代原「收藏即提醒」） */
typealias SatelliteReminderFlags = Set<Int>
```

`SatelliteListItem` **不需要改动**：分类从外部 Map 查询（`membership[sat.catalogNumber]`），避免污染 `tle/pass/radios` 三元组。

### 3.2 提醒链路解耦（本次改造的核心）

**原链路**

```
收藏 Set<Int> ──┬─→ toggleFavorite 建/删 ReminderItem + 闹钟
                └─→ refreshRemindersFromPrediction 过滤「哪些卫星更新提醒」
```

**目标链路**

```
分类归属 Map<Int, Set<categoryId>>  ──→  组织 / 浏览 / 筛选（不碰提醒）
提醒开关 Set<Int>                   ──→  refreshRemindersFromPrediction
                                          ReminderRefreshWorker
```

具体改动：

1. **删除** `FavoriteSatellitesStore`（保留为只读迁移源，见 §3.5）。
2. `MainViewModel.toggleFavorite` → 拆成两个互不干扰的方法：
   - `toggleSatelliteCategory(catalogNumber, categoryId)`：**绝不触碰** `reminderStore` / `reminderScheduler`；
   - `setSatelliteReminderEnabled(catalogNumber, enabled)`：把现在 `toggleFavorite` 里那两段副作用逻辑（`:441-467`）**平移**过来，内部复用现成的 `addReminderForSatellite` / `reminderStore.removeItem` + `reminderScheduler.cancel`。
3. `refreshRemindersFromPrediction`（`:1103-1146`）把 `val favorites = _favoriteSatellites.value` 换成 `val reminderFlags = _satelliteReminders.value`。
4. `ReminderRefreshWorker`（`:41,93`）改为读提醒开关（最简实现：直接以 `ReminderStore.loadItems()` 中 `enabled == true` 的项为准，避免 Worker 依赖新存储）。
5. 保留 `ReminderItem.enabled` 与 `setReminderItemEnabled` / `deleteReminderItem` —— 语义天然吻合，**不引入新概念**。

### 3.3 持久化

新建 `data/satellite/SatelliteCategoryStore.kt`，照抄 `SatelliteCacheStore` 的 SharedPreferences + JSON 范式：

```
prefs: "radio_area_satellite_categories"
├── KEY_CATEGORIES  : JSON 数组 [{id,name,sortOrder}, ...]
├── KEY_MEMBERSHIP  : JSON 对象 {"25544":["<uuid>","<uuid>"], ...}
├── KEY_REMINDERS   : JSON 数组 [25544, 43017]
└── KEY_MIGRATED_V1 : Boolean（迁移标记）
```

- 16k 卫星中通常只有少量被打标，`KEY_MEMBERSHIP` 只存已归属项，体积可控。
- `MainViewModel` 增加 `_satelliteCategories` / `_categoryMembership` / `_satelliteReminders` 三个 `mutableStateOf`，与 `_favoriteSatellites`（`:197`）同构。

### 3.4 交换格式（导出文件）

```json
{
  "schema": "hamkit.satellite.categories",
  "version": 1,
  "exportedAt": "2026-08-20T12:00:00Z",
  "appVersion": "3.0.0",
  "categories": [
    { "id": "8f3c…", "name": "新手常用 FM", "sortOrder": 0 },
    { "id": "b21d…", "name": "线性转发器", "sortOrder": 1 }
  ],
  "membership": { "25544": ["8f3c…"], "43017": ["8f3c…"], "7530": ["b21d…"] },
  "reminderFlags": [25544],
  "satelliteNames": { "25544": "ISS (ZARYA)", "43017": "AO-91" }
}
```

- `schema` + `version` 用于导入前校验，必须显式拒绝不匹配的文件；
- `satelliteNames` 是冗余可读性字段（便于分享文件在编辑器里可读），导入时**忽略、以 NORAD 为准**；
- `id` 用 UUID，避免两台设备合并冲突；
- 文件后缀 `.json`，MIME `application/json`，默认名 `HamKit_satellite_categories_yyyy-MM-dd.json`（对齐 `SendLogDialog` 时间戳命名习惯）。

### 3.5 导入与迁移

**导入（Q4=合并）**：

1. `OpenDocument` 选文件 → 读取 → 解析 JSON；
2. 校验 `schema` 与 `version`，不匹配则提示「文件格式不支持」；
3. 分类按 `id` 匹配：命中 → 更新元数据；未命中 → 新增；
4. `membership` 取**并集**，不删除既有归属；`reminderFlags` 同样取并集；
5. Toast 汇总：「导入 3 个分类、42 颗卫星归属（新增 2 个分类，跳过 1 个重复）」；
6. 全程 `Dispatchers.IO`，异常转错误 Toast，不崩溃、不破坏现有配置。

**历史迁移（Q3.2/Q3.3）**：

1. 首次启动读取 `FavoriteSatellitesStore.load()`；
2. 非空则创建内置分类「我的关注」（`id = "builtin:starred"`，`sortOrder = 0`），收藏编号全部写入其 `membership`；
3. 同时把这些编号写入 `KEY_REMINDERS`（保证「原来会提醒的，升级后仍然提醒」，**行为不回退**）；
4. 写 `KEY_MIGRATED_V1 = true`，避免重复迁移；
5. 迁移异常时**不标记**，下次启动重试；**绝不删除 `radio_area_favorites` 原数据**（回滚兜底）。

> 这是 Q3=C 下**唯一的用户可感知风险点**：迁移做错会让老用户丢失全部提醒。必须配套回归测试。

### 3.6 UI 方案

#### (a) 卫星管理页入口

在 `SatelliteOverviewCard` 内、`SatelliteFilterButton` 旁（`SatelliteManagementScreen.kt:502` / `SatelliteManagementMaterial.kt:491`）加同款 `SatelliteCategoryButton`：

- 图标 `Icons.Rounded.Category`（`material-icons-extended` 已依赖）；
- 文字「分类」，激活态 `colorScheme.primary` + 6dp 小圆点，**完全复用筛选按钮视觉语言**；
- 点击 → 分类弹窗（Miuix `OverlayDialog`，照抄 `SatelliteFilterDialog.kt:23`）或 `navigator.push(Route.SatelliteCategoryManage)`。

> 两个皮肤各改一次。

#### (b) 分类弹窗 / 管理页内容

照抄 `SatelliteFilterScreen.kt` 的双皮肤范式（`SatelliteFilterDialogContent:59` 分发）：

1. **分类列表**：名称 + 卫星数 + 点击切换为当前筛选 + 编辑（重命名/删除）；
2. **新建分类**；
3. **删除确认**：移除 N 颗卫星的归属，但**不删除卫星、不影响提醒**（提醒是独立的卫星级开关）。

#### (c) 卫星打分类 & 星标按钮改造

列表项 `SatelliteManagementItem`（`:540` / Material `:536`）现有星标按钮改为分类入口：

- 点击 → 弹出该卫星的分类多选表（复用 `MiuixFilterSelectableRow` 风格，`SatelliteFilterScreen.kt:196`），勾选即时生效；
- 同一弹窗内提供**「过境提醒」开关**，让「组织」与「提醒」在同一处但语义分离；
- 二期可选：批量分类模式（多选卫星 → 统一归类），1.6 万条目录下体验更好。

#### (d) 与现有筛选集成

```kotlin
// SatelliteFilter 扩展（MainViewModel.kt:1408）
val categoryIds: Set<String> = emptySet(),   // 空集合 = 不按分类筛选；取代 onlyFavorites
```

`applyFilterToItems`（`:1427`）把 `favorites: Set<Int>` 参数替换为 `membership: Map<Int, Set<String>> = emptyMap()`，匹配逻辑与既有 `onlyFavorites` 同构：

```kotlin
val categoryOk = filter.categoryIds.isEmpty() ||
    membership[sat.catalogNumber].orEmpty().any { it in filter.categoryIds }
```

⚠️ 「仅已关注」开关（`SatelliteFilterScreen.kt:166-169, 344-347`）改为「仅显示分类…」多选；排序键（`SatelliteManagementScreen.kt:230`）从 `in favorites` 改为「属于任一分类」。

#### (e) 设置页导入/导出

在 `SettingsMiuix.kt` / `SettingsMaterial.kt` 新增 Card：

```
┌─ 卫星分类配置 ────────────────────────┐
│ ⬆ 导出分类配置   导出为 JSON 文件分享   │
│ ⬇ 导入分类配置   从 JSON 文件合并分类   │
└──────────────────────────────────────┘
```

- 追加 `onExportSatelliteCategories` / `onImportSatelliteCategories` 到 `SettingsScreenActions`（`SettingsUiState.kt:46`），在 `SettingPager`（`SettingsScreen.kt:39`）装配；
- 导出 `CreateDocument("application/json")` + `openOutputStream(uri)`（照抄 `SendLogDialog.kt:51-70`）；
- 导入 `OpenDocument()` + `openInputStream(uri)`。

---

## 四、影响面清单（文件级）

| 文件 | 改动 | 类型 |
|---|---|---|
| `data/satellite/SatelliteCategory.kt` | 新建 模型 | 新增 |
| `data/satellite/SatelliteCategoryStore.kt` | 新建 持久化（分类 + 归属 + 提醒开关 + 迁移标记） | 新增 |
| `data/satellite/SatelliteCategoryExchange.kt` | 新建 导出序列化 / 导入解析 + 合并 | 新增 |
| `data/satellite/FavoriteSatellitesStore.kt` | 降级为迁移只读源 | 改 |
| `data/reminder/ReminderRefreshWorker.kt` | `:41,93` 收藏过滤 → 提醒开关过滤 | 改 |
| `data/reminder/ReminderSettings.kt` | `ReminderItem` 文档语义更新 | 改 |
| `data/reminder/ReminderScheduler.kt` | `:123` 注释更新 | 改 |
| `ui/navigation3/Routes.kt` | 加 `Route.SatelliteCategoryManage` | 改 |
| `ui/MainActivity.kt` | `:192-225` 加 `entry` | 改 |
| `ui/MainViewModel.kt` | 删 `toggleFavorite`；新增分类 state + toggle + 提醒开关；`:1103-1146` 改提醒过滤；`:1408/1427/1452` 改 filter；新增迁移 | 改（重） |
| `ui/screen/satellite/SatelliteManagementScreen.kt` | 分类按钮；星标→分类入口；计数/筛选/排序改造 | 改 |
| `ui/screen/satellite/SatelliteManagementMaterial.kt` | 同上（Material 皮肤） | 改 |
| `ui/screen/satellite/SatelliteFilterScreen.kt` | 「仅已关注」→ 分类多选（双皮肤） | 改 |
| `ui/screen/satellite/SatelliteCategoryScreen.kt` | 新建 分类内容（双皮肤）+ 弹窗外壳 | 新增 |
| `ui/screen/home/HomeScreen.kt` | `:45,84,93-97,106` 收藏 → 分类/提醒开关 | 改 |
| `ui/screen/home/HomeUiState.kt` | `:31` `favorites` 字段语义 | 改 |
| `ui/screen/settings/SettingsMiuix.kt` | 导入/导出 Card；`:251` 计数文案 | 改 |
| `ui/screen/settings/SettingsMaterial.kt` | 同上；`:228` | 改 |
| `ui/screen/settings/SettingsUiState.kt` | `SettingsScreenActions` 加 2 回调 | 改 |
| `ui/screen/settings/SettingsScreen.kt` | 装配回调 | 改 |
| `res/values/strings.xml` | 新增约 20-25 条（中文 + `translatable="false"`） | 改 |
| `test/.../SatelliteCategoryStoreTest.kt` | 新建 存储/往返 | 新增 |
| `test/.../SatelliteCategoryExchangeTest.kt` | 新建 导入导出/合并/异常文件 | 新增 |
| `test/.../SatelliteCategoryMigrationTest.kt` | 新建 历史收藏迁移（行为不回退） | 新增 |
| `test/.../SatelliteFilterTest.kt` | 分类筛选取代 onlyFavorites | 改 |
| `androidTest/.../FavoriteSatellitesStoreIntegrationTest.kt` | 重写为分类存储测试 | 改 |
| `androidTest/.../MainViewModelIntegrationTest.kt` | `:41-65` 收藏断言重写 | 改 |

**规模估算**：6 个新文件 + 18 个既有文件改动，新增代码约 1400-1900 行（含双皮肤 UI 与迁移），新增/重写测试约 400-600 行。

---

## 五、风险

| 风险 | 等级 | 对策 |
|---|---|---|
| **提醒行为回归** | 🔴 高 | 改动「什么决定卫星被提醒」。**先写迁移与提醒链路的回归测试**（升级后提醒数量/内容不变），再动实现 |
| **「加入分类」误触发提醒** | 🟢 已消除 | Q3.1=A 把「组织」与「提醒」彻底分离，加入分类不产生任何提醒 |
| **历史收藏丢失** | 🟠 中高 | 迁移幂等 + 失败不标记 + 保留 `radio_area_favorites` 原数据回滚 |
| **耦合点分散在后台 Worker** | 🟠 中高 | `ReminderRefreshWorker` 在无 ViewModel 环境下直接读 `FavoriteSatellitesStore`，最容易漏改。对策：「谁需要提醒」收敛到单一来源 |
| **双皮肤 UI 翻倍** | 🟠 中高 | 逻辑放 Store/ViewModel，UI 只做薄渲染，沿用内容分发范式 |
| **1.6 万条目录下交互性能** | 🟡 中 | 复用 `nameQuery` 搜索 + 限制结果数；`membership` 用 Map O(1) 查询 |
| **导入任意文件崩溃** | 🟡 中 | 校验 `schema`/`version`，全程 try-catch，解析放 IO 线程 |
| **概念迁移的 UX 断裂** | 🟡 中 | 内置「我的关注」保留熟悉感；`favorites_count`（「已关注」）文案统筹替换 |
| **`applyFilterToItems` 签名变更** | 🟢 低 | 2 个调用点 + 测试；默认参数保持兼容 |

---

## 六、实施计划（建议 4 个 PR）

> 关键原则：**先把「行为等价重构」独立交付并验证，再叠加新功能。**

| PR | 内容 | 为什么单独拆 |
|---|---|---|
| **PR 1** | 提醒链路解耦 + 历史迁移：新增提醒开关存储；`toggleFavorite` 拆为 `toggleSatelliteCategory`(空实现) / `setSatelliteReminderEnabled`；`refreshRemindersFromPrediction` 与 `ReminderRefreshWorker` 改读提醒开关；迁移逻辑 + 回归测试 | 行为等价重构，**不引入任何 UI 变化**。升级后老用户提醒完全不变，是风险最高也最该单独验证的一步 |
| **PR 2** | 分类模型 + 存储 + ViewModel 状态 + `SatelliteFilter.categoryIds` + 筛选逻辑与单测 | 纯逻辑，可脱离 UI 完整测试 |
| **PR 3** | 双皮肤 UI：管理页分类按钮、分类管理弹窗/页、列表项分类入口、星标按钮改造、筛选页「仅已关注」→ 分类多选、字符串 | 工作量集中处，2 个皮肤各一套 |
| **PR 4** | 设置页导入/导出：交换格式、`CreateDocument`/`OpenDocument`、合并策略、异常文件处理 + 单测 | 独立闭环，可最后交付 |

**验收标准（一期）**

1. 可新建/重命名/删除分类；删除分类后卫星仍在目录中。
2. 卫星管理页有分类入口按钮，激活态视觉与筛选按钮一致。
3. 可给任意卫星（含 active 源 1.6 万条）分配一个或多个分类。
4. 可按分类筛选卫星列表，且与模式/状态筛选叠加生效。
5. **老用户升级后提醒行为不回退**：升级前会提醒的卫星，升级后仍然提醒（数量与内容一致）。
6. **把卫星加入分类不会创建/删除任何提醒或闹钟**（专项断言）。
7. 每颗卫星可单独开关过境提醒，且与分类归属互不影响。
8. 设置页可导出分类配置为 JSON；导出文件可被同版本应用导入，分类/归属/提醒开关往返一致。
9. 导入采用合并策略，同 id 分类不重复；非法文件给出友好提示且**不崩溃**、不破坏现有配置。
10. 迁移幂等：重复启动不产生重复分类或重复提醒。
11. `gradle :app:testDebugUnitTest` 与 CI 全绿。

**二期**：分类颜色/图标、拖拽排序、列表按分类分组显示、批量分类模式、内置「未分类」视图。
