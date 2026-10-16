package net.tegulis.kalin.router

import java.net.URLDecoder
import net.tegulis.kalin.config.RouterConfig

/** Thrown for invalid path patterns. */
public open class InvalidPathException(message: String) : IllegalArgumentException(message)

/** A path segment has unbalanced `{}` or `<>` brackets. */
public class MissingBracketsException(segment: String, public val path: String) :
    InvalidPathException("This segment '$segment' is missing some brackets! Found in path '$path'")

/** A wildcard directly touches a path parameter bracket, which is ambiguous. */
public class WildcardBracketAdjacentException(segment: String, public val path: String) :
    InvalidPathException(
        "Wildcard and a path parameter bracket are adjacent in segment '$segment' of path '$path'. This is forbidden"
    )

/** The same path parameter name is used twice. */
public class ParameterNamesNotUniqueException(public val path: String) :
    InvalidPathException("Duplicate path param names detected! This is forbidden. Found in path '$path'")

/**
 * Matches request paths against a Javalin-style path pattern:
 * - `{name}`: a path parameter without slashes
 * - `<name>`: a path parameter that may contain slashes
 * - `*`: a wildcard (any characters, value not extractable)
 * - a trailing `}*` or `>*` also matches every sub path
 *
 * Static patterns are matched by normalized string comparison, dynamic ones by a regex.
 */
internal class PathMatcher(val rawPath: String, private val config: RouterConfig) {
    private sealed interface Part {
        data class Literal(val text: String) : Part

        data class Param(val name: String, val slashes: Boolean) : Part

        data object Wildcard : Part
    }

    init {
        if (rawPath.contains("/:")) {
            throw InvalidPathException(
                "Path '$rawPath' invalid - Kalin (like Javalin 4+) uses '{param}' instead of ':param'."
            )
        }
    }

    private val matchEverySubPath = rawPath.endsWith(">*") || rawPath.endsWith("}*")
    private val path = if (matchEverySubPath) rawPath.removeSuffix("*") else rawPath
    private val segments: List<List<Part>> = path.split('/').filter { it.isNotEmpty() }.map { parseSegment(it) }

    val pathParamNames: List<String> =
        segments
            .flatten()
            .filterIsInstance<Part.Param>()
            .map { it.name }
            .also { names -> if (names.toSet().size != names.size) throw ParameterNamesNotUniqueException(rawPath) }

    /** True if the pattern has no parameters or wildcards. */
    val isStatic: Boolean = !matchEverySubPath && segments.all { seg -> seg.all { it is Part.Literal } }

    /** The normalized lookup key for static patterns. */
    val staticKey: String? = if (isStatic) normalize(rawPath, config) else null

    private val regexes: List<Regex> =
        if (isStatic) emptyList()
        else
            listOfNotNull(
                regex(segments),
                if (matchEverySubPath) regex(segments + listOf(listOf(Part.Wildcard))) else null,
            )

    fun matches(requestPath: String): Boolean =
        if (isStatic) normalize(requestPath, config) == staticKey else regexes.any { it.matches(requestPath) }

    /** Extracts the (percent-decoded) path parameters; empty if none. */
    fun extractPathParams(requestPath: String): Map<String, String> {
        if (pathParamNames.isEmpty()) return emptyMap()
        for (r in regexes) {
            val m = r.matchEntire(requestPath) ?: continue
            val result = HashMap<String, String>(pathParamNames.size * 2)
            for (i in pathParamNames.indices) result[pathParamNames[i]] = urlDecodePath(m.groupValues[i + 1])
            return result
        }
        return emptyMap()
    }

    private fun regex(segs: List<List<Part>>): Regex {
        val slash = if (config.treatMultipleSlashesAsSingleSlash) "/+" else "/"
        val suffix =
            when {
                rawPath == "/" -> ""
                config.treatMultipleSlashesAsSingleSlash && config.ignoreTrailingSlashes -> "/*"
                config.treatMultipleSlashesAsSingleSlash && rawPath.endsWith("/") -> "/+"
                config.ignoreTrailingSlashes -> "/?"
                rawPath.endsWith("/") -> "/"
                else -> ""
            }
        val body =
            segs.joinToString(slash) { seg ->
                seg.joinToString("") { part ->
                    when (part) {
                        is Part.Literal -> Regex.escape(part.text)
                        is Part.Param -> if (part.slashes) "(.+?)" else "([^/]+?)"
                        Part.Wildcard -> ".*?"
                    }
                }
            }
        val prefix = if (config.treatMultipleSlashesAsSingleSlash) "^/+" else "^/"
        val options = if (config.caseInsensitiveRoutes) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex("$prefix$body$suffix$", options)
    }

    private fun parseSegment(segment: String): List<Part> {
        val parts = ArrayList<Part>()
        val literal = StringBuilder()
        var i = 0
        fun flush() {
            if (literal.isNotEmpty()) {
                parts.add(Part.Literal(literal.toString()))
                literal.clear()
            }
        }
        while (i < segment.length) {
            val c = segment[i]
            when (c) {
                '{',
                '<' -> {
                    val close = if (c == '{') '}' else '>'
                    val end = segment.indexOf(close, i + 1)
                    if (end < 0) throw MissingBracketsException(segment, rawPath)
                    val name = segment.substring(i + 1, end)
                    if (name.isEmpty() || name.any { it in "{}<>/" }) throw MissingBracketsException(segment, rawPath)
                    flush()
                    if (parts.lastOrNull() is Part.Param)
                        throw InvalidPathException("Adjacent path params without delimiter in '$rawPath'")
                    if (parts.lastOrNull() == Part.Wildcard) throw WildcardBracketAdjacentException(segment, rawPath)
                    parts.add(Part.Param(name, slashes = c == '<'))
                    i = end + 1
                }
                '}',
                '>' -> throw MissingBracketsException(segment, rawPath)
                '*' -> {
                    flush()
                    if (parts.lastOrNull() is Part.Param && !(matchEverySubPath && i == segment.length - 1))
                        throw WildcardBracketAdjacentException(segment, rawPath)
                    parts.add(Part.Wildcard)
                    i++
                }
                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flush()
        return parts
    }

    companion object {
        /** Normalizes a path for static matching according to the router configuration. */
        fun normalize(path: String, config: RouterConfig): String {
            var p = path
            if (config.treatMultipleSlashesAsSingleSlash && p.contains("//")) p = p.replace(Regex("/{2,}"), "/")
            if (config.ignoreTrailingSlashes && p.length > 1 && p.endsWith('/')) p = p.trimEnd('/').ifEmpty { "/" }
            if (config.caseInsensitiveRoutes) p = p.lowercase()
            return p
        }

        /** Percent-decodes a path parameter, keeping literal `+` characters. */
        fun urlDecodePath(s: String): String {
            if (s.indexOf('%') < 0) return s
            return try {
                URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw net.tegulis.kalin.http.BadRequestResponse()
            }
        }
    }
}
