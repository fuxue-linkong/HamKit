package com.example.hamkit.data.satellite.predict

import kotlin.math.*

/**
 * SGP4/SDP4 卫星轨道传播器（自研纯 Kotlin 实现）。
 *
 * 算法来源（均为公共领域/宽松许可的公开算法）：
 * - Hoots, Roehrich, "Spacetrack Report #3" (1980)，NORAD SGP4/SDP4 原始模型
 * - Vallado, Crawford, Hujsak, Kelso, "Revisiting Spacetrack Report #3" (AIAA 2006-6753)
 * - satellite.js（MIT License，https://github.com/shashwatak/satellite-js）为上述
 *   算法的忠实 TypeScript 移植，本实现按其流程移植为 Kotlin。
 *
 * 用法：
 * 1. [fromTle] 解析 TLE 元素并完成初始化（包含深空 SDP4 判断）；
 * 2. 每次调用 [propagate] 传入"距历元的时间（分钟）"，得到 TEME 惯性系
 *    位置（km）与速度（km/s）。
 *
 * 非线程安全：[propagate] 会写入实例内的短周期/深空积分状态（如 [atime]/[xli]/[xni]）。
 * 每次预测应为每颗卫星创建独立实例，禁止多线程共享同一实例。
 */
class Sgp4Satellite private constructor(
    /** 卫星编号字符串 */
    private val satnum: String,
    /** 历元年（四位数） */
    private val epochyr: Int,
    /** 历元儒略日 */
    val jdsatepoch: Double,
    /** 历元日（年内小数日） */
    private val epochdays: Double,
    /** 一阶平均运动变化率（rad/min²，SGP4 忽略） */
    private val ndot: Double,
    /** 二阶平均运动变化率（rad/min³，SGP4 忽略） */
    private val nddot: Double,
    /** 弹道系数 B*（地球半径倒数） */
    val bstar: Double,
    /** 轨道倾角（rad） */
    val inclo: Double,
    /** 升交点赤经（rad） */
    val nodeo: Double,
    /** 偏心率 */
    val ecco: Double,
    /** 近地点幅角（rad） */
    val argpo: Double,
    /** 平近点角（rad） */
    val mo: Double,
    /** 平均运动（rad/min，未 Kozai 修正） */
    val no: Double,
) {
    // ── 传播状态（sgp4init 后由 sgp4 在调用期写入）──

    /** 简化模式标志（近地点 < 220 km 时启用） */
    private var isimp: Int = 0
    /** 传播方法：'n' 近地 SGP4 / 'd' 深空 SDP4 */
    private var method: Char = 'n'

    // 近地项
    private var aycof: Double = 0.0
    private var con41: Double = 0.0
    private var cc1: Double = 0.0
    private var cc4: Double = 0.0
    private var cc5: Double = 0.0
    private var d2: Double = 0.0
    private var d3: Double = 0.0
    private var d4: Double = 0.0
    private var delmo: Double = 0.0
    private var eta: Double = 0.0
    private var argpdot: Double = 0.0
    private var omgcof: Double = 0.0
    private var sinmao: Double = 0.0
    private var t: Double = 0.0
    private var t2cof: Double = 0.0
    private var t3cof: Double = 0.0
    private var t4cof: Double = 0.0
    private var t5cof: Double = 0.0
    private var x1mth2: Double = 0.0
    private var x7thm1: Double = 0.0
    private var mdot: Double = 0.0
    private var nodedot: Double = 0.0
    private var xlcof: Double = 0.0
    private var xmcof: Double = 0.0
    private var nodecf: Double = 0.0

    // 深空项
    private var irez: Int = 0
    private var d2201 = 0.0; private var d2211 = 0.0
    private var d3210 = 0.0; private var d3222 = 0.0
    private var d4410 = 0.0; private var d4422 = 0.0
    private var d5220 = 0.0; private var d5232 = 0.0
    private var d5421 = 0.0; private var d5433 = 0.0
    private var dedt = 0.0; private var didt = 0.0
    private var dmdt = 0.0; private var dnodt = 0.0
    private var domdt = 0.0
    private var del1 = 0.0; private var del2 = 0.0; private var del3 = 0.0
    private var e3 = 0.0; private var ee2 = 0.0
    private var peo = 0.0; private var pgho = 0.0
    private var pho = 0.0; private var pinco = 0.0; private var plo = 0.0
    private var se2 = 0.0; private var se3 = 0.0
    private var sgh2 = 0.0; private var sgh3 = 0.0; private var sgh4 = 0.0
    private var sh2 = 0.0; private var sh3 = 0.0
    private var si2 = 0.0; private var si3 = 0.0
    private var sl2 = 0.0; private var sl3 = 0.0; private var sl4 = 0.0
    private var gsto: Double = 0.0
    private var xfact = 0.0; private var xlamo = 0.0
    private var xgh2 = 0.0; private var xgh3 = 0.0; private var xgh4 = 0.0
    private var xh2 = 0.0; private var xh3 = 0.0
    private var xi2 = 0.0; private var xi3 = 0.0
    private var xl2 = 0.0; private var xl3 = 0.0; private var xl4 = 0.0
    private var zmol = 0.0; private var zmos = 0.0
    private var atime = 0.0
    private var xli = 0.0; private var xni = 0.0
    private var tempa: Double = 1.0

    companion object {
        /** 深空判定阈值：平均运动周期 ≥ 225 分钟 */
        private const val DEEP_SPACE_PERIOD_MIN = 225.0

        /** 除以零保护阈值（倾角 180°） */
        private const val TEMP4 = 1.5e-12

        /** 出错码：无错误 */
        private const val ERR_NONE = 0
        /** 出错码：平偏心率越界 */
        private const val ERR_MEAN_ECC = 1
        /** 出错码：平均运动 ≤ 0 */
        private const val ERR_MEAN_MOTION = 2
        /** 出错码：摄动偏心率越界 */
        private const val ERR_PERT_ECC = 3
        /** 出错码：半通径 < 0 */
        private const val ERR_SEMI_LATUS = 4
        /** 出错码：卫星已再入（mrt < 1） */
        private const val ERR_DECAYED = 6

        /**
         * 由解析后的 TLE 元素构建并初始化 SGP4/SDP4 传播器。
         *
         * @param tle 已解析的 TLE 元素（弧度制字段已换算）
         * @return 初始化完成的传播器
         */
        fun fromTle(tle: ParsedTleElements): Sgp4Satellite {
            val sat = Sgp4Satellite(
                satnum = tle.catalogNumber.toString(),
                epochyr = tle.epochYear,
                jdsatepoch = tle.jdsatepoch,
                epochdays = tle.epochDays,
                ndot = tle.ndotRadMin2,
                nddot = tle.nddotRadMin3,
                bstar = tle.bstar,
                inclo = tle.inclinationRad,
                nodeo = tle.raanRad,
                ecco = tle.eccentricity,
                argpo = tle.argPerigeeRad,
                mo = tle.meanAnomalyRad,
                no = tle.meanMotionRadMin,
            )
            sat.sgp4init()
            return sat
        }
    }

    /**
     * SGP4/SDP4 初始化：近地公共项 + 深空项（等价 Vallado sgp4init）。
     */
    private fun sgp4init() {
        isimp = 0
        method = 'n'

        val ss = 78.0 / Sgp4Constants.EARTH_RADIUS_KM + 1.0
        val qzms2tTemp = (120.0 - 78.0) / Sgp4Constants.EARTH_RADIUS_KM
        val qzms2t = qzms2tTemp * qzms2tTemp * qzms2tTemp * qzms2tTemp

        t = 0.0

        // initl：恢复无 Kozai 的平均运动、半长轴与历元 GMST
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio

        var noKozai = no
        val ak = (Sgp4Constants.XKE / noKozai).pow(Sgp4Constants.X2O3)
        val d1 = (0.75 * Sgp4Constants.J2 * (3.0 * cosio2 - 1.0)) / (rteosq * omeosq)
        var delPrime = d1 / (ak * ak)
        val adel = ak * (1.0 - delPrime * delPrime -
            delPrime * (1.0 / 3.0 + (134.0 * delPrime * delPrime) / 81.0))
        delPrime = d1 / (adel * adel)
        noKozai /= 1.0 + delPrime

        val ao = (Sgp4Constants.XKE / noKozai).pow(Sgp4Constants.X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        // 历元 GMST（改进模式 'i' 用现代公式）
        val epoch = jdsatepoch - 2433281.5
        gsto = OrbitMath.gstime(epoch + 2433281.5)

        // a = (no * tumin)^(-2/3)
        val a = (noKozai * Sgp4Constants.TUMIN).pow(-2.0 / 3.0)

        if (omeosq >= 0.0 || noKozai >= 0.0) {
            isimp = 0
            if (rp < 220.0 / Sgp4Constants.EARTH_RADIUS_KM + 1.0) isimp = 1
            var sfour = ss
            var qzms24 = qzms2t
            val perige = (rp - 1.0) * Sgp4Constants.EARTH_RADIUS_KM

            if (perige < 156.0) {
                sfour = perige - 78.0
                if (perige < 98.0) sfour = 20.0
                val qzms24Temp = (120.0 - sfour) / Sgp4Constants.EARTH_RADIUS_KM
                qzms24 = qzms24Temp * qzms24Temp * qzms24Temp * qzms24Temp
                sfour = sfour / Sgp4Constants.EARTH_RADIUS_KM + 1.0
            }
            val pinvsq = 1.0 / posq

            val tsi = 1.0 / (ao - sfour)
            eta = ao * ecco * tsi
            val etasq = eta * eta
            val eeta = ecco * eta
            val psisq = abs(1.0 - etasq)
            val coef = qzms24 * (tsi).pow(4.0)
            val coef1 = coef / (psisq).pow(3.5)
            val cc2 = coef1 * noKozai *
                (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                    ((0.375 * Sgp4Constants.J2 * tsi) / psisq) * con41 *
                    (8.0 + 3.0 * etasq * (8.0 + etasq)))
            cc1 = bstar * cc2
            var cc3 = 0.0
            if (ecco > 1.0e-4) {
                cc3 = (-2.0 * coef * tsi * Sgp4Constants.J3OJ2 * noKozai * sinio) / ecco
            }
            x1mth2 = 1.0 - cosio2
            cc4 = 2.0 * noKozai * coef1 * ao * omeosq *
                (eta * (2.0 + 0.5 * etasq) +
                    ecco * (0.5 + 2.0 * etasq) -
                    ((Sgp4Constants.J2 * tsi) / (ao * psisq)) *
                        (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                            0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) *
                                cos(2.0 * argpo)))
            cc5 = 2.0 * coef1 * ao * omeosq *
                (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
            val cosio4 = cosio2 * cosio2
            val temp1 = 1.5 * Sgp4Constants.J2 * pinvsq * noKozai
            val temp2 = 0.5 * temp1 * Sgp4Constants.J2 * pinvsq
            val temp3 = -0.46875 * Sgp4Constants.J4 * pinvsq * pinvsq * noKozai
            mdot = noKozai + 0.5 * temp1 * rteosq * con41 +
                0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
            argpdot = -0.5 * temp1 * con42 +
                0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
            val xhdot1 = -temp1 * cosio
            nodedot = xhdot1 +
                (0.5 * temp2 * (4.0 - 19.0 * cosio2) +
                    2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
            val xpidot = argpdot + nodedot
            omgcof = bstar * cc3 * cos(argpo)
            xmcof = 0.0
            if (ecco > 1.0e-4) {
                xmcof = (-Sgp4Constants.X2O3 * coef * bstar) / eeta
            }
            nodecf = 3.5 * omeosq * xhdot1 * cc1
            t2cof = 1.5 * cc1

            // 倾角 180° 除零保护
            xlcof = if (abs(cosio + 1.0) > TEMP4) {
                (-0.25 * Sgp4Constants.J3OJ2 * sinio * (3.0 + 5.0 * cosio)) / (1.0 + cosio)
            } else {
                (-0.25 * Sgp4Constants.J3OJ2 * sinio * (3.0 + 5.0 * cosio)) / TEMP4
            }
            aycof = -0.5 * Sgp4Constants.J3OJ2 * sinio

            val delmotemp = 1.0 + eta * cos(mo)
            delmo = delmotemp * delmotemp * delmotemp
            sinmao = sin(mo)
            x7thm1 = 7.0 * cosio2 - 1.0

            // ── 深空初始化（SDP4）──
            if ((2 * PI) / noKozai >= DEEP_SPACE_PERIOD_MIN) {
                method = 'd'
                isimp = 1

                // dscom：深空公共项（日月摄动几何）
                val dscom = dscom(epoch, ecco, argpo, inclo, nodeo, noKozai)
                e3 = dscom.e3; ee2 = dscom.ee2
                peo = dscom.peo; pgho = dscom.pgho; pho = dscom.pho
                pinco = dscom.pinco; plo = dscom.plo
                se2 = dscom.se2; se3 = dscom.se3
                sgh2 = dscom.sgh2; sgh3 = dscom.sgh3; sgh4 = dscom.sgh4
                sh2 = dscom.sh2; sh3 = dscom.sh3
                si2 = dscom.si2; si3 = dscom.si3
                sl2 = dscom.sl2; sl3 = dscom.sl3; sl4 = dscom.sl4
                xgh2 = dscom.xgh2; xgh3 = dscom.xgh3; xgh4 = dscom.xgh4
                xh2 = dscom.xh2; xh3 = dscom.xh3
                xi2 = dscom.xi2; xi3 = dscom.xi3
                xl2 = dscom.xl2; xl3 = dscom.xl3; xl4 = dscom.xl4
                zmol = dscom.zmol; zmos = dscom.zmos

                // dpper（初始化阶段，init='y'，零时刻周期项）
                val dpperInit = dpper(init = true, ep = ecco, inclp = inclo,
                    nodep = nodeo, argpp = argpo, mp = mo)
                var eArg = dpperInit.ep
                var inclArg = dpperInit.inclp
                var nodeArg = dpperInit.nodep
                var argpArg = dpperInit.argpp
                var mArg = dpperInit.mp

                // dsinit：深空共振项
                val dsinit = dsinit(
                    cosim = dscom.cosim, emsq = dscom.emsq,
                    argpo = argpArg, s1 = dscom.s1, s2 = dscom.s2, s3 = dscom.s3,
                    s4 = dscom.s4, s5 = dscom.s5, sinim = dscom.sinim,
                    ss1 = dscom.ss1, ss2 = dscom.ss2, ss3 = dscom.ss3,
                    ss4 = dscom.ss4, ss5 = dscom.ss5,
                    sz1 = dscom.sz1, sz3 = dscom.sz3,
                    sz11 = dscom.sz11, sz13 = dscom.sz13,
                    sz21 = dscom.sz21, sz23 = dscom.sz23,
                    sz31 = dscom.sz31, sz33 = dscom.sz33,
                    gsto = gsto, mo = mArg, mdot = mdot, no = noKozai,
                    nodeo = nodeArg, nodedot = nodedot, xpidot = xpidot,
                    z1 = dscom.z1, z3 = dscom.z3,
                    z11 = dscom.z11, z13 = dscom.z13,
                    z21 = dscom.z21, z23 = dscom.z23,
                    z31 = dscom.z31, z33 = dscom.z33,
                    ecco = eArg, eccsq = eccsq, em = eArg, argpm = argpArg,
                    inclm = inclArg, mm = mArg, nm = noKozai, nodem = nodeArg,
                )
                irez = dsinit.irez
                atime = dsinit.atime
                d2201 = dsinit.d2201; d2211 = dsinit.d2211
                d3210 = dsinit.d3210; d3222 = dsinit.d3222
                d4410 = dsinit.d4410; d4422 = dsinit.d4422
                d5220 = dsinit.d5220; d5232 = dsinit.d5232
                d5421 = dsinit.d5421; d5433 = dsinit.d5433
                dedt = dsinit.dedt; didt = dsinit.didt
                dmdt = dsinit.dmdt; dnodt = dsinit.dnodt; domdt = dsinit.domdt
                del1 = dsinit.del1; del2 = dsinit.del2; del3 = dsinit.del3
                xfact = dsinit.xfact; xlamo = dsinit.xlamo
                xli = dsinit.xli; xni = dsinit.xni
            }

            // 非简化模式下的高阶拖拽项
            if (isimp != 1) {
                val cc1sq = cc1 * cc1
                d2 = 4.0 * ao * tsi * cc1sq
                val temp = (d2 * tsi * cc1) / 3.0
                d3 = (17.0 * ao + sfour) * temp
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
                t3cof = d2 + 2.0 * cc1sq
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 +
                    15.0 * cc1sq * (2.0 * d2 + cc1sq))
            }
        }

        // 传播到零时刻完成初始化（保持与标准实现一致）
        propagateRaw(0.0)
    }

    /**
     * 传播到 [tsince]（距历元分钟）并返回 TEME 位置/速度。
     *
     * @param tsince 距历元的时间（分钟）
     * @return ECI 位置（km）与速度（km/s）；轨道参数越界/卫星已再入时返回 null
     */
    fun propagate(tsince: Double): EciState? = propagateRaw(tsince)

    private fun propagateRaw(tsince: Double): EciState? {
        t = tsince

        // ── 世俗引力与大气拖拽更新 ──
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2

        if (isimp != 1) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        tempa = tempa

        var nm = no
        var em = ecco
        var inclm = inclo

        // ── 深空共振/日月世俗项 ──
        if (method == 'd') {
            val dspace = dspace(
                irez = irez, argpo = argpo, argpdot = argpdot, t = t,
                gsto = gsto, no = no,
                del1 = del1, del2 = del2, del3 = del3,
                em = em, argpm = argpm, inclm = inclm,
                mm = mm, nodem = nodem, nm = nm,
            )
            em = dspace.em
            argpm = dspace.argpm
            inclm = dspace.inclm
            mm = dspace.mm
            nodem = dspace.nodem
            nm = dspace.nm
        }

        if (nm <= 0.0) return null

        val am = (Sgp4Constants.XKE / nm).pow(Sgp4Constants.X2O3) * tempa * tempa
        nm = Sgp4Constants.XKE / (am).pow(1.5)
        em -= tempe

        if (em >= 1.0 || em < -0.001) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += no * templ
        var xlm = mm + argpm + nodem

        nodem %= Sgp4Constants.TWO_PI
        argpm %= Sgp4Constants.TWO_PI
        xlm %= Sgp4Constants.TWO_PI
        mm = (xlm - argpm - nodem) % Sgp4Constants.TWO_PI

        val sinim = sin(inclm)
        val cosim = cos(inclm)

        // ── 日月长期周期项（仅深空）──
        var ep = em
        var xincp = inclm
        var argpp = argpm
        var nodep = nodem
        var mp = mm
        var sinip = sinim
        var cosip = cosim

        if (method == 'd') {
            val dpper = dpper(init = false, ep = ep, inclp = xincp,
                nodep = nodep, argpp = argpp, mp = mp)
            ep = dpper.ep
            nodep = dpper.nodep
            argpp = dpper.argpp
            mp = dpper.mp
            xincp = dpper.inclp

            if (xincp < 0.0) {
                xincp = -xincp
                nodep += PI
                argpp -= PI
            }
            if (ep < 0.0 || ep > 1.0) return null
        }

        // ── 长期周期项 ──
        if (method == 'd') {
            sinip = sin(xincp)
            cosip = cos(xincp)
            aycof = -0.5 * Sgp4Constants.J3OJ2 * sinip
            xlcof = if (abs(cosip + 1.0) > TEMP4) {
                (-0.25 * Sgp4Constants.J3OJ2 * sinip * (3.0 + 5.0 * cosip)) / (1.0 + cosip)
            } else {
                (-0.25 * Sgp4Constants.J3OJ2 * sinip * (3.0 + 5.0 * cosip)) / TEMP4
            }
        }

        val axnl = ep * cos(argpp)
        val temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl

        // ── 解 Kepler 方程 ──
        val u = (xl - nodep) % Sgp4Constants.TWO_PI
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr += 1
        }

        // ── 短周期初步量 ──
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null

        val rl = am * (1.0 - ecose)
        val rdotl = (sqrt(am) * esine) / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        val tempR = esine / (1.0 + betal)
        val sinu = (am / rl) * (sineo1 - aynl - axnl * tempR)
        val cosu = (am / rl) * (coseo1 - axnl + aynl * tempR)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        val tempP = 1.0 / pl
        val temp1 = 0.5 * Sgp4Constants.J2 * tempP
        val temp2 = temp1 * tempP

        // 短周期项更新
        if (method == 'd') {
            val cosisq = cosip * cosip
            con41 = 3.0 * cosisq - 1.0
            x1mth2 = 1.0 - cosisq
            x7thm1 = 7.0 * cosisq - 1.0
        }

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) +
            0.5 * temp1 * x1mth2 * cos2u
        if (mrt < 1.0) return null // 卫星已再入

        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - (nm * temp1 * x1mth2 * sin2u) / Sgp4Constants.XKE
        val rvdot = rvdotl + (nm * temp1 * (x1mth2 * cos2u + 1.5 * con41)) / Sgp4Constants.XKE

        // ── 方向向量与位置/速度（km 与 km/s）──
        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        val r = doubleArrayOf(
            mrt * ux * Sgp4Constants.EARTH_RADIUS_KM,
            mrt * uy * Sgp4Constants.EARTH_RADIUS_KM,
            mrt * uz * Sgp4Constants.EARTH_RADIUS_KM,
        )
        val v = doubleArrayOf(
            (mvt * ux + rvdot * vx) * Sgp4Constants.VKM_PER_SEC,
            (mvt * uy + rvdot * vy) * Sgp4Constants.VKM_PER_SEC,
            (mvt * uz + rvdot * vz) * Sgp4Constants.VKM_PER_SEC,
        )
        // 轨道相位（rad，[0, 2π)）：平经度 − 节点 − 近地点，即含长期项的平近点角
        val phase = OrbitMath.mod2pi(xlm - argpm - nodem)
        return EciState(r, v, phase)
    }

    // ────────────────────────── 深空子过程 ──────────────────────────

    /**
     * 深空公共项（dscom）：计算日月摄动的几何常量，供 dpper/dsinit 使用。
     * 等价 Vallado dscom。
     */
    private fun dscom(
        epoch: Double,
        ep: Double,
        argpp: Double,
        inclp: Double,
        nodep: Double,
        np: Double,
    ): DscomResult {
        val zes = 0.01675
        val zel = 0.0549
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458

        val nm = np
        val em = ep
        val snodm = sin(nodep)
        val cnodm = cos(nodep)
        val sinomm = sin(argpp)
        val cosomm = cos(argpp)
        val sinim = sin(inclp)
        val cosim = cos(inclp)
        val emsq = em * em
        val betasq = 1.0 - emsq
        val rtemsq = sqrt(betasq)

        val peo = 0.0; val pinco = 0.0; val plo = 0.0; val pgho = 0.0; val pho = 0.0
        val day = epoch + 18261.5 + 0.0 / 1440.0
        val xnodce = (4.523602 - 9.2422029e-4 * day) % Sgp4Constants.TWO_PI
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = (0.089683511 * stem) / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        val gam = 5.8351514 + 0.001944368 * day
        var zx = (0.39785416 * stem) / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx += gam - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)

        var zcosg = zcosgs
        var zsing = zsings
        var zcosi = zcosis
        var zsini = zsinis
        var zcosh = cnodm
        var zsinh = snodm
        var cc = c1ss
        val xnoi = 1.0 / nm

        var ss1 = 0.0; var ss2 = 0.0; var ss3 = 0.0; var ss4 = 0.0; var ss5 = 0.0
        var ss6 = 0.0; var ss7 = 0.0
        var sz1 = 0.0; var sz2 = 0.0; var sz3 = 0.0
        var sz11 = 0.0; var sz12 = 0.0; var sz13 = 0.0
        var sz21 = 0.0; var sz22 = 0.0; var sz23 = 0.0
        var sz31 = 0.0; var sz32 = 0.0; var sz33 = 0.0
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var s5 = 0.0
        var s6 = 0.0; var s7 = 0.0
        var z1 = 0.0; var z2 = 0.0; var z3 = 0.0
        var z11 = 0.0; var z12 = 0.0; var z13 = 0.0
        var z21 = 0.0; var z22 = 0.0; var z23 = 0.0
        var z31 = 0.0; var z32 = 0.0; var z33 = 0.0

        var lsflg = 0
        while (lsflg < 2) {
            lsflg += 1
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = cosim * a7 + sinim * a8
            val a4 = cosim * a9 + sinim * a10
            val a5 = -sinim * a7 + cosim * a8
            val a6 = -sinim * a9 + cosim * a10

            val x1 = a1 * cosomm + a2 * sinomm
            val x2 = a3 * cosomm + a4 * sinomm
            val x3 = -a1 * sinomm + a2 * cosomm
            val x4 = -a3 * sinomm + a4 * cosomm
            val x5 = a5 * sinomm
            val x6 = a6 * sinomm
            val x7 = a5 * cosomm
            val x8 = a6 * cosomm

            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4

            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq

            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            z12 = -6.0 * (a1 * a6 + a3 * a5) +
                emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5))
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)

            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            z22 = 6.0 * (a4 * a5 + a2 * a6) +
                emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)

            z1 = z1 + z1 + betasq * z31
            z2 = z2 + z2 + betasq * z32
            z3 = z3 + z3 + betasq * z33
            s3 = cc * xnoi
            s2 = (-0.5 * s3) / rtemsq
            s4 = s3 * rtemsq
            s1 = -15.0 * em * s4
            s5 = x1 * x3 + x2 * x4
            s6 = x2 * x3 + x1 * x4
            s7 = x2 * x4 - x1 * x3

            if (lsflg == 1) {
                ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5
                ss6 = s6; ss7 = s7
                sz1 = z1; sz2 = z2; sz3 = z3
                sz11 = z11; sz12 = z12; sz13 = z13
                sz21 = z21; sz22 = z22; sz23 = z23
                sz31 = z31; sz32 = z32; sz33 = z33
                zcosg = zcosgl
                zsing = zsingl
                zcosi = zcosil
                zsini = zsinil
                zcosh = zcoshl * cnodm + zsinhl * snodm
                zsinh = snodm * zcoshl - cnodm * zsinhl
                cc = c1l
            }
        }

        val zmolR = (4.7199672 + (0.2299715 * day - gam)) % Sgp4Constants.TWO_PI
        val zmosR = (6.2565837 + 0.017201977 * day) % Sgp4Constants.TWO_PI

        val se2 = 2.0 * ss1 * ss6
        val se3 = 2.0 * ss1 * ss7
        val si2 = 2.0 * ss2 * sz12
        val si3 = 2.0 * ss2 * (sz13 - sz11)
        val sl2 = -2.0 * ss3 * sz2
        val sl3 = -2.0 * ss3 * (sz3 - sz1)
        val sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes
        val sgh2 = 2.0 * ss4 * sz32
        val sgh3 = 2.0 * ss4 * (sz33 - sz31)
        val sgh4 = -18.0 * ss4 * zes
        val sh2 = -2.0 * ss2 * sz22
        val sh3 = -2.0 * ss2 * (sz23 - sz21)

        val ee2 = 2.0 * s1 * s6
        val e3 = 2.0 * s1 * s7
        val xi2 = 2.0 * s2 * z12
        val xi3 = 2.0 * s2 * (z13 - z11)
        val xl2 = -2.0 * s3 * z2
        val xl3 = -2.0 * s3 * (z3 - z1)
        val xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel
        val xgh2 = 2.0 * s4 * z32
        val xgh3 = 2.0 * s4 * (z33 - z31)
        val xgh4 = -18.0 * s4 * zel
        val xh2 = -2.0 * s2 * z22
        val xh3 = -2.0 * s2 * (z23 - z21)

        return DscomResult(
            sinim = sinim, cosim = cosim, emsq = emsq,
            e3 = e3, ee2 = ee2, peo = peo, pgho = pgho, pho = pho,
            pinco = pinco, plo = plo,
            se2 = se2, se3 = se3, sgh2 = sgh2, sgh3 = sgh3, sgh4 = sgh4,
            sh2 = sh2, sh3 = sh3, si2 = si2, si3 = si3,
            sl2 = sl2, sl3 = sl3, sl4 = sl4,
            xgh2 = xgh2, xgh3 = xgh3, xgh4 = xgh4, xh2 = xh2, xh3 = xh3,
            xi2 = xi2, xi3 = xi3, xl2 = xl2, xl3 = xl3, xl4 = xl4,
            s1 = s1, s2 = s2, s3 = s3, s4 = s4, s5 = s5,
            ss1 = ss1, ss2 = ss2, ss3 = ss3, ss4 = ss4, ss5 = ss5,
            sz1 = sz1, sz3 = sz3, sz11 = sz11, sz13 = sz13,
            sz21 = sz21, sz23 = sz23, sz31 = sz31, sz33 = sz33,
            z1 = z1, z3 = z3, z11 = z11, z13 = z13,
            z21 = z21, z23 = z23, z31 = z31, z33 = z33,
            zmol = zmolR, zmos = zmosR,
        )
    }

    /**
     * 深空长期周期项（dpper）：日月摄动对平根数的长期周期贡献。
     *
     * @param init true = 初始化调用（零时刻，不叠加基准量）
     */
    private fun dpper(
        init: Boolean,
        ep: Double,
        inclp: Double,
        nodep: Double,
        argpp: Double,
        mp: Double,
    ): DpperResult {
        var epR = ep
        var inclpR = inclp
        var nodepR = nodep
        var argppR = argpp
        var mpR = mp

        val zns = 1.19459e-5
        val zes = 0.01675
        val znl = 1.5835218e-4
        val zel = 0.0549

        var zm = zmos + zns * t
        if (init) zm = zmos

        var zf = zm + 2.0 * zes * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)

        val ses = se2 * f2 + se3 * f3
        val sis = si2 * f2 + si3 * f3
        val sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf
        val sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf
        val shs = sh2 * f2 + sh3 * f3

        zm = zmol + znl * t
        if (init) zm = zmol

        zf = zm + 2.0 * zel * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)

        val sel = ee2 * f2 + e3 * f3
        val sil = xi2 * f2 + xi3 * f3
        val sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf
        val sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf
        val shll = xh2 * f2 + xh3 * f3

        var pe = ses + sel
        var pinc = sis + sil
        var pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll

        if (!init) {
            pe -= peo
            pinc -= pinco
            pl -= plo
            pgh -= pgho
            ph -= pho
            inclpR += pinc
            epR += pe
            val sinip = sin(inclpR)
            val cosip = cos(inclpR)

            // Lyddane 修正分支（倾角 ≥ 0.2 rad 时直接叠加，否则用 Lyddane 变换）
            if (inclpR >= 0.2) {
                ph /= sinip
                pgh -= cosip * ph
                argppR += pgh
                nodepR += ph
                mpR += pl
            } else {
                val sinop = sin(nodepR)
                val cosop = cos(nodepR)
                var alfdp = sinip * sinop
                var betdp = sinip * cosop
                val dalf = ph * cosop + pinc * cosip * sinop
                val dbet = -ph * sinop + pinc * cosip * cosop
                alfdp += dalf
                betdp += dbet
                nodepR %= Sgp4Constants.TWO_PI
                val xls = mpR + argppR + cosip * nodepR
                val dls = pl + pgh - pinc * nodepR * sinip
                val xlsR = xls + dls
                val xnoh = nodepR
                nodepR = atan2(alfdp, betdp)
                if (nodepR < 0.0) nodepR += Sgp4Constants.TWO_PI
                if (abs(xnoh - nodepR) > PI) {
                    nodepR = if (nodepR < xnoh) nodepR + Sgp4Constants.TWO_PI
                    else nodepR - Sgp4Constants.TWO_PI
                }
                mpR += pl
                argppR = xlsR - mpR - cosip * nodepR
            }
        }

        return DpperResult(epR, inclpR, nodepR, argppR, mpR)
    }

    /**
     * 深空共振初始化（dsinit）：半日/一日共振对平均运动变化率的贡献。
     * 等价 Vallado dsinit。
     */
    private fun dsinit(
        cosim: Double, emsq: Double, argpo: Double,
        s1: Double, s2: Double, s3: Double, s4: Double, s5: Double,
        sinim: Double,
        ss1: Double, ss2: Double, ss3: Double, ss4: Double, ss5: Double,
        sz1: Double, sz3: Double, sz11: Double, sz13: Double,
        sz21: Double, sz23: Double, sz31: Double, sz33: Double,
        gsto: Double, mo: Double, mdot: Double, no: Double,
        nodeo: Double, nodedot: Double, xpidot: Double,
        z1: Double, z3: Double, z11: Double, z13: Double,
        z21: Double, z23: Double, z31: Double, z33: Double,
        ecco: Double, eccsq: Double,
        em: Double, argpm: Double, inclm: Double, mm: Double, nm: Double, nodem: Double,
    ): DsinitResult {
        var emR = em
        var argpmR = argpm
        var inclmR = inclm
        var mmR = mm
        var nmR = nm
        var nodemR = nodem

        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val rptim = 4.37526908801129966e-3
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7
        val znl = 1.5835218e-4
        val zns = 1.19459e-5

        var irezR = 0
        if (nmR < 0.0052359877 && nmR > 0.0034906585) irezR = 1
        if (nmR >= 8.26e-3 && nmR <= 9.24e-3 && emR >= 0.5) irezR = 2

        // 太阳项
        val ses = ss1 * zns * ss5
        val sis = ss2 * zns * (sz11 + sz13)
        val sls = -zns * ss3 * (sz1 + sz3 - 14.0 - 6.0 * emsq)
        val sghs = ss4 * zns * (sz31 + sz33 - 6.0)
        var shs = -zns * ss2 * (sz21 + sz23)
        if (inclmR < 5.2359877e-2 || inclmR > PI - 5.2359877e-2) shs = 0.0
        if (sinim != 0.0) shs /= sinim
        val sgs = sghs - cosim * shs

        // 月球项
        var dedtR = ses + s1 * znl * s5
        var didtR = sis + s2 * znl * (z11 + z13)
        var dmdtR = sls - znl * s3 * (z1 + z3 - 14.0 - 6.0 * emsq)
        val sghl = s4 * znl * (z31 + z33 - 6.0)
        var shll = -znl * s2 * (z21 + z23)
        if (inclmR < 5.2359877e-2 || inclmR > PI - 5.2359877e-2) shll = 0.0
        var domdtR = sgs + sghl
        var dnodtR = shs
        if (sinim != 0.0) {
            domdtR -= (cosim / sinim) * shll
            dnodtR += shll / sinim
        }

        val dndt = 0.0
        val theta = (gsto + 0.0 * rptim) % Sgp4Constants.TWO_PI
        emR += dedtR * 0.0
        inclmR += didtR * 0.0
        argpmR += domdtR * 0.0
        nodemR += dnodtR * 0.0
        mmR += dmdtR * 0.0

        var del1R = 0.0; var del2R = 0.0; var del3R = 0.0
        var xfactR = 0.0; var xlamoR = 0.0
        var xliR = 0.0; var xniR = 0.0
        var d2201R = 0.0; var d2211R = 0.0
        var d3210R = 0.0; var d3222R = 0.0
        var d4410R = 0.0; var d4422R = 0.0
        var d5220R = 0.0; var d5232R = 0.0
        var d5421R = 0.0; var d5433R = 0.0

        if (irezR != 0) {
            val aonv = (nmR / Sgp4Constants.XKE).pow(Sgp4Constants.X2O3)

            if (irezR == 2) {
                // 半日共振（12h 轨道）
                val cosisq = cosim * cosim
                val emo = emR
                emR = ecco
                val emsqo = emsq
                val emsqR = eccsq
                val eoc = emR * emsqR
                val g201 = -0.306 - (emR - 0.64) * 0.44

                val (g211, g310, g322, g410, g422, g520) = if (emR <= 0.65) {
                    Quadruple(
                        g211 = 3.616 - 13.247 * emR + 16.29 * emsqR,
                        g310 = -19.302 + 117.39 * emR - 228.419 * emsqR + 156.591 * eoc,
                        g322 = -18.9068 + 109.7927 * emR - 214.6334 * emsqR + 146.5816 * eoc,
                        g410 = -41.122 + 242.694 * emR - 471.094 * emsqR + 313.953 * eoc,
                        g422 = -146.407 + 841.88 * emR - 1629.014 * emsqR + 1083.435 * eoc,
                        g520 = -532.114 + 3017.977 * emR - 5740.032 * emsqR + 3708.276 * eoc,
                    )
                } else {
                    val g520V = if (emR > 0.715) {
                        -5149.66 + 29936.92 * emR - 54087.36 * emsqR + 31324.56 * eoc
                    } else {
                        1464.74 - 4664.75 * emR + 3763.64 * emsqR
                    }
                    Quadruple(
                        g211 = -72.099 + 331.819 * emR - 508.738 * emsqR + 266.724 * eoc,
                        g310 = -346.844 + 1582.851 * emR - 2415.925 * emsqR + 1246.113 * eoc,
                        g322 = -342.585 + 1554.908 * emR - 2366.899 * emsqR + 1215.972 * eoc,
                        g410 = -1052.797 + 4758.686 * emR - 7193.992 * emsqR + 3651.957 * eoc,
                        g422 = -3581.69 + 16178.11 * emR - 24462.77 * emsqR + 12422.52 * eoc,
                        g520 = g520V,
                    )
                }
                val (g533, g521, g532) = if (emR < 0.7) {
                    Triple(
                        -919.2277 + 4988.61 * emR - 9064.77 * emsqR + 5542.21 * eoc,
                        -822.71072 + 4568.6173 * emR - 8491.4146 * emsqR + 5337.524 * eoc,
                        -853.666 + 4690.25 * emR - 8624.77 * emsqR + 5341.4 * eoc,
                    )
                } else {
                    Triple(
                        -37995.78 + 161616.52 * emR - 229838.2 * emsqR + 109377.94 * eoc,
                        -51752.104 + 218913.95 * emR - 309468.16 * emsqR + 146349.42 * eoc,
                        -40023.88 + 170470.89 * emR - 242699.48 * emsqR + 115605.82 * eoc,
                    )
                }
                val sini2 = sinim * sinim
                val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
                val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.375 * sini2 * sini2
                val f522 = 9.84375 * sinim *
                    (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) +
                        0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq))
                val f523 = sinim *
                    (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) +
                        6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq))
                val f542 = 29.53125 * sinim *
                    (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq))
                val f543 = 29.53125 * sinim *
                    (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq))

                val xno2 = nmR * nmR
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201R = temp * f220 * g201
                d2211R = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * root32
                d3210R = temp * f321 * g310
                d3222R = temp * f322 * g322
                temp1 *= aonv
                temp = 2.0 * temp1 * root44
                d4410R = temp * f441 * g410
                d4422R = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * root52
                d5220R = temp * f522 * g520
                d5232R = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421R = temp * f542 * g521
                d5433R = temp * f543 * g533
                xlamoR = (mo + nodeo + nodeo - (theta + theta)) % Sgp4Constants.TWO_PI
                xfactR = mdot + dmdtR + 2.0 * (nodedot + dnodtR - rptim) - no
                emR = emo
                // emsq 恢复为调用值（与标准实现一致）
            }

            if (irezR == 1) {
                // 一日共振（同步轨道）
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
                var f330 = 1.0 + cosim
                f330 = 1.875 * f330 * f330 * f330
                del1R = 3.0 * nmR * nmR * aonv * aonv
                del2R = 2.0 * del1R * f220 * g200 * q22
                del3R = 3.0 * del1R * f330 * g300 * q33 * aonv
                del1R = del1R * f311 * g310 * q31 * aonv
                xlamoR = (mo + nodeo + argpo - theta) % Sgp4Constants.TWO_PI
                xfactR = mdot + xpidot + dmdtR + domdtR + dnodtR - (no + rptim)
            }

            // 初始化积分器
            xliR = xlamoR
            xniR = no
            atime = 0.0
            nmR = no + dndt
        }

        return DsinitResult(
            em = emR, argpm = argpmR, inclm = inclmR, mm = mmR, nm = nmR, nodem = nodemR,
            irez = irezR, atime = 0.0,
            d2201 = d2201R, d2211 = d2211R, d3210 = d3210R, d3222 = d3222R,
            d4410 = d4410R, d4422 = d4422R, d5220 = d5220R, d5232 = d5232R,
            d5421 = d5421R, d5433 = d5433R,
            dedt = dedtR, didt = didtR, dmdt = dmdtR, dnodt = dnodtR, domdt = domdtR,
            del1 = del1R, del2 = del2R, del3 = del3R,
            xfact = xfactR, xlamo = xlamoR, xli = xliR, xni = xniR,
        )
    }

    /**
     * 深空共振数值积分（dspace）：欧拉-麦克劳林积分更新共振项。
     * 等价 Vallado dspace。
     */
    private fun dspace(
        irez: Int, argpo: Double, argpdot: Double, t: Double,
        gsto: Double, no: Double,
        del1: Double, del2: Double, del3: Double,
        em: Double, argpm: Double, inclm: Double, mm: Double, nodem: Double, nm: Double,
    ): DspaceResult {
        var emR = em
        var argpmR = argpm
        var inclmR = inclm
        var mmR = mm
        var nodemR = nodem
        var nmR = nm

        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.050833
        val g54 = 4.4108898
        val rptim = 4.37526908801129966e-3
        val stepp = 720.0
        val stepn = -720.0
        val step2 = 259200.0

        val theta = (gsto + t * rptim) % Sgp4Constants.TWO_PI
        emR += dedt * t
        inclmR += didt * t
        argpmR += domdt * t
        nodemR += dnodt * t
        mmR += dmdt * t

        var atimeR = atime
        var xliR = xli
        var xniR = xni
        var dndt = 0.0
        var xndt = 0.0
        var xnddt = 0.0
        var xldot = 0.0
        var ft = 0.0

        if (irez != 0) {
            if (atimeR == 0.0 || t * atimeR <= 0.0 || abs(t) < abs(atimeR)) {
                atimeR = 0.0
                xniR = no
                xliR = xlamo
            }

            val delt = if (t > 0.0) stepp else stepn

            var iretn = 381
            while (iretn == 381) {
                if (irez != 2) {
                    // 近同步共振项
                    xndt = del1 * sin(xliR - fasx2) +
                        del2 * sin(2.0 * (xliR - fasx4)) +
                        del3 * sin(3.0 * (xliR - fasx6))
                    xldot = xniR + xfact
                    xnddt = del1 * cos(xliR - fasx2) +
                        2.0 * del2 * cos(2.0 * (xliR - fasx4)) +
                        3.0 * del3 * cos(3.0 * (xliR - fasx6))
                    xnddt *= xldot
                } else {
                    // 半日共振项
                    val xomi = argpo + argpdot * atimeR
                    val x2omi = xomi + xomi
                    val x2li = xliR + xliR
                    xndt = d2201 * sin(x2omi + xliR - g22) +
                        d2211 * sin(xliR - g22) +
                        d3210 * sin(xomi + xliR - g32) +
                        d3222 * sin(-xomi + xliR - g32) +
                        d4410 * sin(x2omi + x2li - g44) +
                        d4422 * sin(x2li - g44) +
                        d5220 * sin(xomi + xliR - g52) +
                        d5232 * sin(-xomi + xliR - g52) +
                        d5421 * sin(xomi + x2li - g54) +
                        d5433 * sin(-xomi + x2li - g54)
                    xldot = xniR + xfact
                    xnddt = d2201 * cos(x2omi + xliR - g22) +
                        d2211 * cos(xliR - g22) +
                        d3210 * cos(xomi + xliR - g32) +
                        d3222 * cos(-xomi + xliR - g32) +
                        d5220 * cos(xomi + xliR - g52) +
                        d5232 * cos(-xomi + xliR - g52) +
                        2.0 * (d4410 * cos(x2omi + x2li - g44) +
                            d4422 * cos(x2li - g44) +
                            d5421 * cos(xomi + x2li - g54) +
                            d5433 * cos(-xomi + x2li - g54))
                    xnddt *= xldot
                }

                if (abs(t - atimeR) >= stepp) {
                    iretn = 381
                } else {
                    ft = t - atimeR
                    iretn = 0
                }
                if (iretn == 381) {
                    xliR += xldot * delt + xndt * step2
                    xniR += xndt * delt + xnddt * step2
                    atimeR += delt
                }
            }

            nmR = xniR + xndt * ft + xnddt * ft * ft * 0.5
            val xl = xliR + xldot * ft + xndt * ft * ft * 0.5
            if (irez != 1) {
                mmR = xl - 2.0 * nodemR + 2.0 * theta
                dndt = nmR - no
            } else {
                mmR = xl - nodemR - argpmR + theta
                dndt = nmR - no
            }
            nmR = no + dndt
        }

        atime = atimeR
        xli = xliR
        xni = xniR
        return DspaceResult(emR, argpmR, inclmR, mmR, nodemR, nmR)
    }
}

