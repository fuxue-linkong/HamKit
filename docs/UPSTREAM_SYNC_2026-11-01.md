# 上游同步（2026-11-01）：AI 工具路由 / 社区工具 / 卫星标签，与 iOS 落地

> 上游仓库：`fuxue-linkong/HamKit`（`Dual-zone_network_positioning`）@ `upstream/miuix` = `f449de6`
> 本地分支：`feat/ios-kmp`，上次同步点 `2aba3d3`
> 本轮差异：**5 个提交 / 14 个文件 / +846 −52**，全部落在 AI 助手模块
> 结论：内核（`shared`）与 iOS 侧全部落地；Android 侧经合并直接生效。

## 0. 同步动作

```bash
# 代理把 github 走 502，改为直连
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
git fetch upstream miuix
# 需先移走损坏的 refs/remotes/upstream/HEAD（指向不存在的 upstream/main）
git merge upstream/miuix      # 无冲突
```

| 提交 | 上游内容 | 本轮落点 |
|---|---|---|
| `bd7b46b` | `feat(ai)`: 卫星检索支持 tag 过滤与 `max` 上限（默认 20 / 最大 100） | 内核新增 `AiSatelliteTags.kt`，`search_satellites` 重写 |
| `6176585` `6e396ce` | tag 匹配/解析改为纯 Kotlin 参数（去掉 `org.json` 依赖以便单测） | 内核 `parseAiSatelliteTags` / `filterAiSatellites` 纯函数 + 单测 |
| `20bc783` | `feat(ai)`: 新增 FT8 / APRS / QSO 三个工具；意图路由、只读并行、结果截断；提示词补充 | 内核 `AiToolbox` 三个新工具 + `AiToolRouter` + `AiChatEngine` 并行/截断 + `AiPrompts` |
| `f449de6` | `test(ai)`: 注册表测试补三个新工具 | 内核工具层单测覆盖（`AiToolboxCommunityTest`） |

## 1. 内核对齐（`shared/src/commonMain/kotlin/com/example/hamkit/`）

| 文件 | 改动 |
|---|---|
| `ai/AiSatelliteTags.kt`（新） | `AiSatelliteTag`（14 个 tag，AND 语义）、`parseAiSatelliteTags`、`filterAiSatellites`；`repeater` 标签**惰性**取转发器——iOS 全量目录 1.6 万颗，不能每颗都跨语言调用 |
| `data/satellite/SunPosition.kt`（新） | NOAA 简化太阳位置，`isDaylightAt` 供 `daylight` 标签使用。Android 侧原来是 `CelestialComputer`（依赖 `java.time`），iOS 没有等效实现；下沉内核后两端同一份公式 |
| `ai/AiToolbox.kt` | `search_satellites` 改为 `query` + `tags` + `max`（兼容旧 `mode`/`only_above`/`limit`），输出 `total/returned/truncated/tags`；新增 `get_ft8_activity`、`get_aprs_stations`、`search_qso_log`；`AiAppFacade` 扩 `ft8Snapshot/aprsSnapshot/qsoRecords`；APRS 距离/方位改由内核 haversine 计算（输出仍不含坐标，保持脱敏） |
| `ai/AiToolRouter.kt`（新） | 11 条意图规则 → 工具集合；未命中返回空集（下发全部工具）。**不用正则**：`\b` 对 CJK 的行为在 JVM / Native 上不一致，改为「关键词 + ASCII 词边界」可单测实现 |
| `ai/AiChat.kt` | ① 按最后一条用户消息路由、裁剪下发工具；② 工具结果超 6000 字截断后再回传模型（UI 摘要与卡片不截断）；③ 同轮**全部只读**工具并行发起、按 calls 顺序回填 |
| `ai/AiModel.kt` | `MAX_TOOL_RESULT_CHARS` + `truncateToolResult` |
| `ai/AiPrompts.kt` | 补 tag 用法、三个社区工具、截断说明、工具调用节流要求 |

## 2. iOS 落地（`iosApp/HamKit/`）

| 文件 | 改动 |
|---|---|
| `Ft8LogStore.swift`（新） | FT8 解码从 `Ft8MainView` 的 `@State` 提到会话级 store（上限 200 条、内存态），AI 助手才能读到「刚才解出什么」 |
| `Ft8Views.swift` | 解码列表改读 `Ft8LogStore`；接收开始/结束/取消维护 `isDecoding` |
| `AiFacade.swift` | 实现 `ft8Snapshot()`（解码 + 仅 FT8 模式的 QSO）、`aprsSnapshot()`（台站坐标交由内核换算距离，模型只看距离/方位）、`qsoRecords()`（全模式日志） |
| 设置页 | 工具开关读 `AiBridge.toolCatalogJson`，新增 3 个工具自动出现，无需改 UI |

提示词未在 iOS 侧另存一份：`systemPrompt` 为空即用内核默认值，本轮改动自动生效。

## 3. 验证

| 检查 | 命令 | 结果 |
|---|---|---|
| 内核编译 | `./gradlew :shared:compileKotlinJvm` | ✅ |
| 内核单测 | `./gradlew :shared:jvmTest` | ✅ **62 个用例**（原 30 + 新增 32：标签 11 / 路由 6 / 社区工具 10 / 引擎 5） |
| Android 编译 | `./gradlew :app:compileDebugKotlin` | ✅ |
| iOS 编译 | 需 macOS + Xcode | ⚠️ **未验证**（Windows 无 Swift 工具链）。Swift 侧按现有工程模式编写，改动集中在 `AiFacade.swift` 与 `Ft8Views.swift` |

## 4. 与上游的已知差异 / 后续

| 项 | 说明 |
|---|---|
| 工具集仍是子集 | iOS 没有 `get_weather` / `cw_encode` / `start_sstv_receive`（依赖天气会话与音频生命周期），路由规则里保留这些名字，但不会下发（spec 不存在时自然过滤掉） |
| `daylight` 标签 | 内核判定「AOS 时刻地面站太阳仰角 > -0.833°」，与 Android `CelestialComputer` 同义；未带定位时工具直接报错，而不是静默返回空 |
| APRS 语义 | 只读当前会话已接收台站，明确 `scope=session`；提示词要求模型不得说成「你附近全部台站」 |
| FT8 会话范围 | iOS 的 `get_ft8_activity` 解码只保留本次进程内的记录（与 Android 内存语义一致），历史通联走 `search_qso_log` |
