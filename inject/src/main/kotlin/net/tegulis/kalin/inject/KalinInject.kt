package net.tegulis.kalin.inject

import me.tatarka.inject.annotations.Scope
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.config.RoutesConfig
import net.tegulis.kalin.json.JsonMapper
import net.tegulis.kalin.plugin.Plugin
import net.tegulis.kalin.rendering.FileRenderer

/** The scope of app-wide singletons (repositories, services, controllers, clients, configuration). */
@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
public annotation class AppScope

/** A group of routes contributed to the app, typically bound with `@IntoSet`. */
public fun interface KalinRoutes {
    /** Registers the routes. */
    public fun register(routes: RoutesConfig)
}

/**
 * The contract of a kotlin-inject component that wires a Kalin app. Implement it with an abstract `@Component` class
 * and install it with [install].
 *
 * ```
 * @AppScope @Component
 * abstract class AppComponent(@get:Provides val db: DataSource) : KalinComponent {
 *     @Provides @IntoSet protected fun users(r: UserRoutes): KalinRoutes = r
 * }
 *
 * Kalin.create { config -> config.install(AppComponent::class.create(dataSource())) }
 * ```
 */
public interface KalinComponent {
    /** All route groups (bind with `@IntoSet`). */
    public val routes: Set<KalinRoutes>

    /** Plugins to register. */
    public val plugins: Set<Plugin<*>>
        get() = emptySet()

    /** The JSON mapper, or null for the default. */
    public val jsonMapper: JsonMapper?
        get() = null

    /** The template renderer, or null for none. */
    public val fileRenderer: FileRenderer?
        get() = null
}

/** Registers the routes, plugins and mappers of [component]. The object graph is built once, at startup. */
public fun KalinConfig.install(component: KalinComponent) {
    component.jsonMapper?.let { jsonMapper(it) }
    component.fileRenderer?.let { fileRenderer(it) }
    component.plugins.forEach { registerPlugin(it) }
    component.routes.forEach { it.register(routes) }
}
