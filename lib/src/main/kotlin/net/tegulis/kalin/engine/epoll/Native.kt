package net.tegulis.kalin.engine.epoll

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** A failed system call. */
internal class NativeException(val call: String, val errno: Int) :
    java.io.IOException("$call failed: errno $errno (${Errno.name(errno)})")

internal object Errno {
    const val EINTR = 4
    const val EBADF = 9
    const val EAGAIN = 11
    const val EPIPE = 32
    const val ECONNRESET = 104
    const val ENOTCONN = 107
    const val EMFILE = 24
    const val ENFILE = 23
    const val ECONNABORTED = 103
    const val EADDRINUSE = 98

    fun name(errno: Int): String =
        when (errno) {
            EINTR -> "EINTR"
            EBADF -> "EBADF"
            EAGAIN -> "EAGAIN"
            EPIPE -> "EPIPE"
            ECONNRESET -> "ECONNRESET"
            ENOTCONN -> "ENOTCONN"
            EMFILE -> "EMFILE"
            ENFILE -> "ENFILE"
            ECONNABORTED -> "ECONNABORTED"
            EADDRINUSE -> "EADDRINUSE"
            else -> "?"
        }
}

/**
 * Downcall handles to the Linux libc functions used by the epoll transport. Created once. Socket read/write calls are
 * `critical` (no thread state transition, heap arrays passed directly), which is safe because the sockets are
 * non-blocking.
 */
internal object Native {
    const val AF_INET = 2
    const val AF_INET6 = 10
    const val SOCK_STREAM = 1
    const val SOCK_NONBLOCK = 0x800
    const val SOCK_CLOEXEC = 0x80000
    const val SOL_SOCKET = 1
    const val SO_REUSEADDR = 2
    const val SO_REUSEPORT = 15
    const val IPPROTO_TCP = 6
    const val IPPROTO_IPV6 = 41
    const val IPV6_V6ONLY = 26
    const val TCP_NODELAY = 1
    const val TCP_DEFER_ACCEPT = 9
    const val EPOLL_CTL_ADD = 1
    const val EPOLL_CTL_DEL = 2
    const val EPOLL_CTL_MOD = 3
    const val EPOLLIN = 0x001
    const val EPOLLOUT = 0x004
    const val EPOLLERR = 0x008
    const val EPOLLHUP = 0x010
    const val EPOLLRDHUP = 0x2000
    const val EPOLLET = 1 shl 31
    const val EFD_NONBLOCK = 0x800
    const val EFD_CLOEXEC = 0x80000
    const val EPOLL_CLOEXEC = 0x80000
    const val O_RDONLY = 0
    const val O_CLOEXEC = 0x80000
    const val SHUT_WR = 1

