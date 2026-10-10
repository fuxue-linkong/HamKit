# QSO 记录与导出功能的设计方案

> 对应需求：`REQ-0001-qso-logging-export.md`（QSO 日志记录与 ADIF/XML 导出）
> 状态：设计定稿（已联网核对 ADIF 3.1.4 官方规范）
> 日期：2026-08-27

---

## 1. 目标

按需求文档 REQ-0001 落地：FT8 通联记录的**自动生成 + 手动增删改 + Room 持久化**，并支持**用户自选格式**导出 **ADIF (.adi)** 或 **XML**，可保存到系统文件或分享。第一阶段仅覆盖 FT8，结构为 CW 等通联模式预留扩展；**APRS 记录明确不计入 QSO 日志**（APRS 消息/站点保持独立的 `data/aprs/AprsMessageStore`，两套数据互不合并）。

## 2. 现状盘点（已核实）

| 事实 | 位置 |
|---|---|
| 领域模型 `Ft8QsoRecord` 已存在（callsign/grid/reportSent/reportReceived/band/freqHz/mode/qsoTime/isComplete） | `data/ft8/Ft8Models.kt:51` |
| `Ft8UiState.qsoRecords` 硬编码 `emptyList()`，UI 未消费 | `ui/viewmodel/Ft8ViewModel.kt:35` |
| Room 范本（Entity/DAO/Database/Store 同文件、`fallbackToDestructiveMigration`、单例） | `data/aprs/AprsMessageStore.kt` |
| FT8 解码消息文本由 `Ft8Protocol.unpackMessage` 产出（`TO FROM EXTRA` / `CQ PEER GRID`） | `data/ft8/Ft8Protocol.kt` |
| FT8 模块 UI 全为 Miuix 单主题 | `ui/screen/ft8/Ft8MainScreen.kt`、`Ft8SettingsScreen.kt` |
| 设置页双主题「查看提醒列表」入口可仿写 | `SettingsMiuix.kt:246`、`SettingsMaterial.kt:219` |
| FileProvider 已注册（cache-path/files-path），`SendLogDialog` 有写缓存+ACTION_SEND 分享范式 | `AndroidManifest.xml:66`、`SendLogDialog.kt:120` |
| Navigation3 路由 + `MainActivity` entry 注册 + `appViewModel<T>()` | `ui/navigation3/Routes.kt`、`MainActivity.kt` |
| Room 2.7.0 + KSP 已在依赖 | `gradle/libs.versions.toml`、`app/build.gradle.kts` |

## 3. 总体架构

```
Ft8MainScreen(新增「日志」) ─┐        Settings(Miuix+Material，新增「QSO 日志」)
                             ▼                    ▼
                       Route.QsoLogList ── MainActivity 注册
                             ▼
                   QsoLogScreen(列表/筛选/增删改/导出)
                             ▼
                 QsoLogViewModel(筛选态+CRUD+导出内容生成)
                             ▲
Ft8ViewModel(解码回调→自动记录写入)│ 同一 Room 库，Flow 自动同步
                             ▼
      data/qso/ QsoStore→QsoDao→QsoDatabase("qso_database")
      data/qso/ AdifExporter / XmlExporter(纯函数，JVM 可测)
```

两个 ViewModel 各自经 Room Flow 读同一库，无需共享实例。

## 4. 详细设计

### 4.1 领域模型扩展 — `data/ft8/Ft8Models.kt`

`Ft8QsoRecord` 追加 3 个带默认值的可空字段（向后兼容）：`operator: String?`（本台呼号）、`myGrid: String?`、`comment: String?`。

### 4.2 数据层 — 新增 `data/qso/QsoStore.kt`

**实体 `QsoRecordEntity`**（表 `qso_records`，`qso_time` 建索引，`qso_time DESC` 查询）：

| 列 | 类型 | 领域字段 | ADIF 映射 |
|---|---|---|---|
| id | Long PK 自增 | id | — |
| callsign | String | callsign | CALL |
| grid | String? | grid | GRIDSQUARE |
| report_sent | String(默认 "-99") | reportSent | RST_SENT |
| report_received | String(默认 "-99") | reportReceived | RST_RCVD |
| band | String(Ft8Band.name) | band | BAND |
| freq_hz | Long | freqHz | FREQ(MHz) |
| mode | String | mode | MODE |
| qso_time | Long(epoch) | qsoTime | QSO_DATE+TIME_ON |
| is_complete | Boolean | isComplete | QSO_COMPLETE |
| operator | String? | operator | OPERATOR / STATION_CALLSIGN |
| my_grid | String? | myGrid | MY_GRIDSQUARE |
| comment | String? | comment | COMMENT / COMMENT_INTL |

