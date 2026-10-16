package net.tegulis.kalin.engine.loop

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import net.tegulis.kalin.engine.BadRequestBodyException
import net.tegulis.kalin.engine.BodyInput
import net.tegulis.kalin.engine.ByteBuilder
import net.tegulis.kalin.engine.ChunkedDecoder
import net.tegulis.kalin.engine.EmptyBody
import net.tegulis.kalin.engine.ExchangeIo
import net.tegulis.kalin.engine.HandlerExecution
import net.tegulis.kalin.engine.Http1Exchange
import net.tegulis.kalin.engine.HttpParseException
import net.tegulis.kalin.engine.HttpParser
import net.tegulis.kalin.engine.ManagedConnection
import net.tegulis.kalin.engine.RequestHead
import net.tegulis.kalin.engine.ServerRuntime
import net.tegulis.kalin.engine.TimerWheel

/**
 * The HTTP/1.1 state machine of one connection on an event loop: `READ_HEAD → (READ_BODY) → HANDLE → WRITE → READ_HEAD
 * | CLOSE`. Everything except the [ExchangeIo] methods runs on the loop thread.
 *
 * Handlers run on a virtual thread by default; the request body streams to it through a bounded pipe (reading from the
 * socket pauses when the pipe is full). Non-blocking handlers run inline on the loop with a fully buffered body.
 */
