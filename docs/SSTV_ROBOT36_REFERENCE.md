# 参照 robot36 的能力补齐记录

> 参考项目：[xdsopl/robot36](https://github.com/xdsopl/robot36) —— Android SSTV 解码器，Java + JNI，
> **0BSD** 许可，518★，2026-10 仍在活跃维护（默认分支 `v2`）。
> 本文记录 HamKit 从它身上借鉴了什么、依据是什么、踩到了哪些坑、以及**明确没有采纳**的部分。
> 上游文档索引见 [REQUIREMENT_SSTV.md](REQUIREMENT_SSTV.md) §12（二期）与 §13（本次）。

## 〇、先划清边界：借鉴思路，不搬代码

HamKit 的既有约束是「不擅自引入第三方依赖」与「不移植 robot36 的 C/JNI」（见
[REQUIREMENT_SSTV.md](REQUIREMENT_SSTV.md) 四、明确不做）。本次仍然遵守：

- **没有**引入 robot36 的任何文件、没有加 NDK/JNI、没有新增第三方库；
- 借鉴的是**算法公式、模式参数、以及几个工程判断**，实现全部重写为纯 Kotlin；
- 几处关键参数都用**多个独立来源**交叉验证（robot36 / `sstv` Rust crate / pySSTV / slowrx），
  而不是只信一家。

robot36 是 0BSD（等同公有领域），法律上无需署名；这里标注出处是出于工程溯源的需要。

---

## 一、能力对照

| 能力 | robot36 | HamKit（补齐前） | HamKit（现在） |
|---|---|---|---|
| 模式数 | Robot 36/72、Martin、Scottie 1/2/DX、PD 全族、Wraase SC2-180、Pasokon P3/P5/P7、HF Fax | 18 种（缺 Wraase、Pasokon、HF Fax） | **19 种 SSTV + HF Fax**（新增 Wraase SC2-180） |
| 行奇偶判定 | 由分隔脉冲频率判定（even 1500 Hz / odd 2300 Hz） | ❌ 按行序号 `row % 2` 猜 | ✅ 由分隔脉冲判定（**修掉真 bug**） |
| 像素级低通 | 零相位、截止于像素 Nyquist（前向+反向 EMA） | ❌ 只有像素中点 30% 窗平均 | ✅ 已加入（默认开启，可关） |
| 输入格式 | WAV（8/16/24/32 位整数、32/64 位浮点）、FLAC、MP3 | 仅 WAV（含 32 位浮点） | ✅ WAV + FLAC/MP3/AAC/OGG（平台解码器）+ 64 位浮点 |
| 播放捕获 | ✅ Android 10+ | ❌ | ✅（+ mediaProjection 前台服务） |
| 录音/raw 留存 | 有 `RawDecoder`（保留无法解码的原始数据） | ❌ | ✅ 接收时流式录 WAV，可一键重解 |
| 水位/示波器 | ✅ `postProcessScopeImage` | ❌ | ✅ 瀑布图（频率-时间，逐格配色） |
| HF Fax（天气传真） | ✅ | ❌ | ✅ 实时 + 模块级自环验证 |
| 文件回放（边放边解） | ✅ 含 Turbo 模式 | 导入即一次性解完（等价 Turbo） | 同左（未做「边听边解」的实时回放） |
| 发射 | ❌ 只收 | ✅ 已有 | 同左 |

---

## 二、逐项记录

### A. Robot 24/36 的行奇偶（**这是真 bug**）

**问题**：Robot 24/36 每行只发一个色度分量（偶行 Cr、奇行 Cb）。原实现按**行序号**
`row % 2` 判断，隐含假设「解出的第一行就是偶行」。但录音完全可能从奇数行开始 ——
ISS 过境时手动按录音键必然如此。此时 Cr/Cb 整幅互换，图像**红蓝颠倒**，而自环测试
发现不了（编解码同模型会自洽）。

**依据（两处独立实现一致）**：
- robot36 `Robot_36_Color`：`ROBOT_36_EVEN_SEQUENCE` 的分隔用 **1500 Hz**、
  `ROBOT_36_ODD_SEQUENCE` 用 **2300 Hz**；解码时 `boolean even = separator < 0`（黑电平为偶行）。
- `sstv` crate `modes/robot.rs`：同样的 1500/2300 注释与序列，且文档明确写
  "even lines carry the red difference, odd lines the blue one, with a separator pulse
  marking the parity"。

**实现**：
- `SstvMode` 新增 `SstvParityMarker(startSeconds, seconds, evenHz, oddHz)`，Robot 24/36 声明它
  （起点 = `sync + porch + 2·chanLen` = 9+3+88 = **100 ms**）；
- 编码器新增 `SstvLineEvent.ParityMarker` 事件，按 `radioLine % 2` 发 1500/2300 Hz；
- 解码器 `resolveLineParity()` 采样该分隔段，只有当实测值**明显更像**其中一个候选
  （余量 200 Hz）时才采信，否则沿用上一行的判定 —— 弱信号下逐行硬猜会让红蓝随机翻转，
  画面上是逐行色斑。

**验证**：新增用例
- `分隔脉冲频率按行奇偶交替写出`（直接检查编码音频，偶行 1500 / 奇行 2300）；
- `录音从奇数行开始时颜色不互换`（把帧首整行切掉后解码，断言 PSNR 高 **且** R/B 互换版本的
  PSNR 明显更低 —— 这直接证明了「颜色没被交换」）；
- `完整帧的奇偶判定与行序号一致`（防极性搞反）。

### B. 零相位像素 Nyquist 低通（**收益有边界，值得记下来**）

**依据**：robot36 的 `ExponentialMovingAverage` + `cutoff(horizontalPixels, 2*luminanceSamples, 2)`，
再做**前向一遍、reset、反向一遍**。其公式：
```
x = cos(2π·f/rate);  α₁ = x − 1 + √(x(x−4)+3);  α = α₁^(1/order)
avg(v) = prev = prev·(1−α) + α·v
```
`f = pixels`、`rate = 2·channelSamples` 时归一化截止恰为
`0.5/pixelSamples` —— 正是**像素 Nyquist**，即「一个像素能表达的最高横向频率」。
前向+反向是关键：单向滤波会把整行内容沿时间轴平移，表现为水平错位。

**实现**：`SstvDecoder.sampleChannel()` 在开启时先取通道覆盖的样本 → 转成频率流 →
零相位二阶 EMA → 再按像素中点取值；关闭时退回一期的「像素中点 30% 窗平均」。
反向那一遍用**最后一个样本**初始化 `prev` 而不是 0（robot36 用 0，会把最右侧几个像素压暗；
这是本实现的一处主动改进）。

**实测发现的收益边界**（写进 `SstvRobot36ReferenceTest`）：

| 模式 | 像素 Nyquist @48 kHz | 解调器基带带宽 | 低通收益（SNR 20 dB） |
|---|---|---|---|
| PD-120 | 2632 Hz | 1200 Hz | ≈ 0（无事可做，也不劣化） |
| Robot 36（亮度通道） | 1818 Hz | 1200 Hz | ≈ +0.4 dB |
| Scottie DX | **463 Hz** | 1200 Hz | 明显（有专门用例守护 > 1 dB） |

原因是**本项目的解调器已经在更前面做了带限**：8 阶 Butterworth 截止 1200 Hz，
对多数快模式而言它比像素 Nyquist 还窄，噪声在进入解码器之前就被滤掉了。robot36 需要这个
滤波器，是因为它的解调基带比像素 Nyquist 宽得多。**这不是「robot36 的做法更好/更差」，而是
两级带限的先后顺序不同**；把它加进来对慢模式（长像素时长）有实打实的价值。

**验证**：`低通不改变纯色图像`（行内部逐像素不变、无整体衰减）、`低通不引入水平位移`
（干净信号不劣化 > 1.5 dB）、`低通改善弱信号图像`、`低通在像素 Nyquist 低于解调带宽时才真正降噪`、
`低通在快模式下也不劣化`。

### C. Wraase SC2-180（**三处来源交叉确认**）

结构最简单的彩色模式：同步 + porch 之后直接跟 **R、G、B** 三段，**没有任何通道分隔**。
机器人36 与 `sstv` crate 给出完全相同的时序：

| 参数 | 值 | 来源 |
|---|---|---|
| VIS 码 | **55 = 0x37** | robot36 `RGBModes.Wraase_SC2_180` + `sstv` crate `Mode::vis_code()` |
| 分辨率 | 320×256 | 两处一致 |
| sync | 1200 Hz，5.5225 ms | 两处一致 |
| porch | 1500 Hz，500 µs | 两处一致 |
| 通道 | R 235 ms → G 235 ms → B 235 ms | 两处一致（**顺序 R→G→B**） |
| 行周期 | 711.0225 ms（256 行 = 182 s） | 两处一致 |
| 分隔 | **无** | 两处一致 |

**实现要点**：Martin/Scottie 是 **G→B→R**，Wraase 是 **R→G→B** —— 顺序写错会让整幅图红绿蓝互换，
而自环测试**发现不了**。因此把顺序参数化成 `SstvRgbOrder`（GBR / RGB），解码端**按角色取通道**
而不是按下标。用例 `Wraase 的通道顺序是 R G B 而 Martin 是 G B R` 直接断言顺序本身。

顺带：这一轮的 VIS 码交叉核对也**第三次确认**了此前由我方推断的 PD 族 VIS 码
（PD-50=93、PD-90=99、PD-160=98、PD-290=94），以及 PD-120/180/240 与 Robot/Martin/Scottie 全族。

### D. FLAC / MP3 导入 + 64 位浮点 WAV

**依据**：robot36 README 明确列出「WAV（8/16/24/32 位整数或 32/64 位浮点）、FLAC、MP3」，
并提醒「MP3 有损，可能引入噪点/偏色/解码失败」。网上流传的 ISS SSTV 录音确实以 MP3 居多。

**实现**（零第三方依赖，全部走平台 API）：
- 新增 `SstvAudioConvert.kt`：纯 Kotlin、可单测的「字节流 + 采样格式 → 单声道 FloatArray →
  线性插值重采样到 48 kHz → 300 秒截断」共享层，含 `MonoAccumulator`（**边解边截断**，
  避免 1 小时 FLAC 先撑出 690 MB 才被裁掉）；
- 新增 `SstvMediaAudioReader.kt`：`MediaExtractor` + `MediaCodec` 解任意容器。处理了
  `audio/raw` 轨道（WAV 常见，免 codec）、`info.offset` 非零、输出格式的
  `PCM_16BIT` / `PCM_FLOAT` / `PCM_8BIT` / `PCM_24BIT_PACKED` / `PCM_32BIT`，
  以及采样率/声道数由**输出格式**决定（8–192 kHz）；
- `SstvWavReader` 补 **64 位 IEEE float**；`SstvViewModel.importAudio` 改为**按内容选通路**
  （RIFF/WAVE 走纯 Kotlin 解析，其余走平台解码器；前者失败时再回落到平台解码器并合并两条失败原因）。

**未验证**：`MediaCodec` 解码循环无法在 JVM 单测里跑，**只做了编译验证**，未在真机上解过
FLAC/MP3；`SstvAudioConvert` 的纯逻辑有 23 个用例覆盖。

### E. 接收时录制原始音频

**动机**：robot36 有 `RawDecoder` 保留无法解码的原始数据；更重要的是 HamKit 一直缺
「真实录音回归素材」（[REQUIREMENT_SSTV.md](REQUIREMENT_SSTV.md) §12.5 列的遗留项）。
录下来还能让「事后重解」救回当时没解好的帧。

**实现**：
- 新增 `SstvWavRecorder.kt`：**流式**写 PCM16、结束时回填 RIFF 头（内存占用与时长无关）；
- 新增 `SstvRecordingStore.kt`：独立目录 `filesDir/sstv_recordings/`（**刻意与图库分开** ——
  录音是中间物、且大一个数量级，混在一起会让「图库占用」失去意义），
  文件名 `SSTV_RX_<时间戳>[_<模式>].wav`（模式在收到 VIS 之后才知道，故结束时改名）；
- `SstvRecorder` 从**打开音源的第一刻**开始录，因此 **VIS 头一定在文件里** ——
  这是「录下的文件能被离线解码器识别」的前提；
- 写盘不在读取线程做：读取线程只做一次内存拷贝到暂存区，真正的 `RandomAccessFile.write`
  放在解码循环（约每 500 ms），避免磁盘抖动打断音频读取而丢样本。

**闭环验收**：`SstvWavRecorderTest.录制一帧音频后可以重新解码出原图` —— 编码一帧 Robot 8 B/W →
按接收的分块方式流式录制 → 用 `SstvWavReader` 读回 → `SstvDecoder` 解码 → 断言模式、行数与 PSNR > 30 dB。

### F. HF Fax（WEFAX 天气传真）

与 SSTV 的**本质差异**（决定了它必须单独一条接收通路）：**没有 VIS、没有逐行同步脉冲**，
行节拍只能自由运行在「120 行/分钟」上；灰度单通道（1500 Hz 黑 / 2300 Hz 白）；
图像前有 5–10 秒 **300 Hz 启动音**；图像左侧有一条粗白边，可作为**水平**对齐基准
（垂直方向无解 —— 无逐行同步，最多半行偏移）。

**参数与做法**取自 robot36 `HFFax.java`：宽度 640（IOC 576 标称值之外留白边区）、
每行 0.5 s、逐行零相位像素 Nyquist 低通、逐列亮度指数滑动平均（衰减 0.99）取最亮列做水平移位。

**两个踩到的真坑**（都已写成回归用例）：

1. **上游解调器 `phaseSpan` 必须 ≤ 14，推荐 8。**
   300 Hz 距中心 1900 Hz 有 1600 Hz，而相位差分的无模糊范围是 `±fs/(2·span)`：
   48 kHz 下 span=8 时 300 Hz 读数正常（均值 300.000），**span=15 恰在数学边界上、
   直接折叠成 1940 Hz**，span=16 变成约 3300 Hz。因此**不能**用
   `recommendPhaseSpan(pixelSeconds·rate)`（0.5 s/640 算出 37.5 样本 → span 16 → 启动音必然检测失败）。
2. **低频音的瞬时值会抖、但均值极准。**
   300 Hz 的瞬时读数实测在 257.9–348.7 Hz（±45 Hz）摆动，而**均值误差 < 0.01 Hz**。原因是
   300 Hz 远在 1200 Hz 基带之外，混频镜像（约 2250 Hz）只被 8 阶低通压到 −42 dB。
   最初「瞬时值连续 2 秒落在 ±50 Hz 内」的实现因此**在失谐素材上永远凑不满**；
   最终改为「瞬时值走加宽门累计时长、是否真是启动音由**段内均值**判定」。

**验证**：`HfFaxDecoderTest` 10 个用例（灰度阶梯自环、启动音有/无 + `startImmediately`、
分块一致性逐像素、白边对齐、失谐 ±50 Hz 自动/手动、纯噪声、行数上限、NaN 与野值夹紧、
上面那个 span 折叠回归）。

**未验证（务必如实告知用户）**：全部是自写编码器 → 解调器 → 解码器的自环，
**没有碰过任何真实 WEFAX 信号**；启动音阈值（默认 2 秒 vs 真实 5–10 秒）、
启动音之后的 phasing 条纹（会被当图像解出，开头几行需人工忽略）、
真实图像的「白边是否仍是最亮列」、以及 1500/2300 Hz 的真实黑白标定，都未在真机上验证。
垂直对齐是 WEFAX 的固有限制，UI 已就此给出说明。

### G. 播放捕获（Android 10+）

**依据**：robot36 的 `AudioPlaybackCaptureSource` + `AudioCaptureService`。
价值在于：把 SSTV 录音放在任意播放器里播、或对着 WebSDR 的网页播放，HamKit 直接从系统的
播放混音取音 —— 比「扬声器 → 麦克风」少一层声学失真。

**实现**：
- 新增 `SstvAudioSource.kt`：把「从哪里拿音频」抽象出来（这是 robot36 的 `AudioSource`
  家族教给我们的**结构**），`MicrophoneAudioSource`（48k→44.1k 回退）与
  `PlaybackCaptureAudioSource`（`AudioPlaybackCaptureConfiguration`，匹配
  `USAGE_MEDIA`/`UNKNOWN`/`GAME`）两个实现，`SstvRecorder` 的采集/解调/连续接收/录音/瀑布图
  全部逻辑对两者复用；
- 新增 `SstvCaptureService.kt` + manifest 的 `FOREGROUND_SERVICE_MEDIA_PROJECTION`：
  **targetSdk ≥ 34 时必须在 `getMediaProjection()` 之前已有
  `foregroundServiceType="mediaProjection"` 的前台服务在运行**，否则抛 `SecurityException`。
  UI 的顺序是「先起服务 → 请求投影授权 → 拿到投影切音源」。

**未验证**：**只做了编译验证**。真机上的授权弹窗、被捕获应用的 `allowAudioPlaybackCapture`
拒绝、以及 Android 15 的重复授权要求都未验证。UI 已提示「录制其它应用音频」这一行为。

### H. 瀑布图（示波器）

**依据**：robot36 的 `postProcessScopeImage` 与 `scopeBufferWidth`。

**实现**：新增 `SstvWaterfall.kt`（纯 Kotlin，9 个用例）——**不是 FFT 频谱图**，而是把
逐样本瞬时频率按 4 ms 一行、1500 Hz 跨度 96 格做累加。理由是：SSTV 是单载波模拟调频，
任一时刻只有一个瞬时频率，**逐样本频率本身就是最忠实的谱图**，而真正的 FFT 谱图每秒要
做 1.2 万次 2048 点 FFT，代价高一个数量级却看不出更多信息。配色是「频率 → 色相」的彩虹映射
（灰度下无法一眼分清 1200 Hz 同步线与 1500 Hz 黑电平，而那正是判断失谐的关键）。

`SstvRecorder.pumpFrequencies()` 把**同一条**频率流同时喂给解码器与瀑布图，因此用户看到的
波形与实际用于出图的内容必然一致 —— 不会出现「看着有信号却解不出」的错觉。

---

## 三、明确**没有**采纳的部分

| 项 | 理由 |
|---|---|
| Pasokon P3/P5/P7（`sstv` crate 有，VIS 113/114/115） | 顺带发现的额外模式族（640×496，**同步在行尾**、R/G/B 顺序、8.33/6.25/4.17 ms 时间单位）。参数查得到，但属于本次范围之外；已记录在此作为后续候选。**注意** pySSTV 把 P7 的 VIS 写成 `0xF3`（>127，不可能是 7 位 VIS 码），crate 给的是 115 = 0x73 —— 若日后实现，以 crate 为准 |
| AVT 系列 | 与既有判断一致：资料稀缺、收益低于查证成本 |
| HFFax 发射 | WEFAX 接收是绝对主流用法；发射需要另一套「图像 → 天气传真音频」的编码器，收益低 |
| 文件「边听边解」（robot36 的 File 模式 + Turbo） | HamKit 的「导入录音」已经是 Turbo 语义（一次性解完，不等实时），缺的只是「边听边解」的实时回放，价值不高 |
| 移植 robot36 的 JNI/C 路径 | 违反既有约束（构建复杂度 + APK 体积），且纯 Kotlin 路线已被证明可行 |
| 24 位 FLAC 的精度 | robot36 README 指出部分设备会把 24 位 FLAC 输出成 16 位 PCM。这是平台行为，HamKit 通过累加器按**输出格式**解释样本来正确应对，但无法恢复已丢失的位深 |

---

## 四、本轮沉淀的几条通用经验

1. **「按序号推断」是模拟模式解码里最危险的习惯。** 行奇偶、帧起点、色度配对都属于「信号里
   本来就带着答案」的信息，用序号猜只在「录音恰好从帧首开始」时成立。robot36 从分隔脉冲读奇偶，
   这一点上比我们原先的实现健壮。
2. **带限要问「谁在前面」。** 两级低通串联时，后加的那一级收益取决于前一级的带宽；
   在把别人的滤波器搬进来之前，先量清楚自己链路里已有的带宽。
3. **照抄参数要交叉验证。** 本次 Wraase 与 PD 族的全部参数都拿到了两到三个独立来源的一致确认；
   而 Robot 12 B/W 这类找不到第二来源的，就只有推断值，且在 KDoc 里显式标注为推断。
4. **低频音在窄带鉴频器里是「瞬时抖、均值稳」。** 任何时候要用「频率是否落在某窗口内」
   做判据，都该先看均值而不是瞬时值（尤其是远离基带中心的音）。
5. **平台 API 的参数约束要写进文档。** `phaseSpan ≤ 14` 这种「不写下来就一定会再踩」的
   上游约束，最终进入了 `HfFaxDecoder` 的类 KDoc 与一条专门的回归用例。

## 五、参考来源

| 来源 | 用途 |
|---|---|
| [xdsopl/robot36](https://github.com/xdsopl/robot36)（0BSD） | 行奇偶判定、零相位像素 Nyquist 低通、Wraase 参数、HF Fax 做法、播放捕获结构、示波器思路 |
| [sstv crate](https://docs.rs/sstv/latest/sstv/)（Rust，Dayton 论文逐行转写） | PD / Wrasse 时序与全族 VIS 码的独立交叉验证 |
| [pySSTV](https://github.com/dnet/pySSTV) | Robot B/W 参数、Pasokon 结构、Wraase 的旁证 |
| slowrx `modespec` / `vis` | 一期即已采用的权威来源（见 REQUIREMENT_SSTV.md 附录 A） |