    private val linker: Linker = Linker.nativeLinker()
    private val libc = linker.defaultLookup()
    private val captureLayout: StructLayout = Linker.Option.captureStateLayout()
    private val errnoHandle: VarHandle = captureLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))

    /** epoll_event is packed (12 bytes) on x86-64 and naturally aligned (16 bytes) elsewhere. */
    val packedEpollEvent: Boolean = System.getProperty("os.arch").let { it == "amd64" || it == "x86_64" }
    val epollEventSize: Long = if (packedEpollEvent) 12 else 16
    val epollDataOffset: Long = if (packedEpollEvent) 4 else 8

    private fun handle(name: String, descriptor: FunctionDescriptor, critical: Boolean = false): MethodHandle {
        val symbol = libc.find(name).orElseThrow { UnsatisfiedLinkError("libc function $name not found") }
        val options =
            if (critical) arrayOf(Linker.Option.captureCallState("errno"), Linker.Option.critical(true))
            else arrayOf(Linker.Option.captureCallState("errno"))
        return linker.downcallHandle(symbol, descriptor, *options)
    }

    private val epollCreate1 = handle("epoll_create1", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    private val epollCtl = handle("epoll_ctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS))
    private val epollWait = handle("epoll_wait", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
    private val eventfd = handle("eventfd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    private val socket = handle("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
    private val setsockopt =
        handle("setsockopt", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    private val bind = handle("bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    private val listen = handle("listen", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    private val accept4 = handle("accept4", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
    private val getsockname = handle("getsockname", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    private val read = handle("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), critical = true)
    private val write = handle("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), critical = true)
    private val writev = handle("writev", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT))
    private val sendfile = handle("sendfile", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG))
    private val open =
        linker.downcallHandle(
            libc.find("open").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
            Linker.Option.captureCallState("errno"),
            Linker.Option.firstVariadicArg(2),
        )
    private val shutdown = handle("shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    private val close = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

    /** Per-thread scratch memory for errno capture and small out parameters. */
    class Scratch(arena: Arena) {
        val state: MemorySegment = arena.allocate(captureLayout)
        val small: MemorySegment = arena.allocate(256, 8)
        val iovecs: MemorySegment = arena.allocate(16L * 64, 8)

        fun errno(): Int = errnoHandle.get(state, 0L) as Int
    }

    fun epollCreate(s: Scratch): Int =
        check(s, "epoll_create1", epollCreate1.invokeExact(s.state, EPOLL_CLOEXEC) as Int)

    fun epollCtl(s: Scratch, epfd: Int, op: Int, fd: Int, events: Int, data: Long) {
        val ev = s.small
        ev.set(JAVA_INT, 0, events)
        ev.set(JAVA_LONG.withByteAlignment(1), epollDataOffset, data)
        check(s, "epoll_ctl", epollCtl.invokeExact(s.state, epfd, op, fd, ev) as Int)
    }

    fun epollWait(s: Scratch, epfd: Int, events: MemorySegment, max: Int, timeoutMs: Int): Int {
        val n = epollWait.invokeExact(s.state, epfd, events, max, timeoutMs) as Int
        if (n < 0) {
            val e = s.errno()
            if (e == Errno.EINTR) return 0
            throw NativeException("epoll_wait", e)
        }
        return n
    }

    fun eventfd(s: Scratch): Int =
        check(s, "eventfd", eventfd.invokeExact(s.state, 0, EFD_NONBLOCK or EFD_CLOEXEC) as Int)

    fun socket(s: Scratch, family: Int): Int =
        check(s, "socket", socket.invokeExact(s.state, family, SOCK_STREAM or SOCK_NONBLOCK or SOCK_CLOEXEC, 0) as Int)

    fun setIntOption(s: Scratch, fd: Int, level: Int, option: Int, value: Int) {
        val v = s.small
        v.set(JAVA_INT, 0, value)
        check(s, "setsockopt", setsockopt.invokeExact(s.state, fd, level, option, v, 4) as Int)
    }

    /** Writes a sockaddr for [address] into [seg]; returns its length. */
    fun sockaddr(seg: MemorySegment, address: InetAddress, port: Int): Int {
        seg.fill(0)
        when (address) {
            is Inet4Address -> {
                seg.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 0, AF_INET.toShort())
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 2, (port ushr 8).toByte())
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 3, port.toByte())
                val a = address.address
                for (i in 0..3) seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 4L + i, a[i])
                return 16
            }
            is Inet6Address -> {
                seg.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 0, AF_INET6.toShort())
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 2, (port ushr 8).toByte())
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 3, port.toByte())
                val a = address.address
                for (i in 0..15) seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8L + i, a[i])
                seg.set(java.lang.foreign.ValueLayout.JAVA_INT, 24, address.scopeId)
                return 28
            }
            else -> throw IllegalArgumentException("Unsupported address $address")
        }
    }

    /** Reads a sockaddr_in/sockaddr_in6. */
    fun parseSockaddr(seg: MemorySegment): InetSocketAddress? {
        val family = seg.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 0).toInt()
        val port =
            ((seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 2).toInt() and 0xFF) shl 8) or
                (seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 3).toInt() and 0xFF)
        return when (family) {
            AF_INET ->
                InetSocketAddress(
                    InetAddress.getByAddress(
                        ByteArray(4) { seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 4L + it) }
                    ),
                    port,
                )
            AF_INET6 -> {
                val bytes = ByteArray(16) { seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 8L + it) }
                val v4mapped =
                    (0..9).all { bytes[it] == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
                val addr =
                    if (v4mapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                    else InetAddress.getByAddress(bytes)
                InetSocketAddress(addr, port)
            }
            else -> null
        }
    }

    fun bind(s: Scratch, fd: Int, address: InetAddress, port: Int) {
        val addr = s.small.asSlice(32, 64)
        val len = sockaddr(addr, address, port)
        val r = bind.invokeExact(s.state, fd, addr, len) as Int
        if (r < 0) {
            val e = s.errno()
            if (e == Errno.EADDRINUSE) throw java.net.BindException("Address already in use")
            throw NativeException("bind", e)
        }
    }

    fun listen(s: Scratch, fd: Int, backlog: Int) {
        check(s, "listen", listen.invokeExact(s.state, fd, backlog) as Int)
    }

    fun localAddress(s: Scratch, fd: Int): InetSocketAddress? {
        val addr = s.small.asSlice(32, 64)
        val len = s.small.asSlice(128, 8)
        len.set(JAVA_INT, 0, 64)
        check(s, "getsockname", getsockname.invokeExact(s.state, fd, addr, len) as Int)
        return parseSockaddr(addr)
    }

    /** Accepts a connection; returns the fd or -1 if none is pending. The peer address is left in [peer]. */
    fun accept(s: Scratch, fd: Int, peer: MemorySegment, peerLen: MemorySegment): Int {
        peerLen.set(JAVA_INT, 0, peer.byteSize().toInt())
        val r = accept4.invokeExact(s.state, fd, peer, peerLen, SOCK_NONBLOCK or SOCK_CLOEXEC) as Int
        if (r < 0) {
            val e = s.errno()
            if (e == Errno.EAGAIN || e == Errno.EINTR || e == Errno.ECONNABORTED) return -1
            throw NativeException("accept4", e)
        }
        return r
    }

    /** Non-blocking read into a heap array. Returns bytes read, 0 if it would block, -1 at EOF. */
    fun read(s: Scratch, fd: Int, buf: ByteArray, off: Int, len: Int): Int {
        val n =
            read.invokeExact(s.state, fd, MemorySegment.ofArray(buf).asSlice(off.toLong(), len.toLong()), len.toLong())
                as Long
        if (n > 0) return n.toInt()
        if (n == 0L) return -1
        val e = s.errno()
        if (e == Errno.EAGAIN || e == Errno.EINTR) return 0
        throw NativeException("read", e)
    }

    /** Non-blocking write from a heap array. Returns bytes written (0 if it would block). */
    fun write(s: Scratch, fd: Int, buf: ByteArray, off: Int, len: Int): Int {
        val n =
            write.invokeExact(s.state, fd, MemorySegment.ofArray(buf).asSlice(off.toLong(), len.toLong()), len.toLong())
                as Long
        if (n >= 0) return n.toInt()
        val e = s.errno()
        if (e == Errno.EAGAIN || e == Errno.EINTR) return 0
        throw NativeException("write", e)
    }

    /** Gathering write of native segments. Returns bytes written (0 if it would block). */
    fun writev(s: Scratch, fd: Int, segments: Array<MemorySegment>, count: Int): Long {
        val iov = s.iovecs
        for (i in 0 until count) {
            iov.set(ADDRESS, i * 16L, segments[i])
            iov.set(JAVA_LONG, i * 16L + 8, segments[i].byteSize())
        }
        val n = writev.invokeExact(s.state, fd, iov, count) as Long
        if (n >= 0) return n
        val e = s.errno()
        if (e == Errno.EAGAIN || e == Errno.EINTR) return 0
        throw NativeException("writev", e)
    }

    /** Non-blocking sendfile. Returns bytes sent (0 if it would block). */
    fun sendfile(s: Scratch, outFd: Int, inFd: Int, position: Long, count: Long): Long {
        val off = s.small.asSlice(192, 8)
        off.set(JAVA_LONG, 0, position)
        val n = sendfile.invokeExact(s.state, outFd, inFd, off, count) as Long
        if (n >= 0) return n
        val e = s.errno()
        if (e == Errno.EAGAIN || e == Errno.EINTR) return 0
        throw NativeException("sendfile", e)
    }

    fun openRead(s: Scratch, arena: Arena, path: String): Int {
        val p = arena.allocateFrom(path)
        return check(s, "open", open.invokeExact(s.state, p, O_RDONLY or O_CLOEXEC) as Int)
    }

    fun shutdownOutput(s: Scratch, fd: Int) {
        @Suppress("UNUSED_VARIABLE") val ignored = shutdown.invokeExact(s.state, fd, SHUT_WR) as Int
    }

    fun close(s: Scratch, fd: Int) {
        @Suppress("UNUSED_VARIABLE") val ignored = close.invokeExact(s.state, fd) as Int
    }

    fun writeEventfd(s: Scratch, fd: Int) {
        val v = s.small.asSlice(224, 8)
        v.set(JAVA_LONG, 0, 1L)
        @Suppress("UNUSED_VARIABLE") val ignored = write.invokeExact(s.state, fd, v, 8L) as Long
    }

    fun drainEventfd(s: Scratch, fd: Int) {
        val v = s.small.asSlice(224, 8)
        @Suppress("UNUSED_VARIABLE") val ignored = read.invokeExact(s.state, fd, v, 8L) as Long
    }

    private fun check(s: Scratch, call: String, result: Int): Int {
        if (result < 0) throw NativeException(call, s.errno())
        return result
    }
}
