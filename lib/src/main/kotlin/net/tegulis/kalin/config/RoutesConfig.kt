package net.tegulis.kalin.config

import java.util.function.Consumer
import net.tegulis.kalin.apibuilder.ApiBuilder
import net.tegulis.kalin.apibuilder.EndpointGroup
import net.tegulis.kalin.http.ExceptionHandler
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.router.Endpoint
import net.tegulis.kalin.router.Roles
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.sse.SseClient
import net.tegulis.kalin.sse.SseHandler

/**
 * Routes, filters, exception and error mappers. Everything must be added inside `Kalin.create { config -> }`; routes
 * cannot be added after the app was created.
 */
public class RoutesConfig internal constructor(private val config: KalinConfig) {
    internal val endpoints = ArrayList<Endpoint>()
    internal val exceptionHandlers = LinkedHashMap<Class<out Exception>, ExceptionHandler<Exception>>()
    internal val errorHandlers = ArrayList<Triple<Int, String, Handler>>()
    internal var frozen = false

    private fun checkOpen() {
        check(!frozen) { "Routes must be added in config.routes inside Kalin.create { }; the app is already created." }
    }

    private fun normalize(path: String): String = if (path == "*" || path.startsWith("/")) path else "/$path"

    /** Adds an endpoint. */
    public fun addEndpoint(endpoint: Endpoint): RoutesConfig = apply {
        checkOpen()
        val scoped = ApiBuilder.scope(this, endpoint)
        endpoints.add(Endpoint(scoped.method, normalize(scoped.path), scoped.handler, scoped.metadata().toSet()))
    }

    /** Adds a handler for any [handlerType] (also custom methods from [HandlerType.findOrCreate]). */
    public fun addHttpHandler(
        handlerType: HandlerType,
        path: String,
        handler: Handler,
        vararg roles: RouteRole,
    ): RoutesConfig =
        addEndpoint(
            Endpoint(handlerType, path, handler, if (roles.isEmpty()) emptySet() else setOf(Roles(roles.toSet())))
        )

    /** Adds a GET endpoint. */
    public fun get(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.GET, path, handler)

    /** Adds a GET endpoint with roles. */
    public fun get(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.GET, path, handler, *roles)

    /** Adds a POST endpoint. */
    public fun post(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.POST, path, handler)

    /** Adds a POST endpoint with roles. */
    public fun post(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.POST, path, handler, *roles)

    /** Adds a QUERY endpoint (a safe method with a request body). */
    public fun query(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.QUERY, path, handler)

    /** Adds a QUERY endpoint with roles. */
    public fun query(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.QUERY, path, handler, *roles)

    /** Adds a PUT endpoint. */
    public fun put(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.PUT, path, handler)

    /** Adds a PUT endpoint with roles. */
    public fun put(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.PUT, path, handler, *roles)

    /** Adds a PATCH endpoint. */
    public fun patch(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.PATCH, path, handler)

    /** Adds a PATCH endpoint with roles. */
    public fun patch(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.PATCH, path, handler, *roles)

    /** Adds a DELETE endpoint. */
    public fun delete(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.DELETE, path, handler)

    /** Adds a DELETE endpoint with roles. */
    public fun delete(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.DELETE, path, handler, *roles)

    /** Adds a HEAD endpoint. */
    public fun head(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.HEAD, path, handler)

    /** Adds a HEAD endpoint with roles. */
    public fun head(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.HEAD, path, handler, *roles)

    /** Adds an OPTIONS endpoint. */
    public fun options(path: String, handler: Handler): RoutesConfig =
        addHttpHandler(HandlerType.OPTIONS, path, handler)

    /** Adds an OPTIONS endpoint with roles. */
    public fun options(path: String, handler: Handler, vararg roles: RouteRole): RoutesConfig =
        addHttpHandler(HandlerType.OPTIONS, path, handler, *roles)

    /** Adds a before handler for [path]. */
    public fun before(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.BEFORE, path, handler)

    /** Adds a before handler for all requests. */
    public fun before(handler: Handler): RoutesConfig = before("*", handler)

    /** Adds a before handler that only runs when an endpoint (or static file) matches. */
    public fun beforeMatched(path: String, handler: Handler): RoutesConfig =
        addHttpHandler(HandlerType.BEFORE_MATCHED, path, handler)

    /** Adds a before-matched handler for all requests. */
    public fun beforeMatched(handler: Handler): RoutesConfig = beforeMatched("*", handler)

    /** Adds an after handler for [path]. Runs even if an exception occurred. */
    public fun after(path: String, handler: Handler): RoutesConfig = addHttpHandler(HandlerType.AFTER, path, handler)

    /** Adds an after handler for all requests. */
    public fun after(handler: Handler): RoutesConfig = after("*", handler)

    /** Adds an after handler that only runs when an endpoint (or static file) matched. */
    public fun afterMatched(path: String, handler: Handler): RoutesConfig =
        addHttpHandler(HandlerType.AFTER_MATCHED, path, handler)

    /** Adds an after-matched handler for all requests. */
    public fun afterMatched(handler: Handler): RoutesConfig = afterMatched("*", handler)

    /** Adds a server-sent events endpoint. */
    public fun sse(path: String, client: Consumer<SseClient>): RoutesConfig =
        get(path, SseHandler(config.sse.heartbeat, client))

    /** Adds a server-sent events endpoint with roles. */
    public fun sse(path: String, client: Consumer<SseClient>, vararg roles: RouteRole): RoutesConfig =
        get(path, SseHandler(config.sse.heartbeat, client), *roles)

    /** Maps exceptions of [exceptionClass] (and subclasses) to [exceptionHandler]. */
    public fun <E : Exception> exception(
        exceptionClass: Class<E>,
        exceptionHandler: ExceptionHandler<in E>,
    ): RoutesConfig = apply {
        checkOpen()
        @Suppress("UNCHECKED_CAST")
        exceptionHandlers[exceptionClass] = exceptionHandler as ExceptionHandler<Exception>
    }

    /** Maps exceptions of type [E] to [exceptionHandler]. */
    public inline fun <reified E : Exception> exception(exceptionHandler: ExceptionHandler<in E>): RoutesConfig =
        exception(E::class.java, exceptionHandler)

    /** Adds an error handler for [status]. */
    public fun error(status: Int, handler: Handler): RoutesConfig = error(status, "*", handler)

    /** Adds an error handler for [status], run only if the client accepts [contentType] (`html`, `json`, ...). */
    public fun error(status: Int, contentType: String, handler: Handler): RoutesConfig = apply {
        checkOpen()
        errorHandlers.add(Triple(status, contentType, handler))
    }

    /** Adds an error handler for [status]. */
    public fun error(status: HttpStatus, handler: Handler): RoutesConfig = error(status.code, handler)

    /** Adds an error handler for [status] and [contentType]. */
    public fun error(status: HttpStatus, contentType: String, handler: Handler): RoutesConfig =
        error(status.code, contentType, handler)

    /** Adds endpoints with the static [ApiBuilder] functions (`path`, `get`, `crud`, ...). */
    public fun apiBuilder(endpoints: EndpointGroup): RoutesConfig = apply {
        ApiBuilder.withRoutes(this) { endpoints.addEndpoints() }
    }
}
