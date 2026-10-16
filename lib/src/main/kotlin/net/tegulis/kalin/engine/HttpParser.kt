package net.tegulis.kalin.engine

import net.tegulis.kalin.http.Header

/** Protocol limits applied by the parser and body codecs. */
internal class Limits(
    val maxRequestLineSize: Int = 8 * 1024,
    val maxHeaderSize: Int = 16 * 1024,
    val maxHeaderCount: Int = 100,
    val maxChunkLineSize: Int = 4 * 1024,
    val maxTrailerSize: Int = 16 * 1024,
)

/** A request that cannot be processed; answered with [status] and the connection is closed. */
internal class HttpParseException(val status: Int, message: String) : Exception(message, null, false, false)

/** The parsed request line and header section. */
internal class RequestHead {
    lateinit var method: String
    lateinit var target: String
    lateinit var path: String
    var query: String? = null
    var http11: Boolean = true
    val headers: RequestHeaders = RequestHeaders()
    var contentLength: Long = -1
    var chunked: Boolean = false
    var keepAlive: Boolean = true
    var expectContinue: Boolean = false
    var host: String? = null

    val protocol: String
        get() = if (http11) "HTTP/1.1" else "HTTP/1.0"

    val hasBody: Boolean
        get() = chunked || contentLength > 0

    val isHead: Boolean
        get() = method === "HEAD"
}

/**
 * Byte-level HTTP/1.1 request head parser (RFC 9112). One instance per connection; [findHeadEnd] can be called
 * repeatedly as bytes arrive without rescanning already inspected bytes.
 */
internal class HttpParser(private val limits: Limits) {
    private var scanPos = -1

    /** Forgets the incremental scan state; call before parsing the next request. */
    fun reset() {
        scanPos = -1
    }

