package com.example.hamkit.data.sstv

import kotlin.math.roundToInt

/**
 * SSTV 使用的色彩空间转换（BT.601 / YCrCb）。
 *
 * SSTV 各模式族的通道语义不同，但都基于同一套 BT.601 系数：
 *  - **PD 族**：每对图像行共享 Cr/Cb
 *  - **Robot 族**：Y + 行交替的单个色度分量
 *  - **Martin**：直接传输 G/B/R，不经过色彩空间转换
 *
 * 全部为纯函数，无 Android 依赖，便于单元测试覆盖「PD 色度配对」与
 * 「Robot 行奇偶互补」这两类易错点。
 */
object SstvColor {

    /** 亮度权重（BT.601）。 */
    private const val Y_R = 0.299
    private const val Y_G = 0.587
    private const val Y_B = 0.114

    /** 色度权重（BT.601，输出带 128 偏移）。 */
    private const val CR_R = 0.5
    private const val CR_G = -0.418688
    private const val CR_B = -0.081312

    private const val CB_R = -0.168736
    private const val CB_G = -0.331264
    private const val CB_B = 0.5

    /** 色度偏移量（8 位无符号）。 */
    const val CHROMA_OFFSET = 128.0

    /** 取出打包 RGB（0xRRGGBB）中的红色分量。 */
    fun redOf(rgb: Int): Int = (rgb shr 16) and 0xFF

    /** 取出打包 RGB（0xRRGGBB）中的绿色分量。 */
    fun greenOf(rgb: Int): Int = (rgb shr 8) and 0xFF

    /** 取出打包 RGB（0xRRGGBB）中的蓝色分量。 */
    fun blueOf(rgb: Int): Int = rgb and 0xFF

    /** 打包 RGB 三分量为 0xRRGGBB。 */
    fun pack(r: Int, g: Int, b: Int): Int =
        (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)

    /** RGB → 亮度 Y（0–255）。 */
    fun rgbToY(r: Int, g: Int, b: Int): Int =
        clamp8((Y_R * r + Y_G * g + Y_B * b).roundToInt())

    /** RGB → Cr（0–255，含 128 偏移）。 */
    fun rgbToCr(r: Int, g: Int, b: Int): Int =
        clamp8((CR_R * r + CR_G * g + CR_B * b + CHROMA_OFFSET).roundToInt())

    /** RGB → Cb（0–255，含 128 偏移）。 */
    fun rgbToCb(r: Int, g: Int, b: Int): Int =
        clamp8((CB_R * r + CB_G * g + CB_B * b + CHROMA_OFFSET).roundToInt())

    /**
     * YCrCb → 打包 RGB（0xRRGGBB）。
     *
     * 这是解码端的主路径：PD 族与 Robot 族都经由此函数还原彩色。
     * 典型「图像发绿」的成因是 Cr/Cb 传参颠倒或 PD 色度行配对错位。
     */
    fun ycbcrToRgb(y: Int, cr: Int, cb: Int): Int {
        val crf = cr - CHROMA_OFFSET
        val cbf = cb - CHROMA_OFFSET
        val r = (y + 1.402 * crf).roundToInt()
        val g = (y - 0.344136 * cbf - 0.714136 * crf).roundToInt()
        val b = (y + 1.772 * cbf).roundToInt()
        return pack(r, g, b)
    }

    /** YCrCb → RGB 三分量数组（顺序 R, G, B），供逐行组装使用。 */
    fun ycbcrToRgbComponents(y: Int, cr: Int, cb: Int, out: IntArray) {
        val crf = cr - CHROMA_OFFSET
        val cbf = cb - CHROMA_OFFSET
        out[0] = clamp8((y + 1.402 * crf).roundToInt())
        out[1] = clamp8((y - 0.344136 * cbf - 0.714136 * crf).roundToInt())
        out[2] = clamp8((y + 1.772 * cbf).roundToInt())
    }

    private fun clamp8(value: Int): Int = value.coerceIn(0, 255)
}
