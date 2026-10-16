package net.tegulis.kalin.compression

import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** Gzip compression with a level from 0 to 9. */
public class Gzip @JvmOverloads constructor(public val level: Int = 6) {
    init {
        require(level in 0..9) { "Valid range for compression level is 0 to 9" }
    }
}

/** Deflate (zlib) compression with a level from 0 to 9. */
public class Deflate @JvmOverloads constructor(public val level: Int = 6) {
    init {
        require(level in 0..9) { "Valid range for compression level is 0 to 9" }
    }
}

/** The supported content encodings. */
public enum class CompressionType(
    /** The `Content-Encoding` token. */
    public val typeName: String
) {
    GZIP("gzip"),
    DEFLATE("deflate"),
}

/** Response compression settings: which encodings are enabled and when they apply. */
public class CompressionStrategy
@JvmOverloads
constructor(public val gzip: Gzip? = null, public val deflate: Deflate? = null) {
    /** Responses smaller than this are not compressed. */
    public var defaultMinSizeForCompression: Int = 1500

    /** Mime type prefixes that are never compressed (already compressed formats). */
    public val excludedMimeTypes: MutableList<String> =
        mutableListOf(
            "image/",
            "audio/",
            "video/",
            "font/woff",
            "application/compress",
            "application/zip",
            "application/gzip",
            "application/x-bzip",
            "application/brotli",
            "application/x-xz",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/java-archive",
            "application/wasm",
            "application/pdf",
            "text/event-stream",
        )

    /** True if any encoding is enabled. */
    public val isEnabled: Boolean
        get() = gzip != null || deflate != null

    /** Returns true if responses of [contentType] may be compressed. */
    public fun allowsCompression(contentType: String?): Boolean {
        if (contentType == null) return true
        val type = contentType.substringBefore(';').trim().lowercase()
        if (type == "image/svg+xml") return true
        return excludedMimeTypes.none { type.startsWith(it) }
    }

    /** Picks the encoding for an `Accept-Encoding` header, or null. */
    public fun select(acceptEncoding: String?): CompressionType? {
        if (acceptEncoding == null || !isEnabled) return null
        var gzipQ = -1.0
        var deflateQ = -1.0
        var starQ = -1.0
        for (part in acceptEncoding.split(',')) {
            val token = part.substringBefore(';').trim().lowercase()
            val q = qValue(part)
            when (token) {
                "gzip",
                "x-gzip" -> gzipQ = maxOf(gzipQ, q)
                "deflate" -> deflateQ = maxOf(deflateQ, q)
                "*" -> starQ = q
            }
        }
        if (gzipQ < 0) gzipQ = starQ
        if (deflateQ < 0) deflateQ = starQ
        return when {
            gzip != null && gzipQ > 0 && (deflate == null || gzipQ >= deflateQ) -> CompressionType.GZIP
            deflate != null && deflateQ > 0 -> CompressionType.DEFLATE
            gzip != null && gzipQ > 0 -> CompressionType.GZIP
            else -> null
        }
    }

    private fun qValue(part: String): Double {
        val params = part.split(';').drop(1)
        for (p in params) {
            val (k, v) = p.split('=', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            if (k.equals("q", ignoreCase = true)) return v.toDoubleOrNull() ?: 0.0
        }
        return 1.0
    }

    /** Wraps [out] in a compressing stream for [type]. */
    public fun compress(type: CompressionType, out: OutputStream, syncFlush: Boolean = false): OutputStream =
        when (type) {
            CompressionType.GZIP ->
                object : GZIPOutputStream(out, 8192, syncFlush) {
                    init {
                        def.setLevel(gzip?.level ?: 6)
                    }
                }
            CompressionType.DEFLATE -> EndingDeflaterOutputStream(out, deflate?.level ?: 6, syncFlush)
        }

    /** Compresses a complete body. */
    public fun compress(type: CompressionType, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): ByteArray {
        val bos = java.io.ByteArrayOutputStream(maxOf(64, length / 3))
        compress(type, bos).use { it.write(bytes, offset, length) }
        return bos.toByteArray()
    }

    public companion object {
        /** No compression. */
        @JvmField public val NONE: CompressionStrategy = CompressionStrategy()

        /** Gzip at level 6 (the default). */
        @JvmField public val GZIP: CompressionStrategy = CompressionStrategy(Gzip())

        /** Gzip and Deflate at level 6. */
        @JvmField public val GZIP_AND_DEFLATE: CompressionStrategy = CompressionStrategy(Gzip(), Deflate())
    }
}

/** A [DeflaterOutputStream] that frees its own [Deflater]'s native memory on close, even when closing fails. */
internal class EndingDeflaterOutputStream(out: OutputStream, level: Int, syncFlush: Boolean) :
    DeflaterOutputStream(out, Deflater(level), 8192, syncFlush) {
    internal val deflater: Deflater
        get() = def

    override fun close() {
        try {
            super.close()
        } finally {
            def.end()
        }
    }
}
