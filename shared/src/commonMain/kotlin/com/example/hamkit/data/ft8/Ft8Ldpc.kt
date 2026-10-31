package com.example.hamkit.data.ft8

/**
 * FT8 (174, 91) LDPC 编解码器。
 *
 * 完全符合 FT4/FT8 协议标准（WSJT-X / ft8_lib，KGoba 的公开实现）。
 * 之前的实现使用了一个自创的 (174,87) 准循环码，与真实 FT8 完全不兼容：
 * 编码结果无法被 WSJT-X / FT8CN 解出，解码也无法识别真实 FT8 信号。
 * 这里替换为协议标准 (174,91) LDPC 码及其生成矩阵 / 校验矩阵。
 *
 * - K = 91（77 位消息 + 14 位 CRC）
 * - N = 174，M = 83 校验位
 * - 采用和积（sum-product）/ 置信传播（BP）软判决解码
 */
object Ft8Ldpc {

    /** 信息位长度（含 CRC） */
    const val K = 91

    /** 编码后长度 */
    const val N = 174

    /** 校验位数 */
    const val M = 83

    /** 传输音调数 */
    const val NUM_TONES = 79

    /** 数据符号数 */
    const val ND = 58

    /** 同步符号数 */
    const val LENGTH_SYNC = 7

    /** 同步块数量 */
    const val NUM_SYNC = 3

    /** 同步块偏移 */
    const val SYNC_OFFSET = 36

    const val K_BYTES = (K + 7) / 8   // 12
    const val N_BYTES = (N + 7) / 8   // 22

    /** FT8 Costas 同步阵列 */
    val COSTAS = intArrayOf(3, 1, 4, 0, 6, 5, 2)

    /** FT8 Gray 码映射：3-bit 位组 -> 音调序号 */
    val GRAY_MAP = intArrayOf(0, 1, 3, 2, 5, 6, 4, 7)

    /** Gray 码反映射：音调序号 -> 3-bit 位组 */
    val GRAY_MAP_INV = IntArray(8) { j ->
        GRAY_MAP.indexOf(j)
    }

