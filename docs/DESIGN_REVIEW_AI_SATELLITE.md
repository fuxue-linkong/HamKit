# AI 模块与卫星模块 UI 设计审查报告

> 审查日期：2026-11-13
> 审查范围：AI 模块（AiAssistantScreen / AiAssistantMaterial、AiSettingsScreen / AiSettingsMaterial、component/SatelliteRadar）、卫星模块（SatelliteManagement、SatelliteDetail、SatelliteRadar、SatelliteMap、SatelliteFilter、SatelliteCategory、RadarView、SatelliteCompass），含 Miuix / Material 双皮肤实现。
> 审查维度：布局与排版 / 视觉风格 / 交互体验 / 信息层级与一致性 / 与整体风格的协调性。
> 严重程度：🔴 高（影响可用性或明显破坏视觉）· 🟡 中（影响体验与观感）· 🟢 低（细节打磨）

---

## 一、总体评价

两个模块功能完整、信息密度高，代码中已体现不少好的设计实践（48dp 触控区、无障碍语义、折叠工具卡、统一 Chip 语言等）。但整体上仍处于"功能优先"的状态，主要短板集中在：

1. **硬编码颜色与主题脱节**——大量 `Color(0xFF4CAF50)`、`Color(0xFFF44336)` 写死在卫星页面，暗色主题下对比度无保证；
2. **双皮肤实现大量复制粘贴**，已经出现明显的行为与视觉分叉；
3. **字号/间距体系不统一**，9–30sp 任意取值，缺乏 type scale；
4. **信息层级靠字号撑**，缺少分组标题、留白节奏等结构性手段；
5. **危险操作与状态反馈偏弱**——无二次确认、无 Toast/Snackbar 语言。

---

## 二、问题清单

### A. 视觉风格（配色 / 字体 / 图标）

