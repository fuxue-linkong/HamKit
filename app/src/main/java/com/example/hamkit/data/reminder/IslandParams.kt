package com.example.hamkit.data.reminder

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import com.example.hamkit.R
import com.example.hamkit.data.sstv.SstvLaunchIntent
import com.example.hamkit.data.sstv.SstvSatelliteLink
import com.example.hamkit.ui.MainActivity
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 小米超级岛（HyperOS 3）/ 焦点通知（HyperOS 2）通知参数构建。
 *
 * 数据协议（依据小米超级岛《开发指南》pId=2131）：
 * - `miui.focus.param`（String）：`{"param_v2": {...}}` JSON，承载岛/焦点通知全部字段；
 * - `miui.focus.pics`（Bundle）：模板中以 `"miui.focus.pic_xxx"` 引用的图片，Icon 形式传入；
 * - `miui.focus.actions`（Bundle）：模板中以 `"miui.focus.action_xxx"` 引用的动作，
 *   `Notification.Action` 形式传入。
 *
 * 所有构建函数均为纯函数且不抛异常（内部兜底返回 null），调用方拿不到参数时
 * 直接走普通通知，保证非小米设备与异常路径零回归。
 */
object IslandParams {

    /** extras key：岛参数 JSON（OS2/OS3 通用，文档标注 "OS2/OS3"）。 */
    const val EXTRA_FOCUS_PARAM = "miui.focus.param"

    /** extras key：图片 Bundle。 */
    const val EXTRA_FOCUS_PICS = "miui.focus.pics"

    /** extras key：动作 Bundle。 */
    const val EXTRA_FOCUS_ACTIONS = "miui.focus.actions"

    /** 岛参数 key 引用的图片名（信息模板左图 + 状态栏/息屏图标共用）。 */
    private const val PIC_MAIN = "miui.focus.pic_main"

    /** 岛参数 key 引用的 SSTV 直达动作。 */
    private const val ACTION_MAIN = "miui.focus.action_main"
    private const val ACTION_SSTV = "miui.focus.action_sstv"

    /**
     * 一次性构建岛通知所需的全部扩展数据。
     *
     * @return 附加到通知 extras 的完整 Bundle（param JSON + pics + actions），
     *         任一环节失败返回 null（调用方退回普通通知）。
     */
    fun buildExtras(context: Context, item: ReminderItem): Bundle? = try {
        val isSstv = SstvSatelliteLink.isSstvSatellite(item.modes, item.catalogNumber)
        val sstvPreset = if (isSstv) SstvSatelliteLink.presetModeNameFor(item.catalogNumber) else null

        val param = buildParamJson(context, item, isSstv)
        val bundle = Bundle()
        bundle.putString(EXTRA_FOCUS_PARAM, param.toString())
        bundle.putBundle(EXTRA_FOCUS_PICS, buildPics(context))
        bundle.putBundle(EXTRA_FOCUS_ACTIONS, buildActions(context, item, sstvPreset))
        bundle
    } catch (_: Exception) {
        // JSON/图标构建失败不能影响普通通知的发送
        null
    }

