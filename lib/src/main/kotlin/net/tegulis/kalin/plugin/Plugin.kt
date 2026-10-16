package net.tegulis.kalin.plugin

import java.util.function.Consumer
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.http.Context

/** Plugin initialization order. */
public enum class PluginPriority {
    EARLY,
    NORMAL,
    LATE,
}

/**
 * Base class of plugins. Register with `config.registerPlugin(plugin)`. A plugin may add routes, handlers and settings
 * in [onInitialize] (called during `Kalin.create`) and run code when the server starts in [onStart].
 */
public abstract class Plugin<CONFIG>
@JvmOverloads
constructor(userConfig: Consumer<CONFIG>? = null, defaultConfig: CONFIG? = null) {
    /** Called once when the app is created, in priority order. */
    public open fun onInitialize(config: KalinConfig) {}

    /** Called when the server starts. */
    public open fun onStart(config: KalinConfig) {}

    /** True if the plugin may be registered more than once. */
    public open fun repeatable(): Boolean = false

    /** The initialization priority. */
    public open fun priority(): PluginPriority = PluginPriority.NORMAL

    /** The plugin name. */
    public open fun name(): String = javaClass.simpleName

    /** The plugin configuration (default config with the user config applied). */
    @Suppress("UNCHECKED_CAST")
    @JvmField
    protected var pluginConfig: CONFIG = defaultConfig?.also { userConfig?.accept(it) } as CONFIG
}

/** A plugin that adds an extension available in handlers through `ctx.with(MyPlugin::class)`. */
public abstract class ContextPlugin<CONFIG, EXTENSION>
@JvmOverloads
constructor(userConfig: Consumer<CONFIG>? = null, defaultConfig: CONFIG? = null) :
    Plugin<CONFIG>(userConfig, defaultConfig) {
    final override fun repeatable(): Boolean = false

    /** Creates the extension for one request. */
    public abstract fun createExtension(context: Context): EXTENSION
}

/** Thrown for plugin misconfiguration. */
public class PluginException(pluginClass: Class<*>, message: String) :
    net.tegulis.kalin.http.KalinException("Error in ${pluginClass.simpleName}: $message")

/** Thrown when a non-repeatable plugin is registered twice. */
public class PluginAlreadyRegisteredException(plugin: Plugin<*>) :
    net.tegulis.kalin.http.KalinException("${plugin.name()} is already registered")

/** Thrown by `ctx.with(...)` when the plugin is not registered. */
public class PluginNotRegisteredException(clazz: Class<*>) :
    net.tegulis.kalin.http.KalinException("${clazz.simpleName} is not registered as a plugin")
