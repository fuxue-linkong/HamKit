package com.example.hamkit.ui

/**
 * 大屏（窗口宽度 ≥ 600dp）下的导航形态。
 *
 * 背景：平板/折叠屏展开后，底部导航会浪费横向空间，侧栏更符合大屏习惯；
 * 但部分用户偏好悬浮胶囊。这里提供显式三档，避免「悬浮底栏」开关隐式决定平板形态。
 */
enum class WideNavStyle(val value: String) {
    /** 自动：沿用既有规则（Miuix 主题开启悬浮底栏时用底部胶囊，否则用侧栏） */
    Auto("auto"),

    /** 侧栏（默认）：平板/大屏优先使用侧栏，符合 Material 大屏规范 */
    Rail("rail"),

    /** 底部导航：宽屏也使用底部导航（Miuix 为悬浮胶囊，Material 为底部应用栏） */
    BottomBar("bottom");

    companion object {
        /** 解析持久化值；未知值回退到 [Auto]，避免脏数据导致崩溃 */
        fun fromValue(value: String): WideNavStyle =
            entries.firstOrNull { it.value == value } ?: Auto
    }
}
