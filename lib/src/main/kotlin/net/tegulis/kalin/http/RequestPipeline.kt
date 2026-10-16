package net.tegulis.kalin.http

import net.tegulis.kalin.engine.ExchangeHandler
import net.tegulis.kalin.engine.Http1Exchange

/**
 * The request lifecycle, in Javalin order: before → beforeMatched → endpoint (or static files, SPA root, 404/405) →
 * afterMatched → error → after. Exceptions are mapped by the exception mappers; the request logger runs after the
 * response was written.
 */
internal class RequestPipeline(private val state: KalinState) : ExchangeHandler {
    override fun handle(exchange: Http1Exchange) {
        val ctx = KalinContext(exchange, state)
        try {
            createTasks(ctx)
        } catch (t: Throwable) {
            ctx.exceptionOccurred = true
            ctx.tasks.addFirst(Task(false) { state.router.exceptionMapper.handle(ctx, t) })
        }
        ctx.runTasks()
    }

    override fun isNonBlocking(method: String, path: String): Boolean {
        val type = HandlerType.findByName(method) ?: return false
        val cp = state.contextPath
        val uri =
            if (cp.isEmpty()) path
            else if (path.startsWith(cp)) path.substring(cp.length).ifEmpty { "/" } else return false
        return state.router.isNonBlocking(type, uri)
    }

    private fun createTasks(ctx: KalinContext) {
        val router = state.router
        val uri = ctx.requestUri
        val tasks = ctx.tasks
        if (!ctx.inContextPath) {
            tasks.add(Task(true) { throw NotFoundResponse() })
            return
        }
        if (state.hasBefore) {
            for (entry in router.findAll(HandlerType.BEFORE, uri)) tasks.add(Task(true) { entry.handle(ctx, uri) })
        }
        if (state.hasBeforeMatched) {
            for (entry in router.findAll(HandlerType.BEFORE_MATCHED, uri)) {
                tasks.add(
                    Task(true) {
                        if (ctx.willMatch()) {
                            val http = ctx.httpHandler()
                            ctx.setRouteRoles(
                                http?.endpoint?.roles ?: (state.resourceHandler?.resourceRouteRoles(ctx) ?: emptySet())
                            )
                            if (http != null) ctx.endpoints().matchedHttpEndpoint = http.endpoint
                            if (http != null && !entry.hasPathParams && http.hasPathParams) {
                                entry.handleWithPathParams(ctx, http.matcher.extractPathParams(uri))
                            } else {
                                entry.handle(ctx, uri)
                            }
                        }
                    }
                )
            }
        }
        tasks.add(Task(true) { handleHttp(ctx, uri) })
        if (state.hasAfterMatched) {
            for (entry in router.findAll(HandlerType.AFTER_MATCHED, uri)) {
                tasks.add(Task(false) { if (ctx.willMatch()) entry.handle(ctx, uri) })
            }
        }
        if (state.hasErrorMappers) tasks.add(Task(false) { router.errorMapper.handle(ctx.statusCode(), ctx) })
        if (state.hasAfter) {
            for (entry in router.findAll(HandlerType.AFTER, uri)) tasks.add(Task(false) { entry.handle(ctx, uri) })
        }
    }

    private fun handleHttp(ctx: KalinContext, uri: String) {
        val entry = ctx.httpHandler()
        if (entry != null) {
            ctx.setRouteRoles(entry.endpoint.roles)
            ctx.endpoints().matchedHttpEndpoint = entry.endpoint
            entry.handle(ctx, uri)
            return
        }
        val method = ctx.method()
        if (method == HandlerType.GET || method == HandlerType.HEAD) {
            state.resourceHandler?.let { handler ->
                if (state.hasBeforeMatched) ctx.setRouteRoles(handler.resourceRouteRoles(ctx))
                if (handler.handle(ctx)) return
            }
            if (state.singlePageHandler.handle(ctx)) return
        }
        val available = state.router.availableMethods(uri)
        if (state.prefer405over404 && available.isNotEmpty()) {
            throw MethodNotAllowedResponse(details = mapOf("availableMethods" to available.joinToString(", ")))
        }
        throw EndpointNotFound(method, uri)
    }
}
