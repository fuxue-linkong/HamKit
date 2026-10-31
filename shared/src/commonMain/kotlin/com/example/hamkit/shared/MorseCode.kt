package com.example.hamkit.shared

/**
 * 摩斯电码核心逻辑 —— 从 Android `MorseCodeGenerator.kt` + `MorseCodeScreen` 的
 * encode/decode + `MorseCodePlayer` 的节拍模型移植到 KMP 共享内核。
 *
 * 音频播放交由各平台原生实现：本文件只产出「音/停 + 时长」时间线，
 * iOS 用 AVAudioEngine 合成正弦波，Android 继续用 AudioTrack。
 */
object MorseCode {

    /** 字符 → 摩斯码（与 Android 完全一致） */
    val MAP: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '-' to "-....-",
        '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-",
        ' ' to "/", '=' to "-...-",
    )

    private val REVERSE: Map<String, Char> = MAP.entries.associate { (k, v) -> v to k }
    private const val HEX_CHARS = "0123456789ABCDEF"

    /** Koch 课程字符顺序（lcwo.net 同序） */
    private const val KOCH_ORDER = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X"

    private val CW_WORDS = listOf(
        "CQ", "DE", "K", "KN", "SK", "AR", "BT", "AS", "VE", "73",
        "RST", "RIG", "ANT", "PWR", "WX", "TEMP", "NAME", "QTH", "QRM", "QRN",
        "QSB", "QSL", "OM", "YL", "ES", "FB", "GM", "GA", "GE",
        "TU", "HI", "HIHI", "TEST", "R", "RR", "UR", "MY", "YOUR",
    )

    /** 时间线元素：tone=发声，gap=静音；时长单位毫秒。 */
    

    /** 文本 → 摩斯码（Android `MorseCodeGenerator.toMorseCode` 同语义，空格编码为 `/`）。 */
    fun toMorse(text: String): String =
        text.uppercase().mapNotNull { MAP[it] }.joinToString(" ")

    /** 文本 → 摩斯码（词间用 " / "，用于编解码展示页）。 */
    fun encode(text: String): String =
        text.uppercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" / ") { word ->
                word.flatMap { ch ->
                    val code = MAP[ch]
                    if (code != null) {
                        listOf(code)
                    } else {
                        // 非摩斯字符（中文等）：U 前缀 + Unicode 十六进制逐位编码
                        val hex = codePointHex(ch.code)
                        listOf(MAP['U']!!) + hex.map { MAP[it]!! }
                    }
                }.joinToString(" ")
            }

    /** 摩斯码 → 文本（支持 U 前缀 Unicode 还原）。 */
    fun decode(morse: String): String =
        morse.trim()
            .split("/")
            .joinToString(" ") { word ->
                val codes = word.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val sb = StringBuilder()
                var i = 0
                while (i < codes.size) {
                    if (codes[i] == MAP['U'] && i + 1 < codes.size) {
                        val hex = StringBuilder()
                        var j = i + 1
                        while (j < codes.size) {
                            val ch = REVERSE[codes[j]]
                            if (ch != null && ch in HEX_CHARS) {
                                hex.append(ch)
                                j++
                            } else {
                                break
                            }
                        }
                        if (hex.isNotEmpty()) {
                            val cp = hex.toString().toInt(16)
                            appendCodePoint(sb, cp)
                            i = j
                        } else {
                            i++
                        }
                    } else {
                        REVERSE[codes[i]]?.let { sb.append(it) }
                        i++
                    }
                }
                sb.toString()
            }
            .trim()

    /**
     * 摩斯码 → 播放时间线。
     *
     * 节拍与 Android `MorseCodePlayer` 一致：
     * 点 = 1200/wpm ms，划 = 3 点，符号间隔 = 1 点，字符间隔 = 3 点，词间隔 = 7 点。
     * 每个发声段之间自动插入符号间隔，字符/词间隔用空格与 `/` 表示。
     */
    fun timeline(morse: String, wpm: Int): List<MorseStep> {
        val safeWpm = wpm.coerceIn(1, 100)
        val dot = 1200.0 / safeWpm
        val steps = mutableListOf<MorseStep>()
        var needSymbolGap = false
        for (ch in morse) {
            when (ch) {
                '.' -> {
                    if (needSymbolGap) steps.add(MorseStep(false, dot.toInt()))
                    steps.add(MorseStep(true, dot.toInt()))
                    needSymbolGap = true
                }
                '-' -> {
                    if (needSymbolGap) steps.add(MorseStep(false, dot.toInt()))
                    steps.add(MorseStep(true, (dot * 3).toInt()))
                    needSymbolGap = true
                }
                ' ' -> {
                    steps.add(MorseStep(false, (dot * 3).toInt()))
                    needSymbolGap = false
                }
                '/' -> {
                    steps.add(MorseStep(false, (dot * 7).toInt()))
                    needSymbolGap = false
                }
            }
        }
        return steps
    }

    // MARK: - 训练内容生成（对齐 Android MorseCodeGenerator）

    const val CHARSET_LETTERS = "letters"
    const val CHARSET_NUMBERS = "numbers"
    const val CHARSET_SYMBOLS = "symbols"
    const val CHARSET_CUSTOM = "custom"

    fun characterSets(): List<String> =
        listOf(CHARSET_LETTERS, CHARSET_NUMBERS, CHARSET_SYMBOLS, CHARSET_CUSTOM)

    fun generateRandomCharacters(characterSet: String, length: Int): String {
        val chars = when (characterSet) {
            CHARSET_LETTERS -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            CHARSET_NUMBERS -> "0123456789"
            CHARSET_SYMBOLS -> ".,?!-/()"
            else -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        }
        return buildString { repeat(length.coerceAtLeast(0)) { append(chars.random()) } }
    }

    fun generateKochLesson(lesson: Int, length: Int = 25): String {
        val chars = kochLessonChars(lesson)
        return buildString { repeat(length.coerceAtLeast(0)) { append(chars.random()) } }
    }

    fun kochLessonChars(lesson: Int): String =
        KOCH_ORDER.take(lesson.coerceIn(1, 26))

    fun generateCharacterGroups(groupSize: Int = 3, groupCount: Int = 10): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val size = groupSize.coerceIn(2, 5)
        return (1..groupCount).joinToString(" ") {
            buildString { repeat(size) { append(chars.random()) } }
        }
    }

    fun generateCallsigns(count: Int = 10): String {
        val prefixes = listOf("BY", "BA", "BV", "BG", "BD", "BI", "BH", "BT", "BS")
        return (1..count).joinToString(" ") {
            val prefix = prefixes.random()
            val digit = (1..9).random()
            val suffix = buildString {
                repeat((1..3).random()) { append(('A'..'Z').random()) }
            }
            "$prefix$digit$suffix"
        }
    }

    fun generateCwText(sentenceCount: Int = 5): String {
        val templates = listOf(
            { "CQ CQ CQ DE ${generateCallsigns(1)} ${generateCallsigns(1)} K" },
            { "DE ${generateCallsigns(1)} ${generateCallsigns(1)} = UR RST 599 599 = NAME IS ${CW_WORDS.random()} ES QTH IS ${CW_WORDS.random()} = BK" },
            { "${generateCallsigns(1)} DE ${generateCallsigns(1)} = TNX FER RPT UR RST 579 579 = WX HR IS ${CW_WORDS.random()} = BK" },
            { "73 ES TNX FER QSO ${generateCallsigns(1)} DE ${generateCallsigns(1)} SK" },
            { "TEST ${generateCallsigns(1)} ${generateCallsigns(1)} = NR ? BK" },
        )
        return (1..sentenceCount).joinToString(" ") { templates.random()() }
    }

    /** 课程：1=Koch, 2=字符组, 3=呼号, 4=文本 */
    fun tutorialContent(courseId: Int, lessonId: Int, length: Int = 25): String = when (courseId) {
        1 -> generateKochLesson(lessonId, length)
        2 -> generateCharacterGroups(3, maxOf(1, length / 4))
        3 -> generateCallsigns(maxOf(1, length / 6))
        4 -> generateCwText(maxOf(1, length / 50))
        else -> generateKochLesson(1, length)
    }

    // MARK: - 工具

    private fun codePointHex(code: Int): String {
        val digits = "0123456789ABCDEF"
        if (code == 0) return "0"
        val sb = StringBuilder()
        var v = code
        while (v > 0) {
            sb.append(digits[v and 0xF])
            v = v shr 4
        }
        return sb.reverse().toString()
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
}

/** 摩斯播放时间线元素：tone=发声，gap=静音；时长单位毫秒。 */
data class MorseStep(
    val isTone: Boolean,
    val durationMs: Int,
)