| # | 位置 | 问题 | 严重度 | 建议 |
|---|------|------|--------|------|
| A1 | 卫星详情页 Miuix `SatelliteDetailScreen.kt:199,346,456-457`；Material 版 `SatelliteDetailMaterial.kt:169`；雷达页 `SatelliteRadarScreen.kt:142`、`SatelliteRadarMaterial.kt:121` | 蚀状态/日照状态/转发器状态使用硬编码 `Color(0xFF4CAF50)`（绿）、`Color(0xFFF44336)`（红）、`Color(0xFF42A5F5)`（蓝），未走主题色。项目已有 `SafeColors`（successIcon/errorIcon），且其中 successContainer 是浅色专用（`0xFFDFFAE4`），暗色模式下 SatelliteManagement 用 `SafeColors.errorContainer` 做芯片背景会出现浅粉底+深字，与整体暗色调冲突 | 🔴 | 统一收敛到 `SafeColors` 并补充 dark 变体（参照 `errorContainerDark` 的做法）；所有状态色从主题读取 |
| A2 | `RadarView.kt:50-53` | 雷达图绘制色 `AIM_COLOR=0xFFBA1A1A`、`RADAR_COLOR=0xFF546E7A`、`PRIMARY_COLOR=0xFF4CAF50` 全部硬编码，`sweepColor` 虽然可传参但卫星页未传入主题色，AI 卡片里的 `SatelliteRadar`（component 版）则正确地由调用方传主题色——同一功能两套颜色语言 | 🟡 | RadarView 增加 `colors` 参数或全部由调用方传入 primary/outline；两个雷达组件合并为一套视觉规格 |
| A3 | AI 聊天页两版 `ReasoningCard`（`AiAssistantScreen.kt:1137`、`AiAssistantMaterial.kt:1105`） | 「💡 思考过程」用 emoji 当图标，与全 App 的 Material 图标体系（Rounded 系）不一致；且 AI 空态/头像用 AutoAwesome，思考卡却是 emoji，视觉语言混杂 | 🟡 | 换成 `Icons.Rounded.Psychology` 或 `Lightbulb`，统一图标族 |
| A4 | AI 聊天页 `StreamingDotsMiuix`（`AiAssistantScreen.kt:689-693`） | 用文本 "● ● ●"（8sp）模拟打字点动画，字形渲染在不同字体下基线不齐、大小不可控；Material 版干脆没有流式点动画（只有光标），两皮肤体验不对齐 | 🟡 | 用 3 个 Circle 绘制或统一为光标+点动画；两皮肤对齐同一动效规格 |
| A5 | `AttachmentChipMaterial`（`AiAssistantMaterial.kt:1169-1176`） | 移除附件用文本 "×" 且命中区域只有 `padding(2.dp)`，实际可点区域约 12dp，远低于 48dp 最小触控标准；Miuix 版用的是 22dp 圆形 IconButton + Close 图标，规格又不一致 | 🔴 | Material 版对齐 Miuix 的做法（图标 + ≥32dp 命中区），或统一抽成共享组件 |
| A6 | 卫星管理页列表项（`SatelliteManagementScreen.kt:729-734`） | 最大仰角直接以 "45°" 裸文本放在名称右侧，与分类/提醒/展开三个 IconButton 挤在一行，看不出它是"数据"还是"操作"，层级混乱 | 🟡 | 给仰角加时间徽章同款的处理（label+value 或 chip），或移到卡片第二行信息区 |
| A7 | 卫星管理页 `ManagementStat`（`SatelliteManagementScreen.kt:537-551`） | 统计数字 22sp Bold + label 12sp，与卫星详情页 `LiveBigNumberMiuix`（30sp）和雷达页 `RadarStatMiuix`（15sp）三处"大数字"规格互不相同，同类信息三种视觉重量 | 🟡 | 建立模块级数字显示规格（如 hero 30sp / primary 22sp / inline 15sp），三页统一 |
| A8 | AI 设置页 `ChipMiuix`（`AiSettingsScreen.kt:372-386`） | 服务商/协议选择的 chip 无选中态边框、无涟漪反馈，选中只靠底色变化；未选态用 `surfaceVariant` 纯色，与 AI 聊天页附件 chip（同形状但语义不同）视觉接近，容易误读为标签而非按钮 | 🟢 | 选中态加 1dp primary 边框 + ripple；与附件 chip 在形状/密度上做区分 |
| A9 | AI 设置页 `McpCardMiuix`（`AiSettingsScreen.kt:966-999`） | MCP 地址/Token 用 10-11sp 小字展示长字符串，无换行控制（`Text` 默认不换行处理 URL 会溢出或挤压），复制按钮是 TextButton 与"重置"并排，两个动作权重相同但风险等级完全不同 | 🟡 | Token/地址改为可换行 + 等宽字体；「重置」降级为次要样式或加确认弹窗 |
| A10 | 卫星筛选页 Miuix 自绘 Switch（`SatelliteFilterScreen.kt:282-297`） | 开关为纯 Box 手绘（40×22dp，无动画 offset 未用 `animateDpAsState`），与 Miuix 库自带 Switch 及 App 内其他开关形态不一致，且滑块永远纯白，暗色主题下突兀 | 🟡 | 换用 Miuix `Switch` 组件；如需保留自绘，至少加动画与主题色适配 |
| A11 | 卫星筛选页自绘 Checkbox（`SatelliteFilterScreen.kt:248-263`、`SatelliteCategoryScreen.kt:469-483`） | 用 20dp 方块 + "✓" 文本模拟复选框，无无障碍 Role（筛选页的版本甚至没有 toggleable 语义），与 material3 Checkbox 共存于同一页面（Material 版筛选用的是真 Checkbox），同页两套勾选控件 | 🟡 | Miuix 版也统一用语义化自绘（补 Role.Checkbox + minimumInteractiveComponentSize）或直接用库组件 |
| A12 | `MiuixFilterSelectableRow` 等筛选行（`SatelliteFilterScreen.kt:233-265`） | 行点击区域 `padding(vertical = 10.dp)` 仅约 40dp 高，低于 48dp 触控标准（分类弹窗 `MiuixCheckRow` 反而做了 heightIn(48dp)，同一功能两种标准） | 🟡 | 统一 48dp 最小行高（参照 MiuixCheckRow 的写法） |

