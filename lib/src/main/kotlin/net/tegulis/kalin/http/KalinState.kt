package net.tegulis.kalin.http

import net.tegulis.kalin.compression.CompressionStrategy
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.config.Key
import net.tegulis.kalin.json.JsonMapper
import net.tegulis.kalin.plugin.ContextPlugin
import net.tegulis.kalin.rendering.FileRenderer
import net.tegulis.kalin.rendering.MissingFileRenderer
import net.tegulis.kalin.router.HandlerMetaInfo
import net.tegulis.kalin.router.InternalRouter
import net.tegulis.kalin.staticfiles.KalinStaticResourceHandler
import net.tegulis.kalin.staticfiles.ResourceHandler
import net.tegulis.kalin.staticfiles.SinglePageHandler
import net.tegulis.kalin.validation.Validation

/** Immutable runtime state of an app, built from its configuration when the server starts. */
internal class KalinState(val config: KalinConfig) {
    val router: InternalRouter = InternalRouter(config.router)
    val jsonMapper: JsonMapper = config.jsonMapper ?: JsonMapper.default()
    val fileRenderer: FileRenderer = config.fileRenderer ?: MissingFileRenderer
    val resourceHandler: ResourceHandler?
    val singlePageHandler = SinglePageHandler()
    val validation = Validation(config.validation)
    val requestLoggers = config.requestLogger.httpLoggers.toList()
    val contextPath: String = normalizeContextPath(config.router.contextPath)
    val compression: CompressionStrategy = config.http.compressionStrategy
    val defaultContentType: String = config.http.defaultContentType
    val maxRequestSize: Long = config.http.maxRequestSize
    val responseBufferSize: Int = config.http.responseBufferSize ?: 32 * 1024
    val generateEtags: Boolean = config.http.generateEtags
    val prefer405over404: Boolean = config.http.prefer405over404
    val strictContentTypes: Boolean = config.http.strictContentTypes
    val asyncTimeout: Long = config.http.asyncTimeout
    val appData: Map<Key<*>, Any?> = HashMap(config.appData)
    val contextPlugins: Map<Class<*>, ContextPlugin<*, *>> =
        config.plugins.filterIsInstance<ContextPlugin<*, *>>().associateBy { it.javaClass }
    val resolver = config.contextResolver
    val hasBefore: Boolean
    val hasBeforeMatched: Boolean
    val hasAfterMatched: Boolean
    val hasAfter: Boolean
    val hasErrorMappers: Boolean

    init {
        val handler =
            config.resourceHandler
                ?: if (config.staticFiles.configs.isNotEmpty()) KalinStaticResourceHandler() else null
        config.staticFiles.configs.forEach { handler?.addStaticFileConfig(it) }
        resourceHandler = handler
        config.spaRoot.entries.forEach { (path, h) -> singlePageHandler.add(path, h) }
        for (endpoint in config.routes.endpoints) {
            router.add(endpoint)
            val info = HandlerMetaInfo(endpoint.method, endpoint.path, endpoint.handler, endpoint.roles)
            config.events.handlerAddedListeners.forEach { it.accept(info) }
        }
        config.routes.exceptionHandlers.forEach { (k, v) -> router.exceptionMapper.handlers[k] = v }
        if (
            router.exceptionMapper.handlers.keys.none {
                it == net.tegulis.kalin.validation.ValidationException::class.java
            }
        ) {
            router.exceptionMapper.handlers[net.tegulis.kalin.validation.ValidationException::class.java] =
                ExceptionHandler { e, ctx ->
                    e as net.tegulis.kalin.validation.ValidationException
                    ctx.contentType(ContentType.APPLICATION_JSON)
                    ctx.result(net.tegulis.kalin.json.SimpleJson.write(e.errors)).status(HttpStatus.BAD_REQUEST)
                }
        }
        config.routes.errorHandlers.forEach { (status, type, h) -> router.errorMapper.add(status, type, h) }
        hasBefore = router.hasType(HandlerType.BEFORE)
        hasBeforeMatched = router.hasType(HandlerType.BEFORE_MATCHED)
        hasAfterMatched = router.hasType(HandlerType.AFTER_MATCHED)
        hasAfter = router.hasType(HandlerType.AFTER)
        hasErrorMappers = !router.errorMapper.isEmpty
    }

    private fun normalizeContextPath(path: String): String {
        val p = "/" + path.trim().trim('/')
        return if (p == "/") "" else p
    }
}
