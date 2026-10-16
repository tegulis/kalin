package net.tegulis.kalin.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.HttpStatus

/** One HTTP/1.1 request/response exchange. Engine independent; I/O goes through [ExchangeIo]. */
internal class Http1Exchange(
    internal val head: RequestHead,
    private val io: ExchangeIo,
    private val serverHeader: ByteArray?,
) : Exchange {
    override val method: String
        get() = head.method

    override val target: String
        get() = head.target

    override val path: String
        get() = head.path

    override val query: String?
        get() = head.query

    override val protocol: String
        get() = head.protocol

    override val requestHeaders: RequestHeaders
        get() = head.headers

    override val requestContentLength: Long
        get() = head.contentLength

    override val hasRequestBody: Boolean
        get() = head.hasBody

    override val remoteAddress: InetSocketAddress?
        get() = io.remoteAddress

    override val localAddress: InetSocketAddress?
        get() = io.localAddress

    override var status: Int = 200
    override val responseHeaders: ResponseHeaders = ResponseHeaders()

    @Volatile
    override var isCommitted: Boolean = false
        private set

    @Volatile
    override var isCompleted: Boolean = false
        private set

    /** True if the connection must be closed after this exchange. */
    @Volatile
    var closeAfter: Boolean = !head.keepAlive
        private set

    /** True if a disconnect watcher took over the read side of the connection. */
    @Volatile
    var watching: Boolean = false
        private set

    private var body: BodyInput? = null
    private var continueSent = false
    private val disconnectCallbacks = CopyOnWriteArrayList<Runnable>()
    private val disconnected = AtomicBoolean(false)

    val bodyInput: BodyInput?
        get() = body

    override fun requestBody(): InputStream {
        body?.let {
            return it
        }
        val created = if (head.hasBody) io.bodyInput(head, this) else EmptyBody
        body = created
        return created
    }

    /** Sends `100 Continue` if the client asked for it and nothing has been sent yet. */
    internal fun sendContinueIfNeeded() {
        if (head.expectContinue && !continueSent && !isCommitted) {
            continueSent = true
            io.write(CONTINUE_LINE, 0, CONTINUE_LINE.size)
            io.flush()
        }
    }

    /** True if the request asked for `100 Continue` but the body was never requested. */
    internal val continueSkipped: Boolean
        get() = head.expectContinue && !continueSent

    override fun closeConnectionAfterResponse() {
        closeAfter = true
    }

    private fun bodyAllowed(): Boolean = status >= 200 && status != 204 && status != 304

    private fun writeHead(contentLength: Long): Boolean {
        check(!isCommitted) { "Response already committed" }
        isCommitted = true
        // An unread body we cannot cheaply skip: tell the client we close (decided by the connection later as well)
        if (continueSkipped) closeAfter = true
        val connectionHeader = responseHeaders[Header.CONNECTION]
        if (connectionHeader != null && connectionHeader.contains("close", ignoreCase = true)) closeAfter = true
        val sb = io.scratch
        sb.reset()
        sb.append(statusLine(status))
        sb.append(DateCache.dateLine())
        serverHeader?.let { sb.append(it) }
        var chunked = false
        val allowed = bodyAllowed()
        for (i in 0 until responseHeaders.size) {
            val name = responseHeaders.name(i)
            if (
                name.equals(Header.CONTENT_LENGTH, true) ||
                    name.equals(Header.TRANSFER_ENCODING, true) ||
                    name.equals(Header.CONNECTION, true) ||
                    name.equals(Header.DATE, true)
            )
                continue
            sb.appendHeaderText(name)
            sb.append(':'.code)
            sb.append(' '.code)
            sb.appendHeaderText(responseHeaders.value(i))
            sb.append(CRLF)
        }
        if (allowed) {
            if (contentLength >= 0) {
                sb.append(CONTENT_LENGTH)
                sb.appendDecimal(contentLength)
                sb.append(CRLF)
            } else if (head.http11) {
                sb.append(CHUNKED)
                chunked = true
            } else {
                closeAfter = true
            }
        }
        if (closeAfter) sb.append(CONNECTION_CLOSE) else if (!head.http11) sb.append(CONNECTION_KEEP_ALIVE)
        sb.append(CRLF)
        io.write(sb.buf, 0, sb.len)
        return chunked
    }

    override fun send(body: ByteArray, offset: Int, length: Int) {
        val len = if (bodyAllowed()) length else 0
        writeHead(len.toLong())
        if (len > 0 && !head.isHead) io.write(body, offset, len)
        complete()
    }

    /** Completes the response with an empty body if nothing was sent yet. */
    fun finish() {
        if (isCompleted) return
        if (!isCommitted) {
            send(EMPTY, 0, 0)
            return
        }
        stream?.close()
        if (!isCompleted) complete()
    }

    private var stream: OutputStream? = null

    override fun startStreaming(contentLength: Long): OutputStream {
        var length = contentLength
        if (length < 0) {
            responseHeaders[Header.CONTENT_LENGTH]?.toLongOrNull()?.let { length = it }
        }
        if (!bodyAllowed()) length = 0
        val chunked = writeHead(length)
        val out: OutputStream =
            when {
                head.isHead || !bodyAllowed() -> DiscardingStream(length)
                chunked -> ChunkedStream()
                length >= 0 -> FixedStream(length)
                else -> CloseDelimitedStream()
            }
        stream = out
        return out
    }

    override fun sendFile(channel: FileChannel, position: Long, count: Long) {
        writeHead(count)
        if (!head.isHead && bodyAllowed() && count > 0) io.writeFile(channel, position, count)
        complete()
    }

    override fun sendFile(path: java.nio.file.Path, position: Long, count: Long) {
        writeHead(count)
        if (!head.isHead && bodyAllowed() && count > 0) io.writeFile(path, position, count)
        complete()
    }

    /** Ends the exchange after a failure: the connection is closed after what was written so far. */
    fun abort() {
        closeAfter = true
        complete()
    }

    private fun complete() {
        if (isCompleted) return
        isCompleted = true
        io.exchangeCompleted(this)
    }

    override fun onDisconnect(callback: Runnable) {
        if (disconnected.get()) {
            callback.run()
            return
        }
        disconnectCallbacks.add(callback)
        if (!watching) {
            watching = true
            closeAfter = true
            io.watchDisconnect(this)
        }
        if (disconnected.get() && disconnectCallbacks.remove(callback)) callback.run()
    }

    override val isDisconnected: Boolean
        get() = disconnected.get() || io.closed

    /** Called by the engine when the connection closed. Runs disconnect callbacks once. */
    internal fun disconnected() {
        if (!disconnected.compareAndSet(false, true)) return
        for (cb in disconnectCallbacks) {
            try {
                cb.run()
            } catch (_: Throwable) {}
        }
        disconnectCallbacks.clear()
    }

    private inner class ChunkedStream : OutputStream() {
        private var closed = false
        private val single = ByteArray(1)

        override fun write(b: Int) {
            single[0] = b.toByte()
            write(single, 0, 1)
        }

        private val sizeLine = ByteBuilder(16)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            if (len == 0) return
            if (isDisconnected) throw IOException("Connection closed")
            sizeLine.reset()
            sizeLine.appendHex(len)
            sizeLine.append(CRLF)
            io.write(sizeLine.buf, 0, sizeLine.len)
            io.write(b, off, len)
            io.write(CRLF, 0, 2)
        }

        @Synchronized
        override fun flush() {
            if (!closed) io.flush()
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            try {
                io.write(LAST_CHUNK, 0, LAST_CHUNK.size)
            } finally {
                complete()
            }
        }
    }

    private inner class FixedStream(private val length: Long) : OutputStream() {
        private var written = 0L
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            if (written + len > length) throw IOException("Response body exceeds Content-Length $length")
            if (isDisconnected) throw IOException("Connection closed")
            io.write(b, off, len)
            written += len
        }

        override fun flush() {
            if (!closed) io.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            if (written < length) closeAfter = true // framing broken; the connection cannot be reused
            complete()
        }
    }

    private inner class CloseDelimitedStream : OutputStream() {
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            io.write(b, off, len)
        }

        override fun flush() {
            if (!closed) io.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            complete()
        }
    }

    private inner class DiscardingStream(private val length: Long) : OutputStream() {
        private var closed = false

        override fun write(b: Int) {}

        override fun write(b: ByteArray, off: Int, len: Int) {}

        override fun close() {
            if (closed) return
            closed = true
            if (length < 0 && head.isHead) closeAfter = closeAfter || !head.http11
            complete()
        }
    }

    internal companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        val EMPTY = ByteArray(0)
        private val CONTINUE_LINE = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        private val CONTENT_LENGTH = "Content-Length: ".toByteArray(Charsets.ISO_8859_1)
        private val CHUNKED = "Transfer-Encoding: chunked\r\n".toByteArray(Charsets.ISO_8859_1)
        private val CONNECTION_CLOSE = "Connection: close\r\n".toByteArray(Charsets.ISO_8859_1)
        private val CONNECTION_KEEP_ALIVE = "Connection: keep-alive\r\n".toByteArray(Charsets.ISO_8859_1)
        private val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        private val STATUS_LINES = arrayOfNulls<ByteArray>(600)

        fun statusLine(status: Int): ByteArray {
            if (status in 100..599) {
                STATUS_LINES[status]?.let {
                    return it
                }
                val line = "HTTP/1.1 $status ${HttpStatus.reasonPhrase(status)}\r\n".toByteArray(Charsets.ISO_8859_1)
                STATUS_LINES[status] = line
                return line
            }
            throw IllegalArgumentException("Invalid status code $status")
        }

        /** Builds a minimal complete error response for protocol errors. */
        fun errorResponse(status: Int, message: String): ByteArray {
            val body = message.toByteArray(Charsets.UTF_8)
            val head =
                "HTTP/1.1 $status ${HttpStatus.reasonPhrase(status)}\r\n" +
                    "Content-Type: text/plain;charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            return head.toByteArray(Charsets.ISO_8859_1) + body
        }
    }
}
