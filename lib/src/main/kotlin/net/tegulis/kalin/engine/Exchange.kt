package net.tegulis.kalin.engine

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.channels.FileChannel

/**
 * The raw HTTP exchange of one request, as seen by the engine. Most applications use `Context` instead; this API is for
 * plugins and special cases (it replaces Javalin's servlet request/response objects).
 */
public interface Exchange {
    /** The request method token, for example `GET`. */
    public val method: String

    /** The raw request target, for example `/users?id=1`. */
    public val target: String

    /** The raw (not percent-decoded) path of the request target. */
    public val path: String

    /** The raw query string without the `?`, or null. */
    public val query: String?

    /** `HTTP/1.1` or `HTTP/1.0`. */
    public val protocol: String

    /** The request headers. */
    public val requestHeaders: RequestHeaders

    /** The declared request body length, or -1 if unknown (chunked) or absent. */
    public val requestContentLength: Long

    /** True if the request carries a body (fixed length > 0 or chunked). */
    public val hasRequestBody: Boolean

    /** The client address, or null for in-memory connections. */
    public val remoteAddress: InetSocketAddress?

    /** The local server address, or null for in-memory connections. */
    public val localAddress: InetSocketAddress?

    /**
     * The request body stream. For `Expect: 100-continue` requests, `100 Continue` is sent on the first read. Reading
     * blocks, so in `HandlerExecution.EVENT_LOOP` mode the body is fully buffered before the handler runs.
     */
    public fun requestBody(): InputStream

    /** The response status code (default 200). */
    public var status: Int

    /** The response headers. Changes after [isCommitted] have no effect. */
    public val responseHeaders: ResponseHeaders

    /** True once the response head has been written. */
    public val isCommitted: Boolean

    /** True once the response is complete. */
    public val isCompleted: Boolean

    /** Sends a complete response with a fixed-length body. */
    public fun send(body: ByteArray, offset: Int = 0, length: Int = body.size)

    /**
     * Commits the response head and returns a stream for the body. With [contentLength] -1 the body is sent chunked (or
     * close-delimited for HTTP/1.0 clients). Closing the stream completes the response.
     */
    public fun startStreaming(contentLength: Long = -1): OutputStream

    /** Sends [count] bytes of a file as the complete response body. */
    public fun sendFile(channel: FileChannel, position: Long, count: Long)

    /**
     * Sends [count] bytes of the file at [path] as the complete response body (zero-copy `sendfile` where available).
     */
    public fun sendFile(path: java.nio.file.Path, position: Long, count: Long)

    /** Closes the connection after this response instead of keeping it alive. */
    public fun closeConnectionAfterResponse()

    /**
     * Registers [callback] to run once when the client disconnects or the connection is closed. Used for SSE. After
     * calling this, the connection is not reused for further requests.
     */
    public fun onDisconnect(callback: Runnable)

    /** True if the connection is known to be closed. */
    public val isDisconnected: Boolean
}

/** Engine-side I/O of one connection, used by [Http1Exchange]. */
internal interface ExchangeIo {
    /** Buffers or writes response bytes. */
    fun write(b: ByteArray, off: Int, len: Int)

    /** Pushes buffered response bytes to the network. */
    fun flush()

    /** Writes a file region as response body bytes (after flushing buffered bytes). */
    fun writeFile(channel: FileChannel, position: Long, count: Long)

    /** Writes a region of the file at [path] as response body bytes. */
    fun writeFile(path: java.nio.file.Path, position: Long, count: Long) {
        FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { writeFile(it, position, count) }
    }

    /** Scratch buffer used to build response heads; reused per connection. */
    val scratch: ByteBuilder

    /** Creates the body stream for [head]. */
    fun bodyInput(head: RequestHead, exchange: Http1Exchange): BodyInput

    /** Starts watching for client disconnects. */
    fun watchDisconnect(exchange: Http1Exchange)

    val remoteAddress: InetSocketAddress?
    val localAddress: InetSocketAddress?

    /** True if the transport is known to be closed. */
    val closed: Boolean

    /** Called once when the exchange completes its response. */
    fun exchangeCompleted(exchange: Http1Exchange)
}

/** A request body stream that reports whether the body has been fully read. */
internal abstract class BodyInput : InputStream() {
    /** True once the complete body has been consumed. */
    abstract val finished: Boolean

    /** Reads and discards up to [limit] remaining body bytes; returns true if the body is then finished. */
    open fun drain(limit: Long): Boolean {
        val skip = ByteArray(8192)
        var left = limit
        while (!finished && left > 0) {
            val n = read(skip, 0, minOf(skip.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
        return finished
    }
}

internal object EmptyBody : BodyInput() {
    override val finished: Boolean
        get() = true

    override fun read(): Int = -1

    override fun read(b: ByteArray, off: Int, len: Int): Int = -1
}

/** A growable byte buffer for building response heads. */
internal class ByteBuilder(initial: Int = 512) {
    var buf: ByteArray = ByteArray(initial)
    var len: Int = 0

    fun reset() {
        len = 0
    }

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
    }

    fun append(bytes: ByteArray) {
        ensure(bytes.size)
        System.arraycopy(bytes, 0, buf, len, bytes.size)
        len += bytes.size
    }

    fun append(b: Int) {
        ensure(1)
        buf[len++] = b.toByte()
    }

    /** Appends a string; chars above 0xFF are UTF-8 encoded, CR/LF are replaced by spaces (header injection). */
    fun appendHeaderText(s: String) {
        ensure(s.length)
        for (i in s.indices) {
            val c = s[i].code
            when {
                c == '\r'.code || c == '\n'.code || c == 0 -> append(' '.code)
                c < 0x80 -> {
                    if (len == buf.size) ensure(1)
                    buf[len++] = c.toByte()
                }
                else -> append(s[i].toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun appendAscii(s: String) {
        ensure(s.length)
        for (i in s.indices) buf[len++] = s[i].code.toByte()
    }

    fun appendDecimal(value: Long) {
        appendAscii(value.toString())
    }

    fun appendHex(value: Int) {
        appendAscii(Integer.toHexString(value))
    }
}

/** A malformed request body (for example broken chunked framing). Mapped to [status] by the request pipeline. */
public class BadRequestBodyException(
    /** The response status to answer with. */
    public val status: Int,
    message: String,
) : java.io.IOException(message)