### B. 页面布局与排版

| # | 位置 | 问题 | 严重度 | 建议 |
|---|------|------|--------|------|
| B1 | AI 聊天页 `ComposerMiuix/Material`（`AiAssistantScreen.kt:802-880`） | 输入框底部一行塞了 3 个动作：附加（36dp 圆）、模型选择（文本+箭头）、发送（40dp 圆）。模型标签限宽 140dp 且流式时 alpha 0.5 可点击（点击还会触发 reloadConfig），可发现性与状态语义都不清晰 | 🟡 | 模型选择在 streaming 时禁用并显示提示；标签改为「模型名 ▾」固定样式；三个动作间距规格化（8dp→12dp） |
| B2 | AI 设置页 `ModelConfigCardMiuix`（`AiSettingsScreen.kt:544-840`） | 单卡塞入：预设/协议 tab、Base URL、名称、模型目录、模型列表（每项含两个输入框）、Key 保存、保存/取消、新增服务商，纵向近 10 个逻辑分区无任何分组标题或分隔节奏，滚动迷失 | 🔴 | 拆为多卡：① 服务商与连接 ② 模型列表 ③ API Key；或加分组小标题（13sp SemiBold + 8dp 上边距的节奏） |
| B3 | AI 设置页探针结果 `ProbeResultMiuix/Material`（`AiSettingsScreen.kt:389-415`） | 测试连接的结果以 4 行 11sp 裸文本直接堆在页面底部（无卡片包裹），与上方卡片的视觉重量断裂；且结果距离触发按钮（「测试连接」在页面中部）很远，反馈不可见 | 🔴 | 结果合并进「测试连接」所在卡片内，紧邻按钮显示；用状态色背景条（success/error container）增强可读性 |
| B4 | 卫星雷达页（`SatelliteRadarScreen.kt:120-199`） | 布局是 Column 顶天立地：倒计时行贴顶、雷达图居中后、数据卡贴底，中小屏幕上雷达图与数据卡之间可能形成大片空白或挤压；未对 `widthIn(max=460dp)` 之外的空间做水平居中说明（Column 有 CenterHorizontally，OK，但数据卡与倒计时行在超宽屏上是全宽的，与雷达图 460dp 不对齐） | 🟢 | 倒计时行与底部数据卡同样限制 max 460dp 并居中，保持同一内容轴线 |
| B5 | 卫星地图页底部面板（`SatelliteMapScreen.kt:133-174`） | 四个指标（方位/仰角/距离/高度）用 SpaceBetween 挤一行，倒计时独占第二行左对齐，信息主次不清；面板 alpha 0.9 盖在地图上但无圆角顶部处理，像悬浮补丁而非面板 | 🟡 | 倒计时（关键信息）提到第一行或做成主视觉；面板顶部加 16dp 圆角 + blur，向卫星详情页的 BlurredBar 语言靠拢 |
| B6 | 卫星管理页概览卡（`SatelliteManagementScreen.kt:358-496`） | 搜索框、描述文字、3 个统计、2 个入口按钮、2 个 ActionRow（定位/数据源）全部塞进一张卡，垂直高度超过半屏，首屏几乎看不到卫星列表 | 🔴 | 精简：描述文字可删（信息重复）；统计三连与筛选入口合并成一行；两个刷新 ActionRow 折叠进「数据源」次级面板或下拉刷新 |
| B7 | AI 空态 `EmptyStateMiuix`（`AiAssistantScreen.kt:415-480`） | 空态含 logo+标题+描述+4 个示例卡，整体高度可能超出键盘弹出后的可视区域，且未在 `ime` 弹出时收缩；示例卡固定 4 条不可滚动 | 🟢 | 空态区域套 `verticalScroll` 或限制最大高度；键盘弹出时隐藏示例（`imePadding` + `imeVisible` 判断） |
| B8 | 卫星详情页操作按钮（`SatelliteDetailScreen.kt:248-282`） | 「打开雷达图」「打开地图」两个 primary 按钮并排等权重，且位于页面最底部（需滚动到底才能看到）；Button 内 `Spacer(Modifier.height(0.dp))` 是无效代码 | 🟢 | 双按钮降为「主按钮 + 次按钮（outlined）」；或固定在底部导航区；清理 Spacer(0) |

