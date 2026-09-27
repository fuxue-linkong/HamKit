# SSTV 三期 Q0 开发文档：流式逐行解码器

> 编制日期：2026-09-27
> 上游：[SSTV_PHASE3_PLAN.md](SSTV_PHASE3_PLAN.md)（三期调研）§六 Q0
> 依据：当前 `miuix` 分支代码（HEAD `6fec027`）中 `SstvDecoder` / `SstvSync` / `SstvDemodulator` / `SstvMode` 的实际签名
> 状态：**设计完成，未开始编码**
> 预估：2 天（含 11 个单元测试）

---

## 一、目标与非目标

### 1.1 目标

提供一个**有状态**的 SSTV 解码器，支持：

1. **从任意时刻开始**：不要求音频从帧首开始，也不要求存在 VIS 头（模式由调用方给定）；
2. **增量输入**：可反复喂入任意长度的频率流片段，跨块保持状态；
3. **逐行输出**：每解完一行立即回调，供 UI 实时追加显示（对应三期「预览窗口变长」）；
4. **长时间连续**：状态占用有界，并向调用方报告「哪些样本已消费完、可以回收」。

### 1.2 非目标（明确划给后续任务）

| 不做 | 归属 |
|---|---|
| 频率流缓冲与内存回收策略 | Q1（本设计只**给出接口** `consumedSamples`） |
| 滚动预览 UI、图像保存 | Q2 |
| 重复帧识别与跨帧融合 | Q3–Q4 |
| 盲扫自动判定模式 | 二期 T5（本设计接受调用方给定的模式） |
| 改变解调算法（滤波器/相位差分参数） | 不在本任务范围，`SstvDemodulator` 保持现状 |

---

## 二、现状与可复用零件

设计的关键前提：**一期已经把绝大部分零件准备好了**，Q0 的主要工作是"把一次性流程改成有状态流程"，而不是重写 DSP。

| 零件 | 位置 | 复用方式 |
|---|---|---|
| 单行解码（通道采样 + 三族色彩组装 + Robot 行交替色度状态） | `SstvDecoder.decodeRadioLine()` <br>`app/src/main/java/com/example/hamkit/data/sstv/SstvDecoder.kt:177` | **直接复用**。它只依赖一个 `SstvSync.SyncFit` 参数即可定位并解出该行；唯一障碍是它目前是 `private`（见 §5 D1） |
| 行定位（拟合 + 逐行精调） | `SstvSync.SyncFit.lineStartRefined(line, syncSamples)` <br>`SstvSync.kt` | 直接复用。它会在 `pulseCenters` 里找预测位置最近的实测脉冲做精调 —— 流式版只需把"最近若干脉冲"放进 `pulseCenters` |
| 同步脉冲频率容差、行首偏移 | `SstvSync.SYNC_TOLERANCE_HZ`、`SstvMode.syncOffsetSeconds` | 复用常量与偏移定义（Scottie 行中同步已被一期解决） |
| 通道时序 / 写入顺序 | `SstvMode.channelTasks()`、`lineEvents()` | 不涉及（本任务只解码） |
| 有状态增量解调 | `SstvDemodulator.process(block)` | 直接复用；调用方（`SstvRecorder.pumpFrequencies`）已经这么用了 |
| 灰度量化与失谐补偿 | `SstvDecoder.sampleLevel()` | 复用（随 `decodeRadioLine` 一起） |

**当前 `decodeRadioLine` 的签名**（摘自源码）：

```kotlin
private fun decodeRadioLine(
    freqs: FloatArray,        // 频率流（下标 0 = 本次传入窗口的起点）
    fit: SstvSync.SyncFit,    // 行定位信息
    mode: SstvMode,
    line: Int,                // 无线行号（PD 族一行含两个图像行）
    hedrShiftHz: Double,
    pixels: IntArray,         // 输出：width × imageLines
    validFrom: Int,
    effectiveRate: Double,
): Boolean                    // false = 越界/同步丢失
```

