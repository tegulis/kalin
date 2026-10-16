package net.tegulis.kalin.engine.loop

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import net.tegulis.kalin.engine.ServerRuntime
import net.tegulis.kalin.engine.TimerWheel

/** A non-blocking socket owned by one event loop. All methods are called on the loop thread. */
internal interface LoopSocket {
    /** Reads into [b]; returns bytes read, 0 if it would block, -1 at end of stream. */
    fun read(b: ByteArray, off: Int, len: Int): Int

    /** Writes from [b]; returns bytes written (0 if it would block). */
    fun write(b: ByteArray, off: Int, len: Int): Int

    /** Starts sending a file region; returns a handle for [sendFile]. */
    fun openFile(path: Path): AutoCloseable

    /** Sends up to [count] bytes of an opened file from [position]; returns bytes sent (0 if it would block). */
    fun sendFile(file: AutoCloseable, position: Long, count: Long): Long

    /** The current size of an opened file. */
    fun fileSize(file: AutoCloseable): Long

    /** Enables or disables read readiness notifications (level-triggered transports only). */
    fun setReadInterest(on: Boolean)

    /** Enables or disables write readiness notifications (level-triggered transports only). */
    fun setWriteInterest(on: Boolean)

    /** True for edge-triggered transports: reads must continue until they would block. */
    val edgeTriggered: Boolean

    fun shutdownOutput()

    fun close()

    val remoteAddress: InetSocketAddress?
    val localAddress: InetSocketAddress?
}

/**
 * One event loop thread: waits for readiness events, runs tasks submitted by other threads, and drives a timer wheel
 * for connection deadlines. Connections are owned by exactly one loop, so their state needs no locks.
 */
internal abstract class EventLoop(val name: String, val runtime: ServerRuntime) : Runnable {
    lateinit var thread: Thread
        private set

    val timer = TimerWheel()
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val wakeupPending = AtomicBoolean(false)

    @Volatile var running: Boolean = true

    val inLoop: Boolean
        get() = Thread.currentThread() === thread

    fun start() {
        thread = Thread.ofPlatform().name(name).start(this)
    }

    /** Runs [task] on the loop thread (immediately queued; the loop is woken if needed). */
    fun execute(task: Runnable) {
        tasks.add(task)
        if (!inLoop && wakeupPending.compareAndSet(false, true)) wakeup()
    }

    /** Interrupts a blocking [poll]. Thread-safe. */
    protected abstract fun wakeup()

    /** Waits up to [timeoutMs] for readiness events and dispatches them. */
    protected abstract fun poll(timeoutMs: Long)

    /** Releases transport resources; called on the loop thread when it ends. */
    protected abstract fun cleanup()

    private fun runTasks() {
        wakeupPending.set(false)
        while (true) {
            val task = tasks.poll() ?: break
            try {
                task.run()
            } catch (t: Throwable) {
                ServerRuntime.log.log(System.Logger.Level.ERROR, "Error in event loop task", t)
            }
        }
    }

    override fun run() {
        try {
            while (running) {
                try {
                    poll(if (tasks.isEmpty()) timer.millisToNextTick() else 0)
                } catch (e: IOException) {
                    ServerRuntime.log.log(System.Logger.Level.ERROR, "Event loop poll failed", e)
                }
                runTasks()
                timer.advance()
            }
            runTasks()
        } finally {
            cleanup()
        }
    }

    fun shutdown() {
        running = false
        wakeup()
    }

    /** Releases the resources of a loop that was never started (a failed server start). */
    fun closeUnstarted() {
        check(!::thread.isInitialized) { "Event loop $name was started" }
        running = false
        cleanup()
    }
}