### C. 交互体验（操作流程 / 按钮与反馈）

| # | 位置 | 问题 | 严重度 | 建议 |
|---|------|------|--------|------|
| C1 | AI 聊天页历史面板（`AiAssistantScreen.kt:1754-1767`） | 删除会话：点垃圾桶 → 弹确认框（OK）。但**导出**操作点击后直接拉起系统保存框，无任何进行中/完成反馈；删除确认框与导出共用 `AiConfirmDialogMiuix` 样式，权重混乱 | 🟡 | 导出加成功 Snackbar；Material 版用 AlertDialog、Miuix 版用 OverlayDialog，两者样式已分叉（见 D 组） |
| C2 | 卫星管理页列表项提醒开关（`SatelliteManagementScreen.kt:744-764`） | 提醒 IconButton 直接切换，无开关结果反馈（成功/失败/权限缺失静默）；且图标在 16dp 信息密集行内只有默认 48dp 命中区，与相邻分类、展开按钮的间距仅 4dp，密集排列易误触 | 🟡 | 切换后用 Snackbar/Haptic 反馈；三个 IconButton 间距增至 8dp 或改用紧凑图标按钮规格 |
| C3 | 卫星筛选弹窗（`SatelliteFilterScreen.kt`） | 60+ 模式选项平铺在一个 LazyColumn 里，无搜索、无分组（按频段/卫星类型），找 "FM" 需要长滚动；即时写回但弹窗内无「已选 N 项」汇总 | 🟡 | 模式区顶部加搜索框；已选模式以 chip 流显示在区块顶部，支持单个移除 |
| C4 | AI 设置页「清除历史」（`AiSettingsScreen.kt:195-199`） | `ArrowPreference(onClick = viewModel::clearHistory)` **无确认弹窗**，误触即清空全部历史，破坏性操作无保护 | 🔴 | 先弹确认（标题+影响说明+确认按钮），再执行 |
| C5 | AI 设置页 Key 输入（`AiSettingsScreen.kt:777-792`） | API Key 输入框无 `visualTransformation = PasswordVisualTransformation`，明文显示；无粘贴即校验格式的前端提示 | 🟡 | 加密码变换 + 可见性切换按钮 |
| C6 | AI 模型选择弹窗 `ModelPickerDialogMiuix`（`AiSettingsScreen.kt:911-916`） | 「删除」按钮以 TextButton 形式与「选择」并排放在每一行，误触概率高且视觉权重高于其危险度 | 🟡 | 删除移到长按或行尾图标（低饱和），或加确认 |
| C7 | AI 聊天页模型 Popup（`AiAssistantScreen.kt:929-934`） | `Popup(offset = IntOffset(0, -56))` 硬编码偏移锚定 BottomEnd，小屏/横屏或输入框换行后可能被屏幕边缘裁切；无入场动画 | 🟢 | 用 `onGloballyPositioned` 取锚点位置动态计算；加 scale+fade 入场 |
| C8 | 卫星雷达页罗盘十字（`RadarView.kt:182-189`） | 罗盘 aim 十字线使用 AIM_COLOR（深红 0xFFBA1A1A）绘制在雷达图上，与「卫星位置光点」（绿色）语义易混淆——红色在该图中已是「蚀/错误」语义 | 🟡 | 罗盘线换用中性色（onSurface 40% alpha）或加「N」方位字母标记 |
| C9 | AI 设置页测试连接（两版） | 测试中仅按钮文案变「测试中…」，无 loading 指示、无超时提示；结果区远在页尾（见 B3） | 🟡 | 按钮内加 CircularProgressIndicator；结果就近显示 |

