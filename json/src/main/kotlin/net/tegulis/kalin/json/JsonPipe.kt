package net.tegulis.kalin.json

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded in-memory pipe from a producer thread to a consumer. Unlike `PipedInputStream`, a producer failure is
 * rethrown to the reader (so a truncated stream never looks complete), and closing the reading end makes pending and
 * later writes fail (so an abandoned producer never blocks forever).
 */
internal class JsonPipe(capacity: Int) : InputStream() {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val buffer = ByteArray(capacity)
    private var readPos = 0
    private var count = 0
    private var finished = false
    private var failure: Throwable? = null
    private var readerClosed = false

    /** The writing end; [complete] or [fail] must be called when the producer is done. */
    val sink: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var offset = off
                var remaining = len
                lock.withLock {
                    while (remaining > 0) {
                        while (count == buffer.size && !readerClosed) changed.await()
                        if (readerClosed) throw IOException("Pipe closed by the reader")
                        if (finished) throw IOException("Pipe already completed")
                        val writePos = (readPos + count) % buffer.size
                        val n = minOf(remaining, buffer.size - count, buffer.size - writePos)
                        System.arraycopy(b, offset, buffer, writePos, n)
                        count += n
                        offset += n
                        remaining -= n
                        changed.signalAll()
                    }
                }
            }
        }

    /** Marks the end of the data. */
    fun complete() {
        lock.withLock {
            finished = true
            changed.signalAll()
        }
    }

    /** Marks the data as failed; the reader gets [cause] wrapped in an [IOException] after the buffered bytes. */
    fun fail(cause: Throwable) {
        lock.withLock {
            failure = cause
            finished = true
            changed.signalAll()
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        lock.withLock {
            while (count == 0 && !finished && !readerClosed) changed.await()
            if (readerClosed) throw IOException("Pipe closed")
            if (count == 0) {
                failure?.let { throw IOException("JSON serialization failed", it) }
                return -1
            }
            val n = minOf(len, count, buffer.size - readPos)
            System.arraycopy(buffer, readPos, b, off, n)
            readPos = (readPos + n) % buffer.size
            count -= n
            changed.signalAll()
            return n
        }
    }

    override fun available(): Int = lock.withLock { count }

    override fun close() {
        lock.withLock {
            readerClosed = true
            changed.signalAll()
        }
    }
}
