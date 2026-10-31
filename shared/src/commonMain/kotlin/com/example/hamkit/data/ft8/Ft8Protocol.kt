package com.example.hamkit.data.ft8

import kotlin.ExperimentalUnsignedTypes

/**
 * FT8 协议层：CRC-14、呼号哈希、消息打包/解包、文本字符表。
 *
 * 完全按 FT4/FT8 协议标准（ft8_lib / WSJT-X）实现。
 * 原实现中网格编码、呼号哈希、CRC 与自由文本编码都是自创的、与真实 FT8 不兼容，
 * 这里全部替换为标准算法。
 */
@OptIn(ExperimentalUnsignedTypes::class)
object Ft8Protocol {

    // ── 常量 ────────────────────────────────────────────────────────────────
    const val NTOKENS = 2063592        // 2^21 以下保留给特殊 token
    const val MAX22 = 4194304          // 2^22
    const val MAXGRID4 = 32400         // 180*180，网格/报告编码上限
    const val CRC_POLYNOMIAL = 0x2757  // x^14+x^13+x^11+x^7+x^4+x^2+x+1
    const val CRC_WIDTH = 14
    const val TOPBIT = 1 shl (CRC_WIDTH - 1)

    // ── 字符表 ──────────────────────────────────────────────────────────────
    /** 完整字符表（自由文本），42 字符 */
    const val CHAR_TABLE_FULL = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"

    /** 字母数字+空格（37 字符）：标准呼号第 1 位 */
    private const val A1 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 字母数字（36 字符）：标准呼号第 2 位 */
    private const val A2 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 数字（10 字符）：标准呼号第 3 位 */
    private const val A3 = "0123456789"

    /** 字母+空格（27 字符）：标准呼号第 4-6 位 */
    private const val A4 = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 字母数字+空格+斜杠（38 字符）：非标准呼号（c58） */
    private const val A5 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/"

    // ── CRC-14 ──────────────────────────────────────────────────────────────
    /**
     * 计算 FT8 CRC-14（与 ft8_lib ftx_compute_crc 完全一致）。
     *
     * 采用"每 8 位将字节移入 14 位寄存器"的逐位算法，MSB first。
     *
     * @param message 字节序列（MSB first）
     * @param numBits 参与计算的位数
     */
    fun computeCrc(message: UByteArray, numBits: Int): Int {
        var remainder = 0
        var idxByte = 0
        for (idxBit in 0 until numBits) {
            if (idxBit % 8 == 0) {
                // 将当前字节移入 14 位余数寄存器
                remainder = (remainder xor ((message[idxByte].toInt() and 0xFF) shl (CRC_WIDTH - 8))) and 0xFFFF
                idxByte++
            }
            if ((remainder and TOPBIT) != 0) {
                remainder = ((remainder shl 1) xor CRC_POLYNOMIAL) and 0xFFFF
            } else {
                remainder = (remainder shl 1) and 0xFFFF
            }
        }
        return remainder and ((TOPBIT shl 1) - 1)
    }

    /** 把 77 位消息扩展为 91 位（77 + 14 CRC）的 12 字节数组（ftx_add_crc） */
    fun addCrc(payload77: UByteArray): UByteArray {
        // payload77 是 10 字节，仅低 77 位有效
        val a91 = UByteArray(12)
        for (i in 0 until 10) a91[i] = payload77[i]

        // 77 位后补 5 个 0（到 82 位），计算 CRC
        a91[9] = (a91[9].toInt() and 0xF8).toUByte()
        a91[10] = 0.toUByte()

        // 'The CRC is calculated on the source-encoded message, zero-extended from 77 to 82 bits'
        val checksum = computeCrc(a91, 96 - 14)

        // 存储 CRC：从 bit 77 开始
        a91[9] = (a91[9].toInt() or (checksum shr 11)).toUByte()
        a91[10] = (checksum shr 3).toUByte()
        a91[11] = (checksum shl 5).toUByte()
        return a91
    }

