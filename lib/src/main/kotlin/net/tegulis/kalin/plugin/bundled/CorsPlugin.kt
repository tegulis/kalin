package net.tegulis.kalin.plugin.bundled

import java.net.URI
import java.util.function.Consumer
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.plugin.Plugin

/** CORS configuration: one or more rules. */
public class CorsPluginConfig {
    internal val rules = ArrayList<CorsRule>()

    /** Adds a rule. */
    public fun addRule(rule: Consumer<CorsRule>): CorsPluginConfig = also {
        rules.add(CorsRule().also { rule.accept(it) })
    }

    /** One CORS rule. */
    public class CorsRule {
        /** Send `Access-Control-Allow-Credentials: true`. */
        @JvmField public var allowCredentials: Boolean = false

        /**
         * Echo the client's origin instead of a fixed list. Combined with [allowCredentials] this lets any website make
         * credentialed requests (with the user's cookies) and read the responses; a warning is logged at startup.
         */
        @JvmField public var reflectClientOrigin: Boolean = false

        /** The scheme assumed for hosts given without one. */
        @JvmField public var defaultScheme: String = "https"

        /** The path the rule applies to. */
        @JvmField public var path: String = "*"

        /** `Access-Control-Max-Age` in seconds for preflight responses; -1 omits it. */
        @JvmField public var maxAge: Int = -1

        private val allowedOrigins = ArrayList<String>()
        private val headersToExpose = ArrayList<String>()

        /** The allowed origins. */
        public fun allowedOrigins(): List<String> = allowedOrigins.toList()

        /** The exposed headers. */
        public fun headersToExpose(): List<String> = headersToExpose.toList()

        /** Allows any origin (`*`). */
        public fun anyHost() {
            allowedOrigins.add("*")
        }

        /** Allows [host] and [others]; a leading `*.` wildcard matches subdomains. */
        public fun allowHost(host: String, vararg others: String) {
            for (h in listOf(host) + others) {
                require(h != "null") {
                    "Adding the string null as an allowed host is forbidden. Consider calling anyHost() instead"
                }
                val withScheme = if (h.contains("://")) h else "$defaultScheme://$h"
                val hostPart = withScheme.substringAfter("://")
                require(hostPart.count { it == '*' } <= 1) {
                    "Too many wildcards detected inside '$h'. Only one at the start of the host is allowed!"
                }
                require(!hostPart.contains('*') || hostPart.startsWith("*.")) {
                    "The wildcard must be at the start of the passed in host. The value '$h' violates this requirement!"
                }
                allowedOrigins.add(withScheme.trimEnd('/'))
            }
        }

        /** Exposes a response header to scripts. */
        public fun exposeHeader(header: String) {
            headersToExpose.add(header)
        }
    }
}

