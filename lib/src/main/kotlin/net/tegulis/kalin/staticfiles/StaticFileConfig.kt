package net.tegulis.kalin.staticfiles

import java.nio.file.Path
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.security.RouteRole

/** Where static files are loaded from. */
public enum class Location {
    /** From the classpath (for example inside the jar). */
    CLASSPATH,

    /** From the file system. */
    EXTERNAL,
}

/** Custom mime types for static files. */
public class MimeTypesConfig {
    internal val extensionToMimeType = HashMap<String, String>()

    /** Maps [extensions] (without dot) to [mimeType]. */
    public fun add(mimeType: String, vararg extensions: String) {
        for (ext in extensions) extensionToMimeType[ext.lowercase().removePrefix(".")] = mimeType
    }

    /** All custom mappings. */
    public fun getMapping(): Map<String, String> = extensionToMimeType
}

/** Configuration of one static file location. Several can be added; they do not share configuration. */
public class StaticFileConfig {
    /** The URL path the files are hosted on, for example `/assets`. */
    @JvmField public var hostedPath: String = "/"

    /** The directory with the files (a classpath folder or a file system path). */
    @JvmField public var directory: String = "/public"

    /** Where to load the files from. */
    @JvmField public var location: Location = Location.CLASSPATH

    /**
     * Compressed variants of files up to this size are cached in memory (when compression applies). 0 means all sizes,
     * -1 disables caching (files are compressed on the fly every time).
     */
    @JvmField public var precompressMaxSize: Long = 1024 * 1024

    /** Serve `file.gz` instead of `file` when it exists and the client accepts gzip (like nginx `gzip_static`). */
    @JvmField public var precompressedFiles: Boolean = false

    /**
     * Decides whether a path that resolves outside the directory through a symbolic link may be served. Null (the
     * default) denies such aliases.
     */
    @JvmField public var aliasCheck: ((Path) -> Boolean)? = null

    /** Headers added to every static file response. */
    @JvmField public var headers: Map<String, String> = mapOf(Header.CACHE_CONTROL to "max-age=0")

    /** Return true to skip (not serve) the file for this request. */
    @JvmField public var skipFileFunction: (Context) -> Boolean = { false }

    /** Custom mime types. */
    @JvmField public val mimeTypes: MimeTypesConfig = MimeTypesConfig()

    /** Roles of the static files (visible to `beforeMatched` handlers through `ctx.routeRoles()`). */
    @JvmField public var roles: Set<RouteRole> = emptySet()

    /** Welcome files looked up in directories. */
    @JvmField public var welcomeFiles: List<String> = listOf("index.html")

    /** Show an HTML listing for directories without a welcome file (external locations only). */
    @JvmField public var directoryListing: Boolean = false

    /** Serve files and directories whose name starts with a dot. */
    @JvmField public var serveHiddenFiles: Boolean = true

    /** Send `ETag` and `Last-Modified` headers and answer conditional requests with `304`. */
    @JvmField public var conditionalRequests: Boolean = true

    override fun toString(): String =
        "StaticFileConfig(hostedPath=$hostedPath, directory=$directory, location=$location)"
}
