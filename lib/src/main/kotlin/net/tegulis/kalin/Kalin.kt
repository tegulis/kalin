package net.tegulis.kalin

import java.io.IOException
import java.lang.System.Logger.Level
import java.net.BindException
import java.util.function.Consumer
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.config.LifecycleEvent
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.engine.EventLoopEngine
import net.tegulis.kalin.engine.LoomEngine
import net.tegulis.kalin.engine.MemoryConnection
import net.tegulis.kalin.engine.MemoryEngine
import net.tegulis.kalin.engine.ServerEngine
import net.tegulis.kalin.engine.ServerRuntime
import net.tegulis.kalin.engine.Transport
import net.tegulis.kalin.engine.epoll.Epoll
import net.tegulis.kalin.http.KalinException
import net.tegulis.kalin.http.KalinState
import net.tegulis.kalin.http.RequestPipeline

/** Thrown when the server cannot bind its port. */
public class KalinBindException(message: String, cause: Throwable) : KalinException(message, cause)

/**
 * A Kalin application.
 *
 * ```
 * Kalin.create { config ->
 *     config.routes.get("/") { ctx -> ctx.result("Hello World") }
 * }.start(7070)
 * ```
 */
public class Kalin private constructor(private val cfg: KalinConfig) {
    private var engine: ServerEngine? = null
    private var runtime: ServerRuntime? = null
    private var state: KalinState? = null
    private var boundPort: Int = -1

    @Volatile private var started = false

    /** The engine and transport actually used (after start), for example `LOOM/NIO`. */
    public var engineDescription: String = "not started"
        private set

    /** Starts the server on the configured host and port. */
    public fun start(): Kalin = apply { startServer() }

    /** Starts the server on [port]. */
    public fun start(port: Int): Kalin = apply {
        cfg.server.port = port
        startServer()
    }

    /** Starts the server on [host] and [port]. */
    public fun start(host: String, port: Int): Kalin = apply {
        cfg.server.host = host
        cfg.server.port = port
        startServer()
    }

    @Synchronized
    private fun startServer() {
        check(!started) {
            "Server already started. If you are trying to call start() on an instance created by Kalin.start(), use Kalin.create() instead."
        }
        val startNanos = System.nanoTime()
        cfg.events.fire(LifecycleEvent.SERVER_STARTING)
        try {
            for (plugin in cfg.plugins.toList()) plugin.onStart(cfg)
            cfg.routes.frozen = true
            val s = KalinState(cfg)
            state = s
            val rt = ServerRuntime(cfg.server, RequestPipeline(s))
            runtime = rt
            val (e, description) = selectEngine(rt)
            engine = e
            engineDescription = description
            val address =
                try {
                    e.start()
                } catch (ex: BindException) {
                    throw KalinBindException(
                        "Port already in use. Make sure no other process is using port ${cfg.server.port} and try again.",
                        ex,
                    )
                }
            boundPort = address?.port ?: cfg.server.port
            started = true
            if (cfg.startup.showBanner) {
                val ms = (System.nanoTime() - startNanos) / 1_000_000
                val where =
                    if (address == null) "in memory"
                    else "on http://${cfg.server.host ?: "localhost"}:$boundPort${s.contextPath}/"
                log.log(Level.INFO, "Kalin $VERSION started in $ms ms, listening $where ($description)")
            }
            cfg.events.fire(LifecycleEvent.SERVER_STARTED)
        } catch (t: Throwable) {
            log.log(Level.ERROR, "Failed to start Kalin", t)
            try {
                engine?.stop()
            } catch (_: Throwable) {}
            cfg.events.fire(LifecycleEvent.SERVER_START_FAILED)
            if (t is KalinException) throw t
            if (t is IOException) throw KalinException("Failed to start server: ${t.message}", t)
            throw t
        }
    }

    private fun selectEngine(rt: ServerRuntime): Pair<ServerEngine, String> {
        val server = cfg.server
        if (server.transport == Transport.MEMORY) return MemoryEngine(rt) to "${server.engine}/MEMORY"
        return when (server.engine) {
            Engine.LOOM -> {
                if (server.transport == Transport.EPOLL) {
                    log.log(Level.WARNING, "Engine.LOOM supports only Transport.NIO; using NIO")
                }
                LoomEngine(rt) to "LOOM/NIO"
            }
            Engine.EVENT_LOOP -> {
                val useEpoll =
                    when (server.transport) {
                        Transport.EPOLL ->
                            Epoll.available().also {
                                if (!it)
                                    log.log(
                                        Level.WARNING,
                                        "Native epoll unavailable (${Epoll.unavailabilityReason()}); using NIO",
                                    )
                            }
                        Transport.AUTO -> Epoll.available()
                        else -> false
                    }
                EventLoopEngine(rt, useEpoll) to "EVENT_LOOP/${if (useEpoll) "EPOLL" else "NIO"}"
            }
        }
    }

    /** Stops the server: stops accepting, finishes in-flight requests (within the shutdown timeout), closes all. */
    @Synchronized
    public fun stop(): Kalin = apply {
        if (!started) return@apply
        cfg.events.fire(LifecycleEvent.SERVER_STOPPING)
        try {
            engine?.stop()
            started = false
            cfg.events.fire(LifecycleEvent.SERVER_STOPPED)
        } catch (t: Throwable) {
            log.log(Level.ERROR, "Failed to stop Kalin", t)
            cfg.events.fire(LifecycleEvent.SERVER_STOP_FAILED)
        }
    }

    /** The bound port (useful with port 0), or -1 before start. */
    public fun port(): Int = boundPort

    /** True while the server is running. */
    public val isStarted: Boolean
        get() = started

    /** Opens an in-memory connection (used by `kalin-testtools`). */
    @KalinInternalApi
    public fun connectInMemory(): MemoryConnection {
        val e = engine ?: throw IllegalStateException("Server not started")
        return e.connectInMemory()
    }

    /** The JSON mapper of this app (available after start). */
    @KalinInternalApi
    public fun jsonMapper(): net.tegulis.kalin.json.JsonMapper =
        state?.jsonMapper ?: cfg.jsonMapper ?: net.tegulis.kalin.json.JsonMapper.default()

    /** Low-level access to the configuration after creation (for test tools and plugins). */
    @KalinInternalApi public fun unsafeConfig(): KalinConfig = cfg

    public companion object {
        private val log: System.Logger = System.getLogger("net.tegulis.kalin.Kalin")

        /** Creates an app with default configuration. */
        @JvmStatic public fun create(): Kalin = create {}

        /** Creates an app; routes and settings are configured in [config]. */
        @JvmStatic
        public fun create(config: Consumer<KalinConfig>): Kalin {
            val cfg = KalinConfig()
            config.accept(cfg)
            initializePlugins(cfg)
            return Kalin(cfg)
        }

        /** Creates and starts an app. */
        @JvmStatic public fun start(config: Consumer<KalinConfig>): Kalin = create(config).start()

        private fun initializePlugins(cfg: KalinConfig) {
            val initialized = HashSet<Any>()
            while (true) {
                val pending = cfg.plugins.filter { it !in initialized }.sortedBy { it.priority() }
                if (pending.isEmpty()) break
                for (plugin in pending) {
                    initialized.add(plugin)
                    plugin.onInitialize(cfg)
                }
            }
        }
    }
}