### D. 一致性（双皮肤 / 模块间 / 与整体风格）

| # | 位置 | 问题 | 严重度 | 建议 |
|---|------|------|--------|------|
| D1 | AI 聊天顶栏 | Miuix 版只有 History + Settings 两个动作，Material 版有 History + **Add（新建会话）** + Settings 三个（`AiAssistantMaterial.kt:225-234`）。新建会话在 Miuix 版只能进历史面板找，入口不一致 | 🔴 | Miuix 版补「新建会话」入口（顶栏或输入区），或统一去掉顶栏 Add、都收进历史面板 |
| D2 | 删除会话确认弹窗 | Material 版用 `AlertDialog`（`AiAssistantMaterial.kt:314`），Miuix 版复用 `AiConfirmDialogMiuix` 自绘 Dialog——两个弹窗圆角、按钮布局、字重都不同；而卫星分类模块已经有现成的双皮肤确认弹窗方案（`MiuixConfirmDialog`）可借鉴 | 🟡 | 抽公共 `ConfirmDialog(uiMode)` 组件，全 App 统一 |
| D3 | 附件 chip | Miuix 版带文件类型图标 + PARSING/ERROR 状态（`AiAssistantScreen.kt:1167-1230`）；Material 版也有图标但 ERROR 状态缺失（只有 PARSING），且移除按钮实现不同（见 A5） | 🟡 | 以 Miuix 版为准补齐 Material 版 ERROR 状态 |
| D4 | 流式光标 | Miuix 版光标有透明度呼吸动画，Material 版是静态 "▍"（`AiAssistantMaterial.kt:579`） | 🟢 | 统一呼吸动画 |
| D5 | 卫星详情页 | Miuix 版有 BlurredBar（毛玻璃顶栏）+ LocalCardAlpha 卡片透明度，Material 版无 blur 也无卡片 alpha，同一页面在两种皮肤下质感差异巨大 | 🟡 | Material 版顶栏加 `surfaceColorAtElevation` 或接受差异并记录进 UI_STYLE_PARITY 文档；至少统一卡片 alpha 行为 |
| D6 | `MetricMiuix` vs `MetricMaterial` vs `LiveBigNumberMiuix` vs `RadarStatMiuix` vs `MapInfoTextMiuix` | 同为「label+value 数字对」，五个实现、五种字号组合（9.5/11/12/15/16/22/30sp）。卫星卡片内 AI 复用了 MetricMiuix，卫星模块又各自造轮子 | 🟡 | 在 `ui/component` 下抽 `StatCell(label, value, emphasis)` 统一规格 |
| D7 | 倒计时格式 | 卫星雷达页顶部用 `formatPassCountdown`（primary 色 14sp），地图页底部用同一函数但 12sp 灰色，详情页在卡片里 14sp——同一数据三种呈现 | 🟢 | 确定倒计时的信息级别（建议雷达页主视觉、地图页与详情页次级），统一字号与颜色策略 |
| D8 | AI 设置页标题 | 两版均硬编码 "AI 助手设置" 字符串（`AiSettingsScreen.kt:105`、`AiSettingsMaterial.kt:83`），未走 `stringResource`，与其余页面的 i18n 实践不一致 | 🟢 | 提取到 strings.xml |
| D9 | 卫星卡片内 `SatelliteRadar`（AI 组件）与卫星模块 `RadarView` | 两个雷达组件视觉规格完全不同（后者带扫描扇形/轨迹/日月图标，前者是简化版但扫描角语义也不同——AI 版画的是 ±30° 固定扇区且 startAngle 计算为 azimuth-120，扇区并不跟随扫描逻辑），用户在 AI 卡片与雷达页看到两种"雷达" | 🟡 | AI 卡片复用 RadarView（缩小尺寸 + 关闭 sweep/track），保持视觉连续性 |

### E. 信息层级与可读性

