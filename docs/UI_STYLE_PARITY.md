# 界面双风格（Miuix / Material）实现约定

> 编写日期：2026-10-11
> 适用分支：`miuix`

## 一、为什么有两套界面

HamKit 提供两种视觉风格，用户在设置里切换：

| 风格 | 取值 | 组件来源 |
|---|---|---|
| MIUI 风格 | `UiMode.Miuix` | `top.yukonga.miuix.kmp.*`（Miuix） |
| Material 风格 | `UiMode.Material` | `androidx.compose.material3.*` |

风格由 `com.example.hamkit.ui.UiMode` 表示，通过 `com.example.hamkit.ui.LocalUiMode`
（`CompositionLocal`）读取；主题入口是 `ui/theme/Theme.kt` 的 `HamKitTheme`，它在两种模式下
分别套 `MiuixHamKitTheme` / `MaterialHamKitTheme`。

**这意味着：在 Material 模式下，`MiuixTheme` 并没有被提供。** 一个只用 Miuix 组件写的页面
在 Material 模式下会退化成 Miuix 的默认配色，与周围的 Material 界面完全不是一套视觉语言 ——
这不是「不好看」，而是明确的功能缺陷。

## 二、分发约定（新增页面必须遵守）

**对外只暴露一个函数（原页面名），内部按风格分发。**

两种写法都在用，按页面规模选择：

### 写法 A：同文件分发（页面小、状态少）

用 import alias 区分两套组件，两个 private composable 放在同一文件。范例见
`ui/screen/location/LocationDetailScreen.kt`：

```kotlin
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
// ...

@Composable
fun LocationDetailScreen() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> LocationDetailScreenMiuix(...)
        UiMode.Material -> LocationDetailScreenMaterial(...)
    }
}
```

### 写法 B：分文件（页面大、交互多 —— 本仓库的主流写法）

- `XxxScreen.kt`：保留原 Miuix 实现，函数改名为 `XxxScreenMiuix`，并在文件里新增分发入口
  `XxxScreen`（**名字与签名保持一致**，调用方无需改动）。
- `XxxScreenMaterial.kt`：新增的 Material 实现，函数名 `XxxScreenMaterial`，**参数列表与 Miuix 版
  完全一致**。

```kotlin
// XxxScreen.kt
@Composable
fun XxxScreen(onBack: () -> Unit = {}) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> XxxScreenMiuix(onBack)
        UiMode.Material -> XxxScreenMaterial(onBack)
    }
}

@Composable
fun XxxScreenMiuix(onBack: () -> Unit = {}) { /* 原实现 */ }
```

```kotlin
// XxxScreenMaterial.kt
@Composable
fun XxxScreenMaterial(onBack: () -> Unit = {}) { /* Material 实现 */ }
```

页面内的 private 辅助组件各写一份（Material 版加 `Material` 后缀避免同名冲突）。

## 三、状态共享

两套实现**必须共用同一个 ViewModel**：都调用
`com.example.hamkit.ui.appViewModel<XxxViewModel>()`。该函数绑定 application 级
`ViewModelStoreOwner`，因此两种风格拿到的是同一实例 —— 用户切换风格不会丢状态、不会中断
正在进行的接收或播放。

不要为了「传参方便」把状态从分发入口往下传：状态项往往有几十个，内联参数会把签名撑爆，
而共享 ViewModel 已经解决了这个问题。

### 例外：首启隐私闸门没有 ViewModel

首次启动的隐私同意页（`ui/screen/privacy/PrivacyConsentScreen.kt`）出现在任何业务
ViewModel 创建之前，因此它没有共享 ViewModel；三项勾选与「是否正在看政策全文」等
**纯 UI 局部状态**由分发入口自己 `remember`，再以少量参数传给两套实现。

与它相关的两条硬性要求：

1. **闸门内必须自带主题**。`MainActivity` 在 `consentGranted == false` 时提前
   `return@setContent`，走不到主界面的 `HamKitTheme`，所以闸门里要用
   `CompositionLocalProvider(LocalUiMode provides …) { HamKitTheme(…) { … } }` 自行包裹，
   否则页面会退回 Material 3 默认紫（这正是 2026-10-25 修复的问题）。
