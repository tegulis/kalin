package net.tegulis.kalin.http

import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.stream.Stream
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import net.tegulis.kalin.async.AsyncTaskConfig
import net.tegulis.kalin.config.Key
import net.tegulis.kalin.config.MultipartConfig
import net.tegulis.kalin.engine.Exchange
import net.tegulis.kalin.json.JsonMapper
import net.tegulis.kalin.plugin.ContextPlugin
import net.tegulis.kalin.router.Endpoint
import net.tegulis.kalin.router.Endpoints
import net.tegulis.kalin.security.BasicAuthCredentials
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.util.CookieStore
import net.tegulis.kalin.util.FormParser
import net.tegulis.kalin.validation.BodyValidator
import net.tegulis.kalin.validation.Validation
import net.tegulis.kalin.validation.Validator

/**
 * Everything a handler needs for one request: request getters, response setters and lifecycle helpers. The API follows
 * Javalin's `Context`; servlet objects are replaced by [exchange].
 */
public abstract class Context {
    // ---------------------------------------------------------------------------------------------------------
    // Framework
    // ---------------------------------------------------------------------------------------------------------

    /** The raw engine exchange of this request. */
    public abstract fun exchange(): Exchange

    /** The endpoints visited so far. */
    public abstract fun endpoints(): Endpoints

    /** The endpoint currently executing. */
    public fun endpoint(): Endpoint = endpoints().current()

    /** Returns app data registered with `config.appData(key, value)`. */
    public abstract fun <T> appData(key: Key<T>): T

    /** The configured JSON mapper. */
    public abstract fun jsonMapper(): JsonMapper

    /** Returns the extension of the [ContextPlugin] of type [clazz]. */
    public abstract fun <T> with(clazz: Class<out ContextPlugin<*, T>>): T

    /** Returns the extension of the [ContextPlugin] of type [clazz]. */
    public fun <T> with(clazz: kotlin.reflect.KClass<out ContextPlugin<*, T>>): T = with(clazz.java)

    /** The validation support (converters) of this app. */
    public abstract fun validation(): Validation

    /** True if `config.http.strictContentTypes` is set. */
    public abstract fun strictContentTypes(): Boolean

    /** The multipart configuration. */
    public abstract fun multipartConfig(): MultipartConfig

    // ---------------------------------------------------------------------------------------------------------
    // Request
    // ---------------------------------------------------------------------------------------------------------

    /** The request body length, or -1 if unknown. */
    public open fun contentLength(): Int =
        exchange().requestContentLength.let { if (it > Int.MAX_VALUE) -1 else it.toInt() }

    /** The request `Content-Type`. */
    public fun contentType(): String? = header(Header.CONTENT_TYPE)

    /** The request method (honors `X-HTTP-Method-Override`). */
    public abstract fun method(): HandlerType

    /** The raw request path, including the context path. */
    public abstract fun path(): String

    /** The local port the request was received on. */
    public abstract fun port(): Int

    /** The protocol, `HTTP/1.1` or `HTTP/1.0`. */
    public fun protocol(): String = exchange().protocol

    /** The context path. */
    public abstract fun contextPath(): String

    /** The `User-Agent` header. */
    public fun userAgent(): String? = header(Header.USER_AGENT)

