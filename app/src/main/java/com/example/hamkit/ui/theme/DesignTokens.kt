package com.example.hamkit.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计 token：
 *
 * - 圆角是交互语义，不是装饰：全应用只允许 [Radii] 的 6 个值 + 胶囊/CircleShape；
 * - 动效收敛为曲线 token：业务代码禁止裸写 spring/tween 数值；
 * - 动效速度由 [MotionScale] 全局控制，关闭档动画瞬移但功能完整。
 */
object Radii {
    /** 徽章、小标签、极小件。 */
    val Badge: Dp = 6.dp

    /** 输入框、行内控件、小按钮。 */
    val Control: Dp = 10.dp

    /** 卡片、连体组内条目。 */
    val Card: Dp = 16.dp

    /** 连体卡片组外角。 */
    val CardGroup: Dp = 20.dp

    /** 对话框、底部弹层。 */
    val Sheet: Dp = 26.dp

    /** 底部 Dock 等大容器。 */
    val Dock: Dp = 32.dp
}

/**
 * 动效曲线 token：全应用只允许这 5 条曲线。
 *
 * 泛型 helper 让 Float/Offset/Rect 等都能复用同一组参数。
 */
object Motions {
    /** 按压回弹。 */
    fun <T> press() = spring<T>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium * 2)

    /** 标准过渡。 */
    fun <T> standard() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /** 微交互（开关、勾选等小位移）。 */
    fun <T> micro() = spring<T>(dampingRatio = 0.90f, stiffness = Spring.StiffnessMedium * 3)

    /** 果冻回弹（底部 Dock 挤压）。 */
    fun <T> jelly() = spring<T>(dampingRatio = 0.45f, stiffness = 260f)

    /** 退场。 */
    fun <T> exit() = tween<T>(180)
}

/** 动效速度档位。 */
enum class MotionScale { STANDARD, FAST, OFF }

val LocalMotionScale = staticCompositionLocalOf { MotionScale.STANDARD }
