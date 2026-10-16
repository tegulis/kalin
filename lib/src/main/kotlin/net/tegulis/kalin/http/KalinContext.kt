package net.tegulis.kalin.http

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.stream.Stream
import java.util.zip.CRC32C
import net.tegulis.kalin.async.AsyncTaskConfig
import net.tegulis.kalin.async.KalinExecutors
import net.tegulis.kalin.compression.CompressionType
import net.tegulis.kalin.config.Key
import net.tegulis.kalin.config.MultipartConfig
import net.tegulis.kalin.engine.Exchange
import net.tegulis.kalin.engine.Http1Exchange
import net.tegulis.kalin.json.JsonMapper
import net.tegulis.kalin.plugin.ContextPlugin
import net.tegulis.kalin.plugin.PluginNotRegisteredException
import net.tegulis.kalin.rendering.StreamingFileRenderer
import net.tegulis.kalin.router.Endpoint
import net.tegulis.kalin.router.Endpoints
import net.tegulis.kalin.router.ParsedEndpoint
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.staticfiles.Resource
import net.tegulis.kalin.util.CookieStore
import net.tegulis.kalin.util.FormParser
import net.tegulis.kalin.util.Multipart
import net.tegulis.kalin.validation.Validation

/** A unit of work in the request lifecycle. */
internal class Task(val skipOnExceptionAndRedirect: Boolean, val run: () -> Unit)

/** The [Context] implementation. One instance per request. */
internal class KalinContext(private val exchange: Http1Exchange, internal val state: KalinState) : Context() {
    internal val tasks = ArrayDeque<Task>(8)
    internal var exceptionOccurred = false
    private val responseWritten = AtomicBoolean(false)
    private val startNanos = if (state.requestLoggers.isEmpty()) 0L else System.nanoTime()
    private val endpoints = Endpoints()
    private var routeRoles: Set<RouteRole> = emptySet()
    private var userFuture: Supplier<out CompletableFuture<*>>? = null
    private var minSizeForCompression = state.compression.defaultMinSizeForCompression

    // Result: ByteArray, InputStream or ResourceResult
    private var result: Any? = null
    private var resultLength = -1L
    private var output: ResponseOutputStream? = null

    private var attributes: HashMap<String, Any?>? = null
    private var cachedMethod: HandlerType? = null
    private var cachedBody: ByteArray? = null
    private var cachedQuery: Map<String, List<String>>? = null
    private var cachedForm: Map<String, List<String>>? = null
    private var cachedCookies: Map<String, String>? = null
    private var cachedMultipart: Multipart.Parsed? = null
    private var cookieStore: CookieStore? = null
    private var httpHandlerLookedUp = false
    private var httpHandler: ParsedEndpoint? = null
    private var willMatchState = 0

    /** True if the request path is below the context path. */
    internal val inContextPath: Boolean =
        state.contextPath.isEmpty() ||
            exchange.path == state.contextPath ||
            exchange.path.startsWith(state.contextPath + "/")

    internal val requestUri: String =
        exchange.path.let { p ->
            val cp = state.contextPath
            if (cp.isEmpty() || !inContextPath) p else p.substring(cp.length).ifEmpty { "/" }
        }

    internal fun requestUri(): String = requestUri

    // ---- framework ---------------------------------------------------------------------------------------

    override fun exchange(): Exchange = exchange

    override fun endpoints(): Endpoints = endpoints

    @Suppress("UNCHECKED_CAST")
    override fun <T> appData(key: Key<T>): T {
        if (!state.appData.containsKey(key)) throw IllegalArgumentException("Key '${key.id}' does not exist")
        return state.appData[key] as T
    }

    override fun jsonMapper(): JsonMapper = state.jsonMapper

    @Suppress("UNCHECKED_CAST")
    override fun <T> with(clazz: Class<out ContextPlugin<*, T>>): T {
        val plugin = state.contextPlugins[clazz] ?: throw PluginNotRegisteredException(clazz)
        return (plugin as ContextPlugin<*, T>).createExtension(this)
    }

