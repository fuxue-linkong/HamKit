package com.example.hamkit.data.satellite

/**
 * 多普勒频移计算（Phase 4 实现）。
 *
 * 约定：rangeRateMps > 0 表示卫星正在远离观察者（频移为负），
 * rangeRateMps < 0 表示接近（频移为正）。频移公式：Δf = -f0 * v / c。
 *
 * 经典（非相对论）多普勒公式 Δf = -f0·v/c 对卫星通信场景（v ≤ 8 km/s）
 * 与相对论修正之差 < 0.1 Hz，足够业余卫星跟踪使用。
 */
object DopplerCalculator {

    /** 光速（m/s） */
    const val SPEED_OF_LIGHT_MPS = 299_792_458.0

    /**
     * 由基频与径向速度计算多普勒频移（Hz）。正值表示频率升高。
     *
     * @param baseFrequencyHz 静止基频（Hz）
     * @param rangeRateMps 径向速度（m/s，>0 远离，<0 接近）
     * @return Δf = -f0·v/c；例如 145.8 MHz、v = -7500 m/s（接近）≈ +3647 Hz
     */
    fun dopplerShiftHz(baseFrequencyHz: Double, rangeRateMps: Double): Double =
        -baseFrequencyHz * rangeRateMps / SPEED_OF_LIGHT_MPS

    /**
     * 计算上下行实时（多普勒校正后）频率。
     *
     * 物理模型：同一径向速度下，各频段的相对频移 Δf/f = -v/c 相同，
     * 但绝对频移按各自基频计算（Δf_down = -f_down·v/c，Δf_up = -f_up·v/c）。
     * 地面接收时听到的下行频率 = 下行基频 + Δf_down；发射上行时需预补偿
     * 同样的频移，因此上行实时频率 = 上行基频 + Δf_up。
     *
     * @param downlinkHz 卫星下行静止频率（Hz）
     * @param uplinkHz 上行静止频率（Hz）
     * @param rangeRateMps 径向速度（m/s，>0 远离，<0 接近）
     * @return Pair(downlink 实时频率, uplink 实时频率)
     */
    fun dopplerFrequencies(
        downlinkHz: Double,
        uplinkHz: Double,
        rangeRateMps: Double,
    ): Pair<Double, Double> {
        val downlinkShift = dopplerShiftHz(downlinkHz, rangeRateMps)
        val uplinkShift = dopplerShiftHz(uplinkHz, rangeRateMps)
        return (downlinkHz + downlinkShift) to (uplinkHz + uplinkShift)
    }
}
