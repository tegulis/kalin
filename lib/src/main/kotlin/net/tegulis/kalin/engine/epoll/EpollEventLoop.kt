package net.tegulis.kalin.engine.epoll

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import net.tegulis.kalin.engine.Http1Exchange
import net.tegulis.kalin.engine.OverloadPolicy
import net.tegulis.kalin.engine.ServerRuntime
import net.tegulis.kalin.engine.loop.EventLoop
import net.tegulis.kalin.engine.loop.LoopConnection
import net.tegulis.kalin.engine.loop.LoopSocket

/**
 * An event loop on Linux epoll (edge-triggered) through FFM. Each loop owns its own listening socket (`SO_REUSEPORT`),
 * so the kernel balances new connections between loops without a shared acceptor. If the constructor throws, the caller
 * still owns `listenFd`.
 */
internal class EpollEventLoop(name: String, runtime: ServerRuntime, private val listenFd: Int) :
    EventLoop(name, runtime) {
    private val arena: Arena = Arena.ofShared()
    internal val scratch = Native.Scratch(arena)
    private val wakeupScratch = Native.Scratch(arena)
    private val epfd: Int
    private val eventFd: Int
    private val maxEvents = 512
    private val events: MemorySegment = arena.allocate(Native.epollEventSize * maxEvents, 8)
    private val peer: MemorySegment = arena.allocate(64, 8)
    private val peerLen: MemorySegment = arena.allocate(8, 8)
    private val connections = HashMap<Int, LoopConnection>()
    @Volatile private var accepting = true

    /** Accepts one pending connection (replaceable in tests to simulate failures). */
    internal var accept: (Int) -> Int = { fd -> Native.accept(scratch, fd, peer, peerLen) }

    /** Set after accept failures (for example `EMFILE`) until an accept succeeds; one warning per episode. */
    private var backingOff = false

    /** While accepting is paused after a failure: when to watch the listening socket again (timer clock), else 0. */
    private var acceptResumeAt = 0L

    init {
        var ep = -1
        var ev = -1
        try {
            ep = Native.epollCreate(scratch)
            ev = Native.eventfd(scratch)
            Native.epollCtl(scratch, ep, Native.EPOLL_CTL_ADD, ev, Native.EPOLLIN, TAG_WAKEUP)
            if (listenFd >= 0) Native.epollCtl(scratch, ep, Native.EPOLL_CTL_ADD, listenFd, Native.EPOLLIN, TAG_LISTEN)
        } catch (e: Exception) {
            if (ev >= 0) Native.close(scratch, ev)
            if (ep >= 0) Native.close(scratch, ep)
            arena.close()
            throw e
        }
        epfd = ep
        eventFd = ev
    }

    /** Set once the eventfd and arena are released; guarded by the wakeup lock. */
    private var terminated = false

    override fun wakeup() {
        // Handler threads may still submit tasks after the loop ended
        synchronized(wakeupScratch) { if (!terminated) Native.writeEventfd(wakeupScratch, eventFd) }
    }

    override fun poll(timeoutMs: Long) {
        // The loop polls at least once per timer tick, so a paused listener is re-armed about on time
        if (acceptResumeAt != 0L && timer.now >= acceptResumeAt) resumeAccepting()
        val n =
            Native.epollWait(scratch, epfd, events, maxEvents, timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        for (i in 0 until n) {
            val base = i * Native.epollEventSize
            val ev = events.get(JAVA_INT, base)
            val data = events.get(JAVA_LONG_UNALIGNED, base + Native.epollDataOffset)
            when (data) {
                TAG_WAKEUP -> Native.drainEventfd(scratch, eventFd)
                TAG_LISTEN -> acceptAll()
                else -> {
                    val connection = connections[data.toInt()] ?: continue
                    if (ev and Native.EPOLLOUT != 0) connection.onWritable()
                    if (ev and (Native.EPOLLIN or Native.EPOLLRDHUP or Native.EPOLLERR or Native.EPOLLHUP) != 0)
                        connection.onReadable()
                }
            }
        }
    }

    private fun acceptAll() {
        while (accepting && acceptResumeAt == 0L) {
            val fd =
                try {
                    accept(listenFd)
                } catch (e: NativeException) {
                    pauseAccepting(e)
                    return
                }
            backingOff = false
            if (fd < 0) return
            if (runtime.stopping || !runtime.tryOpen()) {
                if (!runtime.stopping && runtime.config.overloadPolicy == OverloadPolicy.REJECT_503) {
                    // Best effort, like the NIO acceptor: a fresh socket's send buffer takes the small response
                    try {
                        Native.write(scratch, fd, REJECT_503, 0, REJECT_503.size)
                    } catch (_: NativeException) {}
                }
                Native.close(scratch, fd)
                continue
            }
            val remote = Native.parseSockaddr(peer)
            val target = peers[nextPeer]
            nextPeer = (nextPeer + 1) % peers.size
            if (target === this) register(fd, remote) else target.execute { target.register(fd, remote) }
        }
    }

    /**
     * The listening socket is level-triggered: after a failed accept (out of descriptors or memory) it would report
     * readiness again immediately. Stop watching it for a moment instead of spinning.
     */
    private fun pauseAccepting(e: NativeException) {
        if (!backingOff) {
            backingOff = true
            ServerRuntime.log.log(
                System.Logger.Level.WARNING,
                "accept failed: ${e.message}; pausing accepting for $ACCEPT_BACKOFF_MS ms at a time until it succeeds",
            )
        }
        try {
            Native.epollCtl(scratch, epfd, Native.EPOLL_CTL_DEL, listenFd, 0, 0)
        } catch (_: NativeException) {}
        acceptResumeAt = timer.now + ACCEPT_BACKOFF_MS
    }

    private fun resumeAccepting() {
        acceptResumeAt = 0L
        if (!accepting) return
        try {
            Native.epollCtl(scratch, epfd, Native.EPOLL_CTL_ADD, listenFd, Native.EPOLLIN, TAG_LISTEN)
        } catch (e: NativeException) {
            pauseAccepting(e)
            return
        }
        acceptAll()
    }

    /** The loops accepted connections are distributed to (round-robin), including this one. */
    var peers: List<EpollEventLoop> = listOf(this)
    private var nextPeer = 0

    /** Registers an accepted connection with this loop (loop thread). */
    private fun register(fd: Int, remote: InetSocketAddress?) {
        try {
            if (runtime.config.tcpNoDelay) Native.setIntOption(scratch, fd, Native.IPPROTO_TCP, Native.TCP_NODELAY, 1)
            val socket = EpollSocket(fd, this, remote)
            val connection = LoopConnection(socket, this, runtime)
            connections[fd] = connection
            Native.epollCtl(scratch, epfd, Native.EPOLL_CTL_ADD, fd, EpollSocket.BASE_EVENTS, fd.toLong())
            connection.start()
            connection.onReadable()
        } catch (e: Exception) {
            ServerRuntime.log.log(System.Logger.Level.WARNING, "Could not register connection: ${e.message}")
            connections.remove(fd)?.closeNow()
                ?: run {
                    Native.close(scratch, fd)
                    runtime.closed()
                }
        }
    }

    internal fun modify(fd: Int, events: Int) {
        try {
            Native.epollCtl(scratch, epfd, Native.EPOLL_CTL_MOD, fd, events, fd.toLong())
        } catch (_: NativeException) {}
    }

    internal fun removed(fd: Int) {
        connections.remove(fd)
    }

    /** Stops accepting new connections (called on the loop thread). */
    fun stopAccepting() {
        accepting = false
        if (listenFd < 0) return
        try {
            Native.epollCtl(scratch, epfd, Native.EPOLL_CTL_DEL, listenFd, 0, 0)
        } catch (_: Exception) {}
        Native.close(scratch, listenFd)
    }

    val localAddress: InetSocketAddress?
        get() = if (listenFd >= 0) Native.localAddress(scratch, listenFd) else null

    override fun cleanup() {
        for (c in connections.values.toList()) c.closeNow()
        if (accepting) {
            accepting = false
            if (listenFd >= 0) Native.close(scratch, listenFd)
        }
        synchronized(wakeupScratch) { terminated = true }
        Native.close(scratch, eventFd)
        Native.close(scratch, epfd)
        // The arena is shared with wakeup callers; leave it to the GC-independent close after the thread ends
        try {
            arena.close()
        } catch (_: IllegalStateException) {}
    }

    companion object {
        const val TAG_WAKEUP = -1L
        const val TAG_LISTEN = -2L
        const val ACCEPT_BACKOFF_MS = 100L
        private val REJECT_503 = Http1Exchange.errorResponse(503, "Service Unavailable")
        private val JAVA_LONG_UNALIGNED = JAVA_LONG.withByteAlignment(1)

        /** Creates a non-blocking listening socket with `SO_REUSEPORT`. */
        fun listen(scratch: Native.Scratch, host: String?, port: Int, backlog: Int): Int {
            val address = host?.let { InetAddress.getByName(it) }
            val candidates =
                if (address != null) listOf(address)
                else listOf(InetAddress.getByName("::"), InetAddress.getByName("0.0.0.0"))
            var lastError: Exception? = null
            for (a in candidates) {
                val family = if (a is java.net.Inet6Address) Native.AF_INET6 else Native.AF_INET
                val fd =
                    try {
                        Native.socket(scratch, family)
                    } catch (e: NativeException) {
                        lastError = e
                        continue
                    }
                try {
                    Native.setIntOption(scratch, fd, Native.SOL_SOCKET, Native.SO_REUSEADDR, 1)
                    Native.setIntOption(scratch, fd, Native.SOL_SOCKET, Native.SO_REUSEPORT, 1)
                    if (family == Native.AF_INET6 && address == null) {
                        Native.setIntOption(scratch, fd, Native.IPPROTO_IPV6, Native.IPV6_V6ONLY, 0)
                    }
                    Native.bind(scratch, fd, a, port)
                    Native.listen(scratch, fd, backlog)
                    return fd
                } catch (e: Exception) {
                    Native.close(scratch, fd)
                    if (e is java.net.BindException) throw e
                    lastError = e
                }
            }
            throw lastError ?: java.io.IOException("Could not listen")
        }
    }
}

/** A non-blocking socket file descriptor as [LoopSocket]. */
internal class EpollSocket(
    private val fd: Int,
    private val loop: EpollEventLoop,
    override val remoteAddress: InetSocketAddress?,
) : LoopSocket {
    /** Loop thread scratch (reads, control operations). */
    private val s = loop.scratch

    /** Scratch for writes, which handler threads may also perform (serialized by the output queue lock). */
    private val ws = Native.Scratch(Arena.ofAuto())
    private var open = true
    override val edgeTriggered: Boolean = true

    /** Read once on the loop thread: handler threads must not use the loop's scratch memory. */
    override val localAddress: InetSocketAddress? =
        try {
            Native.localAddress(s, fd)
        } catch (_: NativeException) {
            null
        }

    override fun read(b: ByteArray, off: Int, len: Int): Int = Native.read(s, fd, b, off, len)

    override fun write(b: ByteArray, off: Int, len: Int): Int = Native.write(ws, fd, b, off, len)

    override fun openFile(path: Path): AutoCloseable {
        val fileFd = Arena.ofConfined().use { a -> Native.openRead(ws, a, path.toAbsolutePath().toString()) }
        // Closing is idempotent: a descriptor closed twice may already belong to another connection or file
        val handle =
            object : AutoCloseable {
                override fun close() {
                    handles.remove(this)?.let { Native.close(ws, it) }
                }
            }
        handles[handle] = fileFd
        return handle
    }

    private val handles = java.util.concurrent.ConcurrentHashMap<AutoCloseable, Int>()

    override fun sendFile(file: AutoCloseable, position: Long, count: Long): Long {
        val inFd = handles[file] ?: throw java.io.IOException("Unknown file handle")
        val n = Native.sendfile(ws, fd, inFd, position, minOf(count, 1L shl 30))
        return n
    }

    override fun setReadInterest(on: Boolean) {}

    /** EPOLLOUT is only registered while a write is incomplete (like Netty), avoiding readiness noise. */
    override fun setWriteInterest(on: Boolean) {
        if (!open) return
        val events = if (on) BASE_EVENTS or Native.EPOLLOUT else BASE_EVENTS
        loop.modify(fd, events)
    }

    companion object {
        const val BASE_EVENTS = Native.EPOLLIN or Native.EPOLLRDHUP or Native.EPOLLET
    }

    override fun shutdownOutput() {
        if (open) Native.shutdownOutput(s, fd)
    }

    override fun close() {
        if (!open) return
        open = false
        loop.removed(fd)
        Native.close(s, fd)
        for (h in handles.keys) handles.remove(h)?.let { Native.close(s, it) }
    }
}
