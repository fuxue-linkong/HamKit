package com.example.hamkit.data.sstv

/**
 * 动态高度图像缓冲（SSTV 三期 Q1）。
 *
 * 上游：[docs/SSTV_PHASE3_PLAN.md](../../../../../../../../../docs/SSTV_PHASE3_PLAN.md) §3.2 ②
 *
 * 一期与二期解出的都是「固定高度」的一帧（`mode.imageLines`）。持续接收没有帧边界，
 * 画面像**卷纸**一样不断向下延长，因此需要一个「行号 → 像素」的稀疏缓冲：
 *
 *  - 行号是流水号（见 [SstvStreamDecoder] 类注释「行号语义」），可能因丢锁出现空洞；
 *  - 高度随时增长，但**有上限**（[maxRows]），到顶后丢弃最老的行并整体上移 —— 这正是
 *    「卷纸」的语义：新行不断从底部进入，旧行从顶部滚出；
 *  - 主线程（UI 是否要重建位图）与解码线程（追加行）都会调用，因此全方法加锁。
 *
 * ## 为什么不用「IntArray 按需翻倍」
 *
 * 调研方案 A（全内存动态数组）在 1 小时 Robot 36 下约 18 MB，但 PD-120 是 640 宽
 * 双倍像素、行周期也长，且 SSTV 可能在无人值守时开一晚上。方案 C 给出上限保护，
 * 这里把它实现成**环形缓冲**而不是「到顶就停」：到顶后旧行滚出，接收可以无限持续，
 * 内存恒定（`maxRows × width × 4` 字节）。
 *
 * 纯 Kotlin 实现，不依赖 Android API（位图转换在 UI 层）。
 *
 * @param width 图像宽度（像素），必须与模式的 [SstvMode.linePixels] 一致
 * @param maxRows 保留的最大行数。默认 20000 行：
 *   PD-120 的 640 宽下约 51 MB 像素，Robot 36 的 320 宽下约 25 MB，都在可接受范围；
 *   按 Robot 36 约 4 行/秒计，20000 行 ≈ 83 分钟，足够一次过境甚至一场活动。
 * @param maxHeightRows 单次导出位图的最大高度。位图高度受
 *   `Canvas` 最大尺寸与内存限制约束（实践上 9600 像素就要 320×9600×4 ≈ 12 MB），
 *   因此「整卷」导出时按行抽样压缩到该高度以内，而不是直接失败。
 */