    // ── 生成矩阵（83×91，位压缩为 83×12 字节，MSB first）──────────────────────────
    // 来源：ft8_lib constants.c kFTX_LDPC_generator
    private val GENERATOR: Array<UByteArray> = arrayOf(
        ub("83 29 ce 11 bf 31 ea f5 09 f2 7f c0"),
        ub("76 1c 26 4e 25 c2 59 33 54 93 13 20"),
        ub("dc 26 59 02 fb 27 7c 64 10 a1 bd c0"),
        ub("1b 3f 41 78 58 cd 2d d3 3e c7 f6 20"),
        ub("09 fd a4 fe e0 41 95 fd 03 47 83 a0"),
        ub("07 7c cc c1 1b 88 73 ed 5c 3d 48 a0"),
        ub("29 b6 2a fe 3c a0 36 f4 fe 1a 9d a0"),
        ub("60 54 fa f5 f3 5d 96 d3 b0 c8 c3 e0"),
        ub("e2 07 98 e4 31 0e ed 27 88 4a e9 00"),
        ub("77 5c 9c 08 e8 0e 26 dd ae 56 31 80"),
        ub("b0 b8 11 02 8c 2b f9 97 21 34 87 c0"),
        ub("18 a0 c9 23 1f c6 0a df 5c 5e a3 20"),
        ub("76 47 1e 83 02 a0 72 1e 01 b1 2b 80"),
        ub("ff bc cb 80 ca 83 41 fa fb 47 b2 e0"),
        ub("66 a7 2a 15 8f 93 25 a2 bf 67 17 00"),
        ub("c4 24 36 89 fe 85 b1 c5 13 63 a1 80"),
        ub("0d ff 73 94 14 d1 a1 b3 4b 1c 27 00"),
        ub("15 b4 88 30 63 6c 8b 99 89 49 72 e0"),
        ub("29 a8 9c 0d 3d e8 1d 66 54 89 b0 e0"),
        ub("4f 12 6f 37 fa 51 cb e6 1b d6 b9 40"),
        ub("99 c4 72 39 d0 d9 7d 3c 84 e0 94 00"),
        ub("19 19 b7 51 19 76 56 21 bb 4f 1e 80"),
        ub("09 db 12 d7 31 fa ee 0b 86 df 6b 80"),
        ub("48 8f c3 3d f4 3f bd ee a4 ea fb 40"),
        ub("82 74 23 ee 40 b6 75 f7 56 eb 5f e0"),
        ub("ab e1 97 c4 84 cb 74 75 71 44 a9 a0"),
        ub("2b 50 0e 4b c0 ec 5a 6d 2b db dd 00"),
        ub("c4 74 aa 53 d7 02 18 76 16 69 36 00"),
        ub("8e ba 1a 13 db 33 90 bd 67 18 ce c0"),
        ub("75 38 44 67 3a 27 78 2c c4 20 12 e0"),
        ub("06 ff 83 a1 45 c3 70 35 a5 c1 26 80"),
        ub("3b 37 41 78 58 cc 2d d3 3e c3 f6 20"),
        ub("9a 4a 5a 28 ee 17 ca 9c 32 48 42 c0"),
        ub("bc 29 f4 65 30 9c 97 7e 89 61 0a 40"),
        ub("26 63 ae 6d df 8b 5c e2 bb 29 48 80"),
        ub("46 f2 31 ef e4 57 03 4c 18 14 41 80"),
        ub("3f b2 ce 85 ab e9 b0 c7 2e 06 fb e0"),
        ub("de 87 48 1f 28 2c 15 39 71 a0 a2 e0"),
        ub("fc d7 cc f2 3c 69 fa 99 bb a1 41 20"),
        ub("f0 26 14 47 e9 49 0c a8 e4 74 ce c0"),
        ub("44 10 11 58 18 19 6f 95 cd d7 01 20"),
        ub("08 8f c3 1d f4 bf bd e2 a4 ea fb 40"),
        ub("b8 fe f1 b6 30 77 29 fb 0a 07 8c 00"),
        ub("5a fe a7 ac cc b7 7b bc 9d 99 a9 00"),
        ub("49 a7 01 6a c6 53 f6 5e cd c9 07 60"),
        ub("19 44 d0 85 be 4e 7d a8 d6 cc 7d 00"),
        ub("25 1f 62 ad c4 03 2f 0e e7 14 00 20"),
        ub("56 47 1f 87 02 a0 72 1e 00 b1 2b 80"),
        ub("2b 8e 49 23 f2 dd 51 e2 d5 37 fa 00"),
        ub("6b 55 0a 40 a6 6f 47 55 de 95 c2 60"),
        ub("a1 8a d2 8d 4e 27 fe 92 a4 f6 c8 40"),
        ub("10 c2 e5 86 38 8c b8 2a 3d 80 75 80"),
        ub("ef 34 a4 18 17 ee 02 13 3d b2 eb 00"),
        ub("7e 9c 0c 54 32 5a 9c 15 83 6e 00 00"),
        ub("36 93 e5 72 d1 fd e4 cd f0 79 e8 60"),
        ub("bf b2 ce c5 ab e1 b0 c7 2e 07 fb e0"),
        ub("7e e1 82 30 c5 83 cc cc 57 d4 b0 80"),
        ub("a0 66 cb 2f ed af c9 f5 26 64 12 60"),
        ub("bb 23 72 5a bc 47 cc 5f 4c c4 cd 20"),
        ub("de d9 db a3 be e4 0c 59 b5 60 9b 40"),
        ub("d9 a7 01 6a c6 53 e6 de cd c9 03 60"),
        ub("9a d4 6a ed 5f 70 7f 28 0a b5 fc 40"),
        ub("e5 92 1c 77 82 25 87 31 6d 7d 3c 20"),
        ub("4f 14 da 82 42 a8 b8 6d ca 73 35 20"),
        ub("8b 8b 50 7a d4 67 d4 44 1d f7 70 e0"),
        ub("22 83 1c 9c f1 16 94 67 ad 04 b6 80"),
        ub("21 3b 83 8f e2 ae 54 c3 8e e7 18 00"),
        ub("5d 92 6b 6d d7 1f 08 51 81 a4 e1 20"),
        ub("66 ab 79 d4 b2 9e e6 e6 95 09 e5 60"),
        ub("95 81 48 68 2d 74 8a 38 dd 68 ba a0"),
        ub("b8 ce 02 0c f0 69 c3 2a 72 3a b1 40"),
        ub("f4 33 1d 6d 46 16 07 e9 57 52 74 60"),
        ub("6d a2 3b a4 24 b9 59 61 33 cf 9c 80"),
        ub("a6 36 bc bc 7b 30 c5 fb ea e6 7f e0"),
        ub("5c b0 d8 6a 07 df 65 4a 90 89 a2 00"),
        ub("f1 1f 10 68 48 78 0f c9 ec dd 80 a0"),
        ub("1f bb 53 64 fb 8d 2c 9d 73 0d 5b a0"),
        ub("fc b8 6b c7 0a 50 c9 d0 2a 5d 03 40"),
        ub("a5 34 43 30 29 ea c1 5f 32 2e 34 c0"),
        ub("c9 89 d9 c7 c3 d3 b8 c5 5d 75 13 00"),
        ub("7b b3 8b 2f 01 86 d4 66 43 ae 96 20"),
        ub("26 44 eb ad eb 44 b9 46 7d 1f 42 c0"),
        ub("60 8c c8 57 59 4b fb b5 5d 69 60 00"),
    )