    override fun validation(): Validation = state.validation

    override fun strictContentTypes(): Boolean = state.strictContentTypes

    override fun multipartConfig(): MultipartConfig = state.config.http.multipartConfig

    internal fun update(endpoint: Endpoint, pathParams: Map<String, String>) {
        endpoints.add(endpoint, pathParams)
    }

    internal fun setRouteRoles(roles: Set<RouteRole>) {
        routeRoles = roles
    }

    // ---- request -----------------------------------------------------------------------------------------

    override fun method(): HandlerType {
        cachedMethod?.let {
            return it
        }
        val override = exchange.requestHeaders[Header.X_HTTP_METHOD_OVERRIDE]
        val m = HandlerType.findOrDefault(override?.uppercase() ?: exchange.method)
        cachedMethod = m
        return m
    }

    override fun path(): String = exchange.path

    override fun port(): Int = exchange.localAddress?.port ?: state.config.server.port

    override fun contextPath(): String = state.contextPath.ifEmpty { "/" }

    override fun url(): String = state.resolver.url(this)

    override fun fullUrl(): String = state.resolver.fullUrl(this)

    override fun scheme(): String = state.resolver.scheme(this)

    override fun host(): String? = state.resolver.host(this)

    override fun ip(): String = state.resolver.ip(this)

    override fun remoteIp(): String =
        exchange.remoteAddress?.let { it.address?.hostAddress ?: it.hostString } ?: "127.0.0.1"

    override fun bodyAsBytes(): ByteArray {
        cachedBody?.let {
            return it
        }
        val declared = exchange.requestContentLength
        if (declared > state.maxRequestSize) {
            throw ContentTooLargeResponse("Body is larger than the allowed maximum of ${state.maxRequestSize} bytes")
        }
        val bytes =
            if (!exchange.hasRequestBody) ByteArray(0)
            else {
                val input = exchange.requestBody()
                if (declared >= 0) input.readNBytes(declared.toInt())
                else {
                    val limit = state.maxRequestSize
                    val data = input.readNBytes((limit + 1).coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt())
                    if (data.size > limit) {
                        throw ContentTooLargeResponse("Body is larger than the allowed maximum of $limit bytes")
                    }
                    data
                }
            }
        cachedBody = bytes
        return bytes
    }

    override fun bodyInputStream(): InputStream = cachedBody?.let { ByteArrayInputStream(it) } ?: exchange.requestBody()

    override fun formParamMap(): Map<String, List<String>> {
        cachedForm?.let {
            return it
        }
        val form =
            when {
                isMultipartFormData() -> multipart().fields
                isFormUrlencoded() || !strictContentTypes() ->
                    if (exchange.hasRequestBody) FormParser.parse(body(), charset()) else emptyMap()
                else -> emptyMap()
            }
        cachedForm = form
        return form
    }

    private fun charset() =
        characterEncoding()?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    private fun multipart(): Multipart.Parsed {
        cachedMultipart?.let {
            return it
        }
        val parsed = Multipart.parse(this, multipartConfig())
        cachedMultipart = parsed
        return parsed
    }

    override fun uploadedFileMap(): Map<String, List<UploadedFile>> =
        if (isMultipartFormData()) multipart().files else emptyMap()

    override fun pathParam(key: String): String =
        endpoints.lastPathParams[key.removePrefix("{").removeSuffix("}")]
            ?: throw IllegalArgumentException(
                "'$key' is not a valid path-param for '${endpoints.lastMatchedEndpoint?.path ?: endpoint().path}'."
            )

    override fun pathParamMap(): Map<String, String> = java.util.Collections.unmodifiableMap(endpoints.lastPathParams)

    override fun queryParamMap(): Map<String, List<String>> {
        cachedQuery?.let {
            return it
        }
        val q = exchange.query?.let { FormParser.parse(it, Charsets.UTF_8) } ?: emptyMap()
        cachedQuery = q
        return q
    }

