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
}

/** 行内同步脉冲的位置，决定行首对齐模型。 */
enum class SstvSyncPosition {
    /** 同步脉冲位于行的最前端（PD / Robot / Martin）。 */
    LINE_START,

    /** 同步脉冲位于行中（Scottie：介于 B 与 R 之间）。 */
    SCOTTIE,
}

/**
 * SSTV 模式参数表（权威来源：slowrx `modespec.c`）。
 *
 * 参数逐项核对自 slowrx 的 modespec.rs（Oona Räisänen，ISC License），
 * VIS 码源自 Dave Jones (KB4YZ) 1998 年《List of SSTV Modes with VIS Codes》。
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
) {
    /** Robot 24（黑白兼容的快速彩色模式）。 */
    ROBOT_24(
        visCode = 0x04, displayName = "Robot 24", linePixels = 320, imageLines = 240,
        lineSeconds = 0.150, syncSeconds = 0.009, porchSeconds = 0.003,
        pixelSeconds = 0.0001375, separatorSeconds = 0.006,
        layout = SstvChannelLayout.ROBOT_YUV, syncPosition = SstvSyncPosition.LINE_START,
    ),

    /** Robot 36：UV 段与卫星最常用的快速模式（36 秒/帧）。 */
    ROBOT_36(
        visCode = 0x08, displayName = "Robot 36", linePixels = 320, imageLines = 240,
        lineSeconds = 0.150, syncSeconds = 0.009, porchSeconds = 0.003,
        pixelSeconds = 0.0001375, separatorSeconds = 0.006,
        layout = SstvChannelLayout.ROBOT_YUV, syncPosition = SstvSyncPosition.LINE_START,
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
                // Martin：Sync + Porch + G + Sep + B + Sep + R
                SstvSyncPosition.LINE_START -> listOf(
                    SstvChannelTask(SstvChannelRole.GREEN, uniformStart(0), linePixels, pixelSeconds),
                    SstvChannelTask(SstvChannelRole.BLUE, uniformStart(1), linePixels, pixelSeconds),
                    SstvChannelTask(SstvChannelRole.RED, uniformStart(2), linePixels, pixelSeconds),
                )

                // Scottie：[sep][G][sep][B][SYNC][porch][R]（同步位于行中）
                //
                // 逐项对照 slowrx `mode_scottie.rs` 的 ChanStart：
                //   G = septr, B = 2·septr + chanLen, R = 2·septr + 2·chanLen + sync + porch
                // 注意行首本身就是一个分隔脉冲（曾被漏掉，导致整行相位差 1 个 sep）。
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
                // Robot 24/36：Y 通道占 2× 像素时间
                listOf(
                    sync(), gap(porchSeconds),
                    channel(SstvChannelRole.Y, pixelSeconds = pixelSeconds * 2.0),
                    gap(separatorSeconds),
                    channel(SstvChannelRole.CHROMA),
                )
            }

            SstvChannelLayout.RGB_SEQUENTIAL -> when (syncPosition) {
                // Martin：[Sync][Porch][G][sep][B][sep][R]
                SstvSyncPosition.LINE_START -> listOf(
                    sync(), gap(porchSeconds),
                    channel(SstvChannelRole.GREEN), gap(separatorSeconds),
                    channel(SstvChannelRole.BLUE), gap(separatorSeconds),
                    channel(SstvChannelRole.RED),
                )

                // Scottie：[sep][G][sep][B][SYNC][porch][R]
                SstvSyncPosition.SCOTTIE -> listOf(
                    gap(separatorSeconds), channel(SstvChannelRole.GREEN),
                    gap(separatorSeconds), channel(SstvChannelRole.BLUE),
                    sync(), gap(porchSeconds),
                    channel(SstvChannelRole.RED),
                )
            }
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

        /** 本期可用于解码的模式（按推荐优先级排序，UI 直接复用）。 */
        val DECODABLE_MODES: List<SstvMode> =
            entries.filter { it.decodable }.sortedBy { it.frameSeconds }

        /** 按 VIS 码查模式；未定义或未录入的码返回 null。 */
        fun fromVisCode(visCode: Int): SstvMode? =
            entries.firstOrNull { it.visCode == visCode }
    }
}