    // ── 校验矩阵：每一行描述一条校验方程（1-origin 码字位索引）────────────────────
    // 来源：ft8_lib constants.c kFTX_LDPC_Nm（来自 WSJT-X ldpc_174_91_c_reordered_parity.f90）
    private val NM: Array<IntArray> = arrayOf(
        intArrayOf(4, 31, 59, 91, 92, 96, 153),
        intArrayOf(5, 32, 60, 93, 115, 146),
        intArrayOf(6, 24, 61, 94, 122, 151),
        intArrayOf(7, 33, 62, 95, 96, 143),
        intArrayOf(8, 25, 63, 83, 93, 96, 148),
        intArrayOf(6, 32, 64, 97, 126, 138),
        intArrayOf(5, 34, 65, 78, 98, 107, 154),
        intArrayOf(9, 35, 66, 99, 139, 146),
        intArrayOf(10, 36, 67, 100, 107, 126),
        intArrayOf(11, 37, 67, 87, 101, 139, 158),
        intArrayOf(12, 38, 68, 102, 105, 155),
        intArrayOf(13, 39, 69, 103, 149, 162),
        intArrayOf(8, 40, 70, 82, 104, 114, 145),
        intArrayOf(14, 41, 71, 88, 102, 123, 156),
        intArrayOf(15, 42, 59, 106, 123, 159),
        intArrayOf(1, 33, 72, 106, 107, 157),
        intArrayOf(16, 43, 73, 108, 141, 160),
        intArrayOf(17, 37, 74, 81, 109, 131, 154),
        intArrayOf(11, 44, 75, 110, 121, 166),
        intArrayOf(45, 55, 64, 111, 130, 161, 173),
        intArrayOf(8, 46, 71, 112, 119, 166),
        intArrayOf(18, 36, 76, 89, 113, 114, 143),
        intArrayOf(19, 38, 77, 104, 116, 163),
        intArrayOf(20, 47, 70, 92, 138, 165),
        intArrayOf(2, 48, 74, 113, 128, 160),
        intArrayOf(21, 45, 78, 83, 117, 121, 151),
        intArrayOf(22, 47, 58, 118, 127, 164),
        intArrayOf(16, 39, 62, 112, 134, 158),
        intArrayOf(23, 43, 79, 120, 131, 145),
        intArrayOf(19, 35, 59, 73, 110, 125, 161),
        intArrayOf(20, 36, 63, 94, 136, 161),
        intArrayOf(14, 31, 79, 98, 132, 164),
        intArrayOf(3, 44, 80, 124, 127, 169),
        intArrayOf(19, 46, 81, 117, 135, 167),
        intArrayOf(7, 49, 58, 90, 100, 105, 168),
        intArrayOf(12, 50, 61, 118, 119, 144),
        intArrayOf(13, 51, 64, 114, 118, 157),
        intArrayOf(24, 52, 76, 129, 148, 149),
        intArrayOf(25, 53, 69, 90, 101, 130, 156),
        intArrayOf(20, 46, 65, 80, 120, 140, 170),
        intArrayOf(21, 54, 77, 100, 140, 171),
        intArrayOf(35, 82, 133, 142, 171, 174),
        intArrayOf(14, 30, 83, 113, 125, 170),
        intArrayOf(4, 29, 68, 120, 134, 173),
        intArrayOf(1, 4, 52, 57, 86, 136, 152),
        intArrayOf(26, 51, 56, 91, 122, 137, 168),
        intArrayOf(52, 84, 110, 115, 145, 168),
        intArrayOf(7, 50, 81, 99, 132, 173),
        intArrayOf(23, 55, 67, 95, 172, 174),
        intArrayOf(26, 41, 77, 109, 141, 148),
        intArrayOf(2, 27, 41, 61, 62, 115, 133),
        intArrayOf(27, 40, 56, 124, 125, 126),
        intArrayOf(18, 49, 55, 124, 141, 167),
        intArrayOf(6, 33, 85, 108, 116, 156),
        intArrayOf(28, 48, 70, 85, 105, 129, 158),
        intArrayOf(9, 54, 63, 131, 147, 155),
        intArrayOf(22, 53, 68, 109, 121, 174),
        intArrayOf(3, 13, 48, 78, 95, 123),
        intArrayOf(31, 69, 133, 150, 155, 169),
        intArrayOf(12, 43, 66, 89, 97, 135, 159),
        intArrayOf(5, 39, 75, 102, 136, 167),
        intArrayOf(2, 54, 86, 101, 135, 164),
        intArrayOf(15, 56, 87, 108, 119, 171),
        intArrayOf(10, 44, 82, 91, 111, 144, 149),
        intArrayOf(23, 34, 71, 94, 127, 153),
        intArrayOf(11, 49, 88, 92, 142, 157),
        intArrayOf(29, 34, 87, 97, 147, 162),
        intArrayOf(30, 50, 60, 86, 137, 142, 162),
        intArrayOf(10, 53, 66, 84, 112, 128, 165),
        intArrayOf(22, 57, 85, 93, 140, 159),
        intArrayOf(28, 32, 72, 103, 132, 166),
        intArrayOf(28, 29, 84, 88, 117, 143, 150),
        intArrayOf(1, 26, 45, 80, 128, 147),
        intArrayOf(17, 27, 89, 103, 116, 153),
        intArrayOf(51, 57, 98, 163, 165, 172),
        intArrayOf(21, 37, 73, 138, 152, 169),
        intArrayOf(16, 47, 76, 130, 137, 154),
        intArrayOf(3, 24, 30, 72, 104, 139),
        intArrayOf(9, 40, 90, 106, 134, 151),
        intArrayOf(15, 58, 60, 74, 111, 150, 163),
        intArrayOf(18, 42, 79, 144, 146, 152),
        intArrayOf(25, 38, 65, 99, 122, 160),
        intArrayOf(17, 42, 75, 129, 170, 172),
    )

