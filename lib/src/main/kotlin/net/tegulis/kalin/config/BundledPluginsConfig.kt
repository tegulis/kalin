package net.tegulis.kalin.config

import java.util.function.Consumer
import net.tegulis.kalin.plugin.Plugin
import net.tegulis.kalin.plugin.bundled.BasicAuthPlugin
import net.tegulis.kalin.plugin.bundled.CorsPlugin
import net.tegulis.kalin.plugin.bundled.CorsPluginConfig
import net.tegulis.kalin.plugin.bundled.DevLoggingPlugin
import net.tegulis.kalin.plugin.bundled.GlobalHeadersConfig
import net.tegulis.kalin.plugin.bundled.GlobalHeadersPlugin
import net.tegulis.kalin.plugin.bundled.HttpAllowedMethodsPlugin
import net.tegulis.kalin.plugin.bundled.RedirectToLowercasePathPlugin
import net.tegulis.kalin.plugin.bundled.RouteOverviewPlugin
import net.tegulis.kalin.security.RouteRole

/** Shortcuts for the bundled plugins. */
public class BundledPluginsConfig internal constructor(private val config: KalinConfig) {
    /** Serves a route overview at [path]. */
    public fun enableRouteOverview(path: String, vararg roles: RouteRole): Plugin<*> =
        config.registerPlugin(
            RouteOverviewPlugin {
                it.path = path
                it.roles = roles
            }
        )

    /** Requires basic authentication for all requests. */
    public fun enableBasicAuth(username: String, password: String): Plugin<*> =
        config.registerPlugin(
            BasicAuthPlugin {
                it.username = username
                it.password = password
            }
        )

    /** Adds headers to all responses. */
    public fun enableGlobalHeaders(globalHeadersConfig: Consumer<GlobalHeadersConfig>): Plugin<*> =
        config.registerPlugin(GlobalHeadersPlugin(globalHeadersConfig))

    /** Enables CORS. */
    public fun enableCors(userConfig: Consumer<CorsPluginConfig>): Plugin<*> =
        config.registerPlugin(CorsPlugin(userConfig))

    /** Answers OPTIONS requests on routed paths with the allowed methods. */
    public fun enableHttpAllowedMethodsOnRoutes(): Plugin<*> = config.registerPlugin(HttpAllowedMethodsPlugin())

    /** Logs requests in detail (development only). */
    public fun enableDevLogging(): Plugin<*> = config.registerPlugin(DevLoggingPlugin())

    /** Logs requests in detail (development only). */
    public fun enableDevLogging(userConfig: Consumer<DevLoggingPlugin.Config>): Plugin<*> =
        config.registerPlugin(DevLoggingPlugin(userConfig))

    /** Redirects uppercase paths to lowercase routes. */
    public fun enableRedirectToLowercasePaths(): Plugin<*> = config.registerPlugin(RedirectToLowercasePathPlugin())
}
