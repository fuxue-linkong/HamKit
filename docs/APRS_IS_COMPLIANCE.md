# APRS-IS Passcode 合规说明

> 对应整改条目：**HK-BUG-003**（passcode 合规）、**HK-REQ-003**（只读模式）、**HK-REQ-004**（持照声明确认）
> 关联 Issue：<https://github.com/fuxue-linkong/HamKit/issues/93>
> 社区来源：HamCQ <https://forum.hamcq.cn/d/8940/18>、<https://forum.hamcq.cn/d/8940/21>
> 官方依据：<https://www.aprs-is.net/Connecting.aspx>

## 一、官方要求（原文）

> Authors, YOU are responsible for issuing passcodes to amateur radio operators ONLY.
> Do not make this available in an ondemand fashion nor point your software users to
> someone else.

中文对照：**软件作者有责任仅向业余无线电操作者发放验证码；不得以“按需”的方式提供，
也不得把你的软件用户指引到其他人那里去获取验证码。**

## 二、整改前的违规点

| # | 位置 | 问题 |
|---|------|------|
| ① | `AprsPacket.passcode()` | 应用内直接实现 APRS-IS passcode 算法（0x73e2 成对 XOR & 0x7fff） |
| ② | `AprsConfig.computedPasscode` | 呼号非空即自动算出验证码 |
| ③ | `AprsSettingsScreen` | 设置页显示「Passcode (留空自动计算)」，并内联重算后把验证码明文展示给用户 |
| ④ | `AprsConnection` | 用户留空时自动套用 `computedPasscode` 完成登录 |

即：**任何安装本应用的人，只需填入呼号即可获得验证码** —— 属于官方明确禁止的
“ondemand fashion” 发放方式。

## 三、整改措施（当前实现）

### 3.1 移除算法（HK-BUG-003）

- 删除 `AprsPacket.passcode()` 与 `AprsConfig.computedPasscode`，算法不再随发行包发布。
- 设置页不再展示、不再提示“自动计算”；输入框 label 改为
  「Passcode（需自行持有）」，并提示「凭据由你本人持有；HamKit 不计算、不显示也不代为申请 Passcode」。
- 单测同步调整：
  - 删除依赖算法的断言（原先只断言 `>0 / <=32767 / 自洽`，无金标准，算法写错也能通过）；
  - 新增 **回归守卫**：用反射断言 `AprsPacket` 不再暴露任何 passcode 计算方法；
  - 新增 **金标准断言**：只读登录串必须精确等于 `user N0CALL pass -1 vers …`，
    持有验证码时精确等于 `user N0CALL-7 pass 12345 vers …`。

### 3.2 只读模式（HK-REQ-003）

用户未自行填写 passcode 时（`AprsConfig.isReadOnly == true`），以官方约定的
**`pass -1`** 建立只读会话：

- `AprsConnection` 使用 `config.effectivePasscode` 登录，只读时即 `-1`；
- 可正常接收报文（位置、消息、天气等）；
- 所有注入路径在客户端即被拦截，不产生无效上行流量：
  - `AprsViewModel.transmitPosition()` — 位置上报
  - `AprsViewModel.sendMessage()` — 发送消息
  - `AprsViewModel.sendPendingMessages()` — 离线队列重发
  - `AprsViewModel.sendAck()` — 自动 ACK
- APRS 主界面与设置页均显示「当前为只读模式：未填写 Passcode，仅接收不发送」。

> 服务端同样会拒绝 `pass -1` 会话的任何注入报文，客户端拦截只是为了避免
> 无用流量与误导用户。

### 3.3 持照与责任声明（HK-REQ-004）

首次连接 APRS-IS 前必须确认声明，未确认不可登录：

- 点击「连接」时，若尚未确认，则弹出《持照与责任声明》对话框；
- 声明内容：仅限持有效业余无线电执照者使用；**你呼号下的所有流量（含位置上报与消息）由你本人负责**；
  确认 Passcode 由本人合法持有，HamKit 不代为计算或发放；未填写时将以只读模式连接；
- 确认后状态持久化（`AprsSettingsStore.licenseConfirmed`），不再重复打扰；
- `AprsViewModel.connect()` 内保留防御性校验：未确认时直接转为弹窗，阻断绕过 UI 的调用路径。

## 四、开发者对外答复口径

- 我们确认收到反馈，并已按 APRS-IS 官方要求整改（Issue #93）；
- 应用内**不再实现、不显示、不自动填充**验证码算法；
- 未持有验证码的用户可以**只读方式**接入 APRS-IS（接收不受影响）；
- 验证码需用户本人通过官方渠道（`passcode@aprs-is.net`）自行申请，应用不做任何指引跳转。

## 五、发布前复查清单

- [ ] 全仓 grep `passcode`：仅剩用户输入存储、只读常量 `-1`、UI 文案与测试守卫
- [ ] 反编译 release APK，确认无 passcode 算法残留
- [ ] 官网 / README / README_EN / 应用商店描述中无 passcode 获取指引（已复查：无相关表述）
- [ ] 真机验证只读模式：能收到报文、发送被拦截且提示正确
- [ ] 真机验证首次连接弹窗：取消不连接、确认后可连接且不再重复弹出
