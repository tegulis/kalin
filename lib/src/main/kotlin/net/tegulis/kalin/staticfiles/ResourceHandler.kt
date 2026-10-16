package net.tegulis.kalin.staticfiles

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import net.tegulis.kalin.compression.CompressionType
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.http.KalinContext
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.util.HttpDates

/** Serves static resources. Runs after endpoint matching for GET and HEAD requests without an endpoint. */
public interface ResourceHandler {
    /** True if [ctx] would be answered by this handler (used for `beforeMatched`). */
    public fun canHandle(ctx: Context): Boolean

    /** Answers [ctx] if a resource matches; returns false otherwise. */
    public fun handle(ctx: Context): Boolean

    /** Adds a static file location. */
    public fun addStaticFileConfig(config: StaticFileConfig): Boolean

    /** The roles of the resource matching [ctx]. */
    public fun resourceRouteRoles(ctx: Context): Set<RouteRole>
}

/** A resolved static resource. */
internal sealed class Resource(val name: String, val size: Long, val lastModified: Long) {
    class FileResource(val path: Path, size: Long, lastModified: Long) :
        Resource(path.fileName?.toString() ?: "", size, lastModified)

    class UrlResource(val url: URL, name: String, size: Long, lastModified: Long) : Resource(name, size, lastModified)

    class Directory(val path: Path) : Resource(path.fileName?.toString() ?: "", 0, 0)

    /** A directory requested without trailing slash. */
    class DirectoryRedirect(val location: String) : Resource("", 0, 0)

    fun open(): InputStream =
        when (this) {
            is FileResource -> Files.newInputStream(path)
            is UrlResource -> url.openStream()
            else -> throw IOException("Not a file")
        }
}

/** Kalin's static file handler (no Jetty). */
public class KalinStaticResourceHandler : ResourceHandler {
    private val handlers = ArrayList<StaticLocation>()

    override fun addStaticFileConfig(config: StaticFileConfig): Boolean {
        handlers.add(StaticLocation(config))
        return true
    }

    override fun canHandle(ctx: Context): Boolean = handlers.any { it.resolve(ctx) != null }

    override fun handle(ctx: Context): Boolean {
        for (h in handlers) {
            val resource = h.resolve(ctx) ?: continue
            h.serve(ctx as KalinContext, resource)
            return true
        }
        return false
    }

    override fun resourceRouteRoles(ctx: Context): Set<RouteRole> =
        handlers.firstOrNull { it.resolve(ctx) != null }?.config?.roles ?: emptySet()
}

internal class StaticLocation(val config: StaticFileConfig) {
    private val hostedPath: String = "/" + config.hostedPath.trim('/')
    private val root: Path? =
        if (config.location == Location.EXTERNAL) Paths.get(config.directory).toAbsolutePath().normalize() else null
    private val realRoot: Path? = root?.let {
        try {
            it.toRealPath()
        } catch (_: IOException) {
            null
        }
    }
    /** [root] with the file system's real names (Windows long names) but symbolic links kept. */
    private val namedRoot: Path? = root?.let {
        try {
            it.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            null
        }
    }
    private val classpathRoot: String = config.directory.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    private val cache = CompressedCache

    init {
        if (config.location == Location.EXTERNAL && (root == null || !Files.isDirectory(root))) {
            throw IllegalArgumentException("Static resource directory with path: '${config.directory}' does not exist.")
        }
    }