    override fun attribute(key: String, value: Any?) {
        if (value == null) attributes?.remove(key)
        else (attributes ?: HashMap<String, Any?>().also { attributes = it })[key] = value
    }

    @Suppress("UNCHECKED_CAST") override fun <T> attribute(key: String): T? = attributes?.get(key) as T?

    override fun attributeMap(): Map<String, Any?> = attributes?.toMap() ?: emptyMap()

    override fun cookieStore(): CookieStore = cookieStore ?: CookieStore.create(this).also { cookieStore = it }

    override fun cookieMap(): Map<String, String> {
        cachedCookies?.let {
            return it
        }
        val map = LinkedHashMap<String, String>()
        for (header in exchange.requestHeaders.getAll(Header.COOKIE)) {
            for (pair in header.split(';')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim().removeSurrounding("\"")
                if (name.isNotEmpty()) map.putIfAbsent(name, value)
            }
        }
        cachedCookies = map
        return map
    }

    override fun header(header: String): String? = exchange.requestHeaders[header]

    override fun headerMap(): Map<String, String> {
        val headers = exchange.requestHeaders
        val map = LinkedHashMap<String, String>()
        for (i in 0 until headers.size) map.putIfAbsent(headers.name(i), headers.value(i))
        return map
    }

    // ---- response ----------------------------------------------------------------------------------------

    override fun outputStream(): OutputStream = output ?: ResponseOutputStream().also { output = it }

    override fun minSizeForCompression(minSizeForCompression: Int): Context = apply {
        this.minSizeForCompression = minSizeForCompression
    }

    override fun writeSeekableStream(inputStream: InputStream, contentType: String, totalBytes: Long) {
        header(Header.ACCEPT_RANGES, "bytes")
        contentType(contentType)
        disableCompression()
        val range = header(Header.RANGE)
        if (range == null || !range.startsWith("bytes=") || range.contains(',')) {
            result(inputStream)
            resultLength = totalBytes
            return
        }
        val spec = range.substring(6)
        val a = spec.substringBefore('-').trim()
        val b = spec.substringAfter('-').trim()
        val (start, end) =
            if (a.isEmpty()) maxOf(0, totalBytes - (b.toLongOrNull() ?: 0)) to totalBytes - 1
            else (a.toLongOrNull() ?: 0) to minOf(b.toLongOrNull() ?: (totalBytes - 1), totalBytes - 1)
        if (start >= totalBytes || start > end) {
            inputStream.close()
            status(HttpStatus.RANGE_NOT_SATISFIABLE)
            header(Header.CONTENT_RANGE, "bytes */$totalBytes")
            result = null
            return
        }
        inputStream.skipNBytes(start)
        status(HttpStatus.PARTIAL_CONTENT)
        header(Header.CONTENT_RANGE, "bytes $start-$end/$totalBytes")
        result(LimitedInputStream(inputStream, end - start + 1))
        resultLength = end - start + 1
    }

    override fun result(resultBytes: ByteArray): Context = apply {
        closeResultStream()
        result = resultBytes
        resultLength = -1
    }

    override fun result(resultStream: InputStream): Context = apply {
        closeResultStream()
        result = resultStream
        resultLength = -1
    }

    private fun closeResultStream() {
        (result as? InputStream)?.let {
            try {
                it.close()
            } catch (_: Exception) {}
        }
    }

    internal fun resultEmpty() {
        closeResultStream()
        result = null
        resultLength = -1
    }

    internal fun resultResource(resource: Resource, position: Long, count: Long) {
        closeResultStream()
        result = ResourceResult(resource, position, count)
        resultLength = count
    }

    override fun result(): String? =
        when (val r = result) {
            null -> null
            is ByteArray -> String(r, responseCharset())
            is InputStream ->
                if (r.markSupported()) {
                    try {
                        r.mark(Int.MAX_VALUE)
                        String(r.readAllBytes(), responseCharset()).also { r.reset() }
                    } catch (_: IOException) {
                        "resultString unavailable (resultStream couldn't be reset)"
                    }
                } else "resultString unavailable (resultStream couldn't be reset)"
            else -> null
        }

