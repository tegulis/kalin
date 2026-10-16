@file:JvmName("ApiBuilder")

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

/** Thread-local state of `config.routes.apiBuilder { }` blocks. */
internal object ApiBuilderState {
    private class State(val routes: RoutesConfig) {
        val paths = ArrayDeque<String>()
        val roles = ArrayDeque<Set<RouteRole>>()
    }

    private val state = ThreadLocal<State?>()

    fun withRoutes(routes: RoutesConfig, block: () -> Unit) {
        val previous = state.get()
        state.set(State(routes))
        try {
            block()
        } finally {
            state.set(previous)
        }
    }

    /** Applies the roles of enclosing [path] blocks to [endpoint]. */
    fun scope(routes: RoutesConfig, endpoint: Endpoint): Endpoint {
        val s = state.get() ?: return endpoint
        if (s.routes !== routes || s.roles.isEmpty()) return endpoint
        val scoped = s.roles.flatMapTo(LinkedHashSet()) { it }
        if (scoped.isEmpty()) return endpoint
        return endpoint.withMetadata(Roles(scoped + endpoint.roles))
    }

    fun routes(): RoutesConfig = current().routes

    fun prefixPath(path: String): String {
        val normalized = if (path == "*" || path.isEmpty() || path.startsWith("/")) path else "/$path"
        return current().paths.joinToString("") + normalized
    }

    fun full(path: String): String = prefixPath(path).ifEmpty { "/" }

    fun path(path: String, roles: Collection<RouteRole>, endpointGroup: EndpointGroup) {
        val s = current()
        val normalized = if (path.startsWith("/")) path else "/$path"
        s.paths.addLast(normalized.trimEnd('/'))
        s.roles.addLast(roles.toSet())
        try {
            endpointGroup.addEndpoints()
        } finally {
            s.paths.removeLast()
            s.roles.removeLast()
        }
    }

    private fun current(): State =
        state.get()
            ?: throw IllegalStateException(
                "The ApiBuilder functions can only be used inside config.routes.apiBuilder { }"
            )
}

/*
 * Static-style routing functions for `config.routes.apiBuilder { }`. Kotlin: `import net.tegulis.kalin.apibuilder.*`.
 * Java: `import static net.tegulis.kalin.apibuilder.ApiBuilder.*`.
 */

/** Prefixes [path] with the enclosing [path] blocks. */
public fun prefixPath(path: String): String = ApiBuilderState.prefixPath(path)

/** Groups endpoints below [path]. */
public fun path(path: String, endpointGroup: EndpointGroup): Unit =
    ApiBuilderState.path(path, emptyList(), endpointGroup)

/** Groups endpoints below [path], adding [roles] to all of them. */
public fun path(path: String, roles: Collection<RouteRole>, endpointGroup: EndpointGroup): Unit =
    ApiBuilderState.path(path, roles, endpointGroup)

private fun r() = ApiBuilderState.routes()

private fun f(path: String) = ApiBuilderState.full(path)

private fun p(path: String) = ApiBuilderState.prefixPath(path)

/** GET endpoint relative to the current [path]. */
public fun get(path: String, handler: Handler) {
    r().get(f(path), handler)
}

/** GET endpoint relative to the current [path], with roles. */
public fun get(path: String, handler: Handler, vararg roles: RouteRole) {
    r().get(f(path), handler, *roles)
}

/** GET endpoint at the current [path]. */
public fun get(handler: Handler) {
    r().get(f(""), handler)
}

/** GET endpoint at the current [path], with roles. */
public fun get(handler: Handler, vararg roles: RouteRole) {
    r().get(f(""), handler, *roles)
}

/** POST endpoint relative to the current [path]. */
public fun post(path: String, handler: Handler) {
    r().post(f(path), handler)
}

/** POST endpoint relative to the current [path], with roles. */
public fun post(path: String, handler: Handler, vararg roles: RouteRole) {
    r().post(f(path), handler, *roles)
}

/** POST endpoint at the current [path]. */
public fun post(handler: Handler) {
    r().post(f(""), handler)
}

/** POST endpoint at the current [path], with roles. */
public fun post(handler: Handler, vararg roles: RouteRole) {
    r().post(f(""), handler, *roles)
}

/** QUERY endpoint relative to the current [path]. */
public fun query(path: String, handler: Handler) {
    r().query(f(path), handler)
}

/** QUERY endpoint relative to the current [path], with roles. */
public fun query(path: String, handler: Handler, vararg roles: RouteRole) {
    r().query(f(path), handler, *roles)
}

/** QUERY endpoint at the current [path]. */
public fun query(handler: Handler) {
    r().query(f(""), handler)
}

/** QUERY endpoint at the current [path], with roles. */
public fun query(handler: Handler, vararg roles: RouteRole) {
    r().query(f(""), handler, *roles)
}

/** PUT endpoint relative to the current [path]. */
public fun put(path: String, handler: Handler) {
    r().put(f(path), handler)
}

/** PUT endpoint relative to the current [path], with roles. */
public fun put(path: String, handler: Handler, vararg roles: RouteRole) {
    r().put(f(path), handler, *roles)
}

/** PUT endpoint at the current [path]. */
public fun put(handler: Handler) {
    r().put(f(""), handler)
}

/** PUT endpoint at the current [path], with roles. */
public fun put(handler: Handler, vararg roles: RouteRole) {
    r().put(f(""), handler, *roles)
}