**当前 `SyncFit` 的构造参数**（全部可由流式版自行产出）：

```kotlin
data class SyncFit(
    val pulseCount: Int,
    val firstLineStart: Double,     // 第 0 行的行首（同步起点）样本位置
    val lineSamples: Double,        // 实际行周期（样本）
    val nominalLineSamples: Double,
    val syncFrequencyHz: Double,    // 脉冲平均频率 → measuredShiftHz
    val pulseCenters: List<Double>, // 用于逐行精调
    val syncOffsetSamples: Double,
)
```

---

## 三、总体设计

### 3.1 双输出模式（本设计的核心决策）

流式与离线有一个**不可调和的差异**：离线解码用**全部**同步脉冲做一次最小二乘拟合，再据此解码每一行（非因果）；流式解码在处理第 k 行时只能用**前 k 个**脉冲（因果）。因此早期行的定位必然与离线不同。

与其二选一，不如**两个都要**：

| 模式 | 触发 | 定位依据 | 用途 | 质量 |
|---|---|---|---|---|
| **实时（streaming）** | 每次 `feed()` | 滚动窗口的增量回归（因果） | 边收边显示，低延迟 | 早期行略差，随行数增加迅速收敛 |
| **收尾（finalize）** | `finalize()` 显式调用 | 累积的全部脉冲做一次全局拟合（复用 `SstvSync.findSyncPulses`） | 用户停止/收满后的"最终版"、保存 | **与一期结果逐像素一致** |

这正好映射到三期的用法：**实时输出用于滚动预览，`finalize()` 用于保存与跨帧融合的输入**（融合要求各帧对齐一致，用 finalize 的结果最稳）。

### 3.2 数据流

```
麦克风/AudioRecord ──► SstvDemodulator.process() ──► 增量频率流
                                                      │
                                        SstvStreamDecoder.feed(freqs)
                                                      │
                        ┌─────────────────────────────┼──────────────────────────┐
                        ▼                             ▼                          ▼
              ① 流式脉冲检测                ② 滚动回归 + 失谐均值        ③ 逐行解码
              （1200 Hz 段状态机）          （在线最小二乘，O(1)/行）    （构造局部 SyncFit
                        │                             │                  → decodeRadioLine）
                        └──────────► 脉冲位置 ────────┘                          │
                                                                                 ▼
                                                                    onLine(lineIndex, IntArray)
                                                                                 │
                                                                   ④ 累积图像缓冲（动态高度）
                                                                                 │
                                        SstvStreamDecoder.finalize() ──► 全局重拟合 + 重解
                                                                          → 与一期一致的最终图
```

### 3.3 类与接口

