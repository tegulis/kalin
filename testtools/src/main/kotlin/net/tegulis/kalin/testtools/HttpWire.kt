package net.tegulis.kalin.testtools

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Minimal HTTP/1.1 client-side wire format: request writing and response parsing. */
internal object HttpWire {
    fun writeRequest(out: OutputStream, request: TestRequest, method: String, target: String) {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
        if (request.headers.none { it.first.equals("Host", true) }) sb.append("Host: localhost\r\n")
        for ((k, v) in request.headers) sb.append(k).append(": ").append(v).append("\r\n")
        val cookies = request.cookies
        if (cookies.isNotEmpty())
            sb.append("Cookie: ").append(cookies.joinToString("; ") { "${it.first}=${it.second}" }).append("\r\n")
        val body = request.body
        if (body != null) {
            if (
                request.headers.none {
                    it.first.equals("Content-Length", true) || it.first.equals("Transfer-Encoding", true)
                }
            )
                sb.append("Content-Length: ").append(body.size).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body != null) out.write(body)
        out.flush()
    }

    class Head(val status: Int, val reason: String, val headers: List<Pair<String, String>>) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    }

    private fun readLine(input: InputStream, raw: ByteArrayOutputStream?): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (line.size() == 0) return null
                throw EOFException("Unexpected end of stream in line")
            }
            raw?.write(b)
            if (b == '\n'.code) break
            line.write(b)
        }
        val bytes = line.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    /** Reads a response head, skipping interim 1xx responses. Returns null at a clean end of stream. */
    fun readHead(input: InputStream, raw: ByteArrayOutputStream? = null): Head? {
        while (true) {
            val statusLine = readLine(input, raw) ?: return null
            if (statusLine.isEmpty()) continue
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Invalid status line: $statusLine")
            val status = parts[1].toInt()
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val line = readLine(input, raw) ?: throw EOFException("Unexpected end of stream in head")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
            }
            if (status in 100..199 && status != 101) continue
            return Head(status, parts.getOrElse(2) { "" }, headers)
        }
    }

    /** Opens the body stream of a response according to its framing. */
    fun bodyStream(input: InputStream, head: Head, requestMethod: String): InputStream {
        if (requestMethod == "HEAD" || head.status == 204 || head.status == 304 || head.status in 100..199)
            return InputStream.nullInputStream()
        if (head.header("Transfer-Encoding")?.contains("chunked", true) == true) return ChunkedInput(input)
        val length = head.header("Content-Length")?.toLong()
        if (length != null) return BoundedInput(input, length)
        return input // close-delimited
    }

    fun readBody(input: InputStream, head: Head, requestMethod: String): ByteArray =
        bodyStream(input, head, requestMethod).readAllBytes()

    private class BoundedInput(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b < 0) throw EOFException("Body shorter than Content-Length")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("Body shorter than Content-Length")
            left -= n
            return n
        }
    }

    private class ChunkedInput(private val input: InputStream) : InputStream() {
        private var left = 0L
        private var done = false

        private fun nextChunk() {
            val line = readLine(input, null) ?: throw EOFException("Unexpected end in chunked body")
            val size = line.substringBefore(';').trim().toLong(16)
            if (size == 0L) {
                while (true) {
                    val t = readLine(input, null) ?: break
                    if (t.isEmpty()) break
                }
                done = true
            }
            left = size
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (left == 0L) {
                nextChunk()
                if (done) return -1
            }
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("Unexpected end in chunk")
            left -= n
            if (left == 0L) {
                readLine(input, null) // CRLF after chunk data
            }
            return n
        }
    }
}
