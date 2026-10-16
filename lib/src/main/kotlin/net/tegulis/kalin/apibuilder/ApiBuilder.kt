@file:JvmName("ApiBuilderFunctions")

package net.tegulis.kalin.apibuilder

import java.util.function.Consumer
import net.tegulis.kalin.config.RoutesConfig
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.router.Endpoint
import net.tegulis.kalin.router.Roles
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.sse.SseClient

/** A group of endpoints, used by `config.routes.apiBuilder { }` and [path]. */
public fun interface EndpointGroup {
    /** Adds the endpoints. */
    public fun addEndpoints()
}

/** The five common CRUD operations, registered with [crud]. */
public interface CrudHandler {
    /** `GET /resource` */
    public fun getAll(ctx: Context)

    /** `GET /resource/{id}` */
    public fun getOne(ctx: Context, resourceId: String)

    /** `POST /resource` */
    public fun create(ctx: Context)

    /** `PATCH /resource/{id}` */
    public fun update(ctx: Context, resourceId: String)

    /** `DELETE /resource/{id}` */
    public fun delete(ctx: Context, resourceId: String)
}

/**
 * Static-style routing functions for use inside `config.routes.apiBuilder { }`. Kotlin code can import the top level
 * functions of this package (`import net.tegulis.kalin.apibuilder.*`).
 */
public object ApiBuilder {
    private class State(val routes: RoutesConfig) {
        val paths = ArrayDeque<String>()
        val roles = ArrayDeque<Set<RouteRole>>()
    }

    private val state = ThreadLocal<State?>()

    internal fun withRoutes(routes: RoutesConfig, block: () -> Unit) {
        val previous = state.get()
        state.set(State(routes))
        try {
            block()
        } finally {
            state.set(previous)
        }
    }

    /** Applies the roles of enclosing [path] blocks to [endpoint]. */
    internal fun scope(routes: RoutesConfig, endpoint: Endpoint): Endpoint {
        val s = state.get() ?: return endpoint
        if (s.routes !== routes || s.roles.isEmpty()) return endpoint
        val scoped = s.roles.flatMapTo(LinkedHashSet()) { it }
        if (scoped.isEmpty()) return endpoint
        return endpoint.withMetadata(Roles(scoped + endpoint.roles))
    }

    private fun current(): State =
        state.get()
            ?: throw IllegalStateException(
                "The ApiBuilder functions can only be used inside config.routes.apiBuilder { }"
            )

    /** Prefixes [path] with the enclosing [path] blocks. */
    @JvmStatic
    public fun prefixPath(path: String): String {
        val normalized = if (path == "*" || path.isEmpty() || path.startsWith("/")) path else "/$path"
        return current().paths.joinToString("") + normalized
    }

    /** Groups endpoints below [path]. */
    @JvmStatic
    public fun path(path: String, endpointGroup: EndpointGroup) {
        path(path, emptyList(), endpointGroup)
    }

    /** Groups endpoints below [path], adding [roles] to all of them. */
    @JvmStatic
    public fun path(path: String, roles: Collection<RouteRole>, endpointGroup: EndpointGroup) {
        val s = current()
        val normalized = if (path.startsWith("/")) path else "/$path"
        s.paths.addLast(normalized.trimEnd('/').ifEmpty { "" })
        s.roles.addLast(roles.toSet())
        try {
            endpointGroup.addEndpoints()
        } finally {
            s.paths.removeLast()
            s.roles.removeLast()
        }
    }

    private fun routes(): RoutesConfig = current().routes

    private fun full(path: String): String = prefixPath(path).ifEmpty { "/" }

    @JvmStatic
    public fun get(path: String, handler: Handler) {
        routes().get(full(path), handler)
    }

    @JvmStatic
    public fun get(path: String, handler: Handler, vararg roles: RouteRole) {
        routes().get(full(path), handler, *roles)
    }

    @JvmStatic
    public fun get(handler: Handler) {
        get("", handler)
    }

