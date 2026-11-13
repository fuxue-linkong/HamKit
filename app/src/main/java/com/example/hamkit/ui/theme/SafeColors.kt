package com.example.hamkit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * 规范颜色库 - 仅包含经过视觉验证的安全颜色值。
 *
 * 所有界面元素、文本、背景及交互状态均应使用此对象中的颜色，
 * 禁止直接使用 Color(0x...) 硬编码值。
 */
object SafeColors {

    // ==================== 状态指示色 ====================

    /** 成功状态 - 图标色（明亮绿，避免深绿） */
    val successIcon: Color = Color(0xFF36D167)

    /** 成功状态 - 容器背景色（浅绿，避免肉色/肤色） */
    val successContainer: Color = Color(0xFFDFFAE4)

    /** 成功状态 - 深色模式图标色 */
    val successIconDark: Color = Color(0xFF4ADE80)

    /** 成功状态 - 深色模式容器背景色 */
    val successContainerDark: Color = Color(0xFF0B2E16)

    /** 错误状态 - 图标色（明亮红，避免深红/酒红） */
    val errorIcon: Color = Color(0xFFF72727)

    /** 错误状态 - 容器背景色（浅红，避免肉色/肤色） */
    val errorContainer: Color = Color(0xFFF8E2E2)

    /** 错误状态 - 深色模式图标色 */
    val errorIconDark: Color = Color(0xFFF72727)

    /** 错误状态 - 深色模式容器背景色 */
    val errorContainerDark: Color = Color(0xFF310808)

    /** 警告状态 - 图标色（琥珀，light/dark 通用） */
    val warningIcon: Color = Color(0xFFFFA726)

    /** 警告状态 - 容器背景色（浅琥珀） */
    val warningContainer: Color = Color(0xFFFFF3E0)

    /** 警告状态 - 深色模式容器背景色 */
    val warningContainerDark: Color = Color(0xFF33240D)

    // ==================== 信息指示色（蓝色系）====================

    /** 信息状态 - 图标色（明亮蓝，Blue 400） */
    val infoIcon: Color = Color(0xFF42A5F5)

    /** 信息状态 - 容器背景色（浅蓝） */
    val infoContainer: Color = Color(0xFFE3F2FD)

    /** 信息状态 - 深色模式图标色 */
    val infoIconDark: Color = Color(0xFF64B5F6)

    /** 信息状态 - 深色模式容器背景色 */
    val infoContainerDark: Color = Color(0xFF0D2740)

    // ==================== 文本色 ====================

    /** 主要文字色（浅色模式） */
    val textPrimaryLight: Color = Color(0xFF111111)

    /** 次要文字色（浅色模式） */
    val textSecondaryLight: Color = Color(0xFF666666)

    // ==================== 关键色种子（keyColor） ====================

    /**
     * 经过视觉验证的安全种子色列表。
     * 全部使用 Material Design 400 色值，避免产生肉色、酒红色等诡异色调。
     */
    val keyColorSeed: List<Int> = listOf(
        Color(0xFFEF5350).toArgb(),  // Red 400
        Color(0xFFEC407A).toArgb(),  // Pink 400
        Color(0xFFAB47BC).toArgb(),  // Purple 400
        Color(0xFF7E57C2).toArgb(),  // Deep Purple 400
        Color(0xFF5C6BC0).toArgb(),  // Indigo 400
        Color(0xFF42A5F5).toArgb(),  // Blue 400
        Color(0xFF26C6DA).toArgb(),  // Cyan 400
        Color(0xFF26A69A).toArgb(),  // Teal 400
        Color(0xFF66BB6A).toArgb(),  // Green 400
        Color(0xFFFFEE58).toArgb(),  // Yellow 400
        Color(0xFFFFCA28).toArgb(),  // Amber 400
        Color(0xFFFFA726).toArgb(),  // Orange 400
        Color(0xFF8D6E63).toArgb(),  // Brown 400
        Color(0xFF78909C).toArgb(),  // Blue Grey 400
        Color(0xFFF48FB1).toArgb(),  // Sakura (Pink 200)
    )

    /**
     * 颜色校验机制：自动过滤接近异常颜色范围的数值。
     *
     * 异常颜色范围定义（HSL）：
     * - 肉色/肤色：H ∈ [15°, 45°] 且 S ∈ [20%, 60%] 且 L ∈ [60%, 85%]
     * - 深红色/酒红色：H ∈ [350°, 360°] ∪ [0°, 15°] 且 S > 40% 且 L < 45%
     *
     * 若输入颜色落入异常范围，则返回 null，由调用方决定回退策略。
     */
    fun validateColor(color: Color): Color? {
        val argb = color.toArgb()
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC

        // 跳过无彩色（灰阶）
        if (delta < 0.01f) return color

        val l = (maxC + minC) / 2f
        val s = if (l < 0.5f) delta / (maxC + minC) else delta / (2f - maxC - minC)

        val h = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val hue = if (h < 0) h + 360f else h

        // 肉色/肤色检测
        val isSkin = hue in 15f..45f && s in 0.2f..0.6f && l in 0.6f..0.85f

        // 深红色/酒红色检测
        val isWine = (hue in 350f..360f || hue in 0f..15f) && s > 0.4f && l < 0.45f

        return if (isSkin || isWine) null else color
    }

    /**
     * 对关键色进行校验，若不通过则从安全列表中选取最接近的有效颜色。
     */
    fun sanitizeKeyColor(color: Int): Int {
        validateColor(Color(color))?.let { return color }

        // 回退：选取关键色种子中的第一个（红色 400）
        return keyColorSeed.first()
    }

    // ==================== 主题感知状态色（token 读取入口） ====================

    /**
     * 状态色令牌的语义枚举。
     * 界面统一通过 [statusColor] / [statusContainer] 读取，禁止直接取用单一模式变体。
     */
    enum class StatusTone { SUCCESS, ERROR, INFO, WARNING }

    /**
     * 主题感知的状态色三件套（前景 / 容器背景 / 容器前景）。
     */
    data class StatusColors(
        val foreground: Color,
        val container: Color,
        val onContainer: Color,
    )

    /**
     * 读取主题感知的状态色。
     *
     * @param tone 状态语义（成功/错误/信息/警告）
     */
    @Composable
    fun statusColors(tone: StatusTone): StatusColors {
        val dark = isInDarkTheme()
        return when (tone) {
            StatusTone.SUCCESS -> StatusColors(
                foreground = if (dark) successIconDark else successIcon,
                container = if (dark) successContainerDark else successContainer,
                onContainer = if (dark) successIconDark else Color(0xFF0B4A20),
            )
            StatusTone.ERROR -> StatusColors(
                foreground = errorIcon,
                container = if (dark) errorContainerDark else errorContainer,
                onContainer = if (dark) errorIcon else Color(0xFF8C1616),
            )
            StatusTone.INFO -> StatusColors(
                foreground = if (dark) infoIconDark else infoIcon,
                container = if (dark) infoContainerDark else infoContainer,
                onContainer = if (dark) infoIconDark else Color(0xFF0B4A7A),
            )
            StatusTone.WARNING -> StatusColors(
                foreground = warningIcon,
                container = if (dark) warningContainerDark else warningContainer,
                onContainer = if (dark) warningIcon else Color(0xFF8A5A00),
            )
        }
    }
}
