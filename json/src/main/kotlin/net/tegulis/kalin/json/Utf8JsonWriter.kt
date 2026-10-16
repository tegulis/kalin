package net.tegulis.kalin.json

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes JSON into a reusable UTF-8 byte buffer (one per platform thread). Values are encoded with kotlinx's tree
 * encoder (no shared, synchronized buffer pools) and written without an intermediate `String`.
 */
internal class Utf8JsonWriter {
    var buf = ByteArray(1024)
    var len = 0

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
    }

    fun writeLong(value: Long) {
        if (value == Long.MIN_VALUE) {
            write(value.toString())
            return
        }
        ensure(20)
        var v = value
        if (v < 0) {
            buf[len++] = '-'.code.toByte()
            v = -v
        }
        val start = len
        do {
            buf[len++] = ('0'.code + (v % 10).toInt()).toByte()
            v /= 10
        } while (v > 0)
        var i = start
        var j = len - 1
        while (i < j) {
            val t = buf[i]
            buf[i++] = buf[j]
            buf[j--] = t
        }
    }

    fun writeChar(char: Char) {
        if (char.code < 0x80) {
            ensure(1)
            buf[len++] = char.code.toByte()
        } else {
            write(char.toString())
        }
    }

    fun write(text: String) {
        ensure(text.length * 3)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i].code
            when {
                c < 0x80 -> buf[len++] = c.toByte()
                c < 0x800 -> {
                    buf[len++] = (0xC0 or (c shr 6)).toByte()
                    buf[len++] = (0x80 or (c and 0x3F)).toByte()
                }
                Character.isHighSurrogate(text[i]) && i + 1 < n && Character.isLowSurrogate(text[i + 1]) -> {
                    val cp = Character.toCodePoint(text[i], text[i + 1])
                    ensure(4)
                    buf[len++] = (0xF0 or (cp shr 18)).toByte()
                    buf[len++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    buf[len++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    buf[len++] = (0x80 or (cp and 0x3F)).toByte()
                    i++
                }
                else -> {
                    val cc = if (Character.isSurrogate(text[i])) '?'.code else c
                    buf[len++] = (0xE0 or (cc shr 12)).toByte()
                    buf[len++] = (0x80 or ((cc shr 6) and 0x3F)).toByte()
                    buf[len++] = (0x80 or (cc and 0x3F)).toByte()
                }
            }
            i++
        }
    }

    fun writeQuoted(text: String) {
        ensure(text.length + 2)
        buf[len++] = '"'.code.toByte()
        var start = 0
        for (i in text.indices) {
            val c = text[i].code
            if (c < ESCAPES.size && ESCAPES[c] != null) {
                if (i > start) write(text.substring(start, i))
                val e = ESCAPES[c]!!
                ensure(e.size)
                System.arraycopy(e, 0, buf, len, e.size)
                len += e.size
                start = i + 1
            }
        }
        if (start == 0) write(text) else if (start < text.length) write(text.substring(start))
        ensure(1)
        buf[len++] = '"'.code.toByte()
    }

    fun writeElement(element: JsonElement) {
        when (element) {
            is JsonPrimitive ->
                when {
                    element === JsonNull -> write("null")
                    element.isString -> writeQuoted(element.content)
                    else -> write(element.content)
                }
            is JsonObject -> {
                writeChar('{')
                var first = true
                for ((k, v) in element) {
                    if (!first) writeChar(',')
                    first = false
                    writeQuoted(k)
                    writeChar(':')
                    writeElement(v)
                }
                writeChar('}')
            }
            is JsonArray -> {
                writeChar('[')
                for (i in element.indices) {
                    if (i > 0) writeChar(',')
                    writeElement(element[i])
                }
                writeChar(']')
            }
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)

    companion object {
        private val ESCAPES: Array<ByteArray?> =
            arrayOfNulls<ByteArray>(93).also { t ->
                for (c in 0 until 0x20) t[c] = "\\u%04x".format(c).toByteArray()
                t['\b'.code] = "\\b".toByteArray()
                t['\t'.code] = "\\t".toByteArray()
                t['\n'.code] = "\\n".toByteArray()
                t['\u000C'.code] = "\\f".toByteArray()
                t['\r'.code] = "\\r".toByteArray()
                t['"'.code] = "\\\"".toByteArray()
                t['\\'.code] = "\\\\".toByteArray()
            }

        private val local = ThreadLocal.withInitial { Utf8JsonWriter() }

        /** Encodes [value] to UTF-8 JSON bytes. */
        fun <T> encode(json: Json, serializer: SerializationStrategy<T>, value: T): ByteArray {
            val writer = if (Thread.currentThread().isVirtual) Utf8JsonWriter() else local.get()
            writer.len = 0
            writer.writeElement(json.encodeToJsonElement(serializer, value))
            val bytes = writer.toByteArray()
            if (writer.buf.size > 64 * 1024) writer.buf = ByteArray(1024)
            return bytes
        }
    }
}
