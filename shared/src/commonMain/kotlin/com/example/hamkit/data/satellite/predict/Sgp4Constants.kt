package com.example.hamkit.data.satellite.predict

import kotlin.math.*

/**
 * SGP4/SDP4 轨道传播算法使用的 WGS-72 常数。
 *
 * 数值取自公共领域算法文档：
 * - Hoots, Roehrich, "Spacetrack Report #3" (1980)
 * - Vallado, Crawford, Hujsak, Kelso, "Revisiting Spacetrack Report #3" (AIAA 2006-6753)
 * - satellite.js（MIT License）的 constants.ts，为上述论文算法的忠实移植
 */
internal object Sgp4Constants {
    /** π */
    const val PI = 3.141592653589793
    /** 2π */
    const val TWO_PI = 2.0 * PI

    /** WGS-72 地球半径（km） */
    const val EARTH_RADIUS_KM = 6378.135

    /** 地球引力常数（km³/s²） */
    const val MU = 398600.8

    /** XKE = 60 / sqrt(re³/mu)（厄尔特曼常数，单位 1/min） */
    val XKE: Double = 60.0 / sqrt(EARTH_RADIUS_KM * EARTH_RADIUS_KM * EARTH_RADIUS_KM / MU)

    /** VKMPERSEC = re * xke / 60（把无量纲速度换算为 km/s） */
    val VKM_PER_SEC: Double = EARTH_RADIUS_KM * XKE / 60.0

    /** TUMIN = 1 / XKE */
    val TUMIN: Double = 1.0 / XKE

    /** J2 二阶带谐项 */
    const val J2 = 0.001082616

    /** J3 三阶带谐项 */
    const val J3 = -0.00000253881

    /** J4 四阶带谐项 */
    const val J4 = -0.00000165597

    /** J3 / J2 */
    const val J3OJ2 = J3 / J2

    /** 2/3 */
    const val X2O3 = 2.0 / 3.0

    /** 每分钟转弧度系数：1440 / (2π)（转/天 → 弧度/分钟） */
    const val XP_DOT_P = 1440.0 / TWO_PI
}
