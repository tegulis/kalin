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

    /** Files up to this size are kept in memory. */
    public var maxInMemoryFileSize: Long = 1024 * 1024

    /** The maximum size of a complete multipart request, -1 for unlimited. */
    public var maxTotalRequestSize: Long = -1

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
