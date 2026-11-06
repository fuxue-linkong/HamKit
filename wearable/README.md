# 卫星过境提醒 · 手环端

小米手环 9 / 9 Pro 系列上的 Vela 快应用：**AOS 前 60 秒**弹出全屏倒计时，长振动一次，上滑关闭。

```
┌──────────────┐   interconnect   ┌────────────────────┐
│  手环快应用   │ ◄──────────────► │  HamKit (Android)  │
│ (本目录 rpk)  │   JSON 报文       │  xms-wearable-lib  │
└──────────────┘                  └────────────────────┘
```

## 交互

- **弹出**：手机在下发提醒时把 AOS 绝对时间一起发过来，手环用 `消息里的 now − 本地时间` 校正时钟偏差后**自己本地倒计时**，不依赖手机持续推送。
- **显示**：上行 `AOS 00:59`（46px 大字，每秒刷新），下行卫星名（如 `ISS`），下方一条剩余时间进度条。
- **震动**：进入页面长振动一次；到 00:00 再振一次。
- **关闭**：**上滑**（`swipe` 事件 `direction === "up"`）→ 回待命页并给手机回 `ack dismiss`。
- 00:00 之后再多显示 1 分钟，然后自动关闭，避免用户走开后页面一直挂着。

## 目录

```
wearable/
├── package.json            # aiot-toolkit 2.0.5
├── sign/debug|release/     # 签名（.gitignore 已忽略，需自己生成）
└── src/
    ├── manifest.json       # package 必须 = com.example.hamkit
    ├── app.ux              # interconnect 单例、消息分发、握手
    ├── common/utils/alert.js  # 协议解析 / 倒计时格式化（纯函数，可单测）
    └── pages/
        ├── index/index.ux  # 待命页：连接状态 + 最近提醒 + 测试按钮
        └── alert/index.ux  # 提醒页：倒计时 + 卫星名 + 上滑关闭
```

## 前置条件

1. 手环：小米手环 9 / 9 Pro 系列（AstroBox 标注「小米手环 9 及未来系列设备 Full Support」）。
2. 手机：安装 [AstroBox](https://astrobox.online/) 用于安装 rpk。
3. 手机上的 HamKit 需要是**正式签名**的包（debug 包就用 debug keystore 签 rpk）。
4. 本机：Node.js ≥ 18。

## 1. 准备签名（最容易踩坑的一步）

**rpk 的包名和签名必须与 HamKit APK 完全一致**，否则手环与手机连不上：

| 项目 | 必须等于 |
| --- | --- |
| `src/manifest.json` 的 `package` | `com.example.hamkit`（App 的 `applicationId`） |
| `sign/release/{private,certificate}.pem` | HamKit release keystore 的证书与私钥 |

用仓库自带脚本从 keystore 生成：

```bash
KEYSTORE_PATH=signing/release.keystore \
KEYSTORE_PASSWORD=xxx KEY_ALIAS=xxx KEY_PASSWORD=xxx \
./scripts/prepare-wearable-sign.sh
```

- 装 **release** 版 HamKit → 用 `wearable/sign/release/`
- 装 **debug** 版 HamKit → 用 debug keystore 再生成一份到 `wearable/sign/debug/`：

```bash
KEYSTORE_PATH=~/.android/debug.keystore \
KEYSTORE_PASSWORD=android KEY_ALIAS=androiddebugkey KEY_PASSWORD=android \
OUT_DIR=wearable/sign/debug ./scripts/prepare-wearable-sign.sh
```

> aiot 打包时会按目标自动取 `sign/debug` 或 `sign/release` 下的 pem，两者都准备最省事。

## 2. 构建 rpk

```bash
cd wearable
npm i                 # 项目已带 .npmrc，走 npmmirror 镜像
npm run build         # 开发包：dist/*.debug.rpk
npm run release       # 正式包：dist/*.release.rpk
```

也可以不本地构建：推送到 `miuix` 分支后，GitHub Actions 的 **Build Wearable RPK** job
会产出 artifact `wearable-rpk-<run_id>`。

- 仓库配了 `KEYSTORE_BASE64` 等 secrets → 产出**与 APK 同签名**的 release rpk，可直接联调
- 没配 → 用 toolkit 测试证书出 debug rpk，只能看 UI，连不上手机 App

## 3. 装到手环

1. 手环与手机正常配对、连接。
2. 打开 AstroBox → 连接设备 → 选择「安装快应用 / 上传 rpk」。
3. 选择上一步生成的 `.rpk`。
4. 安装完成后手环应用列表里出现「卫星过境」。
5. **重新打开一次**应用，确认待命页显示「已连接手机」。

> 若之前用同包名装过旧版本，建议先在 AstroBox 里卸载再装，避免旧图标/旧签名残留。

## 4. 手机端联调

1. HamKit → 设置 → 日程提醒 → 打开「**同步到手环**」。
2. 在卫星管理页给某颗卫星打开「过境提醒」。
3. 手环端应用**保持在前台待命页**（后台会被系统停掉，见下方限制）。
4. 到 AOS 前 60 秒，手环自动弹出倒计时。

排查顺序：

```
adb logcat -s WearableAlert XmsWearableLink
```

- `wearable node ready: xxx` → 手环已连上
- `alert delivered to quick app: ISS` → 消息发出去了
- `quick app unreachable, fallback to system notify` → 手环应用不在前台，走系统通知兜底
- `watch ack: shown/dismiss` → 手环回执正常

## 5. 不接手机也能自测

手环端待命页有一个「**测试提醒**」按钮：本地造一条 15 秒后 AOS 的假提醒，
直接跳到提醒页。用来验证布局、倒计时、震动、上滑关闭是否正常，不需要手机参与。

## 协议（v1）

字段名必须与 `app/src/main/java/com/example/hamkit/data/wearable/WearableAlert.kt` 一致。

```jsonc
// 手机 → 手环
{"t":"pass","v":1,"id":"25544-1730000000000","sat":"ISS","norad":25544,
 "aos":1730000000000,"los":1730000600000,"el":42,"az":128,
 "modes":["FM"],"now":1733599940000}

// 手环 → 手机
{"t":"hello","v":1}                                  // 握手，请求补发最近一条未过期提醒
{"t":"ack","v":1,"id":"…","act":"shown"|"dismiss","at":…}
```

解析是**容错**的：手机端能解开被二次 JSON 编码的字符串，手环端 likewise 逐层解到对象为止。

## 已知限制

- **应用必须在前台**：Vela 的后台运行白名单只有 `system.audio` / `system.request` /
  `system.geolocation`，`system.interconnect` 不在其中，应用退到后台会被停，收不到消息。
  因此设计成「待命页占住前台」，手机端在发不出去时自动退化为**手环系统通知**。
- **屏幕不可旋转**：Vela 没有旋转 API，所谓"横向"是横排文字（倒计时一行 + 卫星名一行），
  不是把屏幕转 90°。
- 同一时刻多颗卫星先后过境时，手环端只弹第一条，其余在回待命页后显示最新一条。
- 手环端不做 SGP4 预测，时间与卫星信息完全以手机为准。

## 改 UI 提示

- 尺寸单位：`manifest.json` 里 `designWidth: "device-width"`，即 CSS 里的 `px` 就是**设备物理像素**。
  手环 9 是 192×490 的长条屏，9 Pro 是 336×480，所以容器一律用 `width/height: 100%`，
  字号用固定 px 才能在有宽屏型号上保持一致观感。
- 圆形屏/跑道屏可用媒体查询微调：`@media screen and (shape: pill-shaped) { ... }`。