    /** 每行校验方程的有效位个数 */
    private val NUM_ROWS: IntArray = IntArray(M) { m -> NM[m].size }

    /**
     * Mn 表：码字第 i 位（1-origin）参与的校验方程（行）。
     * 由 NM 表派生（与 ft8_lib 中 kFTX_LDPC_Mn 一致）。
     */
    val MN: Array<IntArray> = run {
        val result = Array(N) { IntArray(3) }
        val count = IntArray(N)
        for (m in 0 until M) {
            for (n1 in NM[m]) {
                val n = n1 - 1
                if (count[n] < 3) {
                    result[n][count[n]] = m + 1
                    count[n]++
                }
            }
        }
        result
    }

    private fun ub(hex: String): UByteArray {
        val parts = hex.trim().split(Regex("\\s+"))
        return UByteArray(parts.size) { i -> parts[i].toInt(16).toUByte() }
    }

    /** 返回 x 中 1 的个数的奇偶性 */
    private fun parity8(x: Int): Int {
        var v = x
        v = v xor (v ushr 4)
        v = v xor (v ushr 2)
        v = v xor (v ushr 1)
        return v and 1
    }

    /**
     * LDPC(174,91) 编码。
     *
     * @param payload 91 位信息（12 字节，仅低 91 位有效，MSB first）
     * @return 174 位码字（22 字节，MSB first）
     */
    fun encode174(payload: UByteArray): UByteArray {
        require(payload.size == K_BYTES) { "FT8 LDPC expects $K_BYTES payload bytes" }
        val codeword = UByteArray(N_BYTES)
        for (j in 0 until K_BYTES) codeword[j] = payload[j]

        // 第一个校验位位于第 K 位（bit 91）
        var colMask = 0x80 ushr (K % 8)
        var colIdx = K_BYTES - 1

        for (i in 0 until M) {
            var nsum = 0
            for (j in 0 until K_BYTES) {
                val bits = (payload[j].toInt() and GENERATOR[i][j].toInt())
                nsum = nsum xor parity8(bits)
            }
            if (nsum % 2 == 1) {
                codeword[colIdx] = (codeword[colIdx].toInt() or colMask).toUByte()
            }
            colMask = colMask ushr 1
            if (colMask == 0) {
                colMask = 0x80
                colIdx++
            }
        }
        return codeword
    }

