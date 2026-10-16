package net.tegulis.kalin.engine

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ThreadFactory

/**
 * `Engine.LOOM`: an acceptor thread hands each connection to its own virtual thread, which runs an [Http1Session] with
 * blocking channel I/O. The JDK parks blocked virtual threads on its own epoll/kqueue/IOCP poller, so idle keep-alive
 * connections cost no platform thread.
 */
internal class LoomEngine(private val runtime: ServerRuntime) : ServerEngine {
    private var server: ServerSocketChannel? = null
    private var acceptor: Thread? = null
    private val timer = TimerWheel()
    private val factory: ThreadFactory = Thread.ofVirtual().name("kalin-conn-", 0).factory()

    init {
        runtime.timer = timer
    }

    override fun start(): InetSocketAddress {
        val config = runtime.config
        val channel = ServerSocketChannel.open()
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            val address =
                config.host?.let { InetSocketAddress(InetAddress.getByName(it), config.port) }
                    ?: InetSocketAddress(config.port)
            channel.bind(address, config.backlog)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        server = channel
        timer.startThread("kalin-timer")
        acceptor = Thread.ofPlatform().name("kalin-acceptor").daemon(false).start { acceptLoop(channel) }
        return channel.localAddress as InetSocketAddress
    }

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (channel.isOpen) {
            val socket =
                try {
                    channel.accept()
                } catch (_: ClosedChannelException) {
                    break
                } catch (e: IOException) {
                    if (!channel.isOpen) break
                    ServerRuntime.log.log(System.Logger.Level.WARNING, "Accept failed: ${e.message}")
                    Thread.sleep(10)
                    continue
                }
            if (runtime.stopping) {
                socket.close()
                continue
            }
            if (!runtime.tryOpen()) {
                reject(socket)
                continue
            }
            try {
                if (runtime.config.tcpNoDelay) socket.setOption(StandardSocketOptions.TCP_NODELAY, true)
                factory.newThread(Http1Session(NioBlockingIo(socket), runtime)).start()
            } catch (e: Throwable) {
                runtime.closed()
                socket.close()
                ServerRuntime.log.log(System.Logger.Level.WARNING, "Could not start connection: ${e.message}")
            }
        }
    }

    private fun reject(socket: SocketChannel) {
        if (runtime.config.overloadPolicy == OverloadPolicy.REJECT_503) {
            Thread.ofVirtual().start {
                try {
                    socket.write(ByteBuffer.wrap(Http1Exchange.errorResponse(503, "Service Unavailable")))
                } catch (_: IOException) {}
                socket.close()
            }
        } else {
            socket.close()
        }
    }

    override fun stop() {
        runtime.stopping = true
        try {
            server?.close()
        } catch (_: IOException) {}
        acceptor?.join(2000)
        runtime.drainConnections()
        timer.stop()
    }

    override fun connectInMemory(): MemoryConnection = MemoryTransport.connect(runtime)
}

/** Blocking I/O over a [SocketChannel]. */
internal class NioBlockingIo(private val channel: SocketChannel) : BlockingIo() {
    override val remoteAddress: InetSocketAddress? = channel.remoteAddress as? InetSocketAddress
    override val localAddress: InetSocketAddress? = channel.localAddress as? InetSocketAddress

    override fun read(b: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(b, off, len))

    override fun write(b: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(b, off, len)
        while (buf.hasRemaining()) channel.write(buf)
    }

    override fun transferFile(channel: FileChannel, position: Long, count: Long) {
        var pos = position
        val end = position + count
        while (pos < end) {
            val n = channel.transferTo(pos, end - pos, this.channel)
            if (n <= 0) {
                // fall back to copying (for example when sendfile is not possible)
                super.transferFile(channel, pos, end - pos)
                return
            }
            pos += n
        }
    }

    override fun shutdownOutput() {
        channel.shutdownOutput()
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: IOException) {}
    }

    override val isClosed: Boolean
        get() = !channel.isOpen
}
