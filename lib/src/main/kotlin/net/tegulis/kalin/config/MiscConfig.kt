package net.tegulis.kalin.config

import java.net.InetAddress
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
     * Trusts the `Forwarded` (RFC 7239) and `X-Forwarded-For` / `-Proto` / `-Host` headers set by reverse proxies. Only
     * enable this when every request passes through a proxy that sets or appends these headers; otherwise any client
     * can claim any IP, scheme or host.
     *
     * The client IP is picked from the forwarding chain: the `for=` entries of `Forwarded`, else `X-Forwarded-For`,
     * else `X-Real-IP` (see `Context.forwardedFor`). With [ForwardedIp.RIGHTMOST] (the default) the chain is walked
     * from the right, skipping [trustedProxies]; the first untrusted entry is the client (the leftmost entry if all are
     * trusted). Proxies append to the chain, so entries left of your own proxies may be forged by the client, while the
     * entry added by the proxy closest to the server cannot be. With [ForwardedIp.LEFTMOST] the first entry is used,
     * like Javalin; only do that if your outermost proxy replaces the header instead of appending to it.
     *
     * Scheme and host come from the same hop: the `proto=` / `host=` of the selected `Forwarded` element, or the
     * `X-Forwarded-Proto` / `X-Forwarded-Host` value at the same position as the selected `X-Forwarded-For` entry. When
     * those headers have a different number of values than the chain, their rightmost value is used with
     * [ForwardedIp.RIGHTMOST] and their leftmost with [ForwardedIp.LEFTMOST].
     *
     * @param mode which entry of the chain is the client.
     * @param trustedProxies IP addresses or CIDR ranges (IPv4 or IPv6, for example `10.0.0.0/8` or `fd00::/8`) of your
     *   own proxies. Host names are rejected; no DNS lookups are made.
     * @throws IllegalArgumentException if an entry of [trustedProxies] is not an IP address or CIDR range.
     */
    @JvmOverloads
    public fun useForwardedHeaders(
        mode: ForwardedIp = ForwardedIp.RIGHTMOST,
        trustedProxies: List<String> = emptyList(),
    ): ContextResolverConfig = apply {
        val trusted = trustedProxies.map { IpRange.parse(it) }
        val forwarded = ForwardedHeaders(mode, trusted)
        ip = { ctx -> forwarded.clientIp(ctx) ?: ctx.remoteIp() }
        host = { ctx -> forwarded.host(ctx) ?: ctx.header(Header.HOST) }
        scheme = { ctx -> forwarded.proto(ctx) ?: "http" }
    }
}

/** Which entry of the forwarding chain [ContextResolverConfig.useForwardedHeaders] takes as the client IP. */
public enum class ForwardedIp {
    /** The rightmost entry that is not a trusted proxy. Safe behind proxies that append to the chain. */
    RIGHTMOST,

    /** The leftmost entry, as Javalin does. Spoofable unless the outermost proxy overwrites the header. */
    LEFTMOST,
}

/** An IP address or CIDR range, parsed without DNS lookups. */
internal class IpRange private constructor(private val network: ByteArray, private val prefix: Int) {
    fun contains(address: String): Boolean {
        val bytes = literalBytes(address) ?: return false
        if (bytes.size != network.size) return false
        var bits = prefix
        for (i in bytes.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("""[0-9a-fA-F:.]*:[0-9a-fA-F:.]*""")

        /** The bytes of an IPv4 or IPv6 literal (IPv4-mapped IPv6 becomes IPv4), or null for anything else. */
        fun literalBytes(address: String): ByteArray? {
            val a = address.trim().removePrefix("[").removeSuffix("]")
            if (!IPV4.matches(a) && !IPV6.matches(a)) return null
            if (IPV4.matches(a) && a.split('.').any { it.toInt() > 255 }) return null
            // Only literals reach InetAddress, so it never resolves names
            return try {
                InetAddress.getByName(a).address
            } catch (_: Exception) {
                null
            }
        }

        fun parse(value: String): IpRange {
            val address = value.substringBefore('/').trim()
            val bytes =
                literalBytes(address)
                    ?: throw IllegalArgumentException("Trusted proxy '$value' is not an IP address or CIDR range")
            val maxPrefix = bytes.size * 8
            val prefix =
                if (!value.contains('/')) maxPrefix
                else
                    value.substringAfter('/').trim().toIntOrNull()?.takeIf { it in 0..maxPrefix }
                        ?: throw IllegalArgumentException("Invalid prefix length in trusted proxy '$value'")
            return IpRange(bytes, prefix)
        }
    }
}

/** Parsing of proxy headers. */
internal class ForwardedHeaders(private val mode: ForwardedIp, private val trusted: List<IpRange>) {
    private class Hop(val forValue: String, val proto: String?, val host: String?)