**`QsoDao`**：insert / update / delete / deleteById / `getAll(): Flow`(DESC) / `findRecentIncomplete(callsign,band,since)`（自动记录去重用）。

**`QsoDatabase`**：v1，`qso_database`，`fallbackToDestructiveMigration()`，单例（同 AprsDatabase）。

**`QsoStore`**：`records: Flow<List<Ft8QsoRecord>>`（实体↔模型映射，band 反查回退 BAND_40M）+ suspend 增删改查。筛选在 ViewModel 内存 `combine`。

### 4.3 导出器 — 新增 `data/qso/QsoExporters.kt`（纯 JVM + 枚举）

`enum QsoExportFormat { ADIF, XML }`、`export(records, format): String`、`suggestFilename(format): String`。

#### ADIF（.adi，3.1.4，已联网核对官方规范）

- Header **首字符必须非 `<`**（否则视为无 Header）：首行注释 `HamKit QSO log export (ADIF 3.1.4)` → `<adif_ver:5>3.1.4` → `<programid:6>HamKit` → `<EOH>`
- 记录：字段任意顺序、仅导非空（grid 为 null、RST 为 "-99"/空省略）、`<EOR>` 结尾；字段名小写（大小写不敏感）
- **全字段 ASCII**（String=ASCII 32–126）：`COMMENT` 消毒剥离非 ASCII；日期/时间均 ASCII
- 日期时间 **UTC**：`qso_date:8`=yyyyMMdd、`time_on:6`=HHmmss
- `freq`=MHz Number（7,074,000 → `7.074`，尾零裁剪）
- `band`=Ft8Band.displayName（如 `40m`，ADIF 枚举已确认）；`mode`=`FT8`
- `isComplete` → 仅导出 `<qso_complete:1>Y`（枚举 Y/N/NIL/?）
- 长度=字符数（ASCII 下字符数=字节数，无歧义）

#### XML（.xml，ADX 兼容，已联网核对官方规范）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<ADX>
  <HEADER><ADIF_VER>3.1.4</ADIF_VER><PROGRAMID>HamKit</PROGRAMID></HEADER>
  <RECORDS><RECORD>
    <CALL>BG7HIM</CALL><BAND>40m</BAND><FREQ>7.074</FREQ><MODE>FT8</MODE>
    <QSO_DATE>20260827</QSO_DATE><TIME_ON>141500</TIME_ON>
    <STATION_CALLSIGN>…</STATION_CALLSIGN><OPERATOR>…</OPERATOR><MY_GRIDSQUARE>…</MY_GRIDSQUARE>
    <RST_SENT>…</RST_SENT><RST_RCVD>…</RST_RCVD>
    <COMMENT_INTL>…中文…</COMMENT_INTL>
    <QSO_COMPLETE>Y</QSO_COMPLETE>
  </RECORD></RECORDS>
