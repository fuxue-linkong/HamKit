# QSO 通联记录页面埋点清单

> 目的：用户反馈 QSO Log 页面"操作逻辑体验差"，本清单定义诊断用的行为埋点。
> 数据仅用于分析操作路径与停留时长，不涉及通联内容之外的用户隐私。

## 基础设施

- **追踪器**：`app/src/main/java/com/example/hamkit/ui/util/QsoLogEventTracker.kt`
  - 事件定义：密封类 `QsoLogEvent`（23 个事件节点，类型安全）
  - 输出：Logcat（tag `QsoLog`，`adb logcat -s QsoLog` 过滤）+ SharedPreferences JSON 持久化
  - 持久化文件：`qso_log_events`（键 `events_json`，最多保留 500 条，超出丢最旧）
  - 非阻塞：主线程仅内存操作；写盘经 `SharedPreferences.apply()` 异步 + 1 秒防抖，
    高频事件（TextField onChange）不触发同步 I/O
- **初始化**：`HamKitApplication.onCreate()` 调用 `QsoLogEventTracker.init(this)`
- **会话模型**：进入页面 `logScreenEnter()`（重置计时与计数器），离开 `endSession()`
  （立即落盘，防进程被杀丢数据）

## 事件清单

| # | 操作节点 | 事件名 | 埋点位置 | 记录内容 |
|---|---------|--------|---------|---------|
| 1 | 进入页面 | `screen_enter` | QsoLogScreen 首次加载（`DisposableEffect`，Miuix/Material 两版） | `user_id=default`、时间戳 |
| 2 | 打开筛选菜单 | `filter_opened` | Miuix `OverlayDropdownPreference.onExpandedChange`、Material `QsoDropdownFieldMaterial` 展开回调 | 时间戳 |
| 3 | 修改呼号筛选 | `filter_callsign_changed` | ViewModel `setFilterCallsign()` | `value`（当前值）、`change_count`（会话内修改次数） |
| 4 | 修改频段筛选 | `filter_band_changed` | ViewModel `setFilterBand()` | `band`（null=全部频段）、`change_count` |
| 5 | 修改时间筛选 | `filter_time_changed` | ViewModel `setTimeRange()` 与 `setCustomDateRange()` | `range`（枚举名，自定义范围时为 `CUSTOM`）、`change_count` |
| 5a | 切换呼号匹配方式 | `filter_match_mode_changed` | ViewModel `setCallsignMatchMode()` | `mode`（`PREFIX`/`EXACT`）、`change_count` |
| 6 | 清除所有筛选 | `filter_cleared` | ViewModel `clearFilters()`（记录发生在重置**之前**） | `previous_callsign`、`previous_band`、`previous_time_range` |
| 7 | 点击新增 | `record_add_started` | 顶栏 "+" 按钮 onClick | 时间戳 |
| 8 | 打开编辑对话框 | `record_edit_opened` | 编辑对话框组合时（`DisposableEffect`，仅一次） | `record_id`、`is_add`（id==0 为新增入口） |
| 9 | 修改记录字段 | `record_field_changed` | 编辑对话框各输入控件 onChange | `field`（`callsign`/`grid`/`report_sent`/`report_received`/`freq`/`band`/`is_complete`/`comment`）、`change_count`（按字段分别计数） |
| 10 | 点击保存 | `record_saved` | ViewModel `addRecord()` / `updateRecord()` 入口 | `operation`（`add`/`update`）、`record_id` |
| 10a | 保存被校验拦截 | `record_save_blocked` | 编辑对话框保存按钮：呼号/频率校验失败、对话框保持打开 | 时间戳 |
| 10b | 对话框内通过校验 | `record_saved_from_dialog` | 编辑对话框保存按钮：校验通过、即将调用 ViewModel | 时间戳 |
| 11 | 删除记录 | `record_delete_opened` + `record_deleted` | 编辑对话框删除按钮 onClick / ViewModel `deleteRecord()` 入口 | `record_id`（删除即生效，无二次确认；记录进 10 秒撤销缓冲） |
| 12 | 撤销删除 | `record_delete_undone` | ViewModel `undoDelete()`（Snackbar"撤销"按钮） | `record_id` |
| 13 | 打开导出对话框 | `export_opened` | 顶栏"导出"按钮 onClick | 时间戳 |
| 15 | 修改导出格式 | `export_format_changed` | 导出对话框格式 chip onClick | `format`（`ADIF`/`XML`） |
| 16 | 修改导出范围 | `export_scope_changed` | 导出对话框范围 chip onClick | `scope`（`filtered`/`all`）、`record_count` |
| 17 | 导出保存到文件 | `export_saved` | `writeSavedExport` 写流成功后（失败不记录） | `format`、`scope`、`record_count`、时间戳 |
| 18 | 导出分享 | `export_shared` | 导出对话框分享按钮 onClick（`shareExport` 调用前） | `format`、`scope`、`record_count`、时间戳 |
| 19 | 页面停留时长 | `screen_duration` | 离开页面（`onDispose` → `endSession()`） | `duration_seconds`（总停留秒数）、`action_count`（会话内操作计数，不含 enter/duration 自身） |