```kotlin
package com.example.hamkit.data.sstv

/**
 * 流式逐行 SSTV 解码器（三期 Q0）。
 *
 * 与一次性解码的区别：
 *  - 有状态：跨 [feed] 调用保持脉冲检测、回归与色度行交替状态；
 *  - 任意起点：不要求 VIS 头，模式由构造参数给定；
 *  - 无限高度：输出行数不设上限，由调用方通过 [consumedSamples] 驱动内存回收。
 */
class SstvStreamDecoder(
    /** 采样率（Hz），与上游解调一致。 */
    private val sampleRate: Int,
    /** 已知模式（持续接收时来自手动锁定 / 盲扫 / 中途捕到的 VIS）。 */
    private val mode: SstvMode,
    /** 启动时的失谐量估计（Hz）；未捕获 VIS 时传 0，收敛后会由脉冲实测值取代。 */
    initialHedrShiftHz: Double = 0.0,
    /** 每解码完一行（PD 族为两个图像行）回调一次。 */
    private val onLine: (LineEvent) -> Unit,
) {

    /**
     * 一行解码结果。
     *
     * @param imageRow 图像行号（从 0 开始，持续增长，不因"一帧结束"而重置）
     * @param pixels 该行的打包 RGB（0xRRGGBB），长度 = [SstvMode.linePixels]
     * @param locked 该行是否由**实测同步脉冲**定位（false 表示用的是外推预测，
     *   调用方可用于质量筛选 —— 三期的跨帧融合需要这个信号）
     */
    data class LineEvent(val imageRow: Int, val pixels: IntArray, val locked: Boolean)

    /** 解码统计快照，供 UI 展示与质量筛选。 */
    data class Stats(
        val decodedLines: Int,
        val syncPulses: Int,
        val hedrShiftHz: Double,
        val slantRatio: Double,
        /** 最近 64 行的同步命中率（0–1），持续低于阈值可提示用户。 */
        val syncHitRate: Float,
        val lostSync: Boolean,
    )

    /**
     * 喂入一段**新的**频率流（必须是上次之后的新样本，长度任意、可为 0）。
     *
     * 内部会：检测同步脉冲 → 更新回归 → 在能确定行位置时逐行解码并回调。
     * 若尚未锁定（见 [Stats.lostSync]），只做脉冲搜索，不解码。
     */
    fun feed(freqs: FloatArray)

    /** 当前统计快照。 */
    fun stats(): Stats

    /**
     * 已消费的样本数：调用方可安全丢弃 `feed` 累计前 [consumedSamples] 个样本。
     *
     * Q0 只提供这个数字，不做缓冲管理；Q1 用它实现频率流滚动回收。
     */
    fun consumedSamples(): Long

    /**
     * 收尾：用累积的全部脉冲做一次全局拟合，重解所有行，得到与一期
     * [SstvDecoder.decodeFromFrequencies] 逐像素一致的最终图像。
     *
     * **要求调用方保留完整的频率流**（Q0 期间尚不回收）；Q1 落地后，finalize 将
     * 改为"基于已解码行的重定位"而非重解全部频率，届时接口不变。
     *
     * @return 最终图像；数据不足以定位时返回 null
     */
    fun finalize(): SstvDecoder.Result?

    /** 清空全部状态（重新开始一次持续接收）。 */
    fun reset()
}
```

---

## 四、核心算法

### 4.1 流式同步脉冲检测

与 `SstvSync.findSyncPulses` 的差别：那边是"一次扫描出全部脉冲"，这里是**跨块延续的段状态机**。

```kotlin
// 状态
private var inPulse = false
private var pulseStart = 0L        // 绝对样本位置
private var pulseFreqSum = 0.0     // 段内频率累加（用于事后取均值频率）
private var pulseFreqCount = 0
private val syncTargetHz = SstvMode.VIS_BREAK_HZ + hedrShiftHz   // 滚动更新

// 每个样本（freqs[i] 为绝对位置 absSample = baseSample + i）
if (abs(freqs[i] - syncTargetHz) <= TOLERANCE) {
    if (!inPulse) { inPulse = true; pulseStart = abs; freqSum = 0.0; count = 0 }
    freqSum += freqs[i]; count++
} else if (inPulse) {
    inPulse = false
    val length = abs - pulseStart
    if (length >= minPulseSamples) onPulseDetected(pulseStart, abs, freqSum / count)
    // 否则视为噪声毛刺，丢弃
}
```

要点：

- `TOLERANCE` 直接用 `SstvSync` 的 60 Hz（相对实测失谐）；
- `minPulseSamples = mode.syncSeconds × sampleRate × 0.45`（与一期 `MIN_PULSE_RATIO` 一致，避免把过渡带误判成脉冲）；
- **段内平均频率**是滚动失谐估计的输入（一期靠 `syncFrequencyHz`，这里同样）；
- 段尚未结束时不要提前判定 —— 输入块可能在脉冲中间断开，状态必须跨块保持（这就是 `inPulse` 等字段存在的意义）。

### 4.2 滚动回归与失谐估计

一期的 `findSyncPulses` 用**两个**脉冲做最小二乘得到 `(firstLineStart, lineSamples)`，并**刻意剔除第一个脉冲**（它可能与 VIS 停止位粘连）。流式版沿用同样思路，改为增量：

