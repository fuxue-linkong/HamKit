package com.example.hamkit.data.sstv

/**
 * 通道在无线行中承担的角色。
 *
 * 命名与 slowrx 的通道顺序一一对应，解码器据此把各通道样本组装成图像行。
 */
enum class SstvChannelRole {
    /** PD 帧内的奇数行亮度 */
    Y_ODD,

    /** PD 帧内共享的 Cr（V）色度 */
    CR,

    /** PD 帧内共享的 Cb（U）色度 */
    CB,

    /** PD 帧内的偶数行亮度 */
    Y_EVEN,

    /** Robot 24/36 的单亮度通道 */
    Y,

    /** Robot 24/36 的行交替色度通道（偶行 Cr / 奇行 Cb） */
    CHROMA,

    /** Robot 72 的 U 分量（等价 Cr） */
    U,

    /** Robot 72 的 V 分量（等价 Cb） */
    V,

    /**
     * 黑白（灰度）模式的唯一通道。
     *
     * Robot B/W 系列每行只发一个亮度通道，既无色彩空间转换也无通道配对，
     * 因此单独一个角色比复用 [Y] 更明确 —— 解码器据此走「单通道 → 灰度」分支。
     */
    MONO,

    /** RGB 顺序模式的绿通道 */
    GREEN,

    /** RGB 顺序模式的蓝通道 */
    BLUE,

    /** RGB 顺序模式的红通道 */
    RED,
}

/**
 * 一个无线行内某通道的解调任务。
 *
 * @param role 通道角色
 * @param startSeconds 通道起始时刻，相对该无线行的同步脉冲起点（秒）
 * @param pixels 该通道的像素数
 * @param pixelSeconds 该通道每个像素占用的时长（秒）
 */
data class SstvChannelTask(
    val role: SstvChannelRole,
    val startSeconds: Double,
    val pixels: Int,
    val pixelSeconds: Double,
)

/**
 * 无线行内的一个音频事件（**编码**视角）。
 *
 * [SstvMode.channelTasks] 描述「通道位于何处」，本序列描述「按什么顺序写入」，
 * 因此能表达 Scottie 特有的「同步脉冲夹在 B 与 R 之间」结构 —— 这是编码器唯一
 * 需要区分的差异。
 *
 * **不变量**：按顺序累加 [Channel] 事件得到的起始时刻，必须与
 * [SstvMode.channelTasks] 中各任务的 `startSeconds` 逐一相等（有单元测试守护）。
 */
sealed interface SstvLineEvent {
    /** 1200 Hz 行同步脉冲。 */
    data class Sync(val seconds: Double) : SstvLineEvent

    /** 黑电平间隔（porch 或通道分隔脉冲）。 */
    data class Gap(val seconds: Double) : SstvLineEvent

    /**
     * 携带**行奇偶**的分隔脉冲（Robot 24/36 专有）。
     *
     * 时间轴上与 [Gap] 等价（同样占用 [seconds]），区别只在频率：偶行发 [evenHz]、
     * 奇行发 [oddHz]。解码端据此判定当前行的色度分量是 Cr 还是 Cb，而不是靠行序号猜。
     */
    data class ParityMarker(
        val seconds: Double,
        val evenHz: Double,
        val oddHz: Double,
    ) : SstvLineEvent

    /** 一个图像通道。 */
    data class Channel(val task: SstvChannelTask) : SstvLineEvent
}

/** 模式族的像素布局，决定解码器如何把通道组装成图像行。 */
enum class SstvChannelLayout {
    /** PD 族：一个无线行 = Y(奇行) + Cr + Cb + Y(偶行)，色度供相邻两行共用。 */
    PD_FRAME,

    /** Robot 族：YUV。24/36 为「Y(2× 像素时间) + 行交替色度」，72 为「Y + U + V」。 */
    ROBOT_YUV,

    /** RGB 顺序族：Martin 与 Scottie，三通道逐行顺序发送。 */
    RGB_SEQUENTIAL,

    /**
     * 单通道灰阶族：Robot B/W 系列。
     *
     * 行结构为 `Sync + Porch + 单通道`，解码时把唯一通道的值同时写入 R/G/B
     * 三分量（即直接输出灰度），不做任何色彩空间转换。
     */
    MONO,
}

/** 行内同步脉冲的位置，决定行首对齐模型。 */
enum class SstvSyncPosition {
    /** 同步脉冲位于行的最前端（PD / Robot / Martin / Wraase）。 */
    LINE_START,