    override fun resultInputStream(): InputStream? =
        when (val r = result) {
            null -> null
            is ByteArray -> ByteArrayInputStream(r).also { result = it }
            is InputStream -> r
            else -> null
        }

    override fun async(config: Consumer<AsyncTaskConfig>, task: Runnable) {
        val cfg = AsyncTaskConfig().also { config.accept(it) }
        future {
            var f: CompletableFuture<Void?> = CompletableFuture.runAsync(task, cfg.executor ?: KalinExecutors.virtual)
            if (cfg.timeout > 0) f = f.orTimeout(cfg.timeout, TimeUnit.MILLISECONDS)
            val onTimeout = cfg.onTimeout
            if (onTimeout != null) {
                f.exceptionally { e ->
                    val cause = if (e is java.util.concurrent.CompletionException) e.cause else e
                    if (cause is TimeoutException) {
                        onTimeout.accept(this)
                        null
                    } else throw e
                }
            } else f
        }
    }

    override fun future(future: Supplier<out CompletableFuture<*>>) {
        check(userFuture == null) { "Cannot override future from the same handler" }
        userFuture = future
    }

    override fun header(name: String, value: String): Context = apply { exchange.responseHeaders[name] = value }

    override fun addHeader(name: String, value: String): Context = apply { exchange.responseHeaders.add(name, value) }

    override fun removeHeader(name: String): Context = apply { exchange.responseHeaders.remove(name) }

    override fun responseHeader(name: String): String? =
        exchange.responseHeaders[name] ?: if (name.equals(Header.CONTENT_TYPE, true)) state.defaultContentType else null

    override fun responseHeaders(name: String): List<String> = exchange.responseHeaders.getAll(name)

    override fun redirect(location: String, status: HttpStatus) {
        header(Header.LOCATION, location).status(status).result("Redirected")
        val current = endpoint().method
        if (current == HandlerType.BEFORE || current == HandlerType.BEFORE_MATCHED) {
            tasks.removeIf { it.skipOnExceptionAndRedirect }
        }
    }

    override fun status(status: Int): Context = apply { exchange.status = status }

    override fun statusCode(): Int = exchange.status

    override fun cookie(cookie: Cookie): Context = apply {
        val headers = exchange.responseHeaders
        val existing = headers.getAll(Header.SET_COOKIE).filterNot { it.startsWith(cookie.name + "=") }
        headers.remove(Header.SET_COOKIE)
        existing.forEach { headers.add(Header.SET_COOKIE, it) }
        headers.add(Header.SET_COOKIE, cookie.toSetCookieHeader())
    }

    override fun writeJsonStream(stream: Stream<*>) {
        minSizeForCompression = 0
        contentType(ContentType.APPLICATION_JSON)
        state.jsonMapper.writeToOutputStream(stream, outputStream())
    }

    override fun render(filePath: String, model: Map<String, Any?>): Context = apply {
        val renderer = state.fileRenderer
        if (responseHeader(Header.CONTENT_TYPE) == state.defaultContentType)
            contentType(ContentType.HTML + ";charset=utf-8")
        if (renderer is StreamingFileRenderer) {
            renderer.render(filePath, model, this, outputStream())
        } else {
            result(renderer.render(filePath, model, this))
        }
    }

    override fun skipRemainingHandlers(): Context = apply { tasks.clear() }

    override fun routeRoles(): Set<RouteRole> = routeRoles

    // ---- lifecycle ---------------------------------------------------------------------------------------

    internal fun executionTimeMs(): Float = if (startNanos == 0L) -1f else (System.nanoTime() - startNanos) / 1_000_000f