```kotlin
// 已接受脉冲的累积统计（k = 1, 2, 3, ... 序号，第 1 个脉冲被标记为"可能粘连"）
private var pulseK = 0L
private var sumK = 0.0; private var sumP = 0.0
private var sumKK = 0.0; private var sumKP = 0.0

// 脉冲被接受时（k 从 1 开始计数）
pulseK++
if (pulseK >= 2) {           // 第 1 个只用于占用序号，不参与回归（与一期剔除首脉冲一致）
    sumK += pulseK.toDouble(); sumP += center
    sumKK += pulseK.toDouble() * pulseK; sumKP += pulseK.toDouble() * center
    val n = (pulseK - 1).toDouble()
    val den = n * sumKK - sumK * sumK
    if (den > 0) {
        lineSamples = (n * sumKP - sumK * sumP) / den
        firstLineStart = (sumP - lineSamples * sumK) / n - syncOffsetSamples
    }
}
```

- **复杂度 O(1)/脉冲**（不重算历史）；
- 至少 4 个脉冲（与一期 `MIN_PULSES_FOR_FIT` 一致）后才认为**锁定**，之前只搜索不解码；
- `lineSamples` 需落在 `nominalLineSamples × [0.95, 1.05]`，越界说明串链错误 → 视为丢锁重搜（与一期同样的守卫）。

**滚动失谐估计**：维护最近 `SHIFT_WINDOW = 32` 个脉冲的平均频率（环形缓冲），`hedrShiftHz = 滑动均值 − 1200`。冷启动（< `MIN_PULSES_FOR_SHIFT_REFINE = 8`，与一期一致）时退化为 `initialHedrShiftHz`。

### 4.3 每行的局部 `SyncFit`

这是"复用一期"的关键 —— 每解一行前构造一个**只包含附近脉冲**的 `SyncFit`：

```kotlin
private fun fitForLine(line: Int): SstvSync.SyncFit {
    val nominal = mode.lineSeconds * sampleRate
    val predictedCenter = firstLineStart + line * lineSamples + syncOffsetSamples + mode.syncSeconds * sampleRate / 2
    // 只在预测位置 ±1.5 行周期内取脉冲，避免把远处的脉冲喂给 lineStartRefined
    val nearby = recentPulses.filter { abs(it - predictedCenter) <= nominal * 1.5 }
    return SstvSync.SyncFit(
        pulseCount = nearby.size,
        firstLineStart = firstLineStart,
        lineSamples = lineSamples,
        nominalLineSamples = nominal,
        syncFrequencyHz = SstvMode.VIS_BREAK_HZ + hedrShiftHz,
        pulseCenters = nearby,
        syncOffsetSamples = mode.syncOffsetSeconds * sampleRate,
    )
}
```

`SyncFit.lineStartRefined()` 会在 `nearby` 里找预测位置最近的脉冲；命中则用**实测位置**定位该行（`locked = true`），未命中则退回拟合预测（`locked = false`）。**`locked` 这个信号是给三期的跨帧融合做质量筛选用的**。

### 4.4 行解码

```kotlin
private fun decodeNextLine(): Boolean {
    val line = nextRadioLine
    val fit = fitForLine(line)
    val locked = fit.pulseCenters.any { /* 距离预测位置在容差内 */ }

    if (!decoder.decodeRadioLineStreaming(freqs, fit, line, hedrShiftHz, validFrom, effectiveRate, onLine)) {
        return false
    }
    nextRadioLine++
    return true
}
```

- `freqs` 是**内部维护的频率流窗口**（Q0 暂不回收，即整段保留；Q1 改为滚动窗口时，只需把索引基准一起平移，`decodeRadioLine` 无感）；
- PD 族 `imageRowsPerRadioLine == 2`，一次 `decodeRadioLine` 产出两行，回调两次（或一次回调含两行，见 §5 D5）；
- Robot 24/36 的跨行色度（`chromaCr/chromaCb`）状态**必须落在解码器实例上**，不能每次重建 —— 这也是它必须"有状态"的原因之一。

