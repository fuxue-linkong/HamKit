# HamKit 手环提醒 · 卫星过境

小米手环 9 / 9 Pro 系列上的 Vela 快应用：**AOS（卫星升起）前 60 秒**弹出全屏倒计时，
长振动一次，**上滑关闭**。

本分支是手环端工程**独立仓库**（孤儿分支）：根目录就是 Vela 工程，
提交即由 GitHub Actions 构建 rpk（见 `.github/workflows/build-rpk.yml`），产物在 Actions 的 artifact 里。

```
┌──────────────┐   interconnect   ┌────────────────────┐
│  手环快应用   │ ◄──────────────► │  HamKit (Android)  │
│ (本仓库 rpk)  │   JSON 报文       │  xms-wearable-lib  │
└──────────────┘                  └────────────────────┘
```

## 交互

- **弹出**：手机下发提醒时把 AOS 绝对时间一起发来，手环用 `消息里的 now − 本地时间`
  校正时钟偏差后**本地倒计时**，不依赖手机持续推送。
- **显示**：上行 `AOS 00:59`（大字，每秒刷新），下行卫星名（如 `ISS`），下方剩余时间进度条。
- **震动**：进页面长振动一次；到 00:00 再振一次。
- **关闭**：**上滑**（`swipe` 的 `direction === "up"`）→ 回待命页并回 `ack dismiss`。
- 00:00 后多显示 1 分钟自动退出，避免用户走开时页面一直挂着。

## 目录

```
├── src/
│   ├── manifest.json            # package 必须 = com.example.hamkit
│   ├── app.ux                   # interconnect 单例、握手、消息分发
│   ├── common/utils/alert.js    # 协议解析 / 倒计时格式化（纯函数）
│   └── pages/
│       ├── index/index.ux       # 待命页：连接状态 + 最近提醒 + 测试按钮
│       └── alert/index.ux       # 提醒页：倒计时 + 卫星名 + 上滑关闭
├── scripts/prepare-wearable-sign.sh   # 从 keystore 生成 rpk 所需 pem
└── .github/workflows/build-rpk.yml
```

## 签名（关键，最容易踩坑）

**rpk 的包名与签名必须与 HamKit APK 完全一致**，否则手环与手机连不上：

| 项目 | 必须等于 |
| --- | --- |
| `src/manifest.json` 的 `package` | `com.example.hamkit`（HamKit 的 `applicationId`） |
| `sign/release/{private,certificate}.pem` | HamKit release keystore 的证书与私钥 |

```bash
export KEYSTORE_PATH=/path/to/release.keystore
export KEYSTORE_PASSWORD=xxx KEY_ALIAS=xxx KEY_PASSWORD=xxx
OUT_DIR=sign/release ./scripts/prepare-wearable-sign.sh
```

装 debug 版 HamKit 时改用 debug keystore，输出到 `sign/debug/`：

```bash
KEYSTORE_PATH=~/.android/debug.keystore \
KEYSTORE_PASSWORD=android KEY_ALIAS=androiddebugkey KEY_PASSWORD=android \
OUT_DIR=sign/debug ./scripts/prepare-wearable-sign.sh
```

> CI 里由仓库 secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）
> 自动完成这一步；没配 secrets 就用 toolkit 测试证书，只能看 UI。

## 构建

```bash
npm ci
npm run build      # dist/*.debug.rpk
npm run release    # dist/*.release.rpk（需 sign/release 下的 pem）
```

## 装到手环

1. 手环与手机正常配对。
2. [AstroBox](https://astrobox.online/) → 连接设备 → 安装快应用 → 选择 rpk。
3. 装完打开应用，确认待命页显示「已连接手机」。
4. 若是覆盖安装旧版本，建议先卸载再装，避免旧签名残留。

## 不接手机自测

待命页有「**测试提醒**」按钮：本地造一条 15 秒后 AOS 的假提醒，直接跳提醒页，
可用于验证布局、倒计时、震动、上滑关闭。

## 协议（v1）

字段名必须与 HamKit 的 `data/wearable/WearableAlert.kt` 一致。

```jsonc
// 手机 → 手环
{"t":"pass","v":1,"id":"25544-1730000000000","sat":"ISS","norad":25544,
 "aos":1730000000000,"los":1730000600000,"el":42,"az":128,
 "modes":["FM"],"now":1733599940000}

// 手环 → 手机
{"t":"hello","v":1}                                  // 握手，请求补发最近一条未过期提醒
{"t":"ack","v":1,"id":"…","act":"shown"|"dismiss","at":…}
```

解析是容错的：两端都能解开被二次 JSON 编码的字符串。

## 已知限制

- **应用必须在前台**：Vela 后台运行白名单只有 `system.audio` / `system.request` /
  `system.geolocation`，`system.interconnect` 不在其中，退到后台会被停、收不到消息。
  所以设计成「待命页占住前台」；发不出去时手机端自动退化为**手环系统通知**。
- **屏幕不可旋转**：Vela 无旋转 API，"横向"是横排文字（倒计时一行 + 卫星名一行）。
- 同一时刻多颗卫星先后过境时只弹第一条，其余在回待命页后显示最新一条。
- 手环端不做 SGP4 预测，时间与卫星信息完全以手机为准。

## 改 UI

- `designWidth: "device-width"`，CSS 里的 `px` 就是设备物理像素。手环 9 是 192×490 长条屏，
  9 Pro 是 336×480，所以容器一律 `width/height: 100%`，字号用固定 px。
- 跑道屏可加媒体查询：`@media screen and (shape: pill-shaped) { ... }`。