class SstvRollingImage(
    val width: Int,
    val maxRows: Int = DEFAULT_MAX_ROWS,
    val maxHeightRows: Int = DEFAULT_MAX_BITMAP_ROWS,
) {

    init {
        require(width > 0) { "宽度必须为正：$width" }
        require(maxRows > 0) { "行数上限必须为正：$maxRows" }
        require(maxHeightRows > 0) { "导出高度上限必须为正：$maxHeightRows" }
    }

    private val lock = Any()

    /** 环形缓冲：`[head, height)` 区间内每一行都是有效数据（逻辑行号 = 缓冲下标）。 */
    private var rows = IntArray(minOf(maxRows, INITIAL_ROWS) * width)

    /** 已写入的行数（可超过 [maxRows]）；逻辑行号上限。 */
    private var height = 0

    /** 已滚出顶部的行数（逻辑行号 + [shift] = 累计追加序号）。 */
    private var shift = 0

    /**
     * 最老一行在 [rows] 里的环形下标。
     *
     * 未到顶时恒为 0（数据自上而下连续）；到顶后随每次追加前进一格。
     */
    private var writeIndex = 0

    /**
     * 已写入的行数（含滚出的），用于统计，也用于判断预览是否需要重建。
     *
     * 解码线程写、预览线程读，故为 `@Volatile`：长时间接收时缓冲到达上限后
     * `size` 不再增长，只能靠这个累计值判断「又有新行到了」。
     */
    @Volatile
    private var appended = 0

    /** 最近一次追加的行号（流水号），用于 UI 判断「有新行」。 */
    @Volatile
    var lastAppendedRow: Int = -1
        private set

    /** 当前有效行数（0..[maxRows]）。 */
    val size: Int get() = synchronized(lock) { height }

    /** 累计写入的行数（可能远大于 [size]，[size] 到达上限后仍增长）。 */
    val appendedRows: Int get() = appended

    /**
     * 按**流水号行号**回写一行（覆盖已存在的那一行）。
     *
     * 用途是「同一行解了第二遍」：调用方知道某个流水号对应哪一行，就直接覆盖它，
     * 而不是再追加一行让画面错位。与 [append] 的分工：**追加用 [append]**
     * （按接收顺序占位、到顶滚动），**改写用本方法**（按流水号定位、越界静默忽略）。
     *
     * @param firstAppendedRow 缓冲第 0 行对应的流水号（由调用方维护）
     * @param row 要写的流水号；落在已滚出或尚未到达的范围时忽略
     */
    fun writeRow(firstAppendedRow: Int, row: Int, pixels: IntArray) {
        require(pixels.size >= width) { "行像素长度 ${pixels.size} 小于宽度 $width" }
        synchronized(lock) {
            val index = row - firstAppendedRow
            if (index < 0 || index >= height) return
            System.arraycopy(pixels, 0, rows, ringIndex(index) * width, width)
        }
    }

    /** 是否已滚出过顶部行。 */
    val scrolled: Boolean get() = synchronized(lock) { shift > 0 }

    /**
     * 追加一行。
     *
     * 行号可以是流水号（可能跳号，例如丢锁后重新锁定），这里只按**追加顺序**占位：
     * 持续接收的画面对应「接收到的行依次排下来」，因此 [SstvStreamDecoder.LineEvent.imageRow]
     * 只作展示，不入账。到顶时丢弃最老的一行（用写指针回绕实现，不搬移数据）。
     *
     * 注意：**滚出过顶部之后缓冲不再是自上而下的连续内存**，因此 [toPixels] 与
     * [rowAt] 必须按环形下标读取。早期版本用 `arraycopy` 每行整体上移一次，行数到
     * 上限后每追加一行要搬 `maxRows × width × 4` ≈ 25 MB，实时接收下会直接卡死。
     *
     * @param pixels 该行像素（打包 RGB 0xRRGGBB），长度必须 ≥ [width]
     */
    fun append(pixels: IntArray) {
        require(pixels.size >= width) { "行像素长度 ${pixels.size} 小于宽度 $width" }
        synchronized(lock) {
            if (height < maxRows) {
                // 尚未到顶：追加到尾部（此时头部下标 == 0）
                if ((height + 1) * width > rows.size) {
                    // 缓冲未按上限分配（初始只给了 INITIAL_ROWS 行）——按需翻倍到上限
                    val grow = minOf(maxRows, maxOf(height + 1, height * 2))
                    rows = rows.copyOf(grow * width)
                }
                System.arraycopy(pixels, 0, rows, height * width, width)
                height++
            } else {
                // 已到顶：写指针回绕覆盖最老的一行，逻辑上等于「整体上移一行」。
                // 取模用缓冲实际容量而不是 maxRows —— 两者通常相等，但一旦不等，
                // 用 maxRows 取模会写出界（toPixels 也因此会有读不到的行）
                val capacity = rows.size / width
                System.arraycopy(pixels, 0, rows, writeIndex * width, width)
                writeIndex = (writeIndex + 1) % capacity
                shift++
            }
            appended++
        }
    }

    /**
     * 用一整幅图**覆盖**缓冲（结束一次接收后载入已完成的图像）。
     *
     * @param pixels 行优先像素，长度 ≥ `width * count`
     * @param count 行数
     */
    fun replaceAll(pixels: IntArray, count: Int) {
        synchronized(lock) {
            val n = count.coerceIn(0, maxRows)
            require(pixels.size >= width * n) { "像素长度不足以容纳 $n 行" }
            // 固定按上限分配：之后追加时不必再重新分配，读写都按 maxRows 取模，
            // 环形下标的含义在整个对象生命周期内保持不变
            rows = IntArray(maxRows * width)
            System.arraycopy(pixels, 0, rows, 0, n * width)
            height = n
            shift = 0
            writeIndex = 0
            appended = n
        }
    }

    /** 取第 [index] 行（0 = 缓冲内最上一行）。越界返回 null。 */
    fun rowAt(index: Int): IntArray? = synchronized(lock) {
        if (index < 0 || index >= height) return null
        val offset = ringIndex(index) * width
        rows.copyOfRange(offset, offset + width)
    }

    /**
     * 导出为可直接建位图的像素数组。
     *
     * @param maxHeight 目标最大高度（行）。若当前行数超过它，按**等间隔抽样**压缩 ——
     *   宁可整体纵向压缩，也不截断：用户要的是「这一段收了什么」，不是「最后 30 分钟」。
     * @return `(pixels, outHeight)`；空缓冲返回 `(IntArray(0), 0)`
     */
    fun toPixels(maxHeight: Int = maxHeightRows): Pair<IntArray, Int> = synchronized(lock) {
        if (height == 0) return IntArray(0) to 0
        val target = maxHeight.coerceIn(1, maxHeightRows)
        val outHeight = minOf(height, target)
        val out = IntArray(width * outHeight)
        // 最近邻纵向抽样：行数超过目标高度时等间隔取行，保证覆盖整段时间
        val step = height.toDouble() / outHeight
        for (y in 0 until outHeight) {
            val src = if (outHeight == height) y else (y * step).toInt().coerceIn(0, height - 1)
            System.arraycopy(rows, ringIndex(src) * width, out, y * width, width)
        }
        out to outHeight
    }

    /** 逻辑行号 → [rows] 里的环形下标（行数未到顶时恒等于逻辑行号）。 */
    private fun ringIndex(row: Int): Int =
        if (shift == 0) row else (row + writeIndex) % (rows.size / width)

    /**
     * 导出为 [SstvDecoder.Result]（可直接交给图库保存、后处理）。
     *
     * @param mode 用于标注模式名与失谐量
     * @param hedrShiftHz 本次接收的失谐量
     * @param maxHeight 目标最大高度（行），默认 [maxHeightRows]；超过时等间隔抽样
     */
    fun toResult(
        mode: SstvMode,
        hedrShiftHz: Double = 0.0,
        slantRatio: Double = 1.0,
        syncPulseCount: Int = 0,
        maxHeight: Int = maxHeightRows,
    ): SstvDecoder.Result? {
        val (pixels, outHeight) = toPixels(maxHeight)
        if (outHeight == 0) return null
        return SstvDecoder.Result(
            mode = mode,
            width = width,
            height = outHeight,
            pixels = pixels,
            hedrShiftHz = hedrShiftHz,
            slantRatio = slantRatio,
            syncPulseCount = syncPulseCount,
            decodedLines = outHeight,
        )
    }

    /** 清空（重新开始一次持续接收）。 */
    fun clear() {
        synchronized(lock) {
            rows = IntArray(minOf(maxRows, INITIAL_ROWS) * width)
            height = 0
            shift = 0
            writeIndex = 0
            appended = 0
        }
        lastAppendedRow = -1
    }

    companion object {
        /** 默认保留行数（≈ 83 分钟 @ Robot 36）。 */
        const val DEFAULT_MAX_ROWS = 20_000

        /**
         * 单次导出位图的最大高度（行）。
         *
         * 取 4096：640 宽 × 4096 行 × 4 字节 ≈ 10 MB，远低于 Android 单张位图的内存上限，
         * 又不至于把一次过境的图像压得看不清。
         */
        const val DEFAULT_MAX_BITMAP_ROWS = 4_096

        /** 初始分配行数（按需翻倍到上限，避免一开始就分配几十 MB）。 */
        private const val INITIAL_ROWS = 64
    }
}