### 4.5 丢锁与重搜

| 情形 | 判定 | 处理 |
|---|---|---|
| 启动 | 无脉冲 | 持续搜索，不解码 |
| 连续 `LOST_SYNC_LINES = 3` 行都未命中实测脉冲 | 可能丢锁 | 标记 `lostSync = true`，**停止推进行号**，回到纯搜索态；重新集齐 4 个脉冲后从新位置继续解码（行号**继续累加**，保持"卷纸"语义） |
| `lineSamples` 越界 | 串链错误 | 清空回归统计，回到搜索态 |
| 输入块在脉冲中间断开 | `inPulse == true` | 状态跨块保持，不误判 |

---

## 五、关键设计决策

### D1：复用 `decodeRadioLine`，而不是为流式重写行解码

**决策**：把 `SstvDecoder.decodeRadioLine`（及 `sampleLevel`）从 `private` 改为 `internal`，由 `SstvStreamDecoder` 直接调用。

**理由**：
- 行解码里包含三族色彩组装、Robot 行交替色度、PD 双行配对、Scottie 行首偏移 —— 这些全部经过一期 28 个用例验证，重写等于放弃这些保障；
- 它的唯一输入是一个 `SyncFit`，"离线拟合"与"滚动拟合"都能造出来，**耦合点恰好就是正确的抽象边界**。

**反方案**：抽一个 `SstvLineDecoder` 类。职责更清晰，但要搬运状态（色度平面）与重构一期解码路径，收益不抵风险。若后续行解码要支持 B/W 等新模式，再考虑提取。

### D2：因果（实时）与非因果（finalize）并存

**决策**：`feed()` 用滚动回归出实时行；`finalize()` 用全部脉冲全局重拟合后重解。

**理由**：见 §3.1。若只做因果版，早期行定位偏差会一直留在画面里（用户看到"上半张歪、下半张正"）；若只做非因果版，则没有实时性，失去三期"预览窗口变长"的意义。

**代价**：`finalize()` 需要完整频率流（Q0 期间内存占用与一期的整帧缓冲相当）。Q1 落地滚动回收后，`finalize()` 改为"基于已解码行的重定位"（用每行的实测脉冲位置重建行表，不再重解频率），届时接口不变。

### D3：频率流所有权归调用方，解码器只报告消费进度

**决策**：解码器不持有缓冲、不做回收，只通过 `consumedSamples()` 报告"前 N 个样本已经不会再被读"。

**理由**：Q0 不背内存策略的锅。Q1 需要同时考虑"实时行已消费"与"finalize 需要全量"这对矛盾，属独立的决策点（很可能引入"保留全量直到 finalize 或用户保存"的策略）。

### D4：滚动最小二乘，而非经典 PLL

**决策**：用**在线最小二乘**（累积 `Σk, Σp, Σkk, Σkp`）而不是 `phase + period` 双环 PLL。

**理由**：
- 最小二乘在数学上**就是**一期拟合的增量形式，因此 `finalize()` 能与之严格对齐，验收标准可写成"逐像素一致"这种强条件；
- PLL 需要调增益（α/β），在不同模式的行周期（150 ms ~ 1050 ms）上难以用一套参数照顾，而最小二乘无需调参；
- 逐行抖动由 `lineStartRefined()` 的"取最近实测脉冲"吸收，这正是 PLL 想做的事，而一期已经验证过它的效果。

**代价**：最小二乘对**离群脉冲**（误检的图像内容）比 PLL 敏感。用两道防线缓解：`minPulseSamples` 长度门限 + 与预测位置偏差 > 0.5 行周期的脉冲直接丢弃。

### D5：回调粒度按"图像行"而非"无线行"

**决策**：`onLine` 对每个**图像行**回调一次，PD 族一次无线行回调两次。

**理由**：三期的用途是"预览窗口向下延长"，UI 以图像行为单位追加最自然；调用方不需要知道"无线行"这个内部概念。代价是 PD 族每行多一次回调（240 → 496 次/帧），开销可忽略。

