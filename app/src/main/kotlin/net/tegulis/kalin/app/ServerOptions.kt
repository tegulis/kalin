package net.tegulis.kalin.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.engine.Transport

/** Thrown for invalid command line arguments. */
class UsageException(message: String) : IllegalArgumentException(message)

/** Log output formats. */
enum class LogFormat {
    TEXT,
    JSON,
}

/** Everything `kalin-server` can be configured with. */
data class ServerOptions(
    val directory: Path = Path.of("."),
    val host: String? = null,
    val port: Int = 8080,
    val contextPath: String = "/",
    val engine: Engine = Engine.LOOM,
    val transport: Transport = Transport.AUTO,
    val indexFiles: List<String> = listOf("index.html", "index.htm"),
    val directoryListing: Boolean = true,
    val hiddenFiles: Boolean = false,
    val followSymlinks: Boolean = false,
    val spaFallback: String? = null,
    val cacheMaxAge: Long? = null,
    val compression: Boolean = true,
    val precompressed: Boolean = false,
    val cors: Boolean = false,
    val basicAuth: Pair<String, String>? = null,
    val headers: Map<String, String> = emptyMap(),
    val accessLog: Boolean = true,
    val logFormat: LogFormat = LogFormat.TEXT,
    val proxyHeaders: Boolean = false,
    val maxConnections: Int = 0,
    val idleTimeout: Duration = 30.seconds,
    val serverHeader: String? = "kalin-server",
    val showHelp: Boolean = false,
    val showVersion: Boolean = false,
) {
    companion object {
        val USAGE: String =
            """
            |Usage: kalin-server [options] [directory]
            |
            |Serves the files in a directory over HTTP/1.1 (default: the current directory).
            |
            |Options:
            |  -p, --port <port>           Port to listen on (default 8080, 0 = random)
            |  -b, --bind <host>           Address to bind (default: all interfaces)
            |  -d, --dir <directory>       Directory to serve (same as the positional argument)
            |      --context-path <path>   Serve below a path prefix, for example /files
            |      --index <files>         Comma separated index files (default index.html,index.htm)
            |      --no-listing            Do not list directories without an index file
            |      --hidden                Serve hidden files (names starting with '.')
            |      --follow-symlinks       Serve symbolic links that point outside the directory
            |      --spa <file>            Single page app: serve this file (relative to the directory)
            |                              for unknown paths requested by browsers
            |      --cache <seconds>       Cache-Control max-age for files (default: max-age=0)
            |      --no-gzip               Disable response compression
            |      --precompressed         Serve file.gz instead of file when the client accepts gzip
            |      --cors                  Allow cross-origin requests from any origin
            |      --basic-auth <user:pw>  Require HTTP basic authentication
            |  -H, --header <name:value>   Add a response header (repeatable)
            |      --proxy-headers         Trust X-Forwarded-For/-Proto/-Host and Forwarded headers
            |      --max-connections <n>   Limit open connections (default: unlimited)
            |      --idle-timeout <secs>   Close idle keep-alive connections after this time (default 30)
            |      --engine <engine>       LOOM (default) or EVENT_LOOP
            |      --transport <t>         AUTO (default), NIO or EPOLL
            |      --no-server-header      Do not send a Server header
            |  -q, --quiet                 No access log
            |      --log-format <f>        text (default) or json
            |  -v, --version               Print the version and exit
            |  -h, --help                  Print this help and exit
            """
                .trimMargin()

        /** Parses command line arguments. */
        fun parse(args: Array<String>): ServerOptions {
            var o = ServerOptions()
            var directorySet = false
            val headers = LinkedHashMap<String, String>()
            var i = 0
            fun value(name: String): String {
                if (i + 1 >= args.size) throw UsageException("Missing value for $name")
                return args[++i]
            }
            fun int(name: String, text: String, range: IntRange): Int =
                text.toIntOrNull()?.takeIf { it in range } ?: throw UsageException("Invalid value for $name: $text")
            while (i < args.size) {
                val raw = args[i]
                val (arg, inline) =
                    if (raw.startsWith("--") && raw.contains('=')) raw.substringBefore('=') to raw.substringAfter('=')
                    else raw to null
                fun v(): String = inline ?: value(arg)
                when (arg) {
                    "-p",
                    "--port" -> o = o.copy(port = int(arg, v(), 0..65535))
                    "-b",
                    "--bind",
                    "--host" -> o = o.copy(host = v())
                    "-d",
                    "--dir" -> {
                        o = o.copy(directory = Path.of(v()))
                        directorySet = true
                    }
                    "--context-path" -> o = o.copy(contextPath = v())
                    "--index" -> o = o.copy(indexFiles = v().split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    "--listing" -> o = o.copy(directoryListing = true)
                    "--no-listing" -> o = o.copy(directoryListing = false)
                    "--hidden" -> o = o.copy(hiddenFiles = true)
                    "--follow-symlinks" -> o = o.copy(followSymlinks = true)
                    "--spa" -> o = o.copy(spaFallback = v())
                    "--cache" ->
                        o =
                            o.copy(
                                cacheMaxAge =
                                    v().toLongOrNull()?.takeIf { it >= 0 }
                                        ?: throw UsageException("Invalid value for --cache")
                            )
                    "--gzip" -> o = o.copy(compression = true)
                    "--no-gzip" -> o = o.copy(compression = false)
                    "--precompressed" -> o = o.copy(precompressed = true)
                    "--cors" -> o = o.copy(cors = true)
                    "--basic-auth" -> {
                        val text = v()
                        val colon = text.indexOf(':')
                        if (colon <= 0) throw UsageException("--basic-auth expects user:password")
                        o = o.copy(basicAuth = text.substring(0, colon) to text.substring(colon + 1))
                    }
                    "-H",
                    "--header" -> {
                        val text = v()
                        val colon = text.indexOf(':')
                        if (colon <= 0) throw UsageException("--header expects 'Name: value'")
                        headers[text.substring(0, colon).trim()] = text.substring(colon + 1).trim()
                    }
                    "--proxy-headers" -> o = o.copy(proxyHeaders = true)
                    "--max-connections" -> o = o.copy(maxConnections = int(arg, v(), 0..Int.MAX_VALUE))
                    "--idle-timeout" -> o = o.copy(idleTimeout = int(arg, v(), 1..86_400).seconds)
                    "--engine" -> o = o.copy(engine = enumValue<Engine>(arg, v()))
                    "--transport" -> o = o.copy(transport = enumValue<Transport>(arg, v()))
                    "--no-server-header" -> o = o.copy(serverHeader = null)
                    "-q",
                    "--quiet" -> o = o.copy(accessLog = false)
                    "--log-format" -> o = o.copy(logFormat = enumValue<LogFormat>(arg, v()))
                    "-v",
                    "--version" -> o = o.copy(showVersion = true)
                    "-h",
                    "--help" -> o = o.copy(showHelp = true)
                    else -> {
                        if (arg.startsWith("-")) throw UsageException("Unknown option: $arg")
                        if (directorySet) throw UsageException("Only one directory can be served")
                        o = o.copy(directory = Path.of(arg))
                        directorySet = true
                    }
                }
                i++
            }
            return o.copy(headers = headers)
        }

        private inline fun <reified E : Enum<E>> enumValue(name: String, text: String): E =
            enumValues<E>().firstOrNull { it.name.equals(text.replace('-', '_'), ignoreCase = true) }
                ?: throw UsageException(
                    "Invalid value for $name: $text (one of ${enumValues<E>().joinToString { it.name.lowercase() }})"
                )
    }

    /** Validates options that depend on the file system. */
    fun validate(): ServerOptions {
        if (!Files.isDirectory(directory)) throw UsageException("Not a directory: $directory")
        spaFallback?.let {
            if (!Files.isRegularFile(directory.resolve(it))) throw UsageException("SPA file not found: $it")
        }
        return this
    }
}
