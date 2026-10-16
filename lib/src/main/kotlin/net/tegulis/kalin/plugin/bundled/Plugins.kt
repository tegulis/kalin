package net.tegulis.kalin.plugin.bundled

import java.lang.System.Logger.Level
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.HttpResponseException
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.http.KalinContext
import net.tegulis.kalin.http.UnauthorizedResponse
import net.tegulis.kalin.json.SimpleJson
import net.tegulis.kalin.plugin.ContextPlugin
import net.tegulis.kalin.plugin.Plugin
import net.tegulis.kalin.plugin.PluginPriority
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.staticfiles.DirectoryListing

/** Serves an overview of all routes as HTML (or JSON when requested). */
public class RouteOverviewPlugin(userConfig: Consumer<Config>? = null) :
    Plugin<RouteOverviewPlugin.Config>(userConfig, Config()) {
    /** Configuration. */
    public class Config {
        /** The path of the overview page. */
        @JvmField public var path: String = "/routes"

        /** Roles required for the overview. */
        @JvmField public var roles: Array<out RouteRole> = emptyArray()
    }

    override fun onInitialize(config: KalinConfig) {
        config.routes.get(pluginConfig.path, { ctx -> handle(ctx) }, *pluginConfig.roles)
    }

    private fun handle(ctx: Context) {
        val endpoints =
            (ctx as KalinContext).state.router.allEndpoints().filter { it.method.isHttpMethod || it.method in PHASES }
        if (ctx.header(Header.ACCEPT)?.lowercase(Locale.ROOT)?.contains(ContentType.JSON) == true) {
            ctx.contentType(ContentType.JSON)
            ctx.result(
                SimpleJson.write(
                    mapOf(
                        "handlers" to
                            endpoints.map {
                                mapOf(
                                    "path" to it.path,
                                    "handlerType" to it.method.name,
                                    "metaInfo" to it.handler.javaClass.name,
                                    "roles" to it.roles.map(Any::toString),
                                )
                            }
                    )
                )
            )
        } else {
            ctx.html(
                buildString {
                    append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Routes</title><style>")
                    append(
                        "body{font-family:system-ui,sans-serif;margin:2rem}td,th{padding:.3rem .8rem;text-align:left}"
                    )
                    append("tr:nth-child(even){background:#f4f4f4}</style></head><body><h1>Routes</h1><table>")
                    append("<tr><th>Method</th><th>Path</th><th>Handler</th><th>Roles</th></tr>")
                    for (e in endpoints) {
                        append("<tr><td>")
                            .append(e.method.name)
                            .append("</td><td>")
                            .append(DirectoryListing.escape(e.path))
                        append("</td><td>")
                            .append(DirectoryListing.escape(e.handler.javaClass.name))
                            .append("</td><td>")
                        append(DirectoryListing.escape(e.roles.joinToString())).append("</td></tr>")
                    }
                    append("</table></body></html>")
                }
            )
        }
    }

    override fun priority(): PluginPriority = PluginPriority.LATE

    private companion object {
        val PHASES = setOf(HandlerType.BEFORE, HandlerType.BEFORE_MATCHED, HandlerType.AFTER_MATCHED, HandlerType.AFTER)
    }
}

/** Requires HTTP basic authentication for all requests. */
public class BasicAuthPlugin(userConfig: Consumer<Config>) : Plugin<BasicAuthPlugin.Config>(userConfig, Config()) {
    /** Configuration. */
    public class Config {
        /** The user name. */
        @JvmField public var username: String? = null

        /** The password. */
        @JvmField public var password: String? = null

        /** The realm sent in `WWW-Authenticate`. */
        @JvmField public var realm: String? = null
    }

    override fun onInitialize(config: KalinConfig) {
        config.routes.before { ctx ->
            val auth = ctx.basicAuthCredentials()
            val ok =
                auth != null &&
                    constantTimeEquals(auth.username, pluginConfig.username) &&
                    constantTimeEquals(auth.password, pluginConfig.password)
            if (!ok) {
                ctx.header(Header.WWW_AUTHENTICATE, pluginConfig.realm?.let { "Basic realm=\"$it\"" } ?: "Basic")
                throw UnauthorizedResponse()
            }
        }
    }

    private fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}

/** Security and other headers added to every response. */
public class GlobalHeadersConfig {
    /** The headers. */
    public val headers: MutableMap<String, String> = LinkedHashMap()

    /** `Strict-Transport-Security`. */
    public fun strictTransportSecurity(duration: Duration, includeSubdomains: Boolean) {
        headers[Header.STRICT_TRANSPORT_SECURITY] =
            "max-age=" + duration.seconds + if (includeSubdomains) "; includeSubDomains" else ""
    }

