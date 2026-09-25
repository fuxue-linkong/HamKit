# 代码审计报告：卫星自定义分类 + 分类配置导入/导出

> 审计日期：2026-09-25
> 审计对象：miuix 分支工作区（未提交改动）
> 需求文档：`docs/REQUIREMENT_SATELLITE_CATEGORY.md`

---

## 〇、审计方式与**验证能力的重要限制**

⭐ **本项目在当前环境中无法编译。** `./gradlew :app:compileDebugKotlin` 在配置阶段即失败：

```
Plugin [id: 'com.android.application', version: '9.2.1', apply: false] was not found
```

因为 AGP 9.2.1 / Kotlin 2.4.0 / compileSdk 37 / compose-bom 2026.05.01 均无法从公开 Maven 仓库
解析（Gradle 9.4.1 已缓存，但依赖与 SDK platform 37 缺失，SDK 里只有 android-23）。
也没有可用的独立 `kotlinc`。

因此本次审计**全部为静态审查**，不存在「编译通过 / 测试通过」这一层证据。
审计采用 4 个独立视角进行：

| # | 视角 | 覆盖范围 | 执行方式 |
|---|---|---|---|
| 1 | 编译级静态审计 | 未解析引用、签名/具名参数不匹配、缺失导入、Compose/Miuix API 误用 | ⚠️ 委派的子代理两度长时间不收敛已被中断，**改由主代理用确定性脚本自查**（见下表） |
| 2 | 行为回归审计 | 「收藏→分类」迁移、提醒链路解耦、幂等性与前后台一致性 | ✅ 独立子代理完成 |
| 3 | 逻辑与边界审计 | 纯函数操作、持久化、导入校验与合并语义、测试质量 | ✅ 独立子代理完成 |
| 4 | UI/资源/UX 审计 | 字符串资源、格式参数、双皮肤一致性、布局与可达性、性能 | ✅ 独立子代理完成 |

> 视角 1 未能由独立第三方完成，是本报告的一处**方法论缺陷**；下表的脚本化检查覆盖了它原本要查的大部分内容，但不等价于一次独立审查。

**已执行的机器化静态检查（这些是确定性的、非人工判断的结论）：**

| 检查 | 结果 |
|---|---|
| 全部 `app/src/**/*.kt` 的 `R.string.*` 是否都在 `values/strings.xml` 中 | ✅ 全部解析（唯一「未解析」是 `android.R.string.ok` 的误报） |
| 本次新增的 29 条字符串是否有未被引用的 | ✅ 无 |
| 是否残留已删除 API（`onlyFavorites` / `toggleFavorite` / `favoriteSatellites` / `isFavorite` / `favoriteCount` / `onToggleFavorite`） | ✅ 无（注释除外） |
| 所有改动文件的括号/花括号配平 | ✅ 全部配平（`TleParser.kt` 的告警是该文件正则里的 `{4}` 导致的误报，且该文件未被改动） |
| `strings.xml` XML 合法性、重名检查 | ✅ 合法、无重名 |
| 字符串格式参数（`%1$s` / `%1$d`）与调用点实参数量、类型 | ✅ 全部匹配 |
| 新增符号的导入（`NotificationsNone` / `Context` / `Uri` / `ByteArrayOutputStream` / `R` / `withContext`） | ✅ 全部存在 |
| **具名参数签名比对**：21 个被改动的函数/Composable，逐个提取声明参数列表并与全部调用点的具名实参对照 | ✅ **0 处不匹配**，且无「声明有而调用未传」的非默认参数 |
| 项目内 `com.example.hamkit.*` 导入是否都能解析到真实声明 | ✅ 全部解析（`R` / `BuildConfig` 为构建期生成，属预期） |
| `Modifier.weight(...)` 是否都在 `Row`/`Column` 内容 lambda 内（共 32 处） | ✅ 全部在作用域内 |
| 双皮肤隔离：`...Miuix` 函数不得渲染 `androidx.compose.material3.*` 组件，反之亦然 | ✅ Miuix 区（`SatelliteCategoryScreen` 1–520 行）零 material3 组件；Material 区零 Miuix 组件 |