> 删除流程说明：编辑对话框内点"删除"直接生效（无二次确认对话框），记录立即从数据库删除并
> 进入 `DeletedRecordBuffer` 内存缓冲 10 秒，期间 Snackbar 提供"撤销"；撤销把记录重新写回库，
> 超时后缓冲条目过期（记录保持已删除状态）。`record_delete_opened` 在删除按钮 onClick 记录，
> `record_delete_cancelled` 事件已随确认对话框一并移除。

## 日志格式

结构化 key-value，一行一事件（tag `QsoLog`，级别 INFO）：

```
[QsoLog] event=screen_enter user_id=default timestamp=1698765432000
[QsoLog] event=filter_callsign_changed value=BG7S change_count=1 timestamp=1698765435000
[QsoLog] event=record_add_started timestamp=1698765445000
[QsoLog] event=screen_duration duration_seconds=120 action_count=15 timestamp=1698765552000
```

值为 null 的参数（如"全部频段"）不输出该键，保持行简洁。

持久化 JSON（`qso_log_events` → `events_json`）：

```json
[
  {"seq": 1, "event": "screen_enter", "timestamp": 1698765432000, "user_id": "default"},
  {"seq": 2, "event": "filter_callsign_changed", "timestamp": 1698765435000, "value": "BG7S", "change_count": "1"}
]
```

`seq` 为自增序号（跨进程重启续接），用于排序去重。

## 集成位置索引

| 文件 | 集成内容 |
|------|---------|
| `ui/util/QsoLogEventTracker.kt` | 事件密封类 + 追踪器（Logcat / 持久化 / 会话 / 计数器） |
| `ui/viewmodel/QsoLogViewModel.kt` | 筛选方法与增删改方法的埋点调用（业务逻辑未改动） |
| `ui/screen/ft8/QsoLogScreen.kt` | Miuix 版：会话生命周期、新增/导出/编辑/删除入口、字段修改、格式/范围切换、分享 |
| `ui/screen/ft8/QsoLogScreenMaterial.kt` | Material 版：与 Miuix 版等价的全部埋点 |
| `HamKitApplication.kt` | `QsoLogEventTracker.init(this)` |
| `app/src/test/.../QsoLogEventTrackerTest.kt` | 追踪器单测（事件展开、Logcat 格式、持久化、会话统计、降级路径） |
| `app/src/test/.../QsoLogViewModelTrackingTest.kt` | ViewModel 集成单测（埋点不干扰业务状态与导出） |

## 行为细节

- **修改次数**：`change_count` 在进入页面时清零（`beginSession`），同一事件名自增。
- **操作计数**：`action_count` 统计会话内所有非 `screen_enter`/`screen_duration` 事件。
- **防抖落盘**：距上次持久化超过 1 秒才写盘；`endSession` 时强制 flush。
- **未初始化降级**：`init` 未调用时仅输出 Logcat，不持久化（单测兜底路径）。
- **UI 双实现**：Miuix 与 Material 两版 Screen 各自集成等价埋点，共用同一 ViewModel
  （application 级），切风格不重复触发 `screen_enter` 之外的筛选/增删改事件。
- **频率回填**：新增记录时点频段 chip 会同时记录 `record_field_changed field=band` 与
  `field=freq`（后者由频率回填触发 onChange）。

## 观察方式

```bash
# Logcat 实时观察
adb logcat -s QsoLog

# 查询持久化事件（应用数据目录，需 root/debug build）
adb shell "run-as com.example.hamkit cat shared_prefs/qso_log_events.xml"
```