    /** `X-Frame-Options` values. */
    public enum class XFrameOptions {
        DENY,
        SAMEORIGIN,
    }

    /** `X-Frame-Options`. */
    public fun xFrameOptions(xFrameOptions: XFrameOptions) {
        headers[Header.X_FRAME_OPTIONS] = xFrameOptions.name
    }

    /** `X-Content-Type-Options: nosniff`. */
    public fun xContentTypeOptionsNoSniff() {
        headers[Header.X_CONTENT_TYPE_OPTIONS] = "nosniff"
    }

    /** `Content-Security-Policy`. */
    public fun contentSecurityPolicy(contentSecurityPolicy: String) {
        headers[Header.CONTENT_SECURITY_POLICY] = contentSecurityPolicy
    }

    /** `Referrer-Policy` values. */
    public enum class ReferrerPolicy {
        NO_REFERRER,
        NO_REFERRER_WHEN_DOWNGRADE,
        ORIGIN,
        ORIGIN_WHEN_CROSS_ORIGIN,
        SAME_ORIGIN,
        STRICT_ORIGIN,
        STRICT_ORIGIN_WHEN_CROSS_ORIGIN,
        UNSAFE_URL,
    }

    /** `Referrer-Policy`. */
    public fun referrerPolicy(policy: ReferrerPolicy) {
        headers[Header.REFERRER_POLICY] = policy.name.lowercase().replace('_', '-')
    }

    /** `Cross-Origin-Opener-Policy`. */
    public fun crossOriginOpenerPolicy(value: String) {
        headers[Header.CROSS_ORIGIN_OPENER_POLICY] = value
    }

    /** `Cross-Origin-Resource-Policy`. */
    public fun crossOriginResourcePolicy(value: String) {
        headers[Header.CROSS_ORIGIN_RESOURCE_POLICY] = value
    }

    /** `Cross-Origin-Embedder-Policy`. */
    public fun crossOriginEmbedderPolicy(value: String) {
        headers[Header.CROSS_ORIGIN_EMBEDDER_POLICY] = value
    }
}

/** Adds the configured headers to every response. */
public class GlobalHeadersPlugin(userConfig: Consumer<GlobalHeadersConfig>? = null) :
    Plugin<GlobalHeadersConfig>(userConfig, GlobalHeadersConfig()) {
    override fun onInitialize(config: KalinConfig) {
        config.routes.before { ctx -> pluginConfig.headers.forEach { (name, value) -> ctx.header(name, value) } }
    }
}

/** Logs every request and response in detail. For development only. */
public class DevLoggingPlugin(userConfig: Consumer<Config>? = null) :
    Plugin<DevLoggingPlugin.Config>(userConfig, Config()) {
    /** Configuration. */
    public class Config {
        /** Skip requests that look like static files. */
        @JvmField public var skipStaticFiles: Boolean = false
    }

    private val log = System.getLogger("net.tegulis.kalin.DevLogging")

    override fun onInitialize(config: KalinConfig) {
        config.requestLogger.http { ctx, ms -> logRequest(ctx, ms) }
        config.events.handlerAdded { info ->
            log.log(Level.INFO, "KALIN HANDLER REGISTRATION: ${info.httpHandlerType}[${info.path}]")
        }
    }

    private fun logRequest(ctx: Context, ms: Float) {
        if (
            pluginConfig.skipStaticFiles &&
                ctx.endpoints().list().none { it.method.isHttpMethod } &&
                ctx.path().contains('.')
        )
            return
        try {
            val body = if (ctx.isMultipart()) "Multipart data ..." else ctx.body()
            log.log(
                Level.INFO,
                """
                |KALIN REQUEST DEBUG LOG:
                |Request: ${ctx.method()} [${ctx.path()}]
                |    Matching endpoints: ${ctx.endpoints().list().map { "${it.method}=${it.path}" }}
                |    Headers: ${ctx.headerMap()}
                |    Cookies: ${ctx.cookieMap()}
                |    Body: $body
                |    QueryString: ${ctx.queryString()}
                |    QueryParams: ${ctx.queryParamMap()}
                |    FormParams: ${if (ctx.isFormUrlencoded()) ctx.formParamMap() else emptyMap<String, Any>()}
                |Response: [${ctx.status()}], execution took ${String.format(Locale.US, "%.2f", ms)} ms
                |    Headers: ${(ctx as KalinContext).exchange().responseHeaders}
                |    Body: ${ctx.result()?.let { if (it.length > 2000) it.take(2000) + "..." else it } ?: "(stream or none)"}
                |----------------------------------------------------------------------------------
                """
                    .trimMargin(),
            )
        } catch (e: Exception) {
            log.log(Level.INFO, "An exception occurred while logging debug-info", e)
        }
    }
}