> 说明：签名比对的脚本首版把 Kotlin 函数类型箭头 `->` 里的 `>` 误当闭合括号，导致虚报 83 处「不匹配」；
> 修正括号集合后复跑，结果为 0 处不匹配。此处记录该过程，避免读者被中间结论误导。

---

## 一、审计发现与处置

### 🔴 BLOCKER（1 项，已修复）

**B1 — 后台提醒 Worker 在迁移执行前会「取消」历史用户的提醒（回归）**

- 位置：`ReminderRefreshWorker.kt`（当时读 `SatelliteCategoryStore.loadReminderFlags()`）
- 机理：升级后 `ReminderBootReceiver` 收到 `MY_PACKAGE_REPLACED`，30 秒后入队 Worker；
  而迁移只在 `MainViewModel`（仅由 `MainActivity` 构造）里执行。Worker 先跑时
  `reminderFlags` 为空 → 跳过提醒项更新 → 直接 `scheduleAll(旧提醒项)`，
  而 `ReminderScheduler.schedule()` 会**取消**触发时刻已过（`AOS - leadMinutes < now`）的闹钟。
- 后果：老用户在打开应用前，提醒静默失效。原实现读的是有数据的 `FavoriteSatellitesStore`，
  所以这是本次重构**新引入**的回归。
- **处置（已修复）**：迁移下沉为 `SatelliteCategoryStore.loadConfigWithLegacyMigration(legacyFavorites)`，
  由 `MainViewModel` 与 `ReminderRefreshWorker` **共用同一实现**；Worker 在任何提前 return
  之前调用它，因此无位置/总开关关闭的分支也不会跳过迁移。
- 附加：迁移全程持有进程级 `CONFIG_LOCK`，与 `mutate()` 互斥。

### 🟠 HIGH（3 项，已修复）

**H1 — 两套「真相」互相矛盾，且删除提醒会「复活」**

- 卫星页/分类弹窗读 `reminderFlags`，提醒列表读 `ReminderItem.enabled`；而
  `setReminderItemEnabled` / `deleteReminderItem` 都**不写** `reminderFlags`。
- 后果：① 在提醒列表关掉后，卫星页仍显示「已开启」；② 在提醒列表删除后，
  下一次过境预测刷新或 Worker 会依据残留开关**把提醒项重建回来**；
  ③ 卫星页的开关因为提前 return 无法补建缺失的提醒项。
- **处置（已修复）**：确立 `reminderFlags` 为**唯一权威来源**。
  `setReminderItemEnabled` / `deleteReminderItem` 均委托给 `setSatelliteReminderEnabled`；
  后者改为幂等且会补建缺失的提醒项。

**H2 — 导入可以凭空种下「不可删除」的内置分类**

- `merge` 原样接受导入文件里 `builtin:` 前缀的分类，而 `isBuiltin` 由前缀推导、
  删除按钮被隐藏、`removeCategory` 也拒绝删除。
- 触发：把一个已迁移用户导出的文件，导入到一台**没有**内置分类的设备上
  （该文件的 categories 里含 `builtin:starred`）→ 种下一个无法删除的「我的关注」；
  伪造 `builtin:xxx` 的 id 同样有效；`merge` 还会用文件里的名字**重命名本地内置分类**。
- **处置（已修复）**：`merge` 不再新增任何 `isBuiltin` 分类，也不再重命名本地内置分类
  （内置分类只能由 `migrateLegacyFavorites` 建立）。

**H3 — 导入文件的大小上限在「读完」之后才生效**