    @JvmStatic
    public fun get(handler: Handler, vararg roles: RouteRole) {
        get("", handler, *roles)
    }

    @JvmStatic
    public fun post(path: String, handler: Handler) {
        routes().post(full(path), handler)
    }

    @JvmStatic
    public fun post(path: String, handler: Handler, vararg roles: RouteRole) {
        routes().post(full(path), handler, *roles)
    }

    @JvmStatic
    public fun post(handler: Handler) {
        post("", handler)
    }

    @JvmStatic
    public fun post(handler: Handler, vararg roles: RouteRole) {
        post("", handler, *roles)
    }

    @JvmStatic
    public fun query(path: String, handler: Handler) {
        routes().query(full(path), handler)
    }

    @JvmStatic
    public fun query(path: String, handler: Handler, vararg roles: RouteRole) {
        routes().query(full(path), handler, *roles)
    }

    @JvmStatic
    public fun query(handler: Handler) {
        query("", handler)
    }

    @JvmStatic
    public fun put(path: String, handler: Handler) {
        routes().put(full(path), handler)
    }

    @JvmStatic
    public fun put(path: String, handler: Handler, vararg roles: RouteRole) {
        routes().put(full(path), handler, *roles)
    }

    @JvmStatic
    public fun put(handler: Handler) {
        put("", handler)
    }

    @JvmStatic
    public fun put(handler: Handler, vararg roles: RouteRole) {
        put("", handler, *roles)
    }

    @JvmStatic
    public fun patch(path: String, handler: Handler) {
        routes().patch(full(path), handler)
    }

    @JvmStatic
    public fun patch(path: String, handler: Handler, vararg roles: RouteRole) {
        routes().patch(full(path), handler, *roles)
    }

    @JvmStatic
    public fun patch(handler: Handler) {
        patch("", handler)
    }

    @JvmStatic
    public fun patch(handler: Handler, vararg roles: RouteRole) {
        patch("", handler, *roles)
    }

    @JvmStatic
    public fun delete(path: String, handler: Handler) {
        routes().delete(full(path), handler)
    }

    @JvmStatic
    public fun delete(path: String, handler: Handler, vararg roles: RouteRole) {
        routes().delete(full(path), handler, *roles)
    }

    @JvmStatic
    public fun delete(handler: Handler) {
        delete("", handler)
    }

    @JvmStatic
    public fun delete(handler: Handler, vararg roles: RouteRole) {
        delete("", handler, *roles)
    }

    @JvmStatic
    public fun head(path: String, handler: Handler) {
        routes().head(full(path), handler)
    }

    @JvmStatic
    public fun head(path: String, handler: Handler, vararg roles: RouteRole) {
        routes().head(full(path), handler, *roles)
    }

    @JvmStatic
    public fun head(handler: Handler) {
        head("", handler)
    }

    @JvmStatic
    public fun options(path: String, handler: Handler) {
        routes().options(full(path), handler)
    }

    @JvmStatic
    public fun options(handler: Handler) {
        options("", handler)
    }

    @JvmStatic
    public fun before(path: String, handler: Handler) {
        routes().before(prefixPath(path), handler)
    }

    @JvmStatic
    public fun before(handler: Handler) {
        before("*", handler)
    }

    @JvmStatic
    public fun beforeMatched(path: String, handler: Handler) {
        routes().beforeMatched(prefixPath(path), handler)
    }

    @JvmStatic
    public fun beforeMatched(handler: Handler) {
        beforeMatched("*", handler)
    }

    @JvmStatic
    public fun after(path: String, handler: Handler) {
        routes().after(prefixPath(path), handler)
    }

    @JvmStatic
    public fun after(handler: Handler) {
        after("*", handler)
    }

    @JvmStatic
    public fun afterMatched(path: String, handler: Handler) {
        routes().afterMatched(prefixPath(path), handler)
    }

    @JvmStatic
    public fun afterMatched(handler: Handler) {
        afterMatched("*", handler)
    }