    internal fun httpHandler(): ParsedEndpoint? {
        if (!httpHandlerLookedUp) {
            httpHandlerLookedUp = true
            val m = method()
            httpHandler =
                state.router.findFirst(m, requestUri)
                    ?: if (m == HandlerType.HEAD) state.router.findFirst(HandlerType.GET, requestUri) else null
        }
        return httpHandler
    }

    internal fun willMatch(): Boolean {
        if (willMatchState == 0) {
            val m = method()
            val matches =
                httpHandler() != null ||
                    ((m == HandlerType.GET || m == HandlerType.HEAD) &&
                        (state.resourceHandler?.canHandle(this) == true || state.singlePageHandler.canHandle(this)))
            willMatchState = if (matches) 1 else -1
        }
        return willMatchState == 1
    }

    /** Runs queued tasks until done or a future is set, then writes the response. */
    internal fun runTasks() {
        while (userFuture == null) {
            val task = tasks.pollFirst() ?: break
            if (exceptionOccurred && task.skipOnExceptionAndRedirect) continue
            runTask(task.run)
        }
        val supplier = userFuture
        if (supplier != null) {
            userFuture = null
            handleFuture(supplier)
        } else {
            writeResponseAndLog()
        }
    }

    private inline fun <R> runTask(block: () -> R): R? =
        try {
            block()
        } catch (t: Throwable) {
            exceptionOccurred = true
            userFuture = null
            tasks.addFirst(Task(false) { state.router.exceptionMapper.handle(this, t) })
            null
        }

    private fun handleFuture(supplier: Supplier<out CompletableFuture<*>>) {
        val future = runTask { supplier.get() } ?: return runTasks()
        val timeout = state.asyncTimeout
        val timed = if (timeout > 0) future.copy().orTimeout(timeout, TimeUnit.MILLISECONDS) else future
        timed.whenComplete { _, error ->
            val resume = Runnable {
                if (error != null) {
                    val cause = if (error is java.util.concurrent.CompletionException) error.cause ?: error else error
                    if (cause is TimeoutException && timeout > 0 && !future.isDone) {
                        future.cancel(true)
                        status(HttpStatus.INTERNAL_SERVER_ERROR)
                        state.router.errorMapper.handle(statusCode(), this)
                        if (result == null) result("Request timed out")
                    } else {
                        exceptionOccurred = true
                        tasks.addFirst(Task(false) { state.router.exceptionMapper.handle(this, cause) })
                    }
                }
                runTasks()
            }
            if (Thread.currentThread().isVirtual) resume.run() else KalinExecutors.virtual.execute(resume)
        }
    }

    // ---- response writing --------------------------------------------------------------------------------

