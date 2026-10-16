package net.tegulis.kalin.engine

import java.lang.System.Logger.Level
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import net.tegulis.kalin.config.ServerConfig

/** Handles exchanges; implemented by the Kalin request pipeline. */
internal interface ExchangeHandler {
    /** Handles the exchange. The pipeline completes the response, possibly later from another thread. */
    fun handle(exchange: Http1Exchange)

    /** True if requests to this method and path can run on an event loop thread. */
    fun isNonBlocking(method: String, path: String): Boolean = false
}

/** A running server engine. */
internal interface ServerEngine {
    /** Binds and starts accepting connections. Returns the bound address (null for in-memory only). */
    fun start(): InetSocketAddress?

    /** Stops accepting, finishes in-flight exchanges within the shutdown timeout, closes everything. */
    fun stop()

    /** Opens an in-memory client connection to this server. */
    fun connectInMemory(): MemoryConnection
}

/** A connection that can be closed by the runtime on shutdown. */
internal interface ManagedConnection {
    /** True while waiting for the first byte of a request. */
    val idle: Boolean

    /** Closes the connection now. */
    fun forceClose()
}

/** State shared by all connections of one server. */
internal class ServerRuntime(val config: ServerConfig, val handler: ExchangeHandler) {
    val limits =
        Limits(
            maxRequestLineSize = config.maxRequestLineSize,
            maxHeaderSize = config.maxHeaderSize,
            maxHeaderCount = config.maxHeaderCount,
        )
    val serverHeader: ByteArray? = config.serverHeader?.let { "Server: $it\r\n".toByteArray(Charsets.ISO_8859_1) }
    val idleTimeoutMs = config.idleTimeout.inWholeMilliseconds
    val requestTimeoutMs = config.requestTimeout.inWholeMilliseconds
    val writeTimeoutMs = config.writeTimeout.inWholeMilliseconds
    val connections: MutableSet<ManagedConnection> = ConcurrentHashMap.newKeySet()
    val openConnections = AtomicInteger()
    val activeExchanges = AtomicInteger()
    val totalRequests = AtomicLong()

    @Volatile var stopping: Boolean = false

    /** The timer wheel driving connection deadlines of blocking sessions. */
    var timer: TimerWheel? = null

    /** Returns false if the connection must be rejected because of `maxConnections`. */
    fun tryOpen(): Boolean {
        val n = openConnections.incrementAndGet()
        if (config.maxConnections in 1..<n) {
            openConnections.decrementAndGet()
            return false
        }
        return true
    }

    fun closed() {
        openConnections.decrementAndGet()
    }

    /** Closes idle connections, waits for active ones, then force-closes the rest. */
    fun drainConnections() {
        stopping = true
        for (c in connections) if (c.idle) c.forceClose()
        val deadline = System.nanoTime() + config.shutdownTimeout.inWholeNanoseconds
        while (activeExchanges.get() > 0 && System.nanoTime() < deadline) {
            for (c in connections) if (c.idle) c.forceClose()
            Thread.sleep(5)
        }
        for (c in connections) c.forceClose()
    }

    companion object {
        val log: System.Logger = System.getLogger("net.tegulis.kalin.engine")

        fun debug(message: () -> String) {
            if (log.isLoggable(Level.DEBUG)) log.log(Level.DEBUG, message())
        }
    }
}
