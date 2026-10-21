package com.example.hamkit.ui.navigation3

import android.os.Parcelable
import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation keys for Navigation3.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
sealed interface Route : NavKey, Parcelable {
    @Parcelize
    @Serializable
    data object Main : Route

    @Parcelize
    @Serializable
    data object Home : Route

    @Parcelize
    @Serializable
    data object Settings : Route

    @Parcelize
    @Serializable
    data object About : Route

    /** 隐私政策（离线全文，首启同意与“关于”页共用） */
    @Parcelize
    @Serializable
    data object PrivacyPolicy : Route

    @Parcelize
    @Serializable
    data object ColorPalette : Route

    @Parcelize
    @Serializable
    data object Permissions : Route

    /** CW 练习主入口（菜单：自由练习 / 教程练习 / 摩斯电码编解码） */
    @Parcelize
    @Serializable
    data object CWPractice : Route

    /** 卫星管理：卫星列表、分类与过境提醒 */
    @Parcelize
    @Serializable
    data object SatelliteManagement : Route

    /** 卫星筛选：按名称/来源/模式/状态筛选卫星列表 */
    @Parcelize
    @Serializable
    data object SatelliteFilter : Route

    /** 卫星详情：实时追踪面板 + 多普勒 + 蚀状态 */
    @Parcelize
    @Serializable
    data class SatelliteDetail(val catalogNumber: Int) : Route

    /** 卫星雷达图：仰角环 + 方位刻度 + 罗盘 */
    @Parcelize
    @Serializable
    data class SatelliteRadar(val catalogNumber: Int) : Route

    /** 卫星轨道地图：星下点 + 地面轨迹 + 覆盖圆 */
    @Parcelize
    @Serializable
    data class SatelliteMap(val catalogNumber: Int) : Route

    /** 提醒列表：查看与管理过境提醒 */
    @Parcelize
    @Serializable
    data object ReminderList : Route

    /** 定位详情：定位状态 + 位置地图（同一子页面） */
    @Parcelize
    @Serializable
    data object LocationDetail : Route

    /** APRS 主入口 */
    @Parcelize
    @Serializable
    data object AprsMain : Route

    /** APRS 设置 */
    @Parcelize
    @Serializable
    data object AprsSettings : Route

    /** APRS 站点列表 */
    @Parcelize
    @Serializable
    data object AprsStations : Route

    /** APRS 消息 */
    @Parcelize
    @Serializable
    data object AprsMessages : Route

    /** APRS 站点地图 */
    @Parcelize
    @Serializable
    data object AprsMap : Route

    /** APRS 符号选择器 */
    @Parcelize
    @Serializable
    data object AprsSymbolPicker : Route

    /** FT8 数字模式主入口 */
    @Parcelize
    @Serializable
    data object Ft8Main : Route

    /** FT8 设置 */
    @Parcelize
    @Serializable
    data object Ft8Settings : Route

    /**
     * SSTV 慢扫描电视：接收与解码。
     *
     * @param presetModeName 预置锁定的模式名（如 ISS 的 PD_120）；来自卫星详情页的
     *   「接收 SSTV」入口，为 null 时使用 VIS 自动识别
     * @param autoStart 进入页面后立即开始接收（二期 T8：卫星过境提醒直达接收态）；
     *   由详情页手动进入时为 false，用户自己按「开始接收」
     */
    @Parcelize
    @Serializable
    data class SstvMain(
        val presetModeName: String? = null,
        val autoStart: Boolean = false,
    ) : Route

    /** SSTV 设置：模式锁定、保存策略与图库管理 */
    @Parcelize
    @Serializable
    data object SstvSettings : Route

    /**
     * SSTV 图库：浏览、全屏查看、删除、分享、导出到相册。
     *
     * @param pickForTx 为 true 时进入「为发射选图」模式：点击图像即选中并返回发射页
     */
    @Parcelize
    @Serializable
    data class SstvGallery(val pickForTx: Boolean = false) : Route

    /** SSTV 发射：选图 → 编码成 SSTV 音频 → 播放或导出 WAV */
    @Parcelize
    @Serializable
    data object SstvTx : Route

    /**
     * HF Fax（WEFAX）天气传真接收。
     *
     * 与 SSTV 不是同一套协议（无 VIS、无行同步、行节拍靠 120 行/分自由运行），
     * 因此单独一个页面而不是挂在 SSTV 里。
     */
    @Parcelize
    @Serializable
    data object HfFax : Route

    /** QSO 通联日志：FT8 通联记录列表 / 增删改 / ADIF/XML 导出 */
    @Parcelize
    @Serializable
    data object QsoLogList : Route
}
