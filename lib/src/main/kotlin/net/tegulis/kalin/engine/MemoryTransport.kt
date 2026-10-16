package net.tegulis.kalin.engine

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import net.tegulis.kalin.KalinInternalApi

/**
 * A client-side in-memory connection to a Kalin server (`Transport.MEMORY`). Raw HTTP bytes written to [output] go
 * through the real parser, pipeline and response writer; the response bytes can be read from [input].
 */
@KalinInternalApi
public class MemoryConnection
internal constructor(
    /** Response bytes from the server. */
    public val input: InputStream,
    /** Request bytes to the server. */
    public val output: OutputStream,
    private val onClose: () -> Unit,
) : Closeable {
    /** Closes both directions. */
    override fun close() {
        onClose()
    }
}

/** A bounded, blocking byte pipe between two threads. */
internal class MemoryPipe(capacity: Int = 64 * 1024) {
    private val buf = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var writerClosed = false
    private var readerClosed = false
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        lock.withLock {
            while (size == 0) {
                if (writerClosed || readerClosed) return -1
                notEmpty.await()
            }
            val n = minOf(len, size)
            val first = minOf(n, buf.size - head)
            System.arraycopy(buf, head, b, off, first)
            if (n > first) System.arraycopy(buf, 0, b, off + first, n - first)
            head = (head + n) % buf.size
            size -= n
            notFull.signalAll()
            return n
        }
    }

    fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        lock.withLock {
            while (left > 0) {
                while (size == buf.size) {
                    if (readerClosed) throw IOException("Pipe closed")
                    notFull.await()
                }
                if (readerClosed || writerClosed) throw IOException("Pipe closed")
                val tail = (head + size) % buf.size
                val n = minOf(left, buf.size - size, buf.size - tail)
                System.arraycopy(b, o, buf, tail, n)
                size += n
                o += n
                left -= n
                notEmpty.signalAll()
            }
        }
    }

    fun available(): Int = lock.withLock { size }

    fun closeWrite() {
        lock.withLock {
            writerClosed = true
            notEmpty.signalAll()
            notFull.signalAll()
        }
    }

    fun closeRead() {
        lock.withLock {
            readerClosed = true
            notEmpty.signalAll()
            notFull.signalAll()
        }
    }

    val inputStream: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = this@MemoryPipe.read(b, off, len)

            override fun available(): Int = this@MemoryPipe.available()

            override fun close() = closeRead()
        }

    val outputStream: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) = this@MemoryPipe.write(b, off, len)

            override fun close() = closeWrite()
        }
}

/** Server-side blocking I/O over two pipes. */
internal class MemoryIo(private val requests: MemoryPipe, private val responses: MemoryPipe) : BlockingIo() {
    @Volatile private var closed = false

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Connection closed")
        val n = requests.read(b, off, len)
        if (closed) throw IOException("Connection closed")
        return n
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("Connection closed")
        responses.write(b, off, len)
    }

    override fun shutdownOutput() {
        responses.closeWrite()
    }

    override fun close() {
        closed = true
        requests.closeRead()
        responses.closeWrite()
    }

    override val isClosed: Boolean
        get() = closed

    override val remoteAddress: InetSocketAddress? = InetSocketAddress.createUnresolved("127.0.0.1", 0)
    override val localAddress: InetSocketAddress? = null
}

internal object MemoryTransport {
    @OptIn(KalinInternalApi::class)
    fun connect(runtime: ServerRuntime): MemoryConnection {
        check(!runtime.stopping) { "Server is stopped" }
        val requests = MemoryPipe()
        val responses = MemoryPipe()
        val io = MemoryIo(requests, responses)
        if (!runtime.tryOpen()) throw IOException("Too many connections")
        Thread.ofVirtual().name("kalin-mem-conn").start(Http1Session(io, runtime))
        return MemoryConnection(responses.inputStream, requests.outputStream) {
            requests.closeWrite()
            responses.closeRead()
        }
    }
}

/** An engine without network transport (`Transport.MEMORY`): only in-memory connections. */
internal class MemoryEngine(private val runtime: ServerRuntime) : ServerEngine {
    private val timer = TimerWheel()

    init {
        runtime.timer = timer
    }

    override fun start(): InetSocketAddress? {
        timer.startThread("kalin-timer")
        return null
    }

    override fun stop() {
        runtime.stopping = true
        runtime.drainConnections()
        timer.stop()
    }

    override fun connectInMemory(): MemoryConnection = MemoryTransport.connect(runtime)
}