    /** The path below [hostedPath], or null if the request is not for this location. */
    private fun relativePath(ctx: Context): String? {
        val requestPath = (ctx as KalinContext).requestUri()
        val rel =
            when {
                hostedPath == "/" -> requestPath
                requestPath == hostedPath -> "/"
                requestPath.startsWith("$hostedPath/") -> requestPath.substring(hostedPath.length)
                else -> return null
            }
        val decoded =
            try {
                URLDecoder.decode(rel.replace("+", "%2B"), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (decoded.indexOf('\u0000') >= 0 || decoded.indexOf('\\') >= 0) return null
        val segments = decoded.split('/')
        for (s in segments) {
            if (s == ".." || s == ".") return null
            if (!config.serveHiddenFiles && s.startsWith(".")) return null
        }
        return decoded
    }

    fun resolve(ctx: Context): Resource? {
        if (config.skipFileFunction(ctx)) return null
        val rel = relativePath(ctx) ?: return null
        return if (root != null) resolveExternal(ctx, rel) else resolveClasspath(rel)
    }

    private fun resolveExternal(ctx: Context, rel: String): Resource? {
        val root = root!!
        val file = resolveBelow(root, rel) ?: return null
        val attrs =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java)
            } catch (_: IOException) {
                return null
            }
        if (!aliasAllowed(file) || hiddenByRealName(file)) return null
        if (attrs.isDirectory) {
            if (!rel.endsWith("/") && rel.isNotEmpty()) {
                val q = ctx.queryString()?.let { "?$it" } ?: ""
                // One leading slash only: "//host/" in Location is a link to another host
                val path = "/" + (ctx as KalinContext).path().trimStart('/')
                return Resource.DirectoryRedirect("$path/$q")
            }
            for (welcome in config.welcomeFiles) {
                val w = file.resolve(welcome)
                if (Files.isRegularFile(w) && aliasAllowed(w) && !hiddenByRealName(w)) {
                    val a = Files.readAttributes(w, BasicFileAttributes::class.java)
                    return Resource.FileResource(w, a.size(), a.lastModifiedTime().toMillis())
                }
            }
            return if (config.directoryListing) Resource.Directory(file) else null
        }
        if (!attrs.isRegularFile || !Files.isReadable(file)) return null
        return Resource.FileResource(file, attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    private fun aliasAllowed(file: Path): Boolean {
        val realRoot = realRoot ?: return false
        val real =
            try {
                file.toRealPath()
            } catch (_: IOException) {
                return false
            }
        if (real.startsWith(realRoot)) return true
        return config.aliasCheck?.invoke(file) ?: false
    }

    /**
     * True if hidden files are not served and a segment of [file]'s real name below the root starts with a dot. The
     * request path was checked already, but on Windows an 8.3 short name (`HTACCE~1`) can name `.htaccess`.
     */
    private fun hiddenByRealName(file: Path): Boolean {
        if (config.serveHiddenFiles) return false
        val namedRoot = namedRoot ?: return true
        val named =
            try {
                file.toRealPath(LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return true
            }
        if (!named.startsWith(namedRoot)) return true
        return namedRoot.relativize(named).any { it.toString().startsWith(".") }
    }

    private fun resolveClasspath(rel: String): Resource? {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val base = rel.trimStart('/')
        val candidates =
            if (base.isEmpty() || base.endsWith("/")) config.welcomeFiles.map { classpathRoot + base + it }
            else listOf(classpathRoot + base)
        for (name in candidates) {
            val url = loader.getResource(name) ?: continue
            if (url.protocol == "file") {
                val p =
                    try {
                        Paths.get(url.toURI())
                    } catch (_: Exception) {
                        continue
                    }
                if (Files.isDirectory(p)) {
                    continue
                }
                if (Files.isRegularFile(p)) {
                    val a = Files.readAttributes(p, BasicFileAttributes::class.java)
                    return Resource.FileResource(p, a.size(), a.lastModifiedTime().toMillis())
                }
                continue
            }
            val conn =
                try {
                    url.openConnection()
                } catch (_: IOException) {
                    continue
                }
            val length = conn.contentLengthLong
            if (length < 0) continue
            // A directory entry inside a jar has length 0 and a name ending with '/'
            if (name.endsWith("/")) continue
            if (length == 0L && loader.getResource("$name/") != null) continue
            return Resource.UrlResource(url, name.substringAfterLast('/'), length, conn.lastModified)
        }
        return null
    }

    fun serve(ctx: KalinContext, resource: Resource) {
        when (resource) {
            is Resource.DirectoryRedirect -> {
                ctx.header(Header.LOCATION, resource.location)
                ctx.status(HttpStatus.MOVED_PERMANENTLY)
                return
            }
            is Resource.Directory -> {
                config.headers.forEach { (k, v) -> ctx.header(k, v) }
                ctx.html(DirectoryListing.render(ctx.path(), resource.path, config.serveHiddenFiles))
                return
            }
            else -> {}
        }
        config.headers.forEach { (k, v) -> ctx.header(k, v) }
        val mime = MimeTypes.withCharset(MimeTypes.forFileName(resource.name, config.mimeTypes.extensionToMimeType))
        ctx.contentType(mime)
        // Decided before the conditional checks so that a 304 also carries `Vary: Accept-Encoding`
        val encoding = ctx.compressionFor(mime, resource.size)
        // Strong validators differ per content coding: "tag" (identity), "tag-gzip", "tag-deflate"
        val baseTag =
            if (config.conditionalRequests)
                "${java.lang.Long.toHexString(resource.lastModified)}-${java.lang.Long.toHexString(resource.size)}"
            else null
        val identityTag = baseTag?.let { "\"$it\"" }
        val etag = baseTag?.let { if (encoding == null) identityTag else "\"$it-${encoding.typeName}\"" }
        if (baseTag != null && etag != null) {
            ctx.header(Header.ETAG, etag)
            if (resource.lastModified > 0) ctx.header(Header.LAST_MODIFIED, HttpDates.format(resource.lastModified))
            if (notModified(ctx, baseTag, resource.lastModified)) {
                ctx.status(HttpStatus.NOT_MODIFIED)
                ctx.removeHeader(Header.CONTENT_TYPE)
                ctx.resultEmpty()
                return
            }
        }
        ctx.header(Header.ACCEPT_RANGES, "bytes")
        // RFC 9110 §14.2: Range applies to GET only
        val range = if (ctx.method() == HandlerType.GET) ctx.header(Header.RANGE) else null
        // Partial responses are never compressed, so If-Range is checked against the identity tag
        if (range != null && ifRangeMatches(ctx, identityTag, resource.lastModified)) {
            when (val parsed = parseRange(range, resource.size)) {
                null -> {} // invalid or several satisfiable ranges: serve the full resource
                UNSATISFIABLE -> {
                    ctx.header(Header.CONTENT_RANGE, "bytes */${resource.size}")
                    ctx.status(HttpStatus.RANGE_NOT_SATISFIABLE)
                    ctx.removeHeader(Header.CONTENT_TYPE)
                    ctx.resultEmpty()
                    return
                }
                else -> {
                    val (start, end) = parsed
                    ctx.status(HttpStatus.PARTIAL_CONTENT)
                    identityTag?.let { ctx.header(Header.ETAG, it) }
                    ctx.header(Header.CONTENT_RANGE, "bytes $start-$end/${resource.size}")
                    ctx.disableCompression()
                    ctx.resultResource(resource, start, end - start + 1)
                    return
                }
            }
        }
        // Precompressed sibling (file.gz)
        if (config.precompressedFiles && resource is Resource.FileResource && encoding == CompressionType.GZIP) {
            val gz = resource.path.resolveSibling(resource.path.fileName.toString() + ".gz")
            if (Files.isRegularFile(gz, LinkOption.NOFOLLOW_LINKS)) {
                ctx.header(Header.CONTENT_ENCODING, "gzip")
                ctx.addVaryAcceptEncoding()
                val size = Files.size(gz)
                ctx.resultResource(Resource.FileResource(gz, size, resource.lastModified), 0, size)
                return
            }
        }
        if (encoding != null && (config.precompressMaxSize == 0L || resource.size <= config.precompressMaxSize)) {
            val key = CompressedCache.Key(resource.cacheId(), resource.size, resource.lastModified, encoding)
            val bytes =
                cache.getOrCompute(key) { resource.open().use { ctx.compressBytes(encoding, it.readAllBytes()) } }
            ctx.header(Header.CONTENT_ENCODING, encoding.typeName)
            ctx.addVaryAcceptEncoding()
            ctx.disableCompression()
            ctx.result(bytes)
            return
        }
        if (encoding == null) ctx.disableCompression()
        ctx.resultResource(resource, 0, resource.size)
    }

    private fun Resource.cacheId(): String =
        when (this) {
            is Resource.FileResource -> path.toString()
            is Resource.UrlResource -> url.toString()
            else -> name
        }

    /** True if a validator matches; If-None-Match accepts the tag of any content coding of the resource. */
    private fun notModified(ctx: Context, baseTag: String, lastModified: Long): Boolean {
        val inm = ctx.header(Header.IF_NONE_MATCH)
        if (inm != null) {
            return inm.split(',').any {
                val tag = it.trim()
                if (tag == "*") return@any true
                val quoted = tag.removePrefix("W/")
                if (quoted.length < 2 || !quoted.startsWith('"') || !quoted.endsWith('"')) return@any false
                val opaque = quoted.substring(1, quoted.length - 1)
                opaque == baseTag || CompressionType.entries.any { type -> opaque == "$baseTag-${type.typeName}" }
            }
        }
        val ims = ctx.header(Header.IF_MODIFIED_SINCE) ?: return false
        val since = HttpDates.parse(ims) ?: return false
        return lastModified > 0 && lastModified / 1000 <= since / 1000
    }

    private fun ifRangeMatches(ctx: Context, etag: String?, lastModified: Long): Boolean {
        val ifRange = ctx.header(Header.IF_RANGE) ?: return true
        if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) return etag != null && ifRange == etag
        val date = HttpDates.parse(ifRange) ?: return false
        return lastModified / 1000 == date / 1000
    }

    /**
     * Parses a `Range` header (RFC 9110 §14.1.2). Returns the single satisfiable range (end clamped to the last byte),
     * [UNSATISFIABLE] if no range is satisfiable, or null to ignore the header: syntactically invalid (including `last
     * < first` and other units) or, like nginx without multipart support, more than one satisfiable range.
     */
    private fun parseRange(header: String, size: Long): Pair<Long, Long>? {
        val eq = header.indexOf('=')
        if (eq < 0 || !header.substring(0, eq).equals("bytes", ignoreCase = true)) return null
        val satisfiable = ArrayList<Pair<Long, Long>>(1)
        var specs = 0
        for (element in header.substring(eq + 1).split(',')) {
            val spec = element.trim(' ', '\t')
            if (spec.isEmpty()) continue // empty list elements are allowed (RFC 9110 §5.6.1)
            specs++
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val first = spec.substring(0, dash)
            val last = spec.substring(dash + 1)
            if (first.isEmpty()) {
                val suffix = digits(last) ?: return null
                if (suffix > 0 && size > 0) satisfiable.add(maxOf(0, size - suffix) to size - 1)
            } else {
                val start = digits(first) ?: return null
                val end = if (last.isEmpty()) Long.MAX_VALUE else digits(last) ?: return null
                if (end < start) return null
                if (start < size) satisfiable.add(start to minOf(end, size - 1))
            }
        }
        return when {
            specs == 0 -> null
            satisfiable.isEmpty() -> UNSATISFIABLE
            satisfiable.size == 1 -> satisfiable[0]
            else -> null
        }
    }

    /** Parses `1*DIGIT`, saturating at [Long.MAX_VALUE]; null if [s] is not all ASCII digits. */
    private fun digits(s: String): Long? {
        if (s.isEmpty()) return null
        var value = 0L
        for (c in s) {
            if (c !in '0'..'9') return null
            value = if (value > (Long.MAX_VALUE - (c - '0')) / 10) Long.MAX_VALUE else value * 10 + (c - '0')
        }
        return value
    }

    private companion object {
        val UNSATISFIABLE: Pair<Long, Long> = -1L to -1L
    }
}

/**
 * Resolves the request path [rel] below [root], or null if it leaves the root or is not a valid path on this file
 * system (on Windows `:`, `<`, `>`, `|`, `*` and `?`), so such requests are not found instead of failing.
 */
internal fun resolveBelow(root: Path, rel: String): Path? {
    val file =
        try {
            root.resolve(rel.trimStart('/')).normalize()
        } catch (_: InvalidPathException) {
            return null
        }
    return if (file.startsWith(root)) file else null
}

/** A small LRU cache of compressed static resources. */
internal object CompressedCache {
    data class Key(val id: String, val size: Long, val lastModified: Long, val encoding: CompressionType)

    private const val MAX_BYTES = 32L * 1024 * 1024
    private var total = 0L
    private val map = LinkedHashMap<Key, ByteArray>(64, 0.75f, true)

    fun getOrCompute(key: Key, compute: () -> ByteArray): ByteArray {
        synchronized(this) {
            map[key]?.let {
                return it
            }
        }
        val bytes = compute()
        synchronized(this) {
            if (bytes.size > MAX_BYTES / 4) return bytes
            map.put(key, bytes)?.let { total -= it.size }
            total += bytes.size
            val it = map.entries.iterator()
            while (total > MAX_BYTES && it.hasNext()) {
                val e = it.next()
                total -= e.value.size
                it.remove()
            }
        }
        return bytes
    }
}

/** Renders HTML directory listings. */
internal object DirectoryListing {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    fun render(requestPath: String, dir: Path, showHidden: Boolean): String {
        val entries =
            Files.list(dir)
                .use { stream ->
                    stream
                        .filter { showHidden || !it.fileName.toString().startsWith(".") }
                        .map { p ->
                            val attrs =
                                try {
                                    Files.readAttributes(p, BasicFileAttributes::class.java)
                                } catch (_: IOException) {
                                    null
                                }
                            Triple(p.fileName.toString(), attrs?.isDirectory == true, attrs)
                        }
                        .toList()
                }
                .sortedWith(
                    compareBy<Triple<String, Boolean, BasicFileAttributes?>>({ !it.second }, { it.first.lowercase() })
                )
        val title = escape(URLDecoder.decode(requestPath.replace("+", "%2B"), Charsets.UTF_8))
        return buildString {
            append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>Index of ").append(title).append("</title><style>")
            append("body{font-family:system-ui,sans-serif;margin:2rem;color:#222;background:#fff}")
            append("table{border-collapse:collapse;width:100%;max-width:60rem}")
            append("th,td{text-align:left;padding:.3rem .8rem;border-bottom:1px solid #eee}")
            append("td.n{text-align:right;font-variant-numeric:tabular-nums}a{text-decoration:none;color:#0645ad}")
            append(
                "@media (prefers-color-scheme:dark){body{background:#111;color:#ddd}a{color:#8ab4f8}th,td{border-color:#333}}"
            )
            append("</style></head><body><h1>Index of ").append(title).append("</h1><table>")
            append("<tr><th>Name</th><th>Last modified (UTC)</th><th class=\"n\">Size</th></tr>")
            if (requestPath != "/") append("<tr><td><a href=\"../\">../</a></td><td></td><td></td></tr>")
            for ((name, isDir, attrs) in entries) {
                val href = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20") + if (isDir) "/" else ""
                append("<tr><td><a href=\"")
                    .append(href)
                    .append("\">")
                    .append(escape(name))
                    .append(if (isDir) "/" else "")
                append("</a></td><td>")
                attrs?.let { append(dateFormat.format(Instant.ofEpochMilli(it.lastModifiedTime().toMillis()))) }
                append("</td><td class=\"n\">")
                if (!isDir && attrs != null) append(humanSize(attrs.size()))
                append("</td></tr>")
            }
            append("</table></body></html>\n")
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(java.util.Locale.ROOT, v, units[i])
    }

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

/** Single page application roots: unmatched HTML requests below a path get one page. */
public class SinglePageHandler {
    private val entries = LinkedHashMap<String, Handler>()

    /** Serves [handler] for unmatched HTML requests below [hostedPath]. */
    public fun add(hostedPath: String, handler: Handler) {
        entries["/" + hostedPath.trim('/')] = handler
    }

    private fun find(ctx: Context): Handler? {
        if (ctx.header(Header.ACCEPT)?.contains("text/html") != true) return null
        val path = (ctx as KalinContext).requestUri()
        return entries.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (hosted, _) -> hosted == "/" || path == hosted || path.startsWith("$hosted/") }
            ?.value
    }

    /** True if a page would be served. */
    public fun canHandle(ctx: Context): Boolean = find(ctx) != null

    /** Serves the page if a root matches. */
    public fun handle(ctx: Context): Boolean {
        val handler = find(ctx) ?: return false
        handler.handle(ctx)
        return true
    }
}