    /** 同步脉冲位于行中（Scottie：介于 B 与 R 之间）。 */
    SCOTTIE,
}

/**
 * RGB 顺序族里三个通道的**发送顺序**。
 *
 * 这不是可有可无的细节：Martin 与 Scottie 发的是 **G→B→R**，而 Wraase SC2-180 发的是
 * **R→G→B**（依据 [sstv crate `wrasse.rs`](https://docs.rs/crate/sstv/0.1.1/source/src/modes/wrasse.rs)
 * 的 `Step::scan(Channel::Red/Green/Blue)` 顺序，与 robot36 的 `RGBModes.Wraase_SC2_180`
 * 一致）。若把 Wraase 按 G→B→R 解，整幅图会红绿蓝互换。
 */
enum class SstvRgbOrder(val roles: List<SstvChannelRole>) {
    /** Martin / Scottie：G → B → R。 */
    GBR(listOf(SstvChannelRole.GREEN, SstvChannelRole.BLUE, SstvChannelRole.RED)),

    /** Wraase SC2-180：R → G → B。 */
    RGB(listOf(SstvChannelRole.RED, SstvChannelRole.GREEN, SstvChannelRole.BLUE)),
}

/**
 * Robot 24/36 的**行奇偶标记**（分隔脉冲）。
 *
 * **为什么必须有它**：Robot 24/36 每行只发一个色度分量（偶行 Cr、奇行 Cb），解码端
 * 必须知道当前行是偶行还是奇行，否则 Cr/Cb 互换、整幅图红蓝颠倒。而「第几行」这件事
 * **不能靠行序号推断** —— 录音完全可能从奇数行开始（ISS 过境时手动按录音键就必然如此），
 * 那样行序号 0 实际是奇数行。
 *
 * 标准做法是让标记本身携带奇偶信息：**偶行的分隔脉冲发 1500 Hz（黑）、奇行发 2300 Hz（白）**。
 * 这一点由 robot36 的 `Robot_36_Color`（`even-line separator pulse` / `odd-line separator pulse`）
 * 与 `sstv` crate 的 `robot.rs`（`ROBOT_36_EVEN_SEQUENCE` 用 1500 Hz、`ROBOT_36_ODD_SEQUENCE`
 * 用 2300 Hz）**两处独立实现一致确认**。
 *
 * @param startSeconds 标记起点，相对行首（秒）
 * @param seconds 标记时长（秒）
 * @param evenHz 偶行的标记频率（Hz）
 * @param oddHz 奇行的标记频率（Hz）
 */
data class SstvParityMarker(
    val startSeconds: Double,
    val seconds: Double,
    val evenHz: Double,
    val oddHz: Double,
)

/**
 * Robot 24/36 的行奇偶标记：位于 Y 与色度通道之间，起点 = `sync + porch + 2·chanLen`
 * = 9 + 3 + 88 = **100 ms**，时长即分隔脉冲长度 6 ms。
 *
 * 频率取值来自两处独立实现（robot36 `Robot_36_Color`、`sstv` crate `robot.rs`）：
 * **偶行 1500 Hz（黑）、奇行 2300 Hz（白）**。
 *
 * 刻意放在**文件顶层**而不是 [SstvMode] 的伴生对象里：枚举项在伴生对象之前初始化，
 * 在条目里引用伴生对象成员会编译失败（"Companion object ... is uninitialized here"）。
 */
private val ROBOT_PARITY_MARKER = SstvParityMarker(
    startSeconds = 0.009 + 0.003 + 2 * 320 * 0.0001375,
    seconds = 0.006,
    evenHz = 1500.0,
    oddHz = 2300.0,
)

