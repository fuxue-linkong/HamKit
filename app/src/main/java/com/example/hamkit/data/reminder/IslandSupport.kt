package com.example.hamkit.data.reminder

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * 小米超级岛 / 焦点通知能力探测。
 *
 * HyperOS 版本形态（依据小米超级岛《开发指南》《版本信息》）：
 * - 返回 0：非 HyperOS 或不支持焦点通知，岛参数会被系统忽略；
 * - 返回 1：OS1 焦点通知；
 * - 返回 2：OS2 焦点通知（状态栏 + 通知中心 + 锁屏 + 息屏 + 小折叠外屏）；
 * - 返回 3：OS3 小米超级岛（岛摘要态 + 岛展开态 + 焦点通知）。
 *
 * 接入方式是往原生通知 extras 附加 `miui.focus.param` 等扩展参数，
 * 不支持的设备会忽略这些 extras，展示为普通通知，因此探测失败全部
 * 安全降级为"不支持"，调用方无需区分失败原因。
 */
object IslandSupport {

    /**
     * 查询焦点通知协议版本。
     *
     * 系统接口：`Settings.System.getInt(resolver, "notification_focus_protocol", 0)`。
     * 结果每次实时查询（系统升级 OS2 → OS3 后应能立即拿到新值），调用方
     * 如需高频调用可自行缓存；单条通知构建场景每条查一次开销可忽略。
     */
    fun focusProtocolVersion(context: Context): Int = try {
        Settings.System.getInt(context.contentResolver, KEY_FOCUS_PROTOCOL, 0)
    } catch (_: Exception) {
        // Settings.System 在个别 ROM 上可能抛 SecurityException 等异常
        0
    }

    /** 是否为 HyperOS 3 及以上（支持小米超级岛岛形态）。 */
    fun isIslandSupported(context: Context): Boolean = focusProtocolVersion(context) >= 3

    /** 是否支持焦点通知（OS2+，含 OS3 超级岛背后的焦点通知数据通道）。 */
    fun isFocusSupported(context: Context): Boolean = focusProtocolVersion(context) >= 2

    /**
     * 查询本应用是否已开启"焦点通知"权限（用户可在系统设置中关闭）。
     *
     * 系统接口：向 `content://miui.statusbar.notification.public` call `canShowFocus`。
     * 文档标注为耗时操作，且属于跨进程调用——调用方应避免在高频路径使用；
     * 当前仅用于联调诊断输出，不参与通知构建的开关判断。
     */
    fun canShowFocus(context: Context): Boolean = try {
        val extras = Bundle().apply { putString("package", context.packageName) }
        val bundle = context.contentResolver.call(
            Uri.parse("content://miui.statusbar.notification.public"),
            "canShowFocus", null, extras
        )
        bundle?.getBoolean("canShowFocus", false) ?: false
    } catch (_: Exception) {
        // 非小米 ROM 无该 provider，查询失败一律视为"未知/不支持"
        false
    }

    /**
     * 查询系统是否支持岛功能（`persist.sys.feature.island`，反射 SystemProperties）。
     *
     * 这是《开发指南》给出的另一个探测点，与 protocol >= 3 互为补充：
     * 个别工程机上二者可能不一致，联调时可同时打印比对。
     */
    @SuppressLint("PrivateApi")
    fun isSystemIslandFeatureEnabled(): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)
        method.invoke(null, KEY_ISLAND_FEATURE, false) as? Boolean ?: false
    } catch (_: Exception) {
        false
    }

    private const val KEY_FOCUS_PROTOCOL = "notification_focus_protocol"
    private const val KEY_ISLAND_FEATURE = "persist.sys.feature.island"
}
