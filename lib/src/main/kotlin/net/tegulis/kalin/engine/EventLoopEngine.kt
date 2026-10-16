package net.tegulis.kalin.engine

import java.net.InetSocketAddress

internal class EventLoopEngine(private val runtime: ServerRuntime, private val epoll: Boolean) : ServerEngine {
    private val loom = LoomEngine(runtime)

    override fun start(): InetSocketAddress? = loom.start()

    override fun stop() = loom.stop()

    override fun connectInMemory(): MemoryConnection = loom.connectInMemory()
}