    /**
     * 构建 `param_v2` JSON。结构与《开发指南》"模版接入示例"一致：
     * 业务属性 + 状态栏（OS2）+ 息屏（AOD）+ 岛数据（大岛/小岛）+ 焦点通知卡 + 动作。
     */
    private fun buildParamJson(context: Context, item: ReminderItem, isSstv: Boolean): JSONObject {
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
        val aosTime = timeFormatter.format(Instant.ofEpochMilli(item.aosTimeMillis))
        val maxEl = item.maxElevation.toInt()

        // 动作列表：普通卫星给"打开应用"，SSTV 卫星额外给"接收 SSTV"直达动作
        val actions = buildList {
            add(JSONObject().put("action", ACTION_MAIN))
            if (isSstv) add(JSONObject().put("action", ACTION_SSTV))
        }
        val actionsArr = org.json.JSONArray()
        actions.forEach { actionsArr.put(it) }

        return JSONObject().apply {
            put("param_v2", JSONObject().apply {
                // 运营场景标识：过境提醒场景（提报审核时以该 business 归档统计）
                put("business", "satellite_pass")
                // 首次出现即展开态：AOS 前 N 分钟提醒只有一条信息，直接展示完整内容
                put("islandFirstFloat", true)
                // 一次性提醒，无需持续更新能力
                put("updatable", false)
                // 岛上"打开应用"（正文点击）。SSTV 卫星点击直达解码器，
                // 复用现有 [SstvLaunchIntent] extra 约定
                put("hintInfo", JSONObject().apply {
                    put("type", 1)
                    put("title", context.getString(R.string.island_open_app))
                    put("actionInfo", JSONObject().put("action", ACTION_MAIN))
                    if (isSstv) {
                        put("actions", actionsArr)
                    }
                })
                // OS2 状态栏焦点信息（OS3 上不展示，带上以兼容 OS2 设备）
                put("ticker", context.getString(R.string.island_ticker, item.name, aosTime))
                // 息屏（AOD）显示文案
                put("aodTitle", context.getString(R.string.island_aod, item.name, aosTime))

                // 岛数据：摘要态（大岛/小岛）
                put("param_island", JSONObject().apply {
                    // 1 = 信息展示为主（过境提醒无岛上按钮操作诉求）
                    put("islandProperty", 1)
                    put("bigIslandArea", bigIsland(context, item, aosTime, maxEl))
                    put("smallIslandArea", smallIsland())
                })

                // 焦点通知卡（OS2 展开态 / OS3 通知中心形态）
                put("baseInfo", JSONObject().apply {
                    put("title", context.getString(R.string.reminder_notification_title, item.name))
                    put("content", context.getString(
                        R.string.island_base_content, aosTime, maxEl
                    ))
                    put("type", 2)
                })
            })
        }
    }

    /**
     * 大岛内容：信息模板左区（图标 + 标题/内容/附加文案）。
     * 字段结构取自《开发指南》示例中已验证的 `imageTextInfoLeft`，模板 ID 如后续
     * 版本变化只需调整此处字段映射。
     */
    private fun bigIsland(
        context: Context,
        item: ReminderItem,
        aosTime: String,
        maxEl: Int
    ): JSONObject = JSONObject().apply {
        put("imageTextInfoLeft", JSONObject().apply {
            put("type", 1)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", PIC_MAIN)
            })
            put("textInfo", JSONObject().apply {
                put("frontTitle", item.name)
                put("title", context.getString(R.string.island_big_title, aosTime))
                put("content", context.getString(
                    R.string.island_big_content, maxEl, item.aosAzimuth
                ))
                put("useHighLight", false)
            })
        })
    }

    /** 小岛内容：仅图标（摘要态收起时展示）。 */
    private fun smallIsland(): JSONObject = JSONObject().apply {
        put("picInfo", JSONObject().apply {
            put("type", 1)
            put("pic", PIC_MAIN)
        })
    }

    /**
     * 图片 Bundle：使用应用图标作为岛上的卫星占位图。
     * 文档要求 pics 以 `Icon` 形式传入，key 需与 param JSON 中的引用一致。
     */
    private fun buildPics(context: Context): Bundle = Bundle().apply {
        putParcelable(
            PIC_MAIN,
            Icon.createWithResource(context, R.mipmap.ic_launcher)
        )
    }

    /**
     * 动作 Bundle：
     * - [ACTION_MAIN]：打开应用（与普通通知正文点击一致）；
     * - [ACTION_SSTV]：SSTV 卫星直达解码器（复用 [SstvLaunchIntent] extra）。
     */
    private fun buildActions(
        context: Context,
        item: ReminderItem,
        sstvPreset: String?
    ): Bundle = Bundle().apply {
        putParcelable(
            ACTION_MAIN,
            Notification.Action.Builder(
                Icon.createWithResource(context, R.mipmap.ic_launcher),
                context.getString(R.string.island_open_app),
                mainActivityPendingIntent(context, item.catalogNumber, null)
            ).build()
        )
        if (sstvPreset != null) {
            putParcelable(
                ACTION_SSTV,
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.mipmap.ic_launcher),
                    context.getString(R.string.sstv_reminder_action_receive),
                    mainActivityPendingIntent(context, item.catalogNumber, sstvPreset)
                ).build()
            )
        }
    }

    /**
     * 构建打开 [MainActivity] 的 PendingIntent。
     * requestCode 复用 NORAD 编号：与普通通知正文点击的 PendingIntent 同源，
     * 系统会自动去重，不会产生额外实例。
     */
    private fun mainActivityPendingIntent(
        context: Context,
        catalogNumber: Int,
        sstvPreset: String?
    ) = PendingIntent.getActivity(
        context,
        catalogNumber,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sstvPreset != null) SstvLaunchIntent.putLaunchRequest(this, sstvPreset)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