    /** The charset of the request body (from `Content-Type`), or null. */
    public open fun characterEncoding(): String? =
        header(Header.CONTENT_TYPE)?.let { value ->
            value
                .split(';')
                .find { it.trim().startsWith("charset", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.removeSurrounding("\"")
        }

    /** The request URL (scheme, host and path). */
    public abstract fun url(): String

    /** The request URL including the query string. */
    public abstract fun fullUrl(): String

    /** The scheme, `http` unless a proxy says otherwise (see `config.contextResolver`). */
    public abstract fun scheme(): String

    /** The host the client asked for. */
    public abstract fun host(): String?

    /** The client IP (see `config.contextResolver`). */
    public abstract fun ip(): String

    /** The IP of the directly connected peer. */
    public abstract fun remoteIp(): String

    /** The request body as a string (decoded with the request charset, default UTF-8). */
    public open fun body(): String =
        String(
            bodyAsBytes(),
            runCatching { Charset.forName(characterEncoding() ?: "UTF-8") }.getOrDefault(Charsets.UTF_8),
        )

    /** The request body as bytes, read once and cached. Limited by `config.http.maxRequestSize`. */
    public abstract fun bodyAsBytes(): ByteArray

    /** Deserializes the JSON body to [type]. */
    public open fun <T> bodyAsClass(type: Type): T {
        checkJsonContentType()
        return jsonMapper().fromJsonString(body(), type)
    }

    /** Deserializes the JSON body to [clazz]. */
    public fun <T : Any> bodyAsClass(clazz: Class<T>): T = bodyAsClass(clazz as Type)

    /** Deserializes the JSON body to the Kotlin type [type]. */
    public open fun <T> bodyAsClass(type: KType): T {
        checkJsonContentType()
        return jsonMapper().fromJsonString(body(), type)
    }

    /** Deserializes the JSON body to [T]. */
    public inline fun <reified T> bodyAsClass(): T = bodyAsClass(typeOf<T>())

    /** Deserializes the JSON body from the request stream (no intermediate string). */
    public open fun <T> bodyStreamAsClass(type: Type): T {
        checkJsonContentType()
        return jsonMapper().fromJsonStream(bodyInputStream(), type)
    }

    /** Deserializes the JSON body from the request stream. */
    public open fun <T> bodyStreamAsClass(type: KType): T {
        checkJsonContentType()
        return jsonMapper().fromJsonStream(bodyInputStream(), type)
    }

    /** Deserializes the JSON body from the request stream. */
    public inline fun <reified T> bodyStreamAsClass(): T = bodyStreamAsClass(typeOf<T>())

    /** Throws `400` if strict content types are enabled and the request is not JSON. */
    protected fun checkJsonContentType() {
        if (strictContentTypes() && !isJson()) throw BadRequestResponse("Content-Type is not application/json")
    }

    /** The request body stream (not limited by `maxRequestSize`). */
    public abstract fun bodyInputStream(): InputStream

    /** A validator for the JSON body. */
    public fun <T : Any> bodyValidator(clazz: Class<T>): BodyValidator<T> = BodyValidator(body()) { bodyAsClass(clazz) }

    /** A validator for the JSON body. */
    public inline fun <reified T : Any> bodyValidator(): BodyValidator<T> = BodyValidator(body()) { bodyAsClass<T>() }

    /** The first form parameter named [key]. */
    public fun formParam(key: String): String? = formParams(key).firstOrNull()

    /** A validator for the form parameter [key]. */
    public fun <T : Any> formParamAsClass(key: String, clazz: Class<T>): Validator<T> =
        validation().validator(key, clazz, formParam(key))

    /** A validator for the form parameter [key]. */
    public inline fun <reified T : Any> formParamAsClass(key: String): Validator<T> =
        formParamAsClass(key, T::class.java)

    /** All form parameters named [key]. */
    public fun formParams(key: String): List<String> = formParamMap()[key] ?: emptyList()

    /** A validator for all values of the form parameter [key]. */
    public fun <T : Any> formParamsAsClass(key: String, clazz: Class<T>): Validator<List<T>> =
        validation().listValidator(key, clazz, formParams(key))

    /** A validator for all values of the form parameter [key]. */
    public inline fun <reified T : Any> formParamsAsClass(key: String): Validator<List<T>> =
        formParamsAsClass(key, T::class.java)

    /** All form parameters (URL-encoded or multipart fields). */
    public abstract fun formParamMap(): Map<String, List<String>>

    /** The path parameter [key]; throws if the matched path has no such parameter. */
    public abstract fun pathParam(key: String): String

    /** A validator for the path parameter [key]. */
    public fun <T : Any> pathParamAsClass(key: String, clazz: Class<T>): Validator<T> =
        validation().validator(key, clazz, pathParam(key))

    /** A validator for the path parameter [key]. */
    public inline fun <reified T : Any> pathParamAsClass(key: String): Validator<T> =
        pathParamAsClass(key, T::class.java)

    /** All path parameters. */
    public abstract fun pathParamMap(): Map<String, String>

    /** The first query parameter named [key]. */
    public fun queryParam(key: String): String? = queryParams(key).firstOrNull()

    /** A validator for the query parameter [key]. */
    public fun <T : Any> queryParamAsClass(key: String, clazz: Class<T>): Validator<T> =
        validation().validator(key, clazz, queryParam(key))

    /** A validator for the query parameter [key]. */
    public inline fun <reified T : Any> queryParamAsClass(key: String): Validator<T> =
        queryParamAsClass(key, T::class.java)

    /** All query parameters named [key]. */
    public fun queryParams(key: String): List<String> = queryParamMap()[key] ?: emptyList()

    /** A validator for all values of the query parameter [key]. */
    public fun <T : Any> queryParamsAsClass(key: String, clazz: Class<T>): Validator<List<T>> =
        validation().listValidator(key, clazz, queryParams(key))

    /** A validator for all values of the query parameter [key]. */
    public inline fun <reified T : Any> queryParamsAsClass(key: String): Validator<List<T>> =
        queryParamsAsClass(key, T::class.java)

    /** All query parameters. */
    public abstract fun queryParamMap(): Map<String, List<String>>

    /** The raw query string, or null. */
    public fun queryString(): String? = exchange().query

    /** Sets a request attribute; null removes it. */
    public abstract fun attribute(key: String, value: Any?)

    /** Returns a request attribute. */
    public abstract fun <T> attribute(key: String): T?

    /** Returns the attribute [key], computing and storing it with [callback] if absent. */
    public fun <T> attributeOrCompute(key: String, callback: (Context) -> T): T? {
        if (attribute<T>(key) == null) attribute(key, callback(this))
        return attribute(key)
    }

    /** All request attributes. */
    public abstract fun attributeMap(): Map<String, Any?>

    /** A map stored in a cookie, shared between requests and servers. */
    public abstract fun cookieStore(): CookieStore

    /** The request cookie [name]. */
    public fun cookie(name: String): String? = cookieMap()[name]

    /** All request cookies. */
    public abstract fun cookieMap(): Map<String, String>

    /** The request header [header] (case-insensitive). */
    public abstract fun header(header: String): String?

    /** A validator for the request header [header]. */
    public fun <T : Any> headerAsClass(header: String, clazz: Class<T>): Validator<T> =
        validation().validator(header, clazz, header(header))

    /** A validator for the request header [header]. */
    public inline fun <reified T : Any> headerAsClass(header: String): Validator<T> =
        headerAsClass(header, T::class.java)

    /** All request headers (first value per name). */
    public abstract fun headerMap(): Map<String, String>

    /** Credentials from an `Authorization: Basic` header, or null. */
    public fun basicAuthCredentials(): BasicAuthCredentials? {
        val auth = header(Header.AUTHORIZATION) ?: return null
        if (!auth.startsWith("Basic ", ignoreCase = true)) return null
        val decoded =
            try {
                Base64.getDecoder().decode(auth.substring(6).trim()).decodeToString()
            } catch (_: IllegalArgumentException) {
                return null
            }
        val colon = decoded.indexOf(':')
        if (colon < 0) return null
        return BasicAuthCredentials(decoded.substring(0, colon), decoded.substring(colon + 1))
    }

    /** True if the request is `multipart/...`. */
    public fun isMultipart(): Boolean = header(Header.CONTENT_TYPE)?.startsWith("multipart/", ignoreCase = true) == true

    /** True if the request is `multipart/form-data`. */
    public fun isMultipartFormData(): Boolean =
        header(Header.CONTENT_TYPE)?.startsWith("multipart/form-data", ignoreCase = true) == true

    /** True if the request is `application/x-www-form-urlencoded`. */
    public fun isFormUrlencoded(): Boolean =
        header(Header.CONTENT_TYPE)?.startsWith(ContentType.FORM_URLENCODED, ignoreCase = true) == true

    /** True if the request is `application/json`. */
    public fun isJson(): Boolean = header(Header.CONTENT_TYPE)?.startsWith(ContentType.JSON, ignoreCase = true) == true

    /** The first uploaded file named [fileName]. */
    public fun uploadedFile(fileName: String): UploadedFile? = uploadedFiles(fileName).firstOrNull()

    /** All uploaded files named [fileName]. */
    public fun uploadedFiles(fileName: String): List<UploadedFile> = uploadedFileMap()[fileName] ?: emptyList()

    /** All uploaded files. */
    public fun uploadedFiles(): List<UploadedFile> = uploadedFileMap().values.flatten()

    /** All uploaded files by field name. */
    public abstract fun uploadedFileMap(): Map<String, List<UploadedFile>>

    // ---------------------------------------------------------------------------------------------------------
    // Response
    // ---------------------------------------------------------------------------------------------------------

    /** The response charset (from the response `Content-Type`, default UTF-8). */
    public fun responseCharset(): Charset =
        responseHeader(Header.CONTENT_TYPE)
            ?.split(';')
            ?.find { it.trim().startsWith("charset", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

    /**
     * A stream to write the response body directly. Small outputs are buffered and sent with a `Content-Length`; large
     * ones are streamed (chunked). The response is committed when the buffer overflows or the request ends.
     */
    public abstract fun outputStream(): OutputStream

    /** Sets the minimum response size for compression. */
    public abstract fun minSizeForCompression(minSizeForCompression: Int): Context

    /** Disables compression for this response. */
    public fun disableCompression(): Context = minSizeForCompression(Int.MAX_VALUE)

    /** Writes [inputStream] supporting `Range` requests (audio/video seeking). */
    public abstract fun writeSeekableStream(inputStream: InputStream, contentType: String, totalBytes: Long)

    /** Writes [inputStream] supporting `Range` requests. */
    public fun writeSeekableStream(inputStream: InputStream, contentType: String): Unit =
        writeSeekableStream(inputStream, contentType, inputStream.available().toLong())

    /** Sets the result to a string, encoded with the response charset. */
    public open fun result(resultString: String): Context = result(resultString.toByteArray(responseCharset()))

    /** Sets the result to bytes. */
    public abstract fun result(resultBytes: ByteArray): Context

    /** Sets the result to a stream (closed after writing). */
    public abstract fun result(resultStream: InputStream): Context

    /** Returns the current result as a string, if possible. */
    public abstract fun result(): String?

    /** Returns the current result stream. */
    public abstract fun resultInputStream(): InputStream?

    /** Runs [task] asynchronously; the request continues (after handlers, logging) when it finishes. */
    public abstract fun async(config: Consumer<AsyncTaskConfig>, task: Runnable)

    /** Runs [task] asynchronously on a virtual thread. */
    public fun async(task: Runnable): Unit = async({}, task)

    /** Sets a future result; the pipeline waits for it before running after handlers. */
    public abstract fun future(future: Supplier<out CompletableFuture<*>>)

    /** Sets the response `Content-Type`. */
    public fun contentType(contentType: String): Context = header(Header.CONTENT_TYPE, contentType)

    /** Sets the response `Content-Type`. */
    public fun contentType(contentType: ContentType): Context = contentType(contentType.mimeType)

    /** Sets a response header, replacing existing values. */
    public abstract fun header(name: String, value: String): Context

    /** Adds a response header value. */
    public abstract fun addHeader(name: String, value: String): Context

    /** Removes a response header. */
    public abstract fun removeHeader(name: String): Context

    /** Returns a response header. */
    public abstract fun responseHeader(name: String): String?

    /** Returns all values of a response header. */
    public abstract fun responseHeaders(name: String): List<String>

    /** Redirects to [location] with [status]. In before handlers, this skips the remaining before/endpoint handlers. */
    public abstract fun redirect(location: String, status: HttpStatus)

    /** Redirects to [location] with `302 Found`. */
    public fun redirect(location: String): Unit = redirect(location, HttpStatus.FOUND)

    /** Sets the response status. */
    public fun status(status: HttpStatus): Context = status(status.code)

    /** Sets the response status code. */
    public abstract fun status(status: Int): Context

    /** The response status. */
    public fun status(): HttpStatus = HttpStatus.forStatus(statusCode())

    /** The response status code. */
    public abstract fun statusCode(): Int

    /** Sets a session cookie. */
    public fun cookie(name: String, value: String): Context = cookie(name, value, -1)

    /** Sets a cookie with a max age in seconds. */
    public fun cookie(name: String, value: String, maxAge: Int): Context =
        cookie(Cookie(name = name, value = value, maxAge = maxAge))

    /** Sets a cookie. A cookie with the same name set earlier in this response is replaced. */
    public abstract fun cookie(cookie: Cookie): Context

    /** Removes the cookie [name] at [path]. */
    public fun removeCookie(name: String, path: String?): Context =
        cookie(Cookie(name = name, value = "", path = path ?: "/", maxAge = 0))

    /** Removes the cookie [name] at `/`. */
    public fun removeCookie(name: String): Context = removeCookie(name, "/")

    /** Serializes [obj] as JSON of [type] and sets the JSON content type. */
    public open fun json(obj: Any?, type: KType): Context =
        contentType(ContentType.APPLICATION_JSON).result(jsonMapper().toJsonString(obj, type))

    /** Serializes [obj] as JSON of the Java [type]. */
    public open fun json(obj: Any, type: Type): Context =
        contentType(ContentType.APPLICATION_JSON).result(jsonMapper().toJsonString(obj, type))

    /** Serializes [obj] as JSON, using its static type [T] (works for generic collections). */
    public inline fun <reified T> json(obj: T): Context = json(obj, typeOf<T>())

    /** Serializes [obj] to a JSON stream (no intermediate string). */
    public open fun jsonStream(obj: Any?, type: KType): Context =
        contentType(ContentType.APPLICATION_JSON).result(jsonMapper().toJsonStream(obj, type))

    /** Serializes [obj] to a JSON stream. */
    public inline fun <reified T> jsonStream(obj: T): Context = jsonStream(obj, typeOf<T>())

    /** Writes a stream of objects as a JSON array directly to the response. */
    public abstract fun writeJsonStream(stream: Stream<*>)

    /** Sets an HTML result. */
    public fun html(html: String): Context = contentType(ContentType.TEXT_HTML).result(html)

    /** Renders a template with the configured `FileRenderer` and sets it as HTML result. */
    public abstract fun render(filePath: String, model: Map<String, Any?>): Context

    /** Renders a template without a model. */
    public fun render(filePath: String): Context = render(filePath, emptyMap())

    // ---------------------------------------------------------------------------------------------------------
    // Other
    // ---------------------------------------------------------------------------------------------------------

    /** Skips all remaining handlers (including after handlers) for this request. */
    public abstract fun skipRemainingHandlers(): Context

    /** The roles of the matched endpoint. */
    public abstract fun routeRoles(): Set<RouteRole>

    internal companion object {
        fun parseForm(body: String, charset: Charset): Map<String, List<String>> = FormParser.parse(body, charset)
    }
}

/** An uploaded file from a multipart request. */
public interface UploadedFile {
    /** The form field name. */
    public fun fieldName(): String

    /** The original file name. */
    public fun filename(): String

    /** The content type sent by the client. */
    public fun contentType(): String?

    /** The size in bytes. */
    public fun size(): Long

    /** The file extension including the dot, or empty. */
    public fun extension(): String = filename().substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }

    /** The content as a stream. */
    public fun content(): InputStream

    /** The content as bytes. */
    public fun contentAsBytes(): ByteArray = content().use { it.readAllBytes() }

    /** Headers of the part. */
    public fun headers(): Map<String, List<String>>
}