    /** 从 91 位（12 字节）中提取 CRC */
    fun extractCrc(a91: UByteArray): Int {
        return ((a91[9].toInt() and 0x07) shl 11) or (a91[10].toInt() shl 3) or (a91[11].toInt() ushr 5)
    }

    // ── 呼号哈希 ────────────────────────────────────────────────────────────
    /**
     * 计算呼号的 22 位哈希（FT8 标准算法）。
     *
     * 将呼号字符映射到 base-38 数值 n58（不足 11 位以空格补齐），再与
     * 常数相乘后取高 22 位。
     */
    fun hash22(callsign: String): Int {
        val clean = callsign.trim().uppercase()
        var n58 = 0L
        var i = 0
        while (i < clean.length && i < 11) {
            val j = A5.indexOf(clean[i])
            if (j < 0) return 0
            n58 = 38 * n58 + j
            i++
        }
        while (i < 11) {
            n58 = 38 * n58 // 用空格（索引 0）补齐
            i++
        }
        val n22 = ((47055833459L * n58) ushr (64 - 22)) and 0x3FFFFF
        return n22.toInt()
    }

    fun hash12(callsign: String): Int = hash22(callsign) shr 10

    fun hash10(callsign: String): Int = hash22(callsign) shr 12

    // ── 文本辅助 ────────────────────────────────────────────────────────────
    fun isDigit(c: Char): Boolean = c in '0'..'9'

    fun isLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'

    fun isSpace(c: Char): Boolean = c == ' '

    /** 把 4 字符网格/报告打包为 16 位（ir + g15） */
    fun packGridOrReport(grid4: String): Int {
        if (grid4.isEmpty()) return MAXGRID4 + 1
        when (grid4) {
            "RRR" -> return MAXGRID4 + 2
            "RR73" -> return MAXGRID4 + 3
            "73" -> return MAXGRID4 + 4
        }
        // 标准 4 字符网格
        if (grid4.length == 4 && grid4[0] in 'A'..'R' && grid4[1] in 'A'..'R' &&
            isDigit(grid4[2]) && isDigit(grid4[3])
        ) {
            var igrid4 = grid4[0] - 'A'
            igrid4 = igrid4 * 18 + (grid4[1] - 'A')
            igrid4 = igrid4 * 10 + (grid4[2] - '0')
            igrid4 = igrid4 * 10 + (grid4[3] - '0')
            return igrid4
        }
        // 信号报告：+dd / -dd / R+dd / R-dd
        return if (grid4.startsWith('R')) {
            val dd = ddToInt(grid4.substring(1))
            (MAXGRID4 + 35 + dd) or 0x8000
        } else {
            val dd = ddToInt(grid4)
            MAXGRID4 + 35 + dd
        }
    }

    fun ddToInt(s0: String): Int {
        var s = s0.trim()
        var sign = 1
        if (s.startsWith('-')) { sign = -1; s = s.substring(1) }
        else if (s.startsWith('+')) { s = s.substring(1) }
        val v = s.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        return sign * v
    }

    fun intToDd(value: Int, width: Int, fullSign: Boolean): String {
        val sb = StringBuilder()
        var v = value
        if (v < 0) { sb.append('-'); v = -v } else if (fullSign) { sb.append('+') }
        val str = v.toString().padStart(width, '0')
        sb.append(str)
        return sb.toString()
    }

    /** charn：数值 -> 字符（按表）。各表均为有序字符串，索引即编码值。 */
    fun charn(idx: Int, table: String): Char =
        if (idx in 0 until table.length) table[idx] else '_'

    /** nchar：字符 -> 数值（按表），不在表中返回 -1 */
    fun nchar(ch0: Char, table: String): Int {
        val ch = ch0.uppercaseChar()
        val idx = table.indexOf(ch)
        return if (idx >= 0) idx else -1
    }

    // ── 标准呼号打包 ─────────────────────────────────────────────────────────
    /**
     * 检查是否为标准业余呼号。
     * 标准呼号：1-2 个字符前缀（至少 1 个字母）+ 1 个数字 + 最多 3 个字母后缀。
     */
    fun isStandardCallsign(callsign: String): Boolean {
        var temp = callsign.uppercase()
        if (temp.endsWith("/P") || temp.endsWith("/R")) {
            temp = temp.dropLast(2)
        }
        return temp.matches(Regex("[A-Z0-9]?[A-Z0-9][0-9][A-Z][A-Z0-9]?[A-Z]?"))
    }