/** Redirects requests with uppercase characters to the lowercase path if only that one is routed. */
public class RedirectToLowercasePathPlugin : Plugin<Unit>() {
    override fun onInitialize(config: KalinConfig) {
        check(!config.router.caseInsensitiveRoutes) {
            "RedirectToLowercasePathPlugin is not compatible with caseInsensitiveRoutes"
        }
        config.routes.before { ctx ->
            val k = ctx as KalinContext
            val uri = k.requestUri()
            val router = k.state.router
            if (router.findFirst(ctx.method(), uri) != null) return@before
            val lower = uri.lowercase(Locale.ROOT)
            if (lower == uri) return@before
            val target = router.findFirst(ctx.method(), lower) ?: return@before
            // Keep path parameter values as sent; lowercase only the static parts by matching segment-wise
            val clientSegments = uri.split('/')
            val serverSegments = target.endpoint.path.split('/')
            val fixed = clientSegments.mapIndexed { i, seg ->
                val server = serverSegments.getOrNull(i)
                if (server != null && !server.contains('{') && !server.contains('<') && !server.contains('*'))
                    seg.lowercase(Locale.ROOT)
                else seg
            }
            ctx.redirect(
                k.contextPath().trimEnd('/') + fixed.joinToString("/") + (ctx.queryString()?.let { "?$it" } ?: ""),
                HttpStatus.MOVED_PERMANENTLY,
            )
        }
    }

    override fun priority(): PluginPriority = PluginPriority.EARLY
}

/** Answers `OPTIONS` requests on every routed path with the allowed methods. */
public class HttpAllowedMethodsPlugin : Plugin<Unit>() {
    override fun onStart(config: KalinConfig) {
        val byPath = LinkedHashMap<String, MutableSet<HandlerType>>()
        for (e in config.routes.endpoints) if (e.method.isHttpMethod)
            byPath.getOrPut(e.path) { LinkedHashSet() }.add(e.method)
        for ((path, methods) in byPath) {
            if (HandlerType.OPTIONS in methods) continue
            val allowed = (methods + HandlerType.OPTIONS).joinToString(",")
            config.routes.options(path) { ctx ->
                ctx.header(Header.ACCESS_CONTROL_ALLOW_METHODS, allowed).header(Header.ALLOW, allowed)
            }
        }
    }
}

/** A simple in-memory rate limiter, used from handlers with `ctx.with(RateLimitPlugin::class)`. */
public class RateLimitPlugin(userConfig: Consumer<Config>? = null) :
    ContextPlugin<RateLimitPlugin.Config, RateLimitPlugin.Extension>(userConfig, Config()) {
    /** Configuration. */
    public class Config {
        /** The key a limit is counted by (default: ip + method + matched path). */
        public var keyFunction: (Context) -> String = { ctx ->
            val path = ctx.endpoints().matchedHttpEndpoint()?.path ?: ctx.endpoint().path
            ctx.ip() + ctx.method() + path
        }
    }

    private val limiters = ConcurrentHashMap<TimeUnit, RateLimiter>()

    override fun createExtension(context: Context): Extension = Extension(context)

    /** The per-request API. */
    public inner class Extension internal constructor(private val context: Context) {
        /** Counts this request and throws `429` if more than [numRequests] were made in the current [timeUnit]. */
        public fun requestPerTimeUnit(numRequests: Int, timeUnit: TimeUnit) {
            limiters
                .computeIfAbsent(timeUnit) { RateLimiter(it, pluginConfig.keyFunction) }
                .increment(context, numRequests)
        }

        /** The current count for this request's key. */
        public fun getCurrentCount(timeUnit: TimeUnit): Int = limiters[timeUnit]?.count(context) ?: 0
    }

    private class RateLimiter(timeUnit: TimeUnit, private val keyFunction: (Context) -> String) {
        private val unitName = timeUnit.toString().lowercase(Locale.ROOT).removeSuffix("s")
        private val counts = ConcurrentHashMap<String, Int>()

        init {
            scheduler.scheduleAtFixedRate({ counts.clear() }, 1, 1, timeUnit)
        }

        fun increment(ctx: Context, limit: Int) {
            counts.compute(keyFunction(ctx)) { _, count ->
                when {
                    count == null -> 1
                    count < limit -> count + 1
                    else ->
                        throw HttpResponseException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "Rate limit exceeded - Server allows $limit requests per $unitName.",
                        )
                }
            }
        }

        fun count(ctx: Context): Int = counts[keyFunction(ctx)] ?: 0
    }

    private companion object {
        val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread.ofPlatform().daemon().name("kalin-rate-limit").unstarted(r)
            }
        }
    }
}
