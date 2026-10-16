package net.tegulis.kalin.config

import java.util.function.Consumer
import kotlin.time.Duration
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.RequestLogger
import net.tegulis.kalin.router.HandlerMetaInfo

/** Request logging configuration. */
public class RequestLoggerConfig {
    internal val httpLoggers = ArrayList<RequestLogger>()

    /** Adds a logger that runs after each response has been written. */
    public fun http(logger: RequestLogger) {
        httpLoggers.add(logger)
    }
}

/** A callback without arguments, for lifecycle events. */
public fun interface LifecycleEventListener {
    /** Called when the event happens. */
    public fun handleEvent()
}

/** Server lifecycle and handler events. */
public class EventConfig {
    internal val listeners = HashMap<LifecycleEvent, MutableList<LifecycleEventListener>>()
    internal val handlerAddedListeners = ArrayList<Consumer<HandlerMetaInfo>>()

    private fun on(event: LifecycleEvent, listener: LifecycleEventListener) {
        listeners.getOrPut(event) { ArrayList() }.add(listener)
    }

    /** Before the server starts. */
    public fun serverStarting(listener: LifecycleEventListener): Unit = on(LifecycleEvent.SERVER_STARTING, listener)

    /** After the server started. */
    public fun serverStarted(listener: LifecycleEventListener): Unit = on(LifecycleEvent.SERVER_STARTED, listener)

    /** When the server failed to start. */
    public fun serverStartFailed(listener: LifecycleEventListener): Unit =
        on(LifecycleEvent.SERVER_START_FAILED, listener)

    /** Before the server stops. */
    public fun serverStopping(listener: LifecycleEventListener): Unit = on(LifecycleEvent.SERVER_STOPPING, listener)

    /** After the server stopped. */
    public fun serverStopped(listener: LifecycleEventListener): Unit = on(LifecycleEvent.SERVER_STOPPED, listener)

    /** When stopping failed. */
    public fun serverStopFailed(listener: LifecycleEventListener): Unit =
        on(LifecycleEvent.SERVER_STOP_FAILED, listener)

    /** When a handler is added. */
    public fun handlerAdded(listener: Consumer<HandlerMetaInfo>) {
        handlerAddedListeners.add(listener)
    }

    internal fun fire(event: LifecycleEvent) {
        listeners[event]?.forEach { it.handleEvent() }
    }
}

/** Lifecycle event types. */
public enum class LifecycleEvent {
    SERVER_STARTING,
    SERVER_STARTED,
    SERVER_START_FAILED,
    SERVER_STOPPING,
    SERVER_STOPPED,
    SERVER_STOP_FAILED,
}

/**
 * Resolvers for request properties that depend on the deployment (for example behind a reverse proxy). Call
 * [useForwardedHeaders] to trust `Forwarded` / `X-Forwarded-*` headers.
 */
public class ContextResolverConfig {
    /** Resolves `ctx.ip()`. */
    @JvmField public var ip: (Context) -> String = { it.remoteIp() }

    /** Resolves `ctx.host()`. */
    @JvmField public var host: (Context) -> String? = { it.header(Header.HOST) }

    /** Resolves `ctx.scheme()`. */
    @JvmField public var scheme: (Context) -> String = { "http" }

    /** Resolves `ctx.url()`. */
    @JvmField
    public var url: (Context) -> String = { ctx -> "${ctx.scheme()}://${ctx.host() ?: "localhost"}${ctx.path()}" }

    /** Resolves `ctx.fullUrl()`. */
    @JvmField public var fullUrl: (Context) -> String = { ctx -> ctx.url() + (ctx.queryString()?.let { "?$it" } ?: "") }

    /**
     * Trusts the `Forwarded` (RFC 7239) and `X-Forwarded-For` / `-Proto` / `-Host` headers set by a reverse proxy. Only
     * enable this when every request passes through a proxy that sets or strips these headers.
     */
    public fun useForwardedHeaders(): ContextResolverConfig = apply {
        ip = { ctx -> ForwardedHeaders.clientIp(ctx) ?: ctx.remoteIp() }
        host = { ctx -> ForwardedHeaders.host(ctx) ?: ctx.header(Header.HOST) }
        scheme = { ctx -> ForwardedHeaders.proto(ctx) ?: "http" }
    }
}

/** Parsing of proxy headers. */
internal object ForwardedHeaders {
    private fun forwardedParam(ctx: Context, name: String): String? {
        val forwarded = ctx.header(Header.FORWARDED) ?: return null
        val first = forwarded.substringBefore(',')
        for (pair in first.split(';')) {
            val k = pair.substringBefore('=').trim()
            if (k.equals(name, ignoreCase = true)) {
                return pair.substringAfter('=', "").trim().removeSurrounding("\"").ifEmpty { null }
            }
        }
        return null
    }

    fun clientIp(ctx: Context): String? =
        forwardedParam(ctx, "for")?.let { v ->
            // [IPv6]:port, IPv4:port
            if (v.startsWith("[")) v.substringAfter('[').substringBefore(']') else v.substringBefore(':')
        }
            ?: ctx.header(Header.X_FORWARDED_FOR)?.substringBefore(',')?.trim()?.ifEmpty { null }
            ?: ctx.header(Header.X_REAL_IP)?.trim()?.ifEmpty { null }

    fun proto(ctx: Context): String? =
        forwardedParam(ctx, "proto")?.lowercase()
            ?: ctx.header(Header.X_FORWARDED_PROTO)?.substringBefore(',')?.trim()?.lowercase()?.ifEmpty { null }

    fun host(ctx: Context): String? =
        forwardedParam(ctx, "host")
            ?: ctx.header(Header.X_FORWARDED_HOST)?.substringBefore(',')?.trim()?.ifEmpty { null }
}

/** Startup configuration. */
public class StartupConfig {
    /** Log a startup line with the address and startup time. */
    @JvmField public var showBanner: Boolean = true
}

/** Concurrency configuration (Javalin compatibility). */
public class ConcurrencyConfig {
    /** Always effectively true: Kalin runs handlers on virtual threads. */
    @JvmField public var useVirtualThreads: Boolean = true
}

/** Server-sent events configuration. */
public class SseConfig {
    /** If set, a comment line is sent at this interval on idle SSE streams to keep proxies from closing them. */
    public var heartbeat: Duration? = null
}