    /**
     * 把标准呼号打包为 28 位整数（c28）。
     * 特殊值：DE=0, QRZ=1, CQ=2；CQ 修饰符=3+；标准呼号=NTOKENS+MAX22+n28；
     * 非标准呼号=NTOKENS+hash22。
     */
    fun pack28(callsign: String, ip: IntArray): Int {
        ip[0] = 0
        when (callsign) {
            "DE" -> return 0
            "QRZ" -> return 1
            "CQ" -> return 2
        }
        val upper = callsign.uppercase()
        // CQ 修饰符 CQ 000-999 / CQ A-Z / CQ AA-ZZ / CQ AAA-ZZZ / CQ AAAA-ZZZZ
        if (upper.startsWith("CQ ")) {
            val mod = upper.substring(3).trim()
            if (mod.matches(Regex("[0-9]{3}"))) {
                return 3 + mod.toInt()
            }
            if (mod.matches(Regex("[A-Z]{1,4}"))) {
                // base-27 编码（空格为 0，A-Z 为 1-27）
                var m = 0
                for (ch in mod) m = 27 * m + (ch - 'A' + 1)
                return 3 + 1000 + m
            }
        }

        // /R /P 后缀
        var base = upper
        if (upper.endsWith("/P") || upper.endsWith("/R")) {
            ip[0] = 1
            base = upper.dropLast(2)
        }

        val n28 = packBaseCall(base)
        if (n28 >= 0) {
            return NTOKENS + MAX22 + n28
        }
        // 非标准呼号：hash22
        if (base.length in 3..11) {
            ip[0] = 0
            return NTOKENS + hash22(base)
        }
        return -1
    }

    /** 把标准呼号打包为 28 位整数的低值部分（n28），不是标准呼号返回 -1 */
    private fun packBaseCall(callsign: String): Int {
        if (callsign.length < 3) return -1
        var c6 = callsign.uppercase()
        // 斯威士兰/几内亚前缀处理
        if (c6.length > 4 && c6.startsWith("3DA0") && c6.length <= 7) {
            c6 = "3D0" + c6.substring(4)
        } else if (c6.length > 3 && c6.length <= 7 && c6[0] == '3' && c6[1] == 'X' && c6[2].isLetter()) {
            c6 = "Q" + c6.substring(2)
        }

        // 右对齐/左对齐到 6 位（与 ft8_lib pack_basecall 一致）
        val buf = CharArray(6) { ' ' }
        when {
            c6.length <= 6 && isDigit(c6.getOrElse(2) { ' ' }) -> {
                for (i in c6.indices) buf[i] = c6[i]
            }
            c6.length <= 5 && isDigit(c6.getOrElse(1) { ' ' }) -> {
                for (i in c6.indices) buf[i + 1] = c6[i]
            }
            else -> return -1
        }
        val i0 = A1.indexOf(buf[0])
        val i1 = A2.indexOf(buf[1])
        val i2 = A3.indexOf(buf[2])
        val i3 = A4.indexOf(buf[3])
        val i4 = A4.indexOf(buf[4])
        val i5 = A4.indexOf(buf[5])
        if (i0 < 0 || i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0 || i5 < 0) return -1
        var n = i0
        n = n * 36 + i1
        n = n * 10 + i2
        n = n * 27 + i3
        n = n * 27 + i4
        n = n * 27 + i5
        return n
    }