    /**
     * 将 91 位信息（Long 低 91 位）编码为 174 位码字。
     * 兼容旧的调用方式。
     */
    fun encode(infoBits: Long): LongArray {
        val payload = UByteArray(K_BYTES)
        for (b in 0 until K_BYTES) {
            payload[b] = ((infoBits shr (8 * (K_BYTES - 1 - b))) and 0xFFL).toUByte()
        }
        val codeword = encode174(payload)
        val result = LongArray((N + 63) / 64)
        for (i in 0 until N) {
            if (((codeword[i / 8].toInt() shr (7 - i % 8)) and 1) != 0) {
                result[i / 64] = result[i / 64] or (1L shl (i % 64))
            }
        }
        return result
    }

    /** 校验一个 174 位码字（UByteArray 位序列），返回不满足的校验方程数，0 表示完全正确 */
    fun checkCodeword(bit0: BooleanArray): Int {
        var errors = 0
        for (m in 0 until M) {
            var x = 0
            for (n1 in NM[m]) {
                if (bit0[n1 - 1]) x = x xor 1
            }
            if (x != 0) errors++
        }
        return errors
    }

    /**
     * 置信传播（和积）软判决解码。
     *
     * @param log174 174 个位的对数似然比（log P(x=0)/P(x=1)）
     * @param maxIter 最大迭代次数
     * @return 解码结果：错误校验数（0 表示成功）与 174 位硬判决（1 表示 bit=1）
     */
    fun bpDecode(log174: FloatArray, maxIter: Int): Pair<Int, BooleanArray> {
        val tov = Array(N) { FloatArray(3) }
        val toc = Array(M) { FloatArray(7) }
        var minErrors = M

        // tov 初始为 0
        for (n in 0 until N) {
            tov[n][0] = 0f; tov[n][1] = 0f; tov[n][2] = 0f
        }

        var plain = BooleanArray(N)
        var bestPlain = BooleanArray(N)

        for (iter in 0 until maxIter) {
            // 硬判决
            var plainSum = 0
            for (n in 0 until N) {
                plain[n] = (log174[n] + tov[n][0] + tov[n][1] + tov[n][2]) > 0
                if (plain[n]) plainSum++
            }
            if (plainSum == 0) break // 全零消息被禁止

            val errors = checkCodeword(plain)
            if (errors < minErrors) {
                minErrors = errors
                bestPlain = plain.copyOf()
                if (errors == 0) break
            }

            // 位 -> 校验
            for (m in 0 until M) {
                val nList = NM[m]
                for (nIdx in nList.indices) {
                    val n = nList[nIdx] - 1
                    var tnm = log174[n]
                    for (mIdx in 0 until 3) {
                        if ((MN[n][mIdx] - 1) != m) {
                            tnm += tov[n][mIdx]
                        }
                    }
                    toc[m][nIdx] = fastTanh(-tnm / 2.0f)
                }
            }

            // 校验 -> 位
            for (n in 0 until N) {
                for (mIdx in 0 until 3) {
                    val m = MN[n][mIdx] - 1
                    var tmn = 1.0f
                    val nList = NM[m]
                    for (nIdx in nList.indices) {
                        if ((nList[nIdx] - 1) != n) {
                            tmn *= toc[m][nIdx]
                        }
                    }
                    tov[n][mIdx] = -2.0f * fastAtanh(tmn)
                }
            }
        }

        return Pair(minErrors, bestPlain)
    }

    /** tanh 有理近似（ft8_lib fast_tanh） */
    private fun fastTanh(x0: Float): Float {
        var x = x0
        if (x < -4.97f) return -1.0f
        if (x > 4.97f) return 1.0f
        val x2 = x * x
        val a = x * (945.0f + x2 * (105.0f + x2))
        val b = 945.0f + x2 * (420.0f + x2 * 15.0f)
        return a / b
    }

    /** atanh 有理近似（ft8_lib fast_atanh） */
    private fun fastAtanh(x: Float): Float {
        val x2 = x * x
        val a = x * (945.0f + x2 * (-735.0f + x2 * 64.0f))
        val b = 945.0f + x2 * (-1050.0f + x2 * 225.0f)
        return a / b
    }
}
