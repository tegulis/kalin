package net.tegulis.kalin.json

import net.tegulis.kalin.validation.ValidationError

/** A minimal JSON writer and parser for basic types, used for default responses and the fallback mapper. */
public object SimpleJson {
    /** Quotes and escapes a string as a JSON string literal. */
    @JvmStatic public fun quote(s: String): String = StringBuilder(s.length + 2).also { appendQuoted(it, s) }.toString()

    private fun appendQuoted(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ' || c == ' ' || c == ' ') sb.append("\\u").append("%04x".format(c.code))
                    else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Writes [value] as JSON. Unknown objects are written as their `toString()` string. */
    @JvmStatic public fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendQuoted(sb, value)
            is Boolean -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Float -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Number -> sb.append(value)
            is Char -> appendQuoted(sb, value.toString())
            is Enum<*> -> appendQuoted(sb, value.name)
            is ValidationError<*> ->
                write(sb, linkedMapOf("message" to value.message, "args" to value.args, "value" to value.value))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendQuoted(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> writeArray(sb, value.iterator())
            is Array<*> -> writeArray(sb, value.iterator())
            is IntArray -> writeArray(sb, value.iterator())
            is LongArray -> writeArray(sb, value.iterator())
            is DoubleArray -> writeArray(sb, value.iterator())
            is BooleanArray -> writeArray(sb, value.iterator())
            is Sequence<*> -> writeArray(sb, value.iterator())
            else -> appendQuoted(sb, value.toString())
        }
    }

    private fun writeArray(sb: StringBuilder, it: Iterator<*>) {
        sb.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(',')
            first = false
            write(sb, it.next())
        }
        sb.append(']')
    }

    /** Parses JSON into maps (LinkedHashMap), lists, strings, numbers (Int, Long or Double), booleans and null. */
    @JvmStatic
    public fun parse(json: String): Any? {
        val p = Parser(json)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.pos != json.length) p.fail("Trailing characters")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("Invalid JSON at $pos: $msg")

        fun skipWs() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        fun value(): Any? {
            if (pos >= s.length) fail("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Unexpected '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Expected $word")
            pos += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return m
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("Expected key")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("Expected ':'")
                pos++
                skipWs()
                m[k] = value()
                skipWs()
                if (pos >= s.length) fail("Unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return l
            }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (pos >= s.length) fail("Unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("Bad escape '$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in "0123456789.eE+-") pos++
            val t = s.substring(start, pos)
            return t.toIntOrNull() ?: t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Bad number '$t'")
        }
    }
}