    /** 解包 28 位整数 -> 呼号 */
    fun unpack28(n28: Int, ip: Int, i3: Int): String {
        return when {
            n28 == 0 -> "DE"
            n28 == 1 -> "QRZ"
            n28 == 2 -> "CQ"
            n28 in 3..1002 -> "CQ ${n28 - 3}".padEnd(6)
            n28 in 1003..532443 -> {
                // CQ ABCD
                var n = n28 - 1003
                val chars = CharArray(4)
                for (i in 3 downTo 0) {
                    chars[i] = charn(n % 27, A4)
                    n /= 27
                }
                val mod = chars.concatToString().trim()
                "CQ $mod"
            }
            n28 in NTOKENS until (NTOKENS + MAX22) -> {
                // hash22 呼号，需要哈希表查找
                "<H22:${(n28 - NTOKENS)}>"
            }
            n28 >= NTOKENS + MAX22 -> {
                var n = n28 - NTOKENS - MAX22
                val chars = CharArray(6)
                chars[5] = charn(n % 27, A4); n /= 27
                chars[4] = charn(n % 27, A4); n /= 27
                chars[3] = charn(n % 27, A4); n /= 27
                chars[2] = charn(n % 10, A3); n /= 10
                chars[1] = charn(n % 36, A2); n /= 36
                chars[0] = charn(n % 37, A1)
                var result = chars.concatToString().trim()
                if (result.startsWith("3D0") && result.length >= 4 && result[3].isLetter()) {
                    result = "3DA0" + result.substring(3)
                } else if (result.startsWith("Q") && result.length >= 2 && result[1].isLetter()) {
                    result = "3X" + result.substring(1)
                }
                if (ip != 0) {
                    result += if (i3 == 1) "/R" else "/P"
                }
                result
            }
            else -> "<?>"
        }
    }

    /** 解包网格/报告（16 位）-> 文本 */
    fun unpackGridOrReport(value: Int): String {
        val igrid4 = value and 0x7FFF
        val ir = (value and 0x8000) != 0
        if (igrid4 <= MAXGRID4) {
            var n = igrid4
            val sb = StringBuilder(6)
            val c4 = '0' + n % 10; n /= 10
            val c3 = '0' + n % 10; n /= 10
            val c2 = 'A' + n % 18; n /= 18
            val c1 = 'A' + n % 18
            if (ir) sb.append("R ")
            sb.append(c1).append(c2).append(c3).append(c4)
            return sb.toString()
        }
        val irpt = igrid4 - MAXGRID4
        return when (irpt) {
            1 -> ""
            2 -> "RRR"
            3 -> "RR73"
            4 -> "73"
            else -> {
                val dd = irpt - 35
                (if (ir) "R" else "") + intToDd(dd, 2, true)
            }
        }
    }

