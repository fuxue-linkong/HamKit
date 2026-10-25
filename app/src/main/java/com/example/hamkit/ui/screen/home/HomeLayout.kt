package com.example.hamkit.ui.screen.home

import androidx.compose.ui.unit.dp

/**
 * 主页大屏布局参数（Material / Miuix 两套主题共用）。
 *
 * 断点基于「扣除左侧导航栏后的内容区宽度」，而不是窗口宽度：
 * 侧栏固定占用约 86dp，若直接用 600/840 这类窗口断点，600dp 窗口会被误判成两栏。
 */
internal object HomeLayout {

    /** 内容区达到该宽度后，卫星卡与功能入口改为左右两栏 */
    val TwoColumnMinWidth = 700.dp

    /** 内容区达到该宽度后，功能入口区展开为两列（三栏布局） */
    val ThreeColumnMinWidth = 1080.dp

    /** 超宽窗口下内容区的最大宽度，避免卡片被无限拉长 */
    val MaxContentWidth = 1280.dp

    /** 宽屏卡片间距 */
    val Gap = 16.dp

    /** 宽屏左右留白 */
    val ContentPadding = 24.dp

    /** 权限引导卡在宽屏下的最大宽度（单卡，不宜铺满） */
    val PermissionCardMaxWidth = 560.dp
}