</ADX>
```

- 元素名**大写**；普通字段**无数据类型指示符**；无 `<EOH>`
- **日期同 ADI**：QSO_DATE=YYYYMMDD、TIME_ON=HHMMSS（非 ISO 8601，转换函数与 ADI 共用）
- 中文注释用 **`COMMENT_INTL`**（IntlString，UTF-8）完整承载；手写 XML 转义（`& < >`）
- 扩展名 `.xml`（内容为标准 ADX，满足需求字面"XML"，兼容支持 ADX 的软件）

### 4.4 ViewModel 层

#### `Ft8ViewModel`（自动记录）

在 `startDecoding` 的 `onDecoded` 回调追加 `recordQsoFromDecoded(result)`：

| 解码消息 | 动作 |
|---|---|
| `CQ [修饰] PEER [GRID]` | 不记录（仅呼叫） |
| `我 PEER 网格` | upsert 未完成：更新 grid |
| `我 PEER 报告(±dd/R±dd)` | upsert：更新 reportReceived |
| `我 PEER RRR` | upsert：保持未完成 |
| `我 PEER RR73/73` | upsert 并 isComplete=true |
| `PEER 我 报告`（麦克风拾到自家扬声器） | upsert：更新 reportSent |
| 含 `<H22:…>`/`<?>`/DE/QRZ、呼号未设置、解码失败 | 跳过 |

去重：同 callsign+band 且未完成且 qsoTime 近 30 分钟 → 更新，否则新建；新建时写 operator/myGrid 取当前 FT8 配置。`qsoRecords` 改由 store Flow 喂给（替换硬编码）。

#### 新增 `ui/viewmodel/QsoLogViewModel`（AndroidViewModel）

筛选态（band / callsign 前缀 / 时间范围 全部·今天·近7天·近30天）+ `combine` 过滤列表与计数 + add/update/delete + `buildExport(format, useFiltered)`。

### 4.5 UI 层

- **路由**：`Routes.kt` 增 `data object QsoLogList : Route`；`MainActivity` 注册 `entry<Route.QsoLogList>{ WithApplicationViewModelStoreOwner { QsoLogScreen(...) } }`
- **`ui/screen/ft8/QsoLogScreen.kt`**（Miuix，对齐 Ft8SettingsScreen）：TopAppBar（返回/「通联日志」/「添加」「导出」）→ 筛选卡（呼号输入+频段下拉+时间范围+清除）→ LazyColumn 记录卡（呼号大、band/mode/频率、本地时间、RST 收/发、完成徽标）→ 空态 + 计数
- **添加/编辑弹窗**（WindowDialog/ScaleDialog）：呼号、网格、RST 收/发、频段、频率 MHz、备注、完成开关、保存/删除（含确认）
- **导出弹窗**：用户自选 **ADIF (.adi) / XML (.xml)** + 范围（当前筛选/全部）→ 两动作：**保存到文件**（`CreateDocument`，ADI=`text/plain`、XML=`text/xml`，建议名 `HamKit_QSO_yyyyMMdd_HHmmss.adi|.xml`，写 UTF-8）；**分享**（写 cacheDir → FileProvider + ACTION_SEND，复用 SendLogDialog 范式，零新增 manifest 改动）
- **入口**：① `Ft8MainScreen` TopAppBar 增「日志」→ `onNavigate(Route.QsoLogList)`；② 设置页：`SettingsScreenActions` 增 `onOpenQsoLog`，`SettingsScreen.kt` 接 `navigator.push(Route.QsoLogList)`，Miuix 增 ArrowPreference、Material 增 SegmentedListItem（标题「QSO 日志」+ 说明副文案）
- **strings.xml**：新增约 20 个 `qso_log_*`/`qso_export_*`/`qso_settings_*` key，中文、`translatable="false"`

## 5. 测试计划

`app/src/test/java/com/example/hamkit/data/qso/`

- **AdifExporterTest**：首字符非 `<` 且含 `<adif_ver:5>3.1.4`/`<EOH>`；长度前缀=字符数（`<call:6>BG7HIM`）；UTC 日期时间（固定 epoch 跨时区验证）；MHz 格式；空字段省略；`<qso_complete:1>Y`；注释剥离非 ASCII
- **XmlExporterTest**：ADX 结构；元素大写；QSO_DATE=YYYYMMDD（非 ISO）；`COMMENT_INTL` 承载中文；`& < >` 转义

## 6. 实施步骤

1. Ft8Models 扩展 3 字段
2. `data/qso/` 数据层
3. 导出器 + 单测（先测后接线）
4. Ft8ViewModel 自动记录
5. 路由注册
6. QsoLogScreen
7. 双入口接线 + strings
8. 验证

## 7. 验证命令

- `gradlew.bat :app:compileDebugKotlin`
- `gradlew.bat :app:testDebugUnitTest --tests "com.example.hamkit.data.qso.*"`
- 冒烟：录一条→杀进程重启仍在；筛选；导出 .adi/.xml 检查内容

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 自动记录启发式可能漏/误记 | 手动增删改兜底；30 分钟去重窗口为常量可调；CQ 不记录 |
| Room 后续 schema 演进 | v1 + fallbackToDestructiveMigration（同现有策略），字段稳定后再引入 Migration |
| 双主题设置页需两处同步 | 照「查看提醒列表」既有双实现，改动机械 |

**已消除的风险**（联网核对 ADIF 3.1.4）：ADIF/ADX 日期格式（ADX 同 ADI 用 YYYYMMDD/HHMMSS）、非 ASCII 字段长度语义（ADI 仅 ASCII 故长度=字符数=字节数）、ADX 结构细节（大写/无指示符/无 EOH/头部字段名）。剩余实现期只需按本文档字段映射落地即可。

## 9. 明确不做（范围外）

ADIF 导入、LoTW/eQSL/QRZ 上传、CW 自动记录（结构预留）。**APRS 记录不计入 QSO 日志**：APRS 消息/站点归属其独立的 `data/aprs/AprsMessageStore`，QSO 日志仅收录业余无线电通联记录（FT8，后续可扩展 CW），二者存储与导出完全分离。云同步、自定义起止日期区间（先用四档预设）。

## 10. 工作量

约 3–4 人日：数据层 0.5 / 导出器+测试 1 / ViewModel 0.5 / UI 1.5 / 联调 0.5。**不新增任何依赖**。
