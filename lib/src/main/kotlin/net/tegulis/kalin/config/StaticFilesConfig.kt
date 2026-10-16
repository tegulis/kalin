package net.tegulis.kalin.config

import java.util.function.Consumer
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.staticfiles.Location
import net.tegulis.kalin.staticfiles.StaticFileConfig

/** Static file locations. */
public class StaticFilesConfig internal constructor() {
    internal val configs = ArrayList<StaticFileConfig>()

    /** Serves [directory] (from [location]) at `/`. */
    @JvmOverloads
    public fun add(directory: String, location: Location = Location.CLASSPATH) {
        add {
            it.directory = directory
            it.location = location
        }
    }

    /** Adds a configured static file location. */
    public fun add(userConfig: Consumer<StaticFileConfig>) {
        configs.add(StaticFileConfig().also { userConfig.accept(it) })
    }

    /** Serves WebJars at `/webjars/name/version/file`. */
    public fun enableWebjars() {
        add {
            it.directory = "META-INF/resources/webjars"
            it.hostedPath = "/webjars"
            it.headers = mapOf("Cache-Control" to "max-age=31622400")
        }
    }
}

/** Single page application roots. */
public class SpaRootConfig internal constructor() {
    internal val entries = LinkedHashMap<String, Handler>()

    /** Serves the file [filePath] for unmatched HTML requests below [hostedPath]. */
    @JvmOverloads
    public fun addFile(hostedPath: String, filePath: String, location: Location = Location.CLASSPATH) {
        val content: () -> ByteArray =
            when (location) {
                Location.CLASSPATH -> {
                    val name = filePath.trimStart('/')
                    val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
                    require(loader.getResource(name) != null) { "File at '$filePath' not found on classpath" }
                    val bytes = lazy { loader.getResourceAsStream(name)!!.use { it.readAllBytes() } }
                    val supplier: () -> ByteArray = { bytes.value }
                    supplier
                }
                Location.EXTERNAL -> {
                    val path = java.nio.file.Paths.get(filePath)
                    require(java.nio.file.Files.isRegularFile(path)) { "File at '$filePath' does not exist" }
                    val supplier: () -> ByteArray = { java.nio.file.Files.readAllBytes(path) }
                    supplier
                }
            }
        entries[hostedPath] = Handler { ctx -> ctx.contentType(ContentType.HTML + ";charset=utf-8").result(content()) }
    }

    /** Runs [handler] for unmatched HTML requests below [hostedPath]. */
    public fun addHandler(hostedPath: String, handler: Handler) {
        entries[hostedPath] = handler
    }
}
