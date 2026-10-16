package net.tegulis.kalin.config

import net.tegulis.kalin.json.JsonMapper
import net.tegulis.kalin.plugin.Plugin
import net.tegulis.kalin.plugin.PluginAlreadyRegisteredException
import net.tegulis.kalin.rendering.FileRenderer
import net.tegulis.kalin.staticfiles.ResourceHandler
import net.tegulis.kalin.validation.ValidationConfig

/** The configuration passed to `Kalin.create { config -> }`. */
public class KalinConfig internal constructor() {
    /** HTTP settings: ETags, request size, compression, ... */
    @JvmField public val http: HttpConfig = HttpConfig()

    /** Routing settings: context path, slash handling, case sensitivity. */
    @JvmField public val router: RouterConfig = RouterConfig()

    /** Routes, filters, SSE endpoints, exception and error mappers. */
    @JvmField public val routes: RoutesConfig = RoutesConfig(this)

    /** The HTTP server: host, port, engine, transport, timeouts, limits. */
    @JvmField public val server: ServerConfig = ServerConfig()

    /** Static file locations. */
    @JvmField public val staticFiles: StaticFilesConfig = StaticFilesConfig()

    /** Single page application roots. */
    @JvmField public val spaRoot: SpaRootConfig = SpaRootConfig()

    /** Request loggers. */
    @JvmField public val requestLogger: RequestLoggerConfig = RequestLoggerConfig()

    /** Bundled plugins. */
    @JvmField public val bundledPlugins: BundledPluginsConfig = BundledPluginsConfig(this)

    /** Lifecycle events. */
    @JvmField public val events: EventConfig = EventConfig()

    /** Resolvers for ip, host, scheme and URLs (proxy support). */
    @JvmField public val contextResolver: ContextResolverConfig = ContextResolverConfig()

    /** Validation converters. */
    @JvmField public val validation: ValidationConfig = ValidationConfig()

    /** Startup settings. */
    @JvmField public val startup: StartupConfig = StartupConfig()

    /** Server-sent events settings. */
    @JvmField public val sse: SseConfig = SseConfig()

    /** Concurrency settings (Javalin compatibility; handlers always run on virtual threads). */
    @JvmField public val concurrency: ConcurrencyConfig = ConcurrencyConfig()

    internal var jsonMapper: JsonMapper? = null
    internal var fileRenderer: FileRenderer? = null
    internal var resourceHandler: ResourceHandler? = null
    internal val plugins = ArrayList<Plugin<*>>()
    internal val appData = HashMap<Key<*>, Any?>()

    /** Sets the JSON mapper. */
    public fun jsonMapper(jsonMapper: JsonMapper) {
        this.jsonMapper = jsonMapper
    }

    /** Sets the template renderer used by `ctx.render`. */
    public fun fileRenderer(fileRenderer: FileRenderer) {
        this.fileRenderer = fileRenderer
    }

    /** Sets a custom static resource handler. */
    public fun resourceHandler(resourceHandler: ResourceHandler) {
        this.resourceHandler = resourceHandler
    }

    /** Registers a plugin. */
    public fun registerPlugin(plugin: Plugin<*>): Plugin<*> {
        if (!plugin.repeatable() && plugins.any { it.javaClass == plugin.javaClass })
            throw PluginAlreadyRegisteredException(plugin)
        plugins.add(plugin)
        return plugin
    }

    /** Stores app data available through `ctx.appData(key)`. */
    public fun <T> appData(key: Key<T>, value: T) {
        require(key !in appData) { "Key '${key.id}' already exists" }
        appData[key] = value
    }
}
