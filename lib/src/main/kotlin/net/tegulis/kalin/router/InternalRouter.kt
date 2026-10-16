package net.tegulis.kalin.router

import java.lang.System.Logger.Level
import net.tegulis.kalin.config.RouterConfig
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.ExceptionHandler
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.HttpResponseException
import net.tegulis.kalin.http.KalinContext

/** An endpoint with its compiled path matcher. */
internal class ParsedEndpoint(
    val endpoint: Endpoint,
    val matcher: PathMatcher,
    val index: Int,
    wrapper: HandlerWrapper?,
) {
    private val handler: Handler = wrapper?.wrap(endpoint) ?: endpoint.handler

    val hasPathParams: Boolean = matcher.pathParamNames.isNotEmpty()

    /** True if the handler declared itself non-blocking. */
    val nonBlocking: Boolean = endpoint.handler is net.tegulis.kalin.http.NonBlockingHandler

    fun handle(ctx: KalinContext, requestUri: String) {
        ctx.update(endpoint, if (hasPathParams) matcher.extractPathParams(requestUri) else emptyMap())
        handler.handle(ctx)
    }

    fun handleWithPathParams(ctx: KalinContext, pathParams: Map<String, String>) {
        ctx.update(endpoint, pathParams)
        handler.handle(ctx)
    }
}

/** All endpoints of one handler type, with a hash index for static paths. */
private class TypeEntries {
    val all = ArrayList<ParsedEndpoint>()
    val static = HashMap<String, ParsedEndpoint>()
    val dynamic = ArrayList<ParsedEndpoint>()
}

/** Stores endpoints and matches request paths to them, in registration order. */
internal class InternalRouter(private val routerConfig: RouterConfig) {
    private val byType = HashMap<HandlerType, TypeEntries>()
    private var counter = 0
    val exceptionMapper = ExceptionMapper()
    val errorMapper = ErrorMapper()

    fun add(endpoint: Endpoint): ParsedEndpoint {
        val parsed =
            ParsedEndpoint(endpoint, PathMatcher(endpoint.path, routerConfig), counter++, routerConfig.handlerWrapper)
        val entries = byType.getOrPut(endpoint.method) { TypeEntries() }
        entries.all.add(parsed)
        val key = parsed.matcher.staticKey
        if (key != null) entries.static.putIfAbsent(key, parsed) else entries.dynamic.add(parsed)
        return parsed
    }

    fun allEndpoints(): List<Endpoint> = byType.values.flatMap { it.all }.sortedBy { it.index }.map { it.endpoint }

    fun hasType(type: HandlerType): Boolean = byType[type]?.all?.isNotEmpty() == true

    /** The first endpoint of [type] matching [requestUri], honoring registration order. */
    fun findFirst(type: HandlerType, requestUri: String): ParsedEndpoint? {
        val entries = byType[type] ?: return null
        val candidate =
            if (entries.static.isEmpty()) null else entries.static[PathMatcher.normalize(requestUri, routerConfig)]
        for (d in entries.dynamic) {
            if (candidate != null && d.index > candidate.index) break
            if (d.matcher.matches(requestUri)) return d
        }
        return candidate
    }

    /** All endpoints of [type] matching [requestUri], in registration order. */
    fun findAll(type: HandlerType, requestUri: String): List<ParsedEndpoint> {
        val entries = byType[type] ?: return emptyList()
        var result: ArrayList<ParsedEndpoint>? = null
        for (e in entries.all) {
            if (e.matcher.matches(requestUri)) {
                if (result == null) result = ArrayList(2)
                result.add(e)
            }
        }
        return result ?: emptyList()
    }

    fun hasEntry(type: HandlerType, requestUri: String): Boolean = findFirst(type, requestUri) != null

    /** HTTP methods that have an endpoint matching [requestUri]. */
    fun availableMethods(requestUri: String): List<HandlerType> =
        byType.keys.filter { it.isHttpMethod && findFirst(it, requestUri) != null }.sortedBy { it.name }

    /** True if every handler that could run for this request is non-blocking. */
    fun isNonBlocking(type: HandlerType, requestUri: String): Boolean {
        val endpoint = findFirst(type, requestUri) ?: return false
        if (!endpoint.nonBlocking) return false
        for (phase in PHASES) {
            val entries = byType[phase] ?: continue
            for (e in entries.all) if (!e.nonBlocking && e.matcher.matches(requestUri)) return false
        }
        return true
    }

    private companion object {
        val PHASES =
            listOf(HandlerType.BEFORE, HandlerType.BEFORE_MATCHED, HandlerType.AFTER_MATCHED, HandlerType.AFTER)
    }
}

/** Maps exception classes to handlers; the closest superclass wins. */
internal class ExceptionMapper {
    val handlers = LinkedHashMap<Class<out Exception>, ExceptionHandler<Exception>>()

    init {
        handlers[HttpResponseException::class.java] = ExceptionHandler { e, ctx ->
            HttpResponseExceptionMapper.handle(e as HttpResponseException, ctx)
        }
    }

    private fun find(type: Class<*>): ExceptionHandler<Exception>? {
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            handlers[c]?.let {
                return it
            }
            c = c.superclass
        }
        return null
    }

    fun handle(ctx: Context, throwable: Throwable) {
        val t = unwrap(throwable)
        if (t !is Exception) {
            log.log(Level.ERROR, "Error while handling request", t)
            ctx.status(500)
            HttpResponseExceptionMapper.handle(net.tegulis.kalin.http.InternalServerErrorResponse(), ctx)
            return
        }
        val handler = find(t.javaClass)
        try {
            if (handler != null) handler.handle(t, ctx)
            else if (t is HttpResponseException) HttpResponseExceptionMapper.handle(t, ctx) else uncaught(ctx, t)
        } catch (inHandler: Throwable) {
            log.log(Level.WARNING, "Exception handler for ${t.javaClass.simpleName} threw an error", inHandler)
            uncaught(ctx, t)
        }
    }

    private fun uncaught(ctx: Context, e: Exception) {
        if (e is java.io.IOException && ctx.exchange().isDisconnected) {
            log.log(Level.DEBUG, "Client disconnected: ${e.message}")
            ctx.status(499)
            return
        }
        if (e is net.tegulis.kalin.engine.BadRequestBodyException) {
            HttpResponseExceptionMapper.handle(HttpResponseException(e.status, e.message ?: "Bad request"), ctx)
            return
        }
        log.log(Level.WARNING, "Uncaught exception", e)
        HttpResponseExceptionMapper.handle(net.tegulis.kalin.http.InternalServerErrorResponse(), ctx)
    }

    private fun unwrap(t: Throwable): Throwable =
        if (
            (t is java.util.concurrent.CompletionException || t is java.util.concurrent.ExecutionException) &&
                t.cause != null
        )
            unwrap(t.cause!!)
        else t

    companion object {
        val log: System.Logger = System.getLogger("net.tegulis.kalin")
    }
}

/** Maps status codes (and optionally accepted content types) to handlers. */
internal class ErrorMapper {
    private data class Entry(val status: Int, val contentType: String, val handler: Handler)

    private val entries = ArrayList<Entry>()

    val isEmpty: Boolean
        get() = entries.isEmpty()

    fun add(status: Int, contentType: String, handler: Handler) {
        entries.add(Entry(status, contentType, handler))
    }

    fun handle(status: Int, ctx: Context) {
        if (entries.isEmpty()) return
        for (e in entries) {
            if (e.status != status) continue
            if (e.contentType == "*" || ctx.header(Header.ACCEPT)?.contains(e.contentType, ignoreCase = true) == true)
                e.handler.handle(ctx)
        }
    }
}