| # | 位置 | 问题 | 严重度 | 建议 |
|---|------|------|--------|------|
| E1 | 卫星管理列表项（`SatelliteManagementScreen.kt:779-811`） | 单卡最多可出现 4 类 chip（状态/分类×3/模式×4/转发器数），FlowRow 换行后卡片高度不可预期；chip 字号 11sp、内边距 8×3dp 过密，可读性差 | 🟡 | chip 垂直内边距增至 5-6dp；状态 chip 常显、模式 chip 收进展开区或限 2 个 |
| E2 | `MetricMiuix` label 9.5sp（`AiAssistantScreen.kt:1408`） | 9.5sp 低于 Android 推荐最小 12sp（无障碍），中文字符在小字号下笔画粘连 | 🔴 | 最小 11sp（caption 级），推荐 12sp；全部排查 <10sp 的文本（附件 chip 10sp、参数预览 9sp 等同问题） |
| E3 | AI 卫星卡（`AiAssistantScreen.kt:1418-1556`） | 一张卡内平铺：名称/编号/模式/实时数据(6 指标+雷达)/过境×3/转发器×4/两个按钮，卡片可高达一屏，稀释了 AI 回答正文的存在感 | 🟡 | 默认折叠为「名称 + 在境状态 + 最大仰角」摘要行，点击展开完整数据；与 ToolCard 的折叠模式一致 |
| E4 | 转发器行频率格式 | 管理页 `RX 145.800  TX 435.030`（无 MHz 单位，`formatMHz` 只输出数字），详情页 `RX 145.800 MHz`（带单位），AI 卡 `145.800 / 435.030 MHz`（第三种格式）——同一物理量三种写法 | 🟡 | 统一 `145.800 / 435.030 MHz` 或统一省略单位的格式 + 图例说明 |
| E5 | `TimeBadge`（`SatelliteManagementScreen.kt:990-1018`） | label 与 value 用全角冒号拼接（"$label："），中英文混排时基线不齐；「无过境窗口」占位与真实时间徽章视觉等重，扫读时误判 | 🟢 | label/value 分两个 Text 排（参考 RadarStat 的上下结构）；无过境占位降饱和度 |
| E6 | 卫星详情页多普勒卡（`SatelliteDetailScreen.kt:166-178`） | 无转发器数据时显示「数据不可用」两次（上行/下行各一），负面信息占据整卡 | 🟢 | 无数据时整卡隐藏或合并为一行提示 |

---

## 三、共性问题与改进路线

1. **建立状态色令牌**（对应 A1/A2/C8）：在 `SafeColors` 补 `success/warning/danger` 的 on/container 三件套 dark 变体，卫星模块 6 处硬编码色全部替换。工作量小、收益最大，建议第一批做。
2. **统一「数字对」组件**（A7/D6/D7）：`StatCell(label, value, emphasis: Hero|Primary|Inline)`，五个实现收敛为一个。
3. **统一 Chip / 反馈组件**（D2/C1/C2）：公共 ConfirmDialog、全局 Snackbar 宿主、chip 规格（11→12sp、内边距 8×5dp）。
4. **AI 设置页信息架构重排**（B2/B3/C4/C5）：拆卡 + 结果就近 + 破坏性操作加确认，这是一次性重构，优先级第二。
5. **双皮肤 parity 清单**（D1-D9）：把本报告 D 组逐条加进现有 `docs/UI_STYLE_PARITY.md` 跟踪。
6. **触控区专项**（A5/A12/C2）：全局排查 <48dp 可点击元素，批量修。

---

## 四、值得保留的现有优点

- 卫星列表项的「已分类用 tertiaryContainer、在境用 primary 边框」的双状态区分思路清晰；
- 工具调用卡「运行中/失败展开、成功自动折叠」的交互设计很成熟；
- 分类弹窗的 48dp 触控区与无障碍 Role 注释意识很好（可作为其他 Row 的范式）;
- AI 聊天页对 IME/导航栏 inset 取 max 的处理正确且注释清楚。