/**
 * SSTV 模式参数表（权威来源：slowrx `modespec.c` + Dayton 论文模式表）。
 *
 * 参数逐项核对自 slowrx 的 modespec.rs（Oona Räisänen，ISC License）与
 * [sstv crate](https://docs.rs/sstv/latest/sstv/)（Rust，Dayton 论文逐行转写），
 * VIS 码源自 Dave Jones (KB4YZ) 1998 年《List of SSTV Modes with VIS Codes》。
 * 三处来源在 VIS 码上完全一致（PD-50=93 / PD-90=99 / PD-120=95 / PD-160=98 /
 * PD-180=96 / PD-240=97 / PD-290=94 / Wraase SC2-180=55）。
 *
 * **时间轴模型**（与 slowrx `video.c` 的通道公式一致）：
 * ```
 * 通道 n 的起始 = sync + porch + n × (chanLen + separator)
 * 其中 chanLen = linePixels × pixelSeconds（Robot 24/36 的 Y 通道为 2× 像素时间）
 * ```
 * 每个模式的「行周期 = 各通道时长之和」均已反推验证（见 docs/REQUIREMENT_SSTV.md §3.4）。
 *
 * @param visCode VIS 码（7 位有效数据 + 偶校验）
 * @param displayName 展示名
 * @param linePixels 可见图像宽度（像素）
 * @param imageLines 每帧可见图像行数
 * @param lineSeconds 一个无线行的总时长（含同步与间隙，秒）
 * @param syncSeconds 同步脉冲时长（秒）
 * @param porchSeconds 同步后稳定间隔（秒）
 * @param pixelSeconds 基准像素时长（秒）
 * @param separatorSeconds 通道间分隔脉冲时长（秒）
 * @param rgbOrder RGB 顺序族的通道发送顺序（仅 [SstvChannelLayout.RGB_SEQUENTIAL] 有效）
 * @param parityMarker 行奇偶标记（仅 Robot 24/36 有；其余模式为 null）
 */