    // ── 77 位消息打包/解包 ───────────────────────────────────────────────────
    /**
     * 把人类可读消息打包为 77 位（10 字节）。
     *
     * 支持格式：
     *   - "CQ <call> <grid>"          （标准消息 i3=1）
     *   - "<to> <from> <report>"      （标准消息 i3=1）
     *   - 自由文本（<=13 字符）       （i3=0,n3=0）
     *
     * @return 10 字节数组（77 位消息，MSB first）
     */
    fun packMessage(messageText: String): UByteArray? {
        val parts = messageText.trim().split(Regex("\\s+"))
        val result = UByteArray(10)

        // 解析令牌（与 ft8_lib ftx_message_encode 一致）
        var parsePosition = messageText.trim()
        var callTo: String
        var callDe: String
        var extra: String

        val isCq = messageText.trim().startsWith("CQ ")
        if (isCq) {
            // 检查 CQ 修饰符（CQ nnn / CQ aaaa）
            val cqMod = Regex("^CQ ([0-9]{3}|[A-Za-z]{1,4}) ").find(messageText.trim())
            if (cqMod != null) {
                callTo = "CQ ${cqMod.groupValues[1].uppercase()}"
                parsePosition = messageText.trim().substring(cqMod.value.length).trim()
            } else {
                callTo = "CQ"
                parsePosition = messageText.trim().removePrefix("CQ").trim()
            }
        } else {
            callTo = ""
            parsePosition = parsePosition
        }

        if (callTo.isEmpty()) {
            // 普通标准消息：第一个令牌是目标呼号
            val idx = parsePosition.indexOf(' ')
            callTo = if (idx < 0) parsePosition else parsePosition.substring(0, idx)
            parsePosition = if (idx < 0) "" else parsePosition.substring(idx + 1).trim()
        }

        val idxDe = parsePosition.indexOf(' ')
        callDe = if (idxDe < 0) parsePosition else parsePosition.substring(0, idxDe)
        parsePosition = if (idxDe < 0) "" else parsePosition.substring(idxDe + 1).trim()

        extra = if (parsePosition.contains(' ')) parsePosition.substringBefore(' ').trim() else parsePosition

        // 多余令牌（4 个以上词）视为自由文本
        val leftover = parsePosition.removePrefix(extra).trim()
        if (leftover.isNotEmpty()) {
            return packFreeText(messageText)
        }

        // 标准消息：2 个呼号 + 网格/报告
        callTo = callTo.uppercase()
        callDe = callDe.uppercase()
        if (callTo.isEmpty() || callDe.isEmpty()) {
            return packFreeText(messageText)
        }

        val ipa = IntArray(1)
        val ipb = IntArray(1)
        val n28a = pack28(callTo, ipa)
        val n28b = pack28(callDe, ipb)
        if (n28a < 0 || n28b < 0) {
            // 可能是非标准呼号，尝试自由文本
            return packFreeText(messageText)
        }
        var i3 = 1
        if (callTo.endsWith("/P") || callDe.endsWith("/P")) {
            i3 = 2
        }
        val n29a = ((n28a.toLong() shl 1) or ipa[0].toLong())
        val n29b = ((n28b.toLong() shl 1) or ipb[0].toLong())
        val grid = packGridOrReport(extra)

        // 12 字节打包（与 ft8_lib 一致）
        val payload = UByteArray(12)
        payload[0] = (n29a shr 21).toUByte()
        payload[1] = (n29a shr 13).toUByte()
        payload[2] = (n29a shr 5).toUByte()
        payload[3] = ((n29a shl 3) or (n29b shr 26)).toUByte()
        payload[4] = (n29b shr 18).toUByte()
        payload[5] = (n29b shr 10).toUByte()
        payload[6] = (n29b shr 2).toUByte()
        payload[7] = ((n29b shl 6) or (grid.toLong() shr 10)).toUByte()
        payload[8] = (grid shr 2).toUByte()
        payload[9] = ((grid shl 6) or (i3 shl 3)).toUByte()

        // 取低 77 位作为消息
        for (i in 0 until 10) result[i] = payload[i]
        // 清掉超出 77 位的位：bit 77-79（第 10 字节高 3 位）
        result[9] = (result[9].toInt() and 0x1F).toUByte()
        return result
    }

    /** 打包自由文本（<=13 字符），i3=0,n3=0 */
    fun packFreeText(text0: String): UByteArray? {
        val text = text0.uppercase()
        if (text.length > 13) return null

        // b71[9]：13 字符 base-42 的大整数（71 位，MSB first）
        val b71 = ByteArray(9)
        for (idx in 0 until 13) {
            val c = if (idx < text.length) text[idx] else ' '
            val cid = nchar(c, CHAR_TABLE_FULL)
            if (cid < 0) return null
            var rem = cid
            for (i in 8 downTo 0) {
                rem += (b71[i].toInt() and 0xFF) * 42
                b71[i] = (rem and 0xFF).toByte()
                rem = rem ushr 8
            }
        }

        // ftx_message_encode_telemetry：把 71 位数据左移 1 位右对齐到 77 位消息
        val payload = UByteArray(10)
        var carry = 0
        for (i in 8 downTo 0) {
            payload[i] = (((b71[i].toInt() and 0xFF) shl 1) or (carry ushr 7)).toUByte()
            carry = b71[i].toInt() and 0x80
        }
        payload[9] = 0.toUByte() // i3.n3 = 0.0
        return payload
    }