/** PATCH endpoint relative to the current [path]. */
public fun patch(path: String, handler: Handler) {
    r().patch(f(path), handler)
}

/** PATCH endpoint relative to the current [path], with roles. */
public fun patch(path: String, handler: Handler, vararg roles: RouteRole) {
    r().patch(f(path), handler, *roles)
}

/** PATCH endpoint at the current [path]. */
public fun patch(handler: Handler) {
    r().patch(f(""), handler)
}

/** PATCH endpoint at the current [path], with roles. */
public fun patch(handler: Handler, vararg roles: RouteRole) {
    r().patch(f(""), handler, *roles)
}

/** DELETE endpoint relative to the current [path]. */
public fun delete(path: String, handler: Handler) {
    r().delete(f(path), handler)
}

/** DELETE endpoint relative to the current [path], with roles. */
public fun delete(path: String, handler: Handler, vararg roles: RouteRole) {
    r().delete(f(path), handler, *roles)
}

/** DELETE endpoint at the current [path]. */
public fun delete(handler: Handler) {
    r().delete(f(""), handler)
}

/** DELETE endpoint at the current [path], with roles. */
public fun delete(handler: Handler, vararg roles: RouteRole) {
    r().delete(f(""), handler, *roles)
}

/** HEAD endpoint relative to the current [path]. */
public fun head(path: String, handler: Handler) {
    r().head(f(path), handler)
}

/** HEAD endpoint relative to the current [path], with roles. */
public fun head(path: String, handler: Handler, vararg roles: RouteRole) {
    r().head(f(path), handler, *roles)
}

/** HEAD endpoint at the current [path]. */
public fun head(handler: Handler) {
    r().head(f(""), handler)
}

/** HEAD endpoint at the current [path], with roles. */
public fun head(handler: Handler, vararg roles: RouteRole) {
    r().head(f(""), handler, *roles)
}

/** OPTIONS endpoint relative to the current [path]. */
public fun options(path: String, handler: Handler) {
    r().options(f(path), handler)
}

/** OPTIONS endpoint relative to the current [path], with roles. */
public fun options(path: String, handler: Handler, vararg roles: RouteRole) {
    r().options(f(path), handler, *roles)
}

/** OPTIONS endpoint at the current [path]. */
public fun options(handler: Handler) {
    r().options(f(""), handler)
}

/** OPTIONS endpoint at the current [path], with roles. */
public fun options(handler: Handler, vararg roles: RouteRole) {
    r().options(f(""), handler, *roles)
}

/** before handler relative to the current [path]. */
public fun before(path: String, handler: Handler) {
    r().before(p(path), handler)
}

/** before handler for the current [path] and below. */
public fun before(handler: Handler) {
    r().before(p("*"), handler)
}

/** beforeMatched handler relative to the current [path]. */
public fun beforeMatched(path: String, handler: Handler) {
    r().beforeMatched(p(path), handler)
}

/** beforeMatched handler for the current [path] and below. */
public fun beforeMatched(handler: Handler) {
    r().beforeMatched(p("*"), handler)
}

/** after handler relative to the current [path]. */
public fun after(path: String, handler: Handler) {
    r().after(p(path), handler)
}

/** after handler for the current [path] and below. */
public fun after(handler: Handler) {
    r().after(p("*"), handler)
}

/** afterMatched handler relative to the current [path]. */
public fun afterMatched(path: String, handler: Handler) {
    r().afterMatched(p(path), handler)
}

/** afterMatched handler for the current [path] and below. */
public fun afterMatched(handler: Handler) {
    r().afterMatched(p("*"), handler)
}

/** SSE endpoint relative to the current [path]. */
public fun sse(path: String, client: Consumer<SseClient>) {
    r().sse(f(path), client)
}

/** SSE endpoint relative to the current [path], with roles. */
public fun sse(path: String, client: Consumer<SseClient>, vararg roles: RouteRole) {
    r().sse(f(path), client, *roles)
}

/** SSE endpoint at the current [path]. */
public fun sse(client: Consumer<SseClient>) {
    r().sse(f(""), client)
}

/** Registers [crudHandler] for a path ending with a path parameter, for example `users/{user-id}`. */
public fun crud(path: String, crudHandler: CrudHandler, vararg roles: RouteRole) {
    val fullPath = p(path)
    val segments = fullPath.split('/').filter { it.isNotEmpty() }
    val last = segments.lastOrNull()
    require(last != null && last.startsWith("{") && last.endsWith("}")) {
        "CrudHandler requires a path like '/resource/{resource-id}'"
    }
    val idName = last.substring(1, last.length - 1)
    val base = "/" + segments.dropLast(1).joinToString("/")
    val routes = r()
    routes.get(base, { ctx -> crudHandler.getAll(ctx) }, *roles)
    routes.get(fullPath, { ctx -> crudHandler.getOne(ctx, ctx.pathParam(idName)) }, *roles)
    routes.post(base, { ctx -> crudHandler.create(ctx) }, *roles)
    routes.patch(fullPath, { ctx -> crudHandler.update(ctx, ctx.pathParam(idName)) }, *roles)
    routes.delete(fullPath, { ctx -> crudHandler.delete(ctx, ctx.pathParam(idName)) }, *roles)
}

/** Registers [crudHandler] for the current [path], which must end with a path parameter. */
public fun crud(crudHandler: CrudHandler, vararg roles: RouteRole): Unit = crud("", crudHandler, *roles)