    /** The index of the client entry in [chain], or -1 if the chain is empty. */
    private fun select(chain: List<String>): Int {
        if (chain.isEmpty()) return -1
        if (mode == ForwardedIp.LEFTMOST) return 0
        for (i in chain.indices.reversed()) if (trusted.none { it.contains(chain[i]) }) return i
        return 0
    }

    /** Picks the value of a per-hop header for the hop at [index] of a chain of [chainSize] entries. */
    private fun pick(values: List<String>, chainSize: Int, index: Int): String? =
        when {
            values.isEmpty() -> null
            values.size == chainSize && index >= 0 -> values[index]
            mode == ForwardedIp.RIGHTMOST -> values.last()
            else -> values.first()
        }

    private fun forwardedHop(ctx: Context): Hop? {
        val hops = forwardedHops(ctx)
        return hops.getOrNull(select(hops.map { it.forValue }))
    }

    fun clientIp(ctx: Context): String? {
        forwardedHop(ctx)?.let {
            return it.forValue
        }
        val chain = forwardedFor(ctx)
        return chain.getOrNull(select(chain))
    }

    fun proto(ctx: Context): String? =
        forwardedHop(ctx)?.proto?.lowercase()
            ?: headerValues(ctx, Header.X_FORWARDED_PROTO).let { values ->
                val chain = headerValues(ctx, Header.X_FORWARDED_FOR)
                pick(values, chain.size, select(chain))?.lowercase()
            }

    fun host(ctx: Context): String? =
        forwardedHop(ctx)?.host
            ?: headerValues(ctx, Header.X_FORWARDED_HOST).let { values ->
                val chain = headerValues(ctx, Header.X_FORWARDED_FOR)
                pick(values, chain.size, select(chain))
            }

    companion object {
        /** All comma-separated values of all [name] header lines, trimmed, without empty ones. */
        private fun headerValues(ctx: Context, name: String): List<String> =
            ctx.exchange()
                .requestHeaders
                .getAll(name)
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /** Strips quotes, brackets and ports: `"[2001:db8::1]:4711"` → `2001:db8::1`, `1.2.3.4:80` → `1.2.3.4`. */
        private fun node(value: String): String {
            val v = value.trim().removeSurrounding("\"")
            return when {
                v.startsWith("[") -> v.substringAfter('[').substringBefore(']')
                v.count { it == ':' } == 1 -> v.substringBefore(':')
                else -> v
            }
        }

        /** Splits on [separator] outside double quotes. */
        private fun splitUnquoted(value: String, separator: Char): List<String> {
            val result = ArrayList<String>()
            var quoted = false
            var start = 0
            for (i in value.indices) {
                val c = value[i]
                if (c == '"') quoted = !quoted
                else if (c == separator && !quoted) {
                    result.add(value.substring(start, i))
                    start = i + 1
                }
            }
            result.add(value.substring(start))
            return result
        }

        private fun forwardedHops(ctx: Context): List<Hop> {
            val lines = ctx.exchange().requestHeaders.getAll(Header.FORWARDED)
            if (lines.isEmpty()) return emptyList()
            val hops = ArrayList<Hop>()
            for (element in lines.flatMap { splitUnquoted(it, ',') }) {
                var forValue: String? = null
                var proto: String? = null
                var host: String? = null
                for (pair in splitUnquoted(element, ';')) {
                    val value = pair.substringAfter('=', "").trim().removeSurrounding("\"").ifEmpty { null }
                    when (pair.substringBefore('=').trim().lowercase()) {
                        "for" -> forValue = value?.let { node(it) }
                        "proto" -> proto = value
                        "host" -> host = value
                    }
                }
                if (forValue != null) hops.add(Hop(forValue, proto, host))
            }
            return hops
        }

        /** See `Context.forwardedFor`. */
        fun forwardedFor(ctx: Context): List<String> {
            val forwarded = forwardedHops(ctx)
            if (forwarded.isNotEmpty()) return forwarded.map { it.forValue }
            val xff = headerValues(ctx, Header.X_FORWARDED_FOR).map { node(it) }.filter { it.isNotEmpty() }
            if (xff.isNotEmpty()) return xff
            return ctx.header(Header.X_REAL_IP)?.trim()?.ifEmpty { null }?.let { listOf(node(it)) } ?: emptyList()
        }
    }
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