- `SettingsScreen` 先 `input.readBytes()` 再交给 `parse()`，而 `MAX_JSON_CHARS` 检查在 `parse()` 内部。
  SAF 用 `*/*` 打开，用户误选大文件时会在检查前就把整个文件读进内存（可能被 LMK 杀掉）。
- **处置（已修复）**：新增 `readBoundedText(context, uri, maxBytes)`，边读边计数、超限立即失败。

### 🟡 MEDIUM（10 项，9 项已修复）

| # | 问题 | 处置 |
|---|---|---|
| M1 | 前台与后台并发「读-改-写」全量覆盖，可能丢更新（迁移期尤甚） | ✅ 新增 `SatelliteCategoryStore.mutate()`，在进程级 `CONFIG_LOCK` 内重读磁盘再变换；迁移同样持锁 |
| M2 | 容错读出的降级结果被立刻回写，把「坏一条」固化成永久丢失 | ✅ 迁移仅在 `migrated != stored` 时写盘；`mutate` 同样跳过无变化写入 |
| M3 | 导入会静默开启他人的提醒开关，且无变化也提示「导入完成」 | ✅ Toast 增加第 4 个参数报告开启的提醒数；无变化时改用独立文案 |
| M4 | `loadConfig` 可返回「悬空归属」且无法从 UI 清除（`clearAssignments` 无生产调用点） | ✅ `loadConfig` 按分类 id 清洗归属；分类不可读时不清洗，避免连带抹掉归属 |
| M5 | `addCategory` 接受空白名与重复 id，重复 id 会让 rename/remove 命中两条 | ✅ 空白名不新增；重复 id 返回已有分类，不新增 |
| M6 | 迁移后的提醒恢复依赖 SGP4 路径，命中 15 分钟预测缓存时整场会话都不重注册闹钟 | ✅ 新增 `reconcileRemindersOnLaunch()`，在 `initializeIfNeeded()` 开头幂等重注册（读取放 IO 线程） |
| M7 | `ReminderNotificationHelper` 频道描述仍写「收藏卫星」 | ✅ 改为「已开启过境提醒的卫星」 |
| M8 | 每个分类行每次重组都做 O(归属) 的 `satelliteCountOf` 扫描 | ✅ 用 `remember(config)` 预计算 `categoryCounts` |
| M9 | 导入按 id 覆盖本地分类名（含自己改名前的旧导出） | ⚠️ **不修**：这是 Q4「合并」决策的既定语义，仅在报告中记录 |
| M10 | `prefs.getString` 在 try 之外 → 键值类型异常会 `ClassCastException` 冒泡到构造函数崩溃 | ✅ 全部改为 `prefs.all[key] as? String` / `as? Boolean`（含迁移标记） |

### 🟢 LOW（14 项，修复或缓解 12 项）

