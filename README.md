# HamKit

中文丨[English](README_EN.md)

为业余无线电爱好者打造的随身控制台——分区定位、卫星过境预测、CW 摩尔斯训练、过境提醒、AI 助手等功能一体化。

## 网站

[hamkit.click](https://hamkit.click)

## 开发路线图

### 已完成

- **CW 练习器**（摩斯电码学习）
- **SSTV 慢扫描电视**（自研纯 Kotlin DSP，接收 + 发射 + 图库 + 离线录音解码，支持 PD / Robot / Martin / Scottie / Wraase 全族共 19 种模式）
- **HF Fax 天气传真**（WEFAX，120 行/分，实时接收 + 白边自动对齐）
- **卫星定位与追踪**（自研 SGP4/SDP4 引擎，Look4Sat 风格雷达图，SatNOGS 转发器频率库）
- **AMSAT 卫星状态**
- **日历过境提醒**
- **首页时间卡**（实时天气温度、UTC 时间、自定义背景图裁剪）
- **AI 助手（P1）**（底部导航第 3 项；OpenAI Chat Completions / Anthropic Messages / Gemini 三协议流式对话；自带 API Key，Key 与对话历史 Android Keystore AES-GCM 加密；支持内网 http / 自签证书）
  - **工具调用**：双区定位、卫星过境/实时追踪/转发器/AMSAT、搜索卫星、天气/UTC、摩斯编码、页面跳转、开始 SSTV 接收、过境提醒
  - 对话内嵌**卫星详情卡**（实时数值、过境时间、转发器）与工具执行进度条
  - 回答支持 **Markdown 渲染**（标题 / 列表 / 代码块 / 引用 / 链接），设置页可**自动获取模型列表**
- **跨平台内核**（Kotlin Multiplatform `:shared`：分区定位、SGP4/SDP4 过境计算由 Android 与 iOS 共用；iOS 壳工程与迁移路线见 [docs/IOS_ADAPTATION.md](docs/IOS_ADAPTATION.md)，UI 功能持续补齐中）

### 短期计划

- **FT8** 打磨（借鉴 [FT8CN](https://github.com/BG7HIM/FT8CN)）
- **APRS** 打磨（借鉴 [aprsdroid](https://github.com/ge0rg/aprsdroid)）

### 长期计划

- **RTTY** 开发（从零研究信号处理）

### 扩展规划

- QSO 日志记录
- QSO 导出（XML）
- 无线电中继查询（和相关授权方合作）

## 核心功能

### 分区定位
- 一键获取当前 GPS 坐标
- 实时计算 **CQ Zone**、**ITU Zone** 与 6 位 **Maidenhead 网格**定位
- 反向地理编码显示当前位置地址（3 秒去抖、失败指数退避自动恢复）

### 卫星过境预测
- 自研 **SGP4/SDP4** 轨道计算引擎（predict4java 仅用于差分验证），并行预测未来 48 小时过境
- 支持 **CelesTrak** TLE 数据源（amateur / satnogs 分组）
- 内置 **SatNOGS 转发器频率库**，提供收发器名称与频率信息
- **Look4Sat 风格雷达图**，极坐标实时显示在境卫星方位与俯仰
- 实时 **AMSAT** 状态查询（含延续标记），BJT 分段时间线，在境倒计时
- 收藏常用卫星，按收藏 → 在境 → AOS 排序

### CW 摩尔斯练习
- 完整 **Koch 课程**（26 课）+ 字符组 / 呼号 / 文本训练
- AudioTrack 实时合成正弦波，可调 WPM、音调与播放模式
- 训练进度跟踪

### SSTV 慢扫描电视
- **纯 Kotlin 自研 DSP**：正交鉴频（8 阶 Butterworth 低通 + 多样本相位差分），零第三方依赖
- **VIS 头自动识别**模式，实测在 ±120 Hz 频率偏移下仍能正确锁定；弱信号可手动锁定兜底
- 支持 **PD-50 / 90 / 120 / 160 / 180 / 240 / 290、Robot 8 / 12 / 24 B/W、Robot 24 / 36 / 72、Martin 1 / 2、Scottie 1 / 2 / DX、Wraase SC2-180** 共 19 种模式（含黑白族）
- **行同步检测 + slant 最小二乘校正**：自动补偿收发时钟差（实测 ±1% 漂移可精确校正）
- **失谐补偿**：以全部行同步脉冲的平均频率估计频偏，多普勒下仍能还原正确亮度
- **行奇偶从信号判定**：Robot 24/36 的 Cr/Cb 由分隔脉冲频率决定，录音从奇数行开始也不会红蓝互换
- 实时逐行预览（周期性部分解码，图像自上而下显现），收齐自动存图
- **离线解码**：导入 WAV（8/16/24/32 位整数、32/64 位浮点）与 **FLAC / MP3 / AAC / OGG**（走平台解码器，零第三方依赖），事后出图
- **接收录音**：接收时把原始音频流式存成 WAV（带 VIS 头），可一键重解 —— 当时没解好的帧还有机会救回来
- **信号瀑布图**：实时显示解调信号的频率-时间图，便于调谐与判断信号质量（同步线是否落在 1200 Hz）
- **盲扫**：VIS 头被噪声吃掉时，由行同步间隔 + 失谐直方图 + 黑电平间隙结构反推模式，并给出候选列表供切换
- **弱信号增强**：按实测信噪比自适应积分窗（强信号不改变行为）、零相位像素 Nyquist 低通、可选直方图拉伸 / 中值降噪 / 坏行修复
- **连续接收**：收满一帧保留模式与失谐量、自动接续下一帧（ISS 连发多张时用）
- **持续接收**：不需要 VIS 头、不等帧边界，从任意时刻开始解，预览像卷纸一样不断向下延长；频率流两段式回收，收一小时内存也不涨
- **音源可选**：麦克风 / **播放捕获**（Android 10+，直接解码其它应用正在播放的音频，如播放器里的录音或 WebSDR 网页）
- **发射**：选图 → 编码成 SSTV 音频 → 对着电台话筒播放，或导出标准 WAV 走音频线；可叠加呼号、可选 VIS 头，全程流式（最长模式 PD-290 也不会撑爆内存）
- **图库**：缩略图网格浏览、全屏查看、删除、分享（FileProvider）、导出到系统相册
- 与卫星功能衔接：ARISS 的 ISS SSTV 活动使用 **PD-120**（145.800 MHz）。卫星过境提醒的通知可直接进入接收态（自动预置 PD-120 并开始接收），连发多张时自动接续下一帧

### HF Fax 天气传真（WEFAX）
- 自研纯 Kotlin 解码器：**120 行/分（0.5 秒/行）、640 像素宽、灰度**，与 SSTV 完全独立的接收通路（WEFAX 没有 VIS、也没有行同步脉冲）
- **300 Hz 启动音检测**自动找到图像起点；也可「立即开始」跳过（适合从中间录起的素材）
- **白边自动水平对齐**：跨行统计最亮列并循环移位；**失谐自动估计**（±Hz 显示）
- 复用弱信号后处理（直方图拉伸 / 中值降噪 / 坏行修复），可保存到图库
- ⚠️ **未在真实 WEFAX 信号上验证过**，目前只有模块级自环测试；垂直方向无逐行同步（WEFAX 固有限制），可能有半行偏移

### 过境提醒
- AlarmManager 精确闹钟 + WorkManager 每日刷新
- AOS 前可配提前量提醒，支持仅白天模式
- 重启自动恢复（BootReceiver）

### 首页时间卡
- 集成实时天气温度与 UTC 时间
- 支持自定义背景图（内置 the_moon 月相图）与图片裁剪
- MaterialKolor 从背景图自动提取色板生成主题

## 应用截图

| 定位页面 | FT8 | APRS | CW 教程练习 |
| --- | --- | --- | --- |
| ![定位页面](images/定位页面.jpg) | ![FT8](images/FT8.jpg) | ![APRS](images/APRS.jpg) | ![CW 教程练习](images/CW-教程练习.jpg) |

## 技术栈

**语言与框架**
- Kotlin 2.4.0
- Jetpack Compose (BOM 2026.05.01)
- Material 3 Expressive (1.5.0-alpha22) + Miuix KMP 0.9.3
- Navigation3 1.1.2
- Coroutines 1.11.0

**数据与定位**
- Room 2.7.0 / DataStore 1.1.4
- Google Play Services Location 21.3.0
- 高德地图 SDK + Web 服务（地图 / 天气 / 逆地理编码）
- OkHttp 5.3.2 / WorkManager 2.10.0

**领域专用**
- 自研 SGP4/SDP4 引擎（predict4java 1.3.1 仅用于差分验证）
- SatNOGS 转发器频率库
- 高德地图 3D SDK
- MPAndroidChart v3.1.0
- Coil Compose 2.7.0 / Palette 1.0.0
- MaterialKolor 4.1.1（动态取色）/ Commonmark 0.28.0（Markdown 渲染）

**工程化**
- Gradle 9.4.1 / AGP 9.2.1 / KSP 2.3.10
- JaCoCo 0.8.12 / R8 ProGuard
- GitHub Actions CI/CD

## 系统要求

- Android 8.0（API 26）及以上
- targetSdk 37
- 需定位权限

## 反馈

如有 bug 请在 [Issues](https://github.com/fuxue-linkong/HamKit/issues) 中提出，或发送邮件至 liurun637@gmail.com。

也欢迎提出功能需求（能力有限，不一定能实现）。

## 交流群

![HamKit QQ群](images/hamkitqq.jpg)


## 许可证

[MIT License](LICENSE)


- 你看到的月亮不仅仅只是表相