---

## 六、边界情况清单

| # | 情况 | 期望行为 |
|---|---|---|
| 1 | 音频从图像中间开始（无 VIS） | 捕到第一个同步脉冲后开始；前几行用外推定位（`locked = false`），脉冲足够后转为实测定位 |
| 2 | 输入块恰好切在脉冲中间 | 状态跨块保持，不产生半个脉冲的误判 |
| 3 | 连续多行无同步（深衰落） | 3 行后标记 `lostSync`，停止推进；重新锁定后行号继续累加 |
| 4 | 图像内容出现接近 1200 Hz 的段落 | 由 `minPulseSamples` 长度门限过滤（图像亮度映射区间是 1500–2300 Hz，正常内容不会落在 1200 Hz） |
| 5 | Scottie（行中同步） | `syncOffsetSamples > 0`，行首在同步脉冲之前，`firstLineStart` 的换算已含该偏移 |
| 6 | 中途捕到 VIS（模式与当前不同） | 本解码器**不处理**；由调用方（`SstvRecorder`）决定是否用新模式重建解码器（保持单一职责） |
| 7 | 喂入 0 长度或极短块 | 直接返回，不改变状态 |
| 8 | `finalize()` 时数据不足以定位 | 返回 null，不抛异常 |
| 9 | 反复 `reset()` / `finalize()` | 幂等，状态干净 |

---

## 七、测试计划

全部为**纯 JVM** 用例（复用一期 `SstvCodecTest` 的素材生成辅助：编码 → 加噪 → 解码 → 比 PSNR），放进 `SstvStreamDecoderTest.kt`。

| # | 用例 | 断言 |
|---|---|---|
| 1 | `finalize 与一期解码逐像素一致` | `finalize()` 的 `pixels` 与 `SstvDecoder.decodeFromFrequencies()` 的结果 `contentEquals` |
| 2 | `实时输出与一期结果接近` | 喂完整帧后，实时累积图与一期结果的 PSNR ≥ 40 dB |
| 3 | `任意分块喂入结果一致` | 分别以 1024 / 4096 / 整段三种块长喂入，`finalize()` 结果一致 |
| 4 | `无 VIS 从头之后开始仍可解码` | 裁掉前 0.5 秒（VIS 段）后喂入，仍能逐行输出且行数 ≥ 90% |
| 5 | `手动模式启动（不依赖 VIS）` | 用 `mode` 参数直接启动，全程无 VIS 仍能出图 |
| 6 | `丢锁后重新锁定` | 人为插入 1.5 秒静音，`lostSync` 置位；之后恢复到正常解码 |
| 7 | `Scottie 行中同步的行首正确` | Scottie 1 自环，逐行位置与一期偏差 ≤ 1 样本 |
| 8 | `PD 族一次无线行回调两个图像行` | 回调次数 == `imageLines`，且两次回调的 `imageRow` 连续 |
| 9 | `Robot 行交替色度跨行正确` | Robot 36 自环，第 0/1 行的色度沿用关系与一期一致 |
| 10 | `长输入的状态占用有界` | 喂入 10 分钟等效音频，`stats()` 与内部累积统计不随行数线性增长 |
| 11 | `极端输入不崩溃` | 全零、纯噪、超短（< 100 样本）、空数组 |

---

## 八、实施步骤（三个可提交增量）

| 步骤 | 内容 | 产出 | 验证 |
|---|---|---|---|
| **S1 骨架与 finalize** | 把 `decodeRadioLine`/`sampleLevel` 改 `internal`；实现 `SstvStreamDecoder` 的 `feed/finalize/reset/stats` 骨架（`finalize` 直接委托给一期 `findSyncPulses` + 逐行解码）；`feed` 先只做缓冲 | 能通过用例 1、3、11 | 提交可编译、可测试 |
| **S2 流式跟踪** | 实现脉冲段状态机 + 在线最小二乘 + 滚动失谐 + 逐行回调（含 `locked` 标记） | 用例 2、4、5、6、7、8、9 | 重点回归：一期 28 个用例不得失败 |
| **S3 收尾与边界** | `consumedSamples()`、丢锁重搜、长输入状态检查、边界处理 | 用例 10 及其余 | 全绿后合入 |