| # | 问题 | 处置 |
|---|---|---|
| L1 | Miuix 皮肤下用了 `material3.AlertDialog`（Miuix 模式没有 MaterialTheme provider，会脱离主题） | ✅ 新增 Miuix 风格 `MiuixConfirmDialog`（`OverlayDialog` + 两个 `Button`） |
| L2 | 文案仍教用户「收藏卫星」（该概念已不存在） | ✅ 改写 `reminder_section_desc` / `reminder_list_empty`，频道描述一并更新 |
| L3 | 提醒开关不可发现（原星标一键「标记+提醒」被移除，开关藏在「归入分类」弹窗里） | ✅ 列表项的铃铛图标改为**可点击开关**；弹窗标题改为「分类与提醒」 |
| L4 | 列表项重建 1.6 万条：`mainViewModel.satelliteItems` 是昂贵 getter，弹窗只为取一个名字 | ✅ 新增 `MainViewModel.satelliteNameOf()`（按 TLE 实例身份缓存名称索引） |
| L5 | `categorizedCount` 未 `remember`，而本页有 5 秒一次的倒计时重组 | ✅ 加 `remember` |
| L6 | 用户自定义长分类名会把勾选框/勾选指示挤出屏幕 | ✅ 标签加 `Modifier.weight(1f)`（分类弹窗与筛选页各 2 处） |
| L7 | 已分类卡片的分类芯片与卡片同色（都是 `tertiaryContainer`），看不出边界 | ✅ 芯片改用 `surfaceVariant` / `onSurfaceVariant` |
| L8 | Material 设置页新增行缺少 chevron，「可点击」暗示丢失；leading Icon 的 `contentDescription` 与标题重复播报 | ✅ 补 `trailingContent` chevron，leading Icon 描述置 `null` |
| L9 | `satellite_category_*` 的 Kotlin 里硬编码中文反馈文案 | ✅ 新增 `satellite_reminder_feedback_on/pending/off` 资源 |
| L10 | 导入失败提示中英混杂（`unable to open input stream` 直接拼进「导入失败：%1$s」） | ◐ 已把两处 `error(...)` 改为中文；框架异常（如 `FileNotFoundException`）仍是英文，未做错误分类映射 |
| L11 | Miuix 弹窗无「确定/取消」按钮、无 `imePadding`；自定义开关滑块硬编码白色；`MiuixCheckRow` 触控高度 <48dp 且无 `Role.Checkbox` 语义 | ◐ 已修：滑块改用 `colorScheme.surface`（不再硬编码白）；`MiuixCheckRow`/`MiuixSwitchRow` 加 `heightIn(min = 48.dp)` + `toggleable(role = Role.Checkbox / Role.Switch)` 无障碍语义。**未做**：弹窗的确定/取消按钮与 `imePadding`（依赖 Miuix `OverlayDialog` 的 IME 行为，本地无源码无法确认） |
| L12 | Miuix 弹窗内容用 `Column(verticalScroll)` 全量组合，导入数百个分类时会一次性组合全部行 | ◐ 已从根因缓解：导入解析新增 `MAX_CATEGORIES = 200` 上限（超出即截断），分类数不再可能无界；仍非懒加载 |
| L13 | `clear()` 会一并清除迁移标记，未来若接到「重置分类」入口会复活内置分类 | ◐ 未改语义，但在 KDoc 中显著标注了该陷阱与正确做法 |
| L14 | `HomeBusinessState.categorizedSatellites` / `nextSatellite` 是死状态（两个皮肤都不消费） | ✅ 已删除 `HomeBusinessState` 的两个死字段、`HomeScreen` 中对应的 `filter`/`minByOrNull` 计算与 `categoryConfig` 读取。顺带移除了原判断 `in favorites \|\| !isCurrentlyVisible` 里使收藏判断失效的 `\|\|` 误用（该值从未被渲染，删除是行为中性的） |

### 测试质量（修复 1 处无效断言 / 净增 76 个用例）

- 修复了一个**同义反复**的断言：`satelliteCountOf counts members` 里的
  `toggleAssignment(3, "b")` 是空操作（分类 "b" 从未创建），因此
  `assertEquals(0, satelliteCountOf("b"))` 恒真。已改为先创建分类再断言，
  并补了「一星多分类分别计数」的断言。
- 新增用例净增 76 个（新增 3 个测试文件共 68 个，`SatelliteFilterTest` +3，`MainViewModelIntegrationTest` +5），覆盖：空白名/重复 id 的 `addCategory`、`sortOrder` 并列的确定性、
  导入内置分类防护（3 例）、缺失 `categories` 数组、非整数 `version`、
  悬空归属清洗、分类不可读时不清空归属、键值类型异常不崩溃、
  JSON `null` id、非正 NORAD 过滤、`loadConfigWithLegacyMigration` 的迁移与幂等。

---

## 二、按不变式的结论

需求文档 §3.2 定义的核心不变式：

