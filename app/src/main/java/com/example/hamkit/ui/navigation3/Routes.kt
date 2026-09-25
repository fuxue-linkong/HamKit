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
}