    /** 解包 77 位消息 -> 可读文本（含 i3/n3 类型） */
    fun unpackMessage(payload: UByteArray): String {
        // 读取 i3 (bit 74-76) 和 n3 (bit 71-73)
        val i3 = (payload[9].toInt() shr 3) and 0x07
        val n3 = ((payload[8].toInt() shl 2) and 0x04) or ((payload[9].toInt() shr 6) and 0x03)

        return when {
            i3 == 0 && n3 == 0 -> unpackFreeText(payload)
            i3 == 1 || i3 == 2 -> {
                // 标准消息
                val n29a = ((payload[0].toInt() shl 21) or (payload[1].toInt() shl 13) or
                    (payload[2].toInt() shl 5) or (payload[3].toInt() ushr 3)).toLong() and 0x1FFFFFFF
                val n29b = ((payload[3].toInt() and 0x07) shl 26 or (payload[4].toInt() shl 18) or
                    (payload[5].toInt() shl 10) or (payload[6].toInt() shl 2) or
                    (payload[7].toInt() ushr 6)).toLong() and 0x1FFFFFFF
                val ir = (payload[7].toInt() ushr 5) and 1
                val igrid4 = ((payload[7].toInt() and 0x1F) shl 10) or
                    (payload[8].toInt() shl 2) or (payload[9].toInt() ushr 6)

                val callTo = unpack28((n29a ushr 1).toInt(), (n29a and 1).toInt(), i3)
                val callDe = unpack28((n29b ushr 1).toInt(), (n29b and 1).toInt(), i3)
                val extra = unpackGridOrReport(igrid4)
                if (extra.isEmpty()) {
                    "$callTo $callDe"
                } else {
                    "$callTo $callDe $extra"
                }
            }
            i3 == 4 -> {
                // 非标准呼号
                val n12 = ((payload[0].toInt() shl 4) or (payload[1].toInt() ushr 4))
                val n58 = ((payload[1].toLong() and 0x0F) shl 54) or
                    (payload[2].toLong() shl 46) or
                    (payload[3].toLong() shl 38) or
                    (payload[4].toLong() shl 30) or
                    (payload[5].toLong() shl 22) or
                    (payload[6].toLong() shl 14) or
                    (payload[7].toLong() shl 6) or
                    (payload[8].toLong() ushr 2)
                val iflip = (payload[8].toInt() ushr 1) and 1
                val nrpt = ((payload[8].toInt() and 1) shl 1) or (payload[9].toInt() ushr 7)
                val icq = (payload[9].toInt() ushr 6) and 1
                val call58 = unpack58(n58)
                val call12 = "<H12:$n12>"
                val call1 = if (iflip != 0) call58 else call12
                val call2 = if (iflip != 0) call12 else call58
                val extra = when (nrpt) {
                    1 -> "RRR"
                    2 -> "RR73"
                    3 -> "73"
                    else -> ""
                }
                if (icq != 0) {
                    "$call1 CQ"
                } else if (extra.isEmpty()) {
                    "$call1 $call2"
                } else {
                    "$call1 $call2 $extra"
                }
            }
            else -> "类型$i3.$n3 消息"
        }
    }

    /** 解包自由文本 */
    fun unpackFreeText(payload: UByteArray): String {
        // ftx_message_decode_telemetry：把 77 位消息右移 1 位取出 71 位数据
        val b71 = ByteArray(9)
        var carry = 0
        for (i in 0 until 9) {
            b71[i] = ((carry shl 7) or (payload[i].toInt() ushr 1)).toByte()
            carry = payload[i].toInt() and 0x01
        }
        // 从 b71（大整数）中按 base-42 逐位取出 13 个字符
        val chars = CharArray(13)
        for (idx in 12 downTo 0) {
            var rem = 0
            for (i in 0 until 9) {
                rem = (rem shl 8) or (b71[i].toInt() and 0xFF)
                b71[i] = (rem / 42).toByte()
                rem = rem % 42
            }
            chars[idx] = charn(rem, CHAR_TABLE_FULL)
        }
        return chars.concatToString().trimEnd(' ')
    }

    /** 解包 58 位非标准呼号 */
    fun unpack58(n58: Long): String {
        val chars = CharArray(11)
        var n = n58
        for (i in 10 downTo 0) {
            chars[i] = charn((n % 38).toInt(), A5)
            n /= 38
        }
        return chars.concatToString().trim()
    }
}
