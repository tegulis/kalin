package net.tegulis.kalin.engine.loop

import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Path
import net.tegulis.kalin.engine.ServerRuntime

/** An event loop on a JDK [Selector] (level-triggered; works on every platform). */
internal class NioEventLoop(name: String, runtime: ServerRuntime) : EventLoop(name, runtime) {
    private val selector: Selector = Selector.open()

    override fun wakeup() {
        selector.wakeup()
    }

    /** Registers an accepted channel; must run on the loop thread. */
    fun register(channel: SocketChannel) {
        try {
            channel.configureBlocking(false)
            if (runtime.config.tcpNoDelay) channel.setOption(StandardSocketOptions.TCP_NODELAY, true)
            val socket = NioLoopSocket(channel)
            val connection = LoopConnection(socket, this, runtime)
            socket.key = channel.register(selector, SelectionKey.OP_READ, connection)
            connection.start()
        } catch (e: IOException) {
            runtime.closed()
            try {
                channel.close()
            } catch (_: IOException) {}
        }
    }

    override fun poll(timeoutMs: Long) {
        val action = { key: SelectionKey ->
            val connection = key.attachment() as LoopConnection
            if (key.isValid) {
                val ops = key.readyOps()
                if (ops and SelectionKey.OP_WRITE != 0) connection.onWritable()
                if (key.isValid && ops and SelectionKey.OP_READ != 0) connection.onReadable()
            }
        }
        if (timeoutMs <= 0) selector.selectNow(action) else selector.select(action, timeoutMs)
    }

    override fun cleanup() {
        for (key in selector.keys()) (key.attachment() as? LoopConnection)?.closeNow()
        try {
            selector.close()
        } catch (_: IOException) {}
    }
}

/** A non-blocking [SocketChannel] as [LoopSocket]. */
internal class NioLoopSocket(private val channel: SocketChannel) : LoopSocket {
    lateinit var key: SelectionKey
    override val remoteAddress: InetSocketAddress? = channel.remoteAddress as? InetSocketAddress
    override val localAddress: InetSocketAddress? = channel.localAddress as? InetSocketAddress
    override val edgeTriggered: Boolean = false

    override fun read(b: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(b, off, len))

    override fun write(b: ByteArray, off: Int, len: Int): Int = channel.write(ByteBuffer.wrap(b, off, len))

    override fun openFile(path: Path): AutoCloseable = FileChannel.open(path)

    override fun sendFile(file: AutoCloseable, position: Long, count: Long): Long =
        (file as FileChannel).transferTo(position, count, channel)

    override fun fileSize(file: AutoCloseable): Long = (file as FileChannel).size()

    private fun setOp(op: Int, on: Boolean) {
        if (!key.isValid) return
        val ops = key.interestOps()
        key.interestOps(if (on) ops or op else ops and op.inv())
    }

    override fun setReadInterest(on: Boolean) = setOp(SelectionKey.OP_READ, on)

    override fun setWriteInterest(on: Boolean) = setOp(SelectionKey.OP_WRITE, on)

    override fun shutdownOutput() {
        try {
            channel.shutdownOutput()
        } catch (_: IOException) {}
    }

    override fun close() {
        try {
            key.cancel()
            channel.close()
        } catch (_: IOException) {}
    }
}