enum class SstvMode(
    val visCode: Int,
    val displayName: String,
    val linePixels: Int,
    val imageLines: Int,
    val lineSeconds: Double,
    val syncSeconds: Double,
    val porchSeconds: Double,
    val pixelSeconds: Double,
    val separatorSeconds: Double,
    val layout: SstvChannelLayout,
    val syncPosition: SstvSyncPosition,
    val rgbOrder: SstvRgbOrder = SstvRgbOrder.GBR,
    val parityMarker: SstvParityMarker? = null,
) {
    /**
     * Robot 8 B/W：最快的黑白模式，弱信号下比彩色模式稳得多。
     *
     * 参数取自 pySSTV `grayscale.py`（`Robot8BW`：WIDTH=160 / HEIGHT=120 /
     * SYNC=7 ms / SCAN=60 ms，无 porch），并用「行周期 × 行数 = 8.04 s ≈ 标称 8 s」
     * 反验通过。VIS 码 `0x02`。
     */
    ROBOT_8_BW(
        visCode = 0x02, displayName = "Robot 8 B/W", linePixels = 160, imageLines = 120,
        lineSeconds = 0.067, syncSeconds = 0.007, porchSeconds = 0.0,
        pixelSeconds = 0.000375, separatorSeconds = 0.0,
        layout = SstvChannelLayout.MONO, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /**
     * Robot 24 B/W：320 宽的黑白模式，总时长 24 s。
     *
     * 参数取自 pySSTV `grayscale.py`（`Robot24BW`：320×240 / SYNC=7 ms /
     * SCAN=93 ms）。`(0.007 + 0.093) × 240 = 24.000 s` 精确等于标称时长，是该组
     * 参数自洽的有力佐证。VIS 码 `0x0A`。
     */
    ROBOT_24_BW(
        visCode = 0x0A, displayName = "Robot 24 B/W", linePixels = 320, imageLines = 240,
        lineSeconds = 0.100, syncSeconds = 0.007, porchSeconds = 0.0,
        pixelSeconds = 0.000290625, separatorSeconds = 0.0,
        layout = SstvChannelLayout.MONO, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /**
     * Robot 12 B/W：320×120 的黑白模式，总时长 12 s。
     *
     * **参数推断说明（与其余模式不同，此条为推导值）**：Robot B/W 族的 VIS 码按
     * `0x02 / 0x06 / 0x0A` 交错排布（对应 8 s / 12 s / 24 s，与彩色族的
     * `0x04 / 0x08 / 0x0C` 交错），故 `0x06` 即 12 秒档；该族 320 宽模式的扫描时长
     * 固定为 93 ms、同步 7 ms（同 [ROBOT_24_BW]），因此 12 秒档取 120 行：
     * `(0.007 + 0.093) × 120 = 12.000 s`。此模式同时是**奇校验 VIS 的唯一使用者**
     * （slowrx `vis.c:116` 对 `0x06` 翻转校验位，解码端 [SstvVisDetector] 已实现），
     * 属于既有代码中一直未被触发的分支。
     */
    ROBOT_12_BW(
        visCode = 0x06, displayName = "Robot 12 B/W", linePixels = 320, imageLines = 120,
        lineSeconds = 0.100, syncSeconds = 0.007, porchSeconds = 0.0,
        pixelSeconds = 0.000290625, separatorSeconds = 0.0,
        layout = SstvChannelLayout.MONO, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /**
     * Robot 24（黑白兼容的快速彩色模式）。
     *
     * 分隔脉冲携带**行奇偶**（偶行 1500 Hz / 奇行 2300 Hz），见 [SstvParityMarker]。
     * 分隔起点 = `sync + porch + 2·chanLen` = 9 + 3 + 88 = 100 ms。
     */
    ROBOT_24(
        visCode = 0x04, displayName = "Robot 24", linePixels = 320, imageLines = 240,
        lineSeconds = 0.150, syncSeconds = 0.009, porchSeconds = 0.003,
        pixelSeconds = 0.0001375, separatorSeconds = 0.006,
        layout = SstvChannelLayout.ROBOT_YUV, syncPosition = SstvSyncPosition.LINE_START,
        parityMarker = ROBOT_PARITY_MARKER,
    ),

    /** Robot 36：UV 段与卫星最常用的快速模式（36 秒/帧）。 */
    ROBOT_36(
        visCode = 0x08, displayName = "Robot 36", linePixels = 320, imageLines = 240,
        lineSeconds = 0.150, syncSeconds = 0.009, porchSeconds = 0.003,
        pixelSeconds = 0.0001375, separatorSeconds = 0.006,
        layout = SstvChannelLayout.ROBOT_YUV, syncPosition = SstvSyncPosition.LINE_START,
        parityMarker = ROBOT_PARITY_MARKER,
    ),

    /** Robot 72：三通道等宽，色度无行复制。 */
    ROBOT_72(
        visCode = 0x0C, displayName = "Robot 72", linePixels = 320, imageLines = 240,
        lineSeconds = 0.300, syncSeconds = 0.009, porchSeconds = 0.003,
        pixelSeconds = 0.0002875, separatorSeconds = 0.0047,
        layout = SstvChannelLayout.ROBOT_YUV, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /** Martin 2：Martin 1 的倍速版本。 */
    MARTIN_2(
        visCode = 0x28, displayName = "Martin 2", linePixels = 320, imageLines = 256,
        lineSeconds = 0.2267986, syncSeconds = 0.004862, porchSeconds = 0.000572,
        pixelSeconds = 0.0002288, separatorSeconds = 0.000572,
        layout = SstvChannelLayout.RGB_SEQUENTIAL, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /** Martin 1：HF 段最常见的 RGB 顺序模式。 */
    MARTIN_1(
        visCode = 0x2C, displayName = "Martin 1", linePixels = 320, imageLines = 256,
        lineSeconds = 0.446446, syncSeconds = 0.004862, porchSeconds = 0.000572,
        pixelSeconds = 0.0004576, separatorSeconds = 0.000572,
        layout = SstvChannelLayout.RGB_SEQUENTIAL, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /** Scottie 2：Scottie 1 的倍速版本（同步位于行中）。 */
    SCOTTIE_2(
        visCode = 0x38, displayName = "Scottie 2", linePixels = 320, imageLines = 256,
        lineSeconds = 0.277692, syncSeconds = 0.009, porchSeconds = 0.0015,
        pixelSeconds = 0.0002752, separatorSeconds = 0.0015,
        layout = SstvChannelLayout.RGB_SEQUENTIAL, syncPosition = SstvSyncPosition.SCOTTIE,
    ),

    /** Scottie 1：经典 RGB 顺序模式（同步位于行中）。 */
    SCOTTIE_1(
        visCode = 0x3C, displayName = "Scottie 1", linePixels = 320, imageLines = 256,
        lineSeconds = 0.42838, syncSeconds = 0.009, porchSeconds = 0.0015,
        // modespec 的标称值是 0.4320 ms（4 位近似），会让「3 通道 + 2 分隔 + sync + porch」
        // 比行周期短 0.16 ms。此处按行周期反推精确值：(0.42838 − 0.003 − 0.009 − 0.0015) / 960
        pixelSeconds = 0.0004321666667, separatorSeconds = 0.0015,
        layout = SstvChannelLayout.RGB_SEQUENTIAL, syncPosition = SstvSyncPosition.SCOTTIE,
    ),

    /** Scottie DX：慢速高细节版本（同步位于行中）。 */
    SCOTTIE_DX(
        visCode = 0x4C, displayName = "Scottie DX", linePixels = 320, imageLines = 256,
        lineSeconds = 1.0503, syncSeconds = 0.009, porchSeconds = 0.0015,
        // 同理：modespec 标称 1.08053 ms 会使行超长 0.5 ms，按行周期反推为 1.0800 ms
        pixelSeconds = 0.00108, separatorSeconds = 0.0015,
        layout = SstvChannelLayout.RGB_SEQUENTIAL, syncPosition = SstvSyncPosition.SCOTTIE,
    ),

    /**
     * Wraase SC2-180：结构最简单的彩色模式 —— 同步 + porch 之后直接跟 R、G、B 三段，
     * **没有任何通道分隔脉冲**。
     *
     * 参数由两处独立来源核对一致：
     *  - `sstv` crate `modes/wrasse.rs`：`sync 5.5225 ms(1200 Hz) + porch 500 µs(1500 Hz)
     *    + Red 235 ms + Green 235 ms + Blue 235 ms`，256 行 × 711.0225 ms = 182 s；
     *  - robot36 `RGBModes.Wraase_SC2_180`：同样的 5.5225 / 0.0005 / 0.235 与 **R→G→B** 顺序。
     *
     * 与 Martin/Scottie 的两处关键差异（写错就会整幅偏色或错位）：
     *  1. **通道顺序是 R→G→B**（Martin/Scottie 是 G→B→R），故需 [SstvRgbOrder.RGB]；
     *  2. **分隔脉冲为 0**，三段首尾相接。
     *
     * VIS 码 `0x37`（=55，由上述两处来源一致给出）。
     */
    WRASSE_SC2_180(
        visCode = 0x37, displayName = "Wraase SC2-180", linePixels = 320, imageLines = 256,
        lineSeconds = 0.7110225, syncSeconds = 0.0055225, porchSeconds = 0.0005,
        pixelSeconds = 0.000734375, separatorSeconds = 0.0,
        layout = SstvChannelLayout.RGB_SEQUENTIAL, syncPosition = SstvSyncPosition.LINE_START,
        rgbOrder = SstvRgbOrder.RGB,
    ),

    /**
     * PD-50：PD 族最快的模式，早期卫星与部分短通联使用。
     *
     * 参数取自 `sstv` crate `modes/pd.rs`（PD_50 的每通道扫描时长 91.52 ms、
     * 320×256），并用「4 通道 + sync 20 ms + porch 2.08 ms = 388.16 ms/行，
     * 128 行 = 49.7 s」双向反验（crate 注释亦写明 "128 line pairs of 388.16ms
     * each: a 50 second transmission"）。VIS 码 `0x5D`。
     */
    PD_50(
        visCode = 0x5D, displayName = "PD-50", linePixels = 320, imageLines = 256,
        lineSeconds = 0.38816, syncSeconds = 0.020, porchSeconds = 0.00208,
        pixelSeconds = 0.000286, separatorSeconds = 0.0,
        layout = SstvChannelLayout.PD_FRAME, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /**
     * PD-90：320×256 的低速 PD 模式（每通道扫描 170.24 ms，总长 90 s）。
     *
     * 参数同上取自 `sstv` crate `modes/pd.rs`；`128 × 703.04 ms = 89.99 s`
     * 与标称 90 s 吻合。VIS 码 `0x63`。
     */
    PD_90(
        visCode = 0x63, displayName = "PD-90", linePixels = 320, imageLines = 256,
        lineSeconds = 0.70304, syncSeconds = 0.020, porchSeconds = 0.00208,
        pixelSeconds = 0.000532, separatorSeconds = 0.0,
        layout = SstvChannelLayout.PD_FRAME, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /**
     * PD-160：512×400 的中速 PD 模式（每通道扫描 195.584 ms，总长 161 s）。
     *
     * 像素时长 382 µs 与 PD-240 相同 —— 两者是同一采样率下的不同分辨率档位。
     * VIS 码 `0x62`。
     */
    PD_160(
        visCode = 0x62, displayName = "PD-160", linePixels = 512, imageLines = 400,
        lineSeconds = 0.804416, syncSeconds = 0.020, porchSeconds = 0.00208,
        pixelSeconds = 0.000382, separatorSeconds = 0.0,
        layout = SstvChannelLayout.PD_FRAME, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /**
     * PD-290：PD 族分辨率最高的模式（800×616，总长 289 s）。
     *
     * 单帧接近 5 分钟，只适合固定台长时间守听；收录它是为了让「模式表完整」，
     * UI 侧按帧时长排序后它自然排在最末。VIS 码 `0x5E`（94，与 pySSTV 的
     * `PD290.VIS_CODE`、slowrx modespec 及 KB4YZ VIS 表一致）。
     */
    PD_290(
        visCode = 0x5E, displayName = "PD-290", linePixels = 800, imageLines = 616,
        lineSeconds = 0.93728, syncSeconds = 0.020, porchSeconds = 0.00208,
        pixelSeconds = 0.000286, separatorSeconds = 0.0,
        layout = SstvChannelLayout.PD_FRAME, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /** PD-120：ARISS 现行 ISS SSTV 模式（145.800 MHz）。 */
    PD_120(
        visCode = 0x5F, displayName = "PD-120", linePixels = 640, imageLines = 496,
        lineSeconds = 0.50848, syncSeconds = 0.020, porchSeconds = 0.00208,
        pixelSeconds = 0.00019, separatorSeconds = 0.0,
        layout = SstvChannelLayout.PD_FRAME, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /** PD-180：PD-120 的高画质慢速版本。 */
    PD_180(
        visCode = 0x60, displayName = "PD-180", linePixels = 640, imageLines = 496,
        lineSeconds = 0.75424, syncSeconds = 0.020, porchSeconds = 0.00208,
        pixelSeconds = 0.000286, separatorSeconds = 0.0,
        layout = SstvChannelLayout.PD_FRAME, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /** PD-240：PD 族最慢、画质最高的模式。 */
    PD_240(
        visCode = 0x61, displayName = "PD-240", linePixels = 640, imageLines = 496,
        lineSeconds = 1.000, syncSeconds = 0.020, porchSeconds = 0.00208,
        pixelSeconds = 0.000382, separatorSeconds = 0.0,
        layout = SstvChannelLayout.PD_FRAME, syncPosition = SstvSyncPosition.LINE_START,
    ),
    ;

    /** 一个无线行承载多少个图像行：PD 族为 2，其余为 1。 */
    val imageRowsPerRadioLine: Int
        get() = if (layout == SstvChannelLayout.PD_FRAME) 2 else 1

    /** 整帧的无线行数。 */
    val radioLines: Int
        get() = imageLines / imageRowsPerRadioLine

    /** 整帧时长（秒），由参数表推导而非硬编码。 */
    val frameSeconds: Double
        get() = radioLines * lineSeconds

    /**
     * 解码器是否支持该模式。
     *
     * PD / Robot / Martin / Scottie 四族均已支持。Scottie 的同步脉冲位于行中
     * （介于 B 与 R 之间），解码器按 [syncOffsetSeconds] 把检测到的同步位置
     * 反推回行首，其余流程与其他族完全一致。
     */
    val decodable: Boolean
        get() = true

    /** 是否为单通道灰度模式（Robot B/W 族）。 */
    val isMonochrome: Boolean
        get() = layout == SstvChannelLayout.MONO

    /**
     * 一个无线行内「同步 + porch + 全部通道」实际占用的时长（秒）。
     *
     * 取各通道终点的最大值。作用是让「通道之和不得超过行周期」成为可断言的
     * 不变量：若参数表录入有误（例如把某通道的像素时长写大），逐行相位会不断
     * 漂移，而单看参数很难发现。单元测试对**所有**模式断言
     * `lineOccupiedSeconds <= lineSeconds`。
     */
    val lineOccupiedSeconds: Double
        get() = channelTasks().maxOf { it.startSeconds + it.pixels * it.pixelSeconds }

    /**
     * 同步脉冲起点相对行首的偏移（秒）。
     *
     * 除 Scottie 族外同步脉冲就在行首，偏移为 0；Scottie 的行布局是
     * `[sep][G][sep][B][SYNC][porch][R]`（依据 slowrx `mode_scottie.rs`），
     * 行首位于同步脉冲之前 `2·sep + 2·chanLen`。解码器据此把检测到的同步脉冲
     * 位置换算成行首，编码器则据此把同步脉冲插到正确位置。
     */
    val syncOffsetSeconds: Double
        get() = when (syncPosition) {
            SstvSyncPosition.LINE_START -> 0.0
            SstvSyncPosition.SCOTTIE -> 2.0 * separatorSeconds + 2.0 * linePixels * pixelSeconds
        }

    /**
     * 返回该模式一个无线行内的通道解调任务（按发送顺序）。
     *
     * 所有时间均为相对行首同步脉冲起点的秒数，公式与 slowrx `video.c` 一致。
     */
    fun channelTasks(): List<SstvChannelTask> {
        val chanLen = linePixels * pixelSeconds
        // 通用三通道等宽序列：通道 n 起点 = sync + porch + n ×(chanLen + sep)
        fun uniformStart(index: Int): Double =
            syncSeconds + porchSeconds + index * (chanLen + separatorSeconds)

        return when (layout) {
            // PD 帧：Y(奇行) → Cr → Cb → Y(偶行)，四个通道均全宽
            SstvChannelLayout.PD_FRAME -> listOf(
                SstvChannelTask(SstvChannelRole.Y_ODD, uniformStart(0), linePixels, pixelSeconds),
                SstvChannelTask(SstvChannelRole.CR, uniformStart(1), linePixels, pixelSeconds),
                SstvChannelTask(SstvChannelRole.CB, uniformStart(2), linePixels, pixelSeconds),
                SstvChannelTask(SstvChannelRole.Y_EVEN, uniformStart(3), linePixels, pixelSeconds),
            )

            SstvChannelLayout.ROBOT_YUV -> if (this == ROBOT_72) {
                // Robot 72：Y / U / V 三通道等宽
                listOf(
                    SstvChannelTask(SstvChannelRole.Y, uniformStart(0), linePixels, pixelSeconds),
                    SstvChannelTask(SstvChannelRole.U, uniformStart(1), linePixels, pixelSeconds),
                    SstvChannelTask(SstvChannelRole.V, uniformStart(2), linePixels, pixelSeconds),
                )
            } else {
                // Robot 24/36：Y 通道占 2× 像素时间，随后是行交替的单个色度通道。
                // 若按 1× 实现，每行只有 106 ms（标称 150 ms），图像会被挤压缩放。
                val yStart = syncSeconds + porchSeconds
                val chromaStart = yStart + chanLen * 2.0 + separatorSeconds
                listOf(
                    SstvChannelTask(SstvChannelRole.Y, yStart, linePixels, pixelSeconds * 2.0),
                    SstvChannelTask(SstvChannelRole.CHROMA, chromaStart, linePixels, pixelSeconds),
                )
            }

            SstvChannelLayout.RGB_SEQUENTIAL -> when (syncPosition) {
                // Martin / Wraase：Sync + Porch + 三段通道（顺序由 rgbOrder 决定，
                // Martin 是 G→B→R，Wraase 是 R→G→B；Wraase 的分隔为 0）
                SstvSyncPosition.LINE_START -> rgbOrder.roles.mapIndexed { index, role ->
                    SstvChannelTask(role, uniformStart(index), linePixels, pixelSeconds)
                }

                // Scottie：[sep][G][sep][B][SYNC][porch][R]（同步位于行中）
                //
                // 逐项对照 slowrx `mode_scottie.rs` 的 ChanStart：
                //   G = septr, B = 2·septr + chanLen, R = 2·septr + 2·chanLen + sync + porch
                // 注意行首本身就是一个分隔脉冲（曾被漏掉，导致整行相位差 1 个 sep）。
                // Scottie 只有 G→B→R 一种顺序，故此处不参与 rgbOrder 泛化。
                SstvSyncPosition.SCOTTIE -> {
                    val gStart = separatorSeconds
                    val bStart = 2.0 * separatorSeconds + chanLen
                    val rStart = 2.0 * separatorSeconds + 2.0 * chanLen + syncSeconds + porchSeconds
                    listOf(
                        SstvChannelTask(SstvChannelRole.GREEN, gStart, linePixels, pixelSeconds),
                        SstvChannelTask(SstvChannelRole.BLUE, bStart, linePixels, pixelSeconds),
                        SstvChannelTask(SstvChannelRole.RED, rStart, linePixels, pixelSeconds),
                    )
                }
            }

            // Robot B/W：Sync + Porch + 单个亮度通道（无分隔、无色度）
            SstvChannelLayout.MONO -> listOf(
                SstvChannelTask(SstvChannelRole.MONO, uniformStart(0), linePixels, pixelSeconds),
            )
        }
    }

    /**
     * 返回该模式一个无线行的音频事件序列（编码用）。
     *
     * 与 [channelTasks] 的时间轴严格等价，但按**写入顺序**表达，因此能容纳
     * Scottie 的「同步脉冲位于 B 与 R 之间」。
     */
    fun lineEvents(): List<SstvLineEvent> {
        fun sync() = SstvLineEvent.Sync(syncSeconds)
        fun gap(seconds: Double) = SstvLineEvent.Gap(seconds)
        fun channel(
            role: SstvChannelRole,
            pixels: Int = linePixels,
            pixelSeconds: Double = this.pixelSeconds,
        ) = SstvLineEvent.Channel(SstvChannelTask(role, 0.0, pixels, pixelSeconds))

        return when (layout) {
            // PD：[Sync][Porch][Y₁][Cr][Cb][Y₂]（PD 族分隔为 0）
            SstvChannelLayout.PD_FRAME -> listOf(
                sync(), gap(porchSeconds),
                channel(SstvChannelRole.Y_ODD), channel(SstvChannelRole.CR),
                channel(SstvChannelRole.CB), channel(SstvChannelRole.Y_EVEN),
            )

            SstvChannelLayout.ROBOT_YUV -> if (this == ROBOT_72) {
                listOf(
                    sync(), gap(porchSeconds),
                    channel(SstvChannelRole.Y), gap(separatorSeconds),
                    channel(SstvChannelRole.U), gap(separatorSeconds),
                    channel(SstvChannelRole.V),
                )
            } else {
                // Robot 24/36：Y 通道占 2× 像素时间；中间的分隔脉冲**携带行奇偶**
                // （偶行黑 1500 Hz / 奇行白 2300 Hz），因此发的是 ParityMarker
                // 而不是普通黑电平间隔 —— 这是「录音从奇数行开始时红蓝互换」的根治手段
                val marker = parityMarker
                buildList {
                    add(sync())
                    add(gap(porchSeconds))
                    add(channel(SstvChannelRole.Y, pixelSeconds = pixelSeconds * 2.0))
                    if (marker != null) {
                        add(SstvLineEvent.ParityMarker(marker.seconds, marker.evenHz, marker.oddHz))
                    } else {
                        add(gap(separatorSeconds))
                    }
                    add(channel(SstvChannelRole.CHROMA))
                }
            }

            SstvChannelLayout.RGB_SEQUENTIAL -> when (syncPosition) {
                // Martin / Wraase：[Sync][Porch][通道1][sep][通道2][sep][通道3]
                // 顺序由 rgbOrder 决定（Martin = G→B→R，Wraase = R→G→B，后者分隔为 0）
                SstvSyncPosition.LINE_START -> buildList {
                    add(sync())
                    add(gap(porchSeconds))
                    rgbOrder.roles.forEachIndexed { index, role ->
                        if (index > 0) add(gap(separatorSeconds))
                        add(channel(role))
                    }
                }

                // Scottie：[sep][G][sep][B][SYNC][porch][R]
                SstvSyncPosition.SCOTTIE -> listOf(
                    gap(separatorSeconds), channel(SstvChannelRole.GREEN),
                    gap(separatorSeconds), channel(SstvChannelRole.BLUE),
                    sync(), gap(porchSeconds),
                    channel(SstvChannelRole.RED),
                )
            }

            // Robot B/W：[Sync][Porch][灰度行]
            SstvChannelLayout.MONO -> listOf(
                sync(), gap(porchSeconds),
                channel(SstvChannelRole.MONO),
            )
        }
    }

    companion object {
        /** 黑电平对应频率（Hz）。 */
        const val BLACK_HZ: Double = 1500.0

        /** 白电平对应频率（Hz）。 */
        const val WHITE_HZ: Double = 2300.0

        /** VIS 的 leader / 分隔 / 位频率（Hz）。 */
        const val VIS_LEADER_HZ: Double = 1900.0
        const val VIS_BREAK_HZ: Double = 1200.0
        const val VIS_BIT_ONE_HZ: Double = 1100.0
        const val VIS_BIT_ZERO_HZ: Double = 1300.0

        /**
         * Robot 24/36 的行奇偶标记定义在文件顶层（见 [ROBOT_PARITY_MARKER]）：
         * 枚举项初始化早于伴生对象，在条目里引用伴生对象成员会编译失败。
         */

        /** 本期可用于解码的模式（按推荐优先级排序，UI 直接复用）。 */
        val DECODABLE_MODES: List<SstvMode> =
            entries.filter { it.decodable }.sortedBy { it.frameSeconds }

        /** 按 VIS 码查模式；未定义或未录入的码返回 null。 */
        fun fromVisCode(visCode: Int): SstvMode? =
            entries.firstOrNull { it.visCode == visCode }
    }
}