internal class LoopConnection(
    private val socket: LoopSocket,
    private val loop: EventLoop,
    private val runtime: ServerRuntime,
) : ExchangeIo, ManagedConnection {
    private val limits = runtime.limits
    private val parser = HttpParser(limits)
    private val maxInBuf = limits.maxRequestLineSize + limits.maxHeaderSize + 1024
    private var inBuf = ByteArray(4096)
    private var inPos = 0
    private var inLimit = 0
    private val out = OutputQueue(runtime.config.outputBufferSize)
    private val timeout = TimerWheel.Timeout { closeNow() }
    private val flushRequested = AtomicBoolean(false)
    private val inlineExecution = runtime.config.handlerExecution == HandlerExecution.EVENT_LOOP

    private var state = READ_HEAD
    private var current: Http1Exchange? = null
    private var head: RequestHead? = null
    private var headDeadlineSet = false
    private var inputEof = false
    private var readPaused = false
    private var closeAfterFlush = false
    private var writeInterest = false

    // Request body handling
    private var bodyRemaining = 0L
    private var chunked: ChunkedDecoder? = null
    @Volatile private var pipe: PipeBody? = null
    private var inlineBody: java.io.ByteArrayOutputStream? = null
    private var drainedBytes = 0L

    @Volatile
    override var closed: Boolean = false
        private set

    /** An exchange completed off the loop, waiting for the loop to move on (see [exchangeCompleted]). */
    private val pendingCompletion = java.util.concurrent.atomic.AtomicReference<Http1Exchange?>()

    /** Set by the loop when input is buffered while an exchange is still running on another thread. */
    @Volatile private var inputWaiting = false

    /** The exchange counted in `runtime.activeExchanges`, uncounted exactly once. */
    private val counted = java.util.concurrent.atomic.AtomicReference<Http1Exchange?>()

    private fun uncount(exchange: Http1Exchange) {
        if (counted.compareAndSet(exchange, null)) runtime.activeExchanges.decrementAndGet()
    }

    @Volatile
    override var idle: Boolean = true
        private set

    override val scratch: ByteBuilder = ByteBuilder()

    fun start() {
        runtime.connections.add(this)
        loop.timer.register(timeout)
        timeout.after(runtime.idleTimeoutMs)
    }

    // ---- readiness ---------------------------------------------------------------------------------------

    fun onReadable() {
        if (closed) return
        try {
            readLoop()
            if (!closed) flushOutput()
        } catch (e: HttpParseException) {
            protocolError(e.status, e.message ?: "Bad Request")
        } catch (_: IOException) {
            closeNow()
        }
    }

    fun onWritable() {
        if (closed || !writeInterest) return
        try {
            flushOutput()
        } catch (_: IOException) {
            closeNow()
        }
    }

    private fun readLoop() {
        while (!closed && !inputEof && !readPaused) {
            if (inLimit == inBuf.size && !makeRoom()) break
            val space = inBuf.size - inLimit
            val n = socket.read(inBuf, inLimit, space)
            if (n == 0) break
            if (n < 0) {
                inputEof = true
                break
            }
            inLimit += n
            process()
            // A short read means the socket buffer is empty: new data triggers a new edge (or level) event, so
            // another read would only return EAGAIN.
            if (n < space) break
        }
        if (!closed) process()
        if (inputEof && !closed) onInputEof()
    }

    /** Compacts or grows the input buffer; returns false if no room can be made right now. */
    private fun makeRoom(): Boolean {
        if (inPos > 0) {
            System.arraycopy(inBuf, inPos, inBuf, 0, inLimit - inPos)
            inLimit -= inPos
            inPos = 0
            parser.reset()
            return true
        }
        if (inBuf.size < maxInBuf) {
            inBuf = inBuf.copyOf(minOf(inBuf.size * 2, maxInBuf))
            return true
        }
        if (state == READ_HEAD) throw HttpParseException(431, "Request header fields too large")
        // Buffer full while handling (pipelined data): stop reading until the current exchange is done
        pauseRead()
        return false
    }

    private fun onInputEof() {
        val ex = current
        when {
            ex == null && inPos == inLimit -> closeNow()
            ex == null -> closeNow() // partial head
            ex.watching -> {
                ex.disconnected()
                closeNow()
            }
            pipe?.finishedFeeding == false -> {
                pipe?.fail(EOFException("Connection closed in request body"))
                closeAfterFlush = true
            }
            else -> closeAfterFlush = true // half-closed client: finish the response, then close
        }
    }

    // ---- request processing ------------------------------------------------------------------------------

    private fun process() {
        while (!closed) {
            when (state) {
                READ_HEAD -> if (!readHead()) return
                READ_INLINE_BODY -> if (!readInlineBody()) return
                HANDLING -> {
                    if (takeCompletion()) continue
                    if (pipe != null) feedPipe()
                    if (inPos < inLimit) {
                        // Tell a finishing handler thread to wake us; then re-check (Dekker-style handshake)
                        inputWaiting = true
                        if (takeCompletion()) continue
                    }
                    return
                }
                DRAIN_BODY -> if (!drainBody()) return
                else -> return
            }
        }
    }

    private fun readHead(): Boolean {
        if (inPos == inLimit) {
            inPos = 0
            inLimit = 0
            parser.reset()
            idle = true
            headDeadlineSet = false
            if (runtime.stopping) {
                closeAfterFlush = true
                if (out.isEmpty) closeNow()
                return false
            }
            if (out.isEmpty) timeout.after(runtime.idleTimeoutMs)
            return false
        }
        inPos += parser.skipLeadingEmptyLines(inBuf, inPos, inLimit)
        if (inPos == inLimit) return true
        val headEnd = parser.findHeadEnd(inBuf, inPos, inLimit)
        if (headEnd < 0) {
            idle = false
            if (!headDeadlineSet) {
                headDeadlineSet = true
                timeout.after(runtime.requestTimeoutMs)
            }
            return false
        }
        val h = parser.parse(inBuf, inPos, headEnd)
        inPos = headEnd
        parser.reset()
        headDeadlineSet = false
        idle = false
        timeout.clear()
        startExchange(h)
        return true
    }

    private fun startExchange(h: RequestHead) {
        head = h
        val exchange = Http1Exchange(h, this, runtime.serverHeader)
        current = exchange
        counted.set(exchange)
        runtime.activeExchanges.incrementAndGet()
        runtime.totalRequests.incrementAndGet()
        bodyRemaining = if (h.chunked) -1 else maxOf(0, h.contentLength)
        chunked = if (h.chunked) ChunkedDecoder(limits) else null
        val inline = inlineExecution || runtime.handler.isNonBlocking(h.method, h.path)
        if (inline && (!h.hasBody || h.chunked || h.contentLength <= MAX_INLINE_BODY)) {
            if (h.hasBody) {
                if (h.expectContinue) exchange.sendContinueIfNeeded()
                inlineBody = java.io.ByteArrayOutputStream(if (h.contentLength > 0) h.contentLength.toInt() else 1024)
                state = READ_INLINE_BODY
                timeout.after(runtime.requestTimeoutMs)
            } else {
                dispatchInline(exchange, null)
            }
            return
        }
        pipe = if (h.hasBody) PipeBody(exchange, started = !h.expectContinue) else null
        state = HANDLING
        Thread.ofVirtual().name("kalin-handler").start { runtime.runHandler(exchange) }
    }

    private fun readInlineBody(): Boolean {
        val sink = inlineBody!!
        val avail = inLimit - inPos
        val decoder = chunked
        if (decoder != null) {
            inPos += decoder.decode(inBuf, inPos, avail, Int.MAX_VALUE) { b, o, l -> sink.write(b, o, l) }
            if (sink.size() > MAX_INLINE_BODY) throw HttpParseException(413, "Request body too large")
            if (!decoder.done) return false
        } else {
            val n = minOf(avail.toLong(), bodyRemaining).toInt()
            sink.write(inBuf, inPos, n)
            inPos += n
            bodyRemaining -= n
            if (bodyRemaining > 0) return false
        }
        timeout.clear()
        val bytes = sink.toByteArray()
        inlineBody = null
        dispatchInline(current!!, bytes)
        return state != HANDLING
    }

    private var inlineBytes: ByteArray? = null

    private fun dispatchInline(exchange: Http1Exchange, body: ByteArray?) {
        inlineBytes = body
        state = HANDLING
        runtime.runHandler(exchange)
        // completion (if synchronous) already moved the state on
    }

    // ---- request body pipe (virtual-thread handlers) -----------------------------------------------------

    /** Moves buffered body bytes into the pipe (loop thread). */
    private fun feedPipe() {
        val p = pipe ?: return
        if (!p.started || p.finishedFeeding) return
        while (inPos < inLimit && !p.finishedFeeding) {
            val room = p.room()
            if (room <= 0) {
                pauseRead()
                return
            }
            val decoder = chunked
            if (decoder != null) {
                try {
                    inPos += decoder.decode(inBuf, inPos, inLimit - inPos, room) { b, o, l -> p.offer(b, o, l) }
                } catch (e: HttpParseException) {
                    p.fail(BadRequestBodyException(e.status, e.message ?: "Malformed body"))
                    current?.closeConnectionAfterResponse()
                    inPos = inLimit
                    return
                }
                if (decoder.done) p.finish()
            } else {
                val n = minOf((inLimit - inPos).toLong(), bodyRemaining, room.toLong()).toInt()
                p.offer(inBuf, inPos, n)
                inPos += n
                bodyRemaining -= n
                if (bodyRemaining == 0L) p.finish()
            }
        }
        if (!p.finishedFeeding) timeout.after(runtime.requestTimeoutMs) else timeout.clear()
    }

    private fun pauseRead() {
        if (!readPaused) {
            readPaused = true
            socket.setReadInterest(false)
        }
    }

    private fun resumeRead() {
        if (closed || !readPaused) return
        readPaused = false
        socket.setReadInterest(true)
        process()
        onReadable() // edge-triggered transports report no new event for data already in the socket
    }

    /** Discards the rest of an unread body after the response (keeps the connection usable). */
    private fun drainBody(): Boolean {
        val avail = inLimit - inPos
        val decoder = chunked
        val done: Boolean
        if (decoder != null) {
            val n =
                try {
                    decoder.decode(inBuf, inPos, avail, Int.MAX_VALUE) { _, _, l -> drainedBytes += l }
                } catch (_: HttpParseException) {
                    closeNow()
                    return false
                }
            inPos += n
            done = decoder.done
        } else {
            val n = minOf(avail.toLong(), bodyRemaining).toInt()
            inPos += n
            bodyRemaining -= n
            drainedBytes += n
            done = bodyRemaining == 0L
        }
        if (drainedBytes > runtime.config.drainLimit) {
            closeAfterFlush = true
            if (out.isEmpty) closeNow()
            return false
        }
        if (!done) {
            timeout.after(runtime.requestTimeoutMs)
            return false
        }
        nextRequest()
        return true
    }

    // ---- exchange completion -----------------------------------------------------------------------------

    override fun exchangeCompleted(exchange: Http1Exchange) {
        uncount(exchange)
        if (loop.inLoop) {
            onExchangeComplete(exchange)
            return
        }
        // Completed on a handler thread. Simple exchanges write their response directly and hand the connection
        // back without waking the loop; the loop picks the completion up with the next input.
        val simple = !exchange.watching && !exchange.closeAfter && pipe == null && !runtime.stopping
        if (!simple) {
            loop.execute { onExchangeComplete(exchange) }
            return
        }
        val flushed = directFlush()
        pendingCompletion.set(exchange)
        if (!flushed) {
            loop.execute { takeCompletion() }
            return
        }
        timeout.after(runtime.idleTimeoutMs)
        if (inputWaiting || closed) loop.execute { takeCompletion() }
    }

    /** Applies a completion recorded by a handler thread (loop thread). */
    private fun takeCompletion(): Boolean {
        val exchange = pendingCompletion.getAndSet(null) ?: return false
        inputWaiting = false
        onExchangeComplete(exchange)
        return true
    }

    /** Writes pending output from the calling (handler) thread. Returns true if everything was written. */
    private fun directFlush(): Boolean {
        if (closed) return true
        return try {
            out.flushTo(socket) { timeout.after(runtime.writeTimeoutMs) }
        } catch (_: IOException) {
            loop.execute { closeNow() }
            true
        }
    }

    private fun onExchangeComplete(exchange: Http1Exchange) {
        if (current !== exchange || closed) return
        inlineBytes = null
        val h = head!!
        if (exchange.watching || exchange.closeAfter || runtime.stopping) {
            pipe?.fail(IOException("Exchange completed"))
            closeAfterFlush = true
            flushOutput()
            if (!closed && out.isEmpty) closeNow()
            return
        }
        val p = pipe
        val bodyUnread = h.hasBody && (if (p != null) !p.finishedFeeding else false)
        if (bodyUnread) {
            if (exchange.continueSkipped) {
                closeAfterFlush = true
                flushOutput()
                if (!closed && out.isEmpty) closeNow()
                return
            }
            p!!.abandon()
            pipe = null
            drainedBytes = 0
            state = DRAIN_BODY
            process()
            flushIfIdle()
            return
        }
        nextRequest()
        process()
        flushIfIdle()
    }

    private fun nextRequest() {
        current = null
        head = null
        pipe = null
        chunked = null
        state = READ_HEAD
        if (readPaused) {
            readPaused = false
            socket.setReadInterest(true)
            if (socket.edgeTriggered) loop.execute { onReadable() }
        }
    }

    /** Flushes unless more pipelined requests are buffered (their responses are batched). */
    private fun flushIfIdle() {
        if (closed) return
        if (state != READ_HEAD || inPos == inLimit) flushOutput()
    }

    // ---- output ------------------------------------------------------------------------------------------

    private fun flushOutput() {
        if (closed) return
        if (state == HANDLING && pendingCompletion.get() != null) {
            takeCompletion()
            return
        }
        val done =
            try {
                out.flushTo(socket) { timeout.after(runtime.writeTimeoutMs) }
            } catch (_: IOException) {
                closeNow()
                return
            }
        if (done) {
            if (writeInterest) {
                writeInterest = false
                socket.setWriteInterest(false)
            }
            if (closeAfterFlush) {
                lingerAndClose()
                return
            }
            if (state == READ_HEAD && inPos == inLimit) timeout.after(runtime.idleTimeoutMs)
            else if (state == HANDLING && pipe?.finishedFeeding != false) timeout.clear()
        } else {
            if (!writeInterest) {
                writeInterest = true
                socket.setWriteInterest(true)
            }
            timeout.after(runtime.writeTimeoutMs)
        }
    }

    private fun lingerAndClose() {
        try {
            socket.shutdownOutput()
        } catch (_: IOException) {}
        closeNow()
    }

    private fun protocolError(status: Int, message: String) {
        val ex = current
        if (ex != null && ex.isCommitted) {
            closeNow()
            return
        }
        val bytes = Http1Exchange.errorResponse(status, message)
        try {
            out.append(bytes, 0, bytes.size)
        } catch (_: IOException) {}
        closeAfterFlush = true
        inPos = inLimit
        flushOutput()
    }

    fun closeNow() {
        if (closed) return
        closed = true
        idle = false
        timeout.cancel()
        out.close { socket.close() }
        pipe?.fail(IOException("Connection closed"))
        current?.let {
            it.disconnected()
            uncount(it)
        }
        runtime.connections.remove(this)
        runtime.closed()
    }

    override fun forceClose() {
        if (loop.inLoop) closeNow() else loop.execute { closeNow() }
    }

    // ---- ExchangeIo (any thread) -------------------------------------------------------------------------

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("Connection closed")
        out.append(b, off, len)
        if (!loop.inLoop && out.pending > HIGH_WATERMARK) {
            if (!directFlush()) {
                requestFlush()
                out.awaitBelow(HIGH_WATERMARK, LOW_WATERMARK, runtime.writeTimeoutMs)
            }
        }
    }

    override fun flush() {
        if (loop.inLoop) flushOutput() else if (!directFlush()) requestFlush()
    }

    private fun requestFlush() {
        if (flushRequested.compareAndSet(false, true)) {
            loop.execute {
                flushRequested.set(false)
                flushOutput()
            }
        }
    }

    override fun writeFile(channel: FileChannel, position: Long, count: Long) {
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

    override fun writeFile(path: Path, position: Long, count: Long) {
        if (loop.inLoop) {
            // Never block the loop: copy through the queue instead of waiting for sendfile completion
            FileChannel.open(path).use { writeFile(it, position, count) }
            return
        }
        val done = out.appendFile(path, position, count)
        requestFlush()
        if (!done.await(runtime.writeTimeoutMs + 1000, TimeUnit.MILLISECONDS) || closed)
            throw IOException("File write failed")
    }

    override fun bodyInput(head: RequestHead, exchange: Http1Exchange): BodyInput {
        inlineBytes?.let {
            return ByteArrayBody(it)
        }
        return pipe ?: EmptyBody
    }

    override fun watchDisconnect(exchange: Http1Exchange) {
        // The loop keeps reading; EOF while watching closes the connection and notifies the exchange.
        if (loop.inLoop) {
            if (inputEof) {
                exchange.disconnected()
                closeNow()
            }
        } else {
            loop.execute {
                if (inputEof && !closed) {
                    exchange.disconnected()
                    closeNow()
                }
            }
        }
    }

    override val remoteAddress: InetSocketAddress?
        get() = socket.remoteAddress

    override val localAddress: InetSocketAddress?
        get() = socket.localAddress

    // ---- body streams ------------------------------------------------------------------------------------

    private class ByteArrayBody(private val bytes: ByteArray) : BodyInput() {
        private var pos = 0

        override val finished: Boolean
            get() = pos >= bytes.size

        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(len, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = bytes.size - pos
    }

    /** A bounded pipe from the loop (producer) to the handler thread (consumer). */
    private inner class PipeBody(private val exchange: Http1Exchange, started: Boolean) : BodyInput() {
        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()
        private val chunks = ArrayDeque<ByteArray>()
        private var headOffset = 0
        private var size = 0

        @Volatile
        var started: Boolean = started
            private set

        @Volatile
        var finishedFeeding: Boolean = false
            private set

        private var failure: IOException? = null
        private var abandoned = false
        private val startRequested = AtomicBoolean(false)

        override val finished: Boolean
            get() = lock.withLock { finishedFeeding && size == 0 }

        fun room(): Int = PIPE_CAPACITY - size

        fun offer(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            lock.withLock {
                chunks.addLast(b.copyOfRange(off, off + len))
                size += len
                notEmpty.signalAll()
            }
        }

        fun finish() {
            lock.withLock {
                finishedFeeding = true
                notEmpty.signalAll()
            }
        }

        fun fail(e: IOException) {
            lock.withLock {
                if (failure == null) failure = e
                finishedFeeding = true
                notEmpty.signalAll()
            }
        }

        /** The handler finished without reading everything; the loop drains the rest itself. */
        fun abandon() {
            lock.withLock {
                abandoned = true
                chunks.clear()
                size = 0
                notEmpty.signalAll()
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = read(one, 0, 1)
                if (n < 0) return -1
                if (n == 1) return one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!started && startRequested.compareAndSet(false, true)) {
                loop.execute {
                    exchange.sendContinueIfNeeded()
                    started = true
                    feedPipe()
                    flushOutput()
                }
            }
            var resume = false
            val n = lock.withLock {
                while (size == 0 && !finishedFeeding && failure == null && !abandoned) {
                    if (!notEmpty.await(runtime.requestTimeoutMs, TimeUnit.MILLISECONDS)) {
                        throw IOException("Timed out reading request body")
                    }
                }
                failure?.let { throw it }
                if (size == 0) return -1
                var copied = 0
                while (copied < len && chunks.isNotEmpty()) {
                    val chunk = chunks.peekFirst()
                    val k = minOf(len - copied, chunk.size - headOffset)
                    System.arraycopy(chunk, headOffset, b, off + copied, k)
                    copied += k
                    headOffset += k
                    if (headOffset == chunk.size) {
                        chunks.pollFirst()
                        headOffset = 0
                    }
                }
                size -= copied
                if (size < PIPE_CAPACITY / 2 && !finishedFeeding) resume = true
                copied
            }
            if (resume) loop.execute { if (readPaused) resumeRead() else feedPipe() }
            return n
        }

        override fun available(): Int = lock.withLock { size }
    }

    private companion object {
        const val READ_HEAD = 0
        const val READ_INLINE_BODY = 1
        const val HANDLING = 2
        const val DRAIN_BODY = 3
        const val HIGH_WATERMARK = 256L * 1024
        const val LOW_WATERMARK = 64L * 1024
        const val PIPE_CAPACITY = 256 * 1024
        const val MAX_INLINE_BODY = 1L * 1024 * 1024
    }
}
