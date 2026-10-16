package net.tegulis.kalin.engine

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Blocking transport I/O of one connection (a socket channel or an in-memory pipe). */
internal abstract class BlockingIo {
    /** Reads up to [len] bytes, blocking; returns -1 at end of stream. */
    abstract fun read(b: ByteArray, off: Int, len: Int): Int

    /** Writes all bytes, blocking. */
    abstract fun write(b: ByteArray, off: Int, len: Int)

    /** Writes a file region. */
    open fun transferFile(channel: FileChannel, position: Long, count: Long) {
        val buf = ByteBuffer.allocate(minOf(count, 64 * 1024L).toInt())
        var pos = position
        val end = position + count
        while (pos < end) {
            buf.clear()
            if (end - pos < buf.capacity()) buf.limit((end - pos).toInt())
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException("File shorter than expected")
            write(buf.array(), 0, n)
            pos += n
        }
    }

    /** Half-closes the output side. */
    abstract fun shutdownOutput()

    abstract fun close()

    abstract val isClosed: Boolean
    abstract val remoteAddress: InetSocketAddress?
    abstract val localAddress: InetSocketAddress?
}

/**
 * Processes the requests of one connection with blocking I/O on the current (virtual) thread: parse, dispatch, write,
 * drain, repeat. Pipelined requests are processed in order and their responses are flushed together; buffered responses
 * are always flushed before a read that may block.
 */
