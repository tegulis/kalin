package net.tegulis.kalin.app

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.tegulis.kalin.compression.CompressionStrategy
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.staticfiles.Location

private val accessLog = KotlinLogging.logger("kalin-server.access")
private val clfDate = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.US)

/** Configures a Kalin app as a static file server for [options]. */
fun KalinConfig.staticServer(options: ServerOptions) {
    val root = options.directory.toAbsolutePath().normalize()
    server.host = options.host
    server.port = options.port
    server.engine = options.engine
    server.transport = options.transport
    server.maxConnections = options.maxConnections
    server.idleTimeout = options.idleTimeout
    server.serverHeader = options.serverHeader
    router.contextPath = options.contextPath
    http.compressionStrategy = if (options.compression) CompressionStrategy.GZIP else CompressionStrategy.NONE
    startup.showBanner = false
    if (options.proxyHeaders) contextResolver.useForwardedHeaders()

    staticFiles.add { files ->
        files.directory = root.toString()
        files.location = Location.EXTERNAL
        files.hostedPath = "/"
        files.welcomeFiles = options.indexFiles
        files.directoryListing = options.directoryListing
        files.serveHiddenFiles = options.hiddenFiles
        files.precompressedFiles = options.precompressed
        files.headers = mapOf(Header.CACHE_CONTROL to (options.cacheMaxAge?.let { "max-age=$it" } ?: "max-age=0"))
        if (options.followSymlinks) files.aliasCheck = { true }
    }
    options.spaFallback?.let { file ->
        val spaFile = root.resolve(file).normalize()
        spaRoot.addHandler("/") { ctx ->
            ctx.contentType(ContentType.HTML + ";charset=utf-8").result(Files.readAllBytes(spaFile))
        }
    }
    if (options.cors) bundledPlugins.enableCors { cors -> cors.addRule { it.anyHost() } }
    options.basicAuth?.let { (user, password) -> bundledPlugins.enableBasicAuth(user, password) }
    if (options.headers.isNotEmpty()) {
        routes.before { ctx -> options.headers.forEach { (name, value) -> ctx.header(name, value) } }
    }

    // Friendly error pages for browsers
    routes.error(404, "html") { ctx ->
        ctx.html(errorPage(HttpStatus.NOT_FOUND, "The requested resource was not found."))
    }
    routes.error(404) { ctx ->
        if (ctx.header(Header.ACCEPT)?.contains("html") != true) ctx.contentType(ContentType.PLAIN).result("Not Found")
    }
    routes.error(403, "html") { ctx -> ctx.html(errorPage(HttpStatus.FORBIDDEN, "Access denied.")) }

    if (options.accessLog) {
        requestLogger.http { ctx, ms ->
            val size = ctx.exchange().responseBodyBytes.let { if (it > 0) it.toString() else "-" }
            val line =
                "${ctx.ip()} - ${ctx.basicAuthCredentials()?.username ?: "-"} [${clfDate.format(ZonedDateTime.now())}] " +
                    "\"${ctx.method()} ${ctx.exchange().target} ${ctx.protocol()}\" ${ctx.statusCode()} $size " +
                    "\"${ctx.header(Header.REFERER) ?: "-"}\" \"${ctx.userAgent() ?: "-"}\" ${String.format(Locale.ROOT, "%.1f", ms)}ms"
            accessLog.info { line }
        }
    }
}

private fun errorPage(status: HttpStatus, message: String): String =
    """
    |<!DOCTYPE html>
    |<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    |<title>${status.code} ${status.message}</title>
    |<style>body{font-family:system-ui,sans-serif;margin:3rem;color:#222}@media (prefers-color-scheme:dark){body{background:#111;color:#ddd}}</style>
    |</head><body><h1>${status.code} ${status.message}</h1><p>$message</p><hr><small>kalin-server</small></body></html>
    """
        .trimMargin()
