# 小米超级岛（Xiaomi HyperIsland）通知接入研究与实现记录

> **⚠️ 方案已弃用（2026-11-22）**：本方案的 `miui.focus.param` extras 代码已从工程中全部移除，
> 通知能力改走 Android 16 原生 Live Updates（`Notification.ProgressStyle`），见
> `docs/ANDROID_LIVE_UPDATES.md`。本文件保留为研究记录——超级岛需要企业认证、场景预审、
> 正式提报、白名单联调与灰度上线，流程成本高且仅覆盖小米设备；原生 API 无需审核、
> Android 16+ 全设备通用。未来若要重启超级岛接入，本文档第二节的 extras 协议仍然有效。

> 研究时间：2026-11-22 · 分支：miuix · 数据来源：小米澎湃OS开发者平台官方文档
> - [业务介绍](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140)
> - [接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)
> - [开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
> - [版本信息](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2141)
> - [方案提报说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2144)

## 一、技术结论

### 1.1 本质

小米超级岛 = 焦点通知（Focus Notification）的 OS3 升级形态。**接入不需要任何 SDK**，
本质是往 Android 原生通知的 `extras` 附加小米扩展参数：

| extras key | 类型 | 作用 |
|---|---|---|
| `miui.focus.param` | String | `{"param_v2": {...}}` JSON，承载全部岛/焦点通知字段 |
| `miui.focus.pics` | Bundle | 模板引用的图片，`Icon` 形式，key 形如 `miui.focus.pic_xxx` |
| `miui.focus.actions` | Bundle | 模板引用的动作，`Notification.Action` 形式，key 形如 `miui.focus.action_xxx` |

两种实现方式（官方《开发指南》）：

1. **客户端实现**（本项目采用）：应用发送原生通知时附加扩展参数。要求"客户端保持活跃"——
   过境提醒由 AlarmManager 精确闹钟拉起 `ReminderReceiver` 时发送，天然满足。
2. **MIPUSH 服务实现**：通过 MiPush 云推下发（仅 regId），需开通推送服务 + 服务端。
   会把过境时刻上传云端，违背本项目"本地优先"设计，不采用。

### 1.2 版本形态

| 系统版本 | 形态 | 探测值 |
|---|---|---|
| 非 HyperOS | 普通通知（extras 被忽略，零副作用） | 0 |
| OS1 | 焦点通知 | 1 |
| OS2 | 焦点通知：状态栏常驻 + 通知中心 + 锁屏 + 息屏 + 小折叠外屏 | 2 |
| OS3 | 小米超级岛：岛摘要态 + 岛展开态 + 焦点通知（状态栏外全部位置） | 3 |

探测接口（三件套，全部容错实现）：

```kotlin
// 协议版本（本项目主探测点）
Settings.System.getInt(cr, "notification_focus_protocol", 0)

// 焦点通知权限（耗时跨进程调用，仅诊断用）
cr.call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, extras)

// 岛系统特性（反射 SystemProperties，联调比对用）
SystemProperties.getBoolean("persist.sys.feature.island", false)
```

### 1.3 param_v2 数据结构（过境提醒实际使用的字段）

```
{
  "param_v2": {
    "business": "satellite_pass",     // 运营场景标识（提报归档用）
    "islandFirstFloat": true,         // 首次出现即展开态
    "updatable": false,               // 一次性提醒，非持续更新
    "ticker": "ISS 即将过境（14:32）", // OS2 状态栏文案
    "aodTitle": "ISS 14:32 过境",     // 息屏 AOD 文案
    "param_island": {                 // 岛摘要态
      "islandProperty": 1,            // 1=信息展示为主
      "bigIslandArea": {              // 大岛：图标 + 卫星名 + AOS + 仰角/方位
        "imageTextInfoLeft": { "type":1, "picInfo":{...}, "textInfo":{...} }
      },
      "smallIslandArea": { "picInfo": {...} }   // 小岛：图标
    },
    "baseInfo": {                     // 焦点通知卡（OS2 展开态/OS3 通知中心）
      "title": "ISS 即将过境", "content": "AOS 14:32 | 最大仰角 65°", "type": 2
    },
    "hintInfo": { "type":1, "title":"打开应用", "actionInfo":{...} }
  }
}
```

模板字段（`imageTextInfoLeft` 等）来自官方示例已验证结构；完整模板清单见
《小米超级岛模板库》PDF（接入流程文档内链接，联调阶段下载核对）。

### 1.4 官方行为约定（未显式设置时的兜底）

- 岛展开态默认展开 5s 后收起
- 岛默认 1 小时消失；通知卡片默认 12 小时消失（本通知 `autoCancel`，点击后移除）
- 新通知 > 老通知 的展示优先级
- 焦点通知权限被关闭时，默认退化为普通通知正常展示（`filterWhenNoPermission=false`）

## 二、HamKit 落地实现

### 2.1 代码结构

```
data/reminder/
  IslandSupport.kt                 # 能力探测（协议版本/权限/系统特性，全容错）
  IslandParams.kt                  # param_v2 JSON + pics + actions Bundle 构建（纯函数）
  ReminderNotificationHelper.kt    # showPassReminder() 附加岛 extras（注入点）
  ReminderReceiver.kt              # 传递 settings.islandEnabled
  ReminderSettings.kt              # islandEnabled 开关（默认 true）
  ReminderStore.kt                 # island_enabled 持久化
ui/screen/settings/
  SettingsMiuix.kt / SettingsMaterial.kt   # "小米超级岛通知" 开关
AndroidManifest.xml               # com.xiaomi.xms.APP_ID / BUILD_TYPE_DEBUG meta-data
app/build.gradle.kts              # XMS_APP_ID 占位符（env/local.properties）
res/values/strings.xml            # island_* 文案
```

### 2.2 行为矩阵

| 设备 | 开关 | 结果 |
|---|---|---|
| 非 HyperOS | 任意 | 不附加 extras，普通通知（与既有版本零差异） |
| HyperOS 2/3，未通过鉴权/白名单 | 开 | 附加 extras；系统鉴权不过 → 普通通知 |
| HyperOS 2/3，白名单设备联调 | 开 | 岛/焦点通知形态展示 |
| HyperOS 2/3 | 关 | 普通 |
| 附加参数构建异常 | 开 | try-catch 退回普通通知 |

### 2.3 岛内容映射（ReminderItem → 岛字段）

| ReminderItem 字段 | 岛字段 |
|---|---|
| name | 大岛 frontTitle / baseInfo.title / ticker / aodTitle |
| aosTimeMillis（本地 HH:mm） | 大岛 title「入境 HH:mm」/ baseInfo / ticker / aod |
| maxElevation、aosAzimuth | 大岛 content / baseInfo.content |
| SSTV 卫星判定 | 额外「接收 SSTV」Action（复用 SstvLaunchIntent 直达解码） |
| 图标 | 暂用应用图标（`miui.focus.pic_main`），后续可换卫星专属图 |

## 三、上线流程（用户侧操作清单）

代码已完成的部分无需小米侧配合即可合入；以下流程决定岛上真机可见性：

1. **开发者认证**：[dev.mi.com](https://dev.mi.com/xiaomihyperos/console) 注册/认证（企业认证 1-3 工作日）
2. **创建应用**：包名 `com.example.hamkit`，上传 APK，完善资料（1-3 工作日审核）
3. **开通服务**：应用详情 → 开发 → 开放服务 → 小米超级岛 → 启用 → 配置签名指纹证书
   （同时配 Release 与 Debug 两条指纹）→ 获得对应 App Id
4. **场景预审**：场景列表选「其他」（或"日程/提醒"类）→ 填写场景描述（可用本文第五节文案）
5. **正式方案提报**：预审通过后「前往填写」，提交节点全集 + UI 示意（可用本文第五节）
6. **联调**：管理中心 → 设备白名单管理 → 按 OAID 添加测试机（≤10 台、30 天有效）；
   `local.properties` 加 `xms.app.id=<App Id>`；debug 签名 APK 安装到白名单设备测试
7. **上线验证**：提交正式 APK + 场景验证方法 → 通过后灰度 7~15 天全量
8. **OS2 焦点通知**（可选）：邮件 mipush-permission@xiaomi.com 申请开发测试包
   （主题：【申请小米OS2焦点通知开发测试包】-yymmdd-「HamKit」，附场景审核截图 + 测试机版本截图）

> 注意：未经审核的新场景上线会导致超级岛服务停用，严重时应用下架——新场景必须先提报。

### 准入自查（过境提醒场景对照《业务介绍》准入原则）

| 原则 | 符合性 |
|---|---|
| 基础标准 1：明确有限生命周期（≤12h） | ✅ 单次过境约 5-15 分钟，AOS 前提醒 → 过境结束自然结束 |
| 基础标准 2：非营销属性 | ✅ 纯工具提醒 |
| 核心标准 1：用户主动发起、履约预期 | ✅ 用户在卫星管理页手动开启过境提醒 |
| 核心标准 2：高度关注、需实时交互 | ✅ 过境窗口短，用户需实时查看方位/仰角并打开接收 |
| 排除项：长期常驻/营销/低价值高频 | ✅ 均不涉及 |

## 四、本机验证步骤（待用户提供小米设备后）

1. 白名单设备安装 debug APK（`xms.app.id` 已配置）
2. 应用内开启过境提醒 + 超级岛开关，为 ISS 添加提醒
3. `adb shell settings get system notification_focus_protocol` 确认返回 2 或 3
4. 等 AOS 前 N 分钟触发通知，观察：岛摘要态（小岛）→ 点开大岛 → 点击进应用/SSTV 直达
5. 锁屏、息屏（AOD）、下拉通知栏分别确认形态
6. 关闭超级岛开关重测 → 应退回普通通知

## 五、场景提报文案草稿（可直接粘贴到小米开平表单）

**场景名称**：卫星过境提醒（业余无线电通信辅助）

**场景简介**：
HamKit 是面向业余无线电爱好者的卫星跟踪工具。用户为特定卫星（如国际空间站 ISS）
开启「过境提醒」后，应用会在该卫星入境（AOS）前 N 分钟（用户设置 5/10/15/30）
发送提醒，帮助用户在短暂过境窗口（通常 5~15 分钟）内及时架设设备完成通联。
过境结束后通知随用户点击或过境结束自动消失，服务生命周期明确且有限。

**场景节点全集**：
| 节点 | 触发时机 | 展示内容 | 结束方式 |
|---|---|---|---|
| 过境提醒 | AOS 前 5/10/15/30 分钟（AlarmManager 精确触发，用户设定） | 卫星名、入境时刻、最大仰角、入境方位、（SSTV 卫星）直达接收按钮 | 用户点击/过境结束移除 |

（二期规划：过境进行中持续更新剩余时间，`updatable=true`，LOS 时移除。）

**各形态展示内容**：
- 大岛：卫星图标 + 「<卫星名> 入境 HH:mm」+ 最大仰角/入境方位
- 小岛：卫星图标
- 展开态/焦点通知卡：标题「<卫星名> 即将过境」，正文「AOS HH:mm | 最大仰角 N°」，动作「打开应用」（SSTV 卫星追加「接收 SSTV」直达解码器）
- OS2 状态栏：「<卫星名> 即将过境（HH:mm）」
- 息屏 AOD：「<卫星名> HH:mm 过境」

**展示时长**：岛摘要态至多 1 小时（通常用户在 AOS 前数分钟内处理）；通知卡片至多 12 小时，
实际在用户点击或过境结束后即移除。单次服务全周期 < 20 分钟，远低于 12 小时上限。

**用户价值**：卫星过境窗口极短且时刻由轨道计算精确决定，错过即损失一次通联机会
（对ISS/SSTV 图传接收等场景尤为明显）。提醒由用户主动设置，符合履约预期，
属于用户高度关注、错过有实际后果的信息提醒。

## 六、遗留事项

- [x] 代码实现与编译验证（`./gradlew :app:compileDebugKotlin` 通过，2026-11-22）
- [ ] 模板库 PDF 字段核对（联调阶段下载，确认 `imageTextInfoLeft` 在最新模板库中的模板 ID）
- [ ] App Id 获取并写入 `local.properties`（`xms.app.id=...`）
- [ ] 白名单设备 OAID 收集（小米设置或 OAID SDK 均可获取）
- [ ] 真机联调验证（白名单设备走第四节步骤）
- [ ] 二期：过境进行中持续更新岛通知（需 MainViewModel 追踪任务桥接，LOS cancel）
- [ ] 提报 UI 示意图（可截取现有卫星详情页/雷达图再加工，或按官方 Figma 设计模板套用）