// ────────────────────────── 中间结果类型 ──────────────────────────

/** TEME 惯性系位置（km）、速度（km/s）与轨道相位（rad） */
data class EciState(
    val position: DoubleArray,
    val velocity: DoubleArray,
    val phaseRad: Double,
)

/** dscom 结果 */
internal class DscomResult(
    val sinim: Double, val cosim: Double, val emsq: Double,
    val e3: Double, val ee2: Double, val peo: Double, val pgho: Double, val pho: Double,
    val pinco: Double, val plo: Double,
    val se2: Double, val se3: Double,
    val sgh2: Double, val sgh3: Double, val sgh4: Double,
    val sh2: Double, val sh3: Double,
    val si2: Double, val si3: Double,
    val sl2: Double, val sl3: Double, val sl4: Double,
    val xgh2: Double, val xgh3: Double, val xgh4: Double,
    val xh2: Double, val xh3: Double,
    val xi2: Double, val xi3: Double,
    val xl2: Double, val xl3: Double, val xl4: Double,
    val s1: Double, val s2: Double, val s3: Double, val s4: Double, val s5: Double,
    val ss1: Double, val ss2: Double, val ss3: Double, val ss4: Double, val ss5: Double,
    val sz1: Double, val sz3: Double, val sz11: Double, val sz13: Double,
    val sz21: Double, val sz23: Double, val sz31: Double, val sz33: Double,
    val z1: Double, val z3: Double, val z11: Double, val z13: Double,
    val z21: Double, val z23: Double, val z31: Double, val z33: Double,
    val zmol: Double, val zmos: Double,
)

