package net.tegulis.kalin.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * A hashed timer wheel for connection deadlines.
 *
 * Connections own a [Timeout] and move its deadline with a single volatile write (no queue operations on the hot path).
 * The wheel re-buckets a timeout lazily when it finds the deadline moved, so each connection is touched at most about
 * once per second. Timeouts are registered through a lock-free queue. Either a dedicated thread drives the wheel
 * ([startThread]) or an event loop calls [advance] from its own thread.
 */
internal class TimerWheel(private val tickMillis: Long = 100, wheelSize: Int = 512) {
    /** A deadline owned by a connection. [onExpire] runs on the wheel thread and must be quick. */
    class Timeout(val onExpire: Runnable) {
        /** Absolute deadline in milliseconds (System.nanoTime based), or 0 for none. */
        @Volatile var deadline: Long = 0

        @Volatile var cancelled: Boolean = false

        /** Sets the deadline to [millis] from now (0 or negative clears it). */
        fun after(millis: Long) {
            deadline = if (millis <= 0) 0 else nowMillis() + millis
        }

        fun clear() {
            deadline = 0
        }

        fun cancel() {
            cancelled = true
        }
    }

    private val mask = Integer.highestOneBit(wheelSize * 2 - 1) - 1
    private val recheckTicks = minOf(mask.toLong(), maxOf(1L, 1000 / tickMillis))
    private val buckets = Array(mask + 1) { ArrayDeque<Timeout>() }
    private val incoming = ConcurrentLinkedQueue<Timeout>()
    private var tick = nowMillis() / tickMillis

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    /** Registers [timeout]; safe from any thread. */
    fun register(timeout: Timeout) {
        incoming.add(timeout)
    }

    /** Processes all ticks up to now. Must be called from the owning thread only. */
    fun advance() {
        while (true) {
            val t = incoming.poll() ?: break
            place(t, t.deadline)
        }
        val nowTick = nowMillis() / tickMillis
        while (tick <= nowTick) {
            val bucket = buckets[(tick and mask.toLong()).toInt()]
            var n = bucket.size
            while (n-- > 0) {
                val t = bucket.poll() ?: break
                if (t.cancelled) continue
                val d = t.deadline
                if (d != 0L && d <= nowMillis()) {
                    t.deadline = 0
                    try {
                        t.onExpire.run()
                    } catch (_: Throwable) {}
                    if (!t.cancelled) place(t, 0)
                } else {
                    place(t, d)
                }
            }
            tick++
        }
    }

    private fun place(t: Timeout, deadline: Long) {
        if (t.cancelled) return
        // Deadlines may move earlier (for example idle -> header timeout), so look again at least every recheck
        // interval; a deadline therefore fires at most that much late.
        val recheck = tick + recheckTicks
        val targetTick = if (deadline == 0L) recheck else maxOf(deadline / tickMillis, tick + 1).coerceAtMost(recheck)
        buckets[(targetTick and mask.toLong()).toInt()].add(t)
    }

    /** Milliseconds until the next tick, for event loops that block in a poll call. */
    fun millisToNextTick(): Long = maxOf(1, (tick * tickMillis) - nowMillis())

    /** Starts a daemon thread that drives the wheel. */
    fun startThread(name: String) {
        running = true
        thread =
            Thread.ofPlatform().daemon().name(name).start {
                while (running) {
                    advance()
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(tickMillis))
                }
            }
    }

    fun stop() {
        running = false
        thread?.let { LockSupport.unpark(it) }
    }

    companion object {
        private val origin = System.nanoTime()

        fun nowMillis(): Long = (System.nanoTime() - origin) / 1_000_000 + 1
    }
}