| 不变式 | 结论 | 依据 |
|---|---|---|
| **I1** 分类操作不得创建/删除任何提醒或闹钟 | ✅ PASS | `toggleSatelliteCategory` → `updateCategoryConfig` → `mutate`，全链路零 `reminderStore` / `reminderScheduler` 引用（`removeCategory` 也只改 categories/membership）。有 androidTest 断言 |
| **I2** 老用户升级后提醒行为不回退 | ✅ PASS（修复 B1 后） | 迁移由前后台共用同一实现（`loadConfigWithLegacyMigration`），Worker 在提前 return 前调用；迁移同时写入 `reminderFlags` 与内置分类归属 |
| **I3** 幂等：不重复建分类、不重复建提醒 | ✅ PASS | 迁移受 `legacy_migration_done_v1` 标记保护；`migrateLegacyFavorites` 复用已有内置分类并取并集；提醒项用 `indexOfFirst`/upsert；并发只可能丢更新（已加锁），不会重复 |
| **I4** 前后台对「谁需要提醒」判断一致 | ✅ PASS | 两处使用**同一个**商店方法与**同一个**谓词 `catalogNumber in reminderFlags && aosTime > now`，且都保留既有 `ReminderItem.enabled` |

---

## 三、残余风险与未验证项（重要）

### 未验证（因无法编译）

1. **编译正确性**——本次全部为静态审查。虽然已用脚本穷尽检查了字符串引用、
   括号配平、导入存在性与签名比对，仍不能替代编译器。
2. **真实行为**——`org.json` 在 Android 上的 `null`/类型强转行为（`optString` 对显式 `null`
   返回字面量 `"null"`）是根据 AOSP 源码推断，未实测。
3. **Miuix 0.9.3 的具体签名**——该库源码不在任何本地 Gradle/Maven 缓存中，
   `OverlayDialog` / `IconButton` / `Button` 的参数与是否处理 IME insets 只能靠仓库内既有用例推断
   （新代码使用的都是仓库内已有先例的 API 组合）。

### 已知残余风险

| 风险 | 说明 |
|---|---|
| 无 Worker 级回归测试 | 最关键的 B1 回归（升级后 Worker 先于应用运行）**没有自动化测试**。`ReminderRefreshWorker` 至今零测试覆盖。建议补一个 `TestWorkerBuilder` 用例 |
| 迁移的 `apply()` 持久性 | 配置与迁移标记是两次异步写；两者之间被杀会重跑迁移（幂等，安全）。`clear()` 的陷阱已在 KDoc 标注 |
| 并发丢更新 | 已用进程级锁覆盖；但 Worker 与前台若在不同进程则不适用（当前 Worker 未声明 `android:process`，同进程，安全） |
| 提醒恢复的边界 | `reconcileRemindersOnLaunch()` 只「重注册已有提醒项」，不会创建新项（那需要预测结果）。若提醒项已被清空而开关仍为开，需等下一次预测刷新 |
| 导入的语义 | 合并是**只增不减**：重复导入一份旧文件，会把用户已删除的分类/归属再加回来；且会覆盖本地分类名（M9）。这是 Q4 的既定语义，但值得在 UI 上给冲突提示 |
| 未处理的 UX 项 | L11 的弹窗按钮与 `imePadding`（其余无障碍/触控项已修） |

---

## 四、结论

- 审计共发现 **1 项 BLOCKER、3 项 HIGH、10 项 MEDIUM、14 项 LOW**。
- BLOCKER 与全部 HIGH 已修复；MEDIUM 修复 9/10；LOW 修复或缓解 12/14。
- 4 条核心不变式 **全部 PASS**（I2 在修复 B1 后才成立）。
- **最重要的一件事**：本项目在当前环境无法编译，因此本次交付**尚未经过编译器与单元测试验证**。
  合入前必须在具备 AGP 9.2.1 / Kotlin 2.4.0 / compileSdk 37 的环境中执行：

  ```
  ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin
  ```

  并建议优先补上 `ReminderRefreshWorker` 的回归测试。
