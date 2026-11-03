package com.example.hamkit.ai

/**
 * 极简 JSON 树 + 解析/序列化。
 *
 * 为什么不引 `kotlinx-serialization-json`：
 * 1. `:shared` 目前**零第三方依赖**（只有 stdlib），iOS 静态 framework 的体积和
 *    构建时间是敏感项；
 * 2. 这里只需要「读 SSE 帧里的字段 + 拼请求体」两件事，用不到多态/自定义序列化；
 * 3. 自己实现可以保证 Android / iOS 两侧**同一份**解析行为（包括对显式 `null`
 *    的处理，这是上游 AI 协议适配里踩过的坑）。
 *
 * 支持的 JSON 子集就是 RFC 8259 全部语法，未做流式解析：帧都很小（几 KB）。
 */
internal sealed class Json {

    object Null : Json()

    data class Str(val value: String) : Json()

    /** 数字统一按 Double 存；取整时用 [asInt]。 */
    data class Num(val value: Double) : Json()

    data class Bool(val value: Boolean) : Json()

    class Obj(val fields: MutableMap<String, Json> = LinkedHashMap()) : Json() {
        operator fun get(key: String): Json? = fields[key]
        operator fun set(key: String, value: Json) {
            fields[key] = value
        }
        fun put(key: String, value: String): Obj = apply { fields[key] = Str(value) }
        fun put(key: String, value: Int): Obj = apply { fields[key] = Num(value.toDouble()) }
        fun put(key: String, value: Long): Obj = apply { fields[key] = Num(value.toDouble()) }
        fun put(key: String, value: Double): Obj = apply { fields[key] = Num(value) }
        fun put(key: String, value: Boolean): Obj = apply { fields[key] = Bool(value) }
        fun put(key: String, value: Json): Obj = apply { fields[key] = value }
        fun putNull(key: String): Obj = apply { fields[key] = Null }
    }

    class Arr(val items: MutableList<Json> = ArrayList()) : Json() {
        fun add(value: Json): Arr = apply { items.add(value) }
        fun add(value: String): Arr = apply { items.add(Str(value)) }
        operator fun get(index: Int): Json? = items.getOrNull(index)
        val size: Int get() = items.size
    }

    // ── 取值助手：全部返回 null 表示「缺失或类型不符」，不抛异常 ──────────────

    fun objOrNull(): Obj? = this as? Obj
    fun arrOrNull(): Arr? = this as? Arr

    /**
     * 取字符串。显式 `null` 与缺失同样返回 null。
     *
     * 上游踩过的坑：Android `org.json` 对显式 null 调 `optString` 会返回字符串
     * `"null"`，导致 `finish_reason` 被误判成结束、工具 id 变成 `"null"`。
     */
    fun stringOrNull(): String? = when (this) {
        is Str -> value
        else -> null
    }

    fun intOrNull(): Int? = when (this) {
        is Num -> value.toInt()
        is Str -> value.toIntOrNull()
        else -> null
    }

    fun boolOrNull(): Boolean? = when (this) {
        is Bool -> value
        is Str -> when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    fun stringify(): String = buildString { writeTo(this) }

    private fun writeTo(sb: StringBuilder) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (value) "true" else "false")
            is Num -> sb.append(formatNumber(value))
            is Str -> writeString(sb, value)
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    item.writeTo(sb)
                }
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                fields.forEach { (key, value) ->
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key)
                    sb.append(':')
                    value.writeTo(sb)
                }
                sb.append('}')
            }
        }
    }

    companion object {
        /** 解析失败返回 null（协议层统一按「帧内容不是 JSON」处理）。 */
        fun parse(text: String): Json? = JsonParser(text).parse()

        fun obj(): Obj = Obj()

        fun arr(): Arr = Arr()

        fun of(value: String): Json = Str(value)

        fun of(value: Int): Json = Num(value.toDouble())

        fun of(value: Double): Json = Num(value)

        fun of(value: Boolean): Json = Bool(value)

        /** Double → JSON 数字：整数不带小数点，其余按最短可回读形式输出。 */
        private fun formatNumber(value: Double): String {
            if (value.isNaN() || value.isInfinite()) return "0"
            val asLong = value.toLong()
            return if (asLong.toDouble() == value) asLong.toString() else value.toString()
        }

        private fun writeString(sb: StringBuilder, value: String) {
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (ch < ' ') {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            sb.append('"')
        }
    }
}

/** 递归下降解析器：只服务于上面的 [Json]。 */
private class JsonParser(private val text: String) {

    private var index = 0

    fun parse(): Json? {
        val value = runCatching { readValue() }.getOrNull() ?: return null
        skipWhitespace()
        // 尾部只允许空白：容忍网关在 JSON 后面多写一行空内容
        return if (index == text.length) value else null
    }

    private fun readValue(): Json {
        skipWhitespace()
        require(index < text.length) { "unexpected end" }
        return when (val ch = text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> Json.Str(readString())
            't' -> {
                expect("true"); Json.Bool(true)
            }
            'f' -> {
                expect("false"); Json.Bool(false)
            }
            'n' -> {
                expect("null"); Json.Null
            }
            else -> {
                require(ch == '-' || ch.isDigit()) { "unexpected char $ch" }
                readNumber()
            }
        }
    }

    private fun readObject(): Json.Obj {
        index++ // '{'
        val obj = Json.Obj()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return obj
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            require(peek() == ':') { "expected ':'" }
            index++
            obj.fields[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return obj
                }
                else -> error("expected ',' or '}'")
            }
        }
    }

    private fun readArray(): Json.Arr {
        index++ // '['
        val arr = Json.Arr()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return arr
        }
        while (true) {
            arr.items.add(readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return arr
                }
                else -> error("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        require(peek() == '"') { "expected '\"'" }
        index++
        val sb = StringBuilder()
        while (true) {
            require(index < text.length) { "unterminated string" }
            when (val ch = text[index]) {
                '"' -> {
                    index++
                    return sb.toString()
                }
                '\\' -> {
                    index++
                    require(index < text.length) { "bad escape" }
                    when (val esc = text[index]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(index + 4 < text.length) { "bad unicode escape" }
                            val hex = text.substring(index + 1, index + 5)
                            sb.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> error("bad escape $esc")
                    }
                    index++
                }
                else -> {
                    sb.append(ch)
                    index++
                }
            }
        }
    }

    private fun readNumber(): Json.Num {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && text[index].isDigit()) index++
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isDigit()) index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            while (index < text.length && text[index].isDigit()) index++
        }
        val raw = text.substring(start, index)
        return Json.Num(raw.toDoubleOrNull() ?: error("bad number $raw"))
    }

    private fun expect(literal: String) {
        require(text.startsWith(literal, index)) { "expected $literal" }
        index += literal.length
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