2. **读取 `ui_mode` 是安全的**。`ui_mode` 只是本机界面偏好，不含个人信息、不触发网络或
   SDK 初始化；用 `ThemeController.getUiMode(context)` 读取即可。首启 prefs 为空 → 返回
   默认 `UiMode.Miuix`，与进入主界面后的观感一致。

## 四、Material 版的风格基准

写 Material 版前先读这几份，保持一致：

- `ui/screen/about/AboutMaterial.kt`
- `ui/screen/satellite/SatelliteDetailMaterial.kt`
- `ui/screen/settings/SettingsMaterial.kt`

约定要点：

- `Scaffold` + `TopAppBar`（需要可折叠大标题时用 `LargeFlexibleTopAppBar` +
  `TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())`）
- 颜色一律走 `MaterialTheme.colorScheme.*`；卡片底色用
  `surfaceContainerHigh`，或按项目既有写法 `surface.copy(alpha = LocalCardAlpha.current)`
- 文本用 material3 的 `Text`，字号用 `MaterialTheme.typography.*`
- **优先复用项目自有组件**（`ui/component/material/`）：
  `SegmentedColumn` / `SegmentedListItem` / `SegmentedSwitchItem` / `SegmentedRadioItem` /
  `SegmentedDropdownItem`、`SwitchItem`、`TonalCard`、`ExpressiveSwitch`、`SearchBar`、`SnackBar`
- 对话框用项目自有的 `ui/component/dialog/`（`rememberConfirmDialog` 等，内部已按风格分发），
  或直接 `androidx.compose.material3.AlertDialog`
- 不需要写 `@OptIn(ExperimentalMaterial3Api::class)`：`app/build.gradle.kts` 已全局
  opt-in `ExperimentalMaterial3Api` 与 `ExperimentalMaterial3ExpressiveApi`

## 五、当前覆盖情况

已具备双风格的模块：about、home、settings、colorpalette、location、permission、satellite、
SSTV、HF Fax、APRS、FT8、QSO 日志、提醒列表、隐私同意与隐私政策。

隐私同意/隐私政策是 2026-10-25 补齐的：此前首启同意页硬编码
`lightColorScheme()/darkColorScheme()` + Material 3 组件，在默认的 Miuix 模式下会显示一套
紫色 Material 界面；现在按 `UiMode` 分发（`PrivacyConsentScreenMiuix/Material`、
`PrivacyPolicyScreenMiuix/Material`），背景色也改为跟随用户主题色与暗色模式。

其中 SSTV / APRS / FT8 / QSO / 提醒是 2026-10-11 补齐的（此前只有 Miuix 实现，在 Material
模式下会整页显示 MIUI 风格）。

`ui/component/bottombar/`、`ui/component/dialog/`、`ui/component/markdown/` 是组件层的分发点，
其余 `ui/component/` 下的组件按风格分目录（`material/` 与 `miuix/`）。

## 六、验收清单（改动双风格页面时逐条对照）

- [ ] 对外函数名与签名未变，调用方（`ui/MainActivity.kt` 的路由表等）无需改动
- [ ] Material 文件里**没有**任何 `top.yukonga.*`（Miuix）类型
- [ ] 两套实现的 `viewModel.*` / `uiState.*` 访问集合一致（可用 grep 对比）
- [ ] 两套实现的按钮、开关、列表项、对话框一一对应，行为等价
- [ ] `:app:compileDebugKotlin` 通过
- [ ] 若要真机确认：在设置里切换「界面风格」，逐页翻一遍两种风格

## 七、SSTV 界面整理（2026-10）

> 范围：`ui/screen/sstv/` 下 SSTV 的主界面、设置页、发射页（图库另有一处局部调整）。

### 起点问题

