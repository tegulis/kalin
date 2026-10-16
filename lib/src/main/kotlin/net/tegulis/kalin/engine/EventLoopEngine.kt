package net.tegulis.kalin.engine

import java.io.IOException
import java.lang.foreign.Arena
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import net.tegulis.kalin.engine.epoll.EpollEventLoop
import net.tegulis.kalin.engine.epoll.Native
import net.tegulis.kalin.engine.loop.EventLoop
import net.tegulis.kalin.engine.loop.NioEventLoop

/**
 * `Engine.EVENT_LOOP`: N event loops with non-blocking connection state machines. On Linux with native access the loops
 * use epoll directly (one `SO_REUSEPORT` listening socket per loop); elsewhere they use NIO selectors fed by one
 * acceptor thread.
 */
internal class EventLoopEngine(private val runtime: ServerRuntime, private val epoll: Boolean) : ServerEngine {
    private val loops = ArrayList<EventLoop>()
    private var acceptor: Thread? = null
    private var server: ServerSocketChannel? = null

    override fun start(): InetSocketAddress {
        val config = runtime.config
        val count = config.eventLoops.coerceAtLeast(1)
        return if (epoll) startEpoll(count) else startNio(count)
    }

    private fun startEpoll(count: Int): InetSocketAddress {
        val config = runtime.config
        var port = config.port
        var address: InetSocketAddress? = null
        try {
            Arena.ofConfined().use { arena ->
                val scratch = Native.Scratch(arena)
                for (i in 0 until count) {
                    val fd = EpollEventLoop.listen(scratch, config.host, port, config.backlog)
                    val loop = EpollEventLoop("kalin-epoll-$i", runtime, fd)
                    if (i == 0) {
                        address = loop.localAddress
                        port = address?.port ?: port
                    }
                    loops.add(loop)
                }
            }
        } catch (e: Exception) {
            loops.forEach { it.shutdown() }
            throw e
        }
        loops.forEach { it.start() }
        val bound = address ?: InetSocketAddress(port)
        return if (config.host == null) InetSocketAddress(bound.port) else bound
    }

    private fun startNio(count: Int): InetSocketAddress {
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
        repeat(count) { loops.add(NioEventLoop("kalin-loop-$it", runtime).also { l -> l.start() }) }
        acceptor = Thread.ofPlatform().name("kalin-acceptor").start { acceptLoop(channel) }
        return channel.localAddress as InetSocketAddress
    }

    private fun acceptLoop(channel: ServerSocketChannel) {
        var next = 0
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
            if (runtime.stopping || !runtime.tryOpen()) {
                if (!runtime.stopping && runtime.config.overloadPolicy == OverloadPolicy.REJECT_503) {
                    try {
                        socket.write(ByteBuffer.wrap(Http1Exchange.errorResponse(503, "Service Unavailable")))
                    } catch (_: IOException) {}
                }
                socket.close()
                continue
            }
            val loop = loops[next] as NioEventLoop
            next = (next + 1) % loops.size
            loop.execute { loop.register(socket) }
        }
    }

    override fun stop() {
        runtime.stopping = true
        try {
            server?.close()
        } catch (_: IOException) {}
        acceptor?.join(2000)
        for (loop in loops) if (loop is EpollEventLoop) loop.execute { loop.stopAccepting() }
        runtime.drainConnections()
        loops.forEach { it.shutdown() }
        loops.forEach { it.thread.join(2000) }
    }

    override fun connectInMemory(): MemoryConnection = MemoryTransport.connect(runtime)
}