internal class Http1Session(private val io: BlockingIo, private val runtime: ServerRuntime) :
    Runnable, ExchangeIo, ManagedConnection {
    private val limits = runtime.limits
    private val parser = HttpParser(limits)
    private val maxInBuf = limits.maxRequestLineSize + limits.maxHeaderSize + 1024
    private var inBuf = ByteArray(minOf(8 * 1024, maxInBuf))
    private var inPos = 0
    private var inLimit = 0
    private val outBuf = ByteArray(runtime.config.outputBufferSize)
    private var outLen = 0
    private val writeLock = Any()
    private val timeout = TimerWheel.Timeout { io.close() }
    override val scratch: ByteBuilder = ByteBuilder()

    @Volatile
    override var idle: Boolean = true
        private set

    @Volatile private var current: Http1Exchange? = null
    private var completed: CountDownLatch? = null

    override fun run() {
        runtime.connections.add(this)
        runtime.timer?.register(timeout)
        try {
            serve()
        } catch (e: HttpParseException) {
            ServerRuntime.debug { "Bad request from ${io.remoteAddress}: ${e.message}" }
            sendError(e.status, e.message ?: "Bad Request")
        } catch (_: IOException) {
            // client went away or a timeout closed the channel
        } catch (e: Throwable) {
            ServerRuntime.log.log(System.Logger.Level.ERROR, "Unexpected error on connection", e)
        } finally {
            timeout.cancel()
            try {
                flushOut()
            } catch (_: IOException) {}
            io.close()
            current?.disconnected()
            runtime.connections.remove(this)
            runtime.closed()
        }
    }

    private fun serve() {
        while (true) {
            idle = inPos == inLimit
            if (runtime.stopping && idle) return
            val head = readHead() ?: return
            idle = false
            val exchange = Http1Exchange(head, this, runtime.serverHeader)
            current = exchange
            runtime.activeExchanges.incrementAndGet()
            runtime.totalRequests.incrementAndGet()
            // The exchange counts as active until its response was written, so stop() waits for it
            var active = true
            try {
                dispatch(exchange)
                if (exchange.watching) return
                if (!exchange.closeAfter && head.hasBody && !finishRequestBody(exchange))
                    exchange.closeConnectionAfterResponse()
                current = null
                if (exchange.closeAfter || runtime.stopping) {
                    flushOut()
                    runtime.activeExchanges.decrementAndGet()
                    active = false
                    lingeringClose(head.hasBody)
                    return
                }
            } finally {
                if (active) runtime.activeExchanges.decrementAndGet()
            }
        }
    }

    private fun dispatch(exchange: Http1Exchange) {
        val latch = CountDownLatch(1)
        completed = latch
        runtime.runHandler(exchange)
        // The pipeline may complete the response from another thread (async); wait for it.
        if (!exchange.isCompleted) {
            while (!latch.await(1, TimeUnit.SECONDS)) {
                if (io.isClosed) {
                    exchange.disconnected()
                    break
                }
            }
        }
    }

    /** Consumes the rest of the request body so the connection can be reused. Returns false if it cannot. */
    private fun finishRequestBody(exchange: Http1Exchange): Boolean {
        val body = exchange.bodyInput
        if (body == null) {
            if (exchange.continueSkipped) return false // client still waits for 100 Continue
            val created = exchange.requestBody() as BodyInput
            return drainWithTimeout(created)
        }
        if (body.finished) return true
        return drainWithTimeout(body)
    }

    private fun drainWithTimeout(body: BodyInput): Boolean =
        try {
            timeout.after(runtime.requestTimeoutMs)
            body.drain(runtime.config.drainLimit)
        } catch (_: IOException) {
            false
        } catch (_: HttpParseException) {
            false
        } finally {
            timeout.clear()
        }

    /** Half-closes and discards input for a short while so the client receives the response instead of a reset. */
    private fun lingeringClose(unreadInput: Boolean) {
        if (!unreadInput || io.isClosed) return
        try {
            io.shutdownOutput()
            timeout.after(1000)
            val skip = ByteArray(4096)
            var total = 0
            while (total < 256 * 1024) {
                val n = io.read(skip, 0, skip.size)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {}
    }

    /** Reads the next request head; returns null when the client closed the connection between requests. */
    private var headDeadlineSet = false

    private fun readHead(): RequestHead? {
        parser.reset()
        headDeadlineSet = false
        while (true) {
            if (inPos < inLimit) {
                inPos += parser.skipLeadingEmptyLines(inBuf, inPos, inLimit)
                if (inPos < inLimit) {
                    val headEnd = parser.findHeadEnd(inBuf, inPos, inLimit)
                    if (headEnd >= 0) {
                        val head = parser.parse(inBuf, inPos, headEnd)
                        inPos = headEnd
                        timeout.clear()
                        return head
                    }
                    flush() // the next head is incomplete: send the responses batched so far before blocking
                    if (!headDeadlineSet) {
                        idle = false
                        headDeadlineSet = true
                        timeout.after(runtime.requestTimeoutMs)
                    }
                }
            }
            if (inPos == inLimit) {
                flush()
                inPos = 0
                inLimit = 0
                parser.reset()
                idle = true
                headDeadlineSet = false
                if (runtime.stopping) return null
                timeout.after(runtime.idleTimeoutMs)
            } else if (!headDeadlineSet) {
                // The whole head must arrive within the request timeout (slowloris protection)
                idle = false
                headDeadlineSet = true
                timeout.after(runtime.requestTimeoutMs)
            }
            val n = fill()
            if (n < 0) {
                if (inPos == inLimit) return null
                throw EOFException("Connection closed in request head")
            }
        }
    }

    /** Reads more bytes into the input buffer, compacting or growing it as needed. */
    private fun fill(): Int {
        if (inLimit == inBuf.size) {
            if (inPos > 0) {
                System.arraycopy(inBuf, inPos, inBuf, 0, inLimit - inPos)
                inLimit -= inPos
                inPos = 0
                parser.reset()
            } else if (inBuf.size < maxInBuf) {
                inBuf = inBuf.copyOf(minOf(inBuf.size * 2, maxInBuf))
            } else {
                throw HttpParseException(431, "Request header fields too large")
            }
        }
        val n = io.read(inBuf, inLimit, inBuf.size - inLimit)
        if (n > 0) inLimit += n
        return n
    }

    private fun sendError(status: Int, message: String) {
        if (current?.isCommitted == true) return
        try {
            val bytes = Http1Exchange.errorResponse(status, message)
            outLen = 0
            io.write(bytes, 0, bytes.size)
            lingeringClose(true)
        } catch (_: IOException) {}
    }

    // ---- ExchangeIo -------------------------------------------------------------------------------------------

    override fun write(b: ByteArray, off: Int, len: Int) {
        synchronized(writeLock) {
            if (len > outBuf.size - outLen) {
                flushOut()
                if (len >= outBuf.size / 2) {
                    timedWrite(b, off, len)
                    return
                }
            }
            System.arraycopy(b, off, outBuf, outLen, len)
            outLen += len
        }
    }

    override fun flush() {
        synchronized(writeLock) { flushOut() }
    }

    private fun flushOut() {
        if (outLen == 0) return
        val n = outLen
        outLen = 0
        timedWrite(outBuf, 0, n)
    }

    private fun timedWrite(b: ByteArray, off: Int, len: Int) {
        timeout.after(runtime.writeTimeoutMs)
        try {
            io.write(b, off, len)
        } finally {
            timeout.clear()
        }
    }

    override fun writeFile(channel: FileChannel, position: Long, count: Long) {
        synchronized(writeLock) {
            flushOut()
            timeout.after(runtime.writeTimeoutMs)
            try {
                io.transferFile(channel, position, count)
            } finally {
                timeout.clear()
            }
        }
    }

    override fun writeFile(path: java.nio.file.Path, channel: FileChannel, position: Long, count: Long) {
        writeFile(channel, position, count)
    }

    override fun bodyInput(head: RequestHead, exchange: Http1Exchange): BodyInput =
        if (head.chunked) ChunkedBody(exchange) else FixedBody(exchange, head.contentLength)

    override fun watchDisconnect(exchange: Http1Exchange) {
        Thread.ofVirtual().name("kalin-disconnect-watch").start {
            val skip = ByteArray(256)
            try {
                while (io.read(skip, 0, skip.size) >= 0) {
                    // discard: nothing may follow a streaming response on this connection
                }
            } catch (_: IOException) {}
            exchange.disconnected()
        }
    }

    override val remoteAddress: InetSocketAddress?
        get() = io.remoteAddress

    override val localAddress: InetSocketAddress?
        get() = io.localAddress

    override val closed: Boolean
        get() = io.isClosed

    override fun exchangeCompleted(exchange: Http1Exchange) {
        completed?.countDown()
    }

    override fun forceClose() {
        io.close()
    }

    // ---- request bodies --------------------------------------------------------------------------------------

    private inner class FixedBody(private val exchange: Http1Exchange, length: Long) : BodyInput() {
        private var remaining = length

        override val finished: Boolean
            get() = remaining == 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0L) return -1
            if (len == 0) return 0
            exchange.sendContinueIfNeeded()
            val want = minOf(len.toLong(), remaining).toInt()
            val buffered = inLimit - inPos
            val n: Int
            if (buffered > 0) {
                n = minOf(buffered, want)
                System.arraycopy(inBuf, inPos, b, off, n)
                inPos += n
            } else {
                flush()
                timeout.after(runtime.requestTimeoutMs)
                try {
                    n = io.read(b, off, want)
                } finally {
                    timeout.clear()
                }
                if (n < 0) throw EOFException("Connection closed in request body")
            }
            remaining -= n
            return n
        }

        override fun available(): Int = minOf((inLimit - inPos).toLong(), remaining).toInt()
    }

    private inner class ChunkedBody(private val exchange: Http1Exchange) : BodyInput() {
        private val decoder = ChunkedDecoder(limits)
        private var target: ByteArray = ByteArray(0)
        private var targetOff = 0
        private var produced = 0
        private val sink = DataSink { src, o, l ->
            System.arraycopy(src, o, target, targetOff + produced, l)
            produced += l
        }

        override val finished: Boolean
            get() = decoder.done

        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = read(one, 0, 1)
                if (n < 0) return -1
                if (n == 1) return one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (decoder.done) return -1
            if (len == 0) return 0
            exchange.sendContinueIfNeeded()
            target = b
            targetOff = off
            produced = 0
            try {
                decodeInto(len)
            } catch (e: HttpParseException) {
                exchange.closeConnectionAfterResponse()
                throw BadRequestBodyException(e.status, e.message ?: "Malformed request body")
            }
            target = EMPTY
            return if (produced == 0 && decoder.done) -1 else produced
        }

        private fun decodeInto(len: Int) {
            while (produced == 0 && !decoder.done) {
                if (inPos == inLimit) {
                    flush()
                    inPos = 0
                    inLimit = 0
                    timeout.after(runtime.requestTimeoutMs)
                    val n =
                        try {
                            io.read(inBuf, 0, inBuf.size)
                        } finally {
                            timeout.clear()
                        }
                    if (n < 0) throw EOFException("Connection closed in chunked request body")
                    inLimit = n
                }
                inPos += decoder.decode(inBuf, inPos, inLimit - inPos, len, sink)
            }
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