    private fun writeResponseAndLog() {
        if (responseWritten.getAndSet(true)) return
        try {
            writeResponse()
            if (state.requestLoggers.isNotEmpty()) {
                val ms = executionTimeMs()
                for (logger in state.requestLoggers) {
                    try {
                        logger.handle(this, ms)
                    } catch (e: Exception) {
                        log.log(System.Logger.Level.WARNING, "Request logger failed", e)
                    }
                }
            }
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "Failed to write response: ${e.message}")
            exchange.closeConnectionAfterResponse()
        } catch (t: Throwable) {
            log.log(System.Logger.Level.ERROR, "Exception occurred while writing the response", t)
            exchange.closeConnectionAfterResponse()
        } finally {
            if (!exchange.isCompleted) {
                try {
                    if (!exchange.isCommitted) {
                        exchange.status = 500
                        exchange.send(ByteArray(0))
                    } else {
                        exchange.finish()
                    }
                } catch (_: IOException) {}
            }
        }
    }

    private fun writeResponse() {
        val out = output
        if (out != null) {
            when (val r = result) {
                is ByteArray -> out.write(r)
                is InputStream -> r.use { it.transferTo(out) }
                is ResourceResult ->
                    r.resource.open().use {
                        it.skipNBytes(r.position)
                        it.copyTo(out, r.count)
                    }
            }
            result = null
            out.close()
            return
        }
        if (exchange.isCommitted) {
            exchange.finish()
            return
        }
        when (val r = result) {
            null -> sendBytes(EMPTY, 0)
            is ByteArray -> sendBytes(r, r.size)
            is ByteArrayInputStream -> sendBytes(r.readAllBytes().let { it }, -1)
            is InputStream -> r.use { sendStream(it, resultLength) }
            is ResourceResult -> sendResource(r)
        }
    }

    private fun ensureContentType(bodyLength: Long) {
        if (bodyLength != 0L && exchange.responseHeaders[Header.CONTENT_TYPE] == null) {
            exchange.responseHeaders[Header.CONTENT_TYPE] = state.defaultContentType
        }
    }

    /** True if dynamic compression may apply to a body of [size] bytes (-1 unknown). */
    private fun compressionEligible(size: Long): Boolean {
        if (!state.compression.isEnabled) return false
        if (size >= 0 && size < minSizeForCompression) return false
        if (minSizeForCompression == Int.MAX_VALUE) return false
        val status = exchange.status
        if (status < 200 || status == 204 || status == 304 || status == 206) return false
        if (exchange.responseHeaders.contains(Header.CONTENT_ENCODING)) return false
        return state.compression.allowsCompression(
            exchange.responseHeaders[Header.CONTENT_TYPE] ?: state.defaultContentType
        )
    }

    internal fun compressionFor(contentType: String, size: Long): CompressionType? {
        if (!state.compression.isEnabled || size < minSizeForCompression) return null
        if (!state.compression.allowsCompression(contentType)) return null
        addVaryAcceptEncoding()
        return state.compression.select(header(Header.ACCEPT_ENCODING))
    }

    internal fun compressBytes(type: CompressionType, bytes: ByteArray): ByteArray =
        state.compression.compress(type, bytes)

    internal fun addVaryAcceptEncoding() {
        val vary = exchange.responseHeaders[Header.VARY]
        if (vary == null) exchange.responseHeaders[Header.VARY] = Header.ACCEPT_ENCODING
        else if (!vary.contains(Header.ACCEPT_ENCODING, ignoreCase = true))
            exchange.responseHeaders[Header.VARY] = "$vary, ${Header.ACCEPT_ENCODING}"
    }

    private fun sendBytes(bytes: ByteArray, knownLength: Int) {
        val length = if (knownLength < 0) bytes.size else knownLength
        if (
            state.generateEtags &&
                exchange.status == 200 &&
                (method() == HandlerType.GET || method() == HandlerType.HEAD)
        ) {
            val etag =
                exchange.responseHeaders[Header.ETAG]
                    ?: etagOf(bytes, length).also { exchange.responseHeaders[Header.ETAG] = it }
            val inm = header(Header.IF_NONE_MATCH)
            if (
                inm != null &&
                    inm.split(',').any { it.trim() == etag || it.trim() == "*" || it.trim().removePrefix("W/") == etag }
            ) {
                exchange.status = 304
                exchange.send(EMPTY)
                return
            }
        }
        ensureContentType(length.toLong())
        if (compressionEligible(length.toLong())) {
            addVaryAcceptEncoding()
            val type = state.compression.select(header(Header.ACCEPT_ENCODING))
            if (type != null) {
                val compressed = state.compression.compress(type, bytes, 0, length)
                exchange.responseHeaders[Header.CONTENT_ENCODING] = type.typeName
                exchange.send(compressed)
                return
            }
        }
        exchange.send(bytes, 0, length)
    }

    private fun etagOf(bytes: ByteArray, length: Int): String {
        val crc = CRC32C()
        crc.update(bytes, 0, length)
        return "\"" + java.lang.Long.toHexString(crc.value) + java.lang.Integer.toHexString(length) + "\""
    }

    private fun sendStream(input: InputStream, length: Long) {
        ensureContentType(length)
        val type =
            if (compressionEligible(length))
                state.compression.select(header(Header.ACCEPT_ENCODING)).also { addVaryAcceptEncoding() }
            else null
        if (type != null) {
            exchange.responseHeaders[Header.CONTENT_ENCODING] = type.typeName
            val raw = exchange.startStreaming(-1)
            state.compression.compress(type, raw).use { input.transferTo(it) }
            return
        }
        if (length >= 0) {
            exchange.startStreaming(length).use { out -> input.copyTo(out, length) }
            return
        }
        // Unknown length: buffer the first chunk to send small bodies with Content-Length
        val first = input.readNBytes(state.responseBufferSize)
        if (first.size < state.responseBufferSize) {
            exchange.send(first)
            return
        }
        exchange.startStreaming(-1).use { out ->
            out.write(first)
            input.transferTo(out)
        }
    }

    private fun sendResource(r: ResourceResult) {
        val resource = r.resource
        ensureContentType(r.count)
        val type =
            if (compressionEligible(r.count))
                state.compression.select(header(Header.ACCEPT_ENCODING)).also { addVaryAcceptEncoding() }
            else null
        if (type != null) {
            exchange.responseHeaders[Header.CONTENT_ENCODING] = type.typeName
            val raw = exchange.startStreaming(-1)
            resource.open().use { input ->
                input.skipNBytes(r.position)
                state.compression.compress(type, raw).use { input.copyTo(it, r.count) }
            }
            return
        }
        if (resource is Resource.FileResource) {
            FileChannel.open(resource.path, StandardOpenOption.READ).use { ch ->
                exchange.sendFile(ch, r.position, r.count)
            }
            return
        }
        resource.open().use { input ->
            input.skipNBytes(r.position)
            exchange.startStreaming(r.count).use { out -> input.copyTo(out, r.count) }
        }
    }

    private fun InputStream.copyTo(out: OutputStream, count: Long) {
        val buf = ByteArray(minOf(count, 64 * 1024L).toInt().coerceAtLeast(1))
        var left = count
        while (left > 0) {
            val n = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("Unexpected end of stream")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private class ResourceResult(val resource: Resource, val position: Long, val count: Long)

    /** Buffers small outputs (sent with Content-Length); switches to streaming when the buffer overflows. */
    private inner class ResponseOutputStream : OutputStream() {
        private var buffer: java.io.ByteArrayOutputStream? = java.io.ByteArrayOutputStream(1024)
        private var stream: OutputStream? = null
        private var closed = false

        private fun commit() {
            val buffered = buffer!!
            buffer = null
            ensureContentType(-1)
            val type =
                if (compressionEligible(-1))
                    state.compression.select(header(Header.ACCEPT_ENCODING)).also { addVaryAcceptEncoding() }
                else null
            val raw: OutputStream
            if (type != null) {
                exchange.responseHeaders[Header.CONTENT_ENCODING] = type.typeName
                raw = state.compression.compress(type, exchange.startStreaming(-1), syncFlush = true)
            } else {
                raw = exchange.startStreaming(-1)
            }
            stream = raw
            buffered.writeTo(raw)
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            val buf = buffer
            if (buf != null) {
                if (buf.size() + len <= state.responseBufferSize) {
                    buf.write(b, off, len)
                    return
                }
                commit()
            }
            stream!!.write(b, off, len)
        }

        override fun flush() {
            if (closed) return
            if (buffer != null) commit()
            stream!!.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            val buf = buffer
            if (buf != null) {
                buffer = null
                val bytes = buf.toByteArray()
                sendBytes(bytes, bytes.size)
            } else {
                stream!!.close()
            }
        }
    }

    internal companion object {
        val EMPTY = ByteArray(0)
        val log: System.Logger = System.getLogger("net.tegulis.kalin")
    }
}

/** An input stream limited to [limit] bytes. */
internal class LimitedInputStream(private val input: InputStream, private var limit: Long) : InputStream() {
    override fun read(): Int {
        if (limit <= 0) return -1
        val b = input.read()
        if (b >= 0) limit--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }

    override fun close() = input.close()
}
