package net.tegulis.kalin.engine.loop

import java.io.IOException
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Response bytes waiting to be written by the loop. Producers on other threads append and may block when too much is
 * pending (backpressure); the loop thread never blocks.
 */
internal class OutputQueue(private val chunkSize: Int) {
    private sealed class Seg

    private class Bytes(val buf: ByteArray, var off: Int, var len: Int) : Seg() {
        /** Bytes can be appended while this is the tail and owns its buffer. */
        var appendable = true
    }

    private class FileSeg(val path: Path, var position: Long, var remaining: Long) : Seg() {
        var file: AutoCloseable? = null
        val done = java.util.concurrent.CountDownLatch(1)
    }

    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private val segs = ArrayDeque<Seg>()

    @Volatile
    var pending: Long = 0
        private set

    @Volatile private var closed = false

    val isEmpty: Boolean
        get() = pending == 0L

    fun append(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        lock.withLock {
            if (closed) throw IOException("Connection closed")
            var o = off
            var left = len
            val tail = segs.peekLast() as? Bytes
            if (tail != null && tail.appendable) {
                val room = tail.buf.size - (tail.off + tail.len)
                val n = minOf(room, left)
                if (n > 0) {
                    System.arraycopy(b, o, tail.buf, tail.off + tail.len, n)
                    tail.len += n
                    o += n
                    left -= n
                }
            }
            if (left > 0) {
                // Start small (most responses and SSE events are small); grow to chunkSize for larger outputs
                val buf = ByteArray(maxOf(if (segs.isEmpty()) MIN_CHUNK else chunkSize, left))
                System.arraycopy(b, o, buf, 0, left)
                segs.addLast(Bytes(buf, 0, left))
            }
            pending += len
        }
    }

    /** Queues a file region; the returned latch opens when it was written (or the connection closed). */
    fun appendFile(path: Path, position: Long, count: Long): java.util.concurrent.CountDownLatch {
        lock.withLock {
            if (closed) throw IOException("Connection closed")
            (segs.peekLast() as? Bytes)?.appendable = false
            val seg = FileSeg(path, position, count)
            segs.addLast(seg)
            pending += count
            return seg.done
        }
    }

    /** Blocks the calling (non-loop) thread while more than [high] bytes are pending. */
    fun awaitBelow(high: Long, low: Long, timeoutMs: Long) {
        if (pending <= high) return
        lock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (pending > low && !closed) {
                val left = deadline - System.nanoTime()
                if (left <= 0) throw IOException("Write timed out")
                drained.awaitNanos(left)
            }
            if (closed) throw IOException("Connection closed")
        }
    }

    /**
     * Writes as much as possible to [socket] (loop thread). Returns true if everything was written. Progress is
     * reported through [onProgress].
     */
    fun flushTo(socket: LoopSocket, onProgress: () -> Unit): Boolean {
        lock.withLock {
            if (closed) return true
            var progressed = false
            try {
                while (true) {
                    val seg = segs.peekFirst() ?: break
                    when (seg) {
                        is Bytes -> {
                            if (seg.len == 0) {
                                // the retained, empty buffer of the last response: nothing to write
                                if (segs.size == 1) break
                                segs.pollFirst()
                                continue
                            }
                            val n = socket.write(seg.buf, seg.off, seg.len)
                            if (n > 0) progressed = true
                            pending -= n
                            if (n < seg.len) {
                                seg.off += n
                                seg.len -= n
                                return false
                            }
                            if (segs.size == 1 && seg.appendable && seg.buf.size <= RETAIN_LIMIT) {
                                // keep the buffer for the next response
                                seg.off = 0
                                seg.len = 0
                                break
                            }
                            segs.pollFirst()
                        }
                        is FileSeg -> {
                            if (seg.file == null) seg.file = socket.openFile(seg.path)
                            while (seg.remaining > 0) {
                                val n = socket.sendFile(seg.file!!, seg.position, seg.remaining)
                                if (n <= 0) return false
                                progressed = true
                                seg.position += n
                                seg.remaining -= n
                                pending -= n
                            }
                            closeFile(seg)
                            segs.pollFirst()
                        }
                    }
                }
                return true
            } finally {
                if (progressed) {
                    onProgress()
                    drained.signalAll()
                }
            }
        }
    }

    private fun closeFile(seg: FileSeg) {
        try {
            seg.file?.close()
        } catch (_: Exception) {}
        seg.file = null
        seg.done.countDown()
    }

    /**
     * Drops everything and wakes blocked producers (connection closed). [closeSocket] runs under the lock, so no other
     * thread can be writing to the socket (whose descriptor may be reused once closed).
     */
    fun close(closeSocket: () -> Unit = {}) {
        lock.withLock {
            closed = true
            closeSocket()
            for (seg in segs) if (seg is FileSeg) closeFile(seg)
            segs.clear()
            pending = 0
            drained.signalAll()
        }
    }
}

private const val MIN_CHUNK = 1024

/** Buffers up to this size are kept for the next response; larger ones are released (idle connections stay small). */
private const val RETAIN_LIMIT = 4096
