package net.tegulis.kalin.util

import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import net.tegulis.kalin.http.BadRequestResponse
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Cookie
import net.tegulis.kalin.json.SimpleJson

/** Parses `application/x-www-form-urlencoded` data and query strings. */
public object FormParser {
    /**
     * Splits `a=1&b=2&a=3` into a multimap, percent-decoding keys and values (`+` is a space).
     *
     * @throws BadRequestResponse if a key or value contains an invalid percent escape such as `%ZZ`.
     */
    @JvmStatic
    public fun parse(string: String, charset: Charset = Charsets.UTF_8): Map<String, List<String>> {
        if (string.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, MutableList<String>>()
        var start = 0
        val len = string.length
        while (start <= len) {
            var end = string.indexOf('&', start)
            if (end < 0) end = len
            if (end > start) {
                val eq = string.indexOf('=', start).let { if (it < 0 || it > end) -1 else it }
                val rawKey = if (eq < 0) string.substring(start, end) else string.substring(start, eq)
                val rawValue = if (eq < 0) "" else string.substring(eq + 1, end)
                val key = decode(rawKey, charset)
                val value = decode(rawValue, charset)
                result.getOrPut(key) { ArrayList(1) }.add(value)
            }
            start = end + 1
        }
        return result
    }

    private fun decode(s: String, charset: Charset): String {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s
        return try {
            URLDecoder.decode(s, charset)
        } catch (_: IllegalArgumentException) {
            throw BadRequestResponse("Malformed percent-encoding in parameters")
        }
    }
}

/**
 * A map stored in a cookie (base64-encoded JSON), shared between handlers, requests and servers. Values round-trip as
 * JSON types: strings, numbers, booleans, lists and maps.
 */
public class CookieStore internal constructor(private val ctx: Context) {
    private val map: MutableMap<String, Any?> by lazy {
        ctx.cookie(COOKIE_NAME)?.let { raw ->
            try {
                @Suppress("UNCHECKED_CAST")
                (SimpleJson.parse(Base64.getUrlDecoder().decode(raw).decodeToString()) as? Map<String, Any?>)
                    ?.toMutableMap()
            } catch (_: Exception) {
                null
            }
        } ?: LinkedHashMap()
    }

    /** Returns the value for [key]. */
    @Suppress("UNCHECKED_CAST") public operator fun <T> get(key: String): T = map[key] as T

    /** Stores [value] under [key] and updates the cookie. */
    public operator fun set(key: String, value: Any?) {
        map[key] = value
        write()
    }

    /** Removes all values and the cookie. */
    public fun clear() {
        map.clear()
        ctx.removeCookie(COOKIE_NAME)
    }

    private fun write() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(SimpleJson.write(map).toByteArray())
        ctx.cookie(Cookie(COOKIE_NAME, encoded))
    }

    public companion object {
        /** The cookie name. */
        public const val COOKIE_NAME: String = "kalin-cookie-store"

        @JvmSynthetic internal fun create(ctx: Context): CookieStore = CookieStore(ctx)
    }
}