/** dpper 结果 */
internal class DpperResult(
    val ep: Double, val inclp: Double, val nodep: Double,
    val argpp: Double, val mp: Double,
)

/** dsinit 结果 */
internal class DsinitResult(
    val em: Double, val argpm: Double, val inclm: Double, val mm: Double,
    val nm: Double, val nodem: Double,
    val irez: Int, val atime: Double,
    val d2201: Double, val d2211: Double, val d3210: Double, val d3222: Double,
    val d4410: Double, val d4422: Double, val d5220: Double, val d5232: Double,
    val d5421: Double, val d5433: Double,
    val dedt: Double, val didt: Double, val dmdt: Double,
    val dnodt: Double, val domdt: Double,
    val del1: Double, val del2: Double, val del3: Double,
    val xfact: Double, val xlamo: Double, val xli: Double, val xni: Double,
)

/** dspace 结果 */
internal class DspaceResult(
    val em: Double, val argpm: Double, val inclm: Double, val mm: Double,
    val nodem: Double, val nm: Double,
)

/** 六元组：12h 共振 g 系数 */
internal data class Quadruple(
    val g211: Double, val g310: Double, val g322: Double,
    val g410: Double, val g422: Double, val g520: Double,
)

/**
 * 已解析的 TLE 轨道元素（弧度/rad·min 制，供 [Sgp4Satellite] 使用）。
 */
data class ParsedTleElements(
    val catalogNumber: Int,
    val epochYear: Int,
    val epochDays: Double,
    val jdsatepoch: Double,
    val bstar: Double,
    val inclinationRad: Double,
    val raanRad: Double,
    val eccentricity: Double,
    val argPerigeeRad: Double,
    val meanAnomalyRad: Double,
    val meanMotionRadMin: Double,
    val ndotRadMin2: Double,
    val nddotRadMin3: Double,
) {
    /** 平均运动（转/天） */
    val meanMotionRevPerDay: Double
        get() = meanMotionRadMin * 1440.0 / (2.0 * PI)
}