/** Handles CORS preflight and response headers according to the configured rules. */
public class CorsPlugin(userConfig: Consumer<CorsPluginConfig>? = null) :
    Plugin<CorsPluginConfig>(userConfig, CorsPluginConfig()) {
    init {
        require(pluginConfig.rules.isNotEmpty()) {
            "At least one cors config has to be provided. Use CorsPluginConfig.addRule() to add one."
        }
    }

    override fun onInitialize(config: KalinConfig) {
        for (rule in pluginConfig.rules) {
            val origins = rule.allowedOrigins()
            require(origins.isNotEmpty() || rule.reflectClientOrigin) {
                "Origins cannot be empty if `reflectClientOrigin` is false."
            }
            require(origins.isEmpty() || !rule.reflectClientOrigin) {
                "Cannot set `allowedOrigins` if `reflectClientOrigin` is true"
            }
            require(!("*" in origins && rule.allowCredentials)) {
                "Cannot use `anyHost()` / Origin: * if `allowCredentials` is true as that is rejected by all browsers."
            }
            config.routes.before(rule.path) { ctx ->
                addVaryOrigin(ctx)
                handleCors(ctx, rule)
            }
            config.routes.after(rule.path) { ctx ->
                if (
                    ctx.method() == HandlerType.OPTIONS &&
                        ctx.status() in setOf(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED)
                ) {
                    ctx.result("").status(200)
                }
            }
        }
    }

    override fun onStart(config: KalinConfig) {
        for (rule in pluginConfig.rules) {
            if (rule.reflectClientOrigin && rule.allowCredentials) {
                log.log(
                    System.Logger.Level.WARNING,
                    "CORS rule for path '${rule.path}' reflects any client origin and allows credentials: " +
                        "any website can make credentialed requests on behalf of your users and read the responses. " +
                        "Use allowHost(...) to list trusted origins instead.",
                )
            }
        }
    }

    /** Responses differ by `Origin`, also those to requests without one, so caches must key on it. */
    private fun addVaryOrigin(ctx: Context) {
        val existing = ctx.responseHeaders(Header.VARY)
        if (existing.isEmpty()) {
            ctx.header(Header.VARY, Header.ORIGIN)
            return
        }
        val tokens = existing.flatMap { it.split(',') }.map { it.trim() }
        if (tokens.any { it == "*" || it.equals(Header.ORIGIN, ignoreCase = true) }) return
        ctx.header(Header.VARY, existing.joinToString(", ") + ", " + Header.ORIGIN)
    }

    private fun handleCors(ctx: Context, rule: CorsPluginConfig.CorsRule) {
        val clientOrigin = ctx.header(Header.ORIGIN) ?: return
        if (clientOrigin != "null" && !validOrigin(clientOrigin)) return
        if (ctx.method() == HandlerType.OPTIONS) {
            var requested = false
            ctx.header(Header.ACCESS_CONTROL_REQUEST_HEADERS)?.let {
                ctx.header(Header.ACCESS_CONTROL_ALLOW_HEADERS, it)
                requested = true
            }
            ctx.header(Header.ACCESS_CONTROL_REQUEST_METHOD)?.let {
                ctx.header(Header.ACCESS_CONTROL_ALLOW_METHODS, it)
                requested = true
            }
            if (requested && rule.maxAge >= 0) ctx.header(Header.ACCESS_CONTROL_MAX_AGE, rule.maxAge.toString())
        }
        val origins = rule.allowedOrigins()
        val allow =
            when {
                "*" in origins -> "*"
                clientOrigin == "null" -> return
                rule.reflectClientOrigin -> clientOrigin
                origins.any { originMatches(clientOrigin, it) } -> clientOrigin
                else -> {
                    ctx.status(HttpStatus.BAD_REQUEST)
                    return
                }
            }
        ctx.header(Header.ACCESS_CONTROL_ALLOW_ORIGIN, allow)
        if (rule.allowCredentials) ctx.header(Header.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
        if (rule.headersToExpose().isNotEmpty())
            ctx.header(Header.ACCESS_CONTROL_EXPOSE_HEADERS, rule.headersToExpose().joinToString(","))
    }

    private companion object {
        val log: System.Logger = System.getLogger("net.tegulis.kalin.CorsPlugin")
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun parse(origin: String): Origin? =
        try {
            val uri = URI(origin)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = (uri.host ?: uri.authority?.substringBefore(':'))?.lowercase() ?: return null
            val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
            Origin(scheme, host, port)
        } catch (_: Exception) {
            null
        }

    private fun validOrigin(origin: String): Boolean = parse(origin) != null

    private fun originMatches(client: String, server: String): Boolean {
        val c = parse(client) ?: return false
        if (server.contains("://*.")) {
            val scheme = server.substringBefore("://").lowercase()
            val rest = server.substringAfter("://*.")
            val s = parse("$scheme://$rest") ?: return false
            return c.scheme == s.scheme && c.port == s.port && c.host.endsWith("." + s.host)
        }
        val s = parse(server) ?: return false
        return c == s
    }
}