| 问题 | 表现 |
|---|---|
| 卡片过多 | 主界面 5 张卡、设置页 5 张卡、发射页 4 张卡，一屏只能看到一两组内容 |
| 参数占版面 | 「接收参数」一次列 10 行，其中 VIS/失谐/行周期比是调试参数，日常不需要 |
| 设置项重复 | 「连续接收」在主界面与设置页各有一个开关；「后处理」是设置页三个子项的**全选**，语义与细项重复 |
| 冗余操作 | 「清空图库」同时存在于设置页与图库页；图库格子里每格挂一个「删除」文字按钮 |
| 组件不统一 | 模式选择用了三种形态（Miuix 一排按钮 / Material FilterChip / 设置页一列按钮）；文字按钮、说明文字、参数行的样式各写各的 |

### 整理后的结构

**主界面**（`SstvMainScreen`）：预览卡 + 接收设置卡 + 按需出现的盲扫卡。

- 预览卡：画框 → 「模式 + 信号质量」状态行 → 进度条 → 状态文案 → 按钮组。
  主操作（开始/停止接收）独占一行，其余两两一行；「保存原图」只在开启后处理时出现。
- 接收设置卡：音源（二选一单选行）→ 模式（横向胶囊，「自动」恒在首位）→
  开关（连续接收 / 后处理 / 瀑布图）→ 瀑布图（仅在开启时占位）→ 接收明细（**默认收起**，
  常看的模式与质量已提升到预览卡）。

**设置页**（`SstvSettingsScreen`）：接收行为 / 图像后处理 / 模式锁定 / 图库与发射 / 使用提示。

- 去掉了与主界面重复的「连续接收」「后处理」总开关，只保留细项；
  「最大帧数」改为「连续接收上限」下拉，在 Miuix 版与 Material 版都是下拉（不再一边按钮一边分段列表）。
- 「图库与发射」由四个大按钮改为可点行（Miuix `ArrowPreference` / Material `SegmentedListItem`），
  删除「清空图库」以外的重复入口；清空图库仅保留在设置页，主界面不再有。
- 各分区统一「标题 + 说明 + 设置行」的结构，标题样式与全局设置页一致。

**发射页**（`SstvTxScreen`）：图像与控制合卡、参数独立、合规提示移到底部。

- 画框 → 状态 → 源文件名 + 分辨率 + 单帧时长（一行讲完）→ 选图 → 进度 → 发射/导出。
- 合规提示从页首移到页尾并用错误色卡片强调（静态长文本不再占用首屏）。

**图库**（`SstvGalleryScreen`）：格子里的「删除」移到缩略图右上角浮层；最近一次操作结果
只在有图时显示一行。

### 共用组件（避免两套实现各写一遍）

| 组件 | 位置 | 用途 |
|---|---|---|
| `SstvPreview` / `SstvPreviewMaterial` | 主界面文件内 | 黑底、按模式宽高比的画框 |
| `SstvChip` | `SstvMainScreen.kt` | 模式横向胶囊（Miuix 按钮 / Material FilterChip） |
| `SstvSwitchRow` | `SstvMainScreen.kt` | 标题 + 说明 + 右侧开关（主界面 / 设置页 / 发射页 / HF Fax 共用） |
| `SstvRadioRow` | `SstvMainScreen.kt` | 二选一单选行（音源） |
| `SstvInfoRow` | `SstvMainScreen.kt` | 参数行（1:1.6 权重，长文件名可换行） |
| `SstvCardMaterial` | `SstvMainScreenMaterial.kt` | Material 版卡片外壳（`internal`，跨文件共用） |
| `modeSummary(mode)` | `SstvSettingsScreen.kt` | 模式的一行说明，两套界面文案一致 |
| `requestPlaybackCapture` / `handleCaptureResult` | `SstvMainScreen.kt` | 播放捕获授权流程，两套共用 |

> HF Fax 原先调用的是主界面文件里的 `InfoRow`/`SwitchRow`，这两个名字改成了 `Sstv` 前缀
> 以避免与其他模块的同名组件冲突，HF Fax 已同步改为调用新名字。

### 验收

- `:app:compileDebugKotlin` 通过；对外函数签名（`SstvMainScreen` / `SstvSettingsScreen` /
  `SstvTxScreen` / `SstvGalleryScreen`）与调用方均未改动。
- 两套实现的 `uiState.*` 访问集合保持一致（主界面两侧均只读写同一组状态）。