    @JvmStatic
    public fun sse(path: String, client: Consumer<SseClient>) {
        routes().sse(full(path), client)
    }

    @JvmStatic
    public fun sse(path: String, client: Consumer<SseClient>, vararg roles: RouteRole) {
        routes().sse(full(path), client, *roles)
    }

    @JvmStatic
    public fun sse(client: Consumer<SseClient>) {
        sse("", client)
    }

    /** Registers [crudHandler] for a path ending with a path parameter, for example `users/{user-id}`. */
    @JvmStatic
    public fun crud(path: String, crudHandler: CrudHandler, vararg roles: RouteRole) {
        val fullPath = prefixPath(path)
        val segments = fullPath.split('/').filter { it.isNotEmpty() }
        val last = segments.lastOrNull()
        require(last != null && last.startsWith("{") && last.endsWith("}")) {
            "CrudHandler requires a path like '/resource/{resource-id}'"
        }
        val idName = last.substring(1, last.length - 1)
        val base = "/" + segments.dropLast(1).joinToString("/")
        val routes = routes()
        routes.get(base, { ctx -> crudHandler.getAll(ctx) }, *roles)
        routes.get(fullPath, { ctx -> crudHandler.getOne(ctx, ctx.pathParam(idName)) }, *roles)
        routes.post(base, { ctx -> crudHandler.create(ctx) }, *roles)
        routes.patch(fullPath, { ctx -> crudHandler.update(ctx, ctx.pathParam(idName)) }, *roles)
        routes.delete(fullPath, { ctx -> crudHandler.delete(ctx, ctx.pathParam(idName)) }, *roles)
    }

    /** Registers [crudHandler] for the current path, which must end with a path parameter. */
    @JvmStatic
    public fun crud(crudHandler: CrudHandler, vararg roles: RouteRole) {
        crud("", crudHandler, *roles)
    }
}

/** See [ApiBuilder.path]. */
public fun path(path: String, endpointGroup: EndpointGroup): Unit = ApiBuilder.path(path, endpointGroup)

/** See [ApiBuilder.path]. */
public fun path(path: String, roles: Collection<RouteRole>, endpointGroup: EndpointGroup): Unit =
    ApiBuilder.path(path, roles, endpointGroup)