    /**
     * Returns the number of leading empty lines (CRLF or LF) at [start], which RFC 9112 allows before a request line.
     */
    fun skipLeadingEmptyLines(buf: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val b = buf[i]
            if (b == LF) i++
            else if (b == CR && i + 1 < end && buf[i + 1] == LF) i += 2
            else if (b == CR && i + 1 == end) break else break
        }
        return i - start
    }

    /**
     * Searches for the end of the head starting at [start]. Returns the index just after the terminating empty line, or
     * -1 if more bytes are needed. Throws [HttpParseException] when a limit is exceeded.
     */
    fun findHeadEnd(buf: ByteArray, start: Int, end: Int): Int {
        var i = if (scanPos < start) start else scanPos
        var lineStart = start
        // Find the start of the line containing i
        if (i > start) {
            var j = i - 1
            while (j >= start && buf[j] != LF) j--
            lineStart = j + 1
        }
        var sawRequestLine = lineStart > start
        while (i < end) {
            if (buf[i] == LF) {
                if (!sawRequestLine) {
                    sawRequestLine = true
                    if (i - start > limits.maxRequestLineSize) throw HttpParseException(414, "Request line too long")
                } else {
                    val lineLen = i - lineStart
                    if (lineLen == 0 || (lineLen == 1 && buf[lineStart] == CR)) {
                        scanPos = -1
                        return i + 1
                    }
                }
                lineStart = i + 1
            }
            i++
        }
        scanPos = lineStart
        if (!sawRequestLine) {
            if (end - start > limits.maxRequestLineSize) throw HttpParseException(414, "Request line too long")
        } else if (end - start > limits.maxRequestLineSize + limits.maxHeaderSize) {
            throw HttpParseException(431, "Request header fields too large")
        }
        return -1
    }

    /** Parses a complete head in `buf[start, headEnd)` as found by [findHeadEnd]. */
    fun parse(buf: ByteArray, start: Int, headEnd: Int): RequestHead {
        val head = RequestHead()
        var pos = parseRequestLine(buf, start, headEnd, head)
        val headerStart = pos
        var count = 0
        var hostCount = 0
        var transferEncoding: String? = null
        var connection: String? = null
        while (true) {
            val lineEnd = lineEnd(buf, pos, headEnd)
            val contentEnd = if (lineEnd > pos && buf[lineEnd - 1] == CR) lineEnd - 1 else lineEnd
            if (contentEnd == pos) break // empty line: end of head
            if (contentEnd - headerStart > limits.maxHeaderSize)
                throw HttpParseException(431, "Header section too large")
            if (++count > limits.maxHeaderCount) throw HttpParseException(431, "Too many header fields")
            val first = buf[pos]
            if (first == SP || first == HT) throw HttpParseException(400, "Obsolete line folding is not allowed")
            // field-name
            var i = pos
            while (i < contentEnd && buf[i] != COLON) {
                if (!TCHAR[buf[i].toInt() and 0xFF]) throw HttpParseException(400, "Invalid header field name")
                i++
            }
            if (i == contentEnd || i == pos) throw HttpParseException(400, "Invalid header field")
            val name = headerName(buf, pos, i - pos)
            // field-value with optional whitespace trimmed
            var vs = i + 1
            var ve = contentEnd
            while (vs < ve && (buf[vs] == SP || buf[vs] == HT)) vs++
            while (ve > vs && (buf[ve - 1] == SP || buf[ve - 1] == HT)) ve--
            var nonAscii = false
            for (k in vs until ve) {
                val c = buf[k].toInt() and 0xFF
                if (c < 0x20 && c != 0x09 || c == 0x7F)
                    throw HttpParseException(400, "Invalid character in header value")
                if (c >= 0x80) nonAscii = true
            }
            val value = decode(buf, vs, ve - vs, nonAscii)
            head.headers.add(name, value)
            when {
                name === Header.CONTENT_LENGTH -> {
                    if (head.contentLength >= 0) throw HttpParseException(400, "Duplicate Content-Length")
                    head.contentLength = parseContentLength(value)
                }
                name === Header.TRANSFER_ENCODING ->
                    transferEncoding = if (transferEncoding == null) value else "$transferEncoding, $value"
                name === Header.HOST -> {
                    hostCount++
                    head.host = value
                }
                name === Header.CONNECTION -> connection = if (connection == null) value else "$connection, $value"
                name === Header.EXPECT -> {
                    if (value.equals("100-continue", ignoreCase = true)) head.expectContinue = true
                    else throw HttpParseException(417, "Unsupported expectation")
                }
            }
            pos = lineEnd + 1
        }
        if (hostCount > 1) throw HttpParseException(400, "Multiple Host headers")
        if (head.http11 && hostCount == 0 && head.host == null) throw HttpParseException(400, "Missing Host header")
        if (head.host != null && !validHost(head.host!!)) throw HttpParseException(400, "Invalid Host header")
        if (transferEncoding != null) {
            if (head.contentLength >= 0) throw HttpParseException(400, "Both Content-Length and Transfer-Encoding")
            if (!head.http11) throw HttpParseException(400, "Transfer-Encoding in HTTP/1.0 request")
            val codings = transferEncoding.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (codings.isEmpty()) throw HttpParseException(400, "Empty Transfer-Encoding")
            if (!codings.last().equals("chunked", ignoreCase = true)) {
                if (codings.any { it.equals("chunked", ignoreCase = true) })
                    throw HttpParseException(400, "chunked must be the final transfer coding")
                throw HttpParseException(501, "Unsupported transfer coding")
            }
            if (codings.size > 1) throw HttpParseException(501, "Unsupported transfer coding")
            head.chunked = true
        }
        head.keepAlive = head.http11
        if (connection != null) {
            for (token in connection.split(',')) {
                val t = token.trim()
                if (t.equals("close", ignoreCase = true)) {
                    head.keepAlive = false
                    break
                }
                if (t.equals("keep-alive", ignoreCase = true)) head.keepAlive = true
            }
        }
        if (!head.http11) head.expectContinue = false
        return head
    }

    private fun parseRequestLine(buf: ByteArray, start: Int, headEnd: Int, head: RequestHead): Int {
        val lineEnd = lineEnd(buf, start, headEnd)
        val end = if (lineEnd > start && buf[lineEnd - 1] == CR) lineEnd - 1 else lineEnd
        // method
        var i = start
        while (i < end && buf[i] != SP) {
            if (!TCHAR[buf[i].toInt() and 0xFF]) throw HttpParseException(400, "Invalid method")
            i++
        }
        if (i == start || i == end) throw HttpParseException(400, "Malformed request line")
        head.method = method(buf, start, i - start)
        // target
        val ts = i + 1
        var te = ts
        var nonAscii = false
        var queryAt = -1
        while (te < end && buf[te] != SP) {
            val c = buf[te].toInt() and 0xFF
            if (c <= 0x20 || c == 0x7F) throw HttpParseException(400, "Invalid character in request target")
            if (c >= 0x80) nonAscii = true
            if (c == '?'.code && queryAt < 0) queryAt = te
            te++
        }
        if (te == ts || te == end) throw HttpParseException(400, "Malformed request line")
        // version
        val vs = te + 1
        if (end - vs != 8 || !startsWith(buf, vs, HTTP_SLASH)) throw HttpParseException(400, "Malformed HTTP version")
        val major = buf[vs + 5]
        val dot = buf[vs + 6]
        val minor = buf[vs + 7]
        if (dot != DOT || major !in DIGIT_0..DIGIT_9 || minor !in DIGIT_0..DIGIT_9)
            throw HttpParseException(400, "Malformed HTTP version")
        if (major != DIGIT_1) throw HttpParseException(505, "HTTP version not supported")
        head.http11 = minor != DIGIT_0
        val target = decode(buf, ts, te - ts, nonAscii)
        head.target = target
        val pathEnd = if (queryAt < 0) te - ts else queryAt - ts
        head.query = if (queryAt < 0) null else target.substring(pathEnd + 1)
        var path = if (queryAt < 0) target else target.substring(0, pathEnd)
        if (path[0] != '/') {
            when {
                path == "*" -> {}
                path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> {
                    val authStart = path.indexOf("//") + 2
                    val slash = path.indexOf('/', authStart)
                    head.host = if (slash < 0) path.substring(authStart) else path.substring(authStart, slash)
                    path = if (slash < 0) "/" else path.substring(slash)
                }
                head.method === "CONNECT" -> {}
                else -> throw HttpParseException(400, "Invalid request target")
            }
        }
        head.path = path
        return lineEnd + 1
    }

    private fun lineEnd(buf: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i < limit) {
            val b = buf[i]
            if (b == LF) return i
            if (b == CR && (i + 1 >= limit || buf[i + 1] != LF)) throw HttpParseException(400, "Bare CR in head")
            i++
        }
        throw HttpParseException(400, "Unterminated line")
    }

    private fun parseContentLength(value: String): Long {
        if (value.isEmpty() || value.length > 18) throw HttpParseException(400, "Invalid Content-Length")
        var n = 0L
        for (c in value) {
            if (c !in '0'..'9') throw HttpParseException(400, "Invalid Content-Length")
            n = n * 10 + (c - '0')
        }
        return n
    }

    private fun validHost(host: String): Boolean {
        for (c in host) if (c <= ' ' || c == '/' || c == '\\' || c == '@' || c.code >= 0x7F) return false
        return true
    }

    companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()
        const val SP: Byte = ' '.code.toByte()
        const val HT: Byte = '\t'.code.toByte()
        const val COLON: Byte = ':'.code.toByte()
        const val DOT: Byte = '.'.code.toByte()
        const val DIGIT_0: Byte = '0'.code.toByte()
        const val DIGIT_1: Byte = '1'.code.toByte()
        const val DIGIT_9: Byte = '9'.code.toByte()
        private val HTTP_SLASH = "HTTP/".toByteArray(Charsets.ISO_8859_1)

        /** RFC 9110 token characters. */
        val TCHAR: BooleanArray =
            BooleanArray(256).also { t ->
                for (c in '0'..'9') t[c.code] = true
                for (c in 'a'..'z') t[c.code] = true
                for (c in 'A'..'Z') t[c.code] = true
                for (c in "!#$%&'*+-.^_`|~") t[c.code] = true
            }

        private val KNOWN_METHODS: Array<String> =
            arrayOf("GET", "POST", "PUT", "HEAD", "DELETE", "PATCH", "OPTIONS", "QUERY", "TRACE", "CONNECT")
        private val KNOWN_METHOD_BYTES: Array<ByteArray> = Array(KNOWN_METHODS.size) { KNOWN_METHODS[it].toByteArray() }

        private val KNOWN_HEADERS: Array<String> =
            arrayOf(
                Header.HOST,
                Header.CONNECTION,
                Header.CONTENT_LENGTH,
                Header.CONTENT_TYPE,
                Header.TRANSFER_ENCODING,
                Header.EXPECT,
                Header.ACCEPT,
                Header.ACCEPT_ENCODING,
                Header.ACCEPT_LANGUAGE,
                Header.ACCEPT_CHARSET,
                Header.USER_AGENT,
                Header.COOKIE,
                Header.CACHE_CONTROL,
                Header.PRAGMA,
                Header.ORIGIN,
                Header.REFERER,
                Header.AUTHORIZATION,
                Header.IF_NONE_MATCH,
                Header.IF_MODIFIED_SINCE,
                Header.IF_MATCH,
                Header.IF_UNMODIFIED_SINCE,
                Header.IF_RANGE,
                Header.RANGE,
                Header.UPGRADE,
                Header.TE,
                Header.X_FORWARDED_FOR,
                Header.X_FORWARDED_PROTO,
                Header.X_FORWARDED_HOST,
                Header.X_FORWARDED_PORT,
                Header.X_REAL_IP,
                Header.X_REQUESTED_WITH,
                Header.X_HTTP_METHOD_OVERRIDE,
                Header.FORWARDED,
                Header.LAST_EVENT_ID,
                Header.ACCESS_CONTROL_REQUEST_METHOD,
                Header.ACCESS_CONTROL_REQUEST_HEADERS,
                Header.KEEP_ALIVE,
                Header.CONTENT_ENCODING,
                "Sec-Fetch-Site",
                "Sec-Fetch-Mode",
                "Sec-Fetch-Dest",
                "Sec-Fetch-User",
                "Upgrade-Insecure-Requests",
                "DNT",
                "Priority",
            )
        private val KNOWN_HEADER_BYTES: Array<ByteArray> =
            Array(KNOWN_HEADERS.size) { KNOWN_HEADERS[it].toByteArray(Charsets.ISO_8859_1) }

        fun method(buf: ByteArray, off: Int, len: Int): String {
            for (k in KNOWN_METHOD_BYTES.indices) {
                val m = KNOWN_METHOD_BYTES[k]
                if (m.size == len && startsWith(buf, off, m)) return KNOWN_METHODS[k]
            }
            return String(buf, off, len, Charsets.ISO_8859_1)
        }

        fun headerName(buf: ByteArray, off: Int, len: Int): String {
            for (k in KNOWN_HEADER_BYTES.indices) {
                val h = KNOWN_HEADER_BYTES[k]
                if (h.size == len && equalsIgnoreCaseAscii(buf, off, h)) return KNOWN_HEADERS[k]
            }
            return String(buf, off, len, Charsets.ISO_8859_1)
        }

        fun startsWith(buf: ByteArray, off: Int, prefix: ByteArray): Boolean {
            if (off + prefix.size > buf.size) return false
            for (i in prefix.indices) if (buf[off + i] != prefix[i]) return false
            return true
        }

        private fun equalsIgnoreCaseAscii(buf: ByteArray, off: Int, lower: ByteArray): Boolean {
            for (i in lower.indices) {
                val a = buf[off + i].toInt()
                val b = lower[i].toInt()
                if (a != b && (a or 0x20) != (b or 0x20)) return false
                if (a != b && (a or 0x20) !in 'a'.code..'z'.code) return false
            }
            return true
        }

        /** Decodes bytes as Latin-1 when ASCII, else as UTF-8 if valid, else Latin-1. */
        fun decode(buf: ByteArray, off: Int, len: Int, nonAscii: Boolean): String {
            if (!nonAscii) return String(buf, off, len, Charsets.ISO_8859_1)
            val decoder = Charsets.UTF_8.newDecoder()
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(buf, off, len)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                String(buf, off, len, Charsets.ISO_8859_1)
            }
        }
    }
}
