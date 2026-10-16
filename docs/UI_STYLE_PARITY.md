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
SSTV、HF Fax、APRS、FT8、QSO 日志、提醒列表。

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
