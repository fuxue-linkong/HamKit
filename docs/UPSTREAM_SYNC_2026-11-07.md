# 上游同步（2026-11-07）：AI 模型配置改造（提供商下多模型 / 搜索切换 / 设置页重做）

> 上游仓库：`fuxue-linkong/HamKit`，`miuix` @ `63d87b5`
> 本地分支：`ios`
> 本轮差异：`830ab69`、`63d87b5` 两个提交；由于 `ios` 的 Swift 侧此前没有落地
> `24f6269`（多模型配置）与 `7650542`（设置页模型区重做），本轮一并补齐。
> 落点：`iosApp/HamKit/`（纯 Swift 侧）；`:shared` 内核只接收 `AiConfig`，无需改动。

## 0. 上游内容

| 提交 | 上游改动 | iOS 落点 |
|---|---|---|
| `24f6269` | 多模型配置与切换：`AiModelProfile`、`AiProfileStore`、每 profile 独立 Key 槽位、旧配置迁移 | `AiProfile` / `AiModelEntry`、`AiSettingsStore` 多 profile 持久化、Keychain 分槽 |
| `7650542` | 设置页模型区重做：摘要卡、预设 / 自定义 Tab、模型目录、操作栏 | `AiSettingsView` 重写 |
| `830ab69` | 输入框单容器布局；模型切换改为搜索 / 分组下拉面板 | `AiAssistantView` 输入栏 + `AiModelSwitchView` |
| `63d87b5` | 模型保存改造：提供商下多模型（ID + 显示名）、列表编辑、统一保存 | profile 数据模型、模型列表编辑、统一保存 / 取消 |

## 1. 数据层（`iosApp/HamKit/AiSettingsStore.swift`）

| 类型 / 能力 | 说明 |
|---|---|
| `AiModelEntry` | `id` + `modelId` + 可选 `displayName`；`label` 优先显示名称 |
| `AiProfile` | 提供商：`preset` / `protocolName` / `baseUrl` / `models` / `selectedModelId` / `temperature` / `maxOutputTokens`；`normalized()` 与 Android 同规则（去空白、去尾部 `/`、过滤空模型、选中项回退、参数夹取） |
| `AiGlobalSettings` | 系统提示词、工具开关、副作用确认、工具往返上限、历史开关；不随模型切换 |
| `AiSettingsStore.profiles` | UserDefaults（`hamkit.ai.profiles`）存 JSON；`activeProfileId` 单独存 |
| 旧配置迁移 | 首次启动把 `hamkit.ai.config`（旧单模型 JSON）迁移成 `default` profile；全局项一并迁移，随后立即落盘 |
| Key 槽位 | `default` 继续用旧账号 `api-key`，其余用 `api-key.<profileId>`；都在 `click.hamkit.ios.ai` 服务下，`WhenUnlockedThisDeviceOnly` |
| 模型目录 | `AiModelCatalog` 用 `URLSession` 请求 `/models`；OpenAI 兼容读 `data[].id`、Anthropic 读 `data[].id`、Gemini 读 `models[].name`（去 `models/` 前缀），排序去重 |

## 2. 界面

### AI 助手（`AiViews.swift`）

- 输入栏改为**单容器**：文本输入在上，模型切换与发送按钮在下一行；聚焦时主色描边；
- 模型切换按钮显示当前 `modelId`，点开 **按提供商分组、可搜索** 的面板
  （匹配提供商名 / 模型 ID / 显示名），当前项打勾；
- 生成中面板仍可打开，但条目禁用并提示「生成中不能切换模型」；
- 面板底部「管理模型…」跳 AI 设置。

### AI 设置（`AiSettingsView.swift`）

- 顶部「当前模型」摘要卡：提供商名、当前标记、协议 · 模型、切换入口；
- 模型区：
  - 「预设服务商 / 自定义 API」分段 Tab；预设为横向 chip，自定义显示协议与 Base URL；
  - 提供商显示名称；
  - 「模型目录」拉取候选模型，点 chip 直接加入 / 选中；
  - 模型列表逐条编辑「模型 ID + 显示名称」，可删除（至少保留一条）、点行切换当前项；
  - 「取消 / 保存模型」统一保存（名称、协议、URL、模型列表、选中项、温度、最大输出）；
  - 「添加模型提供商」新建独立 profile（沿用连接参数与当前模型，Key 槽位为空）；
- API Key 区按当前提供商显示掩码、保存 / 清除；
- 生成参数、系统提示词、工具开关、历史为全局项，保持即时保存。

## 3. 与上游的差异

| 项 | 说明 |
|---|---|
| 连接测试 | iOS 仍未实现「测试连接」（需要非流式探测；与 `docs/UPSTREAM_SYNC_2026-10-31.md` 的缺口表一致） |
| 附件 | iOS 助手暂不支持图片 / 音频附件，输入栏因此没有「+」按钮 |
| 模型排序 | 上游 `≡` 只是视觉占位，没有实现拖拽；iOS 同样未做重排 |
| 明文 HTTP | iOS 走 ATS，设置页不提供「允许明文 HTTP」开关 |
| profile 存储位置 | 属于平台配置（Android SharedPreferences ↔ iOS UserDefaults/Keychain），因此放在 Swift；`:shared` 仍只接收 `AiConfig`，不需要知道 profile |

## 4. 验证

| 检查 | 命令 | 结果 |
|---|---|---|
| 内核单测 | `./gradlew :shared:jvmTest` | ✅ |
| iOS 模拟器编译 | `xcodebuild ... -sdk iphonesimulator ... build` | ✅ `BUILD SUCCEEDED` |
| 设置页 / 助手 / 模型面板截图 | `xcrun simctl launch ... -smokeAISettings` / `-smokeAIModels` | ✅ |
| 旧配置迁移 | 预置 `hamkit.ai.config`（OpenAI / gpt-4.1）后启动 | ✅ 设置页显示 `OpenAI` chip 与 `gpt-4.1` |
| 多提供商 / 多模型 | 预置两个 profile（含显示名）后启动 | ✅ 面板按提供商分组，显示名与当前勾选正确 |
| CI 冒烟 | `.github/workflows/ios.yml` 新增 `ios-ai-settings.png` / `ios-ai-models.png` | ✅ 每次 iOS 构建自动截图 |

新增冒烟启动参数（正常启动不受影响）：

- `-smokeAI`：直接进入 AI 助手；
- `-smokeAISettings`：再进一层 AI 设置；
- `-smokeAISettingsSheet`：从助手以 sheet 打开 AI 设置（正常入口的形态）；
- `-smokeAIModels`：在助手页自动弹出模型切换面板。