**每步都必须保证一期 28 个用例继续通过**（`decodeRadioLine` 由 `private` 改 `internal` 不影响行为，但要在 S1 验证一次）。

---

## 九、性能预算

| 环节 | 开销 | 说明 |
|---|---|---|
| 脉冲检测 | O(1)/样本 | 一次比较 + 少量累加 |
| 滚动回归 | O(1)/脉冲 | 增量累积，不重算历史 |
| `fitForLine` | O(R)/行，R = 附近脉冲数（≤ 8） | 每次构造一个小对象；可优化为复用 |
| 行解码 | O(width × 通道数)/行 | 与一期相同（Robot 36：320×2；PD-120：640×4） |
| `finalize()` | O(总样本) 一次 | 与一期一次性解码同量级 |

**总体**：`feed` 的持续开销与一期 `decodeFromFrequencies` 的"每轮重解"相比是**数量级下降**（一期为 O(n²)）；峰值内存与一期相当（Q0 不回收频率流）。

---

## 十、风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 1 | 在线最小二乘对离群脉冲敏感 | 行周期被带偏 → 整幅歪斜 | 长度门限 + 偏差 > 0.5 行周期即丢弃 + `lineSamples` 合法性守卫（越界则重置回归） |
| 2 | 冷启动阶段行定位偏差 | 开头几行歪 | `locked` 标记如实上报；`finalize()` 会修正；UI 可对早期行淡化显示 |
| 3 | `finalize()` 需全量频率流 | Q0 阶段内存与一期相当，长时间接收不可行 | 与 Q1 配套；Q0 的定位是"跑通流式"，长时间连续依赖 Q1 |
| 4 | 与一期行为漂移 | 破坏既有 28 个用例 | S1 就把 `finalize` 与一期结果做逐像素一致性测试，作为长期护栏 |
| 5 | 不同模式行周期差异大（150–1050 ms） | minPulseSamples 等常量需按模式取 | 全部由 `mode.syncSeconds`/`lineSeconds` 推导，不写死绝对值 |

---

## 附录 A：需要改动的现有代码（最小化）

| 文件 | 改动 | 理由 |
|---|---|---|
| `SstvDecoder.kt` | `decodeRadioLine`、`sampleLevel` 的可见性 `private` → `internal` | 供流式解码器复用（D1） |
| `SstvDecoder.kt` | 可选：把"写固定 `pixels` 数组"改为"返回该行像素 + 写入外部数组"两种入口 | 流式需要逐行取走；但为最小改动，**允许先传入一个长度为 `width` 的临时行缓冲**并自行拼接，S1 再视情况优化 |
| 新增 | `data/sstv/SstvStreamDecoder.kt` | 本任务主体 |
| 新增 | `app/src/test/.../SstvStreamDecoderTest.kt` | §七 的 11 个用例 |

**不需要改动**：`SstvDemodulator`、`SstvSync`、`SstvMode`、`SstvVis`、`SstvColor`、`SstvRecorder`（Q2 才接入）。

## 附录 B：与后续任务的接口契约

| 后续任务 | 依赖本设计的什么 | 契约 |
|---|---|---|
| Q1 滚动回收 | `consumedSamples()` | 只回收该位置之前的频率样本；finalize 语义届时调整（§5 D2） |
| Q2 滚动预览 | `onLine(LineEvent)` | 按 `imageRow` 追加绘制；`locked=false` 的行可弱化显示 |
| Q3 重复帧识别 | `finalize()` 的 `Result` | 融合输入统一用 finalize 结果（对齐一致） |
| Q4 融合质量筛选 | `Stats.syncHitRate`、`LineEvent.locked`、`Stats.slantRatio` | 作为"该帧是否值得参与融合"的判据（对应三期实验二的教训） |