/** GET endpoint relative to the current [path]. */
public fun get(path: String, handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.get(path, handler, *roles)

/** GET endpoint at the current [path]. */
public fun get(handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.get(handler, *roles)

/** POST endpoint relative to the current [path]. */
public fun post(path: String, handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.post(path, handler, *roles)

/** POST endpoint at the current [path]. */
public fun post(handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.post(handler, *roles)

/** QUERY endpoint relative to the current [path]. */
public fun query(path: String, handler: Handler, vararg roles: RouteRole): Unit =
    ApiBuilder.query(path, handler, *roles)

/** QUERY endpoint at the current [path]. */
public fun query(handler: Handler): Unit = ApiBuilder.query(handler)

/** PUT endpoint relative to the current [path]. */
public fun put(path: String, handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.put(path, handler, *roles)

/** PUT endpoint at the current [path]. */
public fun put(handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.put(handler, *roles)

/** PATCH endpoint relative to the current [path]. */
public fun patch(path: String, handler: Handler, vararg roles: RouteRole): Unit =
    ApiBuilder.patch(path, handler, *roles)

/** PATCH endpoint at the current [path]. */
public fun patch(handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.patch(handler, *roles)

/** DELETE endpoint relative to the current [path]. */
public fun delete(path: String, handler: Handler, vararg roles: RouteRole): Unit =
    ApiBuilder.delete(path, handler, *roles)

/** DELETE endpoint at the current [path]. */
public fun delete(handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.delete(handler, *roles)

/** HEAD endpoint relative to the current [path]. */
public fun head(path: String, handler: Handler, vararg roles: RouteRole): Unit = ApiBuilder.head(path, handler, *roles)

/** OPTIONS endpoint relative to the current [path]. */
public fun options(path: String, handler: Handler): Unit = ApiBuilder.options(path, handler)

/** Before handler relative to the current [path]. */
public fun before(path: String, handler: Handler): Unit = ApiBuilder.before(path, handler)

/** Before handler for the current [path] and below. */
public fun before(handler: Handler): Unit = ApiBuilder.before(handler)

/** Before-matched handler relative to the current [path]. */
public fun beforeMatched(path: String, handler: Handler): Unit = ApiBuilder.beforeMatched(path, handler)

/** Before-matched handler for the current [path] and below. */
public fun beforeMatched(handler: Handler): Unit = ApiBuilder.beforeMatched(handler)

/** After handler relative to the current [path]. */
public fun after(path: String, handler: Handler): Unit = ApiBuilder.after(path, handler)

/** After handler for the current [path] and below. */
public fun after(handler: Handler): Unit = ApiBuilder.after(handler)

/** After-matched handler relative to the current [path]. */
public fun afterMatched(path: String, handler: Handler): Unit = ApiBuilder.afterMatched(path, handler)

/** After-matched handler for the current [path] and below. */
public fun afterMatched(handler: Handler): Unit = ApiBuilder.afterMatched(handler)

/** SSE endpoint relative to the current [path]. */
public fun sse(path: String, client: Consumer<SseClient>, vararg roles: RouteRole): Unit =
    ApiBuilder.sse(path, client, *roles)

/** CRUD endpoints, see [ApiBuilder.crud]. */
public fun crud(path: String, crudHandler: CrudHandler, vararg roles: RouteRole): Unit =
    ApiBuilder.crud(path, crudHandler, *roles)

/** CRUD endpoints at the current [path], see [ApiBuilder.crud]. */
public fun crud(crudHandler: CrudHandler, vararg roles: RouteRole): Unit = ApiBuilder.crud(crudHandler, *roles)

/** GET endpoint relative to the current [path]. */
public fun get(path: String, handler: Handler): Unit = ApiBuilder.get(path, handler)

/** POST endpoint relative to the current [path]. */
public fun post(path: String, handler: Handler): Unit = ApiBuilder.post(path, handler)

/** PUT endpoint relative to the current [path]. */
public fun put(path: String, handler: Handler): Unit = ApiBuilder.put(path, handler)

/** PATCH endpoint relative to the current [path]. */
public fun patch(path: String, handler: Handler): Unit = ApiBuilder.patch(path, handler)

/** DELETE endpoint relative to the current [path]. */
public fun delete(path: String, handler: Handler): Unit = ApiBuilder.delete(path, handler)

/** HEAD endpoint relative to the current [path]. */
public fun head(path: String, handler: Handler): Unit = ApiBuilder.head(path, handler)

/** QUERY endpoint relative to the current [path]. */
public fun query(path: String, handler: Handler): Unit = ApiBuilder.query(path, handler)

/** SSE endpoint relative to the current [path]. */
public fun sse(path: String, client: Consumer<SseClient>): Unit = ApiBuilder.sse(path, client)

/** GET endpoint at the current [path]. */
public fun get(handler: Handler): Unit = ApiBuilder.get(handler)

/** POST endpoint at the current [path]. */
public fun post(handler: Handler): Unit = ApiBuilder.post(handler)

/** PUT endpoint at the current [path]. */
public fun put(handler: Handler): Unit = ApiBuilder.put(handler)

/** PATCH endpoint at the current [path]. */
public fun patch(handler: Handler): Unit = ApiBuilder.patch(handler)

/** DELETE endpoint at the current [path]. */
public fun delete(handler: Handler): Unit = ApiBuilder.delete(handler)

/** HEAD endpoint at the current [path]. */
public fun head(handler: Handler): Unit = ApiBuilder.head(handler)

/** SSE endpoint at the current [path]. */
public fun sse(client: Consumer<SseClient>): Unit = ApiBuilder.sse(client)
