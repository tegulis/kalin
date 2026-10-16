package net.tegulis.kalin.config

import net.tegulis.kalin.compression.CompressionStrategy
import net.tegulis.kalin.http.ContentType

/** HTTP layer configuration. */
public class HttpConfig {
    /** Generate `ETag`s for dynamic GET responses and answer `304` when `If-None-Match` matches. */
    @JvmField public var generateEtags: Boolean = false

    /** Answer `405 Method Not Allowed` instead of `404` when the path exists for other methods. */
    @JvmField public var prefer405over404: Boolean = false

    /** The maximum request body size that `ctx.body()` and friends read into memory. */
    @JvmField public var maxRequestSize: Long = 1_000_000L

    /** The response buffer size used when copying result streams; null uses 32 KB. */
    @JvmField public var responseBufferSize: Int? = null

    /** The default response content type. */
    @JvmField public var defaultContentType: String = ContentType.PLAIN

    /** Timeout in milliseconds for `ctx.future { }` results; 0 disables it. */
    @JvmField public var asyncTimeout: Long = 0L

    /** Require `Content-Type: application/json` for `bodyAsClass` and form content types for `formParam`. */
    @JvmField public var strictContentTypes: Boolean = false

    /**
     * Honor the `X-HTTP-Method-Override` header on `POST` requests, so clients that can only send `GET` and `POST` can
     * reach `PUT`, `PATCH` and `DELETE` routes. Requests with any other method are never overridden. Set to false to
     * ignore the header entirely.
     */
    @JvmField public var methodOverride: Boolean = true

    /**
     * Let the default `HttpResponseException` answer be `text/html` when the client accepts HTML and the exception
     * message contains an HTML tag, for example `throw ForbiddenResponse("<b>No access</b>")`. By default (false) such
     * clients get `text/plain`; JSON clients get JSON either way.
     *
     * **Warning:** the message and details are sent unescaped. Never interpolate request data (paths, parameters,
     * headers, bodies) into exception messages when this is enabled, or you open a reflected XSS hole. Kalin's own
     * "Endpoint ... not found" message contains the request path and is therefore always sent as plain text.
     */
    @JvmField public var htmlErrorMessages: Boolean = false

    /** Response compression. Gzip and Deflate are supported. */
    @JvmField public var compressionStrategy: CompressionStrategy = CompressionStrategy.GZIP

    /** Limits and storage for multipart uploads. */
    @JvmField public val multipartConfig: MultipartConfig = MultipartConfig()
}

/** Multipart upload configuration. */
public class MultipartConfig {
    /** Directory for uploaded files larger than [maxInMemoryFileSize]; null uses the system temp directory. */
    public var cacheDirectory: String? = null

    /** The maximum size of one uploaded file, -1 for unlimited. */
    public var maxFileSize: Long = -1

    /**
     * Files up to this size are kept in memory. The file parts of one request share an in-memory budget of four times
     * this size; once it is used up, further files spill to [cacheDirectory] regardless of their size.
     */
    public var maxInMemoryFileSize: Long = 1024 * 1024

    /**
     * The maximum size of a complete multipart request. -1 (the default) falls back to [HttpConfig.maxRequestSize], so
     * the effective default is 1 MB. Larger requests are answered with `413 Content Too Large`.
     */
    public var maxTotalRequestSize: Long = -1

    /**
     * The maximum number of parts (fields and files) in one multipart request. Requests with more parts are answered
     * with `413 Content Too Large`.
     */
    public var maxParts: Int = 1000

    internal fun maxInMemoryTotalSize(): Long =
        if (maxInMemoryFileSize > Long.MAX_VALUE / 4) Long.MAX_VALUE else maxInMemoryFileSize * 4

    /** Sets [cacheDirectory]. */
    public fun cacheDirectory(path: String): MultipartConfig = apply { cacheDirectory = path }

    /** Sets [maxFileSize]. */
    public fun maxFileSize(size: Long, unit: SizeUnit): MultipartConfig = apply { maxFileSize = size * unit.multiplier }

    /** Sets [maxInMemoryFileSize]. */
    public fun maxInMemoryFileSize(size: Long, unit: SizeUnit): MultipartConfig = apply {
        maxInMemoryFileSize = size * unit.multiplier
    }

    /** Sets [maxTotalRequestSize]. */
    public fun maxTotalRequestSize(size: Long, unit: SizeUnit): MultipartConfig = apply {
        maxTotalRequestSize = size * unit.multiplier
    }

    /** Sets [maxParts]. */
    public fun maxParts(count: Int): MultipartConfig = apply { maxParts = count }
}

/** Size units for [MultipartConfig]. */
public enum class SizeUnit(
    /** Bytes per unit. */
    public val multiplier: Long
) {
    BYTES(1),
    KB(1024),
    MB(1024 * 1024),
    GB(1024 * 1024 * 1024),
}
